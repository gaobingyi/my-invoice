// Package pdf 从 PDF 提取「文本 + 字形几何」，对齐 Java 版 PDFBox Layout
// 一次遍历产出的两类数据：
//   - Text：喂正则路径（近似 PDFTextStripper 输出的换行/空格启发）
//   - Glyphs/Runs：喂 Layout 字形坐标路径（词级 run，对应 PDFBox writeString 粒度）
//
// 引擎选型（spike 结论 2026-10-31）：外部 pdftotext（poppler-utils + poppler-data）。
// 纯 Go 库 dslipak/pdf 对本项目两类关键票不可用：京东票的值画在 Form XObject 里
//（rsc 系不遍历），铁路票用预定义 GBK CMap（rsc 系无预定义 CMap 表，CJK 全乱码）。
// poppler 两者通吃；词级 bbox 粒度恰好对应 PDFBox 的 writeString。
// 部署依赖：apt install poppler-utils poppler-data（data 提供 Adobe-GB1 等 CJK 映射）。
package pdf

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Glyph 单个字形，坐标语义对齐 PDFBox TextPosition 的方向修正值：
// X 自左向右（pt），Y 自页顶向下（pt），W 为字形前进宽度（pt）。
type Glyph struct {
	Ch rune
	X  float64
	Y  float64
	W  float64
}

// Run 一次文本绘制操作（≈Tj/TJ 运行，对应 PDFBox 的一次 writeString 回调）。
// poppler 引擎下 run = word（poppler 按字间间距切词，与 PDFBox 词粒度同构）。
type Run struct {
	Text   string
	Glyphs []Glyph
}

// Doc 整份文档的提取结果：Runs 跨页累积（页间 y 已加偏移，自上而下有序），
// Text 为按启发式重建的纯文本。
type Doc struct {
	Runs []Run
	Text string
}

// Extract 调用 pdftotext -bbox 提取词流。损坏/加密 PDF 返回 error
//（上层转 400「无法解析 PDF 内容」，对齐 Loader.loadPDF 抛 IOException 的语义）。
func Extract(path string) (*Doc, error) {
	bin, libDir, err := findPdftotext()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(bin, "-bbox", "-enc", "UTF-8", path, "-")
	if libDir != "" {
		cmd.Env = append(os.Environ(),
			"LD_LIBRARY_PATH="+libDir+string(os.PathListSeparator)+os.Getenv("LD_LIBRARY_PATH"))
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	stdout, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("pdftotext: %w: %s", err, firstLine(stderr.String()))
	}
	return parseBBox(stdout)
}

// findPdftotext 定位 pdftotext 二进制：POPPLER_PDFTOTEXT → PATH →
// 用户目录 ~/.local/opt/poppler（免 root 的 deb 解压布局，附带 lib 目录注入）。
func findPdftotext() (bin, libDir string, err error) {
	if p := os.Getenv("POPPLER_PDFTOTEXT"); p != "" {
		return p, popplerLibDir(filepath.Dir(p)), nil
	}
	if p, e := exec.LookPath("pdftotext"); e == nil {
		return p, "", nil
	}
	home, e := os.UserHomeDir()
	if e == nil {
		base := filepath.Join(home, ".local", "opt", "poppler", "usr")
		p := filepath.Join(base, "bin", "pdftotext")
		if _, e := os.Stat(p); e == nil {
			return p, filepath.Join(base, "lib", "x86_64-linux-gnu"), nil
		}
	}
	return "", "", fmt.Errorf("找不到 pdftotext：请安装 poppler-utils poppler-data" +
		"（apt install）或设置 POPPLER_PDFTOTEXT 指向二进制路径")
}

func popplerLibDir(binDir string) string {
	lib := filepath.Join(filepath.Dir(binDir), "..", "lib", "x86_64-linux-gnu")
	if fi, e := os.Stat(lib); e == nil && fi.IsDir() {
		return lib
	}
	return ""
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	return strings.TrimSpace(s)
}

// ────── pdftotext -bbox XHTML 解析 ──────

// poppler 26 的 -bbox 输出：<page width height><word .../>...（word 直挂 page 下，
// 无 flow/block/line 嵌套；老版本有嵌套，两种路径 encoding/xml 都能容——嵌套版
// 的 word 不在 page 直接子级，用后置全路径匹配兜底）。
type bboxPage struct {
	Width     float64    `xml:"width,attr"`
	Height    float64    `xml:"height,attr"`
	Words     []bboxWord `xml:"word"`
	NestWords []bboxWord `xml:"flow>block>line>word"`
}

type bboxWord struct {
	XMin float64 `xml:"xMin,attr"`
	YMin float64 `xml:"yMin,attr"`
	XMax float64 `xml:"xMax,attr"`
	YMax float64 `xml:"yMax,attr"`
	Text string  `xml:",chardata"`
}

func (p *bboxPage) allWords() []bboxWord {
	if len(p.Words) > 0 {
		return p.Words
	}
	return p.NestWords
}

func parseBBox(data []byte) (*Doc, error) {
	// 去掉 DOCTYPE（引用外部 DTD，encoding/xml 不会取且可能因未定义实体报错）
	if i := bytes.Index(data, []byte("<!DOCTYPE")); i >= 0 {
		if j := bytes.IndexByte(data[i:], '>'); j >= 0 {
			data = append(data[:i], data[i+j+1:]...)
		}
	}
	// 手工 token 遍历：poppler 不同版本/输出方式的根结构不一（<doc> 直挂 或
	// <html><body><doc>），按元素名任意深度抓 <page>，两种输出都兼容。
	dec := xml.NewDecoder(bytes.NewReader(data))
	dec.Strict = false
	var pages []bboxPage
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("pdftotext bbox 解析: %w", err)
		}
		if se, ok := tok.(xml.StartElement); ok && se.Name.Local == "page" {
			var p bboxPage
			if err := dec.DecodeElement(&p, &se); err != nil {
				return nil, fmt.Errorf("pdftotext bbox page 解析: %w", err)
			}
			pages = append(pages, p)
		}
	}
	out := &Doc{}
	var sb []byte
	yOffset := 0.0
	prevEnd := 0.0
	var prevW *bboxWord
	first := true
	for _, page := range pages {
		for _, w := range page.allWords() {
			w.Text = normalizeFullWidth(w.Text)
			runes := []rune(w.Text)
			if len(runes) == 0 {
				continue
			}
			cw := (w.XMax - w.XMin) / float64(len(runes)) // 逐字形宽度均摊近似
			run := Run{Text: w.Text, Glyphs: make([]Glyph, len(runes))}
			for j, ch := range runes {
				run.Glyphs[j] = Glyph{Ch: ch, X: w.XMin + cw*float64(j), Y: w.YMin + yOffset, W: cw}
			}
			out.Runs = append(out.Runs, run)

			// 文本重建：纵向区间不重叠 → 换行；同视觉行内间隙超 GAP（1.6pt）补空格。
			// 用区间重叠而非 ΔyMin 阈值：同视觉行不同字号的词（扬州/站）yMin 可差数 pt。
			if !first {
				if !verticalOverlap(prevW, &w) {
					sb = append(sb, '\n')
				} else if w.XMin-prevEnd > 1.6 {
					sb = append(sb, ' ')
				}
			}
			first = false
			sb = append(sb, w.Text...)
			prevEnd = w.XMax
			prevW = &w
		}
		yOffset += page.Height
	}
	out.Text = string(sb)
	return out, nil
}

// verticalOverlap 两词的纵向区间是否相交（容忍 2pt 间隙，行距极近的小字也算同段）。
func verticalOverlap(a, b *bboxWord) bool {
	if a == nil || b == nil {
		return false
	}
	return b.YMin < a.YMax+2 && a.YMin < b.YMax+2
}

// normalizeFullWidth 全角字母数字 → 半角。poppler 的 Adobe-GB1 内建映射把部分
// 票面的 ASCII 输出成全角形式（铁路票号码 ２６３２…），PDFBox 的 ToUnicode 则给
// 半角——归一化以对齐 Java 侧行为（有意偏离，见 README）。
func normalizeFullWidth(s string) string {
	need := false
	for _, c := range s {
		if (c >= 0xFF10 && c <= 0xFF19) || (c >= 0xFF21 && c <= 0xFF3A) || (c >= 0xFF41 && c <= 0xFF5A) ||
			c == 0xFF0E || c == 0xFF0C {
			need = true
			break
		}
	}
	if !need {
		return s
	}
	rs := []rune(s)
	for i, c := range rs {
		switch {
		case c >= 0xFF10 && c <= 0xFF19:
			rs[i] = c - 0xFF10 + '0'
		case c >= 0xFF21 && c <= 0xFF3A:
			rs[i] = c - 0xFF21 + 'A'
		case c >= 0xFF41 && c <= 0xFF5A:
			rs[i] = c - 0xFF41 + 'a'
		case c == 0xFF0E:
			rs[i] = '.'
		case c == 0xFF0C:
			rs[i] = ','
		}
	}
	return string(rs)
}

func abs(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}
