# 发票管理系统 · 云端部署指南

> 当前部署形态（**2026-10-31 去 Docker 化**）：宿主机 **systemd + nginx + 本地构建 scp**，
> 单机 VPS（103.232.94.248，1C1G）。
> 公网链路（CF DNS / Origin Rules / Origin Cert / AOP mTLS）见 `docs/deploy-dns.md`；
> 迁移过程与回滚路径见 `docs/de-docker-plan.md`。
> `docker-compose.yml` 与两个 Dockerfile 保留在仓库仅作**回滚保险**，日常不再使用。

---

## 1. 部署架构

```
[浏览器] --HTTPS 443--> [Cloudflare 边缘] --Origin Pull 443→8088(mTLS)--> [宿主 nginx :8088] --/api--> [java :8080 仅 127.0.0.1]
                                                                              │
                                                                              ├─ 静态前端 /opt/invoice/web/dist
                                                                              ├─ SQLite /opt/invoice/data/db/invoice.db (WAL)
                                                                              ├─ 上传文件 /opt/invoice/data/uploads/
                                                                              └─ 日志 /opt/invoice/data/logs/（logback 30d/1GB 轮转）
```

- 进程：systemd 单元 `invoice-backend.service`（`User=root`、`MemoryMax=512M`、`TimeoutStopSec=35` 优雅停机、`Restart=on-failure`）。健康检查由 systemd 天然承担（进程死即拉起）；`/api/auth/ping` 仍公开可用。
- nginx：`/etc/nginx/sites-available/invoice`（源自 `web/nginx.conf` 按 `de-docker-plan.md` §3.3 四处修改）+ `/etc/nginx/cf-allow.conf` + `/etc/nginx/certs/` 三份证书。改配置 `nginx -t && systemctl reload nginx`。
- 8080 只绑回环（单元内 `SERVER_ADDRESS=127.0.0.1`），不对公网。

## 2. 服务器准备

```bash
apt install -y openjdk-25-jre-headless nginx sqlite3   # Ubuntu 24.04 源都有
```

- 源里 JRE 25.0.4，本地构建用 Temurin 25.0.5——同 25 LTS 无兼容问题。
- **不装 maven/node**：构建在本地做，产物 scp（§3）。若坚持 VPS 构建，需 `openjdk-25-jdk-headless`（JRE 无 javac）+ maven，且 noble maven 3.8.7 × JDK 25 组合未验证。

## 3. 构建与发布（本地构建 → scp）

```bash
# 后端（jar 路径写死在 systemd 单元里，pom 升版本需同步改单元 ExecStart）
cd server && mvn -B -DskipTests package
scp server/target/invoice-server-1.0.0.jar root@103.232.94.248:/opt/invoice/server/target/

# 前端
cd web && npm run build
tar czf - dist | ssh root@103.232.94.248 'tar xzf - -C /opt/invoice/web/'   # 覆盖即生效，nginx 免重启
```

## 4. 环境变量

- `/opt/invoice/.env` 是唯一事实源（gitignored，键见 `.env.example`；LLM_*/JWT_SECRET/ADMIN_*/WX_*）。
- systemd `EnvironmentFile` 不吃注释行，随 `.env` 变更需重新生成纯净版：

```bash
grep -E '^[A-Z_]+=' /opt/invoice/.env > /opt/invoice/.env.systemd   # chmod 600
systemctl restart invoice-backend
```

- 单元内固定不变的环境（见 `/etc/systemd/system/invoice-backend.service`）：`SPRING_DATASOURCE_URL`（WAL + `date_class=TEXT`）、`UPLOAD_DIR`、`LOGGING_FILE_NAME`、`TRUST_PROXY=true`、`TZ=Asia/Shanghai`、`SERVER_ADDRESS=127.0.0.1`、`JAVA_OPTS="-Xmx256m -Xms32m -XX:+UseSerialGC"`。
- systemd 两个语法坑（已踩过）：`Environment=` 含空格的值必须整体加引号；`ExecStart` 里要用 `$JAVA_OPTS`（按空格拆参）而非 `${JAVA_OPTS}`（整个值变单个参数 → JVM 报 Invalid maximum heap size）。

## 5. 公网访问与 HTTPS

见 `docs/deploy-dns.md`：CF DNS（橙云代理）+ Origin Rules 443→8088 + Origin Cert + **Authenticated Origin Pulls**（mTLS）。防火墙为 UFW：8088 仅放行 CF IP 段（22 条 allow 规则，清单源自 `/etc/docker-user-cloudflare-8088/cloudflare-v{4,6}.txt`），其余入站默认拒绝（22/8388 除外）。

自检注意：
- 本机 `curl --resolve` 探 8088 **URL 必须带 `:8088`**（--resolve 按端口匹配），且**预期 400**（AOP 对无 client 证书的连接按设计回 400）；裸 IP 不发 SNI → 握手直接被拒。
- 一切功能验证走 `https://invoice.bingyi.dpdns.org`。

## 6. 启动与验证

```bash
systemctl restart invoice-backend
curl -s http://127.0.0.1:8080/api/auth/ping                     # → 200
curl -sk --noproxy '*' --resolve invoice.bingyi.dpdns.org:8088:127.0.0.1 \
  https://invoice.bingyi.dpdns.org:8088/api/auth/ping -o /dev/null -w '%{http_code}\n'   # → 400（AOP 生效）
curl -s https://invoice.bingyi.dpdns.org/api/auth/ping          # → 200（公网全链路）
```

浏览器登录 + 上传一张 PDF + 导出一次（POI/PDFBox 路径）即完成功能验证。迁移日做过一次删除→重传闭环与批次 ZIP 下载验证，字段完全一致（2026-10-31）。

## 7. 数据备份（必须）——单包打包 + MEGA 异地容灾

备份脚本 `scripts/backup-mega.sh`：**每日 3:12**（cron，宿主机 UTC 时区）把 SQLite 一致快照 + 上传目录打成单个 `invoice-backup-<日期时间>.tar.gz`（包内 `db/invoice.db` + `uploads/uploads.tar.gz`），落 `/opt/backup/` 并经 rclone 上传 MEGA 异地保存。MEGA remote `invoice-mega` 已配置。

### 7.1 一次性准备：MEGA + rclone

1. 注册 MEGA 免费账号（约 20GB）。
2. 宿主机 `rclone config` 新建 remote：名字 `invoice-mega`、类型 `mega`、填账号密码。验证 `rclone lsd invoice-mega:`。

### 7.2 定时备份（cron）

脚本随仓库部署到 `/opt/invoice/scripts/`（`git pull` 同步），cron（已在位）：

```cron
12 3 * * * /opt/invoice/scripts/backup-mega.sh >> /var/log/invoice-backup.log 2>&1
```

脚本可调变量（默认见文件头）：`BACKUP_DIR=/opt/backup`、`MEGA_REMOTE=invoice-mega`、`MEGA_FOLDER=invoice-backup`、`RETENTION_DAYS=30`。改完脚本先手动跑通一次。

**一致性说明**：DB 用 `sqlite3 .backup` 在线拿一致快照（WAL 下崩溃一致），不直接 tar DB 文件（复制到一半 WAL/SHM 会得到损坏副本）。

### 7.3 保留策略

本地：脚本自动清 30 天前旧包；MEGA 远端：`rclone delete --min-age 30d`（MEGA 删除进回收站，不占常规额度）。

### 7.4 恢复演练（重要，备份没验证=没备份）

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

## 8. 运维命令速查

```bash
systemctl status invoice-backend        # 状态（崩了 systemd 自动拉起）
journalctl -u invoice-backend -f        # 启动/异常日志
tail -f /opt/invoice/data/logs/invoice-server.log   # 应用日志（含 LLM fill）
systemctl restart invoice-backend       # 换 jar 后重启
nginx -t && systemctl reload nginx      # 改 nginx 配置后
free -m                                 # 内存余量
```

**更新部署流程** = §3：本地构建 → scp → `systemctl restart invoice-backend`（前端覆盖 dist 免重启）。**回滚到 Docker**：观察期内 `cd /opt/invoice && docker compose up -d` 即回旧栈（镜像与卷未删，详见 `de-docker-plan.md` §7）。

---

## 9. 安全清单（部署前逐项确认）

- [ ] `.env` / `.env.systemd` `chmod 600`，强密码，未进仓库
- [ ] `JWT_SECRET` 已设强随机值（未设 = dev 默认密钥，可伪造 token）
- [ ] `ADMIN_PASSWORD` 已改（未改 = `admin123`，可被猜）
- [ ] SQLite DB 不对公网（8080 只绑 127.0.0.1，UFW 亦无 8080 放行）
- [ ] UFW：8088 仅 CF IP 段 22 条 allow；80/443 不放行（CF 代理走 8088）
- [ ] nginx AOP（`ssl_verify_client on`）+ SNI 兜底（`ssl_reject_handshake`）在位
- [ ] systemd `MemoryMax=512M`（防 OOM 错杀邻居）
- [ ] 定时备份 + MEGA 恢复演练完成（§7）

---

## 10. 已知局限

- **单机无高可用**：VPS 宕机即服务中断。个人/内部用可接受。
- **LLM 依赖外网**：`opencode.ai` 不可达时 LLM 兜底失败，但上传不中断（null 保留）。
- **备份按天粒度**（最多丢一天数据），MEGA 免费约 20GB 且 API 有限流；数据量大可改 R2。

---

## 附录：1C1G 内存

调优已固化在 systemd 单元：`JAVA_OPTS=-Xmx256m -Xms32m -XX:+UseSerialGC` + `MemoryMax=512M`。Docker 常驻开销（dockerd/containerd/shim 约 107MiB）已随去 Docker 化移除。

实测（2026-10-31 迁移后）：可用内存 425Mi → **606Mi**；java 进程 RSS 约 240MiB。仍紧张时：堆再压 `-Xmx192m`，或升 2C2G。
