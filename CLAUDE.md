# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

发票管理系统（前后端分离）。上传 PDF 发票 → 解析字段 → 存 SQLite → 列表/预览/下载/删除，另有月度选票打包导出（导出批次）与解析指标看板。业务起点见 `Requirements.md`（原始需求，DB 后来由 MySQL 改为 SQLite），另有微信小程序体验版。

- 后端 `server/`：Java 25 + Spring Boot 3.4.5 + Spring Data JPA + SQLite（Xerial JDBC）+ PDFBox 3.0.2 + POI 5.3.0（导出 Excel）。包 `com.example.invoice`。
- 前端 `web/`：Vue 3 + Vite 6 + Element Plus + Axios（无 lint/typecheck 脚本）。
- 小程序 `miniprogram/`：原生小程序，仅体验版。
- 根 `pom.xml` 是聚合 POM（`<modules><module>server</module></modules>`），**无父依赖管理**，只为 IDEA 打开根目录时识别 `server` 为 Maven 模块。构建始终进 `server/` 执行。
- `AGENTS.md` 是本文件的精简版（只列易踩坑的高信号事实），两者有重叠；改架构时注意同步。

## 常用命令

```bash
# 一键启停本地开发（后端 8080 + 前端 5173）
./scripts/dev.sh start / stop / restart / status / logs

# 清空数据（自动检测本地/Docker 模式，确认后执行）
./scripts/clean.sh

# 后端测试（解析器/服务单测，无需 DB/LLM）
cd server && mvn test

# 单测单类（另有 ExportServiceTest、ExportZipWriterTest、InvoiceExcelGeneratorTest、
# InvoiceServiceTest、ParsingMetricsServiceTest、JwtTokenServiceTest、
# SecurityConfigTest、WxLoginServiceTest）
cd server && mvn test -Dtest=InvoiceParserTest

# 启动后端（首次启动自动建 ./data/invoice.db，上传目录 ./uploads）
cd server && mvn spring-boot:run

# 前端
cd web && npm run dev                   # 5173，vite proxy /api → 8080

# 浏览器端到端测试（需先起后端 + 前端 + Xvfb + google-chrome-stable）
node web/e2e/run.mjs                    # 12 项断言，覆盖发票生命周期 + 导出批次
node web/e2e/mobile-dom-check.mjs       # 移动端只读 DOM 断言（10 项，不写数据）
E2E_HEADLESS=1 node web/e2e/run.mjs     # 无 Xvfb 的机器

# Docker 部署（两服务：backend + nginx，全镜像化）
docker compose up -d --build            # 构建+启动，对外 8088 HTTPS
docker compose logs -f backend
docker compose ps                       # 看 healthy 状态
docker compose down -v                  # 停止+清卷

# 异地备份（VPS 上跑）：每日 3 点把 DB+uploads 打成单包传 MEGA
bash scripts/backup-mega.sh             # 见 DEPLOY.md §7 / docs/deploy-steps.md
```

### 环境前置

- SQLite 是文件式数据库，**首次启动自动建表**（`spring.sql.init.mode: always` 触发 `server/src/main/resources/schema.sql`）。开发期 DB 落 `server/data/invoice.db`，容器内 `/app/data/invoice.db`（命名卷 `backend-db`）。
- JDBC URL 必须带 `date_class=TEXT`：Xerial 默认按 INTEGER（epoch ms）存 timestamp，与读侧 ISO-8601 解析错位会抛 ParseException。
- LLM 兜底调用任意 OpenAI chat-completions 兼容服务，`llm.base-url` 可配（当前指向 `https://opencode.ai/zen/v1`，model `big-pickle`）。API key 从 `LLM_API_KEY` 读（`application.yml` 有 dev default，容器内由 compose 覆盖）。`LLM_ENABLED=false` 可关闭。
- E2E 需要 `google-chrome-stable`（`/usr/bin/google-chrome-stable`）与 Xvfb 虚拟显示；样例 PDF 在 `invoice_examples/`（gitignored，E2E 用 `__dirname` 相对定位）。

## Docker 部署（`docker-compose.yml`）

两服务全镜像化，`docker compose up --build` 从零拉起，不依赖宿主机构建产物。

| 服务 | 镜像 | 说明 |
|---|---|---|
| `backend` | `invoice-backend:1.0.0`（`server/Dockerfile` 多阶段） | maven 构建 → temurin-25-jre 运行；健康检查 `wget /api/auth/ping`；三个命名卷：`backend-db`→`/app/data`、`backend-data`→`/app/uploads`、`backend-logs`→`/app/logs` |
| `nginx` | `invoice-web:1.0.0`（`web/Dockerfile` 多阶段） | node 构建 dist → nginx 服务，对外 8088 **HTTPS**，反代 `/api` 到 backend |

- **双 `.env` 分离**（值不一致，勿混）：根 `.env` 是 compose 变量源（`JWT_SECRET`、`ADMIN_PASSWORD` 为 `:?` 必填，缺任一 compose 拒绝启动；还有 `LLM_*`、`WX_*`）。SQLite 无 DB 凭据。
- `UPLOAD_DIR` **故意不进 `.env`**：它在容器里是卷挂载点（`/app/uploads`），改了 `.env` 但卷没挂到新路径会导致文件落 tmpfs、重启丢失且 DB 留孤儿行。改上传路径要连 `docker-compose.yml` 的 volume 一起改。
- `TRUST_PROXY` 在 compose 里**硬编码 true**（不在 `.env` 暴露，避免误关）：仅当 backend 在可信反代后才可信 `X-Real-IP`。
- nginx 对外 8088 + `certs/origin.pem`（Cloudflare Origin Cert）+ `web/cf-allow.conf` IP 白名单。nginx 用 `CF-Connecting-IP` 回填 `X-Real-IP`（`map` 指令），让限流按访客真实 IP 而非 CF 边缘节点 IP 计数。
- 三种公网方案见 `docs/deploy-dns.md`（DNS + Origin Rules + Origin Cert，当前采用）、`docs/deploy-tunnel.md`（cloudflared）、`DEPLOY.md`（综合指南）。

## schema 双写 + 老库迁移

- SQLite DDL 写在两处（内容必须保持一致）：
  - `server/ddl/schema.sql`：人看 / code review 的源文件
  - `server/src/main/resources/schema.sql`：Spring `spring.sql.init.mode: always` 启动时实际加载的副本

  两份顶部都有 "keep in sync" 标记。JPA `ddl-auto: none`（SQLite 类型亲和非严格，`validate` 频繁误报；schema.sql 是单一事实源，列名错配由运行时 `no such column` 捕获）。**改实体必须同步改两份 schema.sql。**
- 新增列时还要改 `config/SchemaMigration.java`：`CREATE TABLE IF NOT EXISTS` 不会给**已存在的老表**加列，SQLite 也没有 `ADD COLUMN IF NOT EXISTS`。它在启动时按 `PRAGMA table_info` 补齐（`invoice.used`/`used_at`、`parsing_metrics`/`parsing_log` 表都走这条路径）。不用 `continue-on-error`，那会静默吞掉整个 schema.sql 的错误。

## 解析架构（核心）

两条路径，`InvoiceParser.parse()` 编排：

1. **正则快速路径** `InvoiceParser.parseText()`：PDFBox 抽文本后按值 pattern 提取。关键事实：PDFBox 会把这类 PDF 重排 —— 标签聚在顶部，**值按文档顺序在底部流出**（号码、日期、购/销方名称与税号、金额、开票人、单行明细）。因此**按值 pattern + 位置顺序**提取，不锚定邻近标签。
   - 金额：每张票出现 3 个 ¥ 值，满足 `金额 + 税额 = 价税合计`（且 `金额 >= 税额`）。做法是取 max/min，再从剩余值里挑 mid 验证不变量；**不是** `max=价税合计/min=税额` 的简单启发式（后者在零税额票、两值相等的票上会静默错标）。不变量不成立则留空给 LLM。
   - 金额符号同时接受半角 `¥` 与全角 `￥`，位置在数字前或后都可能。
   - 税号：`\b[0-9A-Z]{15,18}\b`（18 位信用代码可能纯数字，不能要求含字母），需过滤 `ALI` 前缀机器码（页脚污染）。号码是 20 位数字，靠长度区分。
   - 开票人字段已从实体移除（`956f935`）。
   - 文件名约定：`<销售方>_<发票号码>.<ext>`，非法字符清洗。
2. **LLM 兜底** `InvoiceLlmExtractor.fill()`：正则只回填 **缺失字段**（`ParsedInvoice.missingFields()` 列出，`merge()` 保留已有值）。失败不报错 —— null 保留、上传继续。两次尝试重试。注意点（改这里必看）：
   - **body 必须用 `String.getBytes(UTF_8)`**，RestClient 的 String body 默认 ISO-8859-1 会损坏中文（400 的根因）。
   - 部分兼容服务会在响应后追加 SSE framing（`data: [DONE]`），需取 `resp.lastIndexOf('}')` 前的内容再解析。
   - 发送前用 `\p{Cntrl}` 正则剥离 PDFBox 文本中的控制字符。
   - opencode 按 User-Agent 限流，RestClient 默认注入 `User-Agent: opencode/1.18.23` 伪装官方客户端绕过 429。
   - 用 `SimpleClientHttpRequestFactory` 设 connect/read timeout，防 LLM 卡死拖住上传。

测试 fixture（`server/src/test/resources/sample-*.pdf`）是**真实样例 PDF 经 PDFBox dump** 后的布局，测试用 `new InvoiceParser(null, null)`（null = 不开 LLM，null = 不记指标）。新增版式时：加 fixture + 加断言测试，再决定正则能否覆盖，否则靠 LLM 兜底。

**个体户销售方坑**：销方名称不限定「公司」结尾（…商店/中心/厂），匹配不上 NAME 时备注里「销方开户银行:…公司」会顶替真实销售方 —— 故名称与其统一社会信用代码**按位置配对**（值流里购方名称→购方代码→销方名称→销方代码），配不上再退回旧 NAME 列表逻辑。

**标签紧贴值版式坑**（纵排 购/买/方/信/息，如山姆票）：`名称:某某公司` 与 `统一社会信用代码/纳税人识别号:91…` 直接相邻，代码值前最近的中文串是列标签本身 —— 取「代码前最近中文串」时要跳过 `纳税人识别号`/`统一社会信用代码` 等标签串再取上一个；否则购销方名称会变成「纳税人识别号」（且 LLM 不兜底：字段非 null）。

## 数据模型

六表，`server/ddl/schema.sql`：

- `invoice`：`invoice_number` UNIQUE（重复上传→409）、购/销方名称+税号、`total_amount`/`tax_amount`/`total_with_tax`（TEXT 存 BigDecimal 字符串，由 `BigDecimalStringConverter` 双向转换，避免 SQLite REAL 浮点漂移）、`category`（如 `*餐饮服务*餐饮服务`）、`file_path`（相对 uploadDir 的路径）、`used`/`used_at`（导出批次占用标记）、`created_at`。
- `app_user`：JWT 认证用单管理员表，存 BCrypt 散列。
- `export_batch`：一次「选票打包」的快照（`batch_month` yyyy-MM **唯一**、`invoice_count`、`total_with_tax`；count/total 为创建时点值，后续编辑会重算）。
- `export_batch_item`：批次-发票关联，无外键（与全库风格一致），发票删除时由 `InvoiceService.delete` 拦截、批次删除时由 `ExportService.deleteBatch` 联动清理。
- `parsing_metrics`：单行计数器表（`CHECK (id = 1)`），记录解析各阶段累计指标（上传总数、正则成功/失败、LLM 填充/API 成功失败、`regex_misses_json` 各字段缺失统计）。Dashboard 卡片数据来源。
- `parsing_log`：每次解析 INSERT 一行明细，含时间戳 + 各阶段 0/1 标记 + LLM 耗时。趋势图数据来源。`@Scheduled`（每 10 分钟）保留最近 1000 条。

## 上传流程（`InvoiceService.upload`）

临时 UUID 文件名落盘 → 解析 → 按 `<销售方>_<号码>` 重命名 → 入库。文件存 `./uploads`，DB 只存相对路径。

- `finally` 统一清理临时文件：copy/parse/move 任一失败都不泄漏；成功后 tmp 已被 move 走，`deleteIfExists` 是空操作。
- 重命名用 `Files.move(tmp, dest)`（**无** `REPLACE_EXISTING`）。目标已存在时**先查 DB**：DB 有同号行 = 真重复 → 409；DB 无行 = 孤儿文件（JVM 在 move 后、save 前崩溃遗留）→ 删了重试，否则会永久屏蔽同号重传。
- 解析失败（损坏/加密 PDF）→ 400（`IllegalArgumentException`），不是 500。
- **购买方业务校验** `validateBuyer()`：要求购买方必须是「上海钦钦印刷科技有限公司」+ 信用代码「91310116332791646K」，名称或代码不符 / 解析不出（null）一律 400（严格拒绝）。校验须在 LLM 兜底**之后**的最终结果上进行。
- 解析不出号码 → 存 `UNKNOWN-<12位hex>` 哨兵（必须 ≤ VARCHAR(20)）。LLM 幻觉的超长号码**视为未解析**（截断会让两张票在前 20 字符相撞 → 合法上传被误判 409），绝不截断唯一键。
- 入库前把字符串字段截断到列宽（LLM 可能返回超长值，否则 DataIntegrityViolation 会被误判为重复）。
- 入库违反 UNIQUE（并发重复）→ 删文件 + 409。判据是 `isDuplicateKey()`：遍历 cause 链，Xerial 唯一键冲突 vendor code 恒为 19 且 message 含 "unique"（SQLState 23000 与 NOT NULL/CHECK 共享，不可单用）。
- `safePath()` 校验 DB 里的相对路径不逃出 uploadDir（防直接改库的越权路径）。

## 导出批次（`ExportService` / `ExportController`）

- 创建批次只建记录 + 标记发票 `used=1`，**不**生成 ZIP；ZIP 由批次页按需重新生成（可重复下载）。
- 校验：`batch_month` 必须 `yyyy-MM` 且全局唯一；发票已 `used` 则拒绝（提示具体号码）；跨页勾选期间被删的票容忍消失，全没了才拦。
- 编辑：`addInvoices` / `removeInvoice` 后按现存 item 重算 count/total；发票不再被**任何**批次引用时恢复 `used=0`（同 `deleteBatch`）。
- 删除发票前校验是否被批次引用（被引用 → 400，需先删批次或从批次移除）。
- `writeZip()`：每张票一个 PDF 条目 + `发票清单.xlsx`（`InvoiceExcelGenerator`，SXSSF 流式写，防 `-Xmx256m` 下 OOM）；文件已丢的票跳过并记入 `缺失清单.txt`。ZIP 条目重名加 `_2` 后缀。
- 控制器先做完所有校验再开流 —— 流一旦开始异常无法再换成错误体。

## 认证架构（JWT）

- 登录：`POST /api/auth/login`（body `{username,password}`）→ `{token,username}`。**公开**路径只有 `/api/auth/**`（含 `GET /api/auth/ping`，供 Docker healthcheck），其余 `/api/**` 一律 401。
- 用户表 `app_user`：单管理员，`AuthService.run`（CommandLineRunner）若无用户则 seed（`ADMIN_USERNAME`/`ADMIN_PASSWORD` 覆盖，默认 `admin`/`admin123`），BCrypt 散列。密码错误 → `BadCredentialsException` → 401。
- 登录限流：`LoginRateLimiter`（内存计数，按 IP+用户名，`login.rate-limit-enabled` 默认开启），防暴力破解。
- 认证链路：`JwtAuthenticationFilter`（Bearer 解析 → `SecurityContextHolder`）→ `JwtTokenService`（jjwt，secret 从 `JWT_SECRET` 读，默认值仅限 dev，`expire-seconds: 86400`）。CSRF 关闭、无 Session（STATELESS）。
- 客户端 IP 解析 `AuthController.clientIp()`：`trust-proxy=true` 时取 `X-Real-IP`（由 nginx 覆盖），否则 `getRemoteAddr()`。**不用 `X-Forwarded-For`**：它会追加客户端自带的 XFF，取 `split(",")[0]` 是可伪造值，能绕过限流并定向陷害某 IP。
- **文件端点带不了 header**：预览/下载前端用 `fetchFile()`（axios `responseType: 'blob'`）取回 object URL，不再用裸 URL 字符串。改前端勿退回 `fileUrl()` 裸链。
- E2E/健康检查：所有 API 直调需 `Authorization: Bearer` 头；E2E 先登录拿 token，页面用 `evaluateOnNewDocument` 注入 localStorage。

## API

- `POST /api/auth/login`、`POST /api/auth/wx-login`、`GET /api/auth/ping`
- `POST /api/invoices/upload`（multipart `file`，≤10MB）
- `GET /api/invoices?page=&size=&used=&keyword=`（分页，按 createdAt 倒序；`used` 过滤已使用标记；`keyword` 对销售方/购买方/发票号码/项目名称模糊匹配，LIKE 通配符已转义；`size` 上限 100）
- `DELETE /api/invoices/{id}`
- `GET /api/invoices/{id}/file?disposition=inline|download`（预览/下载）
- `POST /api/export-batches`（body `{ids, batchMonth}`）、`GET /api/export-batches?page=&size=`
- `GET /api/export-batches/{id}/invoices`、`POST /api/export-batches/{id}/invoices`（body `{ids}`）
- `DELETE /api/export-batches/{id}/invoices/{invoiceId}`、`DELETE /api/export-batches/{id}`
- `GET /api/export-batches/{id}/zip`（流式 ZIP）
- `GET /api/metrics/parsing`（解析指标汇总，含成功率计算）、`GET /api/metrics/parsing/trend?days=30`

## 前端要点

- 路由（`web/src/router/index.js`）：`/login` 公开，`/upload`、`/list`、`/exports`、`/metrics` 挂在 `/` Layout 下，另有 `/:pathMatch(.*)*` → `NotFound.vue` 兜底；`beforeEach` 守卫无 token 跳 `/login`。
- `App.vue` 是 Layout（侧栏 + header 用户下拉 + 移动端 `el-drawer` 抽屉菜单），不再持有 activeMenu 状态机。
- `api/invoice.js` 集中所有请求：axios 实例 + 请求拦截器注入 Bearer + 401 拦截器清 token 整页跳登录（登录接口自身除外）。`fetchFile()` 返回 `{url, blob}`，**url 必须由调用方 `URL.revokeObjectURL()` 释放**，否则 PDF 字节驻留内存。`errorMessage()` 处理 Blob/HTML 错误体并截断到 200 字符（网关 502/413 回整页 HTML，原样塞进 toast 不可读）。
- `components/PdfPreviewDialog.vue`：`InvoiceList` / `ExportBatchList` 共用的预览弹窗（`ref.open(rows, index)`，支持翻张）。桌面 iframe 走浏览器原生查看器；移动端 `vue-pdf-embed` 内嵌渲染（移动浏览器 iframe 不渲染 PDF）。该组件必须 `defineAsyncComponent` 拆独立 chunk，**勿改成静态 import**（pdf.js 体积大）。
- `composables/useIsMobile.js`：模块级单例 `matchMedia`，断点从 CSS 变量 `--bp-mobile` 运行时读取（与 `styles/tokens.css` 同源，避免两处漂移）。
- 主题：`styles/tokens.css` 定义设计 token，`utils/theme.js` 切换 `<html class="dark">`；`index.html` 的 `<head>` 有同步脚本注入 dark class，避免刷新白闪。
- `web/e2e/run.mjs` 的 12 项断言依赖 `.upload-btn`、`.batch-export-btn`、`.login-btn` 等 Element Plus DOM 结构，改前端时勿破坏。

## 微信小程序（`miniprogram/`，体验版）

- 仅做**体验版**（个人用，加体验成员扫码打开）：体验版开调试模式可绕过合法域名校验，`dpdns.org` 免费域名**永远无法 ICP 备案**，上不了正式版；正式版才需要备案域名 + 443。
- 登录无感：`app.js` onLaunch 静默 `wx.login()` code → `POST /api/auth/wx-login`（`WxLoginService`，code2session）→ openid 白名单（`WX_ALLOWED_OPENIDS`，逗号分隔）命中即签 admin JWT。**白名单为空 = 拒绝所有**；首次绑定：留空登录一次，后端日志打出 openid，填入 `.env` 重启。**绝不能自动建号**（任何打开小程序的用户都能拿到合法 openid）。
- code2session 响应体 Content-Type 不规范（text/plain 的 JSON），RestClient 按 Content-Type 选 converter 会失败 —— 故取 `String` 再手动反序列化。
- `miniprogram/utils/request.js`：401 自动清 token 重登并重放一次；上传 multipart 字段名必须 `file`；下载 PDF 走 `downloadInvoiceFile()`（带 header）→ `wx.openDocument` 原生预览（web 端 pdf.js 双路径在端内不需要）。
- 详情页数据经 eventChannel 从列表页传入（后端无 `GET /api/invoices/{id}`）。
- `project.config.json` 的 `urlCheck: false` 即「不校验合法域名」，本地联调可指 `http://localhost:8080`；上线前把 `appid` 从 `touristappid` 换成真实 AppID。

## 开发环境坑

- IDEA 打开**根目录**（靠聚合 POM）。
- 服务器端 curl 测中文上传失败时，先怀疑编码，不要怀疑 LLM。
- 上传 413 场景：`server.tomcat.max-swallow-size: -1` 是为让「文件超过 10MB」的错误体能正常写出，否则经 nginx 反代时浏览器收到 502 而非提示。
