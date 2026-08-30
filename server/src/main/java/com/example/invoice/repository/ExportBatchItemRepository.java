package com.example.invoice.repository;

import com.example.invoice.entity.ExportBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportBatchItemRepository extends JpaRepository<ExportBatchItem, Long> {

    List<ExportBatchItem> findByBatchId(Long batchId);

    void deleteByBatchId(Long batchId);

    /** 发票删除联动：清掉该票在所有批次中的关联。 */
    void deleteByInvoiceId(Long invoiceId);

    /** 该发票是否被指定批次之外的其他批次引用（决定删批次时能否恢复「未使用」）。 */
    boolean existsByInvoiceIdAndBatchIdNot(Long invoiceId, Long batchId);

    /** 发票删除校验：该发票是否被任意批次引用（被引用时不可删除）。 */
    boolean existsByInvoiceId(Long invoiceId);

    /** 批次编辑：该发票是否已在指定批次内。 */
    boolean existsByBatchIdAndInvoiceId(Long batchId, Long invoiceId);

    /** 批次编辑：从指定批次移除该发票的关联。 */
    void deleteByBatchIdAndInvoiceId(Long batchId, Long invoiceId);
}
