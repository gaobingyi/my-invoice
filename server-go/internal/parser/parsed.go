package parser

import "invoice-go/internal/money"

// Parsed 单次解析结果。字符串字段 nil = 未解析到（对齐 Java null 语义——
// 「解析出空串」与「未解析」是不同状态，LLM 可能返回空串）。
// Date 为 ISO yyyy-MM-dd 字符串，nil = 未解析。
type Parsed struct {
	InvoiceNumber *string
	InvoiceDate   *string // yyyy-MM-dd
	BuyerName     *string
	BuyerTaxID    *string
	SellerName    *string
	SellerTaxID   *string
	Category      *string
	TotalAmount   money.Decimal
	TaxAmount     money.Decimal
	TotalWithTax  money.Decimal
}

// missingFieldOrder 缺失字段枚举顺序 = LLM prompt 的字段顺序，勿改动。
var missingFieldOrder = []string{
	"invoiceNumber", "invoiceDate", "buyerName", "buyerTaxId",
	"sellerName", "sellerTaxId", "category",
	"totalAmount", "taxAmount", "totalWithTax",
}

func strPtr(s string) *string { return &s }

func (p Parsed) MissingFields() []string {
	var missing []string
	add := func(cond bool, name string) {
		if cond {
			missing = append(missing, name)
		}
	}
	add(p.InvoiceNumber == nil, "invoiceNumber")
	add(p.InvoiceDate == nil, "invoiceDate")
	add(p.BuyerName == nil, "buyerName")
	add(p.BuyerTaxID == nil, "buyerTaxId")
	add(p.SellerName == nil, "sellerName")
	add(p.SellerTaxID == nil, "sellerTaxId")
	add(p.Category == nil, "category")
	add(p.TotalAmount.IsNull(), "totalAmount")
	add(p.TaxAmount.IsNull(), "taxAmount")
	add(p.TotalWithTax.IsNull(), "totalWithTax")
	return missing
}
