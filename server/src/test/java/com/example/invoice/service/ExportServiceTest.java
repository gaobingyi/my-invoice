package com.example.invoice.service;

import com.example.invoice.entity.ExportBatch;
import com.example.invoice.entity.ExportBatchItem;
import com.example.invoice.entity.Invoice;
import com.example.invoice.repository.ExportBatchItemRepository;
import com.example.invoice.repository.ExportBatchRepository;
import com.example.invoice.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 批次编辑（添加/移除发票）的校验与 count/total 重算逻辑。
 * 仓库层用 JDK 动态代理做内存 fake（Mockito 的 Byte Buddy 在 Java 25 不可用，
 * 与全项目「纯 JUnit 单测」惯例一致），按状态断言而非调用序列。 */
class ExportServiceTest {

    private Map<Long, Invoice> invoicesById;
    private Map<Long, ExportBatch> batchesById;
    private List<ExportBatchItem> items;

    private ExportService service;

    @BeforeEach
    void setup() {
        invoicesById = new HashMap<>();
        batchesById = new HashMap<>();
        items = new ArrayList<>();
        service = new ExportService(invoiceRepo(), batchRepo(), itemRepo(), null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    private InvoiceRepository invoiceRepo() {
        return proxy(InvoiceRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findAllById" -> ((List<Long>) a[0]).stream()
                    .map(id -> invoicesById.get((Long) id)).filter(v -> v != null).toList();
            case "findById" -> Optional.ofNullable(invoicesById.get(a[0]));
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private ExportBatchRepository batchRepo() {
        return proxy(ExportBatchRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findById" -> Optional.ofNullable(batchesById.get(a[0]));
            case "existsByBatchMonth" -> batchesById.values().stream()
                    .anyMatch(b -> b.getBatchMonth().equals(a[0]));
            case "save" -> {
                ExportBatch b = (ExportBatch) a[0];
                batchesById.put(b.getId(), b);
                yield b;
            }
            case "delete" -> {
                batchesById.remove(((ExportBatch) a[0]).getId());
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private ExportBatchItemRepository itemRepo() {
        return proxy(ExportBatchItemRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findByBatchId" -> items.stream()
                    .filter(i -> i.getBatchId().equals(a[0])).toList();
            case "existsByBatchIdAndInvoiceId" -> items.stream()
                    .anyMatch(i -> i.getBatchId().equals(a[0]) && i.getInvoiceId().equals(a[1]));
            case "deleteByBatchIdAndInvoiceId" -> {
                items.removeIf(i -> i.getBatchId().equals(a[0]) && i.getInvoiceId().equals(a[1]));
                yield null;
            }
            case "existsByInvoiceIdAndBatchIdNot" -> items.stream()
                    .anyMatch(i -> i.getInvoiceId().equals(a[0]) && !i.getBatchId().equals(a[1]));
            case "save" -> {
                items.add((ExportBatchItem) a[0]);
                yield a[0];
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private Invoice invoice(long id, String number, boolean used, String total) {
        Invoice inv = new Invoice();
        inv.setId(id);
        inv.setInvoiceNumber(number);
        inv.setUsed(used);
        if (used) inv.setUsedAt(java.time.LocalDateTime.now().minusDays(1));
        if (total != null) inv.setTotalWithTax(new BigDecimal(total));
        invoicesById.put(id, inv);
        return inv;
    }

    private ExportBatch batch(long id, int count, String total) {
        ExportBatch b = new ExportBatch();
        b.setId(id);
        b.setBatchMonth("2026-08");
        b.setInvoiceCount(count);
        b.setTotalWithTax(new BigDecimal(total));
        batchesById.put(id, b);
        return b;
    }

    @Test
    void addInvoicesMarksUsedAndRecalcsCountAndTotal() {
        batch(7, 1, "100.00");
        invoice(1, "N1", true, "100.00");
        Invoice added = invoice(2, "N2", false, "109.00");
        items.add(new ExportBatchItem(7L, 1L));

        ExportBatch result = service.addInvoices(7L, List.of(2L));

        assertTrue(Boolean.TRUE.equals(added.getUsed()), "添加后应标记已使用");
        assertNotNull(added.getUsedAt());
        assertEquals(2, result.getInvoiceCount());
        assertEquals(0, new BigDecimal("209.00").compareTo(result.getTotalWithTax()));
        assertEquals(2, items.size(), "应新增一条批次-发票关联");
    }

    @Test
    void addInvoicesRejectsInvoiceAlreadyInBatch() {
        batch(7, 1, "100.00");
        invoice(2, "N2", true, "109.00");
        items.add(new ExportBatchItem(7L, 2L));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addInvoices(7L, List.of(2L)));
        assertTrue(e.getMessage().contains("已在本批次中"), e.getMessage());
        assertEquals(1, items.size(), "校验失败不应改动关联");
    }

    @Test
    void addInvoicesRejectsInvoiceUsedByOtherBatch() {
        batch(7, 0, "0");
        invoice(2, "N2", true, "109.00");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addInvoices(7L, List.of(2L)));
        assertTrue(e.getMessage().contains("已使用"), e.getMessage());
        assertTrue(items.isEmpty());
    }

    @Test
    void addInvoicesRejectsEmptySelection() {
        batch(7, 0, "0");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addInvoices(7L, List.of()));
        assertTrue(e.getMessage().contains("至少选择"), e.getMessage());
    }

    @Test
    void removeInvoiceRestoresUnusedAndRecalcsCountAndTotal() {
        batch(7, 2, "209.00");
        invoice(1, "N1", true, "100.00");
        Invoice removed = invoice(2, "N2", true, "109.00");
        items.add(new ExportBatchItem(7L, 1L));
        items.add(new ExportBatchItem(7L, 2L));

        ExportBatch result = service.removeInvoice(7L, 2L);

        assertFalse(Boolean.TRUE.equals(removed.getUsed()), "无其他批次引用时应恢复未使用");
        assertNull(removed.getUsedAt());
        assertEquals(1, result.getInvoiceCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.getTotalWithTax()));
        assertEquals(1, items.size());
    }

    @Test
    void removeInvoiceKeepsUsedWhenReferencedByOtherBatch() {
        batch(7, 2, "209.00");
        invoice(1, "N1", true, "100.00");
        Invoice removed = invoice(2, "N2", true, "109.00");
        items.add(new ExportBatchItem(7L, 1L));
        items.add(new ExportBatchItem(7L, 2L));
        items.add(new ExportBatchItem(8L, 2L)); // 另一批次也引用

        service.removeInvoice(7L, 2L);

        assertTrue(Boolean.TRUE.equals(removed.getUsed()), "被其他批次引用时保持已使用");
        assertNotNull(removed.getUsedAt());
    }

    @Test
    void removeInvoiceRejectsItemNotInBatch() {
        batch(7, 1, "100.00");
        invoice(2, "N2", false, "109.00");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.removeInvoice(7L, 2L));
        assertTrue(e.getMessage().contains("不在本批次中"), e.getMessage());
        assertFalse(Boolean.TRUE.equals(invoicesById.get(2L).getUsed()));
    }
}
