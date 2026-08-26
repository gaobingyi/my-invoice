package com.example.invoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fallback structured extractor. The regex path in {@link InvoiceParser} covers the known
 * invoice layouts; when a field comes back null we ask an OpenAI chat-completions compatible
 * LLM (base-url configurable) to fill just the missing pieces. Failing the call is not an
 * error — the nulls stay and the upload still proceeds.
 */
@Component
public class InvoiceLlmExtractor {

    private final RestClient client;
    private final ObjectMapper json;
    private final String model;
    private final boolean enabled;

    public InvoiceLlmExtractor(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.model:}") String model,
            @Value("${llm.enabled:true}") boolean enabled,
            @Value("${llm.timeout-seconds:60}") int timeoutSeconds,
            @Value("${llm.api-key:}") String apiKey,
            RestClient.Builder builder) {
        this.model = model;
        this.enabled = enabled;
        this.json = new ObjectMapper();
        // pocfile: bound the LLM call so a stalled local model cannot hang an upload.
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        builder = builder.requestFactory(factory).baseUrl(baseUrl);
        if (!apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.client = builder.build();
    }

    /**
     * Ask the LLM for the given missing fields only. Returns a ParsedInvoice with the
     * supplied fields preserved and missing ones filled where the model provided them.
     */
    ParsedInvoice fill(ParsedInvoice parsed, String text) {
        if (!enabled) return parsed;
        List<String> missing = missing(parsed);
        if (missing.isEmpty()) return parsed;
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());
        log.info("LLM fill missing: {}", missing);

        // pocfile: retry once — the local model occasionally returns empty content.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode reply = callLlm(text, missing);
                ParsedInvoice merged = merge(parsed, reply);
                log.info("LLM fill done (attempt {}): {}", attempt, reply);
                return merged;
            } catch (Exception e) {
                if (attempt == 2) {
                    // pocfile: LLM is best-effort; a hiccup must not break the upload.
                    log.warn("LLM fill failed: {}", e.getMessage());
                    return parsed;
                }
                log.info("LLM fill attempt {} failed, retrying: {}", attempt, e.getMessage());
            }
        }
        return parsed;
    }

    private JsonNode callLlm(String text, List<String> missing) throws Exception {
        String sys = """
            你是增值税发票信息抽取助手。从发票文本中提取字段，只输出 JSON，不要解释。
            字段说明：invoiceNumber 发票号码（20位数字）；invoiceDate 开票日期 YYYY-MM-DD；
            buyerName/buyerTaxId 购买方名称/税号；sellerName/sellerTaxId 销售方名称/税号；
            category 项目名称（*…* 开头）；totalAmount 金额合计（不含税，两位小数数字）；
            taxAmount 税额合计；totalWithTax 价税合计；drawer 开票人。
            金额字段输出纯数字如 149.00，不要带货币符号和千分位。无法确定的字段输出 null。
            """;
        // pocfile: PDFBox output can carry control characters that some JSON servers reject
        // even when escaped; strip non-printable ones before sending.
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        String prompt = "请提取以下字段：" + String.join(", ", missing)
                + "\n\n发票文本如下：\n" + clean;
        String body = json.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", sys),
                        Map.of("role", "user", "content", prompt)),
                "temperature", 0,
                "max_tokens", 500,
                "response_format", Map.of("type", "json_object")));
        String resp = client.post().uri("/chat/completions")
                .contentType(new org.springframework.http.MediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON, java.nio.charset.StandardCharsets.UTF_8))
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .retrieve()
                .body(String.class);
        // pocfile: some compatible servers append SSE framing ("data: [DONE]") after the JSON
        // body, so parse only up to the last closing brace.
        int end = resp.lastIndexOf('}');
        if (end < 0) throw new IllegalStateException("no JSON in LLM reply");
        JsonNode root = json.readTree(resp.substring(0, end + 1));
        String content = root.path("choices").path(0).path("message").path("content").asText();
        // strip possible markdown code fences
        content = content.replaceAll("(?s)^```\\w*\\s*|\\s*```$", "").trim();
        if (content.isEmpty() || content.equals("null")) {
            throw new IllegalStateException("LLM returned empty content");
        }
        JsonNode reply = json.readTree(content);
        if (!reply.isObject()) {
            throw new IllegalStateException("LLM reply is not JSON object: " + content);
        }
        return reply;
    }

    private List<String> missing(ParsedInvoice p) {
        List<String> m = new ArrayList<>();
        if (p.invoiceNumber() == null) m.add("invoiceNumber");
        if (p.invoiceDate() == null) m.add("invoiceDate");
        if (p.buyerName() == null) m.add("buyerName");
        if (p.buyerTaxId() == null) m.add("buyerTaxId");
        if (p.sellerName() == null) m.add("sellerName");
        if (p.sellerTaxId() == null) m.add("sellerTaxId");
        if (p.category() == null) m.add("category");
        if (p.totalAmount() == null) m.add("totalAmount");
        if (p.taxAmount() == null) m.add("taxAmount");
        if (p.totalWithTax() == null) m.add("totalWithTax");
        if (p.drawer() == null) m.add("drawer");
        return m;
    }

    private ParsedInvoice merge(ParsedInvoice p, JsonNode j) {
        return new ParsedInvoice(
                or(p.invoiceNumber(), j, "invoiceNumber"),
                p.invoiceDate() != null ? p.invoiceDate() : date(j),
                or(p.buyerName(), j, "buyerName"),
                or(p.buyerTaxId(), j, "buyerTaxId"),
                or(p.sellerName(), j, "sellerName"),
                or(p.sellerTaxId(), j, "sellerTaxId"),
                or(p.category(), j, "category"),
                p.totalAmount() != null ? p.totalAmount() : decimal(j, "totalAmount"),
                p.taxAmount() != null ? p.taxAmount() : decimal(j, "taxAmount"),
                p.totalWithTax() != null ? p.totalWithTax() : decimal(j, "totalWithTax"),
                or(p.drawer(), j, "drawer"));
    }

    private static String or(String existing, JsonNode j, String key) {
        if (existing != null) return existing;
        JsonNode n = j.get(key);
        return (n == null || n.isNull()) ? null : n.asText().trim();
    }

    private static LocalDate date(JsonNode j) {
        JsonNode n = j.get("invoiceDate");
        if (n == null || n.isNull()) return null;
        try {
            return LocalDate.parse(n.asText().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode j, String key) {
        JsonNode n = j.get(key);
        if (n == null || n.isNull()) return null;
        try {
            return new BigDecimal(n.asText().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
