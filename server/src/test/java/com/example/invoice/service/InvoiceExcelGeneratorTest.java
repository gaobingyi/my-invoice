package com.example.invoice.service;

import com.example.invoice.entity.Invoice;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceExcelGeneratorTest {

    private Invoice invoice(long id, String number, String seller, String buyer,
                            BigDecimal totalAmount, BigDecimal tax, BigDecimal withTax) {
        Invoice inv = new Invoice();
        inv.setId(id);
        inv.setInvoiceNumber(number);
        inv.setInvoiceDate(LocalDate.of(2026, 7, 15));
        inv.setBuyerName(buyer);
        inv.setSellerName(seller);
        inv.setCategory("*餐饮服务*餐饮服务");
        inv.setTotalAmount(totalAmount);
        inv.setTaxAmount(tax);
        inv.setTotalWithTax(withTax);
        return inv;
    }

    private static final org.apache.poi.ss.usermodel.DataFormatter FORMATTER =
            new org.apache.poi.ss.usermodel.DataFormatter();

    private static String text(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return null;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return null;
        // 序号列是数字单元格，其余是字符串 —— DataFormatter 按显示值统一取
        return FORMATTER.formatCellValue(cell);
    }

    @Test
    void writesTitleHeaderRowsAndTotals() throws Exception {
        List<Invoice> invoices = List.of(
                invoice(1, "26322000004144614676", "销售方甲", "购买方乙",
                        new BigDecimal("100.00"), new BigDecimal("9.00"), new BigDecimal("109.00")),
                invoice(2, "26317000000184677179", "销售方丙", "购买方丁",
                        new BigDecimal("50.50"), null, new BigDecimal("56.00"))
        );
        byte[] xlsx = InvoiceExcelGenerator.generate(invoices, "2026-08");

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("发票清单（2026-08 批次）", text(sheet, 0, 0));
            assertEquals("序号", text(sheet, 1, 0));
            assertEquals("价税合计", text(sheet, 1, 8));

            assertEquals("1", text(sheet, 2, 0));
            assertEquals("26322000004144614676", text(sheet, 2, 1));
            assertEquals("2026-07-15", text(sheet, 2, 2));
            assertEquals("购买方乙", text(sheet, 2, 3));
            assertEquals("销售方甲", text(sheet, 2, 4));

            // 第二行：税额为 null → 空串
            assertEquals("", text(sheet, 3, 7));

            // 合计行：金额/税额/价税合计；null 税额不计入
            assertEquals("合计", text(sheet, 4, 0));
            assertEquals("150.50", text(sheet, 4, 6));
            assertEquals("9.00", text(sheet, 4, 7));
            assertEquals("165.00", text(sheet, 4, 8));

            assertNull(sheet.getRow(5), "合计行之后不应再有数据行");
        }
    }

    @Test
    void emptyListStillProducesHeaderAndZeroTotals() throws Exception {
        byte[] xlsx = InvoiceExcelGenerator.generate(List.of(), "2026-08");
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("发票清单（2026-08 批次）", text(sheet, 0, 0));
            assertEquals("合计", text(sheet, 2, 0));
            assertEquals("0", text(sheet, 2, 8), "空批次合计应为 0 而非空串");
        }
    }
}
