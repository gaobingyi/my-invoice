# 发票管理系统 Docker 部署计划

## Context

发票管理系统（前后端分离：Vue3 + Spring Boot + MySQL）已完成本地开发与 E2E 验证。当前仓库无任何 Docker 脚手架。目标：一套 `docker-compose.yml` 一键起 mysql + backend + nginx 三服务，生产构建静态前端 + 反代 `/api`。

## 用户已确认决策

- **对外端口 8088**（避开本地 8080/5173）
- **LLM 兜底默认开启指向宿主机**：`APP_LLM_ENABLED=true`、`LLM_BASE_URL=http://host.docker.internal:20128/v1`，需 `extra_hosts: ["host.docker.internal:host-gateway"]`

## 新建文件（5 个）

| 路径 | 作用 |
|---|---|
| `docker-compose.yml` | 三服务编排 + env 替换 + 健康检查 + volumes |
| `server/Dockerfile` | 后端多阶段（maven 构建 → temurin-25-jre 运行） |
| `web/Dockerfile` | 前端多阶段（node 构建 → nginx 服务） |
| `web/nginx.conf` | 静态 + `/api` 反代 + 10m body |
| `.env` | compose 密钥（gitignore 已覆盖） |
| `.dockerignore` | 排除 .git/.env/target/node_modules/dist/uploads/logs/invoice_examples |

## 镜像 tag（已验证存在）

- 构建：`maven:3.9-eclipse-temurin-25`（自带 JDK25+Maven）
- 运行：`eclipse-temurin:25-jre-alpine`
- 前端构建：`node:22-alpine`；服务：`nginx:1.27-alpine`
- DB：`mysql:8.0`

## 关键设计

1. **datasource URL**：yml 硬编码 `127.0.0.1`，容器内致命。compose 用 `SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/...` 覆盖（relaxed binding），本地 dev yml 不动。
2. **schema 初始化**：`ddl-auto: validate`，schema 必须先存在。挂 `server/ddl/schema.sql` 到 `/docker-entrypoint-initdb.d/`，backend `depends_on: mysql: service_healthy`。
3. **上传/日志持久化**：命名卷 `backend-data:/app/uploads`、`backend-logs:/app/logs`，compose 设 `APP_UPLOAD_DIR=/app/uploads`、`LOGGING_FILE_NAME=/app/logs/invoice-server.log`。后端以 root 跑，`Files.createDirectories` 自动建目录。
4. **Nginx 反代**：`location /api/ { proxy_pass http://backend:8080; }`（无尾路径 = 原样透传，backend controller 是 `/api/invoices`）。`client_max_body_size 10m` 对齐 Spring multipart 上限。
5. **.env 不泄漏**：`server/.env` 含真实 API key，根 `.gitignore` 已排除 `.env`；`.dockerignore` 双保险。
6. **健康检查**：无 actuator，用 `wget --spider /api/invoices`（Alpine 自带）；mysql 用 `mysqladmin ping`。

## 验证

```bash
docker compose config        # 校验 env 替换
docker compose build
docker compose up -d
docker compose ps
curl http://localhost:8088/api/invoices   # → {"content":[],...}
# 浏览器 8088 上传样例 PDF → 列表出现 → 预览/下载
docker compose exec mysql mysql -u invoice_app -pinvoice_db -e "select count(*) from invoice"
docker compose restart       # 卷持久化验证
```

## 明确跳过

- 非 root 后端用户、actuator、nginx tcp_nopush、`--release` 降级路径（镜像验证过存在，不需要）

---

