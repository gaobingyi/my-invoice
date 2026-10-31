# 部署文档：DNS 方案（`invoice.bingyi.dpdns.org` + Origin Rules + Origin Cert）

> ⚠️ **2026-10-31 起 VPS 已去 Docker 化**（宿主 systemd + nginx，见 `docs/de-docker-plan.md`）。
> 本文的 CF DNS / Origin Rules / Origin Cert / AOP（§8.2）**仍准确有效**；
> 但 §4.2/4.5（compose/Dockerfile）、§5 部署步骤、§8.1（Docker 绕过 UFW）描述的是已退役的
> Docker 栈——8088 现回 INPUT 链由 UFW 直接管（CF 段 22 条 allow），backend 只绑 127.0.0.1。
> 当前日常运维见 `DEPLOY.md`。

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
├── .env                     # 根 .env：LLM_API_KEY、JWT_SECRET、ADMIN_PASSWORD（SQLite 无 DB 凭据）
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
      # 上传目录：与下方 backend-data 卷挂载点必须一致。
      # 故意硬编码 + 不放进 .env：避免"改了 UPLOAD_DIR 但卷没挂到新路径"导致
      # 文件落容器内 tmpfs 重启丢失、DB 留孤儿行。改路径时连 volume 一起改。
      # dev 本地用 ./uploads（yml 默认）；container 用 /app/uploads。
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

> **单一事实源是仓库里的 `web/nginx.conf`**（含 SNI 兜底 default_server、Host 校验、AOP mTLS 三层），此处不再内嵌副本以免漂移。要点：业务 server block 含 `ssl_client_certificate /etc/nginx/certs/cf-origin-pull-ca.pem` + `ssl_verify_client on`（Authenticated Origin Pulls，见 §8.2）。

### 4.4 `web/cf-allow.conf`

> **单一事实源是仓库里的 `web/cf-allow.conf`**（2026-10 起仅含 CF IPv4/IPv6 段 + deny all）。私网/回环放行已删除——AOP 把无 CF client 证书的连接（含 127.0.0.1、私网）拦在 400，IP 层私网条目已是死条目。

> **历史注意**：旧版此文件曾包含 `127.0.0.0/8` 与私网段供宿主机 `127.0.0.1:8088` 自检；AOP 启用后该自检路径已失效，自检一律走 `https://invoice.bingyi.dpdns.org`。

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
ADMIN_PASSWORD=admin123
LLM_BASE_URL=https://opencode.ai/zen/v1
LLM_MODEL=oc/deepseek-v4-flash-free
```

---

## 5. 部署步骤（VPS）

> ⚠️ **前置必读**：nginx 配置已改为 `listen 8088 ssl` 并依赖挂载的 `certs/origin.pem` + `certs/origin.key` + `web/cf-allow.conf`。**若 `certs/` 下没有证书，nginx 容器会立刻报 `cannot load certificate` 退出。** 必须先完成第 3 节（把 Cloudflare Origin Cert 存入 `certs/`）再启动。

```bash
# 1) 拉代码
cd /opt/invoice
git pull

# 2) 确认 certs/ 下有三份证书文件（缺 origin.* 回第 3 节、缺 CA 回 §8.2，否则 nginx 起不来）
ls -l certs/
# 预期看到:
#   certs/origin.pem
#   certs/origin.key            (权限 600)
#   certs/cf-origin-pull-ca.pem (AOP 公共 origin-pull CA，见 §8.2)

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
| AOP 生效（经 CF） | `curl -sD - -o /dev/null https://invoice.bingyi.dpdns.org/api/auth/ping` | 200（CF 出示 client 证书，mTLS 校验通过） |
| 无证书直连被拒 | `curl -vk --resolve invoice.bingyi.dpdns.org:8088:<VPS_PUBLIC_IP> https://invoice.bingyi.dpdns.org:8088/api/auth/ping` | 400（`ssl_verify_client on`，先于 cf-allow 的 IP 层） |
| 无 SNI 握手被拒 | `curl -vk https://<VPS_PUBLIC_IP>:8088/` | TLS 握手失败 `unrecognized name`（default_server `ssl_reject_handshake`） |
| 登录获取 token | `curl -X POST https://invoice.bingyi.dpdns.org/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` | 返回 `{token, username}` |
| 带 token 访问列表 | `curl -H "Authorization: Bearer <token>" https://invoice.bingyi.dpdns.org/api/invoices?page=0&size=10` | 200，返回分页数据 |
| 上传 PDF | `curl -H "Authorization: Bearer <token>" -F "file=@test.pdf" https://invoice.bingyi.dpdns.org/api/invoices/upload` | 201，返回发票信息 |

---

## 7. 入口对照表

| 场景 | URL | 说明 |
|---|---|---|
| **公网访问** | `https://invoice.bingyi.dpdns.org` | 443 → CF → Origin Rules (8088, HTTPS) → nginx:8088 |
| VPS 本地自检 | `curl -s https://invoice.bingyi.dpdns.org/api/auth/ping` | **必须走 CF 域名**：直连 127.0.0.1:8088 已被 SNI 兜底（拒握手）与 AOP（400）双重拦死，属预期 |
| 本地开发 | `https://localhost:8088` | 见《本地开发统一配置》文档（用 mkcert 自签 + cf-allow.local.conf） |

---

## 8. 安全检查清单

- [ ] Cloudflare **SSL/TLS 模式 = Full (Strict)**（Dashboard → SSL/TLS → Overview）
- [x] `cf-allow.conf` 仅含 CF IP 段（2026-10 起；私网/回环条目已随 AOP 启用删除）
- [x] nginx Host/SNI 兜底：`default_server` + `ssl_reject_handshake` + 业务 server 内 `if ($host ...)` 已就位（2026-10 加入，非法 SNI/Host 一律断连）
- [x] **Authenticated Origin Pulls 已启用**（Global 模式，`ssl_verify_client on`，见 §8.2）：无 CF client 证书的连接一律 400，含宿主机/本机直连——自检必须走 CF 域名
- [ ] `JWT_SECRET` ≥ 32 字符，已写入 `.env`，未提交 git
- [ ] `LLM_API_KEY`、`ADMIN_PASSWORD` 仅在 `.env`，未提交 git
- [ ] 定期 `docker compose pull && docker compose up -d --build` 更新基础镜像

### 8.1 已知事实：Docker 发布端口绕过 UFW，8088 防线在 nginx 层

UFW 的默认拒绝挡不住 Docker 发布端口：Docker 的 DNAT/FORWARD 规则插在 ufw 链之前，**8088 从公网 TCP 可达，UFW 放行列表管不着**（2026-10-31 经外部节点直连实测证实：TCP+TLS 握手均完成）。当前 8088 的实际防线全在 nginx 层（2026-10 全部落地）：SNI 错误/缺失 → default_server 直接拒握手（拿不到证书）；SNI 正确但无 CF client 证书 → **400（AOP，mTLS 密码学层，先于 IP 判断）**；Host 头不对 → h1.1 断连（444）/ h2 421；有 CF 证书但 IP 不在白名单 → `cf-allow.conf` 403。端口对公网 TCP 可达，但已无任何未认证数据面。

### 8.2 推荐加固：Authenticated Origin Pulls（mTLS）

一次配置、无需维护 CF IP 段，把信任锚从 IP 段升级为密码学验证，顺带让 `CF-Connecting-IP` 的可信性有 mTLS 保障。**已启用（2026-10，Global 模式）**，实际步骤留档：

1. **CF Dashboard**：SSL/TLS → Origin Server → Authenticated Origin Pulls → 打开 **Global** 开关（CF 用共享 client 证书回源）。Zone-level/Per-hostname 保持关闭——它们要求自备并上传 client 证书，「开着但无证书」是半配置空转态。
2. 下载 CF 公共 origin-pull CA（`https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem`，CN=`origin-pull.cloudflare.net`，有效期至 2029），存为 `certs/cf-origin-pull-ca.pem`（`certs/` 已只读挂载进容器，无需改 compose）。
3. `web/nginx.conf` 业务 server block 两行（已就位）：

   ```nginx
   ssl_client_certificate /etc/nginx/certs/cf-origin-pull-ca.pem;
   ssl_verify_client on;
   ```

4. 渐进上线手法（本次实际采用）：先 `ssl_verify_client optional` + 调试头 `add_header X-SSL-Client-Verify $ssl_client_verify always;` 部署，经 CF 观察到 `x-ssl-client-verify: SUCCESS`（确认 CF 确实在出示证书）后再切 `on`、删调试头——避免「先 on 后开开关」把回源打断。
5. 验证结果（2026-10-31 实测）：经 CF 200；无证书直连源站 403 → **400**；裸 IP/无 SNI 握手拒绝（`unrecognized name`）；容器间反代不受影响。

> 顺序警告：必须**先开 CF Global 开关**再切 `ssl_verify_client on`，否则 CF 回源全挂。回滚 = 删除那两行 + reload（CA 文件留存无副作用）。日后若改回 cloudflared Tunnel，**必须先关 AOP**——tunnel 出口不出示 client 证书，`on` 会把隧道流量全拒。

备选（不推荐）：DOCKER-USER 链只放行 CF IP 段到 8088：

```bash
iptables -I DOCKER-USER -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
ipset create cf4 hash:net family inet   # cf-allow.conf 里的 CF IPv4 段逐条 add 进去
# ... add cf4 <每段> ...
iptables -I DOCKER-USER -p tcp --dport 8088 -m set ! --match-set cf4 src -j DROP
```

需 systemd unit 持久化（重启即丢），且 CF IP 段要与 `cf-allow.conf` 双份维护。仅当 AOP 方案遇阻（如 CF 免费版功能受限）时再用。

---

## 9. 故障排查

| 现象 | 排查 |
|---|---|
| 521 / 522 | Origin Rules 未生效（检查表达式、端口 8088、Scheme HTTPS）、nginx 未监听 8088、证书路径错 |
| 525 / 526 | 源站证书过期/域名不匹配/私钥权限不对（`chmod 600 origin.key`） |
| 400 (ssl_verify_client) | 连接无 CF client 证书：直连源站/宿主机 curl 属预期拦截；经 CF 域名却 400 → CF Dashboard 的 Global AOP 开关被关了 |
| 403 (cf-allow) | 有证书但来源 IP 不在白名单：基本只见于 CF IP 段变更（定期同步 <https://www.cloudflare.com/ips/>） |
| 登录 401 | `JWT_SECRET` 不一致、token 过期、密码错误 |
| 上传 413 | `client_max_body_size` 未生效、或后端 `spring.servlet.multipart.max-file-size` 太小 |

---

## 10. 维护

- **证书续期**：CF Origin Cert 15 年一换，到期前在 Dashboard 重新生成，替换 `/opt/invoice/certs/`，`docker compose restart nginx`
- **CF IP 段更新**：优先改仓库 `web/cf-allow.conf` 后 scp + `nginx -s reload`；应急可直接 `curl https://www.cloudflare.com/ips-v4 > /opt/invoice/web/cf-allow.conf && curl https://www.cloudflare.com/ips-v6 >> /opt/invoice/web/cf-allow.conf && echo "deny all;" >> /opt/invoice/web/cf-allow.conf && docker exec invoice-nginx nginx -s reload`（该命令会抹掉文件头注释，功能等价）
- **日志**：`docker compose logs -f --tail=200 backend` / `nginx`
- **备份（异地 MEGA）**：跑仓库自带脚本 `scripts/backup-mega.sh`（每日 3 点 cron，见 `DEPLOY.md §7`）：`docker exec invoice-backend sqlite3 /app/data/invoice.db ".backup '/tmp/invoice-backup.db'"` 拿 SQLite 一致快照，连同上传文件卷打成单个 `invoice-backup-<时间>.tar.gz`，经 rclone 上传 MEGA 异地保存。