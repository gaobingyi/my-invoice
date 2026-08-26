package com.example.invoice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** 按 IP 限流：每分钟 10 次 + 每小时 100 次登录尝试，超出抛 BadCredentialsException → 401。
 * 内存计数（重启清零、集群不共享），适合单机部署。改集群时换 Redis + 滑动窗口即可。 */
@Component
public class LoginRateLimiter {

    private static final int MINUTE_LIMIT = 10;
    private static final int HOUR_LIMIT = 100;

    private final boolean enabled;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public LoginRateLimiter(@Value("${auth.rate-limit-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public void check(String ip) {
        if (!enabled || ip == null || ip.isBlank()) return;
        Instant now = Instant.now();
        Instant minuteAgo = now.minus(Duration.ofMinutes(1));
        Instant hourAgo = now.minus(Duration.ofHours(1));
        Deque<Instant> log = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (log) {
            // drop expired
            while (!log.isEmpty() && log.peekFirst().isBefore(hourAgo)) log.pollFirst();
            long minuteCount = log.stream().filter(t -> t.isAfter(minuteAgo)).count();
            if (minuteCount >= MINUTE_LIMIT || log.size() >= HOUR_LIMIT) {
                throw new BadCredentialsException("尝试次数过多，请稍后再试");
            }
            log.addLast(now);
        }
    }

    /** 定期清理无活动桶，避免内存随唯一 IP 数线性增长。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10 * 60 * 1000L)
    void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                while (!e.getValue().isEmpty() && e.getValue().peekFirst().isBefore(cutoff)) {
                    e.getValue().pollFirst();
                }
                return e.getValue().isEmpty();
            }
        });
    }
}
