package parser

// 移植自 my-invoice InvoiceParserTest —— 这 6 个用例就是正则路径的行为规格。
// 纯文本解析测试（不依赖 PDF）；版式级回归由真实 PDF 对照生产 DB 真值覆盖（见 spike）。

import (
	"testing"

	"invoice-go/internal/money"
)

func mustDecimal(t *testing.T, s string) money.Decimal {
	t.Helper()
	return money.Must(s)
}

func strEq(t *testing.T, got *string, want string) {
	t.Helper()
	if got == nil || *got != want {
		t.Fatalf("got %v, want %q", got, want)
	}
}

func TestZeroTaxInvoiceResolvesAmounts(t *testing.T) {
	// 税额=0.00 时 金额==价税：max/min 启发式会静默错标，三值不变量不受影响。
	p := parseText(`开票日期：2026年01月01日
购买方名称：测试买家公司
销售方名称：测试卖家公司
金额合计 ¥100.00 税额合计 ¥0.00 价税合计 ¥100.00
`)
	if p.TotalAmount.String() != "100.00" || p.TaxAmount.String() != "0.00" || p.TotalWithTax.String() != "100.00" {
		t.Fatalf("amounts: %s / %s / %s", p.TotalAmount, p.TaxAmount, p.TotalWithTax)
	}
}

func TestNonPaddedDateStillParses(t *testing.T) {
	p := parseText("开票日期：2026年5月6日")
	strEq(t, p.InvoiceDate, "2026-05-06")
}

func TestDateWithInnerSpacesStillParses(t *testing.T) {
	// PDFBox 偶发在「年/月」后插空格（润云购物中心票），日期各段之间须容忍空白。
	p := parseText("开票日期：\n2026年 09月 12日")
	strEq(t, p.InvoiceDate, "2026-09-12")
}

func TestInvalidDateStaysNullForLlm(t *testing.T) {
	// 2026年2月30日 → Java LocalDate.of 抛 DateTimeException → null。
	p := parseText("开票日期：2026年2月30日")
	if p.InvoiceDate != nil {
		t.Fatalf("invalid date should be nil, got %v", *p.InvoiceDate)
	}
}

func TestParsesFullWidthYenLayout(t *testing.T) {
	// 全角 ￥ 与半角 ¥ 同为合法金额符号。按 PDFBox 实际布局构造：
	// 标签聚顶部、值按文档序流底部。
	p := parseText(`发票号码：26322000004144614676
开票日期：2026年5月26日 购买方 销售方 项目名称
测试买家公司 测试卖家公司 *餐饮服务*餐饮服务
26322000004144614676 2026年5月26日 测试买家公司 测试卖家公司
￥189.62 ￥11.38 ￥201.00 王桃桃
`)
	if p.TotalAmount.String() != "189.62" || p.TaxAmount.String() != "11.38" || p.TotalWithTax.String() != "201.00" {
		t.Fatalf("amounts: %s / %s / %s", p.TotalAmount, p.TaxAmount, p.TotalWithTax)
	}
	strEq(t, p.InvoiceNumber, "26322000004144614676")
}

func TestTaxExemptInvoiceResolvesAmountsAndCategory(t *testing.T) {
	// 免税票（旅行社代订交通 26332000009297867451）：税额列印「***」，全文只有
	// 两个相等 ¥ 值；*** 与明细行 *…* 跨行桥接风险靠「先抹连续星号串」化解。
	// 文本取自线上 PDFBox 实际 dump（垂直单字标签各占一行）。
	p := parseText(`电子发票（普通发票） 发票号码：
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
`)
	strEq(t, p.InvoiceNumber, "26332000009297867451")
	strEq(t, p.BuyerName, "上海钦钦印刷科技有限公司")
	strEq(t, p.SellerName, "浙江中山国际旅行社有限责任公司")
	if p.TotalAmount.String() != "3995.00" || p.TaxAmount.String() != "0.00" || p.TotalWithTax.String() != "3995.00" {
		t.Fatalf("amounts: %s / %s / %s", p.TotalAmount, p.TaxAmount, p.TotalWithTax)
	}
	strEq(t, p.Category, "*生产生活服务*")
}

func TestTaxExemptWithoutKeywordKeepsNullForLlm(t *testing.T) {
	// 两个相等 ¥ 值但无 免税/不征税 字样（如 PDFBox 漏打价税合计的普通票）
	// 不走零税额分支，留 null 给 LLM，防误判。
	p := parseText(`发票号码：26322000004144614676
¥100.00
¥100.00
`)
	if !p.TaxAmount.IsNull() || !p.TotalWithTax.IsNull() {
		t.Fatalf("should stay null, got %s / %s", p.TaxAmount, p.TotalWithTax)
	}
	_ = mustDecimal
}

func TestRailwayTicketBranch(t *testing.T) {
	// 铁路电子客票：只有一个 ¥ 值（票价），销售方固定国铁，category 为行程。
	p := parseText(`电子发票（铁路电子客票）
发票号码：26322123456789012345
开票日期：2026年10月20日
扬州站
上海站
票价: ￥127.00
中国铁路祝您旅途愉快
`)
	strEq(t, p.InvoiceNumber, "26322123456789012345")
	strEq(t, p.InvoiceDate, "2026-10-20")
	strEq(t, p.SellerName, "中国国家铁路集团有限公司")
	strEq(t, p.SellerTaxID, "91100000000013477B")
	strEq(t, p.Category, "扬州 → 上海")
	if p.TotalAmount.String() != "127.00" || p.TaxAmount.String() != "0.00" || p.TotalWithTax.String() != "127.00" {
		t.Fatalf("amounts: %s / %s / %s", p.TotalAmount, p.TaxAmount, p.TotalWithTax)
	}
}

func TestNameBeforeLabelGluedRun(t *testing.T) {
	// 标签与名称粘连：「…公司统一社会信用代码:913…」合成一个 CJK_RUN，
	// 取标签前 prefix 为候选名称（山姆票坑）。
	p := parseText(`26332000009297867451
2026年10月28日
名称:上海钦钦印刷科技有限公司统一社会信用代码:91310116332791646K
名称:测试销售公司统一社会信用代码:91330000142934351Q
¥189.62 ￥11.38 ￥201.00
`)
	strEq(t, p.BuyerName, "上海钦钦印刷科技有限公司")
	strEq(t, p.BuyerTaxID, "91310116332791646K")
	strEq(t, p.SellerName, "测试销售公司")
	strEq(t, p.SellerTaxID, "91330000142934351Q")
}

func TestAliMachineCodeFiltered(t *testing.T) {
	// 页脚机器码 ALI… 长得像信用代码，按前缀剔除后不影响位置配对。
	p := parseText(`发票号码：26322000004144614676
测试买家公司
91310116332791646K
测试卖家公司
91330000142934351Q
¥189.62 ￥11.38 ￥201.00
机器码: ALI00123456789012345678
`)
	strEq(t, p.BuyerTaxID, "91310116332791646K")
	strEq(t, p.SellerTaxID, "91330000142934351Q")
}

func TestThirtyFiveDigitGluedStringDoesNotYieldNumber(t *testing.T) {
	// 京东票：35 位数字粘串不能被 \d{20} 截出前 20 位当号码（\b 词边界兜底）。
	p := parseText(`91310116332791646K91330000142934351Q
2026年10月28日
¥189.62 ￥11.38 ￥201.00
`)
	if p.InvoiceNumber != nil {
		t.Fatalf("glued 36-char string must not produce a 20-digit number, got %v", *p.InvoiceNumber)
	}
	if p.TotalAmount.String() != "189.62" {
		t.Fatalf("amounts: %s", p.TotalAmount)
	}
}
