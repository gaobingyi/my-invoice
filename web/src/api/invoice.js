import axios from 'axios'

const TOKEN_KEY = 'token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export function getUsername() {
  return localStorage.getItem('username')
}

export function setUsername(name) {
  if (name) localStorage.setItem('username', name)
  else localStorage.removeItem('username')
}

const http = axios.create({ baseURL: '/api' })

http.interceptors.request.use(cfg => {
  const token = getToken()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  res => res,
  err => {
    const isLoginCall = err.config?.url?.endsWith('/auth/login')
    if (err.response?.status === 401 && !isLoginCall) {
      setToken(null)
      setUsername(null)
      // 避免路由依赖：直接整页跳登录
      if (!location.pathname.startsWith('/login')) location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export function login(username, password) {
  return http.post('/auth/login', { username, password })
}

export function uploadInvoice(file) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/invoices/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function listInvoices(page, size, used, keyword) {
  const params = { page, size }
  if (used !== undefined && used !== null) params.used = used
  if (keyword) params.keyword = keyword
  return http.get('/invoices', { params })
}

/** 拉取全部未使用发票（后端 size 上限 100，翻页取全量）。批次编辑「添加发票」下拉用。 */
export async function listAllUnusedInvoices() {
  const PAGE_SIZE = 100
  const MAX_PAGES = 50 // 兜底上限，防异常响应死循环
  const { data: first } = await listInvoices(0, PAGE_SIZE, false)
  const all = [...first.content]
  if (first.content.length < PAGE_SIZE) return all
  const totalPages = first.totalPages ?? Math.ceil(first.totalElements / PAGE_SIZE)
  if (totalPages > MAX_PAGES) {
    console.warn(`未使用发票超过 ${MAX_PAGES * PAGE_SIZE} 张，仅加载前 ${MAX_PAGES * PAGE_SIZE} 张，请分批创建`)
  }
  const pagesToFetch = Math.min(totalPages - 1, MAX_PAGES - 1)
  if (pagesToFetch <= 0) return all
  // 并发拉取剩余页，取代原串行 50 次请求（2.7s → 单轮并发）
  const results = await Promise.all(
    Array.from({ length: pagesToFetch }, (_, i) => listInvoices(i + 1, PAGE_SIZE, false))
  )
  for (const { data } of results) all.push(...data.content)
  return all
}

export function deleteInvoice(id) {
  return http.delete(`/invoices/${id}`)
}

/** ===== 导出批次 =====
 * 创建批次只建记录不下载；ZIP 由批次页按需生成（blob 下载，同 fetchFile 惯例）。 */
export function createExportBatch(ids, batchMonth) {
  return http.post('/export-batches', { ids, batchMonth })
}

export function listExportBatches(page, size) {
  return http.get('/export-batches', { params: { page, size } })
}

/** 批次内发票清单（预览用）。已删除的票不在其中。 */
export function listBatchInvoices(batchId) {
  return http.get(`/export-batches/${batchId}/invoices`)
}

/** 批次编辑：添加未使用发票（后端标记「已使用」并重算张数/合计），返回更新后的批次。 */
export function addBatchInvoices(batchId, ids) {
  return http.post(`/export-batches/${batchId}/invoices`, { ids })
}

/** 批次编辑：移除一张发票（必要时恢复「未使用」），返回更新后的批次。 */
export function removeBatchInvoice(batchId, invoiceId) {
  return http.delete(`/export-batches/${batchId}/invoices/${invoiceId}`)
}

export async function fetchBatchZip(id) {
  const { data } = await http.get(`/export-batches/${id}/zip`, { responseType: 'blob' })
  return data
}

export function deleteExportBatch(id) {
  return http.delete(`/export-batches/${id}`)
}

/** ===== 解析指标 ===== */
export function fetchParsingMetrics() {
  return http.get('/metrics/parsing')
}

export function fetchParsingTrend(days = 30) {
  return http.get('/metrics/parsing/trend', { params: { days } })
}

/** 预览/下载统一走 blob（裸 URL 带不了 Authorization header）。
 * 返回 { url, blob }：url 必须由调用方 URL.revokeObjectURL() 释放，否则 PDF 字节驻留内存。
 * 返回 blob 是为了下载场景下调用方能在 click 后立刻 revoke —— 浏览器已开始下载即可。 */
export async function fetchFile(id, disposition = 'inline') {
  const { data } = await http.get(`/invoices/${id}/file`, {
    params: { disposition },
    responseType: 'blob'
  })
  return { url: URL.createObjectURL(data), blob: data }
}

/** 从 axios 错误里取可读消息。错误响应体可能是 Blob（如代理/网关错误页、401 弹的 blob），
 * 直接拼接会渲染成 "[object Blob]"；这里读文本并优先取 JSON {message}。
 * 网关错误（502/413 等）常回整页 HTML，原样塞进 toast 是不可读的多行噪音 —— 非 JSON
 * 文本一律走 fallback，JSON/纯文本消息也截断到上限。 */
const MAX_MSG_LEN = 200

function cap(msg, fallback) {
  if (!msg) return fallback
  const s = String(msg).trim()
  return s ? s.slice(0, MAX_MSG_LEN) : fallback
}

export async function errorMessage(e, fallback = '请求失败') {
  const data = e?.response?.data
  if (data == null) return cap(e?.message, fallback)
  if (typeof data === 'string') return cap(data, fallback)
  if (data instanceof Blob) {
    try {
      const text = (await data.text()).trim()
      if (!text) return fallback
      try {
        return cap(JSON.parse(text).message, fallback)
      } catch {
        // HTML 错误页（<!DOCTYPE…、<html>）或任何非 JSON 体：不进 toast
        return text.startsWith('<') ? fallback : cap(text, fallback)
      }
    } catch {
      return fallback
    }
  }
  return cap(data.message, fallback)
}
