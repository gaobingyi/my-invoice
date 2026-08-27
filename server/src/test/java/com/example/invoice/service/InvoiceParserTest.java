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
        assertEquals("王桃桃", p.drawer());
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
        assertEquals("黄亚雄", p.drawer());
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
        // pocfile: 全角 ￥ 与半角 ¥ 同为合法金额符号（YEN/DRAWER_YEN 均已放宽）。
        // 按 PDFBox 实际布局构造：标签聚顶部、值按文档序流底部；开票人不邻标签，
        // 走 ¥ 后 CJK 令牌的兜底路径 —— 锁定全角分支防重构回归。
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
        assertEquals("王桃桃", p.drawer());
    }

    @Test
    void parsesYenAfterNumberInvoice() throws Exception {
        // pocfile: the Shenzhou invoice puts ¥ AFTER the figure (1353.10¥) instead of
        // before it, and 开票人： stays adjacent to its value (岳云鹏).
        ParsedInvoice p = parseShenzhou();
        assertEquals("26117000000103944900", p.invoiceNumber());
        assertEquals(LocalDate.of(2026, 3, 14), p.invoiceDate());
        assertEquals("北京神州数码科捷技术服务有限公司", p.sellerName());
        assertNotNull(p.category());
        assertTrue(p.category().contains("计算机外部设备"));
        assertEquals(new BigDecimal("1353.10"), p.totalAmount());
        assertEquals(new BigDecimal("175.90"), p.taxAmount());
        assertEquals(new BigDecimal("1529.00"), p.totalWithTax());
        assertEquals("岳云鹏", p.drawer());
    }
}
