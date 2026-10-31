// dumptext 调试工具：打印 pdf.Extract 的重建文本与 run 流（spike 用）。
package main

import (
	"fmt"
	"os"
	"strings"

	"invoice-go/internal/pdf"
)

func main() {
	doc, err := pdf.Extract(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "extract:", err)
		os.Exit(1)
	}
	mode := "text"
	if len(os.Args) > 2 {
		mode = os.Args[2]
	}
	if mode == "text" {
		fmt.Print(doc.Text)
		return
	}
	for i, r := range doc.Runs {
		if mode == "digits" && !strings.ContainsAny(r.Text, "0123456789") {
			continue
		}
		fmt.Printf("[%03d] Y=%.1f X=%.1f S=%q\n", i, r.Glyphs[0].Y, r.Glyphs[0].X, r.Text)
	}
}
