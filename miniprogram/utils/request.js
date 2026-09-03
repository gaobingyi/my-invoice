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
          reject(new Error((res.data && res.data.message) || '微信登录失败 ' + res.statusCode))
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

/** Promise 化 wx.request，自动带 Bearer；401 时清 token 重登并重放一次。 */
function request(options, retried) {
  return ensureToken().then(() => new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: Object.assign({ Authorization: 'Bearer ' + getToken() }, options.header || {}),
      success: res => {
        if (res.statusCode === 401 && !retried) {
          setToken(null)
          request(options, true).then(resolve).catch(reject)
          return
        }
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data)
        else reject(makeError(res))
      },
      fail: () => reject(new Error('网络异常'))
    })
  }))
}

/** 上传发票 PDF。注意 multipart 字段名必须是 file（后端 @RequestParam("file")）。 */
function uploadInvoice(filePath) {
  return ensureToken().then(() => new Promise((resolve, reject) => {
    wx.uploadFile({
      url: BASE_URL + '/api/invoices/upload',
      filePath,
      name: 'file',
      header: { Authorization: 'Bearer ' + getToken() },
      timeout: 120000, // LLM 解析需要数秒~数十秒
      success: res => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try { resolve(JSON.parse(res.data)) } catch (e) { resolve(null) }
        } else reject(makeError(res))
      },
      fail: () => reject(new Error('上传失败，请检查网络'))
    })
  }))
}

/** 下载发票原 PDF（带 header，勿用裸 URL）。返回临时文件路径。 */
function downloadInvoiceFile(id) {
  return ensureToken().then(() => new Promise((resolve, reject) => {
    wx.downloadFile({
      url: BASE_URL + '/api/invoices/' + id + '/file',
      header: { Authorization: 'Bearer ' + getToken() },
      success: res => {
        if (res.statusCode === 200) resolve(res.tempFilePath)
        else reject(new Error('下载失败 ' + res.statusCode))
      },
      fail: () => reject(new Error('下载失败，请检查网络'))
    })
  }))
}

module.exports = { request, uploadInvoice, downloadInvoiceFile, wxLogin, ensureToken, getToken, setToken, BASE_URL }
