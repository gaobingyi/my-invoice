package com.example.invoice.dto;

import java.math.BigDecimal;

/** GET /api/invoices/summary 的响应体：与列表同一套过滤条件下的张数与价税合计。
 * totalWithTax 为空集时是 0；解析不出金额的票不计入合计（null 跳过）。 */
public record InvoiceSummaryResponse(long count, BigDecimal totalWithTax) {}
