// parsetool 解析对照工具（spike 用，不随服务部署）：对给定 PDF 运行解析器
//（正则 + Layout，无 LLM），输出 TSV 便于与生产 DB 真值 diff。
package main

import (
	"fmt"
	"os"

	"invoice-go/internal/parser"
)

func main() {
	eng := parser.NewEngine(nil, nil)
	for _, path := range os.Args[1:] {
		p, err := eng.Parse(path)
		if err != nil {
			fmt.Fprintf(os.Stderr, "%s\tERROR\t%v\n", path, err)
			continue
		}
		row := []string{
			path,
			deref(p.InvoiceNumber),
			deref(p.InvoiceDate),
			deref(p.BuyerName),
			deref(p.BuyerTaxID),
			deref(p.SellerName),
			deref(p.SellerTaxID),
			deref(p.Category),
			p.TotalAmount.String(),
			p.TaxAmount.String(),
			p.TotalWithTax.String(),
		}
		out := ""
		for i, v := range row {
			if i > 0 {
				out += "\t"
			}
			out += v
		}
		fmt.Println(out)
	}
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
