package com.example.invoice.service;

import com.example.invoice.dto.ParsingMetricsResponse;
import com.example.invoice.dto.ParsingTrendEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

/**
 * 解析指标服务。持有两张表：
 * <ul>
 *   <li>{@code parsing_metrics} — 单行计数器，Dashboard 卡片数据来源</li>
 *   <li>{@code parsing_log} — 每次解析一行明细，趋势图数据来源</li>
 * </ul>
 * 用法：每次解析创建 {@link ParseContext}，各阶段写入 context，最后 {@link #flush} 一次性落库。
 */
@Service
public class ParsingMetricsService {

    private static final Logger log = LoggerFactory.getLogger(ParsingMetricsService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    /** ParsedInvoice 的 11 个字段名，用于遍历 null 检查 */
    private static final List<String> FIELD_NAMES = List.of(
            "invoiceNumber", "invoiceDate", "buyerName", "buyerTaxId",
            "sellerName", "sellerTaxId", "category",
            "totalAmount", "taxAmount", "totalWithTax", "drawer");

    public ParsingMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void ensureRow() {
        jdbc.execute("INSERT OR IGNORE INTO parsing_metrics (id) VALUES (1)");
    }

    // ────── ParseContext 填充方法 ──────

    /** 判断 ParsedInvoice 的 11 个字段是否全部非 null，并将结果写入 context。 */
    public void recordRegexResult(ParsedInvoice p, ParseContext ctx) {
        List<String> missing = new ArrayList<>();
        for (String field : FIELD_NAMES) {
            if (getField(p, field) == null) {
                missing.add(field);
            }
        }
        ctx.setRegexSuccess(missing.isEmpty());
        ctx.setRegexMissingFields(missing);
    }

    /** LLM 触发后，比较 before/after 判断是否填补成功。 */
    public void recordLlmFillResult(ParsedInvoice before, ParsedInvoice after, ParseContext ctx) {
        int beforeNulls = countNulls(before);
        int afterNulls = countNulls(after);
        ctx.setLlmFillSuccess(afterNulls == 0);
    }

    // ────── flush：一次性写入两表 ──────

    public void flush(ParseContext ctx) {
        // 1. 原子递增 parsing_metrics 各计数器
        jdbc.update("""
            UPDATE parsing_metrics SET
              uploads_total     = uploads_total + 1,
              uploads_pdf_error = uploads_pdf_error + ?,
              regex_success     = regex_success + ?,
              regex_failure     = regex_failure + ?,
              llm_triggered     = llm_triggered + ?,
              llm_fill_success  = llm_fill_success + ?,
              llm_fill_failure  = llm_fill_failure + ?,
              llm_api_success   = llm_api_success + ?,
              llm_api_failure   = llm_api_failure + ?,
              llm_api_total_ms  = llm_api_total_ms + ?,
              llm_api_count     = llm_api_count + ?,
              updated_at        = datetime('now','localtime')
            WHERE id = 1""",
                ctx.isPdfError() ? 1 : 0,
                ctx.isRegexSuccess() ? 1 : 0,
                ctx.isRegexSuccess() ? 0 : 1,
                ctx.isLlmTriggered() ? 1 : 0,
                ctx.isLlmTriggered() && ctx.isLlmFillSuccess() ? 1 : 0,
                ctx.isLlmTriggered() && !ctx.isLlmFillSuccess() ? 1 : 0,
                ctx.getLlmApiSuccessCount(),
                ctx.getLlmApiFailureCount(),
                ctx.getLlmApiTotalMs(),
                ctx.getLlmApiSuccessCount() + ctx.getLlmApiFailureCount());

        // 2. 更新 regex_misses_json（read-modify-write，单用户安全）
        updateRegexMissesJson(ctx.getRegexMissingFields());

        // 3. INSERT parsing_log 明细行
        String missingJson = "[]";
        try {
            missingJson = json.writeValueAsString(ctx.getRegexMissingFields());
        } catch (IOException ignored) {
        }
        jdbc.update("""
            INSERT INTO parsing_log
              (pdf_error, regex_success, regex_missing_json,
               llm_triggered, llm_fill_success,
               llm_api_success, llm_api_failure, llm_api_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                ctx.isPdfError() ? 1 : 0,
                ctx.isRegexSuccess() ? 1 : 0,
                missingJson,
                ctx.isLlmTriggered() ? 1 : 0,
                ctx.isLlmTriggered() && ctx.isLlmFillSuccess() ? 1 : 0,
                ctx.getLlmApiSuccessCount(),
                ctx.getLlmApiFailureCount(),
                ctx.getLlmApiTotalMs());
    }

    // ────── 查询方法 ──────

    public ParsingMetricsResponse getSnapshot() {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM parsing_metrics WHERE id = 1");

        int uploadsTotal = intVal(row, "uploads_total");
        int uploadsPdfError = intVal(row, "uploads_pdf_error");
        int regexSuccess = intVal(row, "regex_success");
        int regexFailure = intVal(row, "regex_failure");
        int llmTriggered = intVal(row, "llm_triggered");
        int llmFillSuccess = intVal(row, "llm_fill_success");
        int llmFillFailure = intVal(row, "llm_fill_failure");
        int llmApiSuccess = intVal(row, "llm_api_success");
        int llmApiFailure = intVal(row, "llm_api_failure");
        int llmApiTotalMs = intVal(row, "llm_api_total_ms");
        int llmApiCount = intVal(row, "llm_api_count");
        String missesJson = String.valueOf(row.getOrDefault("regex_misses_json", "{}"));
        String updatedAt = String.valueOf(row.getOrDefault("updated_at", ""));

        Map<String, Integer> fieldMisses;
        try {
            fieldMisses = json.readValue(missesJson, new TypeReference<>() {});
        } catch (IOException e) {
            fieldMisses = new LinkedHashMap<>();
        }

        return ParsingMetricsResponse.fromRaw(
                uploadsTotal, uploadsPdfError,
                regexSuccess, regexFailure,
                llmTriggered, llmFillSuccess, llmFillFailure,
                llmApiSuccess, llmApiFailure, llmApiTotalMs, llmApiCount,
                fieldMisses, updatedAt);
    }

    public List<ParsingTrendEntry> getTrend(int days) {
        return jdbc.query("""
            SELECT
              date(created_at) AS d,
              COUNT(*)                          AS uploads,
              SUM(pdf_error)                    AS pdf_error,
              SUM(regex_success)                AS regex_success,
              COUNT(*) - SUM(regex_success)     AS regex_failure,
              SUM(llm_triggered)                AS llm_triggered,
              SUM(llm_fill_success)             AS llm_fill_success,
              SUM(llm_api_success)              AS llm_api_success,
              SUM(llm_api_failure)              AS llm_api_failure,
              SUM(llm_api_ms)                   AS total_llm_ms,
              SUM(CASE WHEN llm_api_ms > 0 THEN 1 ELSE 0 END) AS llm_call_count
            FROM parsing_log
            WHERE created_at >= date('now', 'localtime', ? || ' days')
            GROUP BY date(created_at)
            ORDER BY d""",
                ps -> ps.setInt(1, -days),
                (rs, i) -> new ParsingTrendEntry(
                        rs.getString("d"),
                        rs.getInt("uploads"),
                        rs.getInt("pdf_error"),
                        rs.getInt("regex_success"),
                        rs.getInt("regex_failure"),
                        rs.getInt("llm_triggered"),
                        rs.getInt("llm_fill_success"),
                        rs.getInt("llm_api_success"),
                        rs.getInt("llm_api_failure"),
                        llmAvgMs(rs.getInt("total_llm_ms"), rs.getInt("llm_call_count"))));
    }

    // ────── 定时清理 ──────

    @Scheduled(fixedRate = 600_000) // 每 10 分钟
    void cleanupOldLogs() {
        int deleted = jdbc.update("""
            DELETE FROM parsing_log WHERE id NOT IN (
              SELECT id FROM parsing_log ORDER BY id DESC LIMIT 1000
            )""");
        if (deleted > 0) {
            log.info("ParsingMetrics: 清理 {} 条旧 parsing_log 记录", deleted);
        }
    }

    // ────── 内部工具 ──────

    private void updateRegexMissesJson(List<String> missingFields) {
        if (missingFields.isEmpty()) return;
        String raw = jdbc.queryForObject(
                "SELECT regex_misses_json FROM parsing_metrics WHERE id = 1", String.class);
        Map<String, Integer> misses;
        try {
            misses = (raw == null || raw.isBlank())
                    ? new LinkedHashMap<>()
                    : json.readValue(raw, new TypeReference<>() {});
        } catch (IOException e) {
            misses = new LinkedHashMap<>();
        }
        for (String field : missingFields) {
            misses.merge(field, 1, Integer::sum);
        }
        try {
            jdbc.update("UPDATE parsing_metrics SET regex_misses_json = ? WHERE id = 1",
                    json.writeValueAsString(misses));
        } catch (IOException ignored) {
        }
    }

    private static int countNulls(ParsedInvoice p) {
        int count = 0;
        if (p.invoiceNumber() == null) count++;
        if (p.invoiceDate() == null) count++;
        if (p.buyerName() == null) count++;
        if (p.buyerTaxId() == null) count++;
        if (p.sellerName() == null) count++;
        if (p.sellerTaxId() == null) count++;
        if (p.category() == null) count++;
        if (p.totalAmount() == null) count++;
        if (p.taxAmount() == null) count++;
        if (p.totalWithTax() == null) count++;
        if (p.drawer() == null) count++;
        return count;
    }

    private static Object getField(ParsedInvoice p, String name) {
        return switch (name) {
            case "invoiceNumber" -> p.invoiceNumber();
            case "invoiceDate" -> p.invoiceDate();
            case "buyerName" -> p.buyerName();
            case "buyerTaxId" -> p.buyerTaxId();
            case "sellerName" -> p.sellerName();
            case "sellerTaxId" -> p.sellerTaxId();
            case "category" -> p.category();
            case "totalAmount" -> p.totalAmount();
            case "taxAmount" -> p.taxAmount();
            case "totalWithTax" -> p.totalWithTax();
            case "drawer" -> p.drawer();
            default -> null;
        };
    }

    private static int intVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static long llmAvgMs(int totalMs, int count) {
        return count > 0 ? totalMs / count : 0;
    }
}
