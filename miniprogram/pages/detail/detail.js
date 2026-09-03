const { request, downloadInvoiceFile } = require('../../utils/request')

Page({
  data: {
    invoice: null,
    previewing: false,
    deleting: false
  },

  onLoad() {
    // 列表页通过 eventChannel 传入 invoice 对象（后端无 GET /api/invoices/{id} 端点）
    const channel = this.getOpenerEventChannel()
    channel.on('invoice', invoice => {
      const f = v => (v === null || v === undefined || v === '' ? '—' : v)
      this.setData({
        invoice,
        fields: [
          { label: '销售方', value: f(invoice.sellerName) },
          { label: '销售方税号', value: f(invoice.sellerTaxId) },
          { label: '购买方', value: f(invoice.buyerName) },
          { label: '购买方税号', value: f(invoice.buyerTaxId) },
          { label: '发票号码', value: f(invoice.invoiceNumber) },
          { label: '开票日期', value: f(invoice.invoiceDate) },
          { label: '金额', value: f(invoice.totalAmount) },
          { label: '税额', value: f(invoice.taxAmount) },
          { label: '价税合计', value: f(invoice.totalWithTax) },
          { label: '分类', value: f(invoice.category) },
          { label: '使用状态', value: invoice.used ? '已使用' : '未使用' }
        ]
      })
      wx.setNavigationBarTitle({ title: invoice.sellerName || '发票详情' })
    })
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
