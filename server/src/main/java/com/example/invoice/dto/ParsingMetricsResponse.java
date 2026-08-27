package com.example.invoice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/metrics/parsing 的响应体。
 * 成功率在工厂方法中计算，denominator 为 0 时返回 null。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsingMetricsResponse(
        int uploadsTotal,
        int uploadsPdfError,
        RegexMetrics regex,
        LlmMetrics llm,
        Map<String, Integer> regexFieldMisses,
        String updatedAt) {

    public record RegexMetrics(int success, int failure, Double successRate) {}

    public record LlmMetrics(
            int triggered,
            int fillSuccess,
            int fillFailure,
            Double fillSuccessRate,
            int apiSuccess,
            int apiFailure,
            Double apiSuccessRate,
            Long avgResponseTimeMs) {}

    public static ParsingMetricsResponse fromRaw(
            int uploadsTotal, int uploadsPdfError,
            int regexSuccess, int regexFailure,
            int llmTriggered, int llmFillSuccess, int llmFillFailure,
            int llmApiSuccess, int llmApiFailure, int llmApiTotalMs, int llmApiCount,
            Map<String, Integer> fieldMisses, String updatedAt) {

        Double regexRate = (regexSuccess + regexFailure) > 0
                ? (double) regexSuccess / (regexSuccess + regexFailure) : null;
        Double fillRate = llmTriggered > 0
                ? (double) llmFillSuccess / llmTriggered : null;
        Double apiRate = (llmApiSuccess + llmApiFailure) > 0
                ? (double) llmApiSuccess / (llmApiSuccess + llmApiFailure) : null;
        Long avgMs = llmApiCount > 0 ? (long) llmApiTotalMs / llmApiCount : null;

        // 按缺失次数降序排列
        Map<String, Integer> sorted = new LinkedHashMap<>();
        fieldMisses.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));

        return new ParsingMetricsResponse(
                uploadsTotal, uploadsPdfError,
                new RegexMetrics(regexSuccess, regexFailure, regexRate),
                new LlmMetrics(llmTriggered, llmFillSuccess, llmFillFailure, fillRate,
                        llmApiSuccess, llmApiFailure, apiRate, avgMs),
                sorted, updatedAt);
    }
}
