package com.example.invoice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** invoice 表存量库迁移：SQLite 不支持 ADD COLUMN IF NOT EXISTS，而 schema.sql 的
 * CREATE TABLE IF NOT EXISTS 不会给老表加列。这里在启动时按 PRAGMA table_info
 * 补齐缺失列 —— 老库（dev ./data/invoice.db、docker 卷 backend-db）走这条路径升级，
 * 新库上 CREATE 已含新列，此处全部跳过。
 * 不用 spring.sql.init.continue-on-error：那会把整个 schema.sql 的所有错误静默吞掉，
 * 牺牲现有 fail-fast 行为。
 * 时序：spring.sql.init 在 context refresh 期间执行，ApplicationRunner 在 refresh
 * 完成后、HTTP 服务前运行，顺序安全。 */
@Component
public class SchemaMigration implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> columns = new HashSet<>();
        for (var row : jdbc.queryForList("PRAGMA table_info(invoice)")) {
            columns.add(String.valueOf(row.get("name")).toLowerCase());
        }
        if (columns.isEmpty()) {
            // invoice 表不存在说明 schema.sql 本身失败 —— 让异常炸出来，别静默带病启动
            throw new IllegalStateException("invoice 表不存在，schema 初始化失败");
        }
        if (!columns.contains("used")) {
            log.info("SchemaMigration: invoice 表缺 used 列，执行 ALTER TABLE 迁移");
            jdbc.execute("ALTER TABLE invoice ADD COLUMN used INTEGER NOT NULL DEFAULT 0");
        }
        if (!columns.contains("used_at")) {
            log.info("SchemaMigration: invoice 表缺 used_at 列，执行 ALTER TABLE 迁移");
            jdbc.execute("ALTER TABLE invoice ADD COLUMN used_at TEXT");
        }

        // parsing_metrics / parsing_log 表：老库可能不存在
        var tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('parsing_metrics','parsing_log')");
        var tableNames = new HashSet<String>();
        for (var row : tables) {
            tableNames.add(String.valueOf(row.get("name")));
        }
        if (!tableNames.contains("parsing_metrics")) {
            log.info("SchemaMigration: 新增 parsing_metrics 表");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS parsing_metrics (
                  id                    INTEGER PRIMARY KEY CHECK (id = 1),
                  uploads_total         INTEGER NOT NULL DEFAULT 0,
                  uploads_pdf_error     INTEGER NOT NULL DEFAULT 0,
                  regex_success         INTEGER NOT NULL DEFAULT 0,
                  regex_failure         INTEGER NOT NULL DEFAULT 0,
                  llm_triggered         INTEGER NOT NULL DEFAULT 0,
                  llm_fill_success      INTEGER NOT NULL DEFAULT 0,
                  llm_fill_failure      INTEGER NOT NULL DEFAULT 0,
                  llm_api_success       INTEGER NOT NULL DEFAULT 0,
                  llm_api_failure       INTEGER NOT NULL DEFAULT 0,
                  llm_api_total_ms      INTEGER NOT NULL DEFAULT 0,
                  llm_api_count         INTEGER NOT NULL DEFAULT 0,
                  regex_misses_json     TEXT NOT NULL DEFAULT '{}',
                  updated_at            TEXT NOT NULL DEFAULT (datetime('now','localtime'))
                )""");
            jdbc.execute("INSERT OR IGNORE INTO parsing_metrics (id) VALUES (1)");
        }
        if (!tableNames.contains("parsing_log")) {
            log.info("SchemaMigration: 新增 parsing_log 表");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS parsing_log (
                  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                  created_at          TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                  pdf_error           INTEGER NOT NULL DEFAULT 0,
                  regex_success       INTEGER NOT NULL DEFAULT 0,
                  regex_missing_json  TEXT NOT NULL DEFAULT '{}',
                  llm_triggered       INTEGER NOT NULL DEFAULT 0,
                  llm_fill_success    INTEGER NOT NULL DEFAULT 0,
                  llm_api_success     INTEGER NOT NULL DEFAULT 0,
                  llm_api_failure     INTEGER NOT NULL DEFAULT 0,
                  llm_api_ms          INTEGER NOT NULL DEFAULT 0
                )""");
        }
    }
}
