# AGENTS.md

发票管理系统（前后端分离）。上传 PDF → 解析字段 → SQLite → 列表/预览/下载/删除，另有导出批次（`ExportController` `/api/export-batches` + `web/src/views/ExportBatchList.vue`）。详细参考 `CLAUDE.md`，本文只列容易踩坑的高信号事实。

## 布局与命令

- `server/`：Java 25 + Spring Boot 3.4.5 + JPA + SQLite（Xerial JDBC）+ PDFBox 3.0.2。包 `com.example.invoice`。
- `web/`：Vue 3 + Vite 6 + Element Plus + Axios。无 lint/typecheck 脚本。
- 根 `pom.xml` 只是聚合 POM（无父依赖管理），让 IDEA 识别 `server` 模块；构建始终进 `server/` 目录执行。

```bash
cd server && mvn test                          # 单测，无需 DB/LLM
cd server && mvn test -Dtest=InvoiceParserTest  # 单类（另有 JwtTokenServiceTest、SecurityConfigTest）
cd server && mvn spring-boot:run               # 8080，首次启动自动建 ./data/invoice.db
cd web && npm run dev                          # 5173，vite proxy /api → 8080
node web/e2e/run.mjs                           # E2E：需后端+前端+Xvfb+google-chrome-stable；无 Xvfb 机器用 E2E_HEADLESS=1
docker compose up -d --build                   # 两服务全镜像化（backend + nginx），对外 8088（HTTPS）
```

## 环境与 .env（双文件，勿混）

- **根 `.env`**（gitignored，模板 `.env.example`）：docker compose 变量源。`JWT_SECRET`、`ADMIN_PASSWORD` 均为 `:?` **必填**，缺任一 compose 直接拒绝启动。还含 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`。SQLite 是文件式，无 DB 凭据（旧 `DB_PASSWORD` / `MYSQL_ROOT_PASSWORD` 已移除）。
- SQLite 连接（`./data/invoice.db`，WAL 模式）与 LLM 默认配置在 `server/src/main/resources/application.yml`；`LLM_API_KEY` 在 yml 也有 dev default（个人 dev key），本地不起后端可不 source。容器内由 compose 环境变量覆盖（DB `/app/data/invoice.db`、LLM 走 `LLM_BASE_URL`）。

## 解析架构（核心）

`InvoiceParser.parse()` 三条路径，改这里必读 `CLAUDE.md`「解析架构」：

1. **正则快速路径**：PDFBox 会把这类 PDF 重排 —— 标签聚顶、**值按文档顺序在底部流出**，因此按值 pattern + 位置顺序提取，不锚定邻近标签。金额 3 个 ¥ 值：`max=价税合计`、`min=税额`、`middle=金额`。号码是 `\b\d{20}\b`（必须整体 20 位，宽松的 `\d{20}` 会从粘连的信用代码串里截错）。
2. **字形坐标路径** `InvoiceParser.Layout`：同一次 PDFBox 加载顺带收字形坐标，**只覆盖购/销方名称与代码**，取不到就放弃、保留正则结果。专治「购/销两列压进同一物理行」的版式（京东票：两列名称粘成一个 writeString、两列代码粘成另一个且无分隔符，文本顺序已丢失列归属，只有 x 坐标能还原）。按 y 分行、行内按 x 排序，连续 `[0-9A-Z]` 按间距 >1.6pt 切词，取最上面一对代码（左=购右=销），名称逐行上溯最近的**非标签**中文串（`NOT_NAME` 黑名单滤掉页眉与大写金额行）。竖排单字标签（购/买/销/售/方/信/息）须在 `writeString` 阶段丢弃；`getUnicode()` 可能是空串，取字符前判长度。
3. **LLM 兜底** `InvoiceLlmExtractor.fill()`：只回填缺失字段，失败不报错（null 保留、上传继续）。三个坑：body 必须 `String.getBytes(UTF_8)`（RestClient 默认 ISO-8859-1 损坏中文→400）；取 `resp.lastIndexOf('}')` 前内容剥离 SSE framing；发送前剥 `\p{Cntrl}`。

测试 fixture（`server/src/test/resources/*.pdf`）是真实 PDF 布局，测试用 `new InvoiceParser(null, null)`（null=不开 LLM，null=不记指标）。新增版式：加 fixture + 断言，能正则则正则，否则靠 LLM。京东样例不入库 —— `parsesColumnsGluedOntoOneLine` 从仓库根 `invoice_examples/` 读，文件缺失时 `assumeTrue` **跳过而非失败**（新克隆/CI 会静默跳过、覆盖为零）。

个体户销售方坑：销方名称不限定「公司」结尾（…商店/中心/厂），匹配不上 NAME 时备注里「销方开户银行:…公司」会顶替真实销售方 —— 名称与其统一社会信用代码**按位置配对**（信用代码 18 位、可能纯数字），配不上再退回旧 NAME 列表逻辑。

标签紧贴值版式坑（纵排 购/买/方/信/息，如山姆票）：`名称:某某公司` 与 `统一社会信用代码/纳税人识别号:91…` 直接相邻，代码值前最近的中文串是列标签本身 —— `nameBefore` 按 `isLabelRun` 跳过「纳税人识别号/统一社会信用代码」等标签串再取上一个；否则购销方名称会变成「纳税人识别号」（LLM 不兜底：字段非 null）。

两列同排版式坑（京东票）：名称尾部会粘连标签残字（「…有限公司名」），需 `trimLabelTail()` 去掉。

## 数据模型与上传

- schema 由 `server/ddl/schema.sql` 管理（与 `server/src/main/resources/schema.sql` 内容一致），JPA `ddl-auto: none`（SQLite 类型亲和非严格）—— **改实体必须同步改两份 schema.sql**。
- `invoice.invoice_number` UNIQUE：重复上传 → 409。`app_user` 为单管理员 BCrypt 表，启动无用户时 seed（`ADMIN_USERNAME` / `ADMIN_PASSWORD` 覆盖，默认 `admin/admin123`）。
- 上传：临时 UUID 落盘 → 解析 → `Files.move` 重命名 `<销售方>_<号码>`（**无 REPLACE_EXISTING**，目标存在=重复→409）。解析失败删临时文件；无号码存 `UNKNOWN-<12位hex>` 哨兵（必须 ≤ VARCHAR(20)）。

## 认证与前端

- 仅 `/api/auth/**` 公开（含 healthcheck `GET /api/auth/ping`），其余 `/api/**` 一律 401，直调需 `Authorization: Bearer`。登录有内存限流（`LoginRateLimiter`）。
- **文件端点带不了 header**：前端预览/下载必须用 `fetchFile()`（axios `responseType:'blob'`→object URL），勿退回裸 URL。
- `App.vue` 是 Layout（无 activeMenu 状态机）。`web/src/views/InvoiceList.vue` 的 `.upload-btn` 及 Element Plus DOM 结构是 `web/e2e/run.mjs` 断言依赖，改前端勿破坏。
- PDF 预览双路径（`InvoiceList.vue` / `ExportBatchList.vue` 同构）：桌面 iframe 走浏览器原生查看器；移动端用 `vue-pdf-embed` 内嵌渲染（移动浏览器 iframe 不渲染 PDF），该组件 `defineAsyncComponent` 拆独立 chunk，勿改成静态 import。

## 部署坑

- nginx 对外 **8088 HTTPS**（`certs/origin.pem` + Cloudflare IP 白名单 `web/cf-allow.conf`），改端口/证书注意 compose 卷挂载。
- 服务器端 curl 测中文上传失败先怀疑编码，不要怀疑 LLM。

## 微信小程序（`miniprogram/`，体验版）

- 仅做**体验版**（个人用，加体验成员扫码打开）：体验版开调试模式可绕过合法域名校验，`dpdns.org` 免费域名**永远无法 ICP 备案**，上不了正式版；正式版才需要备案域名 + 443。
- 登录无感：`app.js` onLaunch 静默 `wx.login()` code → `POST /api/auth/wx-login`（`WxLoginService`，code2session）→ openid 白名单（`WX_ALLOWED_OPENIDS`）命中即签 admin JWT。**白名单为空 = 拒绝所有**；首次绑定：留空登录一次，后端日志打出 openid，填入 .env 重启。绝不能自动建号（任何打开小程序的用户都能拿到合法 openid）。
- `miniprogram/utils/request.js`：401 自动清 token 重登并重放一次；上传 multipart 字段名必须 `file`；下载 PDF 走 `downloadInvoiceFile()`（带 header）→ `wx.openDocument` 原生预览（web 端 pdf.js 双路径在端内不需要）。
- 详情页数据经 eventChannel 从列表页传入（后端无 `GET /api/invoices/{id}`）。
- `project.config.json` 的 `urlCheck: false` 即「不校验合法域名」，本地联调可指 `http://localhost:8080`；上线前把 `appid` 从 `touristappid` 换成真实 AppID。
