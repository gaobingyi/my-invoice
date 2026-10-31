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

    @Test
    void taxExemptInvoiceResolvesAmountsAndCategory() throws Exception {
        // pocfile: 免税票（旅行社代订交通，26332000009297867451）税额列印「***」：全文只有
        // 金额/价税两个相等 ¥ 值，三值不变量（>=3 个 ¥）不成立 → 金额全 null；*** 又与明细行
        // *…* 跨行桥接，把大写金额/开票人整段吞进 category。文本取自线上 PDFBox 实际 dump。
        ParsedInvoice p = parser.parseText("""
            电子发票（普通发票） 发票号码：
            开票日期：
            购
            买
            方
            信
            息
            统一社会信用代码/纳税人识别号：
            销
            售
            方
            信
            息
            统一社会信用代码/纳税人识别号：
            名称： 名称：
            项目名称 规格型号 单 位 数 量 单 价 金 额 税率/征收率 税 额
            合 计
            价税合计（大写） （小写）
            备
            注
            开票人：
            26332000009297867451
            2026年10月28日
            上海钦钦印刷科技有限公司
            91310116332791646K
            浙江中山国际旅行社有限责任公司
            91330000142934351Q
            ¥3995.00 ***
            叁仟玖佰玖拾伍圆整 ¥3995.00
            沈文婷
            沈文婷
            *生产生活服务*代订交通
            住宿费
            免税3995.00 ***
            """);
        assertEquals("26332000009297867451", p.invoiceNumber());
        assertEquals("上海钦钦印刷科技有限公司", p.buyerName());
        assertEquals("浙江中山国际旅行社有限责任公司", p.sellerName());
        assertEquals(new BigDecimal("3995.00"), p.totalAmount());
        assertEquals(new BigDecimal("0.00"), p.taxAmount());
        assertEquals(new BigDecimal("3995.00"), p.totalWithTax());
        assertEquals("*生产生活服务*", p.category());
    }

    @Test
    void taxExemptWithoutKeywordKeepsNullForLlm() throws Exception {
        // pocfile: 两个相等 ¥ 值但票面无 免税/不征税 字样（如 PDFBox 漏打价税合计的普通票）
        // 不走零税额分支，留 null 给 LLM 兜底，防误判。
        ParsedInvoice p = parser.parseText("""
            发票号码：26322000004144614676
            ¥100.00
            ¥100.00
            """);
        assertNull(p.taxAmount());
        assertNull(p.totalWithTax());
    }
}
