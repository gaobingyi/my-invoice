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
| 部署 | 单机 VPS（1C1G）宿主 **systemd + nginx**（2026-10-31 起，Docker 栈已彻底移除） |

## 快速开始

### 本地开发

**环境前置**（详见 [AGENTS.md](AGENTS.md)）：
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

---

## 生产部署（单机 VPS：systemd + 宿主 nginx）

> 生产机：103.232.94.248（1C1G，Ubuntu 24.04），代码在 `/opt/invoice`，数据在 `/opt/invoice/data/{db,uploads,logs}`。
> 深层架构与坑位说明看 [AGENTS.md](AGENTS.md)；本文是可照搬执行的操作手册。

### 架构

```
[浏览器] --HTTPS 443--> [Cloudflare 边缘] --Origin Pull 443→8088(mTLS)--> [宿主 nginx :8088] --/api--> [java :8080 仅 127.0.0.1]
                                                                              │
                                                                              ├─ 静态前端 /opt/invoice/web/dist
                                                                              ├─ SQLite /opt/invoice/data/db/invoice.db (WAL)
                                                                              ├─ 上传文件 /opt/invoice/data/uploads/
                                                                              └─ 日志 /opt/invoice/data/logs/（logback 30d/1GB 轮转）
```

进程 `invoice-backend.service`（`MemoryMax=512M`、`TimeoutStopSec=35` 优雅停机、`Restart=on-failure`）；nginx 站点 `/etc/nginx/sites-available/invoice`；8080 只绑回环，不对公网。

### 1. 服务器准备

```bash
apt install -y openjdk-25-jre-headless nginx sqlite3   # Ubuntu 24.04 源都有
```

- 源里 JRE 25.0.4，本地构建用 Temurin 25.0.5——同 25 LTS 无兼容问题。
- **不装 maven/node**：构建在本地做（下节）。若坚持 VPS 构建，需改装 `openjdk-25-jdk-headless`（JRE 无 javac，mvn 必失败）+ maven，且 noble 的 maven 3.8.7 × JDK 25 组合未验证。

### 2. 构建与发布（本地构建 → scp）

```bash
# 后端（jar 路径写死在 systemd 单元 ExecStart 里，pom 升版本需同步改单元）
cd server && mvn -B -DskipTests package
scp server/target/invoice-server-1.0.0.jar root@103.232.94.248:/opt/invoice/server/target/
ssh root@103.232.94.248 'systemctl restart invoice-backend'

# 前端（覆盖即生效，nginx 免重启）
cd web && npm run build
tar czf - dist | ssh root@103.232.94.248 'tar xzf - -C /opt/invoice/web/'
```

### 3. 环境变量（.env 与 .env.systemd）

- `/opt/invoice/.env` 是唯一事实源（gitignored，键见 `.env.example`：LLM_*/JWT_SECRET/ADMIN_*/WX_*）。
- systemd `EnvironmentFile` 不吃注释行，`.env` 变更后需重新生成纯净版：

```bash
grep -E '^[A-Z_]+=' /opt/invoice/.env > /opt/invoice/.env.systemd   # chmod 600
systemctl restart invoice-backend
```

- 单元内固定环境：`SPRING_DATASOURCE_URL`（WAL + `date_class=TEXT`）、`UPLOAD_DIR`、`LOGGING_FILE_NAME`、`TRUST_PROXY=true`、`TZ=Asia/Shanghai`、`SERVER_ADDRESS=127.0.0.1`、`JAVA_OPTS="-Xmx256m -Xms32m -XX:+UseSerialGC"`。
- **systemd 两个语法坑（都踩过）**：`Environment=` 含空格的值必须整体加引号，否则整行被拒（Invalid environment block）；`ExecStart` 用 `$JAVA_OPTS`（按空格拆参）而非 `${JAVA_OPTS}`（整个值变单个参数 → JVM 报 Invalid maximum heap size）。
- **nginx 版本坑**：宿主 noble nginx 是 1.24，不认 1.25.1 引入的 `http2 on;` 指令——`web/nginx.conf` 是给容器内 1.27 用的，直接拷去宿主会 unknown directive，须改成 `listen 8088 ssl http2;`。

### 4. 公网链路（Cloudflare）

五件套，全部就位后 `https://invoice.bingyi.dpdns.org` 可用：

| # | 配置点 | 要点 |
|---|---|---|
| 1 | **DNS A 记录** | `invoice` → VPS IP，**必须橙云代理**（否则 Origin Rules 不生效且真实 IP 暴露） |
| 2 | **Origin Rules** | Dashboard → Rules → Origin Rules：`Hostname equals invoice.bingyi.dpdns.org` → **Override origin port 8088 + Scheme HTTPS** |
| 3 | **Origin Cert** | SSL/TLS → Origin Server → Create Certificate（RSA 2048、15 年、域名 `invoice.bingyi.dpdns.org` + `*.bingyi.dpdns.org`）→ 证书存 `/etc/nginx/certs/origin.pem`、私钥 `/etc/nginx/certs/origin.key`（chmod 600） |
| 4 | **AOP（Authenticated Origin Pulls）** | SSL/TLS → Origin Server → 打开 **Global** 开关 → 下载 CF 公共 origin-pull CA（developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem，CN=origin-pull.cloudflare.net，2029 到期）存 `/etc/nginx/certs/cf-origin-pull-ca.pem` → nginx 站点两行：`ssl_client_certificate /etc/nginx/certs/cf-origin-pull-ca.pem;` + `ssl_verify_client on;` |
| 5 | **UFW 防火墙** | 8088 仅放行 CF IP 段（官方清单 https://www.cloudflare.com/ips-v4 / ips-v6，逐条 `ufw allow proto tcp from <cidr> to any port 8088`，现 22 条）；SSH 22 另放行。8080 无放行 |

- CF SSL/TLS 模式须为 **Full (Strict)**。
- **AOP 顺序警告**：必须先开 CF Global 开关再切 `ssl_verify_client on`，否则 CF 回源全挂。渐进上线手法：先 `optional` + 调试头 `add_header X-SSL-Client-Verify $ssl_client_verify always;` 观察 `SUCCESS` 后再切 `on`。
- nginx 源站双层闸门：① SNI/Host 兜底——`default_server` + `ssl_reject_handshake`，裸 IP/无 SNI 握手即拒；② AOP mTLS——无 CF client 证书的连接一律 400。日后若改回 cloudflared Tunnel，**必须先关 AOP 两行**（Tunnel 不出示 client 证书）。

### 5. 启动与验证

```bash
systemctl restart invoice-backend
curl -s http://127.0.0.1:8080/api/auth/ping                     # → 200
# 本机 HTTPS 检查（预期 400 = AOP 生效，不是故障；URL 必须带 :8088，--resolve 按端口匹配）
curl -sk --noproxy '*' --resolve invoice.bingyi.dpdns.org:8088:127.0.0.1 \
  https://invoice.bingyi.dpdns.org:8088/api/auth/ping -o /dev/null -w '%{http_code}\n'
curl -s https://invoice.bingyi.dpdns.org/api/auth/ping          # → 200（公网全链路）
```

浏览器登录 + 上传一张 PDF + 导出一次（POI/PDFBox 路径）即完成功能验证。

### 6. 故障排查

| 现象 | 排查 |
|---|---|
| 521 / 522 | Origin Rules 未生效（检查表达式、端口 8088、Scheme HTTPS）、nginx 未监听 8088、证书路径错 |
| 525 / 526 | 源站证书过期/域名不匹配/私钥权限不对（`chmod 600 origin.key`） |
| 400 (ssl_verify_client) | 直连源站/本机 curl 属预期拦截；**经 CF 域名却 400** → CF Dashboard 的 Global AOP 开关被关了 |
| 403 (cf-allow) | 有证书但来源 IP 不在白名单：基本只见于 CF IP 段变更（同步 https://www.cloudflare.com/ips/） |
| 403 且 UA 是脚本 | CF Bot 防护拦 Python-urllib 等 UA（curl 可过）：脚本请求带浏览器 UA |
| 登录 401 | `JWT_SECRET` 不一致、token 过期、密码错误 |
| 上传 413 | nginx `client_max_body_size` 未生效、或后端 `spring.servlet.multipart.max-file-size` 太小 |
| nginx 起不来 unknown directive `http2` | 宿主 nginx 1.24 不认 `http2 on;`，改 `listen 8088 ssl http2;`（见 §3） |

---

## 备份与恢复（MEGA 异地容灾）

脚本 `scripts/backup-mega.sh`：**每日 3:12**（cron，宿主机 UTC 时区）把 SQLite 一致快照 + 上传目录打成单个 `invoice-backup-<日期时间>.tar.gz`（包内 `db/invoice.db` + `uploads/uploads.tar.gz`），落 `/opt/backup/` 并经 rclone 上传 MEGA。日志 `/var/log/invoice-backup.log`。

### 一次性准备

1. 注册 MEGA 免费账号（约 20GB）。
2. 宿主机装 rclone 并 `rclone config` 新建 remote：名字 `invoice-mega`、类型 `mega`、填账号密码；验证 `rclone lsd invoice-mega:`。
3. cron（已在位）：

```cron
12 3 * * * /opt/invoice/scripts/backup-mega.sh >> /var/log/invoice-backup.log 2>&1
```

可调变量（默认见文件头）：`BACKUP_DIR=/opt/backup`、`MEGA_REMOTE=invoice-mega`、`MEGA_FOLDER=invoice-backup`、`RETENTION_DAYS=30`。改完脚本先手动跑通一次并解包验证 `sqlite3 … ".schema"` 可读。

**一致性说明**：DB 用 `sqlite3 .backup` 在线拿一致快照（WAL 下崩溃一致），绝不直接 tar DB 文件（复制到一半 WAL/SHM 会得到损坏副本）。

### 恢复演练（备份没验证=没备份）

```bash
# 1) 从 MEGA 拉最近一个包并解包
rclone copy invoice-mega:invoice-backup/ /opt/backup/restore/
cd /opt/backup/restore && tar xzf invoice-backup-<日期>.tar.gz

# 2) 停服务（防 SQLite 锁冲突）
systemctl stop invoice-backend

# 3) 恢复 DB 快照（清掉 WAL/SHM 残留，让新库干净落位）
cp /opt/backup/restore/db/invoice.db /opt/invoice/data/db/invoice.db
rm -f /opt/invoice/data/db/invoice.db-wal /opt/invoice/data/db/invoice.db-shm

# 4) 恢复上传文件（包内嵌套 tar 先解出再覆盖）
mkdir -p /tmp/uploads_extract && tar xzf /opt/backup/restore/uploads/uploads.tar.gz -C /tmp/uploads_extract
rm -rf /opt/invoice/data/uploads/* && cp -a /tmp/uploads_extract/. /opt/invoice/data/uploads/

# 5) 起服务并验证
systemctl start invoice-backend
curl -s http://127.0.0.1:8080/api/auth/ping
```

---

## 运维速查

```bash
systemctl status invoice-backend        # 状态（崩了 systemd 自动拉起）
journalctl -u invoice-backend -f        # 启动/异常日志
tail -f /opt/invoice/data/logs/invoice-server.log   # 应用日志（含 LLM fill）
systemctl restart invoice-backend       # 换 jar 后重启
nginx -t && systemctl reload nginx      # 改 nginx 配置后
free -m                                 # 内存余量
```

- **改 `.env`** → 重新生成 `.env.systemd`（见 §3）→ restart。
- **证书续期**：CF Origin Cert 15 年一换，到期前 Dashboard 重新生成替换 `/etc/nginx/certs/`，`systemctl reload nginx`。
- **CF IP 段更新**：改宿主 `/etc/nginx/cf-allow.conf`（格式 `allow <cidr>;`，末尾 `deny all;`）→ `nginx -t && systemctl reload nginx`；UFW 侧同步 `ufw allow/delete`。
- **内存**：java RSS 240~290MiB（随负载），1C1G 可跑；仍紧张时堆再压 `-Xmx192m` 或升 2C2G。

### 安全清单

- [ ] `.env` / `.env.systemd` `chmod 600`，强密码，未进仓库；`JWT_SECRET` ≥ 32 字符随机
- [ ] SQLite DB 不对公网（8080 只绑 127.0.0.1，UFW 亦无 8080 放行）
- [ ] UFW：8088 仅 CF IP 段；80/443 不放行（CF 代理走 8088）
- [ ] nginx AOP（`ssl_verify_client on`）+ SNI 兜底（`ssl_reject_handshake`）在位；CF SSL/TLS = Full (Strict)
- [ ] systemd `MemoryMax=512M`（防 OOM 错杀邻居）
- [ ] 定时备份 + MEGA 恢复演练完成

### 回滚到 Docker（已拆除，仅极端情况）

镜像/卷/Docker 包与仓库内的编排文件（`docker-compose.yml`、两个 Dockerfile）均已删除。需要回 Docker 时：

1. 重装 Docker：`curl -fsSL https://get.docker.com | sh`
2. 从 git 历史取回编排文件：`git checkout be65cca^ -- docker-compose.yml server/Dockerfile web/Dockerfile`（be65cca 是删除提交）
3. `cd /opt/invoice && docker compose build`，停 systemd 服务后把 `/opt/invoice/data/{db,uploads}` 拷回新卷，或直接从 MEGA 备份包恢复（见上文恢复演练）

---

## 解析原理

`InvoiceParser` 三条路径：
1. **正则快速路径**：PDFBox 抽文本后按值 pattern + 位置顺序提取（PDFBox 会把标签聚顶、值按文档序流出）。金额不锚定标签，而是解 `金额 + 税额 = 价税合计` 不变量
2. **字形坐标路径**：顺带收集字形坐标，专治「购/销两列压进同一物理行、文本序丢失列归属」的版式（按 x 坐标还原左右两列），取不到就保留正则结果
3. **LLM 兜底**：正则缺失字段时调 OpenAI 兼容服务补全，失败不中断上传

另对铁路电子客票（无销售方、票面仅一个「票价」值）有专用分支：票价即金额=价税合计、税额记 0，销售方固定为国铁集团，项目名称显示为行程「起点 → 终点」。

上传时另有业务校验：购买方必须是「上海钦钦印刷科技有限公司」（名称 + 信用代码双匹配），不符或解析不出直接拒绝。细节见 [AGENTS.md](AGENTS.md)「解析架构」「上传流程」。

## 项目结构

```
server/         后端（Spring Boot）
  src/          解析器 / 服务 / 控制器 / 实体
  ddl/schema.sql       SQLite 表结构（与 src/main/resources/schema.sql 内容一致）
web/            前端（Vue 3 + Vite）
  e2e/          浏览器端到端测试
miniprogram/    微信小程序（体验版）
scripts/        dev.sh 本地启停 / clean.sh 清库 / backup-mega.sh 异地备份
AGENTS.md       工作区说明（架构细节 / 坑位清单 / 约定）
```

`invoice_examples/`（样例发票 PDF，E2E 依赖）与 `certs/`（TLS 证书副本）已在 `.gitignore` 中，需自行准备。

> 历史部署文档（DEPLOY.md、docs/ 下四份）已于 2026-10-31 并入本 README，需要原文可翻 git 历史。

## 许可证

待补充
