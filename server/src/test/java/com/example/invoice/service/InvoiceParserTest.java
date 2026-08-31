package com.example.invoice.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceParserTest {

    // pocfile: fixture is the real sample PDF dumped through PDFBox (the same path production
    // uses), so the regex is exercised against the actual PDFBox token layout, not a hand-typed
    // approximation that drifts from it.
    private final InvoiceParser parser = new InvoiceParser(null, null);

    private ParsedInvoice parseSample() throws Exception {
        Path pdf = Path.of(getClass().getClassLoader().getResource("sample.pdf").toURI());
        return parser.parse(pdf);
    }

    private ParsedInvoice parseMeigaozhi() throws Exception {
        Path pdf = Path.of(getClass().getClassLoader().getResource("sample-meigaozhi.pdf").toURI());
        return parser.parse(pdf);
    }

    private ParsedInvoice parseShenzhou() throws Exception {
        Path pdf = Path.of(getClass().getClassLoader().getResource("sample-shenzhou.pdf").toURI());
        return parser.parse(pdf);
    }

    @Test
    void parsesAllFields() throws Exception {
        ParsedInvoice p = parseSample();
        assertEquals("26322000004144614676", p.invoiceNumber());
        assertEquals(LocalDate.of(2026, 5, 26), p.invoiceDate());
        assertEquals("上海钦钦印刷科技有限公司", p.buyerName());
        assertEquals("91310116332791646K", p.buyerTaxId());
        assertEquals("扬州滋奇奥邦餐饮管理有限公司", p.sellerName());
        assertEquals("91321002MA27JJYQ1E", p.sellerTaxId());
        assertNotNull(p.category());
        assertTrue(p.category().contains("餐饮服务"));
        assertEquals(new BigDecimal("189.62"), p.totalAmount());
        assertEquals(new BigDecimal("11.38"), p.taxAmount());
        assertEquals(new BigDecimal("201.00"), p.totalWithTax());
    }

    @Test
    void parsesMultiLineDetailInvoice() throws Exception {
        // pocfile: the furniture invoice has a multi-line detail block with a discount line,
        // unlike the single-line restaurant invoice. Amounts come from the ¥ totals, not the
        // wrapped detail rows, so they must still resolve (金额 157.52 + 税额 20.48 = 178.00).
        ParsedInvoice p = parseMeigaozhi();
        assertEquals("26952000001305813736", p.invoiceNumber());
        assertEquals(LocalDate.of(2026, 3, 31), p.invoiceDate());
        assertEquals("深圳市美之高实业发展有限公司", p.sellerName());
        assertNotNull(p.category());
        assertTrue(p.category().contains("家具"));
        assertEquals(new BigDecimal("157.52"), p.totalAmount());
        assertEquals(new BigDecimal("20.48"), p.taxAmount());
        assertEquals(new BigDecimal("178.00"), p.totalWithTax());
        // pocfile: the footer machine code ALI76597… must not leak into the tax-id fields.
        assertEquals("914403007084608622", p.sellerTaxId());
        assertEquals("91310116332791646K", p.buyerTaxId());
    }

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

    @Test
    void parsesYenAfterNumberInvoice() throws Exception {
        // pocfile: the Shenzhou invoice puts ¥ AFTER the figure (1353.10¥) instead of
        // before it.
        ParsedInvoice p = parseShenzhou();
        assertEquals("26117000000103944900", p.invoiceNumber());
        assertEquals(LocalDate.of(2026, 3, 14), p.invoiceDate());
        assertEquals("北京神州数码科捷技术服务有限公司", p.sellerName());
        assertNotNull(p.category());
        assertTrue(p.category().contains("计算机外部设备"));
        assertEquals(new BigDecimal("1353.10"), p.totalAmount());
        assertEquals(new BigDecimal("175.90"), p.taxAmount());
        assertEquals(new BigDecimal("1529.00"), p.totalWithTax());
    }

    @Test
    void parsesLabelAdjacentLayout() throws Exception {
        // pocfile: 纵排标签版式（购/买/方/信/息 竖排）里标签与值直接相邻：
        // 「名称:某某公司」+「统一社会信用代码/纳税人识别号:91…」。代码值前最近的 CJK 串
        // 是列标签本身，旧 nameBefore 会把「纳税人识别号」当成购/销方名称 —— 已知标签串
        // 必须跳过，取再上一个非标签中文串。
        ParsedInvoice p = parser.parse(
                Path.of(getClass().getClassLoader().getResource("sample-shanmu.pdf").toURI()));
        assertEquals("26317000000172864991", p.invoiceNumber());
        assertEquals(LocalDate.of(2026, 1, 7), p.invoiceDate());
        assertEquals("上海钦钦印刷科技有限公司", p.buyerName());
        assertEquals("91310116332791646K", p.buyerTaxId());
        assertEquals("上海真如山姆超市有限公司", p.sellerName());
        assertEquals("91310000MAC5XRQB25", p.sellerTaxId());
        assertEquals(new BigDecimal("44.16"), p.totalAmount());
        assertEquals(new BigDecimal("5.74"), p.taxAmount());
        assertEquals(new BigDecimal("49.90"), p.totalWithTax());
        assertTrue(p.category().contains("方便食品"));
    }

    @Test
    void parsesIndividualBusinessSeller() throws Exception {
        // pocfile: 个体户销售方（仪征市马集镇同发烟酒商店）没有「公司」后缀，旧 NAME 正则
        // 匹配不上，备注里「销方开户银行:江苏仪征农村商业银行股份有限公司马集支行」的银行名
        // 会顶替销售方。名称改为与信用代码按位置配对后，银行备注行不再干扰。
        ParsedInvoice p = parser.parse(
                Path.of(getClass().getClassLoader().getResource("sample-yanjiushop.pdf").toURI()));
        assertEquals("26322000006916576636", p.invoiceNumber());
        assertEquals(LocalDate.of(2026, 8, 24), p.invoiceDate());
        assertEquals("上海钦钦印刷科技有限公司", p.buyerName());
        assertEquals("91310116332791646K", p.buyerTaxId());
        assertEquals("仪征市马集镇同发烟酒商店", p.sellerName());
        assertEquals("92321081MAD18PDY43", p.sellerTaxId());
        assertEquals(new BigDecimal("881.19"), p.totalAmount());
        assertEquals(new BigDecimal("8.81"), p.taxAmount());
        assertEquals(new BigDecimal("890.00"), p.totalWithTax());
        assertTrue(p.category().contains("其他食品"));
    }
}
