// 只读 DOM 功能验证：不写任何数据，只断言移动端交互
import puppeteer from 'puppeteer-core'

const browser = await puppeteer.launch({
  executablePath: '/usr/bin/google-chrome-stable',
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu']
})
const page = await browser.newPage()
await page.setViewport({ width: 375, height: 812, isMobile: true, hasTouch: true })

await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle2' })
await page.type('input[placeholder="用户名"]', 'admin')
await page.type('input[placeholder="密码"]', 'admin123')
await Promise.all([
  page.waitForNavigation({ waitUntil: 'networkidle2' }).catch(() => {}),
  page.click('.login-btn')
])
await new Promise(r => setTimeout(r, 1000))

let pass = 0, fail = 0
const check = (name, cond) => { cond ? pass++ : fail++; console.log((cond ? '  ✅' : '  ❌') + ' ' + name) }

// 顶栏汉堡：存在、可见、位于左上
const hb = await page.$eval('.mobile-menu-btn', el => {
  const r = el.getBoundingClientRect()
  return { x: r.x, y: r.y, w: r.width, h: r.height, svg: !!el.querySelector('svg') }
})
check('汉堡按钮可见且带图标', hb.w > 0 && hb.h > 0 && hb.svg)
check('汉堡位于顶栏左侧', hb.x >= 0 && hb.x < 60 && hb.y >= 0 && hb.y < 60)

// 发票列表：开「已使用」开关 → 卡片出现
await page.goto('http://localhost:5173/list', { waitUntil: 'networkidle2' })
await new Promise(r => setTimeout(r, 800))
check('移动端不渲染 el-table', !(await page.$('.el-table')))
await page.click('.list-title .el-switch')
await new Promise(r => setTimeout(r, 1200))
const cards = await page.$$eval('.inv-card', els => els.map(el => ({
  seller: el.querySelector('.inv-card-seller')?.textContent.trim(),
  amount: el.querySelector('.inv-card-amount')?.textContent.trim(),
  hasCheckbox: !!el.querySelector('.el-checkbox'),
  actions: [...el.querySelectorAll('.inv-card-actions .el-button')].map(b => b.textContent.trim())
})))
check('开「已使用」后卡片数 > 0', cards.length > 0)
check('卡片含销售方/金额/勾选框/三个操作', cards.every(c => c.seller && c.amount && c.hasCheckbox && c.actions.join(',') === '预览,下载,删除'))
console.log('  cards:', JSON.stringify(cards[0]))

// 勾选：已使用的票 checkbox 必须禁用（库里当前唯一发票是 used，正好验证该逻辑）
const cbDisabled = await page.$eval('.inv-card .el-checkbox', el => el.classList.contains('is-disabled'))
check('已使用发票的勾选框禁用', cbDisabled)

// 抽屉：打开 → 菜单导航 → 自动关闭
await page.click('.mobile-menu-btn')
await new Promise(r => setTimeout(r, 600))
const menuItems = await page.$$eval('.drawer-menu .el-menu-item', els => els.map(e => e.textContent.trim()))
check('抽屉菜单 4 项', menuItems.length === 4)
await page.click('.drawer-menu .el-menu-item:nth-child(4)') // 解析指标
await new Promise(r => setTimeout(r, 1200))
check('菜单点击后跳转 /metrics', page.url().includes('/metrics'))
check('菜单点击后抽屉关闭', !(await page.$('.el-drawer:not([style*="display: none"]) .drawer-menu')) ||
  await page.$eval('.el-overlay', el => getComputedStyle(el).display === 'none').catch(() => true))

// 预览按钮触发 window.open（拦截弹窗，验证调用而非真开标签）
await page.goto('http://localhost:5173/list', { waitUntil: 'networkidle2' })
await page.click('.list-title .el-switch')
await new Promise(r => setTimeout(r, 1200))
await page.evaluate(() => { window.__opened = null; window.open = u => { window.__opened = String(u) } })
await page.click('.inv-card .inv-card-actions .el-button') // 预览
await new Promise(r => setTimeout(r, 1500))
const opened = await page.evaluate(() => window.__opened)
check('移动端预览走 window.open(blob:)', !!opened && opened.startsWith('blob:'))

// 弹窗宽度：导出对话框
console.log(`\n${pass} passed, ${fail} failed`)
await browser.close()
process.exit(fail ? 1 : 0)
