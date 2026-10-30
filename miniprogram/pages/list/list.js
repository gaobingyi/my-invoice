const { request, uploadInvoice } = require('../../utils/request')

const PAGE_SIZE = 20

Page({
  data: {
    invoices: [],
    page: 0,
    totalPages: 0,
    keyword: '',
    used: '', // ''=全部 'false'=未使用 'true'=已使用
    summary: null, // 「未使用」tab 的合计条：{count, totalWithTax}
    loading: false,
    finished: false,
    uploading: false
  },

  onLoad() {
    this.reload()
  },

  onShow() {
    // 从详情页返回时刷新（标记使用/删除后状态可能变化）
    if (this.needRefreshOnShow) {
      this.needRefreshOnShow = false
      this.reload()
    }
  },

  buildParams(page) {
    const params = { page, size: PAGE_SIZE }
    if (this.data.used !== '') params.used = this.data.used
    if (this.data.keyword) params.keyword = this.data.keyword
    return params
  },

  fetchPage(page, append) {
    // 序号守卫：下拉刷新/翻页/搜索并发时，旧响应不再覆盖新列表（追加场景尤其危险——
    // 旧搜索条件的第 2 页会拼进新列表，造成重复项与分页状态回退）
    const seq = (this.reqSeq = (this.reqSeq || 0) + 1)
    this.setData({ loading: true })
    return request({ url: '/api/invoices', data: this.buildParams(page) })
      .then(res => {
        if (seq !== this.reqSeq) return
        // 金额补两位小数展示：BigDecimal 10056.20 序列化成 JSON number 后尾零丢失
        const fmt = v => (v === null || v === undefined || v === '' ? '' : Number(v).toFixed(2))
        const raw = append ? this.data.invoices.concat(res.content) : res.content
        const items = raw.map(it => Object.assign({}, it, { totalWithTaxText: fmt(it.totalWithTax) }))
        this.setData({
          invoices: items,
          page,
          totalPages: res.totalPages,
          finished: page + 1 >= res.totalPages
        })
      })
      .finally(() => { if (seq === this.reqSeq) this.setData({ loading: false }) })
  },

  reload() {
    this.fetchSummary()
    return this.fetchPage(0, false).catch(err =>
      wx.showToast({ title: err.message || '加载失败', icon: 'none' }))
  },

  // 「未使用」tab 的合计条，与列表同一套过滤参数（keyword 变化时随之刷新）；
  // 失败静默——统计是附属信息，不阻断列表加载。
  fetchSummary() {
    if (this.data.used !== 'false') {
      if (this.data.summary) this.setData({ summary: null })
      return
    }
    const data = { used: 'false' }
    if (this.data.keyword) data.keyword = this.data.keyword
    request({ url: '/api/invoices/summary', data })
      // 合计固定两位小数：BigDecimal 10056.20 序列化成 JSON number 后尾零会丢失
      .then(s => this.setData({ summary: { count: s.count, totalWithTax: Number(s.totalWithTax).toFixed(2) } }))
      .catch(() => {})
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.loading || this.data.finished) return
    this.fetchPage(this.data.page + 1, true).catch(err =>
      wx.showToast({ title: err.message || '加载失败', icon: 'none' }))
  },

  onKeywordInput(e) {
    const keyword = e.detail.value
    this.setData({ keyword })
    if (this.searchTimer) clearTimeout(this.searchTimer)
    this.searchTimer = setTimeout(() => this.reload(), 500)
  },

  onUnload() {
    // 离开页面时清掉还在等待的搜索防抖，否则它会在卸载后触发一次浪费的 reload
    if (this.searchTimer) clearTimeout(this.searchTimer)
  },

  onSearch() {
    if (this.searchTimer) clearTimeout(this.searchTimer)
    this.reload()
  },

  setFilter(e) {
    const used = e.currentTarget.dataset.used
    if (used === this.data.used) return
    this.setData({ used })
    this.reload()
  },

  openDetail(e) {
    const id = e.currentTarget.dataset.id
    const invoice = this.data.invoices.find(i => i.id === id)
    this.needRefreshOnShow = true
    wx.navigateTo({
      url: '/pages/detail/detail?id=' + id,
      success: res => res.eventChannel.emit('invoice', invoice)
    })
  },

  chooseAndUpload() {
    if (this.data.uploading) return
    // 微信聊天记录直接选 PDF 发票——小程序入口相对 web 的核心优势
    wx.chooseMessageFile({
      count: 5,
      type: 'file',
      extension: ['pdf'],
      success: res => {
        const pdfs = res.tempFiles.filter(f => /\.pdf$/i.test(f.name))
        if (!pdfs.length) {
          wx.showToast({ title: '请选择 PDF 文件', icon: 'none' })
          return
        }
        this.uploadQueue = pdfs
        this.uploadResults = { ok: 0, duplicate: 0, failed: 0 }
        this.uploadFailedNames = []
        this.uploadNext()
      }
    })
  },

  uploadNext() {
    const file = this.uploadQueue && this.uploadQueue.shift()
    if (!file) {
      this.setData({ uploading: false })
      const r = this.uploadResults
      if (r.failed > 0) {
        // 失败明细用 showModal：toast 与 loading 共用同一原生视图，逐个 toast 会被
        // 下一张的 showLoading 立即覆盖，用户看不到具体哪个文件挂了。整批结束后
        // 一次性列出失败明细，成功/重复数进 modal 标题。
        wx.showModal({
          title: '成功 ' + r.ok + ' · 重复 ' + r.duplicate + ' · 失败 ' + r.failed,
          content: this.uploadFailedNames.join('\n'),
          showCancel: false
        })
      } else {
        wx.showToast({ title: '成功 ' + r.ok + ' 张', icon: r.ok ? 'success' : 'none', duration: 3000 })
      }
      if (r.ok) this.reload()
      return
    }
    this.setData({ uploading: true })
    wx.showLoading({ title: '解析中…', mask: true })
    uploadInvoice(file.path)
      .then(() => { this.uploadResults.ok++ })
      .catch(err => {
        if (err.statusCode === 409) this.uploadResults.duplicate++
        else {
          this.uploadResults.failed++
          this.uploadFailedNames.push(file.name + '：' + (err.message || '上传失败'))
        }
      })
      .finally(() => {
        // 先 hideLoading 再处理下一张：下一张的 showLoading 不会覆盖本张可能的 toast
        wx.hideLoading()
        this.uploadNext()
      })
  }
})
