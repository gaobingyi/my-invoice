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
  drawer          TEXT    NULL,
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
CREATE TABLE IF NOT EXISTS export_batch_item (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  batch_id        INTEGER NOT NULL,
  invoice_id      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_export_batch_item_batch ON export_batch_item(batch_id);
CREATE INDEX IF NOT EXISTS idx_invoice_date ON invoice(invoice_date);
