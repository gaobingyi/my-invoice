const { ensureToken } = require('./utils/request')

App({
  onLaunch() {
    // 无感登录：openid 白名单绑定 admin，无登录页。失败仅提示（如未绑定/服务不可达），
    // 后续任意请求的 401 重试机制会再尝试登录。
    ensureToken().catch(err => {
      wx.showToast({ title: err.message || '登录失败', icon: 'none', duration: 3000 })
    })
  }
})
