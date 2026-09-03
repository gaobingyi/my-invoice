const { request, uploadInvoice } = require('../../utils/request')

const PAGE_SIZE = 20

Page({
  data: {
    invoices: [],
    page: 0,
    totalPages: 0,
    keyword: '',
    used: '', // ''=全部 'false'=未使用 'true'=已使用
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
    this.setData({ loading: true })
    return request({ url: '/api/invoices', data: this.buildParams(page) })
      .then(res => {
        const items = append ? this.data.invoices.concat(res.content) : res.content
        this.setData({
          invoices: items,
          page,
          totalPages: res.totalPages,
          finished: page + 1 >= res.totalPages
        })
      })
      .finally(() => this.setData({ loading: false }))
  },

  reload() {
    return this.fetchPage(0, false).catch(err =>
      wx.showToast({ title: err.message || '加载失败', icon: 'none' }))
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
        this.uploadNext()
      }
    })
  },

  uploadNext() {
    const file = this.uploadQueue && this.uploadQueue.shift()
    if (!file) {
      this.setData({ uploading: false })
      const r = this.uploadResults
      wx.showToast({ title: '成功' + r.ok + '，重复' + r.duplicate + '，失败' + r.failed, icon: r.ok ? 'success' : 'none', duration: 3000 })
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
          wx.showToast({ title: file.name + '：' + (err.message || '上传失败'), icon: 'none', duration: 3000 })
        }
      })
      .finally(() => {
        wx.hideLoading()
        this.uploadNext()
      })
  }
})
