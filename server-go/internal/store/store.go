// Package store 管理 SQLite 连接、schema 初始化与列迁移。
// 单连接（MaxOpenConns=1）是正确性前提：SQLite 单写者，多连接下「事务内先读后写」
// 并发交错会立即 BUSY；单连接让事务完全串行（对齐 Java 版 Hikari maximum-pool-size=1）。
package store

import (
	"database/sql"
	_ "embed"
	"fmt"
	"log/slog"
	"net/url"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite" // 纯 Go 驱动，静态编译
)

//go:embed schema.sql
var schemaSQL string

// Open 打开 DB 并完成 schema 初始化 + 列迁移。路径带 query 参数时原样保留。
func Open(path string) (*sql.DB, error) {
	dsn := buildDSN(path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	// 单写者串行：见包注释。SQLite 写连接多开只有 BUSY 风险没有收益。
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	if err := initSchema(db); err != nil {
		db.Close()
		return nil, err
	}
	if err := migrate(db); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

// buildDSN 组 modernc/sqlite DSN：WAL + busy_timeout 5s + 外键（对齐 Java 版 JDBC URL 参数）。
// 相对路径先转绝对：modernc 的 file: URI 解析相对路径报 "invalid uri authority"
// （Xerial 无此约束，dev 默认 ./data/invoice.db 会踩）；转绝对不改变相对语义。
func buildDSN(path string) string {
	if abs, err := filepath.Abs(path); err == nil {
		path = abs
	}
	u := url.URL{Scheme: "file", Path: path}
	q := url.Values{}
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "foreign_keys(1)")
	u.RawQuery = q.Encode()
	return u.String()
}

// initSchema 执行嵌入的 schema.sql（幂等）。按引号感知的分号切分逐句执行，
// 避免「多语句 Exec」在不同驱动版本下的行为差异。
func initSchema(db *sql.DB) error {
	for _, stmt := range splitStatements(schemaSQL) {
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("schema: %w (stmt: %.80s)", err, stmt)
		}
	}
	return nil
}

func splitStatements(s string) []string {
	var out []string
	var sb strings.Builder
	inQuote := false
	flush := func() {
		// 剥掉注释行后仍剩余 SQL 才算语句——不能整块丢弃（块 = 注释行 + 语句）
		var kept []string
		for _, line := range strings.Split(sb.String(), "\n") {
			if !strings.HasPrefix(strings.TrimSpace(line), "--") {
				kept = append(kept, line)
			}
		}
		if t := strings.TrimSpace(strings.Join(kept, "\n")); t != "" {
			out = append(out, t)
		}
	}
	for _, r := range s {
		switch {
		case r == '\'':
			inQuote = !inQuote
			sb.WriteRune(r)
		case r == ';' && !inQuote:
			flush()
			sb.Reset()
		default:
			sb.WriteRune(r)
		}
	}
	flush()
	return out
}

// migrate 给已存在的老表补列：CREATE TABLE IF NOT EXISTS 不会改老表，
// SQLite 也没有 ADD COLUMN IF NOT EXISTS，只能按 PRAGMA table_info 判断。
// 对齐 Java 版 config/SchemaMigration。全新库上三步都是空操作。
func migrate(db *sql.DB) error {
	cols, err := tableColumns(db, "invoice")
	if err != nil {
		return err
	}
	if len(cols) == 0 {
		return fmt.Errorf("invoice 表不存在——schema 初始化失败，拒绝启动")
	}
	if _, ok := cols["used"]; !ok {
		if _, err := db.Exec(`ALTER TABLE invoice ADD COLUMN used INTEGER NOT NULL DEFAULT 0`); err != nil {
			return fmt.Errorf("migrate used: %w", err)
		}
		slog.Info("schema 迁移：invoice.used 列已补齐")
	}
	if _, ok := cols["used_at"]; !ok {
		if _, err := db.Exec(`ALTER TABLE invoice ADD COLUMN used_at TEXT`); err != nil {
			return fmt.Errorf("migrate used_at: %w", err)
		}
		slog.Info("schema 迁移：invoice.used_at 列已补齐")
	}
	return nil
}

func tableColumns(db *sql.DB, table string) (map[string]bool, error) {
	rows, err := db.Query("PRAGMA table_info(" + table + ")")
	if err != nil {
		return nil, fmt.Errorf("pragma table_info(%s): %w", table, err)
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var cid int
		var name, ctype string
		var notNull, pk int
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &ctype, &notNull, &dflt, &pk); err != nil {
			return nil, err
		}
		out[name] = true
	}
	return out, rows.Err()
}

// IsUniqueViolation 判定错误是否为唯一键冲突（对齐 Java 版 isDuplicateKey 的语义：
// Xerial 用 vendor code 19 + message 含 "unique"；modernc 错误串固定含
// "UNIQUE constraint failed"，同样不能用 SQLState 判断——NOT NULL/CHECK 共享同一 SQLState）。
func IsUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	for e := err; e != nil; e = unwrap(e) {
		if strings.Contains(strings.ToLower(e.Error()), "unique constraint failed") {
			return true
		}
	}
	return false
}

func unwrap(err error) error {
	type unwrapper interface{ Unwrap() error }
	if u, ok := err.(unwrapper); ok {
		return u.Unwrap()
	}
	return nil
}
