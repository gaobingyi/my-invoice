// Package parser 是发票解析器的 Go 移植（源：my-invoice InvoiceParser.java）。
// 三层结构：parseText 正则路径 → Layout 字形坐标覆盖购/销方 → LLM 兜底补缺失字段。
// 规格与行为逐字对齐 Java 版；有意偏离处均带注释标记（见 layout.go）。
package parser

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"invoice-go/internal/money"
)

// 正则表：与 Java 版逐字对齐（含注释里的坑位说明，勿单独改一边）。
var (
	// 值流版式（标签聚顶、值按文档序流底部）里按值 pattern + 文档顺序提取，
	// 绝不锚定邻近标签——标签与值之间隔着表头「项目名称」。
	reName = regexp.MustCompile(`[\x{4e00}-\x{9fa5}（）()·]+公司`)
	// 名称不限定「公司」结尾（个体户 …商店/中心/厂），故名称与信用代码按位置配对。
	reCJKRun = regexp.MustCompile(`[\x{4e00}-\x{9fa5}（）()·]{4,}`)
	// 信用代码 15-18 位、可能纯数字，与 20 位发票号码只差长度。粘串匹配不到 → Layout 兜底。
	reTaxID = regexp.MustCompile(`\b[0-9A-Z]{15,18}\b`)
	// 号码必须整体 20 位：宽松的 \d{20} 会从 35 位粘串里截出前 20 位（京东票）。
	reNumber = regexp.MustCompile(`\b\d{20}\b`)
	// PDFBox 偶发在「年/月」后插空格，各段之间必须容忍空白。
	reDate = regexp.MustCompile(`(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日`)
	// 半角 ¥ 与全角 ￥，可在数字前或后。\s 限定同行空白（不含换行）——poppler
	// 阅读序文本里金额与相邻行可能只隔一个换行，跨行配对会把「上一行行尾数字 +
	// 下一行行首 ¥」误配成一个金额（Java 版的 \s* 在 PDFBox 内容序文本上无此问题）。
	reYen = regexp.MustCompile(`[¥￥][^\S\n]*([\d,]+\.\d{2})|([\d,]+\.\d{2})[^\S\n]*[¥￥]`)
	// 明细行第一个 *…* token；内容限短中文串（允许跨行）。匹配前先抹连续星号串。
	reCategory = regexp.MustCompile(`\*[\x{4e00}-\x{9fa5}\n]{1,20}\*`)
	reStars    = regexp.MustCompile(`\*{2,}`)
	// 站名最长如「北京南」，后缀「站」字定界，懒惰匹配。
	reRailwayStation = regexp.MustCompile(`([\x{4e00}-\x{9fa5}]{1,8}?)站`)
)

const (
	railwayMark        = "铁路电子客票"
	railwaySellerName  = "中国国家铁路集团有限公司"
	railwaySellerTaxID = "91100000000013477B"
)

// ParseText 正则路径（导出供 PDF 级回归工具复用；单测走同包直调）。
func ParseText(text string) Parsed { return parseText(text) }

func parseText(text string) Parsed {
	number := first(reNumber, text)

	date := parseCNDate(text)

	names := reName.FindAllString(text, -1) // 文档序 [购方后备, 销方后备]

	// 三 ¥ 值不变量：金额 + 税额 = 价税合计（max）且 金额 >= 税额。
	// 不能用 max=价税/min=税额 的简单启发式——零税额票与两值相等的票会被静默错标。
	yens := allDecimal(reYen, text)
	var totalAmount, taxAmount, totalWithTax money.Decimal
	if len(yens) >= 3 {
		maxV, minV := yens[0], yens[0]
		for _, y := range yens {
			if money.Compare(y, maxV) > 0 {
				maxV = y
			}
			if money.Compare(y, minV) < 0 {
				minV = y
			}
		}
		// mid 取第一个既不等于 max 也不等于 min 的值；全重复时回落 max。
		mid := maxV
		for _, y := range yens {
			if money.Compare(y, maxV) != 0 && money.Compare(y, minV) != 0 {
				mid = y
				break
			}
		}
		// 不变量不成立时三个全 null 留给 LLM。
		if money.Compare(money.Add(mid, minV), maxV) == 0 {
			totalAmount, taxAmount, totalWithTax = mid, minV, maxV
		}
	}
	// 免税/不征税票：税额列印「***」，全文只有两个相等 ¥ 值。关键词门槛 + 两值相等
	// 双条件：普通票即使漏打一个 ¥ 也不会误入（漏打后剩两值必不相等）。
	if totalWithTax.IsNull() && len(yens) == 2 && money.Compare(yens[0], yens[1]) == 0 &&
		(strings.Contains(text, "免税") || strings.Contains(text, "不征税")) {
		totalAmount = yens[0]
		taxAmount = money.Must("0.00")
		totalWithTax = yens[0]
	}

	// 先抹连续星号串（免税票 *** 否则与明细行 *…* 跨行桥接吞掉大写金额段），再取第一对。
	category := first(reCategory, reStars.ReplaceAllString(text, ""))

	// 名称与信用代码按位置配对（购方名称→购方代码→销方名称→销方代码）；配不上退回 NAME 列表。
	taxes := realTaxIDs(text)
	var buyerName, sellerName *string
	if len(taxes) > 0 {
		buyerName = nameBefore(text, taxes[0].start)
	}
	if len(taxes) > 1 {
		sellerName = nameBefore(text, taxes[1].start)
	}
	if buyerName == nil && len(names) > 0 {
		buyerName = &names[0]
	}
	if sellerName == nil && len(names) > 1 {
		sellerName = &names[1]
	}
	var buyerTax, sellerTax *string
	if len(taxes) > 0 {
		buyerTax = strPtr(taxes[0].value)
	}
	if len(taxes) > 1 {
		sellerTax = strPtr(taxes[1].value)
	}

	// 铁路电子客票专用分支：票价即 金额=价税合计、税额记 0.00（127+0=127 自洽，Excel 可累加）；
	// 完全不印销售方，固定为国铁集团；category 为行程。
	if strings.Contains(text, railwayMark) {
		if totalWithTax.IsNull() && len(yens) == 1 {
			totalWithTax = yens[0]
			totalAmount = yens[0]
			taxAmount = money.Must("0.00")
		}
		if sellerName == nil {
			sellerName = strPtr(railwaySellerName)
		}
		if sellerTax == nil {
			sellerTax = strPtr(railwaySellerTaxID)
		}
		if category == nil {
			category = railwayCategory(text)
		}
	}

	return Parsed{
		InvoiceNumber: number,
		InvoiceDate:   date,
		BuyerName:     buyerName,
		BuyerTaxID:    buyerTax,
		SellerName:    sellerName,
		SellerTaxID:   sellerTax,
		Category:      category,
		TotalAmount:   totalAmount,
		TaxAmount:     taxAmount,
		TotalWithTax:  totalWithTax,
	}
}

// parseCNDate 提取「yyyy年M月d日」；非法日期（如 2026年2月30日）置 nil 留给 LLM，
// 与 LLM merge 对坏数据的处理一致（对齐 Java LocalDate.of 抛 DateTimeException → null）。
func parseCNDate(text string) *string {
	m := reDate.FindStringSubmatch(text)
	if m == nil {
		return nil
	}
	y, _ := strconv.Atoi(m[1])
	mo, _ := strconv.Atoi(m[2])
	d, _ := strconv.Atoi(m[3])
	if mo < 1 || mo > 12 || d < 1 {
		return nil
	}
	// 该月实际天数（time.Date 归一化到下月，因此验「原样能表示」即可）。
	if time.Date(y, time.Month(mo), d, 0, 0, 0, 0, time.UTC).Day() != d {
		return nil
	}
	return strPtr(fmt.Sprintf("%04d-%02d-%02d", y, mo, d))
}

// railwayCategory 站点按文档序去重（出发站在前），恰好两站才拼「A → B」（U+2192）。
func railwayCategory(text string) *string {
	var stations []string
	for _, m := range reRailwayStation.FindAllStringSubmatch(text, -1) {
		s := m[1]
		if !containsStr(stations, s) {
			stations = append(stations, s)
		}
	}
	if len(stations) == 2 {
		return strPtr(stations[0] + " → " + stations[1])
	}
	return nil
}

func containsStr(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func first(re *regexp.Regexp, text string) *string {
	if m := re.FindString(text); m != "" {
		return strPtr(m)
	}
	return nil
}

type taxIDAt struct {
	value string
	start int // 字节偏移（Go regexp 语义，配对逻辑全链路一致即可）
}

// realTaxIDs 信用代码带位置收集；页脚机器码（ALI…）也长得像代码，按前缀剔除。
func realTaxIDs(text string) []taxIDAt {
	var out []taxIDAt
	for _, loc := range reTaxID.FindAllStringIndex(text, -1) {
		v := text[loc[0]:loc[1]]
		if strings.HasPrefix(v, "ALI") {
			continue
		}
		out = append(out, taxIDAt{value: v, start: loc[0]})
	}
	return out
}

// nameBefore 代码前最近的连续中文串即名称。region 语义用 text[:pos] 等价实现。
func nameBefore(text string, pos int) *string {
	var last *string
	for _, g := range reCJKRun.FindAllString(text[:pos], -1) {
		// 标签与名称粘连的版式（…公司统一社会信用代码:913…）会把「名称+标签」合成
		// 一个 token 被整串误判为标签丢弃——含标签关键字时取标签前缀为候选。
		cut := -1
		i1 := strings.Index(g, "统一社会信用代码")
		i2 := strings.Index(g, "纳税人识别号")
		if i1 >= 0 {
			cut = i1
		} else if i2 >= 0 {
			cut = i2
		}
		if cut >= 0 {
			prefix := g[:cut]
			if runeLen(prefix) >= 4 && !isLabelRun(prefix) {
				last = strPtr(prefix)
			}
			continue
		}
		if isLabelRun(g) {
			continue
		}
		last = strPtr(g)
	}
	return last
}

// isLabelRun 精确包含完整标签短语（不能用 endsWith("信用代码")，会误伤
// 「上海信用代码服务中心」这类合法公司名）。
func isLabelRun(run string) bool {
	return strings.Contains(run, "纳税人识别号") || strings.Contains(run, "统一社会信用代码")
}

func allDecimal(re *regexp.Regexp, text string) []money.Decimal {
	var out []money.Decimal
	for _, m := range re.FindAllStringSubmatch(text, -1) {
		v := m[1]
		if v == "" {
			v = m[2]
		}
		d, err := money.New(v) // New 内部去千分位逗号（对齐 BigDecimal 语义）
		if err != nil {
			continue // \d+\.\d{2} 模式保证不会发生
		}
		out = append(out, d)
	}
	return out
}

func runeLen(s string) int { return len([]rune(s)) }
