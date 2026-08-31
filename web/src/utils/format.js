/** 通用格式化（InvoiceList / ExportBatchList / InvoiceUpload 共享） */

/** 时间戳 → `YYYY-MM-DD HH:mm:ss`（空值返回空串） */
export function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 金额千分位（解析失败为 null/'' 时返回空串，调用方自行决定是否加 ¥ 前缀）
 *  用字符串运算避免 Number 浮点漂移（如 99999999.99 → 100000000.00）及
 *  toLocaleString 的离线环境本地化漂移（1,234.50 vs 1.234,50）。 */
export function money(v) {
  if (v == null || v === '') return ''
  const s = String(v).trim()
  if (s === '') return ''
  if (!/^-?\d+(\.\d+)?$/.test(s)) return s
  const neg = s.startsWith('-')
  const abs = neg ? s.slice(1) : s
  const [intRaw, decRaw = ''] = abs.split('.')
  const dec = (decRaw + '00').slice(0, 2)
  const intPart = intRaw.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (neg ? '-' : '') + intPart + '.' + dec
}

/** 当前月 `YYYY-MM`（导出批次月份默认值） */
export function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 字节数 → 可读大小（上传列表） */
export function formatSize(bytes) {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
