# 去 Docker 化迁移计划（invoice 单机部署）

> 状态：**计划文档，未执行**。撰写于 2026-10-31，基于当时的实际配置盘点。
> 触发条件：内存紧张（当前可用 ~410Mi，Docker 全家固定开销 ~100Mi）或想简化部署栈时再执行。
> 预期收益：内存净省 ~100Mi（dockerd 42M + containerd 26M + shim×2 ~20M + docker-proxy×2 ~10M），
> 磁盘净省 ~800MB（镜像 625MB + Docker apt 包 ~400MB − 换回 JRE/nginx ~250MB）。
> 预计中断：10-30 分钟。全程每步留回滚路径，旧镜像最后验证完才删。

## 0. 现状盘点（迁移的"从"）

| 组件 | 现状 | 迁移后去向 |
|---|---|---|
| invoice-backend | 容器 `eclipse-temurin:25-jre-alpine`，JAVA_OPTS `-Xmx256m -Xms128m -XX:+UseSerialGC`，healthcheck `/api/auth/ping`，优雅停机 35s | systemd 单元 `invoice-backend.service`，`MemoryMax=512M` + `TimeoutStopSec=35` |
| invoice-nginx | 容器 `nginx:1.27-alpine`，前端 dist 内置于镜像 `/usr/share/nginx/html`，配置从宿主挂载 | 宿主 nginx + git 仓库里的 `web/dist` 构建产物 |
| 数据 | 三个命名卷：backend-db（invoice.db, 108K）、backend-data（uploads, 4.4M）、backend-logs（112K） | `/opt/invoice/data/{db,uploads,logs}` |
| 防火墙 | Docker 走 FORWARD 链，UFW 管不住 → `docker-user-cloudflare-8088.service` 用 DOCKER-USER 链白名单 CF IP 段 | 8088 回到 INPUT 链，UFW 直接放行 CF 段，该 service 退役 |
| 备份 | `backup-mega.sh`：`docker exec sqlite3 .backup` + `docker run alpine tar` + `docker cp` | 改为宿主机直接 sqlite3/tar（见 §4） |
| 环境变量 | docker-compose.yml 读 `.env`（JWT_SECRET、ADMIN_PASSWORD、LLM_*、WX_*） | systemd `EnvironmentFile`（见 §3.1 说明） |

## 1. 前置安装

```bash
apt install -y openjdk-25-jre-headless nginx sqlite3   # ubuntu 24.04 源都有
```

注意：源里 JRE 是 25.0.4，容器里是 Temurin 25.0.5 —— 同为 25 LTS，无兼容问题。

## 2. 数据出卷（不中断服务，可提前做）

```bash
mkdir -p /opt/invoice/data/{db,uploads,logs}
cp /var/lib/docker/volumes/invoice-manager_backend-db/_data/invoice.db*  /opt/invoice/data/db/
cp -a /var/lib/docker/volumes/invoice-manager_backend-data/_data/.       /opt/invoice/data/uploads/
cp -a /var/lib/docker/volumes/invoice-manager_backend-logs/_data/.       /opt/invoice/data/logs/
```

- 用 `cp invoice.db*` 连 WAL/SHM 一起拷，或干脆在低峰期停服后拷，二选一。
- uploads 拷完 `diff -r` 校验一遍。

## 3. 新配置文件（提前写好，切换时启用）

### 3.1 `/etc/systemd/system/invoice-backend.service`

```ini
[Unit]
Description=Invoice backend (Spring Boot)
After=network-online.target
Wants=network-online.target

[Service]
User=root
WorkingDirectory=/opt/invoice
# systemd EnvironmentFile 不支持行内注释/# 开头说明行，.env 里有大量注释，
# 所以用 awk 过滤生成一份纯净版（键=值），随 .env 更新时重新生成：
#   grep -E '^[A-Z_]+=' /opt/invoice/.env > /opt/invoice/.env.systemd
EnvironmentFile=/opt/invoice/.env.systemd
Environment=SPRING_DATASOURCE_URL=jdbc:sqlite:/opt/invoice/data/db/invoice.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&date_class=TEXT
Environment=UPLOAD_DIR=/opt/invoice/data/uploads
Environment=LOGGING_FILE_NAME=/opt/invoice/data/logs/invoice-server.log
Environment=TRUST_PROXY=true
Environment=TZ=Asia/Shanghai
Environment=JAVA_OPTS=-Xmx256m -Xms32m -XX:+UseSerialGC
ExecStart=/usr/bin/java ${JAVA_OPTS} -jar /opt/invoice/server/target/invoice-server-1.0.0.jar
Restart=on-failure
RestartSec=5
# 对应 compose 的 memory: 512m（堆 256m 之外给 PDFBox/POI 原生内存留余量）
MemoryMax=512M
# 对应 stop_grace_period: 35s —— 等 Spring 优雅停机完成再 SIGKILL
TimeoutStopSec=35

[Install]
WantedBy=multi-user.target
```

要点：
- compose 里的 `LLM_ENABLED/LLM_BASE_URL/LLM_MODEL/LLM_API_KEY/JWT_SECRET/ADMIN_USERNAME/ADMIN_PASSWORD/WX_*` 全部走 `EnvironmentFile`，键值从 `.env` 过滤生成，`.env` 原样保留作为唯一事实来源。
- `ExecStart` 里 jar 版本号写死 `1.0.0`（当前 pom version）；升版本时同步改单元。systemd 对 ExecStart 的 glob 展开不可靠，不要依赖。
- `${JAVA_OPTS}` 是 systemd 的环境变量展开语法，来自上面的 `Environment=`。
- `Xms32m` 是顺手把待用的省内存参数带上（堆不再被 128M 保底，RSS 预计 120M→80M）。

### 3.2 构建产物要落到宿主机

现在 jar 和前端 dist 都只存在于镜像内。各构建一次：

- **后端 jar**：VPS 上装 `maven` 直接构建（`cd /opt/invoice/server && mvn -B -DskipTests package`）。构建期内存要 ~1G，960M 机器偏紧但 1G swap 能顶；若 OOM 则改本地构建后 `scp server/target/invoice-server-1.0.0.jar root@…:/opt/invoice/server/target/`。
- **前端 dist**：`npm ci && npm run build`（本地做再 scp 上来，或 VPS 装 node）。放 `/opt/invoice/web/dist`，nginx root 指向它。

### 3.3 宿主 nginx 站点 `/etc/nginx/sites-available/invoice`

内容 = 现有 `web/nginx.conf`（git 仓库里有，挂载进容器的就是它）+ 三处修改：

1. `root /usr/share/nginx/html;` → `root /opt/invoice/web/dist;`（两处）
2. `$backend_upstream` 的 upstream 定义从容器名 `backend:8080` 改为 `127.0.0.1:8080`（看配置里 map/server 段的实际写法对应替换）
3. `include /etc/nginx/cf-allow.conf;` 保留但把文件拷到宿主 `/etc/nginx/cf-allow.conf`；证书 `/etc/nginx/certs/` → 拷 `/opt/invoice/certs/` 下三个 pem/key 到 `/etc/nginx/certs/`

软链启用 + 校验：

```bash
ln -s /etc/nginx/sites-available/invoice /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t
```

### 3.4 UFW 防火墙（切换前先配好，避免窗口期 8088 裸奔）

```bash
# 放行 Cloudflare 官方 IPv4/IPv6 段的 8088（清单从现成的白名单文件拷）
while read cidr; do ufw allow proto tcp from "$cidr" to any port 8088 comment 'cloudflare'; done < /etc/docker-user-cloudflare-8088/cloudflare-v4.txt
while read cidr; do ufw allow proto tcp from "$cidr" to any port 8088 comment 'cloudflare'; done < /etc/docker-user-cloudflare-8088/cloudflare-v6.txt
```

UFW 默认 incoming 是 deny，其余来源自动拒——语义与现 DOCKER-USER 白名单一致。

## 4. 备份脚本改造（backup-mega.sh）

三处 docker 依赖全部替换：

| 原 | 新 |
|---|---|
| `docker exec invoice-backend sqlite3 /app/data/invoice.db ".backup '/tmp/…'"` + `docker cp` | `sqlite3 /opt/invoice/data/db/invoice.db ".backup '$WORK/db/invoice.db'"`（宿主直接出快照，更简单） |
| `docker run --rm -v invoice-manager_backend-data:… alpine tar czf …` | `tar czf "$WORK/uploads/uploads.tar.gz" -C /opt/invoice/data/uploads .` |
| （头部注释里"backend 镜像已装 sqlite3"前提） | 改为"宿主机已装 sqlite3" |

其余逻辑（flock 锁、原子 mv、保留期、rclone 上传 MEGA）不动。改完必须**手动跑一次全流程**并解开 tar 验证包内 `db/invoice.db` 可被 `sqlite3 … ".schema"` 读出。

## 5. 切换日执行顺序（中断窗口从此开始）

```bash
# 1) UFW CF 白名单（§3.4）——先配，别裸奔
# 2) systemd 单元 + nginx 站点文件（§3.1/3.3）写好但先不 enable
# 3) 停旧栈
cd /opt/invoice && docker compose down        # 数据卷保留，绝不加 -v！
# 4) 起新栈
systemctl daemon-reload
systemctl enable --now invoice-backend.service
systemctl enable --now nginx
# 5) 验证
curl -s http://127.0.0.1:8080/api/auth/ping                    # 后端活
curl -sk https://127.0.0.1:8088 -H 'Host: invoice.bingyi.dpdns.org' -o /dev/null -w '%{http_code}\n'
#    外部走 Cloudflare 域名完整登录一次 + 上传一个 PDF + 导出一次（POI/PDFBox 路径）
# 6) 防火墙辅助服务退役
systemctl disable --now docker-user-cloudflare-8088.service
rm /etc/systemd/system/docker-user-cloudflare-8088.service /usr/local/sbin/docker-user-cloudflare-8088
rm -rf /etc/docker-user-cloudflare-8088
systemctl daemon-reload
# 7) 观察一天无异常后，拆除 Docker（§6）
```

健康检查对应：compose 的 healthcheck 由 systemd 天然替代（进程死 → Restart=on-failure）。若想要"进程活着但应用挂死"的检测，可选加个 cron `wget -q --spider http://127.0.0.1:8080/api/auth/ping || systemctl restart invoice-backend`，第一版可以不加。

## 6. 拆除 Docker（验证通过 ≥24h 后）

```bash
cd /opt/invoice && docker compose down --rmi all --volumes   # 最后一刻才删镜像和卷（卷已迁移，删前核对 §2 的拷贝仍在）
apt purge -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-model-plugin docker-ce-rootless-extras
rm -rf /var/lib/docker /var/lib/containerd /etc/docker
apt autoremove -y
```

## 7. 回滚（任一步失败）

- 切换窗口内回滚 = 反向操作：`systemctl disable --now invoice-backend nginx` → `cd /opt/invoice && docker compose up -d` → `systemctl start docker-user-cloudflare-8088`。镜像和卷在 `docker compose down`（无 -v）后原封未动，回滚 < 2 分钟。
- UFW 新规则不清（对 Docker 模式无影响，DOCKER-USER 链优先于 INPUT 语义不冲突）。
- 本文档留在仓库，作为回滚时的配置来源。

## 8. 风险清单

| 风险 | 缓解 |
|---|---|
| 误用 `docker compose down -v` 删掉数据卷 | §5 明文警告；卷删除推迟到 §6 且删前核对 §2 拷贝完整 |
| 8088 窗口期裸奔 | UFW 白名单（§3.4）是切换第 1 步 |
| .env 注释行导致 systemd 解析告警/失败 | §3.1 过滤生成 `.env.systemd`；切换后 `systemctl show invoice-backend --environment` 抽查 JWT_SECRET 已载入 |
| 备份脚本静默失败 | §4 强制手动全流程验证；改完后第二天检查 `/var/log/invoice-backup.log` |
| VPS 上 mvn 构建内存不足 | §3.2 备选：本地构建 scp；失败不影响旧栈运行 |
| Java 小版本差异（25.0.4 vs 25.0.5） | 同 LTS 大版本，无影响；后续走 apt unattended-upgrades 常规通道 |

## 9. 迁移后的日常运维变化

- 更新后端 = 替换 jar → `systemctl restart invoice-backend`（不再有镜像层缓存问题）
- 更新前端 = 覆盖 `web/dist` → nginx 零重启（静态文件即时生效）
- 日志：stdout 不再有 json-file 限幅；logback 直写 `/opt/invoice/data/logs/`（轮转沿用 logback 自身策略）；nginx 日志走 `/var/log/nginx/`（logrotate 系统自带）
- 两个 Dockerfile 保留在仓库（回滚保险），但 DEPLOY.md 需同步改写部署方式；`docker-user-cloudflare-8088` 一节整体作废
