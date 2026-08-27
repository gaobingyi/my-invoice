// E2E interactive test: drives real Chrome (on Xvfb virtual display) against the
// running stack (vite 5173 → backend 8080). Asserts the full invoice lifecycle.
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 后端已加 JWT 认证：API 直调需先登录拿 token。
let token = null
async function login() {
  const res = await fetch('http://localhost:5173/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  })
  if (!res.ok) throw new Error(`login -> ${res.status}`)
  token = (await res.json()).token
  console.log('  login OK (token length ' + token.length + ')')
  // 更强断言：登录拿到的 token 必须能访问受保护端点
  const authRes = await fetch('http://localhost:5173/api/invoices?page=0&size=1', {
    headers: { Authorization: `Bearer ${token}` }
  })
  if (!authRes.ok) throw new Error(`login token invalid -> ${authRes.status} ${await authRes.text()}`)
}

function authHeaders() {
  return { Authorization: `Bearer ${token}` }
}

// Reset DB to a clean slate before the test: delete every invoice via the API so
// the sample PDF (fixed invoice number) can be uploaded without a 409 duplicate.
async function resetInvoices() {
  const api = 'http://localhost:5173/api/invoices'
  for (;;) {
    const res = await fetch(`${api}?page=0&size=20`, { headers: authHeaders() })
    const { content } = await res.json()
    if (!content.length) break
    for (const r of content) {
      const d = await fetch(`${api}/${r.id}`, { method: 'DELETE', headers: authHeaders() })
      if (!d.ok) throw new Error(`reset: delete ${r.id} -> ${d.status}`)
    }
  }
  console.log('  DB reset (0 invoices)')
}

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = path.join(__dirname, 'screenshots')
fs.mkdirSync(SHOT_DIR, { recursive: true })

const BASE = 'http://localhost:5173'
const CHROME = '/usr/bin/google-chrome-stable'
// 样例发票位于仓库根 invoice_examples/，用 __dirname 相对定位，避免依赖机器/账号路径
const INV_EX = path.join(__dirname, '../../invoice_examples')
const SAMPLE = path.join(INV_EX, '_餐饮服务_餐饮服务_-2026年05月26日-201.00.pdf')
const SAMPLE2 = path.join(INV_EX, '260331_178.00_深圳市美之高实业发展有限公司.pdf')
const INVOICE_NUMBER = '26322000004144614676'

let pass = 0, fail = 0
function check(name, cond) {
  if (cond) { pass++; console.log(`  ✅ ${name}`) }
  else { fail++; console.log(`  ❌ ${name}`) }
}

await login()
await resetInvoices()
console.log('0) 清理数据库')

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: false,            // headed on Xvfb: real browser behaviour
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1400,900']
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1400, height: 900 })
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  page.on('requestfailed', r => errors.push(`requestfailed: ${r.url()}`))
  page.on('console', m => {
    if (m.type() !== 'error') return
    const txt = m.text()
    // favicon + aborted navigation noise; keep everything else
    if (txt.includes('favicon') || txt.includes('Failed to load resource')) {
      const req = m.location()
      errors.push(`console-error: ${txt} @${req.url}`)
    } else {
      errors.push(txt)
    }
  })

  // --- 0. auth guard: 未登录访问 / 跳 /login ---
  console.log('\n0) 登录守卫')
  await page.goto(BASE, { waitUntil: 'networkidle0', timeout: 20000 })
  check('未登录跳转 /login', page.url().includes('/login'))
  await page.screenshot({ path: path.join(SHOT_DIR, '0-login.png') })

  // 注入 token 后重新加载，应进入主界面（上传视图）
  await page.evaluate((t, u) => {
    localStorage.setItem('token', t)
    localStorage.setItem('username', u)
    location.reload()
  }, token, 'admin')
  await page.waitForSelector('input[type=file]', { timeout: 10000 })
  check('登录后进入上传视图', page.url().endsWith('/upload') || page.url().endsWith('/'))
  await page.screenshot({ path: path.join(SHOT_DIR, '0-logged-in.png') })

  // --- 1. load ---
  console.log('\n1) 加载页面')
  await page.goto(BASE + '/upload', { waitUntil: 'networkidle0', timeout: 20000 })
  // 默认是上传视图；在 upload 视图找到 file input 即视为就绪
  await page.waitForSelector('input[type=file]', { timeout: 10000 })
  check('上传视图就绪', true)
  await page.screenshot({ path: path.join(SHOT_DIR, '1-upload.png') })

  // --- 2. upload (two invoices consecutively) ---
  console.log('\n2) 连续上传发票')
  const rowsBefore = await page.$$eval('tbody tr', trs => trs.length)
  const samples = [SAMPLE, SAMPLE2]
  // select BOTH files at once, then upload in one click (batch upload)
  const input = await page.$('input[type=file]')
  if (!input) throw new Error('file input not found')
  await input.uploadFile(...samples)
  await new Promise(r => setTimeout(r, 400))   // let on-change fire for both
  const btnText = await page.$eval('.upload-btn', b => b.textContent).catch(() => '')
  check(`批量选择后按钮显示数量 (${btnText.trim()})`, btnText.includes('2'))
  await page.click('.upload-btn:not(.is-disabled)')
  // 上传成功 emit('uploaded')，App 自动跳列表
  await page.waitForSelector('table', { timeout: 15000 })
  await page.waitForFunction(
    () => document.querySelectorAll('tbody tr').length >= 2,
    { timeout: 15000 }
  )
  const rowsAfter = await page.$$eval('tbody tr', trs => trs.length)
  check(`批量上传后行数 ${rowsBefore} → ${rowsAfter}`, rowsAfter >= rowsBefore + 2)
  // verify both sellers rendered
  const sellerNames = await page.$$eval('tbody tr', trs => trs.map(tr => tr.innerText))
  check('含餐饮发票', sellerNames.some(t => t.includes('扬州滋奇奥邦餐饮管理有限公司')))
  check('含美之高发票', sellerNames.some(t => t.includes('深圳市美之高实业发展有限公司')))
  await page.screenshot({ path: path.join(SHOT_DIR, '2-after-upload.png') })

  // --- 3. preview ---
  console.log('\n3) 预览')
  // wait for the "上传成功" toast to clear so it can't swallow the row's clicks
  await page.waitForFunction(
    () => !document.querySelector('.el-message'),
    { timeout: 5000 }
  ).catch(() => {})
  // first row's 预览 button
  const row = (await page.$$('tbody tr'))[0]
  const cellBtns = await row.$$('button')
  const texts = await Promise.all(cellBtns.map(b => b.evaluate(el => el.textContent)))
  // buttons: 预览, 下载, 删除
  const previewBtn = cellBtns[texts.findIndex(t => t.includes('预览'))]
  if (!previewBtn) throw new Error('preview button not found; texts=' + JSON.stringify(texts))
  await previewBtn.click()
  await page.waitForSelector('.el-dialog', { timeout: 5000 })
  await new Promise(r => setTimeout(r, 1500))
  const hasIframe = await page.$eval('.el-dialog iframe', f => !!f).catch(() => false)
  check('预览对话框含 iframe', hasIframe)
  await page.screenshot({ path: path.join(SHOT_DIR, '3-preview.png') })
  // close dialog
  await page.click('.el-dialog__headerbtn')
  await new Promise(r => setTimeout(r, 500))

  // --- 4. download ---
  console.log('\n4) 下载')
  // download flows fire Page.downloadWillBegin — assert an attachment download starts
  const cdp = await page.createCDPSession()
  let downloaded = null
  cdp.on('Page.downloadWillBegin', e => { downloaded = { url: e.url, filename: e.suggestedFilename } })
  await cdp.send('Page.enable')
  await page.waitForFunction(
    () => !document.querySelector('.el-message'),
    { timeout: 5000 }
  ).catch(() => {})
  const row2 = (await page.$$('tbody tr'))[0]
  const btns2 = await row2.$$('button')
  const texts2 = await Promise.all(btns2.map(b => b.evaluate(el => el.textContent)))
  const downloadBtn = btns2[texts2.findIndex(t => t.includes('下载'))]
  if (!downloadBtn) throw new Error('download button not found')
  await downloadBtn.click()
  await new Promise(r => setTimeout(r, 1500))
  check(
    `下载触发 attachment (${downloaded ? downloaded.filename : 'none'})`,
    !!downloaded
  )
  await page.screenshot({ path: path.join(SHOT_DIR, '4-download.png') })

  // --- 5. delete ---
  console.log('\n5) 删除')
  const countBefore = await page.$$eval('tbody tr', trs => trs.length)
  const row3 = (await page.$$('tbody tr'))[0]
  const btns3 = await row3.$$('button')
  const texts3 = await Promise.all(btns3.map(b => b.evaluate(el => el.textContent)))
  const delBtn = btns3[texts3.findIndex(t => t.includes('删除'))]
  if (!delBtn) throw new Error('delete button not found')
  await delBtn.click()
  await page.waitForSelector('.el-message-box', { timeout: 5000 })
  await page.screenshot({ path: path.join(SHOT_DIR, '5-confirm.png') })
  // click confirm (删除 in messagebox footer)
  const confirm = await page.$('.el-message-box__btns .el-button--danger, .el-message-box__btns button:last-child')
  await confirm.click()
  await page.waitForFunction(
    (n) => document.querySelectorAll('tbody tr').length < n,
    { timeout: 10000 }, countBefore
  )
  const countAfter = await page.$$eval('tbody tr', trs => trs.length)
  check(`删除后行数 ${countBefore} → ${countAfter}`, countAfter < countBefore)
  await page.screenshot({ path: path.join(SHOT_DIR, '6-after-delete.png') })

  // --- 6. no page errors ---
  console.log('\n6) 控制台错误')
  check(`无 JS 错误 (${errors.length})`, errors.length === 0)
  if (errors.length) console.log('  errors:', errors.slice(0, 5).join(' | '))

  // --- 7. cleanup: leave the DB empty ---
  console.log('\n7) 清理数据')
  await resetInvoices()
  const remaining = (await (await fetch('http://localhost:5173/api/invoices?page=0&size=20', { headers: authHeaders() })).json()).totalElements
  check(`DB 归零 (剩余 ${remaining})`, remaining === 0)

} finally {
  await browser.close()
}

console.log(`\n=== PASS ${pass} / FAIL ${fail} ===`)
process.exit(fail ? 1 : 0)
