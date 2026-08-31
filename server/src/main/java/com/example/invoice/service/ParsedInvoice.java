package com.example.invoice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record ParsedInvoice(
        String invoiceNumber,
        LocalDate invoiceDate,
        String buyerName,
        String buyerTaxId,
        String sellerName,
        String sellerTaxId,
        String category,
        BigDecimal totalAmount,
        BigDecimal taxAmount,
        BigDecimal totalWithTax
) {
    /** 缺失字段名单（字段名与 LLM prompt 约定一致）。正则指标、LLM 兜底共用，
     * 避免每个消费方各自枚举一遍 10 个字段后随实体演进而漂移。 */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (invoiceNumber == null) missing.add("invoiceNumber");
        if (invoiceDate == null) missing.add("invoiceDate");
        if (buyerName == null) missing.add("buyerName");
        if (buyerTaxId == null) missing.add("buyerTaxId");
        if (sellerName == null) missing.add("sellerName");
        if (sellerTaxId == null) missing.add("sellerTaxId");
        if (category == null) missing.add("category");
        if (totalAmount == null) missing.add("totalAmount");
        if (taxAmount == null) missing.add("taxAmount");
        if (totalWithTax == null) missing.add("totalWithTax");
        return missing;
    }
}
