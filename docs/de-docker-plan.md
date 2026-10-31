# 去 Docker 化迁移计划（invoice 单机部署）

> 状态：**已执行**（2026-10-31 切换完成，§6 拆除 Docker 待 24h 观察后执行）。
> 撰写于 2026-10-31，基于当时的实际配置盘点（并在服务器上逐项核实）。
> 评审修订：systemd 环境变量引号（§3.1）、宿主 nginx 1.24 的 http2 语法（§3.3）、
> 切换时点数据再同步（§2/§5）、本机验证预期值（§5）、防火墙服务退役挪至 §6。
> 执行期修订：ExecStart 用 `$JAVA_OPTS` 而非 `${JAVA_OPTS}`（见 §3.1）、
> §1 不装 maven（改本地构建 scp）、unit 增加 SERVER_ADDRESS=127.0.0.1、§5 验证 URL 带 :8088。
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
**不装 maven**：构建产物（jar/dist）在本地构建后 scp（见 §3.2），VPS 只当运行时。
若坚持 VPS 构建，需改装 `openjdk-25-jdk-headless`（JRE 无 javac，mvn 必失败）+ maven，
且 noble 的 maven 是 3.8.7（Docker 构建用 3.9），组合未验证。

## 2. 数据出卷（不中断服务，可提前做）

```bash
mkdir -p /opt/invoice/data/{db,uploads,logs}
cp /var/lib/docker/volumes/invoice-manager_backend-db/_data/invoice.db*  /opt/invoice/data/db/
cp -a /var/lib/docker/volumes/invoice-manager_backend-data/_data/.       /opt/invoice/data/uploads/
cp -a /var/lib/docker/volumes/invoice-manager_backend-logs/_data/.       /opt/invoice/data/logs/
```

- 预拷贝只是热身，**最终数据以 §5 第 4 步（停栈后）的再同步为准**：预拷贝到切换之间
  的新上传不能丢。这里 `cp invoice.db*` 连 WAL/SHM 一起拷即可——运行中拷贝三件套可能
  不一致，但会被 §5 停栈后的再同步整体覆盖。
- uploads 拷完 `diff -r` 校验一遍（再同步后可再校验一次）。

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
# 所以用 grep 过滤生成一份纯净版（键=值），随 .env 更新时重新生成：
#   grep -E '^[A-Z_]+=' /opt/invoice/.env > /opt/invoice/.env.systemd
EnvironmentFile=/opt/invoice/.env.systemd
Environment=SPRING_DATASOURCE_URL=jdbc:sqlite:/opt/invoice/data/db/invoice.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&date_class=TEXT
Environment=UPLOAD_DIR=/opt/invoice/data/uploads
Environment=LOGGING_FILE_NAME=/opt/invoice/data/logs/invoice-server.log
Environment=TRUST_PROXY=true
Environment=TZ=Asia/Shanghai
# 仅监听回环：Docker 时代 backend 端口不对公网发布（只有 nginx 的 8088 对外），
# 这里以绑定收口，不单靠 UFW；nginx 反代 127.0.0.1:8080 不受影响
Environment=SERVER_ADDRESS=127.0.0.1
# 含空格的值必须整体加引号：systemd 的 Environment= 是空格分隔的赋值列表，
# 不加引号整行被拒（实测 transient unit 直接报 Invalid environment block 起不来）
Environment="JAVA_OPTS=-Xmx256m -Xms32m -XX:+UseSerialGC"
# 必须是 $JAVA_OPTS 而非 ${JAVA_OPTS}：前者按空格拆成多个参数，后者把整个值
# 当单个参数传给 java → "Invalid maximum heap size"（执行日踩过的坑）。
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/invoice/server/target/invoice-server-1.0.0.jar
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

- **后端 jar**：VPS 上装 `maven` 直接构建（`cd /opt/invoice/server && mvn -B -DskipTests package`）。构建期内存要 ~1G，960M 机器偏紧但 1G swap 能顶；若 OOM 则改本地构建后 `scp server/target/invoice-server-1.0.0.jar root@…:/opt/invoice/server/target/`。**试跑放在切换日之前的低峰期**：noble 的 maven 是 3.8.7，Docker 构建用的是 3.9，3.8.7 + JDK 25 组合未验证过——构建失败直接走 scp 兜底，不占切换窗口排障。
- **前端 dist**：`npm ci && npm run build`（本地做再 scp 上来，或 VPS 装 node）。放 `/opt/invoice/web/dist`，nginx root 指向它。

### 3.3 宿主 nginx 站点 `/etc/nginx/sites-available/invoice`

内容 = 现有 `web/nginx.conf`（git 仓库里有，挂载进容器的就是它）+ 四处修改：

1. `root /usr/share/nginx/html;` → `root /opt/invoice/web/dist;`（两处）
2. `$backend_upstream` 的 upstream 定义从容器名 `backend:8080` 改为 `127.0.0.1:8080`，并**删掉 `resolver 127.0.0.11` 那行**（Docker 内嵌 DNS，upstream 改 IP 后是死配置）
3. **`http2 on;` 改回 `listen 8088 ssl http2;`**：宿主 noble 的 nginx 是 **1.24.0**，`http2 on` 指令 1.25.1 才引入（容器里是 1.27 所以现在没炸），不改 `nginx -t` 直接 unknown directive。conf 里其余指令（`ssl_reject_handshake`、AOP、map）1.24 都支持，仅此一处
4. `include /etc/nginx/cf-allow.conf;` 保留但把文件拷到宿主 `/etc/nginx/cf-allow.conf`；证书 `/etc/nginx/certs/` → 拷 `/opt/invoice/certs/` 下三个 pem/key 到 `/etc/nginx/certs/`

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
# 4) 数据终态再同步（覆盖 §2 的预拷贝，保证切换时点数据齐全；~5MB 几秒钟）
cp /var/lib/docker/volumes/invoice-manager_backend-db/_data/invoice.db*  /opt/invoice/data/db/
cp -a /var/lib/docker/volumes/invoice-manager_backend-data/_data/.       /opt/invoice/data/uploads/
cp -a /var/lib/docker/volumes/invoice-manager_backend-logs/_data/.       /opt/invoice/data/logs/
# 5) 起新栈
systemctl daemon-reload
systemctl enable --now invoice-backend.service
systemctl enable --now nginx
# 6) 验证
curl -s http://127.0.0.1:8080/api/auth/ping                    # 后端活，预期 200
#    本机 HTTPS 检查：必须 --resolve 才带得上 SNI——裸 127.0.0.1 curl 不发 SNI，
#    会撞 default_server 的 ssl_reject_handshake 握手失败（curl 退出码 35）。
#    URL 必须写 :8088：--resolve 的 host:port:addr 按端口匹配，URL 缺端口走默认
#    443 → 正常 DNS → 流量经 Cloudflare 回源（带 CF client 证书）→ 200，测不到本机闸门。
#    预期 400 = TLS/SNI/Host 路由 + AOP 闸门均生效（AOP 对无 client 证书的连接
#    按设计回 400，直连探测只得 400 是正常现象，不是故障）。
curl -sk --noproxy '*' --resolve invoice.bingyi.dpdns.org:8088:127.0.0.1 \
  https://invoice.bingyi.dpdns.org:8088/api/auth/ping -o /dev/null -w '%{http_code}\n'
#    功能验证只走公网域名（预期 200）：完整登录一次 + 上传一个 PDF + 导出一次（POI/PDFBox 路径）
# 7) 观察一天无异常后，拆除 Docker（§6；防火墙辅助服务的退役一并挪入 §6）
```

健康检查对应：compose 的 healthcheck 由 systemd 天然替代（进程死 → Restart=on-failure）。若想要"进程活着但应用挂死"的检测，可选加个 cron `wget -q --spider http://127.0.0.1:8080/api/auth/ping || systemctl restart invoice-backend`，第一版可以不加。

## 6. 拆除 Docker（验证通过 ≥24h 后）

```bash
cd /opt/invoice && docker compose down --rmi all --volumes   # 最后一刻才删镜像和卷（卷已迁移，删前核对 §5 第 4 步的再同步结果仍在）
systemctl disable --now docker-user-cloudflare-8088.service  # 防火墙辅助服务退役（从 §5 挪到这里：
rm /etc/systemd/system/docker-user-cloudflare-8088.service   #  24h 观察期内保留文件，回滚 §7 才有得 start）
rm /usr/local/sbin/docker-user-cloudflare-8088
rm -rf /etc/docker-user-cloudflare-8088
systemctl daemon-reload
apt purge -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-model-plugin docker-ce-rootless-extras
rm -rf /var/lib/docker /var/lib/containerd /etc/docker
apt autoremove -y
```

七个包名已按服务器 `dpkg -l` 实际安装核对过（2026-10-31），全在，purge 不会因包缺失中断。

## 7. 回滚（任一步失败）

- 切换窗口内回滚 = 反向操作：`systemctl disable --now invoice-backend nginx` → `cd /opt/invoice && docker compose up -d` → `systemctl start docker-user-cloudflare-8088`。镜像和卷在 `docker compose down`（无 -v）后原封未动，回滚 < 2 分钟。**24h 观察期内的回滚同样适用**——防火墙辅助服务的文件已挪到 §6 才删，观察期内 `systemctl start` 仍有文件可起。
- UFW 新规则不清（对 Docker 模式无影响，DOCKER-USER 链优先于 INPUT 语义不冲突）。
- 本文档留在仓库，作为回滚时的配置来源。

## 8. 风险清单

| 风险 | 缓解 |
|---|---|
| 误用 `docker compose down -v` 删掉数据卷 | §5 明文警告；卷删除推迟到 §6 且删前核对 §5 第 4 步的再同步结果完整 |
| 8088 窗口期裸奔 | UFW 白名单（§3.4）是切换第 1 步 |
| .env 注释行导致 systemd 解析告警/失败 | §3.1 过滤生成 `.env.systemd`；切换后 `systemctl show invoice-backend --environment` 抽查 JWT_SECRET 已载入 |
| 备份脚本静默失败 | §4 强制手动全流程验证；改完后第二天检查 `/var/log/invoice-backup.log` |
| VPS 上 mvn 构建内存不足 / maven 3.8.7 与 JDK 25 组合未验证 | §3.2 试跑提前到切换日前低峰期；失败走 scp 兜底，不影响旧栈运行、不占切换窗口 |
| Java 小版本差异（25.0.4 vs 25.0.5） | 同 LTS 大版本，无影响；后续走 apt unattended-upgrades 常规通道 |

## 9. 迁移后的日常运维变化

- 更新后端 = 替换 jar → `systemctl restart invoice-backend`（不再有镜像层缓存问题）
- 更新前端 = 覆盖 `web/dist` → nginx 零重启（静态文件即时生效）
- 日志：stdout 不再有 json-file 限幅；logback 直写 `/opt/invoice/data/logs/`（轮转沿用 logback 自身策略）；nginx 日志走 `/var/log/nginx/`（logrotate 系统自带）
- 两个 Dockerfile 保留在仓库（回滚保险），但 DEPLOY.md 需同步改写部署方式；`docker-user-cloudflare-8088` 一节整体作废
