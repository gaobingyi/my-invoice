package export

import (
	"archive/zip"
	"io"
	"os"
	"regexp"
	"strconv"

	"invoice-go/internal/apierr"
	"invoice-go/internal/invoice"
)

var reExtSuffix = regexp.MustCompile(`(\.[^.]+)$`)

// WriteZip 把批次打成一个 ZIP 流式写入 out：发票清单.xlsx（第一条目）+ 各 PDF +
// 缺失清单.txt（仅当有缺失）。文件已丢的票跳过并记入缺失清单；条目重名在扩展名前
// 插 _2、_3…（上限 100）。调用方必须先做完所有校验再开流——流一旦开始异常无法
// 再换成错误体（对齐 Java 控制器注释）。
func (s *Service) WriteZip(batch *Batch, invoices []*invoice.Invoice, out io.Writer) error {
	type entry struct {
		path string
		name string
	}
	var entries []entry
	names := map[string]bool{}
	var missing []*invoice.Invoice
	for _, inv := range invoices {
		p, err := s.invoices.ResolveFileOrNull(inv)
		if err != nil {
			return err // 非法路径（DB 被改）如实报错
		}
		if p == "" {
			missing = append(missing, inv)
			continue
		}
		base := pathBaseName(p)
		entryName := base
		suffix := 2
		for names[entryName] {
			entryName = reExtSuffix.ReplaceAllString(base, "_"+strconv.Itoa(suffix)+"$1")
			suffix++
			if suffix > 100 {
				return &apierr.Internal{Msg: "ZIP 条目重名解析失败: " + base}
			}
		}
		names[entryName] = true
		entries = append(entries, entry{path: p, name: entryName})
	}

	zw := zip.NewWriter(out)
	// ① 发票清单.xlsx
	xw, err := zw.Create("发票清单.xlsx")
	if err != nil {
		return err
	}
	if err := generateExcel(xw, invoices, batch.BatchMonth); err != nil {
		return err
	}
	// ② 各 PDF（按加入批次顺序）
	for _, e := range entries {
		f, err := os.Open(e.path)
		if err != nil {
			return err
		}
		zf, err := zw.Create(e.name)
		if err != nil {
			f.Close()
			return err
		}
		if _, err := io.Copy(zf, f); err != nil {
			f.Close()
			return err
		}
		f.Close()
	}
	// ③ 缺失清单.txt（条件追加）
	if len(missing) > 0 {
		zf, err := zw.Create("缺失清单.txt")
		if err != nil {
			return err
		}
		body := "以下发票的 PDF 文件已不存在，未包含在本压缩包内：\n"
		for _, inv := range missing {
			seller := ""
			if inv.SellerName != nil {
				seller = *inv.SellerName
			}
			body += "- " + seller + "_" + inv.InvoiceNumber + "\n"
		}
		if _, err := zf.Write([]byte(body)); err != nil {
			return err
		}
	}
	return zw.Close()
}

func pathBaseName(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' || p[i] == os.PathSeparator {
			return p[i+1:]
		}
	}
	return p
}
