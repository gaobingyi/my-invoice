-- Mirror of ../ddl/schema.sql — keep in sync.
-- 这是 Spring Boot spring.sql.init 在启动时实际加载的副本（classpath:schema.sql）。
-- 源文件 server/ddl/schema.sql 给运维/代码 review 看；改 schema 必须同步两份。

-- 发票管理系统 DDL (SQLite 3.40+)
-- Hibernate ddl-auto: validate 按实体校验。IDENTITY 列必须 INTEGER PRIMARY KEY AUTOINCREMENT。
-- SQLite 无 DECIMAL 类型，三个金额列用 TEXT 存 BigDecimal.toPlainString()（由
-- BigDecimalStringConverter 双向转换），避免 REAL 浮点漂移。
-- 首次启动由 spring.sql.init.mode: always 触发本文件执行；CREATE TABLE IF NOT EXISTS 幂等。

CREATE TABLE IF NOT EXISTS invoice (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  invoice_number  TEXT    NOT NULL UNIQUE,
  invoice_date    TEXT    NULL,                -- ISO-8601 yyyy-MM-dd
  buyer_name      TEXT    NULL,
  buyer_tax_id    TEXT    NULL,
  seller_name     TEXT    NULL,
  seller_tax_id   TEXT    NULL,
  total_amount    TEXT    NULL,                -- BigDecimal 字符串
  tax_amount      TEXT    NULL,
  total_with_tax  TEXT    NULL,
  category        TEXT    NULL,
  file_path       TEXT    NOT NULL,
  used            INTEGER NOT NULL DEFAULT 0,   -- 已使用标记（导出批次创建时置 1，批次删除且无其他批次引用时清 0）
  used_at         TEXT    NULL,
  created_at      TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS app_user (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT    NOT NULL UNIQUE,
  password_hash TEXT    NOT NULL,
  created_at    TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 导出批次：一次「选票打包」的快照记录（count/total 为创建时点值，不随后续发票删除回填）
CREATE TABLE IF NOT EXISTS export_batch (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  batch_month     TEXT    NOT NULL,           -- 批次用途月份 yyyy-MM
  invoice_count   INTEGER NOT NULL,
  total_with_tax  TEXT    NULL,               -- BigDecimal 字符串（同 invoice 表金额列约定）
  created_at      TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 批次-发票关联。无外键（与全库风格一致）；发票被删时由 InvoiceService.delete 联动清理。
-- (batch_id, invoice_id) 唯一：并发「向同批次重复添加同一张票」在 DB 层兜底
-- （上方的 DELETE 先清理历史竞态可能留下的重复行，保证建唯一索引不失败）。
CREATE TABLE IF NOT EXISTS export_batch_item (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  batch_id        INTEGER NOT NULL,
  invoice_id      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_export_batch_item_batch ON export_batch_item(batch_id);
-- 删发票时的引用守卫（existsByInvoiceId 等）按 invoice_id 查询，需要索引
CREATE INDEX IF NOT EXISTS idx_export_batch_item_invoice ON export_batch_item(invoice_id);
-- 列表固定 WHERE used=? ORDER BY created_at DESC，无索引时全表扫 + 排序
CREATE INDEX IF NOT EXISTS idx_invoice_used_created ON invoice(used, created_at DESC);
-- idx_invoice_date 无任何查询使用（死索引，只产生写放大），显式移除老库上的同名索引
DROP INDEX IF EXISTS idx_invoice_date;

-- batch_month 唯一（文档口径「yyyy-MM 全局唯一」的 DB 层保证；先清历史竞态重复行，
-- 保留每组最早创建的一条）。老库/新库统一走唯一索引而非 CREATE TABLE 内联 UNIQUE，
-- 因为 IF NOT EXISTS 的建表语句不会给已存在的老表补约束。
-- 删重复批次会使其 item 变孤儿（batch_id 指向已删行，deleteBatch 够不着），孤儿 item
-- 会让 existsByInvoiceId 守卫永久拦截这些发票的删除 —— 必须一并清掉；清完后
-- 「只被已删重复批次引用、现已无任何批次引用」的发票恢复未使用（同 deleteBatch 的
-- 恢复语义），否则 used=1 永久卡死、既不能再打包也无法从批次页解除。
DELETE FROM export_batch WHERE id NOT IN (
  SELECT MIN(id) FROM export_batch GROUP BY batch_month
);
DELETE FROM export_batch_item WHERE batch_id NOT IN (SELECT id FROM export_batch);
UPDATE invoice SET used = 0, used_at = NULL
WHERE used = 1 AND id NOT IN (SELECT invoice_id FROM export_batch_item);
CREATE UNIQUE INDEX IF NOT EXISTS ux_export_batch_month ON export_batch(batch_month);
DELETE FROM export_batch_item WHERE id NOT IN (
  SELECT MIN(id) FROM export_batch_item GROUP BY batch_id, invoice_id
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_export_batch_item_batch_invoice
  ON export_batch_item(batch_id, invoice_id);

-- 解析指标汇总：单行计数器，id 固定为 1
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
  regex_misses_json     TEXT    NOT NULL DEFAULT '{}',
  updated_at            TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 解析明细日志：每次上传一行，趋势图数据来源
CREATE TABLE IF NOT EXISTS parsing_log (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at          TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  pdf_error           INTEGER NOT NULL DEFAULT 0,
  regex_success       INTEGER NOT NULL DEFAULT 0,
  regex_missing_json  TEXT    NOT NULL DEFAULT '{}',
  llm_triggered       INTEGER NOT NULL DEFAULT 0,
  llm_fill_success    INTEGER NOT NULL DEFAULT 0,
  llm_api_success     INTEGER NOT NULL DEFAULT 0,
  llm_api_failure     INTEGER NOT NULL DEFAULT 0,
  llm_api_ms          INTEGER NOT NULL DEFAULT 0
);
