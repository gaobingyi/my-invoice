package parser

import (
	"invoice-go/internal/metrics"
	"invoice-go/internal/pdf"
)

// Engine 解析管线：pdf 提取 → parseText 正则 → Layout 字形覆盖购/销方 → LLM 兜底。
// 指标钩子在两处：损坏 PDF 也计入 uploads_pdf_error（否则 Dashboard 的
// 「PDF 解析失败」卡片恒为 0）；flush 在 parser 内完成——即使后续业务校验失败
// 或重复上传，uploads_total 也已 +1。
type Engine struct {
	LLM     *LLMExtractor // nil = 不开 LLM（单测）
	Metrics *metrics.Service // nil = 不记指标（单测）
}

func NewEngine(llm *LLMExtractor, m *metrics.Service) *Engine {
	return &Engine{LLM: llm, Metrics: m}
}

// Parse 解析单个 PDF 文件。损坏/加密返回 error（上层转 400「无法解析 PDF 内容」）。
func (e *Engine) Parse(path string) (Parsed, error) {
	var ctx *metrics.Context
	if e.Metrics != nil {
		ctx = &metrics.Context{}
	}
	doc, err := pdf.Extract(path)
	if err != nil {
		if ctx != nil {
			ctx.PdfError = true
			e.Metrics.Flush(ctx)
		}
		return Parsed{}, err
	}
	parsed := parseText(doc.Text)
	// 正则文本路径对「标签紧贴值 + 购/销两列同排」的版式无解，用字形坐标覆盖；
	// 取不到就保留正则结果。
	parsed = overrideParties(parsed, doc.Runs)
	if e.Metrics != nil {
		e.Metrics.RecordRegexResult(parsed.MissingFields(), ctx)
	}
	if e.LLM != nil {
		parsed = e.LLM.Fill(parsed, doc.Text, ctx)
	}
	if e.Metrics != nil {
		e.Metrics.Flush(ctx)
	}
	return parsed, nil
}
