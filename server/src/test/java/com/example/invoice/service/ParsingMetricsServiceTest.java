package com.example.invoice.service;

import com.example.invoice.dto.ParsingMetricsResponse;
import com.example.invoice.dto.ParsingTrendEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParsingMetricsServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private ParsingMetricsService service;

    @BeforeEach
    void setup() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"));
        var ds = new SingleConnectionDataSource(conn, false);
        jdbc = new JdbcTemplate(ds);
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
              regex_misses_json     TEXT    NOT NULL DEFAULT '{}',
              updated_at            TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
            )""");
        jdbc.execute("""
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
            )""");
        service = new ParsingMetricsService(jdbc);
        service.ensureRow();
    }

    private static ParsedInvoice fullParsed() {
        return new ParsedInvoice(
                "26322000004144614676",
                LocalDate.of(2026, 5, 26),
                "测试买家公司", "91110108MA01B1234X",
                "测试卖家公司", "91110108MA01B5678Y",
                "*餐饮服务*餐饮服务",
                new BigDecimal("189.62"),
                new BigDecimal("11.38"),
                new BigDecimal("201.00"));
    }

    private static ParsedInvoice partialParsed() {
        return new ParsedInvoice(
                null, null,
                "测试买家公司", null,
                "测试卖家公司", null,
                null,
                new BigDecimal("189.62"),
                new BigDecimal("11.38"),
                new BigDecimal("201.00"));
    }

    @Test
    void ensureRowIdempotent() {
        service.ensureRow();
        service.ensureRow();
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM parsing_metrics", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void flushRegexSuccess() {
        ParseContext ctx = new ParseContext();
        service.recordRegexResult(fullParsed(), ctx);
        assertTrue(ctx.isRegexSuccess());
        assertEquals(0, ctx.getRegexMissingFields().size());

        service.flush(ctx);

        var snap = service.getSnapshot();
        assertEquals(1, snap.uploadsTotal());
        assertEquals(1, snap.regex().success());
        assertEquals(0, snap.regex().failure());
        assertEquals(1.0, snap.regex().successRate());
        assertEquals(0, snap.llm().triggered());
    }

    @Test
    void flushRegexFailureWithMissingFields() {
        ParseContext ctx = new ParseContext();
        service.recordRegexResult(partialParsed(), ctx);
        assertFalse(ctx.isRegexSuccess());
        assertTrue(ctx.getRegexMissingFields().contains("invoiceNumber"));
        assertTrue(ctx.getRegexMissingFields().contains("buyerTaxId"));

        service.flush(ctx);

        var snap = service.getSnapshot();
        assertEquals(1, snap.uploadsTotal());
        assertEquals(0, snap.regex().success());
        assertEquals(1, snap.regex().failure());
        assertEquals(0.0, snap.regex().successRate());
        assertTrue(snap.regexFieldMisses().containsKey("invoiceNumber"));
        assertTrue(snap.regexFieldMisses().containsKey("buyerTaxId"));
    }

    @Test
    void flushLlmFillSuccess() {
        ParseContext ctx = new ParseContext();
        service.recordRegexResult(partialParsed(), ctx);
        ctx.setLlmTriggered(true);
        // 生产路径由 InvoiceLlmExtractor 在合并后按 missingFields 为空置位；这里直接置位测 flush 记账。
        ctx.setLlmFillSuccess(true);
        ctx.recordLlmApiCall(true, 1500);
        service.flush(ctx);

        var snap = service.getSnapshot();
        assertEquals(1, snap.llm().triggered());
        assertEquals(1, snap.llm().fillSuccess());
        assertEquals(0, snap.llm().fillFailure());
        assertEquals(1.0, snap.llm().fillSuccessRate());
        assertEquals(1, snap.llm().apiSuccess());
        assertEquals(1500, snap.llm().avgResponseTimeMs());
    }

    @Test
    void flushLlmFillFailure() {
        ParseContext ctx = new ParseContext();
        service.recordRegexResult(partialParsed(), ctx);
        ctx.setLlmTriggered(true);
        // LLM 未能补齐（仍 partial）→ fillSuccess 保持 false
        ctx.setLlmFillSuccess(false);
        ctx.recordLlmApiCall(true, 2000);
        service.flush(ctx);

        var snap = service.getSnapshot();
        assertEquals(1, snap.llm().triggered());
        assertEquals(0, snap.llm().fillSuccess());
        assertEquals(1, snap.llm().fillFailure());
        assertEquals(0.0, snap.llm().fillSuccessRate());
    }

    @Test
    void multipleFlushesAccumulate() {
        for (int i = 0; i < 5; i++) {
            ParseContext ctx = new ParseContext();
            service.recordRegexResult(fullParsed(), ctx);
            service.flush(ctx);
        }
        var snap = service.getSnapshot();
        assertEquals(5, snap.uploadsTotal());
        assertEquals(5, snap.regex().success());
        assertEquals(1.0, snap.regex().successRate());
    }

    @Test
    void trendReturnsDailyAggregation() {
        // Flush a few records
        for (int i = 0; i < 3; i++) {
            ParseContext ctx = new ParseContext();
            service.recordRegexResult(fullParsed(), ctx);
            service.flush(ctx);
        }
        List<ParsingTrendEntry> trend = service.getTrend(30);
        assertFalse(trend.isEmpty());
        assertEquals(3, trend.get(0).uploads());
        assertEquals(3, trend.get(0).regexSuccess());
    }
}
