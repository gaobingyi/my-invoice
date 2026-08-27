# 发票管理系统 · 云端部署指南

> 目标场景：**海外单机 VPS + Docker Compose**，个人/内部使用。
> 前置：已有可用的 `docker-compose.yml`（两服务：backend + nginx，全镜像化；数据库用 SQLite 文件内嵌于 backend）。

---

## 1. 部署架构

```
[浏览器] --HTTPS(443)--> [Caddy/nginx] --反向代理--> [nginx:8088 容器] --/api--> [backend:8080 容器]
                              │                                              │
                              └─ 静态前端 dist                               ├─ SQLite 文件 /app/data/invoice.db
                                                                              ├─ LLM 兜底 → https://opencode.ai/zen/v1
                                                                              └─ 命名卷: backend-data / backend-logs / backend-db
```

- 前端构建产物（`web/Dockerfile` 多阶段）与后端 jar（`server/Dockerfile`）都已进镜像
- 单机 `docker compose up --build` 一次拉起，不依赖宿主机预构建产物
- **JWT 登录认证**：`/api/auth/**` 公开（login/ping），其余 `/api/**` 需 `Authorization: Bearer <token>`；`/api/auth/ping` 供 healthcheck

---

## 2. 服务器准备

### 2.1 购买 VPS

| 项 | 建议 |
|---|---|
| 地域 | 海外（目标已定，海外云） |
| 规格 | **1C1G**（已调优，见 §8 附录；预计容器 ~260MiB + 系统 ~200MiB） |
| 系统 | Ubuntu 22.04/24.04 LTS |
| 存储 | 40GB SSD（镜像 + 卷 + 备份） |

> 1C1G 是下限。若预算允许，2C2G 更从容（Java 默认堆免调优）。切到 SQLite 后 MySQL 内存压力已消除，1C1G 跑得很从容。

### 2.2 SSH 登录

```bash
ssh root@<服务器IP>
# 或创建普通用户 + sudo，生产不建议 root 直连
```

### 2.3 安装 Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # 重新登录生效
```

### 2.4 安装 Caddy（HTTPS，推荐）

Caddy 自动申请/续期 Let's Encrypt 证书，比 nginx+certbot 省事：

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

---

## 3. 代码与配置上传

先把项目放到服务器，二选一：

### 3.0 方式一：源码直接部署（有源码网络）

服务器有完整源码（git clone / scp 整个仓库），`docker compose up --build` 就地构建，**不用预打包镜像**：

```bash
git clone <仓库地址> /opt/invoice   # 或 scp -r 整个项目到 /opt/invoice
cd /opt/invoice
cp .env.example .env                # 手动改各区强密码，见 §4.1
docker compose up -d --build        # 就地构建三镜像（maven/node 基础镜像需下载，网络慢会久）
docker compose ps
```

- 构建产物全在镜像内，宿主机无需 JDK / Node / Maven
- 云端首次需拉 `maven:3.9-eclipse-temurin-25` / `node:22-alpine` 两个构建镜像，海外直连通常 OK；国内若慢配镜像源
- 更新：拉新代码后 `docker compose up -d --build` 即可

> 若云端拉构建镜像慢，跳下方 **方式二**（本地预构建传输）。

### 3.1 方式二：本地构建镜像并传输（省云端拉取时间）

云端拉 `maven:3.9-eclipse-temurin-25` / `node:22-alpine` 基础镜像较慢，本地先构建好：

```bash
# 本地
docker compose build
docker save invoice-backend:1.0.0 invoice-web:1.0.0 | gzip > images.tar.gz
scp images.tar.gz root@<服务器IP>:/root/
```

```bash
# 云端
docker load < images.tar.gz
```

> 基础镜像（nginx/temurin/node）在云端 `docker compose up` 时按需拉取。SQLite 走 Xerial JDBC（已打进 backend 镜像），无独立 DB 镜像。

### 3.2 上传项目文件（仅方式二需要）

方式一源码已在服务器，此节跳过。方式二只需 3 个文件：

```bash
scp docker-compose.yml root@<服务器IP>:/opt/invoice/
scp server/ddl/schema.sql root@<服务器IP>:/opt/invoice/server/ddl/
scp web/nginx.conf root@<服务器IP>:/opt/invoice/web/
# .env 手动创建（含密码，勿 scp 明文传输，见下）
```

---

## 4. 环境变量配置

### 4.1 云端 `.env`（手动创建，chmod 600）

```bash
cd /opt/invoice
touch .env && chmod 600 .env
vi .env
```

```ini
# 云端 .env —— 勿提交，勿与本地 .env 混用
# SQLite 是文件式数据库，无 DB 凭据。
LLM_API_KEY=你的新 key
LLM_MODEL=big-pickle
LLM_BASE_URL=https://opencode.ai/zen/v1
# 登录认证：
JWT_SECRET=更换为随机强密钥
ADMIN_USERNAME=admin
ADMIN_PASSWORD=更换为强密码
```

**安全要求**：
- `JWT_SECRET` 必须设置，否则后端回退 dev 默认密钥（不安全）——用 `openssl rand -base64 48`
- `ADMIN_PASSWORD` 别用默认 `admin123`，生产必须改
- 云上 `chmod 600 .env`
- 本地/云端 `.env` 都是 gitignored，**绝不提交仓库**

### 4.2 与本地 `.env` 的区别

| 值 | 本地（根 `.env`） | 云端 `.env` |
|---|---|---|
| `LLM_BASE_URL` | `https://opencode.ai/zen/v1` | 相同（海外可达） |
| `LLM_API_KEY` | 你的 key | 同 key 或云上新 key |
| `JWT_SECRET` | 本地随机值（已在根 `.env`） | 云上新随机值（**两端不同**，改了 token 全失效可接受） |
| `ADMIN_PASSWORD` | `admin123`（开发） | 随机强密码 |

> 登录说明：默认管理员 `admin`，密码由 `ADMIN_PASSWORD` 指定（不设则 `admin123`）。JWT 24h 过期，前端 localStorage 存 token。

---

## 5. 公网访问与 HTTPS

> **本项目无域名**。推荐 **Cloudflare Tunnel**（免费、真 HTTPS、零端口暴露、无需 DNS A 记录）。备选方案见 5.4。

### 5.1 Cloudflare Tunnel（推荐，无域名首选）

原理：VPS 装 `cloudflared` 客户端 → 连 Cloudflare 边缘 → 边缘给一个 `*.trycloudflare.com` 随机子域或自定义子域。VPS **不需要公网开放端口**，安全组只需 22。

```bash
# 1. VPS 安装 cloudflared
curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared.deb

# 2. 快速通道（免注册，临时 URL，每次重启变）
cloudflared tunnel --url http://localhost:8088
# 输出: https://xxx-random-123.trycloudflare.com  ← 浏览器访问这个

# 3. 正式通道（固定域名，需注册 cloudflare + 添加站点）
cloudflared tunnel login
cloudflared tunnel create invoice
cloudflared tunnel route dns invoice your-subdomain.your-site.tld
cloudflared tunnel run --url http://localhost:8088 invoice
# 或写 /etc/cloudflared/config.yml 用 systemd 常驻
```

**优点**：自动 HTTPS、免公网端口、隐藏 VPS IP。**缺点**：走 Cloudflare 边缘，国内访问慢/不稳（本项目目标海外，无碍）。

> 无 Cloudflare 账号？用快速通道（第 2 步）即可先跑起来，URL 随机但 HTTPS 加密可用。

### 5.2 有域名：Caddy（备选）

DNS：域名 A 记录 → VPS IP。`/etc/caddy/Caddyfile`：

```
invoice.example.com {
    reverse_proxy localhost:8088
}
```

```bash
sudo systemctl restart caddy
```

自动申请证书，`https://invoice.example.com` 可用。端口 80/443 在安全组放行。

### 5.3 什么都没有：IP + HTTP/自签

```bash
# 直接访问，无加密（公网 IP 明文传发票数据，个人临时可用，不建议长期）
http://<VPS-IP>:8088
```

或 Caddy 自签（有加密但有浏览器警告）：

```
:443 {
    tls internal
    reverse_proxy localhost:8088
}
```

---

## 6. 启动与验证

```bash
cd /opt/invoice
docker compose up -d
docker compose ps                 # 三个服务应 healthy
curl -s http://localhost:8088/api/auth/ping   # → pong（公开探活）
# 业务端点已 401 保护：先登录拿 token 再访问
TOKEN=$(curl -s -X POST http://localhost:8088/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://localhost:8088/api/invoices -H "Authorization: Bearer $TOKEN"   # → {"content":[],...}
```

浏览器访问：
- 无域名 → Cloudflare Tunnel 给的 `https://xxx.trycloudflare.com`（5.1）
- 有域名 → `https://invoice.example.com`（5.2）

验证项：
1. 浏览器访问 → 跳 `/login` → 用 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 登录成功
2. 列表页正常渲染
3. 上传样例 PDF → 解析 → 入库 → 列表出现
4. 预览/下载/删除可用

**验证 LLM 兜底**（可选）：上传一张正则解析不全的发票，`docker compose logs backend | grep "LLM fill"` 应有调用记录。

---

## 7. 数据备份（必须）——单包打包 + Cloudflare R2 异地容灾

备份脚本 `scripts/backup-r2.sh`：**每日 3 点**把当前 SQLite DB 一致快照 + 上传文件卷打成**单个 `invoice-backup-<日期时间>.tar.gz`**（包内 `db/invoice.db` + `uploads/uploads.tar.gz`），落本地 `/opt/backup/` 并经 rclone 上传 R2 作**异地**保存（VPS 挂掉备份仍在）。

### 7.1 一次性准备：R2 + rclone

1. **R2 bucket**：Cloudflare Dashboard → R2 → Create bucket，命名 `invoice-backup`（可绑自定义域名用于公网校验，不绑也行）。创建 R2 API Token（权限读写），得 `account_id` / `access_key_id` / `access_key_secret`。
2. **宿主机装 rclone 并配 remote**：
   ```bash
   curl https://rclone.org/install.sh | sudo bash
   rclone config            # 新建 S3 remote，provider 选 Cloudflare，填上面的三组值
   ```
   配置落在宿主机 `~/.config/rclone/rclone.conf`（`chmod 600`），**只进宿主机，不进 .env / 仓库**。后续脚本里 remote 名默认 `invoice-r2`。

### 7.2 定时备份（cron）

把 `scripts/backup-r2.sh` 部署到 VPS（`git pull` 或 `scp`）到 `/opt/invoice/scripts/`，然后宿主机 `crontab -e` 追加：

```cron
# 每天 3:12（错峰）：单包备份 DB+uploads → 本地 /opt/backup/ + 上传 R2 异地
12 3 * * * /opt/invoice/scripts/backup-r2.sh >> /var/log/invoice-backup.log 2>&1
```

脚本可调变量（默认值见文件头）：`BACKUP_DIR=/opt/backup`、`R2_REMOTE=invoice-r2`、`R2_BUCKET=invoice-backup`、`RETENTION_DAYS=30`。先手动 `bash /opt/invoice/scripts/backup-r2.sh` 跑通一次再挂 cron。

**一致性说明**：DB 用 `sqlite3 .backup` 在线拿一致快照（WAL 模式下取到崩溃一致的数据库），再与 uploads 同包打包——两者在分钟级时间窗内对齐，业务上可接受；不在容器卷内直接 tar DB，避免复制到一半 WAL/SHM 得出损坏的第二份。

### 7.3 保留策略

```bash
# 本地：脚本每次跑完自动清 N 天前旧包（默认 30）
find /opt/backup -name 'invoice-backup-*.tar.gz' -mtime +30 -delete
# R2 远端：脚本用 `rclone delete --min-age 30d` 清；或改在 R2 控制台配 Lifecycle Rule 按前缀过期
```

### 7.4 恢复演练（重要，备份没验证=没备份）

从 R2 拉最近一个包，落到 `/opt/backup/restore/` 解包：

```bash
# 1) 从 R2 拉包
rclone copy invoice-r2:invoice-backup/ /opt/backup/restore/
# 2) 解包
tar xzf /opt/backup/restore/invoice-backup-2026-08-11-0312.tar.gz -C /opt/backup/restore/

# 3) 停 backend（防 SQLite 锁冲突），在 compose 目录执行
cd /opt/invoice && docker compose stop backend

# 4) 恢复 DB 快照进 backend-db 命名卷
docker cp /opt/backup/restore/db/invoice.db invoice-backend:/app/data/invoice.db
# 如有 WAL/SHM 残留，一并清掉
docker exec invoice-backend sh -c 'rm -f /app/data/invoice.db-wal /app/data/invoice.db-shm'

# 5) 恢复上传文件进 backend-data 命名卷
#    包内 uploads/uploads.tar.gz 是上传文件卷的嵌套 tar，先解出再覆盖进数据卷
mkdir -p /opt/backup/restore/uploads_extract
tar xzf /opt/backup/restore/uploads/uploads.tar.gz -C /opt/backup/restore/uploads_extract
docker run --rm \
  -v /opt/backup/restore/uploads_extract:/in \
  -v invoice-manager_backend-data:/data \
  alpine sh -c 'rm -rf /data/* && cp -a /in/* /data/'

# 6) 起 backend
docker compose start backend
```

---

## 8. 运维命令速查

```bash
docker compose ps                    # 状态
docker compose logs -f backend       # 后端日志（含 LLM fill）
docker compose restart backend       # 重启单服务
docker compose up -d --build         # 更新（改代码后）
docker compose down -v               # 停+清卷（⚠️ 删数据，先备份）
```

**更新部署流程**（与 §3 两种方式对应）：
```bash
# 方式一（源码在服务器）：拉新代码后就地重建
cd /opt/invoice && git pull && docker compose up -d --build

# 方式二（本地打包传输）：
#   本地
docker compose build && docker save invoice-backend:1.0.0 invoice-web:1.0.0 | gzip > images.tar.gz
scp images.tar.gz root@<服务器IP>:/root/
#   云端
docker load < images.tar.gz && cd /opt/invoice && docker compose up -d
```

> 两种方式共用同一个 `schema.sql`（首次启动由 `spring.sql.init` 自动执行）与命名卷，切换方式不影响已有数据。

---

## 9. 安全清单（部署前逐项确认）

- [ ] `.env` `chmod 600`，强密码，未进仓库
- [ ] `JWT_SECRET` 已设强随机值（未设 = dev 默认密钥，可伪造 token）
- [ ] `ADMIN_PASSWORD` 已改（未改 = `admin123`，可被猜）
- [ ] SQLite DB 文件**未**暴露公网（compose 无 `backend-db` 卷挂到公网路径；DB 在容器内 `/app/data/`）
- [ ] HTTPS 已启用（Cloudflare Tunnel 或 Caddy）
- [ ] backend/nginx 有 `restart: unless-stopped`
- [ ] 容器加 `TZ=Asia/Shanghai`（否则 `created_at` 差 8 小时）
- [ ] 定时备份 + R2 恢复演练完成（§7，`backup-r2.sh` + cron + 一次真实恢复）
- [ ] 防火墙/安全组：Cloudflare Tunnel 只放行 22；Caddy 方案放行 22/80/443

---

## 10. 已知局限

- **单机无高可用**：VPS 宕机即服务中断。个人/内部用可接受；需 HA 则上 K8s（超出本文范围）。
- **LLM 依赖外网**：`opencode.ai` 不可达时 LLM 兜底失败，但上传不中断（null 保留）。
- **备份已异地（R2）**：§7 已把每日备份上传 Cloudflare R2，VPS 整体宕机也能从 R2 恢复。局限是备份**按天**粒度（最多丢一天数据）；若需更高 RPO 可加密到小时级别。

---

## 附录：1C1G 内存调优（已在 compose 内）

`docker-compose.yml` 已内置以下调优，无需手动改：

```yaml
backend:
  environment:
    - TZ=Asia/Shanghai
    - JAVA_OPTS=-Xmx256m -Xms128m -XX:+UseSerialGC -XX:MaxRAMPercentage=50
```

| 调优项 | 值 | 效果 |
|---|---|---|
| JVM 堆 | `-Xmx256m` | Java 峰值内存从 ~500MB 压到 ~250MB |
| GC | `UseSerialGC` | 单核下比默认 G1 更省内存 |

> `JAVA_OPTS` 经 `server/Dockerfile` 的 `ENTRYPOINT ["sh","-c","java $JAVA_OPTS ..."]` 传入。改堆参数只需改 compose 的 `JAVA_OPTS`，无需重建镜像（Dockerfile 已支持 env 展开）。

切到 SQLite 后已无独立 DB 进程。**预计部署后内存**：backend ~250MiB + nginx ~10MiB ≈ **260MiB**，系统留 ~740MiB。1C1G 跑得很从容。

**实测监控**：
```bash
docker stats --no-stream    # 看容器内存
free -m                     # 看系统内存余量
```

**仍紧张时**：JVM 堆再压到 `-Xmx192m`；或升 2C2G（最省心）。
