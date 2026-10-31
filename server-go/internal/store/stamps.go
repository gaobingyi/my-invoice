// 时间戳存储格式与 Java/Xerial date_class=TEXT 对齐——生产 VPS 上 Java/Go 两套后端
// 共享同一 SQLite 文件按 systemd Conflicts 互斥切换，写入格式必须逐字节一致，
// 否则混写行 ORDER BY created_at 错序、Java 读回有解析风险。
// Xerial 实际形态（从生产库核实）：LocalDateTime → 'yyyy-MM-dd HH:mm:ss.SSS'（恒 3 位毫秒）、
// LocalDate → 'yyyy-MM-dd 00:00:00.000'（补全为午夜时间戳）。
// 读出侧归一化回 API 形态（对齐 Java 经 Jackson 的序列化）：日期 'yyyy-MM-dd'、
// 时刻 'yyyy-MM-ddTHH:mm:ss'；同时兼容本仓库早期 Go 版写入的 'T' 整秒格式。
package store

import (
	"strings"
	"time"
)

const stampLayout = "2006-01-02 15:04:05.000" // Xerial LocalDateTime TEXT 形态

// NowStamp 当前时刻的存储形态。
func NowStamp() string { return time.Now().Format(stampLayout) }

// DateArg API 日期（yyyy-MM-dd）→ SQL 绑定值：nil/空 → NULL，非空 → Xerial LocalDate 存储形态。
func DateArg(p *string) any {
	if p == nil || *p == "" {
		return nil
	}
	return *p + " 00:00:00.000"
}

// NormalizeDate 存储文本 → API 形态：截掉 Xerial 补全的时间部分，只留 yyyy-MM-dd。
func NormalizeDate(s string) string {
	if i := strings.IndexByte(s, ' '); i >= 0 {
		return s[:i]
	}
	return s
}

// NormalizeStamp 存储文本 → API 形态（yyyy-MM-ddTHH:mm:ss）：兼容 Xerial 的
// 「空格+毫秒」与早期 Go 版的「T+整秒」两种存储形态，输出统一为后者。
func NormalizeStamp(s string) string {
	if i := strings.IndexByte(s, ' '); i >= 0 {
		s = s[:i] + "T" + s[i+1:]
	}
	if i := strings.IndexByte(s, '.'); i >= 0 {
		s = s[:i]
	}
	return s
}
