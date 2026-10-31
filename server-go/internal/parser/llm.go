package parser

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"

	"invoice-go/internal/metrics"
	"invoice-go/internal/money"
)

// LLMExtractor 兜底结构化抽取：正则覆盖已知版式，缺失字段交给 OpenAI
// chat-completions 兼容服务补齐。调用失败不是错误——null 保留、上传继续。
type LLMExtractor struct {
	client  *http.Client
	baseURL string
	model   string
	enabled bool
	apiKey  string
}

// UA 伪装：opencode 按 User-Agent 限流，伪装官方客户端绕过 429（Java 版同款）。
const llmUserAgent = "opencode/1.18.23 (linux 6.1.0-37-amd64; x64)"

const llmSystemPrompt = `你是增值税发票信息抽取助手。从发票文本中提取字段，只输出 JSON，不要解释。
字段说明：invoiceNumber 发票号码（20位数字）；invoiceDate 开票日期 YYYY-MM-DD；
buyerName/buyerTaxId 购买方名称/税号；sellerName/sellerTaxId 销售方名称/税号；
category 项目名称（*…* 开头）；totalAmount 金额合计（不含税，两位小数数字）；
taxAmount 税额合计；totalWithTax 价税合计。
金额字段输出纯数字如 149.00，不要带货币符号和千分位。无法确定的字段输出 null。`

// 控制字符剥离：Java \p{Cntrl} = Cc（\x00-\x1F \x7F-\x9F），保留 \r\n\t。
var reControl = regexp.MustCompile(`[\x00-\x09\x0B\x0C\x0E-\x1F\x7F-\x9F]`)

var reCodeFence = regexp.MustCompile("(?s)^```\\w*\\s*|\\s*```$")

func NewLLMExtractor(baseURL, model string, enabled bool, timeoutSeconds int, apiKey string) *LLMExtractor {
	// connect 5s / read timeoutSeconds：TCP 建立阶段 60s 只会让两次重试最坏占住
	// 上传线程 ~2 分钟；read 用可配置值（本地大模型首 token 可能要数十秒）。
	transport := &http.Transport{
		DialContext:           (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: time.Duration(timeoutSeconds) * time.Second,
	}
	return &LLMExtractor{
		client:  &http.Client{Transport: transport},
		baseURL: strings.TrimRight(baseURL, "/"),
		model:   model,
		enabled: enabled,
		apiKey:  apiKey,
	}
}

// Fill 只补 parsed 中为 nil 的字段；最多两次尝试，全失败返回原 parsed。
func (e *LLMExtractor) Fill(parsed Parsed, text string, ctx *metrics.Context) Parsed {
	if !e.enabled {
		return parsed
	}
	missing := parsed.MissingFields()
	if len(missing) == 0 {
		return parsed
	}
	if ctx != nil {
		ctx.LLMTriggered = true
	}
	slog.Info("LLM fill missing", "fields", missing)

	for attempt := 1; attempt <= 2; attempt++ {
		start := time.Now()
		reply, err := e.callLLM(text, missing)
		elapsed := time.Since(start).Milliseconds()
		if err == nil {
			if ctx != nil {
				ctx.RecordLLMAPICall(true, elapsed)
			}
			merged := merge(parsed, reply)
			if ctx != nil {
				ctx.LLMFillSuccess = len(merged.MissingFields()) == 0
			}
			slog.Info("LLM fill done", "attempt", attempt)
			return merged
		}
		if ctx != nil {
			ctx.RecordLLMAPICall(false, 0)
		}
		if attempt == 2 {
			// LLM 是 best-effort，抖动不能拖垮上传。
			if ctx != nil {
				ctx.LLMFillSuccess = false
			}
			slog.Warn("LLM fill failed", "err", err)
			return parsed
		}
		slog.Info("LLM fill attempt failed, retrying", "attempt", attempt, "err", err)
	}
	return parsed
}

type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

func (e *LLMExtractor) callLLM(text string, missing []string) (map[string]any, error) {
	clean := reControl.ReplaceAllString(text, "")
	prompt := "请提取以下字段：" + strings.Join(missing, ", ") + "\n\n发票文本如下：\n" + clean
	body, err := json.Marshal(map[string]any{
		"model": e.model,
		"messages": []chatMessage{
			{Role: "system", Content: llmSystemPrompt},
			{Role: "user", Content: prompt},
		},
		"temperature":     0,
		"max_tokens":      500,
		"response_format": map[string]string{"type": "json_object"},
	})
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.baseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json;charset=UTF-8")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", llmUserAgent)
	if strings.TrimSpace(e.apiKey) != "" {
		req.Header.Set("Authorization", "Bearer "+e.apiKey)
	}
	resp, err := e.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("llm http %d: %s", resp.StatusCode, string(b))
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	return parseLLMReply(string(raw))
}

// parseLLMReply 兼容部分服务在 JSON 后追加 SSE framing（data: [DONE]）：
// 只取最后一个 '}'（含）之前的内容再解析。UseNumber 保留数字字面量
//（"149.00" 不退化为 float64 149，与 Jackson asText() 行为一致）。
func parseLLMReply(resp string) (map[string]any, error) {
	end := strings.LastIndex(resp, "}")
	if end < 0 {
		return nil, fmt.Errorf("no JSON in LLM reply")
	}
	dec := json.NewDecoder(strings.NewReader(resp[:end+1]))
	dec.UseNumber()
	var root struct {
		Choices []struct {
			Message struct {
				Content any `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := dec.Decode(&root); err != nil {
		return nil, fmt.Errorf("llm reply: %w", err)
	}
	if len(root.Choices) == 0 {
		return nil, fmt.Errorf("llm reply has no choices")
	}
	content := contentAsString(root.Choices[0].Message.Content)
	content = strings.TrimSpace(reCodeFence.ReplaceAllString(content, ""))
	content = strings.TrimSpace(content)
	if content == "" || content == "null" {
		return nil, fmt.Errorf("LLM returned empty content")
	}
	dec2 := json.NewDecoder(strings.NewReader(content))
	dec2.UseNumber()
	var reply map[string]any
	if err := dec2.Decode(&reply); err != nil {
		return nil, fmt.Errorf("LLM reply is not JSON object: %s", content)
	}
	return reply, nil
}

// contentAsString：content 通常是 string，但个别兼容服务返回嵌套 JSON——非串时回退原始 JSON 文本。
func contentAsString(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case nil:
		return ""
	default:
		b, _ := json.Marshal(t)
		return string(b)
	}
}

// merge 已有非 nil 值永远保留（正则胜过 LLM），只填缺失字段；坏值置 nil（不报错）。
func merge(p Parsed, reply map[string]any) Parsed {
	or := func(existing *string, key string) *string {
		if existing != nil {
			return existing
		}
		n, ok := reply[key]
		if !ok || n == nil {
			return nil
		}
		s, ok := n.(string)
		if !ok {
			// 非串标量按文本处理（对齐 Jackson asText()）；对象/数组视为无效。
			switch t := n.(type) {
			case json.Number, bool:
				s = fmt.Sprint(t)
			default:
				return nil
			}
		}
		return strPtr(strings.TrimSpace(s))
	}
	date := p.InvoiceDate
	if date == nil {
		date = parseISOOOrNull(reply["invoiceDate"])
	}
	totalAmount := p.TotalAmount
	if totalAmount.IsNull() {
		totalAmount = decimalOrNull(reply["totalAmount"])
	}
	taxAmount := p.TaxAmount
	if taxAmount.IsNull() {
		taxAmount = decimalOrNull(reply["taxAmount"])
	}
	totalWithTax := p.TotalWithTax
	if totalWithTax.IsNull() {
		totalWithTax = decimalOrNull(reply["totalWithTax"])
	}
	return Parsed{
		InvoiceNumber: or(p.InvoiceNumber, "invoiceNumber"),
		InvoiceDate:   date,
		BuyerName:     or(p.BuyerName, "buyerName"),
		BuyerTaxID:    or(p.BuyerTaxID, "buyerTaxId"),
		SellerName:    or(p.SellerName, "sellerName"),
		SellerTaxID:   or(p.SellerTaxID, "sellerTaxId"),
		Category:      or(p.Category, "category"),
		TotalAmount:   totalAmount,
		TaxAmount:     taxAmount,
		TotalWithTax:  totalWithTax,
	}
}

// parseISOOOrNull 严格 ISO yyyy-MM-dd（对齐 LocalDate.parse），失败 nil。
func parseISOOOrNull(v any) *string {
	s, ok := v.(string)
	if !ok {
		return nil
	}
	t, err := time.Parse("2006-01-02", strings.TrimSpace(s))
	if err != nil {
		return nil
	}
	return strPtr(t.Format("2006-01-02"))
}

func decimalOrNull(v any) money.Decimal {
	switch t := v.(type) {
	case string:
		d, err := money.New(strings.TrimSpace(t))
		if err != nil {
			return money.Decimal{}
		}
		return d
	case json.Number:
		d, err := money.New(strings.TrimSpace(t.String()))
		if err != nil {
			return money.Decimal{}
		}
		return d
	default:
		return money.Decimal{}
	}
}
