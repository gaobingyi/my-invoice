package com.example.invoice.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次解析的各阶段结果收集器。由 {@link InvoiceParser#parse} 创建，
 * 传递给 {@link InvoiceLlmExtractor#fill} 填充 LLM 阶段数据，
 * 最后由 {@link ParsingMetricsService#flush} 消费写入两表。
 */
public class ParseContext {

    private boolean pdfError;
    private boolean regexSuccess;
    private final List<String> regexMissingFields = new ArrayList<>();
    private boolean llmTriggered;
    private boolean llmFillSuccess;
    private int llmApiSuccessCount;
    private int llmApiFailureCount;
    private long llmApiTotalMs;

    public boolean isPdfError() { return pdfError; }
    public void setPdfError(boolean pdfError) { this.pdfError = pdfError; }

    public boolean isRegexSuccess() { return regexSuccess; }
    public void setRegexSuccess(boolean regexSuccess) { this.regexSuccess = regexSuccess; }

    public List<String> getRegexMissingFields() { return regexMissingFields; }
    public void setRegexMissingFields(List<String> fields) {
        regexMissingFields.clear();
        regexMissingFields.addAll(fields);
    }

    public boolean isLlmTriggered() { return llmTriggered; }
    public void setLlmTriggered(boolean llmTriggered) { this.llmTriggered = llmTriggered; }

    public boolean isLlmFillSuccess() { return llmFillSuccess; }
    public void setLlmFillSuccess(boolean llmFillSuccess) { this.llmFillSuccess = llmFillSuccess; }

    public int getLlmApiSuccessCount() { return llmApiSuccessCount; }
    public int getLlmApiFailureCount() { return llmApiFailureCount; }
    public long getLlmApiTotalMs() { return llmApiTotalMs; }

    public void recordLlmApiCall(boolean success, long elapsedMs) {
        if (success) {
            llmApiSuccessCount++;
        } else {
            llmApiFailureCount++;
        }
        llmApiTotalMs += elapsedMs;
    }
}
