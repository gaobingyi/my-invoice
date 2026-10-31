// Package export 导出批次：创建/编辑/删除批次（used 标记与恢复）、ZIP 打包、Excel 清单。
// 行为逐字对齐 my-invoice ExportService.java。
package export

import (
	"database/sql"
	"regexp"
	"strings"

	"invoice-go/internal/apierr"
	"invoice-go/internal/invoice"
	"invoice-go/internal/money"
	"invoice-go/internal/store"
)

var reMonth = regexp.MustCompile(`^\d{4}-(0[1-9]|1[0-2])$`)

// maxIDs 业务上限，也防超长 IN 列表触发 SQLite 绑定参数上限。
const maxIDs = 1000

type Service struct {
	db       *sql.DB
	invoices *invoice.Service
}

func NewService(db *sql.DB, invoices *invoice.Service) *Service {
	return &Service{db: db, invoices: invoices}
}

// Batch 实体（JSON 形状对齐 Jackson）。
type Batch struct {
	ID           int64         `json:"id"`
	BatchMonth   string        `json:"batchMonth"`
	InvoiceCount int64         `json:"invoiceCount"`
	TotalWithTax money.Decimal `json:"totalWithTax"`
	CreatedAt    *string       `json:"createdAt"`
}

const batchCols = `id, batch_month, invoice_count, total_with_tax, created_at`

func scanBatch(rs rowScanner) (*Batch, error) {
	var b Batch
	var month string
	var count int64
	var total sql.NullString
	var createdAt sql.NullString
	if err := rs.Scan(&b.ID, &month, &count, &total, &createdAt); err != nil {
		return nil, err
	}
	b.BatchMonth = month
	b.InvoiceCount = count
	b.TotalWithTax = decimalOrNull(total)
	if createdAt.Valid {
		v := store.NormalizeStamp(createdAt.String)
		b.CreatedAt = &v
	}
	return &b, nil
}

type rowScanner = invoice.RowScanner

func decimalOrNull(n sql.NullString) money.Decimal {
	if !n.Valid || strings.TrimSpace(n.String) == "" {
		return money.Decimal{}
	}
	d, err := money.New(n.String)
	if err != nil {
		return money.Decimal{}
	}
	return d
}

// CreateBatch 创建批次并标记发票已使用（只建记录不生成 ZIP）。校验顺序对齐 Java。
func (s *Service) CreateBatch(ids []int64, batchMonth string) (*Batch, error) {
	if !reMonth.MatchString(batchMonth) {
		return nil, apierr.Bad("批次月份格式应为 yyyy-MM")
	}
	if s.batchMonthExists(batchMonth) {
		return nil, apierr.Bad("该批次月份已存在，不可重复创建")
	}
	if len(ids) == 0 {
		return nil, apierr.Bad("请至少选择一张发票")
	}
	if len(ids) > maxIDs {
		return nil, apierr.Bad("单次操作最多 %d 张发票", maxIDs)
	}

	tx, err := s.db.Begin()
	if err != nil {
		return nil, serverErr(err)
	}
	defer tx.Rollback()

	invoices, err := findInvoicesByIDs(tx, ids) // 被删的票容忍消失，全没了才拦
	if err != nil {
		return nil, serverErr(err)
	}
	if len(invoices) == 0 {
		return nil, apierr.Bad("所选发票均不存在（可能已被删除）")
	}
	var usedNumbers []string
	for _, inv := range invoices {
		if inv.Used {
			usedNumbers = append(usedNumbers, inv.InvoiceNumber)
		}
	}
	if len(usedNumbers) > 0 {
		return nil, apierr.Bad("以下发票已使用，不可重复打包：%s", strings.Join(usedNumbers, "、"))
	}
	total := money.Must("0")
	for _, inv := range invoices {
		if !inv.TotalWithTax.IsNull() {
			total = money.Add(total, inv.TotalWithTax)
		}
	}

	now := store.NowStamp() // 存储形态与 Xerial 一致；响应用 NormalizeStamp 转回 API 形态
	res, err := tx.Exec(
		"INSERT INTO export_batch (batch_month, invoice_count, total_with_tax, created_at) VALUES (?, ?, ?, ?)",
		batchMonth, len(invoices), decimalStr(total), now)
	if err != nil {
		if store.IsUniqueViolation(err) {
			// 唯一索引兜底（exists 与 insert 间极窄竞态），转成与预检一致的友好提示
			return nil, apierr.Bad("该批次月份已存在，不可重复创建")
		}
		return nil, serverErr(err)
	}
	batchID, _ := res.LastInsertId()

	for _, inv := range invoices {
		if _, err := tx.Exec("UPDATE invoice SET used = 1, used_at = ? WHERE id = ?", now, inv.ID); err != nil {
			return nil, serverErr(err)
		}
		if _, err := tx.Exec(
			"INSERT INTO export_batch_item (batch_id, invoice_id) VALUES (?, ?)", batchID, inv.ID); err != nil {
			if store.IsUniqueViolation(err) {
				return nil, apierr.Bad("以下发票已在本批次中：（并发操作冲突，请刷新后重试）")
			}
			return nil, serverErr(err)
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, serverErr(err)
	}
	return &Batch{
		ID:           batchID,
		BatchMonth:   batchMonth,
		InvoiceCount: int64(len(invoices)),
		TotalWithTax: total,
		CreatedAt:    strPtr(store.NormalizeStamp(now)),
	}, nil
}

func (s *Service) batchMonthExists(month string) bool {
	var one int
	err := s.db.QueryRow("SELECT 1 FROM export_batch WHERE batch_month = ? LIMIT 1", month).Scan(&one)
	return err == nil
}

// List 批次分页（createdAt DESC，同秒 id DESC 稳定）。
func (s *Service) List(page, size int) ([]*Batch, int64, error) {
	if page < 0 {
		page = 0
	}
	if size < 1 {
		size = 1
	}
	if size > 100 {
		size = 100
	}
	var total int64
	if err := s.db.QueryRow("SELECT COUNT(*) FROM export_batch").Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.Query(
		"SELECT "+batchCols+" FROM export_batch ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
		size, page*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []*Batch
	for rows.Next() {
		b, err := scanBatch(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, b)
	}
	return out, total, rows.Err()
}

func (s *Service) getBatch(tx *sql.Tx, id int64) (*Batch, error) {
	var row rowScanner = s.db.QueryRow("SELECT "+batchCols+" FROM export_batch WHERE id = ?", id)
	if tx != nil {
		row = tx.QueryRow("SELECT "+batchCols+" FROM export_batch WHERE id = ?", id)
	}
	b, err := scanBatch(row)
	if err == sql.ErrNoRows {
		return nil, apierr.Bad("导出批次不存在: %d", id)
	}
	return b, err
}

// GetBatch 批次单查。
func (s *Service) GetBatch(id int64) (*Batch, error) { return s.getBatch(nil, id) }

// BatchInvoices 批次包含的发票，按关联条目入库顺序（加入批次顺序）；已删发票跳过。
func (s *Service) BatchInvoices(batchID int64) ([]*invoice.Invoice, error) {
	rows, err := s.db.Query(
		"SELECT invoice_id FROM export_batch_item WHERE batch_id = ? ORDER BY id", batchID)
	if err != nil {
		return nil, err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return s.invoicesByIDs(s.db, ids)
}

// findInvoicesByIDs 事务内按 id 集合取发票（无序）。
func findInvoicesByIDs(tx *sql.Tx, ids []int64) ([]*invoice.Invoice, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	q, args := "SELECT "+invoice.InvoiceCols+" FROM invoice WHERE id IN (", make([]any, 0, len(ids))
	for i, id := range ids {
		if i > 0 {
			q += ","
		}
		q += "?"
		args = append(args, id)
	}
	rows, err := tx.Query(q+")", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*invoice.Invoice
	for rows.Next() {
		inv, err := invoice.ScanInvoice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, inv)
	}
	return out, rows.Err()
}

// invoicesByIDs 按 id 顺序取发票，已删的跳过（非事务版）。
func (s *Service) invoicesByIDs(db *sql.DB, ids []int64) ([]*invoice.Invoice, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	q, args := "SELECT "+invoice.InvoiceCols+" FROM invoice WHERE id IN (", make([]any, 0, len(ids))
	for i, id := range ids {
		if i > 0 {
			q += ","
		}
		q += "?"
		args = append(args, id)
	}
	rows, err := db.Query(q+")", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[int64]*invoice.Invoice{}
	for rows.Next() {
		inv, err := invoice.ScanInvoice(rows)
		if err != nil {
			return nil, err
		}
		byID[inv.ID] = inv
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var out []*invoice.Invoice
	for _, id := range ids {
		if inv, ok := byID[id]; ok {
			out = append(out, inv)
		}
	}
	return out, nil
}

// AddInvoices 向批次添加未使用发票并标记「已使用」，返回重算后的批次。
func (s *Service) AddInvoices(batchID int64, ids []int64) (*Batch, error) {
	batch, err := s.getBatch(nil, batchID)
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return nil, apierr.Bad("请至少选择一张发票")
	}
	if len(ids) > maxIDs {
		return nil, apierr.Bad("单次操作最多 %d 张发票", maxIDs)
	}
	// 去重（对齐 ids.stream().distinct()）
	seen := map[int64]bool{}
	var distinct []int64
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			distinct = append(distinct, id)
		}
	}

	tx, err := s.db.Begin()
	if err != nil {
		return nil, serverErr(err)
	}
	defer tx.Rollback()
	invoices, err := findInvoicesByIDs(tx, distinct)
	if err != nil {
		return nil, serverErr(err)
	}
	if len(invoices) == 0 {
		return nil, apierr.Bad("所选发票均不存在（可能已被删除）")
	}
	inBatch := map[int64]bool{}
	rows, err := tx.Query("SELECT invoice_id FROM export_batch_item WHERE batch_id = ?", batchID)
	if err != nil {
		return nil, serverErr(err)
	}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, serverErr(err)
		}
		inBatch[id] = true
	}
	rows.Close()
	var inBatchNames, usedNames []string
	for _, inv := range invoices {
		switch {
		case inBatch[inv.ID]:
			inBatchNames = append(inBatchNames, inv.InvoiceNumber)
		case inv.Used:
			usedNames = append(usedNames, inv.InvoiceNumber)
		}
	}
	if len(inBatchNames) > 0 {
		return nil, apierr.Bad("以下发票已在本批次中：%s", strings.Join(inBatchNames, "、"))
	}
	if len(usedNames) > 0 {
		return nil, apierr.Bad("以下发票已使用，不可重复打包：%s", strings.Join(usedNames, "、"))
	}
	now := store.NowStamp()
	for _, inv := range invoices {
		if _, err := tx.Exec("UPDATE invoice SET used = 1, used_at = ? WHERE id = ?", now, inv.ID); err != nil {
			return nil, serverErr(err)
		}
		if _, err := tx.Exec(
			"INSERT INTO export_batch_item (batch_id, invoice_id) VALUES (?, ?)", batchID, inv.ID); err != nil {
			if store.IsUniqueViolation(err) {
				return nil, apierr.Bad("以下发票已在本批次中：（并发操作冲突，请刷新后重试）")
			}
			return nil, serverErr(err)
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, serverErr(err)
	}
	return s.recalcBatch(batch)
}

// RemoveInvoice 从批次移除一张发票；不再被其他批次引用时恢复「未使用」。
func (s *Service) RemoveInvoice(batchID, invoiceID int64) (*Batch, error) {
	batch, err := s.getBatch(nil, batchID)
	if err != nil {
		return nil, err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, serverErr(err)
	}
	defer tx.Rollback()
	var one int
	err = tx.QueryRow(
		"SELECT 1 FROM export_batch_item WHERE batch_id = ? AND invoice_id = ?",
		batchID, invoiceID).Scan(&one)
	if err == sql.ErrNoRows {
		return nil, apierr.Bad("该发票不在本批次中，无法移除")
	}
	if err != nil {
		return nil, serverErr(err)
	}
	if _, err := tx.Exec(
		"DELETE FROM export_batch_item WHERE batch_id = ? AND invoice_id = ?", batchID, invoiceID); err != nil {
		return nil, serverErr(err)
	}
	if err := restoreIfUnreferenced(tx, invoiceID, batchID); err != nil {
		return nil, serverErr(err)
	}
	if err := tx.Commit(); err != nil {
		return nil, serverErr(err)
	}
	return s.recalcBatch(batch)
}

// restoreIfUnreferenced 恢复规则：不再被任何其他批次引用 且 used=true → used=0, used_at=NULL。
func restoreIfUnreferenced(tx *sql.Tx, invoiceID, excludeBatchID int64) error {
	var one int
	err := tx.QueryRow(
		"SELECT 1 FROM export_batch_item WHERE invoice_id = ? AND batch_id != ? LIMIT 1",
		invoiceID, excludeBatchID).Scan(&one)
	if err == nil {
		return nil // 仍被其他批次引用
	}
	if err != sql.ErrNoRows {
		return err
	}
	_, err = tx.Exec(
		"UPDATE invoice SET used = 0, used_at = NULL WHERE id = ? AND used = 1", invoiceID)
	return err
}

// recalcBatch 按现存 item 重算 count/total；孤儿 item 不计数；允许清空为 0 张。
func (s *Service) recalcBatch(batch *Batch) (*Batch, error) {
	invoices, err := s.BatchInvoices(batch.ID)
	if err != nil {
		return nil, serverErr(err)
	}
	total := money.Must("0")
	for _, inv := range invoices {
		if !inv.TotalWithTax.IsNull() {
			total = money.Add(total, inv.TotalWithTax)
		}
	}
	if _, err := s.db.Exec(
		"UPDATE export_batch SET invoice_count = ?, total_with_tax = ? WHERE id = ?",
		len(invoices), decimalStr(total), batch.ID); err != nil {
		return nil, serverErr(err)
	}
	batch.InvoiceCount = int64(len(invoices))
	batch.TotalWithTax = total
	return batch, nil
}

// DeleteBatch 删除批次：关联项一并删除；不再被其他批次引用的发票恢复「未使用」。
// 返回恢复数量（供前端提示）。
func (s *Service) DeleteBatch(id int64) (int, error) {
	if _, err := s.getBatch(nil, id); err != nil {
		return 0, err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return 0, serverErr(err)
	}
	defer tx.Rollback()
	rows, err := tx.Query("SELECT invoice_id FROM export_batch_item WHERE batch_id = ? ORDER BY id", id)
	if err != nil {
		return 0, serverErr(err)
	}
	var invoiceIDs []int64
	for rows.Next() {
		var v int64
		if err := rows.Scan(&v); err != nil {
			rows.Close()
			return 0, serverErr(err)
		}
		invoiceIDs = append(invoiceIDs, v)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, serverErr(err)
	}
	// 一次查出这批发票在其他批次中的关联
	referencedElsewhere := map[int64]bool{}
	if len(invoiceIDs) > 0 {
		q, args := "SELECT invoice_id FROM export_batch_item WHERE batch_id != ? AND invoice_id IN (",
			[]any{id}
		for i, v := range invoiceIDs {
			if i > 0 {
				q += ","
			}
			q += "?"
			args = append(args, v)
		}
		r2, err := tx.Query(q+")", args...)
		if err != nil {
			return 0, serverErr(err)
		}
		for r2.Next() {
			var v int64
			if err := r2.Scan(&v); err != nil {
				r2.Close()
				return 0, serverErr(err)
			}
			referencedElsewhere[v] = true
		}
		r2.Close()
	}
	// restored 仅统计实际恢复的
	restored := 0
	seen := map[int64]bool{}
	for _, invoiceID := range invoiceIDs {
		if referencedElsewhere[invoiceID] || seen[invoiceID] {
			continue
		}
		seen[invoiceID] = true
		var used int
		err := tx.QueryRow("SELECT used FROM invoice WHERE id = ?", invoiceID).Scan(&used)
		if err == sql.ErrNoRows {
			continue
		}
		if err != nil {
			return 0, serverErr(err)
		}
		if used != 0 {
			if _, err := tx.Exec(
				"UPDATE invoice SET used = 0, used_at = NULL WHERE id = ?", invoiceID); err != nil {
				return 0, serverErr(err)
			}
			restored++
		}
	}
	if _, err := tx.Exec("DELETE FROM export_batch_item WHERE batch_id = ?", id); err != nil {
		return 0, serverErr(err)
	}
	if _, err := tx.Exec("DELETE FROM export_batch WHERE id = ?", id); err != nil {
		return 0, serverErr(err)
	}
	return restored, tx.Commit()
}

func serverErr(err error) error { return &apierr.Internal{Err: err, Msg: "internal"} }

func decimalStr(d money.Decimal) any {
	if d.IsNull() {
		return nil
	}
	return d.String()
}

func strPtr(s string) *string { return &s }
