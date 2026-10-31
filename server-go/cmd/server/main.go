// invoice-server Go 版入口：装配全部组件，优雅停机（SIGTERM 后等在途请求完成，
// 对齐 systemd TimeoutStopSec=35 内的 30s 宽限）。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"invoice-go/internal/api"
	"invoice-go/internal/auth"
	"invoice-go/internal/config"
	"invoice-go/internal/export"
	"invoice-go/internal/invoice"
	"invoice-go/internal/metrics"
	"invoice-go/internal/parser"
	"invoice-go/internal/store"
	"invoice-go/internal/webstatic"
)

func main() {
	if err := run(); err != nil {
		slog.Error("启动失败", "err", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, nil)))

	db, err := store.Open(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()

	metricSvc := metrics.New(db)
	if err := metricSvc.EnsureRow(); err != nil {
		return err
	}

	var llm *parser.LLMExtractor
	if cfg.LLMEnabled {
		llm = parser.NewLLMExtractor(cfg.LLMBaseURL, cfg.LLMModel, true,
			cfg.LLMTimeoutSeconds, cfg.LLMAPIKey)
	}
	engine := parser.NewEngine(llm, metricSvc)

	invSvc := invoice.NewService(db, engine, cfg.UploadDir)
	expSvc := export.NewService(db, invSvc)

	tokens := auth.NewTokenService(cfg.JWTSecret, cfg.JWTExpireSeconds)
	limiter := auth.NewRateLimiter(cfg.RateLimitEnabled)
	authSvc := auth.NewService(db, tokens, limiter,
		cfg.AdminUsername, cfg.AdminPassword, cfg.WXAppID, cfg.WXSecret, cfg.WXAllowedOpenIDs)
	if err := authSvc.Seed(); err != nil {
		return err
	}

	var staticHandler http.Handler
	if cfg.StaticDir != "" {
		if _, err := os.Stat(cfg.StaticDir + "/index.html"); err == nil {
			staticHandler = webstatic.New(cfg.StaticDir)
		} else {
			slog.Warn("STATIC_DIR 下无 index.html，静态服务关闭", "dir", cfg.StaticDir)
		}
	}

	srv := api.NewServer(cfg, authSvc, tokens, invSvc, expSvc, metricSvc, staticHandler)
	httpServer := &http.Server{
		Addr:              cfg.ServerAddr,
		Handler:           srv,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// 定时任务：指标明细保留最新 1000 条；限流桶清理（均每 10 分钟）
	stopTickers := make(chan struct{})
	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				metricSvc.CleanupOldLogs()
				limiter.Cleanup()
			case <-stopTickers:
				return
			}
		}
	}()

	errCh := make(chan error, 1)
	go func() {
		slog.Info("invoice-server (Go) 已启动", "addr", cfg.ServerAddr, "db", cfg.DBPath)
		errCh <- httpServer.ListenAndServe()
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	select {
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case sig := <-stop:
		slog.Info("收到退出信号，优雅停机", "signal", sig.String())
		close(stopTickers)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := httpServer.Shutdown(ctx); err != nil {
			slog.Warn("优雅停机超时，强制退出", "err", err)
		}
	}
	return nil
}
