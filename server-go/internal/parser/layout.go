package parser

import (
	"math"
	"regexp"
	"sort"

	"invoice-go/internal/pdf"
)

// Layout 字形坐标路径（对齐 Java 版内部类 Layout）：值流版式里「名称」与它的
// 信用代码是分开的 writeString，正则只能靠「代码前最近的中文串」配对；京东票把
// 购/销两列压成一条物理行，文本顺序无法还原列归属，字形 x 坐标可以。
//
// 这是局部兜底而非通用抽取器：只覆盖购/销方名称与代码 4 个字段，结构不符合
// 预期（最上面一行不是恰好一对代码）时整体放弃、保留正则结果，不猜。

const (
	lineTol = 3.0 // 同一视觉行的 y 容差
	gap     = 1.6 // 字形间距超过它算一个词边界
)

// 竖排栏目标签（购/买/方/信/息、销/售）是单字 writeString，会混进名称所在视觉行。
var labelChars = map[rune]bool{'购': true, '买': true, '销': true, '售': true, '方': true, '信': true, '息': true}

// 页眉/表头等非名称文本，避免把「开票日期」之类当成公司名。
var reNotName = regexp.MustCompile(
	`开票日期|发票号码|电子发票|普通发票|名称|识别号|信用代码|项目名称|规格型号|价税合计|开票人|合\s*计`)

// glyph = pdf.Glyph（{Ch rune; X, Y, W float64}，Y 自页顶向下）。
type glyph = pdf.Glyph

type code struct {
	value string
	x, y  float64
	line  int
}

// flattenRuns 把 run 流展平为字形流，跳过「单字形且 ∈ 栏目标签」的 run
//（对齐 Java writeString 的过滤：标签字仍进文本、只从字形集合剔除）。
func flattenRuns(runs []pdf.Run) []glyph {
	var out []glyph
	for _, r := range runs {
		if len(r.Glyphs) == 1 && labelChars[r.Glyphs[0].Ch] {
			continue
		}
		out = append(out, r.Glyphs...)
	}
	return out
}

// lines 按 y 分行（行内按 x 排序）；行号供「名称在代码上一行」定位用。
// 稳定排序：y、x 都相等时保持内容流顺序（对齐 Java List.sort 稳定性）。
func lines(glyphs []glyph) [][]glyph {
	sorted := make([]glyph, len(glyphs))
	copy(sorted, glyphs)
	sort.SliceStable(sorted, func(i, j int) bool {
		if sorted[i].Y != sorted[j].Y {
			return sorted[i].Y < sorted[j].Y
		}
		return sorted[i].X < sorted[j].X
	})
	var out [][]glyph
	var currentY float64
	for _, g := range sorted {
		if len(out) == 0 || math.Abs(g.Y-currentY) > lineTol {
			out = append(out, nil)
			currentY = g.Y
		}
		out[len(out)-1] = append(out[len(out)-1], g)
	}
	return out
}

// codes 找形如信用代码的连续 [0-9A-Z] 串；粘连串在此按字形间距切开
//（京东票的 36 字符串 → 18+18）。20 位发票号码因长度被排除。
func codes(ls [][]glyph) []code {
	var out []code
	for li, line := range ls {
		for i := 0; i < len(line); i++ {
			if !isAlnum(line[i]) {
				continue
			}
			run := []rune{}
			start, end := line[i].X, line[i].X
			j := i
			for j < len(line) && isAlnum(line[j]) && (j == i || line[j].X-end <= gap) {
				run = append(run, line[j].Ch)
				end = line[j].X + line[j].W
				j++
			}
			if n := len(run); n >= 15 && n <= 18 {
				out = append(out, code{value: string(run), x: start, y: line[i].Y, line: li})
			}
			i = j - 1
		}
	}
	return out
}

// isAlnum 单字形且为 [0-9] 或 [A-Z]（无 ToUnicode 映射的字形已被 Extract 跳过，
// 这里仍按字符集判定防御）。
func isAlnum(g glyph) bool {
	return (g.Ch >= '0' && g.Ch <= '9') || (g.Ch >= 'A' && g.Ch <= 'Z')
}

// slice 同一视觉行内 [lo, hi) 的字形拼成串，间距大处补空格以分隔相邻文本块。
func slice(line []glyph, lo, hi float64) string {
	var sb []rune
	end := math.NaN()
	for _, g := range line {
		if g.X < lo || g.X >= hi {
			continue
		}
		if !math.IsNaN(end) && g.X-end > gap {
			sb = append(sb, ' ')
		}
		sb = append(sb, g.Ch)
		end = g.X + g.W
	}
	return string(sb)
}

// nameAbove 代码左侧最近的非标签中文串即该方名称；逐行上溯（名称可能在代码上一行）。
func nameAbove(ls [][]glyph, codeLine int, lo, hi float64) *string {
	for li := codeLine - 1; li >= 0; li-- {
		if c := nameIn(slice(ls[li], lo, hi)); c != nil {
			return c
		}
	}
	return nil
}

func nameIn(line string) *string {
	var last *string
	for _, m := range reCJKRun.FindAllString(line, -1) {
		run := trimLabelTail(m)
		if runeLen(run) >= 4 && !reNotName.MatchString(run) {
			last = strPtr(run)
		}
	}
	return last
}

// trimLabelTail 名称尾部可能粘着标签残字（京东票的「…有限公司名」），去掉再判定。
func trimLabelTail(run string) string {
	rs := []rune(run)
	end := len(rs)
	for end > 0 {
		c := rs[end-1]
		if labelChars[c] || c == '名' || c == '称' {
			end--
		} else {
			break
		}
	}
	return string(rs[:end])
}

// overrideParties 取购/销方的名称与代码：两列代码是票面最上面同一视觉行的一对
//（顶部信息区先于明细行），左购右销；名称取各自半区上方最近的中文串。
// 结构不符预期时整体放弃，保留正则结果。
func overrideParties(parsed Parsed, runs []pdf.Run) Parsed {
	ls := lines(flattenRuns(runs))
	all := codes(ls)
	if len(all) == 0 {
		return parsed
	}
	topY := math.MaxFloat64
	for _, c := range all {
		topY = math.Min(topY, c.y)
	}
	var pair []code
	for _, c := range all {
		if c.y <= topY+lineTol {
			pair = append(pair, c)
		}
	}
	if len(pair) != 2 {
		return parsed
	}
	sort.SliceStable(pair, func(i, j int) bool { return pair[i].x < pair[j].x })
	buyer, seller := pair[0], pair[1]
	mid := (buyer.x + seller.x) / 2
	buyerName := nameAbove(ls, buyer.line, 0, mid)
	sellerName := nameAbove(ls, seller.line, mid, math.MaxFloat64)
	if buyerName == nil {
		buyerName = parsed.BuyerName
	}
	if sellerName == nil {
		sellerName = parsed.SellerName
	}
	return Parsed{
		InvoiceNumber: parsed.InvoiceNumber,
		InvoiceDate:   parsed.InvoiceDate,
		BuyerName:     buyerName,
		BuyerTaxID:    strPtr(buyer.value),
		SellerName:    sellerName,
		SellerTaxID:   strPtr(seller.value),
		Category:      parsed.Category,
		TotalAmount:   parsed.TotalAmount,
		TaxAmount:     parsed.TaxAmount,
		TotalWithTax:  parsed.TotalWithTax,
	}
}
