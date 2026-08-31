# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

发票管理系统（前后端分离）。上传 PDF 发票 → 解析字段 → 存入 SQLite → 列表展示。业务见 `Requirements.md`，当前实现已覆盖上传/解析/列表/预览/下载/删除。

- 后端 `server/`：Java 25 + Spring Boot 3.4.5 + Spring Data JPA + SQLite（Xerial JDBC）+ PDFBox 3.0.2
- 前端 `web/`：Vue 3 + Vite 6 + Element Plus + Axios
- 根目录 `pom.xml` 是聚合 POM（`<modules><module>server</module></modules>`），仅为让 IDEA 打开根目录时识别 `server` 为 Maven 模块，**无父依赖管理**。

## 常用命令

```bash
# 一键启停本地开发（后端 8080 + 前端 5173）
./scripts/dev.sh start / stop / restart / status / logs

# 清空数据（自动检测本地/Docker 模式，确认后执行）
./scripts/clean.sh

# 后端测试（解析器单元测试，无需 DB/LLM）
cd server && mvn test

# 后端单独测试类（另有 JwtTokenServiceTest、SecurityConfigTest）
cd server && mvn test -Dtest=InvoiceParserTest

# 启动后端（首次启动自动建 ./data/invoice.db）
cd server && mvn spring-boot:run        # 监听 8080，upload 目录 ./uploads

# 前端
cd web && npm run dev                   # 5173，vite proxy /api → 8080

# 浏览器端到端测试（需先起后端 + 前端）
node web/e2e/run.mjs                    # 驱动 Xvfb + google-chrome-stable，10 项断言

# Docker 部署（两服务：backend + nginx，全镜像化；DB 用 SQLite 文件内嵌于 backend）
docker compose up -d --build            # 构建+启动，对外端口 8088
docker compose logs -f backend
docker compose ps                       # 看 healthy 状态
docker compose down -v                  # 停止+清卷

# 异地备份（VPS 上跑）：每日 3 点把 DB+uploads 打成单包传 MEGA，见 scripts/backup-mega.sh + DEPLOY.md §7
bash scripts/backup-mega.sh
```

### 环境前置

- SQLite 是文件式数据库，**首次启动自动建表**（`spring.sql.init.mode: always` 触发 `server/src/main/resources/schema.sql`）。开发期 DB 落 `./data/invoice.db`，容器内 `/app/data/invoice.db`（命名卷 `backend-db`）。
- LLM 兜底调用任意 OpenAI chat-completions 兼容服务，`llm.base-url` 可配（当前指向 `https://opencode.ai/zen/v1`，model `big-pickle`）。API key 从环境变量 `LLM_API_KEY` 读取（`server/.env` 提供，gitignored）。`llm.enabled: false` 可关闭。
- E2E 需要 `google-chrome-stable`（`/usr/bin/google-chrome-stable`）与 Xvfb 虚拟显示。

## Docker 部署（`docker-compose.yml`）

两服务全镜像化，`docker compose up --build` 从零拉起，不依赖宿主机构建产物。

| 服务 | 镜像 | 说明 |
|---|---|---|
| `backend` | `invoice-backend`（`server/Dockerfile` 多阶段） | maven 构建 → temurin-25-jre 运行，健康检查用 `wget /api/invoices`；命名卷 `backend-db` 存 SQLite 文件于 `/app/data/` |
| `nginx` | `invoice-web`（`web/Dockerfile` 多阶段） | node 构建 dist → nginx 服务，对外 8088，反代 `/api` 到 backend |

**双 `.env` 分离**（值不一致，勿混）：
- **根 `.env`**：docker compose 变量源（`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、必填 `JWT_SECRET`/`ADMIN_PASSWORD`），compose 同目录。SQLite 无 DB 凭据，故 `DB_PASSWORD` / `MYSQL_ROOT_PASSWORD` 已移除。`LLM_API_KEY` 在 `application.yml` 也有 dev default（个人 dev key），本地不起后端可不 source。
- 容器内 DB 用 `/app/data/invoice.db`（命名卷 `backend-db`）；本地 DB 用 `./data/invoice.db`（相对 `server/` cwd）；LLM 用根 `.env` 的 `LLM_BASE_URL` 覆盖 `application.yml` 的 base-url。

## schema 双写

SQLite DDL 写在两处（内容必须保持一致）：
- `server/ddl/schema.sql`：人看/code review 的源文件
- `server/src/main/resources/schema.sql`：Spring `spring.sql.init.mode: always` 启动时实际加载的副本

改 schema 必须同步两份。两份顶部都有 "keep in sync" 标记。

## 解析架构（核心）

两条路径，`InvoiceParser.parse()` 编排：

1. **正则快速路径** `InvoiceParser.parseText()`：PDFBox 抽文本后按值 pattern 提取。关键事实：PDFBox 会把这类 PDF 重排 —— 标签聚在顶部，**值按文档顺序在底部流出**（号码、日期、购/销方名称与税号、金额、开票人、单行明细）。因此**按值 pattern + 位置顺序**提取，不锚定邻近标签。
   - 金额：每张票出现 3 个 ¥ 值。`max = 价税合计`、`min = 税额`、`middle = 金额`（两版式均满足 金额+税额=价税合计）。
   - 税号：`\b[0-9A-Z]{15,18}\b`，需过滤 `ALI` 前缀机器码（页脚污染）。
   - 开票人：先试邻近标签 `开票人：`，失败再试 ¥ 后跟 CJK 令牌。
   - 文件名约定：`<销售方>_<发票号码>.<ext>`，非法字符清洗。

2. **LLM 兜底** `InvoiceLlmExtractor.fill()`：正则只回填 **缺失字段**（`ParsedInvoice.missingFields()` 列出，`merge()` 保留已有值）。失败不报错 —— null 保留、上传继续。两次尝试重试。注意点（改这里必看）：
   - **body 必须用 `String.getBytes(UTF_8)`**，RestClient 的 String body 默认 ISO-8859-1 会损坏中文（400 的根因）。
   - 部分兼容服务会在响应后追加 SSE framing（`data: [DONE]`），需取 `resp.lastIndexOf('}')` 前的内容再解析。
   - 发送前用 `\p{Cntrl}` 正则剥离 PDFBox 文本中的控制字符。
   - opencode 按 User-Agent 限流，RestClient 默认注入 `User-Agent: opencode/1.18.23` 伪装官方客户端绕过 429。

测试 fixture（`server/src/test/resources/*.pdf`）是**真实样例 PDF 经 PDFBox dump** 后的布局，测试用 `new InvoiceParser(null, null)`（null = 不开 LLM，null = 不记指标）。新增版式时：加 fixture + 加断言测试，再决定正则能否覆盖，否则靠 LLM 兜底。

## 数据模型

四表，`server/ddl/schema.sql`：

- `invoice`：`invoice_number` UNIQUE（重复上传→409）、购/销方名称+税号、`total_amount`/`tax_amount`/`total_with_tax`（TEXT 存 BigDecimal 字符串，由 `BigDecimalStringConverter` 双向转换，避免 SQLite REAL 浮点漂移）、`category`（如 `*餐饮服务*餐饮服务`）、`drawer`、`file_path`（磁盘相对路径）、`created_at`。
- `app_user`：JWT 认证用单管理员表，存 BCrypt 散列（见认证架构）。
- `parsing_metrics`：单行计数器表（`CHECK (id = 1)`），记录解析各阶段累计指标（上传总数、正则成功/失败、LLM 填充成功/失败、API 成功/失败、`regex_misses_json` 各字段缺失统计）。Dashboard 卡片数据来源。
- `parsing_log`：每次解析 INSERT 一行明细，含时间戳 + 各阶段 0/1 标记 + LLM 耗时。趋势图数据来源。`@Scheduled` 保留最近 1000 条。

JPA `ddl-auto: none`（SQLite 类型亲和非严格，validate 频繁误报；schema.sql 是单一事实源，列名错配会被运行时的 SQL 异常直接捕获），schema 由 SQL 文件管理，改实体需同步改两份 schema.sql。

## 上传流程（`InvoiceService.upload`）

临时 UUID 文件名落盘 → 解析 → 按 `<销售方>_<号码>` 重命名 → 入库。文件存 `./uploads`，DB 只存相对路径。
- 重命名用 `Files.move(tmp, dest)`（**无** `REPLACE_EXISTING`）：目标已存在（重复发票）→ 409，绝不覆盖已存 PDF。
- 解析失败（损坏/加密 PDF）→ 删临时文件后抛错，不泄露孤儿文件。
- 解析不出号码 → 存 `UNKNOWN-<12位hex>` 哨兵（必须 ≤ VARCHAR(20)，不可用完整 UUID）。
- 入库违反 UNIQUE（并发重复）→ 删文件 + 409。

## 认证架构（JWT）

- 登录：`POST /api/auth/login`（body `{username,password}`）→ `{token,username}`。**公开**路径只有 `/api/auth/**`（含 `GET /api/auth/ping`，供 Docker healthcheck），其余 `/api/**` 一律 401。
- 用户表 `app_user`：单管理员，启动时 `AuthService.run` 若无用户则 seed（`ADMIN_USERNAME`/`ADMIN_PASSWORD` 覆盖，默认 `admin`/`admin123`），BCrypt 散列。密码错误 → `BadCredentialsException` → 401。
- 登录限流：`LoginRateLimiter`（内存计数，按 IP+用户名，配置 `login.rate-limit-enabled`，默认开启），防暴力破解。
- 认证链路：`JwtAuthenticationFilter`（Bearer 解析 → `SecurityContextHolder`）→ `JwtTokenService`（jjwt，secret 从 `JWT_SECRET` 读，默认值仅限 dev）。CSRF 关闭、无 Session（STATELESS）。
- **文件端点带不了 header**：预览/下载前端用 `fetchFile()`（axios `responseType: 'blob'`）取回 object URL，不再用裸 URL 字符串。改前端勿退回 `fileUrl()` 裸链。
- E2E/健康检查：所有 API 直调需 `Authorization: Bearer` 头；E2E 先登录拿 token，页面用 `evaluateOnNewDocument` 注入 localStorage。

## API

- `POST /api/auth/login`、`GET /api/auth/ping`
- `POST /api/invoices/upload`（multipart `file`）
- `GET /api/invoices?page=&size=`（分页，按 createdAt 倒序）
- `DELETE /api/invoices/{id}`
- `GET /api/invoices/{id}/file?disposition=inline|download`（预览/下载）
- `GET /api/metrics/parsing`（解析指标汇总，含成功率计算）
- `GET /api/metrics/parsing/trend?days=30`（按天聚合趋势数据）

## 前端要点

- 路由（`web/src/router/index.js`）：`/login` 公开，`/upload`、`/list`、`/metrics` 挂在 `/` Layout 下；`beforeEach` 守卫无 token 跳 `/login`。`App.vue` 是 Layout（侧栏 + header 用户下拉），不再持有 activeMenu 状态机。
- `web/src/views/MetricsDashboard.vue`：解析指标 dashboard，拉取 `/api/metrics/parsing` + `/api/metrics/parsing/trend` 展示统计卡片（正则/LLM 成功率环形进度条、平均响应时间）+ 趋势条形图 + 字段缺失明细表。
- `web/src/views/InvoiceList.vue`：上传按钮 `.upload-btn`（E2E 选择器）、批量上传（`:limit="20"`，循环调用）、列表（销售方/购买方/项目名称/上传时间等列）、预览用 el-dialog + iframe（src 为 `fetchFile()` 的 blob URL）、下载用隐藏 `<a download>`。
- `web/e2e/run.mjs` 断言依赖这些 Element Plus DOM 结构，改前端时勿破坏。

## 开发环境坑

- IDEA 打开**根目录**（靠聚合 POM）。
- 服务器端 curl 测中文上传失败时，先怀疑编码，不要怀疑 LLM。
