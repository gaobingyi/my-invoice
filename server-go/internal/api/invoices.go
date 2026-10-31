package api

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"invoice-go/internal/apierr"
)

const maxUploadBytes = 10 << 20 // 与 max-file-size 对齐；超限 413（配合 nginx max-swallow 不 502）

func serverErr(err error) error {
	return &apierr.Internal{Err: err, Msg: "internal"}
}

func openFile(path string) (*os.File, error) { return os.Open(path) }

func pathBase(path string) string { return filepath.Base(path) }

func fileSize(f *os.File) int64 {
	fi, err := f.Stat()
	if err != nil {
		return 0
	}
	return fi.Size()
}

func queryInt(r *http.Request, key string, def int) int {
	v := r.URL.Query().Get(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.ContentLength > maxUploadBytes {
		writeText(w, http.StatusRequestEntityTooLarge, "文件超过 10MB 限制")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBytes)
	mr, err := r.MultipartReader()
	if err != nil {
		writeErr(w, serverErr(err)) // 非 multipart → 500（对齐 Spring 兜底）
		return
	}
	var fileName string
	var filePart io.Reader
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			// MaxBytesReader 超限在解析 multipart 时也可能触发
			writeErr(w, mapBodyErr(err))
			return
		}
		if part.FormName() == "file" {
			fileName = part.FileName()
			filePart = part
			break // 单文件字段（对齐 @RequestParam("file")）
		}
		_, _ = io.Copy(io.Discard, part)
		part.Close()
	}
	if filePart == nil {
		writeErr(w, apierr.Bad("文件为空"))
		return
	}
	inv, err := s.Invoices.Upload(fileName, filePart)
	if err != nil {
		writeErr(w, mapBodyErr(err))
		return
	}
	writeJSON(w, http.StatusOK, inv)
}

// mapBodyErr 把请求体超限错误映射为 413（对齐 MaxUploadSizeExceededException）。
func mapBodyErr(err error) error {
	var mbe *http.MaxBytesError
	if errors.As(err, &mbe) {
		return &apierr.TooLarge{Msg: "文件超过 10MB 限制"}
	}
	return err
}

func (s *Server) parseUsed(r *http.Request) (*bool, error) {
	v := r.URL.Query().Get("used")
	if v == "" {
		return nil, nil
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		// 对齐 Spring：类型转换异常落全局 500 兜底
		return nil, &apierr.Internal{Err: err, Msg: "internal"}
	}
	return &b, nil
}

func (s *Server) handleList(w http.ResponseWriter, r *http.Request) {
	page := queryInt(r, "page", 0)
	size := queryInt(r, "size", 20)
	used, err := s.parseUsed(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	items, total, err := s.Invoices.List(page, size, used, r.URL.Query().Get("keyword"))
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	writeJSON(w, http.StatusOK, newPage(itemsOrEmpty(items), page, size, total))
}

func itemsOrEmpty[T any](items []*T) []*T {
	if items == nil {
		return []*T{}
	}
	return items
}

func (s *Server) handleSummary(w http.ResponseWriter, r *http.Request) {
	used, err := s.parseUsed(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	count, total, err := s.Invoices.Summary(used, r.URL.Query().Get("keyword"))
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"count": count, "totalWithTax": total})
}

func (s *Server) handleGet(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err)) // 非数字 id → Spring 转换异常 → 500
		return
	}
	inv, err := s.Invoices.Get(id)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, inv)
}

func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	if err := s.Invoices.Delete(id); err != nil {
		writeErr(w, err)
		return
	}
	w.WriteHeader(http.StatusOK) // 空 body（对齐 void）
}

func (s *Server) handleFile(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	path, _, err := s.Invoices.ResolveFile(id)
	if err != nil {
		writeErr(w, err)
		return
	}
	f, err := openFile(path)
	if err != nil {
		writeErr(w, serverErr(err)) // DB 行在、磁盘文件丢 → 500（对齐 Java 写流时炸）
		return
	}
	defer f.Close()

	// filename 编码逐字对齐 URLEncoder.encode（空格 → +，保留 * - _ .）
	base := pathBase(path)
	mode := "inline"
	if r.URL.Query().Get("disposition") == "download" {
		mode = "attachment"
	}
	w.Header().Set("Content-Type", "application/pdf")
	w.Header().Set("Content-Disposition", mode+"; filename*=UTF-8''"+javaURLEncode(base))
	w.Header().Set("Content-Length", strconv.FormatInt(fileSize(f), 10))
	_, _ = io.Copy(w, f)
}

// javaURLEncode 对齐 java.net.URLEncoder.encode(s, UTF-8)：空格 → +，
// 保留 a-zA-Z0-9 . - _ *，其余 %XX。filename* 里 '+' 按 RFC 5987 应为 %20，
// 但 Java 侧就是发 '+' 且前端/浏览器已兼容——对齐行为优先。
func javaURLEncode(s string) string {
	var sb strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9',
			c == '.', c == '-', c == '_', c == '*':
			sb.WriteByte(c)
		case c == ' ':
			sb.WriteByte('+')
		default:
			sb.WriteByte('%')
			const hexDigits = "0123456789ABCDEF"
			sb.WriteByte(hexDigits[c>>4])
			sb.WriteByte(hexDigits[c&0xF])
		}
	}
	return sb.String()
}
