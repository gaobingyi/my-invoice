// Package config 从环境变量装配全部配置（对齐 Java 版 application.yml 的键与默认值）。
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	DBPath     string // INVOICE_DB（Java 侧对应 SPRING_DATASOURCE_URL 的文件路径）
	UploadDir  string // UPLOAD_DIR
	ServerAddr string // SERVER_ADDRESS，形如 "127.0.0.1:8080"；默认 :8080
	StaticDir  string // STATIC_DIR：Vue dist 目录，空 = 不服务静态文件

	JWTSecret        string // JWT_SECRET，启动时强制 ≥32 字节
	JWTExpireSeconds int    // 固定 86400（Java 侧仅 yml 可调，无 env）

	RateLimitEnabled bool // 默认 true，无 env 开关（对齐 login.rate-limit-enabled）
	TrustProxy       bool // TRUST_PROXY：true 时 client IP 取 X-Real-IP

	AdminUsername string // ADMIN_USERNAME
	AdminPassword string // ADMIN_PASSWORD（仅 seed 时生效）

	LLMEnabled        bool   // LLM_ENABLED
	LLMBaseURL        string // LLM_BASE_URL
	LLMAPIKey         string // LLM_API_KEY
	LLMModel          string // LLM_MODEL
	LLMTimeoutSeconds int    // 固定 60（对齐 yml，无 env）

	WXAppID          string // WX_APPID
	WXSecret         string // WX_SECRET
	WXAllowedOpenIDs string // WX_ALLOWED_OPENIDS，逗号分隔
}

func Load() (*Config, error) {
	c := &Config{
		DBPath:            env("INVOICE_DB", "./data/invoice.db"),
		UploadDir:         env("UPLOAD_DIR", "./uploads"),
		ServerAddr:        env("SERVER_ADDRESS", ":8080"),
		StaticDir:         os.Getenv("STATIC_DIR"),
		JWTSecret:         env("JWT_SECRET", "change-me-in-production-please-32bytes-or-more"),
		JWTExpireSeconds:  86400,
		RateLimitEnabled:  true,
		TrustProxy:        envBool("TRUST_PROXY", false),
		AdminUsername:     env("ADMIN_USERNAME", "admin"),
		AdminPassword:     env("ADMIN_PASSWORD", "admin123"),
		LLMEnabled:        envBool("LLM_ENABLED", true),
		LLMBaseURL:        env("LLM_BASE_URL", "https://opencode.ai/zen/v1"),
		LLMAPIKey:         env("LLM_API_KEY", "public"),
		LLMModel:          env("LLM_MODEL", "big-pickle"),
		LLMTimeoutSeconds: 60,
		WXAppID:           os.Getenv("WX_APPID"),
		WXSecret:          os.Getenv("WX_SECRET"),
		WXAllowedOpenIDs:  os.Getenv("WX_ALLOWED_OPENIDS"),
	}
	// 对齐 jjwt：HMAC 密钥不足 32 字节直接拒绝启动（Java 版 WeakKeyException）。
	if len(c.JWTSecret) < 32 {
		return nil, fmt.Errorf("JWT_SECRET 必须至少 32 字节（当前 %d）", len(c.JWTSecret))
	}
	return c, nil
}

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return def
	}
	return b
}
