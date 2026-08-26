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
    }
}
