package com.example.invoice.service;

import com.example.invoice.entity.Invoice;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 导出批次用 Excel 汇总清单（.xlsx）。
 * SXSSF 流式写：容器 -Xmx256m，全内存的 XSSFWorkbook 在大批量下有 OOM 风险。
 * 列宽用固定值 —— SXSSF 下 autoSizeColumn 需开启 tracking 且逐列回扫，不值得。 */
public class InvoiceExcelGenerator {

    private static final String[] HEADERS = {
            "序号", "发票号码", "开票日期", "购买方", "销售方", "项目名称", "金额", "税额", "价税合计"
    };

    private InvoiceExcelGenerator() {}

    public static byte[] generate(List<Invoice> invoices, String batchMonth) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("发票清单");

            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("发票清单（" + batchMonth + " 批次）");
            // 标题行仅首格有值，其余留空

            Row header = sheet.createRow(1);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
                sheet.setColumnWidth(i, columnWidth(i));
            }

            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal taxAmount = BigDecimal.ZERO;
            BigDecimal totalWithTax = BigDecimal.ZERO;
            int r = 2;
            for (int i = 0; i < invoices.size(); i++) {
                Invoice inv = invoices.get(i);
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(nullSafe(inv.getInvoiceNumber()));
                row.createCell(2).setCellValue(dateText(inv.getInvoiceDate()));
                row.createCell(3).setCellValue(nullSafe(inv.getBuyerName()));
                row.createCell(4).setCellValue(nullSafe(inv.getSellerName()));
                row.createCell(5).setCellValue(nullSafe(inv.getCategory()));
                row.createCell(6).setCellValue(amountText(inv.getTotalAmount()));
                row.createCell(7).setCellValue(amountText(inv.getTaxAmount()));
                row.createCell(8).setCellValue(amountText(inv.getTotalWithTax()));
                totalAmount = add(totalAmount, inv.getTotalAmount());
                taxAmount = add(taxAmount, inv.getTaxAmount());
                totalWithTax = add(totalWithTax, inv.getTotalWithTax());
            }

            Row sum = sheet.createRow(r);
            sum.createCell(0).setCellValue("合计");
            sum.createCell(6).setCellValue(amountText(zeroIfNull(totalAmount)));
            sum.createCell(7).setCellValue(amountText(zeroIfNull(taxAmount)));
            sum.createCell(8).setCellValue(amountText(zeroIfNull(totalWithTax)));

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 与列表页金额列同风格：null 显示为空，非空保留两位小数字符串。 */
    private static String amountText(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String dateText(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal add(BigDecimal acc, BigDecimal v) {
        return v == null ? acc : acc.add(v);
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int columnWidth(int col) {
        // 单位 1/20 pt（POI setColumnWidth 的 1/256 char width 近似）：中文表头/号码不折行即可
        return switch (col) {
            case 1 -> 26 * 256;   // 发票号码 20 位
            case 3, 4 -> 34 * 256; // 购/销方公司名
            case 5 -> 24 * 256;   // 项目名称
            case 6, 7, 8 -> 14 * 256; // 金额三列
            default -> 10 * 256;
        };
    }
}
