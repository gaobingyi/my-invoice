package com.example.invoice.entity;

import jakarta.persistence.*;

/** 批次-发票关联。无外键（与全库风格一致）；发票被删时由 InvoiceService.delete 联动清理。 */
@Entity
@Table(name = "export_batch_item")
public class ExportBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    public ExportBatchItem() {}

    public ExportBatchItem(Long batchId, Long invoiceId) {
        this.batchId = batchId;
        this.invoiceId = invoiceId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }
}
