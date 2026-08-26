# 部署文档：Cloudflare Tunnel 方案（`invoice.bingyi.dpdns.org` + cloudflared）

> 适用场景：VPS **无公网 IP**（或不想暴露端口），域名托管在 Cloudflare，通过 `cloudflared` 隧道把本地服务映射到 CF 边缘。
>
> **特点**：无需在 VPS 防火墙开放任何入站端口、无需 Origin Certificate、CF 与 Tunnel 间自动 TLS、Free 计划可用。

---

## 0. 前置条件

| 项 | 要求 |
|---|---|
| VPS | Linux，已安装 Docker + Docker Compose v2，**可出站访问互联网**（连 CF 控制平面） |tijiao 
| 域名 | `invoice.bingyi.dpdns.org` 在 Cloudflare 托管 |
| 账号 | Cloudflare 账号，已添加域名、有 API Token（Zone:Read, Tunnel:Edit） |
| 认证 | JWT（同 DNS 方案） |

---

## 1. Cloudflare Tunnel 创建

### 1.1 Dashboard 创建 Tunnel

1. **Zero Trust → Networks → Tunnels → Create a tunnel**
2. 名称：`invoice-tunnel` → **Save tunnel**
3. **Configure**：
   - **Subdomain**: `invoice`，Domain: `bingyi.dpdns.org`，Type: `HTTP`，URL: `http://localhost:8080`（指向后端端口）
   - 可再加一条：`www` / `@` → `HTTP` → `http://localhost:80`（如需前端直挂，否则前端也走后端反代）
4. **Save** → 记下 **Tunnel ID**（如 `abc123-def456-...`）与 **Tunnel Token**（`eyJh...`）

> Tunnel Token 视为**密钥**，仅写入 VPS `.env`，不提交 git。

### 1.2 DNS 记录（自动创建）

创建 Tunnel 后，CF 自动在 DNS 添加 `CNAME invoice → <tunnel-id>.cfargotunnel.com`，**Proxied 橙云**（不可改灰云）。

---

## 2. VPS 部署文件

### 2.1 目录结构

```
/opt/invoice/
├── docker-compose.yml
├── .env                     # 含 TUNNEL_TOKEN、LLM_API_KEY、JWT_SECRET 等（SQLite 无 DB 凭据）
├── web/
│   ├── nginx.conf           # 纯 HTTP，监听 80，不配 SSL
│   └── Dockerfile
└── cloudflared/             # 可选：如用容器跑 cloudflared
    └── config.yml
```

### 2.2 `docker-compose.yml`

```yaml
name: invoice-manager

services:
  backend:
    image: invoice-backend:1.0.0
    build:
      context: .
      dockerfile: server/Dockerfile
    container_name: invoice-backend
    environment:
      # SQLite 文件式 DB，由命名卷 backend-db 持久化。date_class=TEXT 让 Xerial JDBC
      # 读写 timestamp 都用 ISO-8601（避免 INTEGER epoch ms 写入与 ISO 读解析错位）。
      SPRING_DATASOURCE_URL: jdbc:sqlite:/app/data/invoice.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&date_class=TEXT
      UPLOAD_DIR: /app/uploads
      LOGGING_FILE_NAME: /app/logs/invoice-server.log
      LLM_ENABLED: "true"
      LLM_BASE_URL: ${LLM_BASE_URL:-https://opencode.ai/zen/v1}
      LLM_MODEL: ${LLM_MODEL:-oc/deepseek-v4-flash-free}
      LLM_API_KEY: ${LLM_API_KEY:-}
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET in .env}
      ADMIN_USERNAME: ${ADMIN_USERNAME:-admin}
      ADMIN_PASSWORD: ${ADMIN_PASSWORD:?set ADMIN_PASSWORD in .env}
      TZ: Asia/Shanghai
      JAVA_OPTS: -Xmx256m -Xms128m -XX:+UseSerialGC -XX:MaxRAMPercentage=50
    volumes:
      - backend-data:/app/uploads
      - backend-logs:/app/logs
      - backend-db:/app/data
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost:8080/api/auth/ping"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 60s

  nginx:
    image: invoice-web:1.0.0
    build:
      context: .
      dockerfile: web/Dockerfile
    container_name: invoice-nginx
    depends_on:
      - backend
    ports:
      - "127.0.0.1:8080:80"   # 仅回环，供 cloudflared 连 localhost:8080
    volumes:
      - ./web/nginx.conf:/etc/nginx/conf.d/default.conf:ro
    restart: unless-stopped

  # 方案 A：cloudflared 容器（推荐，随 compose 生命周期）
  cloudflared:
    image: cloudflare/cloudflared:latest
    container_name: invoice-cloudflared
    command: tunnel --no-autoupdate run --token ${TUNNEL_TOKEN}
    environment:
      TUNNEL_TOKEN: ${TUNNEL_TOKEN:?set TUNNEL_TOKEN in .env}
    restart: unless-stopped
    depends_on:
      - nginx
    # 无需 ports 暴露，纯出站连接 CF

volumes:
  backend-data:
  backend-logs:
  backend-db:
```

> **方案 B**：不在 compose 里跑 cloudflared，而是在宿主机用 systemd 运行 `cloudflared service install <token>`。两者二选一，配置同理。

### 2.3 `web/nginx.conf`（纯 HTTP，无 SSL）

```nginx
server {
    listen 80;
    server_name _;

    client_max_body_size 20m;

    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;  # 告诉后端外层是 HTTPS
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

> 注意：`X-Forwarded-Proto: https` 硬编码，因为 CF 边缘终止 TLS，后端需要知道外层是 HTTPS（用于生成正确的重定向/链接）。

### 2.4 `web/Dockerfile`（同 DNS 方案）

```dockerfile
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

### 2.5 根目录 `.env`（示例）

```env
# SQLite 是文件式数据库，无 DB 凭据。
LLM_API_KEY=sk-xxx
JWT_SECRET=change-me-32-chars-minimum!!!
ADMIN_PASSWORD=admin123
LLM_BASE_URL=https://opencode.ai/zen/v1
LLM_MODEL=oc/deepseek-v4-flash-free

# Tunnel 专用
TUNNEL_TOKEN=eyJhIjoi...your-tunnel-token...
```

---

## 3. 部署步骤（VPS）

```bash
# 1) 拉代码
cd /opt/invoice
git pull

# 2) 确认 .env 里有 TUNNEL_TOKEN
grep TUNNEL_TOKEN .env

# 3) 启动
docker compose up -d --build

# 4) 观察
docker compose logs -f cloudflared
docker compose logs -f nginx
docker compose logs -f backend
docker compose ps   # 全部 healthy，cloudflared 显示 "Connection established"
```

> `cloudflared` 启动后会建立到 CF 边缘的 4 条 QUIC 连接（默认），日志出现 `Registered tunnel connection` 即成功。

---

## 4. 自检清单

| 检查项 | 命令 | 预期 |
|---|---|---|
| Tunnel 连接 | `docker compose logs cloudflared \| grep -i "connection established"` | 有输出 |
| DNS 解析 | `dig +short invoice.bingyi.dpdns.org` | 返回 `*.cfargotunnel.com` CNAME |
| 公网访问 | `curl -I https://invoice.bingyi.dpdns.org` | 200，有 `cf-ray`、`server: cloudflare` |
| 后端健康 | `curl -H "Authorization: Bearer <token>" https://invoice.bingyi.dpdns.org/api/auth/ping` | 200 |
| 直连 VPS 8080 被拒 | `curl -v http://<VPS_IP>:8080` | 连接拒绝（只绑 127.0.0.1） |
| 登录/上传/列表 | 同 DNS 方案自检 | 全部通过 |

---

## 5. 入口对照表

| 场景 | URL | 说明 |
|---|---|---|
| **公网访问** | `https://invoice.bingyi.dpdns.org` | CF 边缘 443 → Tunnel (QUIC) → VPS localhost:8080 (nginx:80) |
| VPS 本地直连 | `curl http://127.0.0.1:8080/api/auth/ping` | 走 nginx HTTP，无需 `-k` |
| 本地开发 | `https://localhost:8088` | 见《本地开发统一配置》（mkcert + cf-allow.local.conf） |

---

## 6. 安全检查清单

- [ ] VPS **防火墙入站全关**（或仅允许 SSH 管理端口），cloudflared 纯出站
- [ ] `TUNNEL_TOKEN` 仅在 `.env`，**未提交 git**
- [ ] `JWT_SECRET` ≥ 32 字符，仅在 `.env`
- [ ] Cloudflare **SSL/TLS 模式 = Full (Strict)** 或 **Full**（Tunnel 场景 Full 也可，因 Tunnel 内部已加密）
- [ ] `cloudflared` 版本定期更新（`docker compose pull cloudflared && docker compose up -d cloudflared`）
- [ ] 禁用 Tunnel 的“Allow unauthenticated”（Zero Trust → Access → Applications，如有配置）

---

## 7. 故障排查

| 现象 | 排查 |
|---|---|
| 521 / 522 | Tunnel 未连接（`docker compose logs cloudflared`）、nginx 未监听 80、Tunnel 配置的 URL 不对（应为 `http://localhost:8080`） |
| 525 / 526 | 不适用（Tunnel 无源站证书概念） |
| 登录 401 | `JWT_SECRET` 不一致、token 过期、密码错误 |
| 上传 413 | `client_max_body_size` 未生效 |
| Tunnel 重连频繁 | VPS 网络抖动、CF 控制平面维护、检查 `cloudflared` 版本 |

---

## 8. 维护

- **Tunnel Token 轮换**：Zero Trust → Networks → Tunnels → Configure → Rotate token → 更新 `.env` → `docker compose up -d cloudflared`
- **cloudflared 升级**：`docker compose pull cloudflared && docker compose up -d cloudflared`
- **日志**：`docker compose logs -f --tail=200 cloudflared` / `nginx` / `backend`
- **备份**：同 DNS 方案（`mysqldump` 或数据卷备份）

---

## 9. 两种方案对比速查

| 维度 | DNS + Origin Rules + Origin Cert | Cloudflare Tunnel |
|---|---|---|
| **VPS 需公网 IP** | 是 | 否（仅需出站） |
| **防火墙入站** | 仅开 8088 给 CF IP | 全关（纯出站） |
| **端口限制** | 需 Origin Rules 绕过 8088 非代理端口 | 无限制（Tunnel 任意端口） |
| **证书管理** | 需上传/轮换 Origin Cert | 无需（CF 自动 TLS） |
| **SSL 模式** | 必须 Full (Strict) | Full / Full (Strict) 均可 |
| **延迟** | CF 边缘 → VPS 直连 | CF 边缘 → Tunnel (QUIC) → VPS |
| **运维复杂度** | 中（证书、IP 白名单、Origin Rules） | 低（仅 Token） |
| **适用场景** | 有公网 IP、想保留自有域名/端口控制 | 无公网 IP、NAT 后、不想管证书 |

---

> **选型建议**：
> - VPS 有公网 IP、想完全掌控 nginx/证书/端口 → **DNS 方案**（本项目当前主推）
> - VPS 无公网 IP、或在家庭宽带/公司内网、或不想碰防火墙/证书 → **Tunnel 方案**