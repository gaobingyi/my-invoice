// Package metrics 持有两张表：parsing_metrics（单行计数器，Dashboard 卡片来源）与
// parsing_log（每次解析一行明细，趋势图来源）。用法：每次解析创建 Context，各阶段
// 写入，最后 Flush 一次性落库。
package metrics

import (
	"database/sql"
	"encoding/json"
	"log/slog"
	"strings"
)

// Context 单次解析的各阶段结果收集器（对齐 Java 版 ParseContext）。
type Context struct {
	PdfError          bool
	RegexSuccess      bool
	RegexMissingField []string
	LLMTriggered      bool
	LLMFillSuccess    bool
	llmAPISuccess     int
	llmAPIFailure     int
	llmAPITotalMS     int64
}

// RecordLLMAPICall 记录一次 LLM HTTP 调用（成功与否 + 耗时）。
func (c *Context) RecordLLMAPICall(success bool, elapsedMS int64) {
	if success {
		c.llmAPISuccess++
	} else {
		c.llmAPIFailure++
	}
	c.llmAPITotalMS += elapsedMS
}

// Service 落库与查询。所有写路径都是观测路径：失败只 warn 绝不上抛——否则已解析
// 成功、文件已落盘的上传会因指标失败整体 500。
type Service struct {
	db *sql.DB
}

func New(db *sql.DB) *Service { return &Service{db: db} }

// DB 供查询侧（API handlers）只读访问。
func (s *Service) DB() *sql.DB { return s.db }

func (s *Service) EnsureRow() error {
	_, err := s.db.Exec("INSERT OR IGNORE INTO parsing_metrics (id) VALUES (1)")
	return err
}

// RecordRegexResult 把 Parsed 的缺失字段（已由调用方算好）写入 context。
func (s *Service) RecordRegexResult(missingFields []string, ctx *Context) {
	ctx.RegexSuccess = len(missingFields) == 0
	ctx.RegexMissingField = missingFields
}

// Flush 三条语句（计数器累加、misses JSON、明细行）在同一事务提交——中途失败不产生
// Dashboard 与趋势图对不上的半截快照；失败整体回滚并只记 warn。
func (s *Service) Flush(ctx *Context) {
	tx, err := s.db.Begin()
	if err != nil {
		slog.Warn("解析指标落库失败（不影响上传）", "err", err)
		return
	}
	if err := s.doFlush(tx, ctx); err != nil {
		tx.Rollback()
		slog.Warn("解析指标落库失败（不影响上传）", "err", err)
		return
	}
	if err := tx.Commit(); err != nil {
		slog.Warn("解析指标落库失败（不影响上传）", "err", err)
	}
}

func (s *Service) doFlush(tx *sql.Tx, ctx *Context) error {
	// 1. 原子递增 parsing_metrics 各计数器
	_, err := tx.Exec(`UPDATE parsing_metrics SET
	      uploads_total     = uploads_total + 1,
	      uploads_pdf_error = uploads_pdf_error + ?,
	      regex_success     = regex_success + ?,
	      regex_failure     = regex_failure + ?,
	      llm_triggered     = llm_triggered + ?,
	      llm_fill_success  = llm_fill_success + ?,
	      llm_fill_failure  = llm_fill_failure + ?,
	      llm_api_success   = llm_api_success + ?,
	      llm_api_failure   = llm_api_failure + ?,
	      llm_api_total_ms  = llm_api_total_ms + ?,
	      llm_api_count     = llm_api_count + ?,
	      updated_at        = datetime('now','localtime')
	    WHERE id = 1`,
		b2i(ctx.PdfError),
		b2i(ctx.RegexSuccess),
		b2i(!ctx.RegexSuccess),
		b2i(ctx.LLMTriggered),
		b2i(ctx.LLMTriggered && ctx.LLMFillSuccess),
		b2i(ctx.LLMTriggered && !ctx.LLMFillSuccess),
		ctx.llmAPISuccess,
		ctx.llmAPIFailure,
		ctx.llmAPITotalMS,
		ctx.llmAPISuccess+ctx.llmAPIFailure)
	if err != nil {
		return err
	}
	// 2. regex_misses_json（read-modify-write，单连接下无并发窗口）
	if len(ctx.RegexMissingField) > 0 {
		if err := updateRegexMissesJSON(tx, ctx.RegexMissingField); err != nil {
			return err
		}
	}
	// 3. parsing_log 明细行
	missingJSON, _ := json.Marshal(ctx.RegexMissingField)
	_, err = tx.Exec(`INSERT INTO parsing_log
	      (pdf_error, regex_success, regex_missing_json,
	       llm_triggered, llm_fill_success,
	       llm_api_success, llm_api_failure, llm_api_ms)
	    VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		b2i(ctx.PdfError),
		b2i(ctx.RegexSuccess),
		string(missingJSON),
		b2i(ctx.LLMTriggered),
		b2i(ctx.LLMTriggered && ctx.LLMFillSuccess),
		ctx.llmAPISuccess,
		ctx.llmAPIFailure,
		ctx.llmAPITotalMS)
	return err
}

func updateRegexMissesJSON(tx *sql.Tx, missingFields []string) error {
	var raw string
	err := tx.QueryRow("SELECT regex_misses_json FROM parsing_metrics WHERE id = 1").Scan(&raw)
	if err != nil && err != sql.ErrNoRows {
		return err
	}
	misses := map[string]int{}
	if strings.TrimSpace(raw) != "" {
		_ = json.Unmarshal([]byte(raw), &misses) // 坏 JSON 视为空（对齐 Java 行为）
	}
	for _, f := range missingFields {
		misses[f]++
	}
	out, err := json.Marshal(misses)
	if err != nil {
		return err
	}
	_, err = tx.Exec("UPDATE parsing_metrics SET regex_misses_json = ? WHERE id = 1", string(out))
	return err
}

// CleanupOldLogs 保留最新 1000 条 parsing_log（每 10 分钟由 ticker 调用）。
func (s *Service) CleanupOldLogs() {
	res, err := s.db.Exec(`DELETE FROM parsing_log WHERE id NOT IN (
	      SELECT id FROM parsing_log ORDER BY id DESC LIMIT 1000)`)
	if err != nil {
		slog.Warn("parsing_log 清理失败", "err", err)
		return
	}
	if n, _ := res.RowsAffected(); n > 0 {
		slog.Info("已清理旧 parsing_log 记录", "count", n)
	}
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}
