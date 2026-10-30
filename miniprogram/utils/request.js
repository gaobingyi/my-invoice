const { BASE_URL } = require('./config')

const TOKEN_KEY = 'token'

// 单管理员 + openid 白名单绑定 → 登录无感：wx.login 静默拿 code 换 JWT。
// token 过期（24h）后由 401 重试机制自动重登，用户无感知。
let loginPromise = null

function getToken() {
  return wx.getStorageSync(TOKEN_KEY)
}

function setToken(token) {
  if (token) wx.setStorageSync(TOKEN_KEY, token)
  else wx.removeStorageSync(TOKEN_KEY)
}

/** 静默登录：wx.login code → POST /api/auth/wx-login → 存 token。 */
function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success: res => (res.code ? resolve(res.code) : reject(new Error('wx.login 未返回 code'))),
      fail: () => reject(new Error('wx.login 调用失败'))
    })
  }).then(code => new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + '/api/auth/wx-login',
      method: 'POST',
      data: { code },
      success: res => {
        if (res.statusCode === 200 && res.data && res.data.token) {
          setToken(res.data.token)
          resolve(res.data)
        } else {
          // 4xx 错误体是纯文本（AuthController 的 BadCredentialsException 直接回 message 字符串）
          const msg = (typeof res.data === 'string' && res.data.trim())
            || (res.data && res.data.message)
            || '微信登录失败 ' + res.statusCode
          reject(new Error(msg))
        }
      },
      fail: () => reject(new Error('网络异常，登录失败'))
    })
  }))
}

/** 确保已持有 token；并发调用共享同一次登录。 */
function ensureToken() {
  if (getToken()) return Promise.resolve()
  if (!loginPromise) {
    loginPromise = wxLogin().finally(() => { loginPromise = null })
  }
  return loginPromise
}

function makeError(res) {
  const data = res.data
  // 4xx 错误体可能是纯文本（如 409 重复发票）也可能是 JSON
  const msg = (typeof data === 'string' && data.trim())
    || (data && data.message)
    || ('请求失败 ' + res.statusCode)
  const err = new Error(msg)
  err.statusCode = res.statusCode
  return err
}

/**
 * 处理 401：只清「确实是这次请求被拒的那个 token」——多个并发请求同时 401 时，
 * 第一个重登成功后 setToken(新token)；迟到的 401 若无条件 setToken(null) 会清掉新
 * token 触发又一轮 wx.login。比较 tokenUsed === 当前 token 才清，避免冗余登录。
 * 返回 true 表示已触发重试（调用方应 return），false 表示非 401 或已重试过。
 */
function handle401(res, tokenUsed, retried, retry) {
  if (res.statusCode !== 401 || retried) return false
  if (getToken() === tokenUsed) setToken(null)
  retry()
  return true
}

/** Promise 化 wx.request，自动带 Bearer；401 时清 token 重登并重放一次。 */
function request(options, retried) {
  return ensureToken().then(() => new Promise((resolve, reject) => {
    const tokenUsed = getToken()
    wx.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data,
      // undefined → 走系统默认 60s；调用方可按 options.timeout 放宽（如导出/慢接口）
      timeout: options.timeout,
      header: Object.assign({ Authorization: 'Bearer ' + tokenUsed }, options.header || {}),
      success: res => {
        if (handle401(res, tokenUsed, retried, () =>
          request(options, true).then(resolve).catch(reject))) return
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data)
        else reject(makeError(res))
      },
      fail: () => reject(new Error('网络异常'))
    })
  }))
}

/** 上传发票 PDF。注意 multipart 字段名必须是 file（后端 @RequestParam("file")）。
 *  与 request 一样做 401 重登重放（原实现漏了，token 过期后首次上传直接失败）。 */
function uploadInvoice(filePath, retried) {
  return ensureToken().then(() => new Promise((resolve, reject) => {
    const tokenUsed = getToken()
    wx.uploadFile({
      url: BASE_URL + '/api/invoices/upload',
      filePath,
      name: 'file',
      header: { Authorization: 'Bearer ' + tokenUsed },
      timeout: 120000, // LLM 解析需要数秒~数十秒
      success: res => {
        if (handle401(res, tokenUsed, retried, () =>
          uploadInvoice(filePath, true).then(resolve).catch(reject))) return
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try { resolve(JSON.parse(res.data)) } catch (e) { resolve(null) }
        } else reject(makeError(res))
      },
      fail: () => reject(new Error('上传失败，请检查网络'))
    })
  }))
}

/** 下载发票原 PDF（带 header，勿用裸 URL）。同样补 401 重登重放。返回临时文件路径。 */
function downloadInvoiceFile(id, retried) {
  return ensureToken().then(() => new Promise((resolve, reject) => {
    const tokenUsed = getToken()
    wx.downloadFile({
      url: BASE_URL + '/api/invoices/' + id + '/file',
      header: { Authorization: 'Bearer ' + tokenUsed },
      success: res => {
        if (handle401(res, tokenUsed, retried, () =>
          downloadInvoiceFile(id, true).then(resolve).catch(reject))) return
        if (res.statusCode === 200) resolve(res.tempFilePath)
        else reject(makeError(res))
      },
      fail: () => reject(new Error('下载失败，请检查网络'))
    })
  }))
}

module.exports = { request, uploadInvoice, downloadInvoiceFile, wxLogin, ensureToken, getToken, setToken, BASE_URL }
