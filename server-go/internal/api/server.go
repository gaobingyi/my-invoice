// Package api HTTP 层：路由、认证中间件、错误映射、分页响应形状。
// 全部行为对齐 my-invoice 的 Controller + GlobalExceptionHandler：
// 业务错误体是 text/plain 字符串，未认证是 JSON {"message":...}，500 固定文案。
package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"invoice-go/internal/apierr"
	"invoice-go/internal/auth"
	"invoice-go/internal/config"
	"invoice-go/internal/export"
	"invoice-go/internal/invoice"
	"invoice-go/internal/metrics"
)

type Server struct {
	Cfg      *config.Config
	Auth     *auth.Service
	Tokens   *auth.TokenService
	Invoices *invoice.Service
	Export   *export.Service
	Metrics  *metrics.Service
	Mux      *http.ServeMux
	Static   http.Handler // nil = 不服务静态文件
}

func NewServer(cfg *config.Config, a *auth.Service, tokens *auth.TokenService,
	inv *invoice.Service, exp *export.Service, m *metrics.Service, static http.Handler) *Server {
	s := &Server{Cfg: cfg, Auth: a, Tokens: tokens, Invoices: inv, Export: exp, Metrics: m, Static: static}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/auth/login", s.handleLogin)
	mux.HandleFunc("POST /api/auth/wx-login", s.handleWxLogin)
	mux.HandleFunc("GET /api/auth/ping", s.handlePing)
	mux.HandleFunc("POST /api/invoices/upload", s.handleUpload)
	mux.HandleFunc("GET /api/invoices", s.handleList)
	mux.HandleFunc("GET /api/invoices/summary", s.handleSummary)
	mux.HandleFunc("GET /api/invoices/{id}", s.handleGet)
	mux.HandleFunc("DELETE /api/invoices/{id}", s.handleDelete)
	mux.HandleFunc("GET /api/invoices/{id}/file", s.handleFile)
	mux.HandleFunc("POST /api/export-batches", s.handleCreateBatch)
	mux.HandleFunc("GET /api/export-batches", s.handleListBatches)
	mux.HandleFunc("GET /api/export-batches/{id}/invoices", s.handleBatchInvoices)
	mux.HandleFunc("POST /api/export-batches/{id}/invoices", s.handleAddToBatch)
	mux.HandleFunc("DELETE /api/export-batches/{id}/invoices/{invoiceId}", s.handleRemoveFromBatch)
	mux.HandleFunc("DELETE /api/export-batches/{id}", s.handleDeleteBatch)
	mux.HandleFunc("GET /api/export-batches/{id}/zip", s.handleBatchZip)
	mux.HandleFunc("GET /api/metrics/parsing", s.handleMetrics)
	mux.HandleFunc("GET /api/metrics/parsing/trend", s.handleMetricsTrend)
	s.Mux = mux
	return s
}

// ServeHTTP 认证闸门：/api/auth/** 公开，其余 /api/** 一律 Bearer；
// 非认证失败是 JSON {"message":"未登录或登录已过期"}（对齐 SecurityConfig）。
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/api/") {
		if !strings.HasPrefix(r.URL.Path, "/api/auth/") {
			if !s.requireAuth(w, r) {
				return
			}
		}
		s.Mux.ServeHTTP(w, r)
		return
	}
	if s.Static != nil {
		s.Static.ServeHTTP(w, r)
		return
	}
	http.NotFound(w, r)
}

func (s *Server) requireAuth(w http.ResponseWriter, r *http.Request) bool {
	h := r.Header.Get("Authorization")
	token, ok := strings.CutPrefix(h, "Bearer ")
	if !ok || strings.TrimSpace(token) == "" {
		writeUnauthorized(w)
		return false
	}
	if _, err := s.Tokens.Parse(strings.TrimSpace(token)); err != nil {
		writeUnauthorized(w)
		return false
	}
	return true
}

func writeUnauthorized(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json;charset=UTF-8")
	w.WriteHeader(http.StatusUnauthorized)
	_, _ = w.Write([]byte(`{"message":"未登录或登录已过期"}`))
}

// writeErr 全局错误映射（对齐 GlobalExceptionHandler）：
// 400/409/413 → text/plain message；500 → 固定「服务器内部错误」。
func writeErr(w http.ResponseWriter, err error) {
	var bad *apierr.BadRequest
	var dup *apierr.Duplicate
	var tooLarge *apierr.TooLarge
	var creds *auth.ErrBadCredentials
	switch {
	case errors.As(err, &bad):
		writeText(w, http.StatusBadRequest, bad.Msg)
	case errors.As(err, &dup):
		writeText(w, http.StatusConflict, dup.Msg)
	case errors.As(err, &tooLarge):
		writeText(w, http.StatusRequestEntityTooLarge, tooLarge.Msg)
	case errors.As(err, &creds):
		writeText(w, http.StatusUnauthorized, creds.Msg)
	default:
		slog.Error("服务器内部错误", "err", unwrapAll(err))
		writeText(w, http.StatusInternalServerError, "服务器内部错误")
	}
}

// unwrapAll 展开错误链（apierr.Internal 的 Error() 只是固定文案，日志要根因）。
func unwrapAll(err error) string {
	var parts []string
	for err != nil {
		parts = append(parts, err.Error())
		err = errors.Unwrap(err)
		if len(parts) > 8 {
			break
		}
	}
	return strings.Join(parts, " <- ")
}

func writeText(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "text/plain;charset=UTF-8")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(msg))
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json;charset=UTF-8")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	_ = enc.Encode(v)
}

// ────── 分页响应（Spring Data Page 形状，前端用 content/totalPages/totalElements）──

type sortShape struct {
	Empty    bool `json:"empty"`
	Sorted   bool `json:"sorted"`
	Unsorted bool `json:"unsorted"`
}

type pageableShape struct {
	Sort       sortShape `json:"sort"`
	PageNumber int       `json:"pageNumber"`
	PageSize   int       `json:"pageSize"`
	Offset     int64     `json:"offset"`
	Paged      bool      `json:"paged"`
	Unpaged    bool      `json:"unpaged"`
}

type pageShape struct {
	Content          any           `json:"content"`
	Pageable         pageableShape `json:"pageable"`
	TotalPages       int64         `json:"totalPages"`
	TotalElements    int64         `json:"totalElements"`
	Last             bool          `json:"last"`
	Size             int           `json:"size"`
	Number           int           `json:"number"`
	Sort             sortShape     `json:"sort"`
	NumberOfElements int64         `json:"numberOfElements"`
	First            bool          `json:"first"`
	Empty            bool          `json:"empty"`
}

func newPage(content any, number, size int, total int64) pageShape {
	totalPages := (total + int64(size) - 1) / int64(size)
	numberOf := int64(size)
	if number >= int(totalPages) && total > 0 {
		numberOf = total - int64(number)*int64(size)
		if numberOf < 0 {
			numberOf = 0
		}
	} else if total == 0 {
		numberOf = 0
	}
	so := sortShape{Empty: true, Unsorted: true}
	return pageShape{
		Content:          content,
		Pageable:         pageableShape{Sort: so, PageNumber: number, PageSize: size, Offset: int64(number) * int64(size), Paged: true},
		TotalPages:       totalPages,
		TotalElements:    total,
		Last:             number >= int(totalPages)-1 || total == 0,
		Size:             size,
		Number:           number,
		Sort:             so,
		NumberOfElements: numberOf,
		First:            number == 0,
		Empty:            total == 0,
	}
}
