package auth

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// ErrBadCredentials 凭据错误（登录/wx-login 共用），上层转 401 text/plain。
type ErrBadCredentials struct{ Msg string }

func (e *ErrBadCredentials) Error() string { return e.Msg }

// Service 单管理员认证服务。
type Service struct {
	db         *sql.DB
	tokens     *TokenService
	limiter    *RateLimiter
	adminUser  string
	adminPass  string // 仅 seed 时使用（对齐 Java：已有用户则不重置）
	wxAppID    string
	wxSecret   string
	wxAllowSet map[string]bool // 白名单为空 = 拒绝所有（绝不自动建号）
	httpClient *http.Client
}

func NewService(db *sql.DB, tokens *TokenService, limiter *RateLimiter,
	adminUser, adminPass, wxAppID, wxSecret, wxAllowedOpenIDs string) *Service {
	allow := map[string]bool{}
	for _, s := range strings.Split(wxAllowedOpenIDs, ",") {
		if t := strings.TrimSpace(s); t != "" {
			allow[t] = true
		}
	}
	return &Service{
		db:         db,
		tokens:     tokens,
		limiter:    limiter,
		adminUser:  adminUser,
		adminPass:  adminPass,
		wxAppID:    wxAppID,
		wxSecret:   wxSecret,
		wxAllowSet: allow,
		httpClient: &http.Client{Timeout: 10 * time.Second}, // 对齐 spring.http.client read-timeout 10s
	}
}

// Seed 无用户时创建管理员（已有任何用户则跳过，不重置密码）。
func (s *Service) Seed() error {
	var count int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM app_user").Scan(&count); err != nil {
		return fmt.Errorf("count users: %w", err)
	}
	if count > 0 {
		return nil
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(s.adminPass), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	if _, err := s.db.Exec(
		"INSERT INTO app_user (username, password_hash) VALUES (?, ?)", s.adminUser, string(hash)); err != nil {
		return fmt.Errorf("seed admin: %w", err)
	}
	slog.Info("已创建管理员账号", "username", s.adminUser)
	return nil
}

// Login 用户名密码登录。限流先行（成功也计数），凭据错误不区分「用户不存在/密码错」。
func (s *Service) Login(username, password, clientIP string) (token string, err error) {
	if err := s.limiter.Check(clientIP); err != nil {
		return "", err
	}
	var hash string
	err = s.db.QueryRow("SELECT password_hash FROM app_user WHERE username = ?", username).Scan(&hash)
	if err != nil || bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) != nil {
		return "", &ErrBadCredentials{Msg: "用户名或密码错误"}
	}
	return s.tokens.Issue(username)
}

// WxLogin 小程序登录：code → code2session 换 openid → 白名单校验 → 签发 admin JWT。
func (s *Service) WxLogin(code, clientIP string) (token string, err error) {
	if err := s.limiter.Check(clientIP); err != nil {
		return "", err
	}
	if s.wxAppID == "" || s.wxSecret == "" {
		return "", &ErrBadCredentials{Msg: "未配置微信登录（WX_APPID / WX_SECRET）"}
	}
	openID, err := s.code2Session(code)
	if err != nil {
		return "", err
	}
	if !s.wxAllowSet[openID] {
		// 首次绑定：留空登录一次，日志打出 openid，填入 WX_ALLOWED_OPENIDS 重启。
		slog.Warn("微信登录被拒绝: openid 不在白名单内（如需绑定，加入 WX_ALLOWED_OPENIDS 后重启）", "openid", openID)
		return "", &ErrBadCredentials{Msg: "该微信未绑定"}
	}
	return s.tokens.Issue(s.adminUser)
}

type wxSession struct {
	OpenID  string `json:"openid"`
	UnionID string `json:"unionid"`
	ErrCode *int   `json:"errcode"`
	ErrMsg  string `json:"errmsg"`
}

func (s *Service) code2Session(code string) (string, error) {
	q := url.Values{}
	q.Set("appid", s.wxAppID)
	q.Set("secret", s.wxSecret)
	q.Set("js_code", code)
	q.Set("grant_type", "authorization_code")
	resp, err := s.httpClient.Get("https://api.weixin.qq.com/sns/jscode2session?" + q.Encode())
	if err != nil {
		slog.Warn("调用微信 code2session 失败", "err", err)
		return "", &ErrBadCredentials{Msg: "微信登录服务不可用，请稍后再试"}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil || resp.StatusCode != http.StatusOK {
		slog.Warn("调用微信 code2session 失败", "status", resp.StatusCode, "err", err)
		return "", &ErrBadCredentials{Msg: "微信登录服务不可用，请稍后再试"}
	}
	// 微信部分场景返回 text/plain 的 JSON 体——按字节解，不依赖 Content-Type。
	var session wxSession
	if err := json.Unmarshal(body, &session); err != nil {
		slog.Warn("code2session 响应解析失败", "err", err)
		return "", &ErrBadCredentials{Msg: "微信登录服务不可用，请稍后再试"}
	}
	if session.ErrCode != nil && *session.ErrCode != 0 {
		slog.Warn("code2session 返回错误", "errcode", *session.ErrCode, "errmsg", session.ErrMsg)
		return "", &ErrBadCredentials{Msg: "微信登录失败，请重试"}
	}
	if strings.TrimSpace(session.OpenID) == "" {
		slog.Warn("code2session 成功但 openid 为空")
		return "", &ErrBadCredentials{Msg: "微信登录失败，请重试"}
	}
	return session.OpenID, nil
}

// ClientIP 解析客户端 IP：仅 TRUST_PROXY=true 时信任 X-Real-IP（nginx 会覆盖客户端
// 自带值）；绝不用 X-Forwarded-For——首个元素是可伪造值，可绕过限流并陷害 IP。
func ClientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if v := strings.TrimSpace(r.Header.Get("X-Real-IP")); v != "" {
			return v
		}
	}
	host := r.RemoteAddr
	if i := strings.LastIndex(host, ":"); i > 0 {
		host = host[:i]
	}
	return strings.Trim(host, "[]")
}
