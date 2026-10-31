package api

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
)

// ParsingMetricsResponse 字段与 Java DTO 逐字对齐；分母为 0 的成功率整体省略
//（对齐 @JsonInclude(NON_NULL)）。
type metricsResponse struct {
	UploadsTotal     int64             `json:"uploadsTotal"`
	UploadsPdfError  int64             `json:"uploadsPdfError"`
	Regex            *rateGroup        `json:"regex"`
	LLM              *llmGroup         `json:"llm"`
	RegexFieldMisses map[string]int64  `json:"regexFieldMisses"`
	UpdatedAt        *string           `json:"updatedAt"`
}

type rateGroup struct {
	Success     int64    `json:"success"`
	Failure     int64    `json:"failure"`
	SuccessRate *float64 `json:"successRate"`
}

type llmGroup struct {
	Triggered       int64    `json:"triggered"`
	FillSuccess     int64    `json:"fillSuccess"`
	FillFailure     int64    `json:"fillFailure"`
	FillSuccessRate *float64 `json:"fillSuccessRate"`
	APISuccess      int64    `json:"apiSuccess"`
	APIFailure      int64    `json:"apiFailure"`
	APISuccessRate  *float64 `json:"apiSuccessRate"`
	AvgResponseTime *int64   `json:"avgResponseTimeMs"`
}

type trendEntry struct {
	Date           string `json:"date"`
	Uploads        int64  `json:"uploads"`
	PdfError       int64  `json:"pdfError"`
	RegexSuccess   int64  `json:"regexSuccess"`
	RegexFailure   int64  `json:"regexFailure"`
	LLMTriggered   int64  `json:"llmTriggered"`
	LLMFillSuccess int64  `json:"llmFillSuccess"`
	LLMAPISuccess  int64  `json:"llmApiSuccess"`
	LLMAPIFailure  int64  `json:"llmApiFailure"`
	AvgLLMMS       int64  `json:"avgLlmMs"`
}

func ratePtr(num, den int64) *float64 {
	if den == 0 {
		return nil
	}
	f := float64(num) / float64(den)
	return &f
}

func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	var (
		uploadsTotal, uploadsPdfError, regexSuccess, regexFailure int64
		llmTriggered, llmFillSuccess, llmFillFailure              int64
		llmAPISuccess, llmAPIFailure, llmAPITotalMS, llmAPICount  int64
		missesJSON, updatedAt                                     sql.NullString
	)
	err := s.Metrics.DB().QueryRow(`SELECT
	      uploads_total, uploads_pdf_error, regex_success, regex_failure,
	      llm_triggered, llm_fill_success, llm_fill_failure,
	      llm_api_success, llm_api_failure, llm_api_total_ms, llm_api_count,
	      regex_misses_json, updated_at
	    FROM parsing_metrics WHERE id = 1`).Scan(
		&uploadsTotal, &uploadsPdfError, &regexSuccess, &regexFailure,
		&llmTriggered, &llmFillSuccess, &llmFillFailure,
		&llmAPISuccess, &llmAPIFailure, &llmAPITotalMS, &llmAPICount,
		&missesJSON, &updatedAt)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	misses := map[string]int64{}
	if missesJSON.Valid && missesJSON.String != "" {
		_ = json.Unmarshal([]byte(missesJSON.String), &misses) // 坏 JSON → 空 map
	}
	// 按次数降序排（对齐 Java 排序输出；JSON 对象键序不敏感，保持一致便于阅读）
	updatedAtStr := updatedAt.String
	var avgMS *int64
	if llmAPICount > 0 {
		v := llmAPITotalMS / llmAPICount
		avgMS = &v
	}
	resp := metricsResponse{
		UploadsTotal:    uploadsTotal,
		UploadsPdfError: uploadsPdfError,
		Regex: &rateGroup{
			Success:     regexSuccess,
			Failure:     regexFailure,
			SuccessRate: ratePtr(regexSuccess, regexSuccess+regexFailure),
		},
		LLM: &llmGroup{
			Triggered:       llmTriggered,
			FillSuccess:     llmFillSuccess,
			FillFailure:     llmFillFailure,
			FillSuccessRate: ratePtr(llmFillSuccess, llmTriggered),
			APISuccess:      llmAPISuccess,
			APIFailure:      llmAPIFailure,
			APISuccessRate:  ratePtr(llmAPISuccess, llmAPISuccess+llmAPIFailure),
			AvgResponseTime: avgMS,
		},
		RegexFieldMisses: misses,
		UpdatedAt:        &updatedAtStr,
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleMetricsTrend(w http.ResponseWriter, r *http.Request) {
	days := 30
	if v := r.URL.Query().Get("days"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			writeErr(w, serverErr(err)) // 类型转换失败 → 500 兜底（对齐 Spring）
			return
		}
		days = n
	}
	if days < 1 {
		days = 1
	}
	if days > 365 {
		days = 365
	}
	rows, err := s.Metrics.DB().Query(`SELECT
	      date(created_at)                     AS d,
	      COUNT(*)                             AS uploads,
	      SUM(pdf_error)                       AS pdf_error,
	      SUM(regex_success)                   AS regex_success,
	      COUNT(*) - SUM(regex_success)        AS regex_failure,
	      SUM(llm_triggered)                   AS llm_triggered,
	      SUM(llm_fill_success)                AS llm_fill_success,
	      SUM(llm_api_success)                 AS llm_api_success,
	      SUM(llm_api_failure)                 AS llm_api_failure,
	      SUM(llm_api_ms)                      AS total_llm_ms,
	      SUM(CASE WHEN llm_api_ms > 0 THEN 1 ELSE 0 END) AS llm_call_count
	    FROM parsing_log
	    WHERE created_at >= date('now', 'localtime', ? || ' days')
	    GROUP BY date(created_at)
	    ORDER BY d`, -days)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	defer rows.Close()
	out := []trendEntry{}
	for rows.Next() {
		var e trendEntry
		var totalMS, callCount int64
		if err := rows.Scan(&e.Date, &e.Uploads, &e.PdfError, &e.RegexSuccess, &e.RegexFailure,
			&e.LLMTriggered, &e.LLMFillSuccess, &e.LLMAPISuccess, &e.LLMAPIFailure,
			&totalMS, &callCount); err != nil {
			writeErr(w, serverErr(err))
			return
		}
		if callCount > 0 {
			e.AvgLLMMS = totalMS / callCount
		}
		out = append(out, e)
	}
	if err := rows.Err(); err != nil {
		writeErr(w, serverErr(err))
		return
	}
	writeJSON(w, http.StatusOK, out)
}
