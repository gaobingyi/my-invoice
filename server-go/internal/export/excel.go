package export

import (
	"io"

	"github.com/xuri/excelize/v2"

	"invoice-go/internal/invoice"
	"invoice-go/internal/money"
)

// generateExcel 生成「发票清单.xlsx」（对齐 InvoiceExcelGenerator.java）：
// 行0 标题；行1 表头 9 列；数据行（金额 NULL → "-"，日期 ISO 或空）；末行合计。
// 列宽（0 基）：B=26、D/E=34、F=24、G/H/I=14、其余 10。
func generateExcel(w io.Writer, invoices []*invoice.Invoice, batchMonth string) error {
	f := excelize.NewFile()
	defer f.Close()
	const sheet = "发票清单"
	if err := f.SetSheetName("Sheet1", sheet); err != nil {
		return err
	}
	// SetColWidth 必须在 NewStreamWriter 之前调用
	widths := map[string]float64{"A": 10, "B": 26, "C": 10, "D": 34, "E": 34, "F": 24, "G": 14, "H": 14, "I": 14}
	for col, width := range widths {
		if err := f.SetColWidth(sheet, col, col, width); err != nil {
			return err
		}
	}
	sw, err := f.NewStreamWriter(sheet)
	if err != nil {
		return err
	}

	if err := sw.SetRow("A1", []any{"发票清单（" + batchMonth + " 批次）"}); err != nil {
		return err
	}
	if err := sw.SetRow("A2", []any{
		"序号", "发票号码", "开票日期", "购买方", "销售方", "项目名称", "金额", "税额", "价税合计"}); err != nil {
		return err
	}

	row := 3
	sumAmount, sumTax, sumTotal := money.Must("0"), money.Must("0"), money.Must("0")
	for i, inv := range invoices {
		if !inv.TotalAmount.IsNull() {
			sumAmount = money.Add(sumAmount, inv.TotalAmount)
		}
		if !inv.TaxAmount.IsNull() {
			sumTax = money.Add(sumTax, inv.TaxAmount)
		}
		if !inv.TotalWithTax.IsNull() {
			sumTotal = money.Add(sumTotal, inv.TotalWithTax)
		}
		cells := []any{
			i + 1,                       // 序号（数字）
			inv.InvoiceNumber,           // 号码
			strOrEmpty(inv.InvoiceDate), // 日期 ISO 或 ""
			strOrEmpty(inv.BuyerName),
			strOrEmpty(inv.SellerName),
			strOrEmpty(inv.Category),
			amountText(inv.TotalAmount),
			amountText(inv.TaxAmount),
			amountText(inv.TotalWithTax),
		}
		if err := sw.SetRow(cell("A", row), cells); err != nil {
			return err
		}
		row++
	}

	// 末行合计：A=合计，三金额列 = 各自累加（null 跳过，全 null 为 0）
	if err := sw.SetRow(cell("A", row), []any{
		"合计", "", "", "", "", "",
		sumAmount.String(), sumTax.String(), sumTotal.String(),
	}); err != nil {
		return err
	}
	if err := sw.Flush(); err != nil {
		return err
	}
	_, err = f.WriteTo(w)
	return err
}

// amountText 金额列：NULL → "-"，否则 plain notation 字符串（对齐 Java toPlainString 文本单元格）。
func amountText(d money.Decimal) string {
	if d.IsNull() {
		return "-"
	}
	return d.String()
}

func strOrEmpty(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func cell(col string, row int) string {
	return col + itoa(row)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	neg := n < 0
	if neg {
		n = -n
	}
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
