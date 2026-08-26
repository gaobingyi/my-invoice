package com.example.invoice.entity;

import com.example.invoice.converter.BigDecimalStringConverter;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 20)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "buyer_name", length = 128)
    private String buyerName;

    @Column(name = "buyer_tax_id", length = 20)
    private String buyerTaxId;

    @Column(name = "seller_name", length = 128)
    private String sellerName;

    @Column(name = "seller_tax_id", length = 20)
    private String sellerTaxId;

    @Column(name = "category", length = 64)
    private String category;

    @Convert(converter = BigDecimalStringConverter.class)
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Convert(converter = BigDecimalStringConverter.class)
    @Column(name = "tax_amount", precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @Convert(converter = BigDecimalStringConverter.class)
    @Column(name = "total_with_tax", precision = 12, scale = 2)
    private BigDecimal totalWithTax;

    @Column(name = "drawer", length = 64)
    private String drawer;

    @Column(name = "file_path", nullable = false, length = 255)
    private String filePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public String getBuyerTaxId() { return buyerTaxId; }
    public void setBuyerTaxId(String buyerTaxId) { this.buyerTaxId = buyerTaxId; }
    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }
    public String getSellerTaxId() { return sellerTaxId; }
    public void setSellerTaxId(String sellerTaxId) { this.sellerTaxId = sellerTaxId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getTotalWithTax() { return totalWithTax; }
    public void setTotalWithTax(BigDecimal totalWithTax) { this.totalWithTax = totalWithTax; }
    public String getDrawer() { return drawer; }
    public void setDrawer(String drawer) { this.drawer = drawer; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
