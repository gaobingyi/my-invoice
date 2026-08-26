# AGENTS.md

发票管理系统（前后端分离）。上传 PDF → 解析字段 → SQLite → 列表/预览/下载/删除。详细参考 `CLAUDE.md`，本文只列容易踩坑的高信号事实。

## 布局与命令

- `server/`：Java 25 + Spring Boot 3.4.5 + JPA + SQLite（Xerial JDBC）+ PDFBox 3.0.2。包 `com.example.invoice`。
- `web/`：Vue 3 + Vite 6 + Element Plus + Axios。无 lint/typecheck 脚本。
- 根 `pom.xml` 只是聚合 POM（无父依赖管理），让 IDEA 识别 `server` 模块；构建始终进 `server/` 目录执行。

```bash
cd server && mvn test                          # 单测，无需 DB/LLM
cd server && mvn test -Dtest=InvoiceParserTest  # 单类（另有 JwtTokenServiceTest、SecurityConfigTest）
cd server && mvn spring-boot:run               # 8080，首次启动自动建 ./data/invoice.db
cd web && npm run dev                          # 5173，vite proxy /api → 8080
node web/e2e/run.mjs                           # E2E：需后端+前端+Xvfb+google-chrome-stable
docker compose up -d --build                   # 两服务全镜像化（backend + nginx），对外 8088（HTTPS）
```

## 环境与 .env（双文件，勿混）

- **根 `.env`**（gitignored，模板 `.env.example`）：docker compose 变量源。`JWT_SECRET`、`APP_ADMIN_PASSWORD` 均为 `:?` **必填**，缺任一 compose 直接拒绝启动。还含 `LLM_API_KEY`、`APP_LLM_BASE_URL`、`APP_LLM_MODEL`。SQLite 是文件式，无 DB 凭据（旧 `DB_PASSWORD` / `MYSQL_ROOT_PASSWORD` 已移除）。
- **`server/.env`**：本地 dev 用（spring-dotenv 从 `server/` cwd 加载，含 `LLM_API_KEY`）。
- SQLite 连接（`./data/invoice.db`，WAL 模式）与 LLM 默认配置在 `server/src/main/resources/application.yml`；容器内由 compose 环境变量覆盖（DB `/app/data/invoice.db`、LLM 走 `APP_LLM_BASE_URL`）。

## 解析架构（核心）

`InvoiceParser.parse()` 两条路径，改这里必读 `CLAUDE.md`「解析架构」：

1. **正则快速路径**：PDFBox 会把这类 PDF 重排 —— 标签聚顶、**值按文档顺序在底部流出**，因此按值 pattern + 位置顺序提取，不锚定邻近标签。金额 3 个 ¥ 值：`max=价税合计`、`min=税额`、`middle=金额`。
2. **LLM 兜底** `InvoiceLlmExtractor.fill()`：只回填缺失字段，失败不报错（null 保留、上传继续）。三个坑：body 必须 `String.getBytes(UTF_8)`（RestClient 默认 ISO-8859-1 损坏中文→400）；取 `resp.lastIndexOf('}')` 前内容剥离 SSE framing；发送前剥 `\p{Cntrl}`。

测试 fixture（`server/src/test/resources/*.pdf`）是真实 PDF 布局，测试用 `new InvoiceParser(null)`（null=不开 LLM）。新增版式：加 fixture + 断言，能正则则正则，否则靠 LLM。

## 数据模型与上传

- schema 由 `server/ddl/schema.sql` 管理（与 `server/src/main/resources/schema.sql` 内容一致），JPA `ddl-auto: none`（SQLite 类型亲和非严格）—— **改实体必须同步改两份 schema.sql**。
- `invoice.invoice_number` UNIQUE：重复上传 → 409。`app_user` 为单管理员 BCrypt 表，启动无用户时 seed（`APP_ADMIN_*` 覆盖，默认 `admin/admin123`）。
- 上传：临时 UUID 落盘 → 解析 → `Files.move` 重命名 `<销售方>_<号码>`（**无 REPLACE_EXISTING**，目标存在=重复→409）。解析失败删临时文件；无号码存 `UNKNOWN-<12位hex>` 哨兵（必须 ≤ VARCHAR(20)）。

## 认证与前端

- 仅 `/api/auth/**` 公开（含 healthcheck `GET /api/auth/ping`），其余 `/api/**` 一律 401，直调需 `Authorization: Bearer`。登录有内存限流（`LoginRateLimiter`）。
- **文件端点带不了 header**：前端预览/下载必须用 `fetchFile()`（axios `responseType:'blob'`→object URL），勿退回裸 URL。
- `App.vue` 是 Layout（无 activeMenu 状态机）。`web/src/views/InvoiceList.vue` 的 `.upload-btn` 及 Element Plus DOM 结构是 `web/e2e/run.mjs` 断言依赖，改前端勿破坏。

## 部署坑

- nginx 对外 **8088 HTTPS**（`certs/origin.pem` + Cloudflare IP 白名单 `web/cf-allow.conf`），改端口/证书注意 compose 卷挂载。
- 服务器端 curl 测中文上传失败先怀疑编码，不要怀疑 LLM。
