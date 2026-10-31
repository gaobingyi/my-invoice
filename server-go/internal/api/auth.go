package api

import (
	"encoding/json"
	"net/http"

	"invoice-go/internal/auth"
)

// 登录请求体：Java 侧 @NotBlank——空白字段走 500 兜底（Spring 参数校验异常
// 无专门映射），这里逐字对齐。
type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type loginResponse struct {
	Token    string `json:"token"`
	Username string `json:"username"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, serverErr(err)) // 畸形 body → 500（对齐 Spring 兜底）
		return
	}
	if req.Username == "" || req.Password == "" {
		writeErr(w, serverErr(nil)) // @NotBlank 违反 → 500 服务器内部错误
		return
	}
	ip := auth.ClientIP(r, s.Cfg.TrustProxy)
	token, err := s.Auth.Login(req.Username, req.Password, ip)
	if err != nil {
		writeErr(w, err) // ErrBadCredentials → 401 text/plain
		return
	}
	writeJSON(w, http.StatusOK, loginResponse{Token: token, Username: req.Username})
}

type wxLoginRequest struct {
	Code string `json:"code"`
}

func (s *Server) handleWxLogin(w http.ResponseWriter, r *http.Request) {
	var req wxLoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, serverErr(err))
		return
	}
	if req.Code == "" {
		writeErr(w, serverErr(nil))
		return
	}
	ip := auth.ClientIP(r, s.Cfg.TrustProxy)
	token, err := s.Auth.WxLogin(req.Code, ip)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, loginResponse{Token: token, Username: s.Cfg.AdminUsername})
}

func (s *Server) handlePing(w http.ResponseWriter, _ *http.Request) {
	writeText(w, http.StatusOK, "pong")
}
