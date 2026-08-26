package com.example.invoice.repository;

import com.example.invoice.entity.ExportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportBatchRepository extends JpaRepository<ExportBatch, Long> {
    boolean existsByBatchMonth(String batchMonth);
}
