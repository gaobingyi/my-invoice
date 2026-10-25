// 列表页逻辑测试：Node 直接加载 pages/list/list.js，wx 全局打桩，不依赖微信开发者工具。
// 覆盖「未使用」合计条的数据流：何时请求、keyword 联动、失败静默、切 tab 清理。
// 运行：node miniprogram/test/list-page.test.js（无外部依赖）。

const calls = []        // 每次 wx.request 的记录（断言参数用）
const toasts = []
let summaryQueue = []   // /api/invoices/summary 的可编程响应队列；空则用默认成功值

global.wx = {
  getStorageSync: key => (key === 'token' ? 'test-token' : ''),
  setStorageSync: () => {},
  removeStorageSync: () => {},
  showToast: t => toasts.push(t.title),
  showLoading: () => {},
  hideLoading: () => {},
  stopPullDownRefresh: () => {},
  navigateTo: () => {},
  request(opts) {
    calls.push(opts)
    setTimeout(() => {
      if (opts.url.endsWith('/api/invoices/summary')) {
        const r = summaryQueue.length ? summaryQueue.shift()
            : { statusCode: 200, data: { count: 9, totalWithTax: 10056.20 } }
        opts.success({ statusCode: r.statusCode, data: r.data })
        return
      }
      if (opts.url.includes('/api/invoices')) {
        opts.success({ statusCode: 200, data: {
          content: [{ id: 1, sellerName: '测试销售方', totalWithTax: 100 }],
          totalPages: 1, totalElements: 1
        } })
        return
      }
      if (opts.fail) opts.fail()
    }, 0)
  }
}

let pageConfig
global.Page = config => { pageConfig = config }
require('../pages/list/list.js')

// 实例化页面：data 深拷贝隔离 + setData 合并语义（近似小程序运行时，方法内 this 指向 page）
const page = Object.assign({}, pageConfig)
page.data = JSON.parse(JSON.stringify(pageConfig.data))
page.setData = function (patch) { Object.assign(this.data, patch) }

const sleep = ms => new Promise(r => setTimeout(r, ms))
const tick = () => sleep(10)
const summaryCalls = () => calls.filter(c => c.url.endsWith('/api/invoices/summary'))
const listCalls = () => calls.filter(c => c.url.includes('/api/invoices') && !c.url.endsWith('/summary'))

let passed = 0, failed = 0
function check(name, cond) {
  if (cond) { passed++; console.log('  ✓ ' + name) }
  else { failed++; console.log('  ✗ ' + name) }
}

;(async () => {
  console.log('场景 1：默认「全部」tab 不出合计条')
  page.onLoad()
  await tick()
  check('加载列表（GET /api/invoices）', listCalls().length === 1)
  check('不请求 summary（非未使用 tab）', summaryCalls().length === 0)
  check('summary 数据为 null（合计条不渲染）', page.data.summary === null)

  console.log('场景 2：切到「未使用」出合计条')
  page.setFilter({ currentTarget: { dataset: { used: 'false' } } })
  await tick()
  const sc = summaryCalls()[0]
  check('请求 summary 且带 used=false', !!sc && sc.data.used === 'false')
  check('合计数据就位（两位小数字符串）', !!page.data.summary && page.data.summary.count === 9
      && page.data.summary.totalWithTax === '10056.20')

  console.log('场景 3：搜索关键词联动（500ms 防抖后带 keyword 请求）')
  page.onKeywordInput({ detail: { value: '马集' } })
  await sleep(600)
  const lastSummary = summaryCalls()[summaryCalls().length - 1]
  const lastList = listCalls()[listCalls().length - 1]
  check('summary 请求带 keyword', !!lastSummary && lastSummary.data.keyword === '马集')
  check('列表请求同步带 keyword', !!lastList && lastList.data.keyword === '马集')

  console.log('场景 4：切回「全部」清空合计条')
  page.setFilter({ currentTarget: { dataset: { used: '' } } })
  await tick()
  const before = summaryCalls().length
  check('summary 数据被清空', page.data.summary === null)
  await tick()
  check('不再发 summary 请求', summaryCalls().length === before)

  console.log('场景 5：summary 请求失败静默（不阻断列表、不弹 toast）')
  summaryQueue = [{ statusCode: 500, data: { message: 'boom' } }]
  page.setFilter({ currentTarget: { dataset: { used: 'false' } } })
  await tick()
  check('列表照常加载', listCalls().length >= 3)
  check('失败静默：summary 保持 null 且无 toast', page.data.summary === null && toasts.length === 0)

  console.log('')
  console.log('通过 ' + passed + '，失败 ' + failed)
  process.exitCode = failed ? 1 : 0
})()
