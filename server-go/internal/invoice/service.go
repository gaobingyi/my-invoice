// Package invoice 发票业务服务：上传全流程、列表/汇总、删除、文件解析。
// 行为逐字对齐 my-invoice InvoiceService.java。
package invoice

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"invoice-go/internal/apierr"
	"invoice-go/internal/money"
	"invoice-go/internal/parser"
	"invoice-go/internal/store"
)

const (
	maxName     = 128 // 与 schema 列宽对齐；LLM 幻觉超长值入库前截断
	maxTaxID    = 20
	maxNumber   = 20 // 号码超长走哨兵，绝不截断唯一键
	maxCategory = 64
	maxListSize = 100

	requiredBuyerName  = "上海钦钦印刷科技有限公司"
	requiredBuyerTaxID = "91310116332791646K"
)

// reFilenameUnsafe 对齐 Java replaceAll("[\\\\/:*?\"<>|\r\n\t ]+", "_")。
var reFilenameUnsafe = regexp.MustCompile(`[\\/:*?"<>|\r\n\t ]+`)

type Service struct {
	db        *sql.DB
	parser    *parser.Engine
	uploadDir string
}

func NewService(db *sql.DB, p *parser.Engine, uploadDir string) *Service {
	return &Service{db: db, parser: p, uploadDir: uploadDir}
}

// Invoice 实体（行结构 + JSON 输出形状，camelCase 对齐 Jackson）。
type Invoice struct {
	ID            int64         `json:"id"`
	InvoiceNumber string        `json:"invoiceNumber"`
	InvoiceDate   *string       `json:"invoiceDate"` // yyyy-MM-dd
	BuyerName     *string       `json:"buyerName"`
	BuyerTaxID    *string       `json:"buyerTaxId"`
	SellerName    *string       `json:"sellerName"`
	SellerTaxID   *string       `json:"sellerTaxId"`
	Category      *string       `json:"category"`
	TotalAmount   money.Decimal `json:"totalAmount"`
	TaxAmount     money.Decimal `json:"taxAmount"`
	TotalWithTax  money.Decimal `json:"totalWithTax"`
	FilePath      string        `json:"filePath"`
	Used          bool          `json:"used"`
	UsedAt        *string       `json:"usedAt"` // yyyy-MM-ddTHH:mm:ss
	CreatedAt     *string       `json:"createdAt"`
}

// Upload 上传主流程：tmp 落盘 → 解析 → 购方校验 → 号码哨兵 → 重命名 → 孤儿处理 →
// 截断入库（唯一冲突删副本 409）。tmp 由 defer 统一清理。
// 返回错误均为 apierr 类型；fileName 为 multipart 原始文件名（可能为空）。
func (s *Service) Upload(fileName string, src io.Reader) (*Invoice, error) {
	if err := os.MkdirAll(s.uploadDir, 0o755); err != nil {
		return nil, &apierr.Internal{Err: err, Msg: "create upload dir"}
	}

	tmp := filepath.Join(s.uploadDir, uuidV4()+".pdf")
	defer os.Remove(tmp) // 成功路径 tmp 已被 move 走，Remove 是空操作

	// 先写 tmp（成功路径才会改名）；空文件/扩展名校验顺序对齐 Java（空文件优先）。
	n, err := copyTo(tmp, src)
	if err != nil {
		return nil, err // 可能是 MaxBytesReader 的超限错误，由 API 层映射 413
	}
	if n == 0 {
		return nil, apierr.Bad("文件为空")
	}
	name := strings.ToLower(fileName)
	if !strings.HasSuffix(name, ".pdf") {
		return nil, apierr.Bad("仅支持 PDF")
	}

	p, err := s.parser.Parse(tmp)
	if err != nil {
		// 文件名是 .pdf 但内容损坏/加密 → 用户输入问题 400，而非 500。
		return nil, apierr.Bad("无法解析 PDF 内容")
	}

	if err := validateBuyer(p); err != nil {
		return nil, err
	}

	// 号码哨兵：>20 位（LLM 幻觉）视为未解析——截断会让共享 20 字符前缀的两张票
	// 撞 UNIQUE，误判 409。
	var number string
	if p.InvoiceNumber != nil && len(*p.InvoiceNumber) <= maxNumber {
		number = *p.InvoiceNumber
	}
	sentinel := ""
	if number == "" {
		sentinel = "UNKNOWN-" + randomHex12()
	}
	resolved := number
	if resolved == "" {
		resolved = sentinel
	}

	dest := filepath.Join(s.uploadDir, buildFilename(p, name, resolved))
	if dest != tmp {
		// dest 同名冲突：先查 DB 区分「真重复」与「孤儿文件」（崩溃/非唯一约束失败遗留）。
		for attempt := 0; attempt < 2; attempt++ {
			// move 用 os.Link（存在即失败，原子）：对齐 Java Files.move 不带
			// REPLACE_EXISTING 的语义——os.Rename 会静默顶掉已有文件，毁掉上一张
			// 票的 PDF。链接成功后删 tmp（此后 defer 的 Remove 是空操作）。
			err := os.Link(tmp, dest)
			if err == nil {
				os.Remove(tmp)
				break
			}
			if !os.IsExist(err) {
				return nil, &apierr.Internal{Err: err, Msg: "move upload"}
			}
			if s.existsByNumber(resolved) {
				// 真重复：DB 已有同号行，dest 上的文件就是它的源 PDF，碰不得。
				// 哨兵场景 409 消息显示字面量 UNKNOWN（对齐 Java）。
				display := number
				if display == "" {
					display = "UNKNOWN"
				}
				return nil, &apierr.Duplicate{Msg: "发票号码已存在: " + display}
			}
			// 孤儿文件：DB 无行，删了重试。
			os.Remove(dest)
			if attempt == 1 {
				return nil, &apierr.Internal{
					Err: err,
					Msg: fmt.Sprintf("无法清理孤儿文件: %s (DB 无 %s)", dest, resolved),
				}
			}
		}
	}

	nowStore := store.NowStamp()
	inv := &Invoice{
		InvoiceNumber: resolved,
		InvoiceDate:   p.InvoiceDate,
		BuyerName:     truncate(p.BuyerName, maxName),
		BuyerTaxID:    truncate(p.BuyerTaxID, maxTaxID),
		SellerName:    truncate(p.SellerName, maxName),
		SellerTaxID:   truncate(p.SellerTaxID, maxTaxID),
		Category:      truncate(p.Category, maxCategory),
		TotalAmount:   p.TotalAmount,
		TaxAmount:     p.TaxAmount,
		TotalWithTax:  p.TotalWithTax,
		FilePath:      relPath(s.uploadDir, dest),
		Used:          false,
		CreatedAt:     strPtr(store.NormalizeStamp(nowStore)),
	}
	if err := s.insert(inv, nowStore); err != nil {
		if store.IsUniqueViolation(err) {
			// 只有唯一键冲突才删刚 move 过来的副本（内容与存活行相同，删了不留孤儿）；
			// 非 NOT NULL/CHECK 类失败保留文件如实 500——修复场景下 dest 是唯一恢复机会。
			os.Remove(dest)
			display := number
			if display == "" {
				display = "UNKNOWN"
			}
			return nil, &apierr.Duplicate{Msg: "发票号码已存在: " + display}
		}
		return nil, &apierr.Internal{Err: err, Msg: "save invoice"}
	}
	return inv, nil
}

func (s *Service) insert(inv *Invoice, createdAt string) error {
	res, err := s.db.Exec(`INSERT INTO invoice
	      (invoice_number, invoice_date, buyer_name, buyer_tax_id, seller_name, seller_tax_id,
	       category, total_amount, tax_amount, total_with_tax, file_path, used, used_at, created_at)
	    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?)`,
		inv.InvoiceNumber, store.DateArg(inv.InvoiceDate), nullStr(inv.BuyerName), nullStr(inv.BuyerTaxID),
		nullStr(inv.SellerName), nullStr(inv.SellerTaxID), nullStr(inv.Category),
		decimalStr(inv.TotalAmount), decimalStr(inv.TaxAmount), decimalStr(inv.TotalWithTax),
		inv.FilePath, createdAt)
	if err != nil {
		return err
	}
	inv.ID, err = res.LastInsertId()
	return err
}

func (s *Service) existsByNumber(number string) bool {
	var one int
	err := s.db.QueryRow("SELECT 1 FROM invoice WHERE invoice_number = ?", number).Scan(&one)
	return err == nil
}

// List 分页列表（createdAt DESC，同秒以 id DESC 稳定排序）。page/size 越界钳制。
func (s *Service) List(page, size int, used *bool, keyword string) ([]*Invoice, int64, error) {
	if page < 0 {
		page = 0
	}
	if size < 1 {
		size = 1
	}
	if size > maxListSize {
		size = maxListSize
	}
	where, args := filterClause(used, keyword)
	var total int64
	if err := s.db.QueryRow("SELECT COUNT(*) FROM invoice"+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	q := "SELECT " + InvoiceCols + " FROM invoice" + where +
		" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
	rows, err := s.db.Query(q, append(args, size, page*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []*Invoice
	for rows.Next() {
		inv, err := ScanInvoice(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, inv)
	}
	return out, total, rows.Err()
}

// Summary 与列表同套过滤下的张数 + 价税合计（金额 NULL 跳过不计入，张数计入）。
// 个人库规模全量取回内存加总——金额列是 TEXT，SQL SUM 不可靠（对齐 Java 注释）。
func (s *Service) Summary(used *bool, keyword string) (int64, money.Decimal, error) {
	where, args := filterClause(used, keyword)
	rows, err := s.db.Query("SELECT "+InvoiceCols+" FROM invoice"+where, args...)
	if err != nil {
		return 0, money.Decimal{}, err
	}
	defer rows.Close()
	var count int64
	total := money.Must("0")
	for rows.Next() {
		inv, err := ScanInvoice(rows)
		if err != nil {
			return 0, money.Decimal{}, err
		}
		count++
		if !inv.TotalWithTax.IsNull() {
			total = money.Add(total, inv.TotalWithTax)
		}
	}
	return count, total, rows.Err()
}

func (s *Service) Get(id int64) (*Invoice, error) {
	row := s.db.QueryRow("SELECT "+InvoiceCols+" FROM invoice WHERE id = ?", id)
	inv, err := ScanInvoice(row)
	if err == sql.ErrNoRows {
		return nil, apierr.Bad("发票不存在: %d", id)
	}
	return inv, err
}

// ResolveFile 预览/下载用：查行 + safePath（不判文件存在——对齐 Java，
// 磁盘缺失在写流时报 500）。
func (s *Service) ResolveFile(id int64) (string, *Invoice, error) {
	inv, err := s.Get(id)
	if err != nil {
		return "", nil, err
	}
	p, err := s.safePath(inv.FilePath)
	if err != nil {
		return "", nil, err
	}
	return p, inv, nil
}

// ResolveFileOrNull 导出打包用：文件缺失返回 ""（writeZip 记缺失清单而非中断）。
func (s *Service) ResolveFileOrNull(inv *Invoice) (string, error) {
	p, err := s.safePath(inv.FilePath)
	if err != nil {
		return "", err
	}
	if _, err := os.Stat(p); err != nil {
		return "", nil
	}
	return p, nil
}

// Delete 事务内「批次引用守卫 → 删文件 → 删行」收为原子单元（单连接下与批次
// 写事务串行，杜绝守卫通过后被并发批次引用的交错窗口）。
func (s *Service) Delete(id int64) error {
	tx, err := s.db.Begin()
	if err != nil {
		return &apierr.Internal{Err: err, Msg: "begin tx"}
	}
	defer tx.Rollback()
	var filePath string
	err = tx.QueryRow("SELECT file_path FROM invoice WHERE id = ?", id).Scan(&filePath)
	if err == sql.ErrNoRows {
		return apierr.Bad("发票不存在: %d", id)
	}
	if err != nil {
		return &apierr.Internal{Err: err, Msg: "find invoice"}
	}
	var one int
	if err := tx.QueryRow(
		"SELECT 1 FROM export_batch_item WHERE invoice_id = ? LIMIT 1", id).Scan(&one); err == nil {
		return apierr.Bad("该发票已被导出批次使用，不可删除。请先删除相关批次或从批次中移除该发票")
	} else if err != sql.ErrNoRows {
		return &apierr.Internal{Err: err, Msg: "check batch item"}
	}
	// 先删文件：删行失败可重试自愈；反之先删行则文件永久孤儿。
	if p, err := s.safePath(filePath); err == nil {
		os.Remove(p)
	}
	if _, err := tx.Exec("DELETE FROM invoice WHERE id = ?", id); err != nil {
		return &apierr.Internal{Err: err, Msg: "delete invoice"}
	}
	return tx.Commit()
}

// ────── 内部工具 ──────

const InvoiceCols = `id, invoice_number, invoice_date, buyer_name, buyer_tax_id,
	seller_name, seller_tax_id, category, total_amount, tax_amount, total_with_tax,
	file_path, used, used_at, created_at`

type RowScanner interface{ Scan(dest ...any) error }

func ScanInvoice(rs RowScanner) (*Invoice, error) {
	var inv Invoice
	var number, filePath string
	var used int
	var date, buyerName, buyerTax, sellerName, sellerTax, category sql.NullString
	var totalAmount, taxAmount, totalWithTax, usedAt, createdAt sql.NullString
	if err := rs.Scan(&inv.ID, &number, &date, &buyerName, &buyerTax, &sellerName, &sellerTax,
		&category, &totalAmount, &taxAmount, &totalWithTax, &filePath, &used, &usedAt, &createdAt); err != nil {
		return nil, err
	}
	inv.InvoiceNumber = number
	inv.FilePath = filePath
	inv.Used = used != 0
	ptr := func(n sql.NullString) *string {
		if !n.Valid {
			return nil
		}
		v := n.String
		return &v
	}
	// 日期/时刻列归一化回 API 形态（见 store/stamps.go）：DB 里是 Xerial 存储形态
	datePtr := func(n sql.NullString) *string {
		if !n.Valid {
			return nil
		}
		v := store.NormalizeDate(n.String)
		return &v
	}
	stampPtr := func(n sql.NullString) *string {
		if !n.Valid {
			return nil
		}
		v := store.NormalizeStamp(n.String)
		return &v
	}
	inv.InvoiceDate, inv.BuyerName, inv.BuyerTaxID = datePtr(date), ptr(buyerName), ptr(buyerTax)
	inv.SellerName, inv.SellerTaxID, inv.Category = ptr(sellerName), ptr(sellerTax), ptr(category)
	inv.UsedAt, inv.CreatedAt = stampPtr(usedAt), stampPtr(createdAt)
	inv.TotalAmount = decimalOrNull(totalAmount)
	inv.TaxAmount = decimalOrNull(taxAmount)
	inv.TotalWithTax = decimalOrNull(totalWithTax)
	return &inv, nil
}

func decimalOrNull(n sql.NullString) money.Decimal {
	if !n.Valid || strings.TrimSpace(n.String) == "" {
		return money.Decimal{}
	}
	d, err := money.New(n.String)
	if err != nil {
		return money.Decimal{} // 老库脏数据防御
	}
	return d
}

// filterClause list/summary 共用过滤：keyword 转义 LIKE 通配符（%/_ 视为字面量），
// 命中字段 = 销售方/购买方/发票号码/项目名称。
func filterClause(used *bool, keyword string) (string, []any) {
	hasKeyword := strings.TrimSpace(keyword) != ""
	if !hasKeyword && used == nil {
		return "", nil
	}
	var conds []string
	var args []any
	if used != nil {
		conds = append(conds, "used = ?")
		if *used {
			args = append(args, 1)
		} else {
			args = append(args, 0)
		}
	}
	if hasKeyword {
		esc := strings.TrimSpace(keyword)
		esc = strings.ReplaceAll(esc, `\`, `\\`)
		esc = strings.ReplaceAll(esc, `%`, `\%`)
		esc = strings.ReplaceAll(esc, `_`, `\_`)
		kw := "%" + esc + "%"
		conds = append(conds,
			`seller_name LIKE ? ESCAPE '\'`,
			`buyer_name LIKE ? ESCAPE '\'`,
			`invoice_number LIKE ? ESCAPE '\'`,
			`category LIKE ? ESCAPE '\'`)
		args = append(args, kw, kw, kw, kw)
	}
	return " WHERE " + strings.Join(conds, " AND "), args
}

// validateBuyer 购买方必须是钦钦公司（名称+信用代码都匹配）；nil 视为不符同样拒绝。
// 消息只带解析到的值，用户能区分「解析失败」与「真买到别家票」。
func validateBuyer(p parser.Parsed) error {
	name, taxID := "", ""
	if p.BuyerName != nil {
		name = strings.TrimSpace(*p.BuyerName)
	}
	if p.BuyerTaxID != nil {
		taxID = strings.TrimSpace(*p.BuyerTaxID)
	}
	if name == requiredBuyerName && taxID == requiredBuyerTaxID {
		return nil
	}
	parsed := "未解析到购买方"
	switch {
	case name == "" && taxID == "":
	case name == "":
		parsed = "信用代码 " + taxID
	case taxID == "":
		parsed = name
	default:
		parsed = name + "（" + taxID + "）"
	}
	return apierr.Bad("购买方校验失败：%s", parsed)
}

// buildFilename `<销售方>_<号码>.<ext>`；销售方 nil → UNKNOWN；非法字符压成 _；
// ext 取（已小写的）原名最后一个 . 起的子串，无 . 则 .pdf。
func buildFilename(p parser.Parsed, lowercasedName, resolvedNumber string) string {
	seller := "UNKNOWN"
	if p.SellerName != nil {
		seller = *p.SellerName
	}
	safe := reFilenameUnsafe.ReplaceAllString(seller+"_"+resolvedNumber, "_")
	ext := ".pdf"
	if i := strings.LastIndexByte(lowercasedName, '.'); i >= 0 {
		ext = lowercasedName[i:]
	}
	return safe + ext
}

func truncate(s *string, max int) *string {
	if s == nil {
		return nil
	}
	rs := []rune(*s)
	if len(rs) <= max {
		return s
	}
	out := string(rs[:max])
	return &out
}

// safePath 校验 DB 相对路径不逃出 uploadDir（防御直接改库的越权路径）。
// 按路径元素比较（对齐 Java Path.startsWith 的元素语义，字符串前缀会放过 …uploads-evil）。
func (s *Service) safePath(relative string) (string, error) {
	p := filepath.Clean(filepath.Join(s.uploadDir, relative))
	base := filepath.Clean(s.uploadDir)
	if p != base && !strings.HasPrefix(p, base+string(filepath.Separator)) {
		return "", apierr.Bad("非法文件路径")
	}
	return p, nil
}

func copyTo(path string, src io.Reader) (int64, error) {
	f, err := os.Create(path)
	if err != nil {
		return 0, &apierr.Internal{Err: err, Msg: "create tmp"}
	}
	defer f.Close()
	n, err := io.Copy(f, src)
	if err != nil {
		return n, err // 可能是请求体超限，API 层按错误串映射 413
	}
	return n, nil
}

func uuidV4() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

func randomHex12() string {
	var b [6]byte
	_, _ = rand.Read(b[:]) // 48 位 CSPRNG，哨兵唯一性足够
	return hex.EncodeToString(b[:])
}

func relPath(base, dest string) string {
	r, err := filepath.Rel(base, dest)
	if err != nil {
		slog.Warn("relpath 失败", "base", base, "dest", dest, "err", err)
		return dest
	}
	return r
}

func nullStr(s *string) any {
	if s == nil {
		return nil
	}
	return *s
}

func decimalStr(d money.Decimal) any {
	if d.IsNull() {
		return nil
	}
	return d.String()
}

func strPtr(s string) *string { return &s }
