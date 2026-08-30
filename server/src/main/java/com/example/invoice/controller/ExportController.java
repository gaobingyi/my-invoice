package com.example.invoice.controller;

import com.example.invoice.entity.ExportBatch;
import com.example.invoice.entity.Invoice;
import com.example.invoice.service.ExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 导出批次端点。SecurityConfig 只放行 /api/auth/**，本控制器自动要求认证。 */
@RestController
@RequestMapping("/api/export-batches")
public class ExportController {

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    public record CreateBatchRequest(List<Long> ids, String batchMonth) {}

    public record AddInvoicesRequest(List<Long> ids) {}

    /** 创建批次（标记发票已使用），返回批次记录 JSON —— 不在此处下载，
     * 前端随后跳转导出记录页手动下载，可重复。 */
    @PostMapping
    public ExportBatch create(@RequestBody CreateBatchRequest req) {
        return service.createBatch(req.ids(), req.batchMonth());
    }

    @GetMapping
    public Page<ExportBatch> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    /** 批次内发票清单（预览用）。已删除的票不在其中。 */
    @GetMapping("/{id}/invoices")
    public List<Invoice> invoices(@PathVariable Long id) {
        return service.batchInvoices(id);
    }

    /** 批次编辑：向批次添加未使用发票（标记「已使用」，重算 count/total），返回更新后的批次。 */
    @PostMapping("/{id}/invoices")
    public ExportBatch addInvoices(@PathVariable Long id, @RequestBody AddInvoicesRequest req) {
        return service.addInvoices(id, req.ids());
    }

    /** 批次编辑：从批次移除一张发票（必要时恢复「未使用」，重算 count/total），返回更新后的批次。 */
    @DeleteMapping("/{id}/invoices/{invoiceId}")
    public ExportBatch removeInvoice(@PathVariable Long id, @PathVariable Long invoiceId) {
        return service.removeInvoice(id, invoiceId);
    }

    /** 按需重新生成 ZIP：每张票一个 PDF + 发票清单.xlsx（缺失票记入缺失清单.txt）。
     * 校验全部完成后才开流 —— 流一旦开始异常无法再换成错误体。 */
    @GetMapping("/{id}/zip")
    public void zip(@PathVariable Long id, HttpServletResponse response) throws IOException {
        ExportBatch batch = service.getBatch(id);
        List<Invoice> invoices = service.batchInvoices(id);

        String filename = URLEncoder.encode("发票导出_" + batch.getBatchMonth() + "_" + id + ".zip",
                StandardCharsets.UTF_8);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + filename);
        service.writeZip(batch, invoices, response.getOutputStream());
    }

    /** 删除批次；不再被其他批次引用的发票恢复「未使用」。返回恢复数量供前端提示。 */
    @DeleteMapping("/{id}")
    public Map<String, Integer> delete(@PathVariable Long id) {
        int restored = service.deleteBatch(id);
        return Map.of("restored", restored);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
    }
}
