package com.example.invoice.entity;

import com.example.invoice.converter.BigDecimalStringConverter;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 导出批次：一次「选票打包」的快照记录。count/total 是创建时点值，不随后续发票删除回填。 */
@Entity
@Table(name = "export_batch")
public class ExportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    /** 批次用途月份 yyyy-MM */
    @Column(name = "batch_month", nullable = false, length = 7)
    private String batchMonth;

    @Column(name = "invoice_count", nullable = false)
    private Integer invoiceCount;

    @Convert(converter = BigDecimalStringConverter.class)
    @Column(name = "total_with_tax", precision = 12, scale = 2)
    private BigDecimal totalWithTax;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchMonth() { return batchMonth; }
    public void setBatchMonth(String batchMonth) { this.batchMonth = batchMonth; }
    public Integer getInvoiceCount() { return invoiceCount; }
    public void setInvoiceCount(Integer invoiceCount) { this.invoiceCount = invoiceCount; }
    public BigDecimal getTotalWithTax() { return totalWithTax; }
    public void setTotalWithTax(BigDecimal totalWithTax) { this.totalWithTax = totalWithTax; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}