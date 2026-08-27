package com.example.invoice.dto;

/**
 * GET /api/metrics/parsing/trend 的单日聚合条目。
 */
public record ParsingTrendEntry(
        String date,
        int uploads,
        int pdfError,
        int regexSuccess,
        int regexFailure,
        int llmTriggered,
        int llmFillSuccess,
        int llmApiSuccess,
        int llmApiFailure,
        long avgLlmMs) {}
