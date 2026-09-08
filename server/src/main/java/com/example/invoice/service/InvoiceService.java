package com.example.invoice.service;

import com.example.invoice.entity.Invoice;
import com.example.invoice.repository.ExportBatchItemRepository;
import com.example.invoice.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class InvoiceService {

    private static final SecureRandom RNG = new SecureRandom();

    // pocfile: 字段长度上限，与 ddl/schema.sql 的 VARCHAR 列宽对齐。
    // LLM 兜底可能返回超长字符串，入库前截断，避免 DataIntegrityViolation 被误判为重复发票。
    private static final int MAX_NAME = 128;
    private static final int MAX_TAX_ID = 20;
    private static final int MAX_NUMBER = 20;
    private static final int MAX_CATEGORY = 64;
    private static final int MAX_LIST_SIZE = 100;

    // pocfile: 业务规则：仅接受钦钦公司的发票（规则细节见 validateBuyer()）。
    private static final String REQUIRED_BUYER_NAME = "上海钦钦印刷科技有限公司";
    private static final String REQUIRED_BUYER_TAX_ID = "91310116332791646K";

    private final InvoiceRepository repository;
    private final ExportBatchItemRepository exportBatchItemRepository;
    private final InvoiceParser parser;
    private final Path uploadDir;

    public InvoiceService(InvoiceRepository repository,
                          ExportBatchItemRepository exportBatchItemRepository,
                          com.example.invoice.service.InvoiceParser parser,
                          @Value("${upload-dir:./uploads}") String uploadDir) {
        this.repository = repository;
        this.exportBatchItemRepository = exportBatchItemRepository;
        this.parser = parser;
        this.uploadDir = Paths.get(uploadDir);
    }

    public Invoice upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 PDF");
        }
        Files.createDirectories(uploadDir);

        // pocfile: store to a temp name first because seller/invoice-number are only known
        // after parsing; rename to <seller>_<number>.<ext> afterwards.
        // finally 统一清理 tmp：copy/parse/move 任一失败都不泄漏临时文件；成功后 tmp 已被
        // move 走，deleteIfExists 是空操作。
        Path tmp = uploadDir.resolve(UUID.randomUUID() + ".pdf");
        try {
            Files.copy(file.getInputStream(), tmp, StandardCopyOption.REPLACE_EXISTING);
            ParsedInvoice p;
            try {
                p = parser.parse(tmp);
            } catch (IOException e) {
                // pocfile: 文件名是 .pdf 但内容不是（或损坏）时 PDFBox 抛 IOException，
                // 归类为用户输入问题 → 400，而非服务器错误 500。
                throw new IllegalArgumentException("无法解析 PDF 内容", e);
            }

            // 业务校验须在 LLM 兜底后的最终结果上进行（规则见 validateBuyer）。
            validateBuyer(p);

            // pocfile: duplicate invoice number should be rejected. If parsing produced no
            // number we keep the file but store a sentinel so the NOT NULL/UNIQUE columns hold.
            // The sentinel must fit the VARCHAR(20) column, hence the truncated UUID suffix.
            // 正则路径的号码恒为 20 位数字；LLM 兜底可能幻觉出超长值 —— 截断会让两张共享
            // 20 字符前缀的不同票在 uk_invoice_number 上相撞（合法上传被误判 409），且入库
            // 值与文件名同 PDF 印刷值不一致。所以超长号码视为未解析出，走 UNKNOWN 哨兵。
            String parsedNumber = p.invoiceNumber();
            String number = parsedNumber != null && parsedNumber.length() <= MAX_NUMBER ? parsedNumber : null;
            // pocfile: 12 hex chars = 48 bits CSPRNG, plenty for sentinel uniqueness.
            // UUID.randomUUID().toString() 全 32 字符再 substring 是浪费：每次分配 + 格式化。
            String sentinel = number != null ? null
                    : "UNKNOWN-" + String.format("%012x", RNG.nextLong() & 0xFFFFFFFFFFFFL);

            String filename = buildFilename(p, name, number != null ? number : sentinel);
            Path dest = uploadDir.resolve(filename);
            if (!dest.equals(tmp)) {
                // pocfile: dest 上若有同名文件，先查 DB 区分"真重复"和"孤儿文件"。
                // 孤儿来源：JVM 在 Files.move 后、repository.save 前崩溃；或 save() 抛非 UNIQUE
                // 的 DataIntegrityViolation（catch 块不删 dest）。这俩情况都让 dest 留在磁盘但
                // 无对应行 —— 放任会永久屏蔽同号重传。做法：DB 无该号则视为孤儿，删了重试。
                String dbKey = number != null ? number : sentinel;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        Files.move(tmp, dest);
                        break;
                    } catch (FileAlreadyExistsException e) {
                        if (repository.existsByInvoiceNumber(dbKey)) {
                            // 真重复：DB 已有同号行，dest 上的文件就是它的源 PDF，碰不得。
                            throw new DuplicateInvoiceException(number != null ? number : "UNKNOWN");
                        }
                        // 孤儿文件：DB 没对应行，删了重试。第二次循环 Files.move 必然成功
                        // （除非并发另一进程又写进来，那会被下一次 try 拦下再判）。
                        Files.deleteIfExists(dest);
                        if (attempt == 1) {
                            // 防御：连续两次都进 catch 但都没 DB 行，理论不该发生。
                            throw new IllegalStateException(
                                    "无法清理孤儿文件: " + dest + " (DB 无 " + dbKey + ")", e);
                        }
                    }
                }
            }

            // pocfile: 入库前截断字符串字段到列宽（LLM 幻觉超长值不再触发 DB 约束异常）。
            // 发票号码不在此列：上面已改为非法即哨兵，绝不截断唯一键。
            Invoice inv = new Invoice();
            inv.setInvoiceNumber(number != null ? number : sentinel);
            inv.setInvoiceDate(p.invoiceDate());
            inv.setBuyerName(truncate(p.buyerName(), MAX_NAME));
            inv.setBuyerTaxId(truncate(p.buyerTaxId(), MAX_TAX_ID));
            inv.setSellerName(truncate(p.sellerName(), MAX_NAME));
            inv.setSellerTaxId(truncate(p.sellerTaxId(), MAX_TAX_ID));
            inv.setCategory(truncate(p.category(), MAX_CATEGORY));
            inv.setTotalAmount(p.totalAmount());
            inv.setTaxAmount(p.taxAmount());
            inv.setTotalWithTax(p.totalWithTax());
            inv.setFilePath(uploadDir.relativize(dest).toString());

            try {
                return repository.save(inv);
            } catch (DataIntegrityViolationException e) {
                // pocfile: 只有唯一键冲突才删除刚 move 过来的副本——此时存活行指向同一文件名，
                // 新副本与旧文件内容相同（同一张票重复上传），删掉新副本不留孤儿。其他约束失败
                // （如 NOT NULL）不是重复上传：dest 上是我们唯一的文件副本，删了它 DB 行虽未建成，
                // 但若是修复场景（旧行文件已丢）会毁掉唯一恢复机会 —— 如实抛 500 并保留文件，
                // 用户重传即可自愈。
                if (isDuplicateKey(e)) {
                    Files.deleteIfExists(dest);
                    // pocfile: two concurrent unparseable uploads collide on the same dest file;
                    // both are UNKNOWN-numbered so keep the first file and 409 the second.
                    throw new DuplicateInvoiceException(number != null ? number : "UNKNOWN");
                }
                throw e;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Page<Invoice> list(int page, int size, Boolean used, String keyword) {
        // pocfile: 防 size 无上限把全表一次拉进内存；page/size 越界也钳制到合法范围。
        if (page < 0) page = 0;
        size = Math.min(Math.max(size, 1), MAX_LIST_SIZE);
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (!hasKeyword && used == null) {
            return repository.findAll(pageable);
        }
        if (!hasKeyword) {
            return repository.findByUsed(used, pageable);
        }
        // 关键词模糊匹配：销售方/购买方/发票号码/项目名称 任一命中（SQLite LIKE 对中文按原文匹配）
        // LIKE 通配符转义：用户输入的 %/_ 视为字面量（分类含 * 测过，但用户仍可能搜 %/_）
        String escaped = keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String kw = "%" + escaped + "%";
        Specification<Invoice> spec = (root, query, cb) -> cb.or(
                cb.like(root.get("sellerName"), kw, '\\'),
                cb.like(root.get("buyerName"), kw, '\\'),
                cb.like(root.get("invoiceNumber"), kw, '\\'),
                cb.like(root.get("category"), kw, '\\')
        );
        if (used != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("used"), used));
        }
        return repository.findAll(spec, pageable);
    }

    public Path resolveFile(Long id) {
        Invoice inv = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + id));
        return safePath(inv.getFilePath());
    }

    /** 导出打包用：按已加载的实体解析磁盘路径。文件不存在返回 null（writeZip 记入缺失
     * 清单而非中断整包），路径非法仍抛 IllegalArgumentException（DB 被改的防御）。 */
    public Path resolveFileOrNull(Invoice inv) {
        try {
            Path p = safePath(inv.getFilePath());
            return Files.exists(p) ? p : null;
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }

    /** 批量解析：一次查询取回实体再逐个 safePath（防穿越逻辑与单张版一致）。 */
    public Map<Long, Path> resolveFiles(Collection<Long> ids) {
        Map<Long, Path> out = new LinkedHashMap<>();
        for (Invoice inv : repository.findAllById(ids)) {
            Path p = safePath(inv.getFilePath());
            if (Files.exists(p)) {
                out.put(inv.getId(), p);
            }
        }
        return out;
    }

    private String buildFilename(ParsedInvoice p, String originalName, String resolvedNumber) {
        String seller = p.sellerName() == null ? "UNKNOWN" : p.sellerName();
        // pocfile: resolvedNumber 已是入库值（合法号码或 UNKNOWN 哨兵），文件名与 DB 永远一致。
        // strip path separators and OS-reserved characters so the filename can never escape
        // uploadDir or break the filesystem.
        String safe = (seller + "_" + resolvedNumber).replaceAll("[\\\\/:*?\"<>|\\r\\n\\t ]+", "_");
        String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : ".pdf";
        return safe + ext;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    /** 业务校验：购买方必须是钦钦公司（名称 + 信用代码都匹配）。null（解析失败、LLM 兜底
     * 也没补上）视为不符，同样拒绝 —— 保证入库的每张票确认购买方正确。消息带上解析到的值，
     * 用户能区分"解析失败"与"真买到别家票"。 */
    static void validateBuyer(ParsedInvoice p) {
        String name = p.buyerName() == null ? "" : p.buyerName().trim();
        String taxId = p.buyerTaxId() == null ? "" : p.buyerTaxId().trim();
        if (REQUIRED_BUYER_NAME.equals(name) && REQUIRED_BUYER_TAX_ID.equals(taxId)) {
            return;
        }
        throw new IllegalArgumentException(
                "购买方校验失败：解析到购买方 [" + name + "]，信用代码 [" + taxId + "]；"
                        + "本系统仅接受购买方为「" + REQUIRED_BUYER_NAME + "」（信用代码 "
                        + REQUIRED_BUYER_TAX_ID + "）的发票");
    }

    private static boolean isDuplicateKey(DataIntegrityViolationException e) {
        // Xerial SQLite JDBC 唯一键冲突：vendor code 恒为 19（SQLITE_CONSTRAINT），message 含
        // "UNIQUE constraint failed: <table>.<column>"。SQLState 23000 与 NOT NULL/CHECK 共享，
        // 不可单用作判据。遍历 cause 链取根 SQLException，按「错误码 + 关键字子串」双判据，
        // 与驱动版本/服务端语言无关。
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se && se.getErrorCode() == 19) {
                String m = se.getMessage();
                if (m != null && m.toLowerCase().contains("unique")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 校验 DB 里的相对路径不会逃出 uploadDir（防御直接改库的越权路径）。 */
    private Path safePath(String relative) {
        Path p = uploadDir.resolve(relative).normalize();
        if (!p.startsWith(uploadDir.normalize())) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return p;
    }

    public void delete(Long id) throws IOException {
        Invoice inv = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + id));
        // 删除前校验：发票已被批次引用时不可删除（需先删除批次或从批次中移除该票）
        if (exportBatchItemRepository.existsByInvoiceId(id)) {
            throw new IllegalArgumentException("该发票已被导出批次使用，不可删除。请先删除相关批次或从批次中移除该发票");
        }
        // pocfile: delete the file first — if removing the DB row then fails the user can
        // retry; deleting the row first orphanes the PDF forever when the file delete fails.
        Files.deleteIfExists(safePath(inv.getFilePath()));
        repository.delete(inv);
    }
}
