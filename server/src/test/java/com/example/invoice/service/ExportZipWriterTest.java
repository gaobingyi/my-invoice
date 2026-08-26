package com.example.invoice.service;

import com.example.invoice.entity.ExportBatch;
import com.example.invoice.entity.Invoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExportZipWriterTest {

    @TempDir
    Path tmp;

    private ExportService serviceWithDir(Path dir) {
        return new ExportService(null, null, null, new InvoiceService(null, null, null, dir.toString()));
    }

    private Invoice invoice(long id, String number, String seller, String filePath) {
        Invoice inv = new Invoice();
        inv.setId(id);
        inv.setInvoiceNumber(number);
        inv.setSellerName(seller);
        inv.setTotalWithTax(new BigDecimal("109.00"));
        inv.setFilePath(filePath);
        return inv;
    }

    @Test
    void packsPdfsPlusExcelAndSkipsMissingFiles() throws Exception {
        Path present = tmp.resolve("销售方甲_26322000004144614676.pdf");
        Files.writeString(present, "%PDF- fake甲");

        ExportService svc = serviceWithDir(tmp);
        ExportBatch batch = new ExportBatch();
        batch.setBatchMonth("2026-08");
        batch.setId(7L);

        List<Invoice> invoices = List.of(
                invoice(1, "26322000004144614676", "销售方甲", "销售方甲_26322000004144614676.pdf"),
                invoice(2, "UNKNOWN-00000000dead", "销售方乙", "销售方乙_UNKNOWN-00000000dead.pdf") // 文件不存在
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.writeZip(batch, invoices, out);

        // 读回：发票清单.xlsx + 存在的 PDF + 缺失清单.txt
        var names = new java.util.LinkedHashMap<String, Integer>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.put(e.getName(), zin.readAllBytes().length);
            }
        }
        assertEquals(3, names.size(), "应有 xlsx + 1 个 pdf + 缺失清单.txt，实际: " + names.keySet());
        assertTrue(names.containsKey("发票清单.xlsx"));
        assertTrue(names.containsKey("销售方甲_26322000004144614676.pdf"));
        assertTrue(names.get("销售方甲_26322000004144614676.pdf") > 0);
        assertTrue(names.containsKey("缺失清单.txt"), names.keySet().toString());
    }

    @Test
    void missingFileListingContainsInvoiceIdentity() throws Exception {
        ExportService svc = serviceWithDir(tmp); // 空目录 → 所有票缺失
        ExportBatch batch = new ExportBatch();
        batch.setBatchMonth("2026-08");
        List<Invoice> invoices = List.of(invoice(1, "N1", "销售方丙", "丙_N1.pdf"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.writeZip(batch, invoices, out);

        String listing = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (e.getName().equals("缺失清单.txt")) {
                    listing = new String(zin.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertNotNull(listing, "全缺失时也必须有缺失清单");
        assertTrue(listing.contains("销售方丙_N1.pdf".replace(".pdf", "")) || listing.contains("N1"),
                "清单应含发票标识: " + listing);
    }
}
