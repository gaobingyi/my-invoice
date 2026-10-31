package auth

import (
	"errors"
	"sync"
	"time"
)

// RateLimiter 按 IP 限流：每分钟 10 次 + 每小时 100 次登录尝试，超出拒绝（401）。
// 内存计数（重启清零、单机）；check 在凭据校验前调用、成功也计数。
type RateLimiter struct {
	enabled bool
	mu      sync.Mutex
	buckets map[string][]time.Time
}

const (
	minuteLimit = 10
	hourLimit   = 100
)

var ErrTooManyAttempts = errors.New("尝试次数过多，请稍后再试")

func NewRateLimiter(enabled bool) *RateLimiter {
	return &RateLimiter{enabled: enabled, buckets: make(map[string][]time.Time)}
}

// Check 限流判定并记录本次尝试。
func (r *RateLimiter) Check(ip string) error {
	if !r.enabled || ip == "" {
		return nil
	}
	now := time.Now()
	minuteAgo := now.Add(-time.Minute)
	hourAgo := now.Add(-time.Hour)

	r.mu.Lock()
	defer r.mu.Unlock()
	log := r.buckets[ip]
	// drop expired
	i := 0
	for i < len(log) && log[i].Before(hourAgo) {
		i++
	}
	log = log[i:]
	minuteCount := 0
	for _, t := range log {
		if t.After(minuteAgo) {
			minuteCount++
		}
	}
	if minuteCount >= minuteLimit || len(log) >= hourLimit {
		r.buckets[ip] = log
		return ErrTooManyAttempts
	}
	r.buckets[ip] = append(log, now)
	return nil
}

// Cleanup 定期清理无活动桶，避免内存随唯一 IP 数线性增长（每 10 分钟由 ticker 调用）。
func (r *RateLimiter) Cleanup() {
	cutoff := time.Now().Add(-time.Hour)
	r.mu.Lock()
	defer r.mu.Unlock()
	for ip, log := range r.buckets {
		i := 0
		for i < len(log) && log[i].Before(cutoff) {
			i++
		}
		if i == len(log) {
			delete(r.buckets, ip)
		} else {
			r.buckets[ip] = log[i:]
		}
	}
}
