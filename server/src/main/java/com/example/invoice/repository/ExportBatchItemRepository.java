package com.example.invoice.repository;

import com.example.invoice.entity.ExportBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ExportBatchItemRepository extends JpaRepository<ExportBatchItem, Long> {

    List<ExportBatchItem> findByBatchId(Long batchId);

    void deleteByBatchId(Long batchId);

    /** 该发票是否被指定批次之外的其他批次引用（决定删批次时能否恢复「未使用」）。 */
    boolean existsByInvoiceIdAndBatchIdNot(Long invoiceId, Long batchId);

    /** 删除批次：一次查出这批发票在其他批次中的关联（决定各票能否恢复「未使用」）。 */
    List<ExportBatchItem> findByBatchIdNotAndInvoiceIdIn(Long batchId, Collection<Long> invoiceIds);

    /** 发票删除校验：该发票是否被任意批次引用（被引用时不可删除）。 */
    boolean existsByInvoiceId(Long invoiceId);

    /** 批次编辑：该发票是否已在指定批次内。 */
    boolean existsByBatchIdAndInvoiceId(Long batchId, Long invoiceId);

    /** 批次编辑：从指定批次移除该发票的关联。 */
    void deleteByBatchIdAndInvoiceId(Long batchId, Long invoiceId);

    /** 兜底清理：直接删除发票时遗留的孤儿关联（正常走 InvoiceService.delete 会被拦截，
     * 但脚本/直接 SQL 绕过 guard 时仍需清理，避免 batchInvoices 数量与 invoiceCount 分歧）。 */
    void deleteByInvoiceId(Long invoiceId);
}
