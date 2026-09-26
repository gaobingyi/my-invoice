# 发票管理系统

上传 PDF 发票 → 自动解析字段 → 存 SQLite → 列表展示管理。前后端分离，支持批量上传、预览、下载、删除、按月选票打包导出。

## 功能

- 🔐 登录认证（JWT）：默认账号 `admin` / `admin123`，生产用 `ADMIN_PASSWORD` 修改
- 📤 上传 PDF 发票（单张/批量 ≤20），自动解析字段
- 🔎 解析：发票号码、开票日期、购/销方名称与税号、金额/税额/价税合计、项目名称
- 📋 列表展示 + 分页（按上传时间倒序）、关键词搜索、「已使用」筛选
- 👁️ 预览 / ⬇️ 下载 / 🗑️ 删除
- 📦 导出批次：按月份选票打包 ZIP（发票 PDF + `发票清单.xlsx`），可编辑增删发票
- 📊 解析指标看板：正则/LLM 成功率、平均耗时、字段缺失明细、趋势图
- 📱 移动端响应式；另有微信小程序体验版（`miniprogram/`）

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 25 · Spring Boot 3.4.5 · Spring Data JPA · SQLite（Xerial JDBC）· PDFBox 3.0.2 · POI 5.3.0（导出 Excel） |
| 前端 | Vue 3 · Vite 6 · Element Plus · Axios · vue-pdf-embed（移动端预览） |
| 小程序 | 原生微信小程序（体验版，openid 白名单绑定 admin） |
| 解析 | 正则快速路径（PDFBox 抽文本）+ LLM 兜底（OpenAI 兼容，可选） |
| 部署 | Docker Compose（backend + nginx 两服务）· 可 1C1G 运行 |

## 快速开始

### Docker 方式（推荐，一键起全部）

```bash
# 1. 准备环境变量（.env 已被 gitignore）
cp .env.example .env   # 或手动创建：LLM_API_KEY / JWT_SECRET / ADMIN_USERNAME / ADMIN_PASSWORD（SQLite 无 DB 凭据）
# JWT_SECRET 与 ADMIN_PASSWORD 为必填，缺任一 compose 直接拒绝启动

# 2. 准备 TLS 证书（certs/ 已被 gitignore，新克隆的仓库没有 —— 缺证书 nginx 起不来）
#    按 docs/deploy-dns.md §3.2 从 Cloudflare 取 Origin Cert，或本地用 mkcert 自签：
#    certs/origin.pem + certs/origin.key

# 3. 构建 + 启动
docker compose up -d --build

# 4. 访问
# https://localhost:8088   （nginx 仅监听 8088 ssl，浏览器会警告证书不受信任，属预期）
# 登录：admin / admin123，可用 ADMIN_PASSWORD 覆盖
```

### 本地开发

**环境前置**（详见 [CLAUDE.md](CLAUDE.md)）：
- SQLite 是文件式数据库，首次启动自动建表（`spring.sql.init.mode: always` 触发 `server/src/main/resources/schema.sql`）。DB 落 `server/data/invoice.db`
- LLM 兜底可选：`LLM_ENABLED=false` 关闭；本地不起后端时无需配 key（`application.yml` 有 dev default）。改 key/地址用根 `.env` 或 shell export（无 `server/.env` 文件）

```bash
# 一键启停（后端 8080 + 前端 5173）
./scripts/dev.sh start / stop / restart / status / logs

# 或手动分别启动
cd server && mvn spring-boot:run      # 8080
cd web && npm run dev                 # 5173，vite proxy /api → 8080

# 后端测试（无需 DB/LLM）
cd server && mvn test
cd server && mvn test -Dtest=InvoiceParserTest

# 浏览器端到端测试（需后端+前端+Xvfb+google-chrome-stable）
node web/e2e/run.mjs                  # 无 Xvfb 的机器用 E2E_HEADLESS=1
```

## 解析原理

`InvoiceParser` 两条路径：
1. **正则快速路径**：PDFBox 抽文本后按值 pattern + 位置顺序提取（PDFBox 会把标签聚顶、值按文档序流出）。金额不锚定标签，而是解 `金额 + 税额 = 价税合计` 不变量
2. **LLM 兜底**：正则缺失字段时调 OpenAI 兼容服务补全，失败不中断上传

上传时另有业务校验：购买方必须是「上海钦钦印刷科技有限公司」（名称 + 信用代码双匹配），不符或解析不出直接拒绝。细节见 [CLAUDE.md](CLAUDE.md)「解析架构」「上传流程」。

## 项目结构

```
server/         后端（Spring Boot）
  src/          解析器 / 服务 / 控制器 / 实体
  ddl/schema.sql       SQLite 表结构（与 src/main/resources/schema.sql 内容一致）
web/            前端（Vue 3 + Vite）
  e2e/          浏览器端到端测试
miniprogram/    微信小程序（体验版）
scripts/        dev.sh 本地启停 / clean.sh 清库 / backup-mega.sh 异地备份
docs/           部署文档（DNS / Tunnel 两种公网方案）
docker-compose.yml   两服务编排（backend + nginx）
DEPLOY.md       云端部署指南（海外 VPS + 无域名方案）
```

`invoice_examples/`（样例发票 PDF，E2E 依赖）与 `certs/`（TLS 证书）已在 `.gitignore` 中，需自行准备。

## 许可证

待补充