# 部署文档：DNS 方案（`invoice.bingyi.dpdns.org` + Origin Rules + Origin Cert）

> 适用场景：VPS 有公网 IP，域名托管在 Cloudflare，想保留 8088 端口（不在 CF 代理端口列表），且使用 **Full (Strict) TLS**（CF 与源站双向验证证书）。

---

## 0. 前置条件

| 项 | 要求 |
|---|---|
| VPS | Linux，已安装 Docker + Docker Compose v2 |
| 域名 | `invoice.bingyi.dpdns.org` 在 Cloudflare 托管 |
| 证书 | Cloudflare Origin Certificate（由 CF 签发，仅 CF 信任） |
| 端口 | VPS **8088** 仅对 Cloudflare IP 段开放（其它 IP 拒绝） |
| 认证 | JWT（登录 `/api/auth/login` 获取 token，后续请求带 `Authorization: Bearer <token>`） |

---

## 1. Cloudflare DNS 记录

| 类型 | 名称 | 内容 | 代理状态 | TTL |
|---|---|---|---|---|
| A | invoice | `<VPS_PUBLIC_IP>` | **Proxied（橙云）** | Auto |

> **必须开启代理**，否则 Origin Rules 不生效，且真实 IP 暴露。

---

## 2. Cloudflare Origin Rules（关键：把 443 → 8088，且走 HTTPS）

> Free/Pro/Business/Enterprise 均可用。**Dashboard → Rules → Origin Rules → Create rule**

| 字段 | 值 |
|---|---|
| Rule name | `invoice-origin-port-8088` |
| **When incoming requests match** | |
| Field | `Hostname` |
| Operator | `equals` |
| Value | `invoice.bingyi.dpdns.org` |
| **Then** → **Override origin port** | `8088` |
| **Then** → **Scheme** | `HTTPS` |
| Expression（等价） | `http.host == "invoice.bingyi.dpdns.org"` |

> 验证：`curl -I https://invoice.bingyi.dpdns.org` → `cf-ray` 头存在，且后端收到 `X-Forwarded-Proto: https`。

---

## 3. 源站 HTTPS：Cloudflare Origin Certificate

### 3.1 生成证书（在 Cloudflare Dashboard）

1. **SSL/TLS → Origin Server → Create Certificate**
2. 选 **RSA (2048)**、**有效期 15 年**（或自定义）、**Key Type: RSA**
3. 域名填：`invoice.bingyi.dpdns.org`、`*.bingyi.dpdns.org`
4. 得到两段 PEM：`origin.pem`（证书链）、`origin.key`（私钥）

### 3.2 保存证书到 VPS 项目目录

进入 VPS 上的项目根目录（例如 `/opt/invoice`），在 `docker-compose.yml` 同级创建 `certs` 目录并将两段内容存入：

```bash
# 1. 进入项目根目录并创建 certs 文件夹
cd /opt/invoice  # 替换为你 VPS 上的实际项目路径
mkdir -p certs

# 2. 保存 Origin Certificate 内容为 certs/origin.pem
nano certs/origin.pem
# (把 Cloudflare 页面上的 Origin Certificate 文本粘贴进去保存)

# 3. 保存 Private Key 内容为 certs/origin.key
nano certs/origin.key
# (把 Cloudflare 页面上的 Private Key 文本粘贴进去保存)

# 4. 设置私钥安全权限
chmod 600 certs/origin.key
```

> **原理解释**：`docker-compose.yml` 中配置了 `./certs:/etc/nginx/certs:ro` 挂载，只要 `certs/` 放在 `docker-compose.yml` 同级目录下，Nginx 容器启动时就会自动读取。

---

## 4. VPS 部署文件

### 4.1 目录结构

```
/opt/invoice/
├── docker-compose.yml
├── .env                     # 根 .env：LLM_API_KEY、JWT_SECRET、APP_ADMIN_PASSWORD（SQLite 无 DB 凭据）
├── web/
│   ├── nginx.conf
│   ├── cf-allow.conf        # 允许 CF IP 段与本地私网网段
│   └── Dockerfile
└── certs/
    ├── origin.pem
    └── origin.key
```

### 4.2 `docker-compose.yml`

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
      # SQLite 是文件式 DB，文件由命名卷 backend-db 持久化。
      # date_class=TEXT：Xerial JDBC 默认按 INTEGER (epoch ms) 存 timestamp，
      # 与读侧 ISO 解析错位导致 ParseException。强制 TEXT 让读写都用 ISO-8601。
      SPRING_DATASOURCE_URL: jdbc:sqlite:/app/data/invoice.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&date_class=TEXT
      APP_UPLOAD_DIR: /app/uploads
      LOGGING_FILE_NAME: /app/logs/invoice-server.log
      APP_LLM_ENABLED: "true"
      APP_LLM_BASE_URL: ${APP_LLM_BASE_URL:-https://opencode.ai/zen/v1}
      APP_LLM_MODEL: ${APP_LLM_MODEL:-oc/deepseek-v4-flash-free}
      LLM_API_KEY: ${LLM_API_KEY:-}
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET in .env}
      APP_ADMIN_USERNAME: ${APP_ADMIN_USERNAME:-admin}
      APP_ADMIN_PASSWORD: ${APP_ADMIN_PASSWORD:?set APP_ADMIN_PASSWORD in .env}
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
      - "8088:8088"
    volumes:
      - ./web/nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./web/cf-allow.conf:/etc/nginx/cf-allow.conf:ro
      - ./certs:/etc/nginx/certs:ro
    restart: unless-stopped

volumes:
  backend-data:
  backend-logs:
  backend-db:
```

### 4.3 `web/nginx.conf`

```nginx
# 包含 Cloudflare IP 白名单及本地网段放行
include /etc/nginx/cf-allow.conf;

server {
    listen 8088 ssl;
    http2 on;
    server_name invoice.bingyi.dpdns.org;

    ssl_certificate /etc/nginx/certs/origin.pem;
    ssl_certificate_key /etc/nginx/certs/origin.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    client_max_body_size 20m;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;
    }
}
```

### 4.4 `web/cf-allow.conf`（Cloudflare IP 段 + 本地/Docker 私网放行）

```nginx
# 回环与 Docker 私网网段（供宿主机 curl 自检与健康检查，防止 403）
allow 127.0.0.0/8;
allow 172.16.0.0/12;
allow 10.0.0.0/8;
allow 192.168.0.0/16;

# Cloudflare IPv4
allow 173.245.48.0/20;
allow 103.21.244.0/22;
allow 103.22.200.0/22;
allow 103.31.4.0/22;
allow 141.101.64.0/18;
allow 108.162.192.0/18;
allow 190.93.240.0/20;
allow 188.114.96.0/20;
allow 197.234.240.0/22;
allow 198.41.128.0/17;
allow 162.158.0.0/15;
allow 104.16.0.0/13;
allow 104.24.0.0/14;
allow 172.64.0.0/13;
allow 131.0.72.0/22;

# Cloudflare IPv6
allow 2400:cb00::/32;
allow 2606:4700::/32;
allow 2803:f800::/32;
allow 2405:b500::/32;
allow 2405:8100::/32;
allow 2a06:98c0::/29;
allow 2c0f:f248::/32;

deny all;
```

> **注意**：此处包含了 `127.0.0.0/8` 与 `172.16.0.0/12`，确保 VPS 宿主机能通过 `127.0.0.1:8088` 自检以及 Docker 内部通信不受阻。公网非 CF IP 依然会被 `deny all` 阻断。

### 4.5 `web/Dockerfile`

```dockerfile
# Build stage
FROM node:22-alpine AS build
WORKDIR /build
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# Runtime stage
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
# nginx.conf / cf-allow.conf / certs 由 docker-compose 挂载覆盖
EXPOSE 8088
CMD ["nginx", "-g", "daemon off;"]
```

### 4.6 根目录 `.env`（示例）

```env
# SQLite 是文件式数据库，无 DB 凭据。
LLM_API_KEY=sk-xxx
JWT_SECRET=change-me-32-chars-minimum!!!
APP_ADMIN_PASSWORD=admin123
APP_LLM_BASE_URL=https://opencode.ai/zen/v1
APP_LLM_MODEL=oc/deepseek-v4-flash-free
```

---

## 5. 部署步骤（VPS）

> ⚠️ **前置必读**：nginx 配置已改为 `listen 8088 ssl` 并依赖挂载的 `certs/origin.pem` + `certs/origin.key` + `web/cf-allow.conf`。**若 `certs/` 下没有证书，nginx 容器会立刻报 `cannot load certificate` 退出。** 必须先完成第 3 节（把 Cloudflare Origin Cert 存入 `certs/`）再启动。

```bash
# 1) 拉代码
cd /opt/invoice
git pull

# 2) 确认 certs/ 下有 origin.pem / origin.key（无则回第 3 节补齐，否则 nginx 起不来）
ls -l certs/
# 预期看到:
#   certs/origin.pem
#   certs/origin.key   (权限 600)

# 3) 启动
docker compose up -d --build

# 4) 观察
docker compose logs -f nginx      # 出现 [emerg] cannot load certificate 说明证书缺失，回第 3 节
docker compose logs -f backend
docker compose ps   # 全部 healthy
```

---

## 6. 自检清单

| 检查项 | 命令 | 预期 |
|---|---|---|
| DNS 解析 | `dig +short invoice.bingyi.dpdns.org` | 返回 CF IP（非 VPS 真实 IP） |
| CF 代理生效 | `curl -I https://invoice.bingyi.dpdns.org` | 有 `cf-ray`、`server: cloudflare` |
| Origin Rules 生效 | `curl -vk https://invoice.bingyi.dpdns.org/api/auth/ping` | 200，后端日志显示 `X-Forwarded-Proto: https` |
| 源站证书验证 | `curl -vk https://127.0.0.1:8088/api/auth/ping` | 200，证书 Subject 包含 `invoice.bingyi.dpdns.org` |
| 非 CF 公网 IP 被拦截 | `curl -vk https://<VPS_PUBLIC_IP>:8088/api/auth/ping` | 403（cf-allow.conf 生效） |
| 登录获取 token | `curl -X POST https://invoice.bingyi.dpdns.org/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` | 返回 `{token, username}` |
| 带 token 访问列表 | `curl -H "Authorization: Bearer <token>" https://invoice.bingyi.dpdns.org/api/invoices?page=0&size=10` | 200，返回分页数据 |
| 上传 PDF | `curl -H "Authorization: Bearer <token>" -F "file=@test.pdf" https://invoice.bingyi.dpdns.org/api/invoices/upload` | 201，返回发票信息 |

---

## 7. 入口对照表

| 场景 | URL | 说明 |
|---|---|---|
| **公网访问** | `https://invoice.bingyi.dpdns.org` | 443 → CF → Origin Rules (8088, HTTPS) → nginx:8088 |
| VPS 本地直连 | `curl -k https://127.0.0.1:8088/api/auth/ping` | 走源站证书，需 `-k`（CF Origin Cert 不被系统信任） |
| 本地开发 | `https://localhost:8088` | 见《本地开发统一配置》文档（用 mkcert 自签 + cf-allow.local.conf） |

---

## 8. 安全检查清单

- [ ] Cloudflare **SSL/TLS 模式 = Full (Strict)**（Dashboard → SSL/TLS → Overview）
- [ ] `cf-allow.conf` 仅含 CF IP 与私网/回环段，阻断外网直连
- [ ] VPS 防火墙（ufw/iptables/security group）**仅对 CF IP 开放 8088**，其它拒绝
- [ ] `JWT_SECRET` ≥ 32 字符，已写入 `.env`，未提交 git
- [ ] `LLM_API_KEY`、`APP_ADMIN_PASSWORD` 仅在 `.env`，未提交 git
- [ ] 定期 `docker compose pull && docker compose up -d --build` 更新基础镜像

---

## 9. 故障排查

| 现象 | 排查 |
|---|---|
| 521 / 522 | Origin Rules 未生效（检查表达式、端口 8088、Scheme HTTPS）、nginx 未监听 8088、证书路径错 |
| 525 / 526 | 源站证书过期/域名不匹配/私钥权限不对（`chmod 600 origin.key`） |
| 403 (cf-allow) | 请求未走 CF（直连 VPS 公网 IP）、或 CF IP 段变更（定期同步 <https://www.cloudflare.com/ips/>） |
| 登录 401 | `JWT_SECRET` 不一致、token 过期、密码错误 |
| 上传 413 | `client_max_body_size` 未生效、或后端 `spring.servlet.multipart.max-file-size` 太小 |

---

## 10. 维护

- **证书续期**：CF Origin Cert 15 年一换，到期前在 Dashboard 重新生成，替换 `/opt/invoice/certs/`，`docker compose restart nginx`
- **CF IP 段更新**：`curl https://www.cloudflare.com/ips-v4 > /opt/invoice/web/cf-allow.conf && curl https://www.cloudflare.com/ips-v6 >> /opt/invoice/web/cf-allow.conf && echo "deny all;" >> /opt/invoice/web/cf-allow.conf && docker compose restart nginx`
- **日志**：`docker compose logs -f --tail=200 backend` / `nginx`
- **备份**：SQLite 在线（生产建议 backend 镜像加装 `sqlite3` CLI）：`docker exec invoice-backend sqlite3 /app/data/invoice.db ".backup '/app/data/backup-$(date +%F).db'" && docker cp invoice-backend:/app/data/backup-$(date +%F).db /opt/backup/`。上传文件卷：`docker run --rm -v invoice-manager_backend-data:/data -v /opt/backup:/backup alpine tar czf /backup/uploads-$(date +%F).tar.gz /data`