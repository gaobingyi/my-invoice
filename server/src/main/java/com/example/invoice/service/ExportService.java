package com.example.invoice.service;

import com.example.invoice.entity.ExportBatch;
import com.example.invoice.entity.ExportBatchItem;
import com.example.invoice.entity.Invoice;
import com.example.invoice.repository.ExportBatchItemRepository;
import com.example.invoice.repository.ExportBatchRepository;
import com.example.invoice.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final InvoiceRepository invoiceRepository;
    private final ExportBatchRepository batchRepository;
    private final ExportBatchItemRepository itemRepository;
    private final InvoiceService invoiceService;

    public ExportService(InvoiceRepository invoiceRepository,
                         ExportBatchRepository batchRepository,
                         ExportBatchItemRepository itemRepository,
                         InvoiceService invoiceService) {
        this.invoiceRepository = invoiceRepository;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.invoiceService = invoiceService;
    }

    /** 创建导出批次并标记发票已使用。校验全部通过才提交 —— 事务边界干净：
     * 创建请求不碰文件系统、不流式输出，ZIP 由批次页另行按需生成。 */
    @Transactional
    public ExportBatch createBatch(List<Long> ids, String batchMonth) {
        if (batchMonth == null || !MONTH.matcher(batchMonth).matches()) {
            throw new IllegalArgumentException("批次月份格式应为 yyyy-MM");
        }
        if (batchRepository.existsByBatchMonth(batchMonth)) {
            throw new IllegalArgumentException("该批次月份已存在，不可重复创建");
        }
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一张发票");
        }
        // 跨页勾选期间被删的票容忍消失，不硬报错；全没了才拦下
        List<Invoice> invoices = new ArrayList<>(invoiceRepository.findAllById(ids));
        if (invoices.isEmpty()) {
            throw new IllegalArgumentException("所选发票均不存在（可能已被删除）");
        }
        List<String> usedNumbers = invoices.stream()
                .filter(inv -> Boolean.TRUE.equals(inv.getUsed()))
                .map(Invoice::getInvoiceNumber)
                .toList();
        if (!usedNumbers.isEmpty()) {
            throw new IllegalArgumentException("以下发票已使用，不可重复打包：" + String.join("、", usedNumbers));
        }

        BigDecimal totalWithTax = invoices.stream()
                .map(Invoice::getTotalWithTax)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ExportBatch batch = new ExportBatch();
        batch.setBatchMonth(batchMonth);
        batch.setInvoiceCount(invoices.size());
        batch.setTotalWithTax(totalWithTax);
        batchRepository.save(batch);

        for (Invoice inv : invoices) {
            itemRepository.save(new ExportBatchItem(batch.getId(), inv.getId()));
            inv.setUsed(true);
            inv.setUsedAt(LocalDateTime.now());
        }
        return batch;
    }

    public org.springframework.data.domain.Page<ExportBatch> list(int page, int size) {
        if (page < 0) page = 0;
        size = Math.min(Math.max(size, 1), 100);
        return batchRepository.findAll(
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    public ExportBatch getBatch(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("导出批次不存在: " + id));
    }

    /** 批次包含的发票，按入库顺序。已删除的票不在返回中（writeZip 会记缺失清单）。 */
    public List<Invoice> batchInvoices(Long batchId) {
        List<Long> invoiceIds = itemRepository.findByBatchId(batchId).stream()
                .map(ExportBatchItem::getInvoiceId)
                .toList();
        return new ArrayList<>(invoiceRepository.findAllById(invoiceIds));
    }

    /** 批次编辑：向批次添加未使用发票并标记「已使用」。返回更新后的批次（count/total 已重算）。 */
    @Transactional
    public ExportBatch addInvoices(Long batchId, List<Long> ids) {
        ExportBatch batch = getBatch(batchId);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一张发票");
        }
        List<Long> distinct = ids.stream().distinct().toList();
        // 跨页勾选期间被删的票容忍消失，不硬报错；全没了才拦下
        List<Invoice> invoices = new ArrayList<>(invoiceRepository.findAllById(distinct));
        if (invoices.isEmpty()) {
            throw new IllegalArgumentException("所选发票均不存在（可能已被删除）");
        }
        List<String> inBatch = new ArrayList<>();
        List<String> usedNumbers = new ArrayList<>();
        for (Invoice inv : invoices) {
            if (itemRepository.existsByBatchIdAndInvoiceId(batchId, inv.getId())) {
                inBatch.add(inv.getInvoiceNumber());
            } else if (Boolean.TRUE.equals(inv.getUsed())) {
                usedNumbers.add(inv.getInvoiceNumber());
            }
        }
        if (!inBatch.isEmpty()) {
            throw new IllegalArgumentException("以下发票已在本批次中：" + String.join("、", inBatch));
        }
        if (!usedNumbers.isEmpty()) {
            throw new IllegalArgumentException("以下发票已使用，不可重复打包：" + String.join("、", usedNumbers));
        }
        for (Invoice inv : invoices) {
            itemRepository.save(new ExportBatchItem(batchId, inv.getId()));
            inv.setUsed(true);
            inv.setUsedAt(LocalDateTime.now());
        }
        return recalcBatch(batch);
    }

    /** 批次编辑：从批次移除一张发票；不再被其他批次引用时恢复「未使用」（同 deleteBatch）。
     * 返回更新后的批次（count/total 已重算）。 */
    @Transactional
    public ExportBatch removeInvoice(Long batchId, Long invoiceId) {
        ExportBatch batch = getBatch(batchId);
        if (!itemRepository.existsByBatchIdAndInvoiceId(batchId, invoiceId)) {
            throw new IllegalArgumentException("该发票不在本批次中，无法移除");
        }
        itemRepository.deleteByBatchIdAndInvoiceId(batchId, invoiceId);
        if (!itemRepository.existsByInvoiceIdAndBatchIdNot(invoiceId, batchId)) {
            invoiceRepository.findById(invoiceId).ifPresent(inv -> {
                if (Boolean.TRUE.equals(inv.getUsed())) {
                    inv.setUsed(false);
                    inv.setUsedAt(null);
                }
            });
        }
        return recalcBatch(batch);
    }

    /** 按批次现存 item 重算 count/total。对应发票已删的孤儿 item 不计数（正常不会出现，
     * InvoiceService.delete 会联动清理）。批次允许清空为 0 张。 */
    private ExportBatch recalcBatch(ExportBatch batch) {
        List<Invoice> invoices = batchInvoices(batch.getId());
        BigDecimal total = invoices.stream()
                .map(Invoice::getTotalWithTax)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        batch.setInvoiceCount(invoices.size());
        batch.setTotalWithTax(total);
        return batchRepository.save(batch);
    }

    /** 删除批次：关联项一并删除；不再被其他批次引用的发票恢复「未使用」。
     * 返回恢复的数量（供前端提示）。 */
    @Transactional
    public int deleteBatch(Long id) {
        ExportBatch batch = getBatch(id);
        List<ExportBatchItem> items = itemRepository.findByBatchId(id);
        int restored = 0;
        for (ExportBatchItem item : items) {
            boolean referencedElsewhere =
                    itemRepository.existsByInvoiceIdAndBatchIdNot(item.getInvoiceId(), id);
            if (!referencedElsewhere) {
                invoiceRepository.findById(item.getInvoiceId()).ifPresent(inv -> {
                    if (Boolean.TRUE.equals(inv.getUsed())) {
                        inv.setUsed(false);
                        inv.setUsedAt(null);
                    }
                });
                restored++;
            }
        }
        itemRepository.deleteByBatchId(id);
        batchRepository.delete(batch);
        return restored;
    }

    /** 把批次打成一个 ZIP 流式写入 out：每张票一个 PDF 条目 + 发票清单.xlsx。
     * 文件已被删的票跳过并记入「缺失清单.txt」。纯函数化设计便于无 DB 单元测试。 */
    public void writeZip(ExportBatch batch, List<Invoice> invoices, OutputStream out) throws IOException {
        Map<Path, String> entries = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        List<Invoice> missing = new ArrayList<>();
        for (Invoice inv : invoices) {
            Path p = invoiceService.resolveFileOrNull(inv);
            if (p == null || !Files.exists(p)) {
                missing.add(inv);
                continue;
            }
            String base = p.getFileName().toString();
            String entryName = base;
            int suffix = 2;
            while (!names.add(entryName)) {
                entryName = base.replaceFirst("(\\.[^.]+)$", "_" + suffix + "$1");
                if (suffix > 100) throw new IllegalStateException("ZIP 条目重名解析失败: " + base);
            }
            entries.put(p, entryName);
        }

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] xlsx = InvoiceExcelGenerator.generate(invoices, batch.getBatchMonth());
            zip.putNextEntry(new ZipEntry("发票清单.xlsx"));
            zip.write(xlsx);
            zip.closeEntry();

            for (Map.Entry<Path, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getValue()));
                Files.copy(e.getKey(), zip);
                zip.closeEntry();
            }

            if (!missing.isEmpty()) {
                StringBuilder sb = new StringBuilder("以下发票的 PDF 文件已不存在，未包含在本压缩包内：\n");
                for (Invoice inv : missing) {
                    sb.append("- ").append(nullSafe(inv.getSellerName())).append('_')
                            .append(inv.getInvoiceNumber()).append('\n');
                }
                zip.putNextEntry(new ZipEntry("缺失清单.txt"));
                zip.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
