# 发票管理系统

上传 PDF 发票 → 自动解析字段 → 存 SQLite → 列表展示管理。前后端分离，支持批量上传、预览、下载、删除。

## 功能

- 🔐 登录认证（JWT）：默认账号 `admin` / `admin123`，生产用 `APP_ADMIN_PASSWORD` 修改
- 📤 上传 PDF 发票（单张/批量 ≤20），自动解析字段
- 🔎 解析：发票号码、开票日期、购/销方名称与税号、金额/税额/价税合计、项目名称、开票人
- 📋 列表展示 + 分页（按上传时间倒序）
- 👁️ 预览 / ⬇️ 下载 / 🗑️ 删除

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 25 · Spring Boot 3.4.5 · Spring Data JPA · SQLite（Xerial JDBC）· PDFBox 3.0.2 |
| 前端 | Vue 3 · Vite 6 · Element Plus · Axios |
| 解析 | 正则快速路径（PDFBox 抽文本）+ LLM 兜底（OpenAI 兼容，可选） |
| 部署 | Docker Compose（backend + nginx）· 可 1C1G 运行 |

## 快速开始

### Docker 方式（推荐，一键起全部）

```bash
# 1. 准备环境变量（.env 已被 gitignore）
cp .env.example .env   # 或手动创建：LLM_API_KEY / JWT_SECRET / APP_ADMIN_*（SQLite 无 DB 凭据）

# 2. 构建 + 启动
docker compose up -d --build

# 3. 访问
# http://localhost:8088   （登录：admin / admin123，可用 APP_ADMIN_PASSWORD 覆盖）
```

### 本地开发

**环境前置**（见 [CLAUDE.md](CLAUDE.md)）：
- SQLite 是文件式数据库，首次启动自动建表（`spring.sql.init.mode: always` 触发 `server/src/main/resources/schema.sql`）。DB 落 `server/data/invoice.db`
- 可选）本地 LLM 服务，配 `server/.env` 的 `LLM_API_KEY`

```bash
# 后端（8080）
cd server && mvn spring-boot:run

# 前端（5173，vite proxy /api → 8080）
cd web && npm run dev

# 浏览器端到端测试（需后端+前端+Chrome）
node web/e2e/run.mjs
```

## 解析原理

`InvoiceParser` 两条路径：
1. **正则快速路径**：PDFBox 抽文本后按值 pattern + 位置顺序提取（PDFBox 会把标签聚顶、值按文档序流出）
2. **LLM 兜底**：正则缺失字段时调 OpenAI 兼容服务补全，失败不中断上传

## 项目结构

```
server/         后端（Spring Boot）
  src/          解析器 / 服务 / 控制器 / 实体
  ddl/schema.sql       SQLite 表结构（与 src/main/resources/schema.sql 内容一致）
web/            前端（Vue 3 + Vite）
  e2e/          浏览器端到端测试
docker-compose.yml   三服务编排
DEPLOY.md       云端部署指南（海外 VPS + 无域名方案）
```

## 许可证

待补充