const { request, downloadInvoiceFile } = require('../../utils/request')

// BigDecimal 10056.20 经 JSON number 序列化丢尾零 → 显示 10056.2；统一两位小数
function fmtMoney(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return isNaN(n) ? '—' : n.toFixed(2)
}

Page({
  data: {
    invoice: null,
    loading: true,
    loadError: false,
    previewing: false,
    deleting: false
  },

  onLoad(query) {
    // 优先用 eventChannel 传入的 invoice（首屏快、免一次往返）；
    // 不可用时（分享卡片直达、小程序重启直达等无 opener 场景）按 id 走新端点兜底。
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
    if (channel && typeof channel.on === 'function') {
      channel.on('invoice', invoice => this.applyInvoice(invoice))
    }
    if (query && query.id) {
      request({ url: '/api/invoices/' + query.id })
        .then(invoice => {
          // eventChannel 已先到则不覆盖（同一条数据覆盖也无害，但省一次 setData）
          if (!this.data.invoice) this.applyInvoice(invoice)
        })
        .catch(() => {
          if (!this.data.invoice) this.setData({ loading: false, loadError: true })
        })
        .finally(() => {
          if (!this.data.invoice) this.setData({ loading: false })
        })
    } else if (!channel || typeof channel.on !== 'function') {
      // 无 id 且无 eventChannel：没有数据来源
      this.setData({ loading: false, loadError: true })
    }
  },

  applyInvoice(invoice) {
    const f = v => (v === null || v === undefined || v === '' ? '—' : v)
    this.setData({
      invoice,
      loading: false,
      loadError: false,
      fields: [
        { label: '销售方', value: f(invoice.sellerName) },
        { label: '销售方税号', value: f(invoice.sellerTaxId) },
        { label: '购买方', value: f(invoice.buyerName) },
        { label: '购买方税号', value: f(invoice.buyerTaxId) },
        { label: '发票号码', value: f(invoice.invoiceNumber) },
        { label: '开票日期', value: f(invoice.invoiceDate) },
        { label: '金额', value: fmtMoney(invoice.totalAmount) },
        { label: '税额', value: fmtMoney(invoice.taxAmount) },
        { label: '价税合计', value: fmtMoney(invoice.totalWithTax) },
        { label: '分类', value: f(invoice.category) },
        { label: '使用状态', value: invoice.used ? '已使用' : '未使用' }
      ]
    })
    wx.setNavigationBarTitle({ title: invoice.sellerName || '发票详情' })
  },

  previewPdf() {
    if (this.data.previewing || !this.data.invoice) return
    this.setData({ previewing: true })
    wx.showLoading({ title: '加载中…', mask: true })
    downloadInvoiceFile(this.data.invoice.id)
      .then(filePath => new Promise((resolve, reject) => {
        wx.openDocument({
          filePath,
          fileType: 'pdf',
          showMenu: true, // 允许转发/保存原文件
          success: resolve,
          fail: () => reject(new Error('打开 PDF 失败'))
        })
      }))
      .catch(err => wx.showToast({ title: err.message || '预览失败', icon: 'none' }))
      .finally(() => {
        wx.hideLoading()
        this.setData({ previewing: false })
      })
  },

  confirmDelete() {
    if (this.data.deleting || !this.data.invoice) return
    wx.showModal({
      title: '删除发票',
      content: '将同时删除 PDF 原文件，确定删除？',
      confirmColor: '#d83931',
      success: res => {
        if (res.confirm) this.doDelete()
      }
    })
  },

  doDelete() {
    this.setData({ deleting: true })
    request({ url: '/api/invoices/' + this.data.invoice.id, method: 'DELETE' })
      .then(() => {
        wx.showToast({ title: '已删除', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 600)
      })
      .catch(err => wx.showToast({ title: err.message || '删除失败', icon: 'none' }))
      .finally(() => this.setData({ deleting: false }))
  }
})
