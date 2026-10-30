package com.example.invoice.controller;

import com.example.invoice.dto.InvoiceSummaryResponse;
import com.example.invoice.entity.Invoice;
import com.example.invoice.service.DuplicateInvoiceException;
import com.example.invoice.service.InvoiceService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Invoice upload(@RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(file);
    }

    @GetMapping
    public Page<Invoice> list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(required = false) Boolean used,
                              @RequestParam(required = false) String keyword) {
        return service.list(page, size, used, keyword);
    }

    /** 与列表同一套过滤参数的统计（张数 + 价税合计），供「未使用」合计条。 */
    @GetMapping("/summary")
    public InvoiceSummaryResponse summary(@RequestParam(required = false) Boolean used,
                                          @RequestParam(required = false) String keyword) {
        return service.summary(used, keyword);
    }

    /** 单条查询（小程序详情页兜底：eventChannel 不可用时按 id 自取）。 */
    @GetMapping("/{id}")
    public Invoice get(@PathVariable Long id) {
        return service.getInvoice(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) throws IOException {
        service.delete(id);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "inline") String disposition) {
        Path p = service.resolveFile(id);
        String filename = URLEncoder.encode(p.getFileName().toString(), StandardCharsets.UTF_8);
        String mode = "download".equals(disposition) ? "attachment" : "inline";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, mode + "; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new FileSystemResource(p));
    }
}
