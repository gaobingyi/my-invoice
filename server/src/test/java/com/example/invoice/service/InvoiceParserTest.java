package com.example.invoice.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceParserTest {

    // 样例发票 fixture 已删除，此处仅保留不依赖 PDF 的纯文本解析测试。
    // 版式级回归（真实 PDFBox dump 布局）由 E2E 与线上验证覆盖。
    private final InvoiceParser parser = new InvoiceParser(null, null);

    @Test
    void zeroTaxInvoiceResolvesAmounts() throws Exception {
        // pocfile: when 税额 = 0.00, 金额 == 价税 and the old max/min heuristic dropped
        // every value equal to max, yielding totalAmount=null and a wrong taxAmount.
        ParsedInvoice p = parser.parseText("""
            开票日期：2026年01月01日
            购买方名称：测试买家公司
            销售方名称：测试卖家公司
            金额合计 ¥100.00 税额合计 ¥0.00 价税合计 ¥100.00
            """);
        assertEquals(new BigDecimal("100.00"), p.totalAmount());
        assertEquals(new BigDecimal("0.00"), p.taxAmount());
        assertEquals(new BigDecimal("100.00"), p.totalWithTax());
    }

    @Test
    void nonPaddedDateStillParses() throws Exception {
        ParsedInvoice p = parser.parseText("开票日期：2026年5月6日");
        assertEquals(LocalDate.of(2026, 5, 6), p.invoiceDate());
    }

    @Test
    void dateWithInnerSpacesStillParses() throws Exception {
        // pocfile: PDFBox 偶发在「年/月」后插空格（润云购物中心票 26322000007541383186），
        // 日期各段之间须容忍空白。
        ParsedInvoice p = parser.parseText("开票日期：\n2026年 09月 12日");
        assertEquals(LocalDate.of(2026, 9, 12), p.invoiceDate());
    }

    @Test
    void parsesFullWidthYenLayout() throws Exception {
        // pocfile: 全角 ￥ 与半角 ¥ 同为合法金额符号（YEN 均已放宽）。
        // 按 PDFBox 实际布局构造：标签聚顶部、值按文档序流底部。
        ParsedInvoice p = parser.parseText("""
            发票号码：26322000004144614676
            开票日期：2026年5月26日 购买方 销售方 项目名称
            测试买家公司 测试卖家公司 *餐饮服务*餐饮服务
            26322000004144614676 2026年5月26日 测试买家公司 测试卖家公司
            ￥189.62 ￥11.38 ￥201.00 王桃桃
            """);
        assertEquals(new BigDecimal("189.62"), p.totalAmount());
        assertEquals(new BigDecimal("11.38"), p.taxAmount());
        assertEquals(new BigDecimal("201.00"), p.totalWithTax());
    }
}
