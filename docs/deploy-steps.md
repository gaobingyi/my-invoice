# VPS 部署步骤（deploy-steps）

本篇是针对一次具体升级的**可照搬执行**部署清单，覆盖：

1. **发票解析指标监控**（后端新增 `parsing_metrics` / `parsing_log` 表 + `SchemaMigration` 自动迁移 + 前端 `/metrics` Dashboard）
2. **Google Drive 异地备份**（新增 `scripts/backup-drive.sh`）

> 部署方式以「源码在服务器」为准（`/opt/invoice`）。如需「本地打包传输」，见 `DEPLOY.md §3` 方式二。

---

## A. 应用代码升级（每次代码更新都做）

**关键结论**：指标监控的**新表和迁移均由应用启动时自动完成**——`config/SchemaMigration.java` 对老库 `ALTER TABLE` 补列、schema.sql 用 `CREATE TABLE IF NOT EXISTS` 建新表。**无需手动动数据库、不清数据**。

SSH 登录 VPS：

```bash
cd /opt/invoice && git pull && docker compose up -d --build
```

验证：

```bash
docker compose ps                    # backend/nginx 都 healthy
docker compose logs -f backend       # 看 SchemaMigration 是否打了补列日志，无 ERROR
```

浏览器打开 `https://invoice.bingyi.dpdns.org/metrics`，能看到解析指标 Dashboard（正则/LLM 成功率、趋势、字段缺失表）即成功。

> 首次升级后新表为空（历史解析不追溯重建），Dashboard 数字从今天起累计，属正常。

---

## B. Google Drive 异地备份（首次一次性配置）

备份脚本 `scripts/backup-drive.sh`：每日 3 点把 SQLite 一致快照 + 上传文件卷打成**单个** `invoice-backup-<时间>.tar.gz`，落本地 `/opt/backup/` 并经 rclone 上传 Google Drive 异地保存。免费 15GB、无需信用卡。

### B1. 自建 Google OAuth client（必需 ★）

rclone 官方共享 client_id **已于 2026 年停用**，必须自建，否则 token 会失效：

1. 浏览器打开 [Google Cloud Console](https://console.cloud.google.com/)（用要存备份的 Google 账号）
2. 建/选一个项目 → **APIs & Services → Library** → 搜 **Google Drive API** → **Enable**
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**
   - 首次会要求先配 **OAuth consent screen**：User type 选 **External**，填应用名（如 `invoice-backup`），保存
   - Application type 选 **Desktop app** → **Create**
4. 记下 **Client ID** 和 **Client Secret**

### B2. VPS 装 rclone 并配 remote

```bash
curl https://rclone.org/install.sh | sudo bash
rclone config        # 交互式
```

向导内依次：

```
n) New remote
name> invoice-drive
Storage type 选 drive
client_id>    粘 B1 的 Client ID
client_secret> 粘 B1 的 Client Secret
scope>  选 1（Full access all files）
service_account_file>  直接回车
Use web browser to authenticate>  n     ← VPS 无浏览器，选 n 走手动授权
> 打印 URL：在你自己电脑浏览器打开，用刚才 Google 账号授权，得到 code 回贴
Configure this as a Shared Drive>  n
确认 y 保存
```

> 配置落在 VPS `~/.config/rclone/rclone.conf`（自动 600 权限，**不进仓库**）。

验证连通：

```bash
rclone lsf invoice-drive:            # 无报错即可（空输出正常）
```

### B3. 部署脚本 + 手动跑通一次

```bash
ls /opt/invoice/scripts/             # 应含 backup-drive.sh
bash /opt/invoice/scripts/backup-drive.sh   # 手动跑一次
```

验证：

```bash
ls -lh /opt/backup/                                # 出现 invoice-backup-<时间>.tar.gz
rclone lsf invoice-drive:invoice-backup/           # 出现同名包 → 已上传 Drive
tar tzf /opt/backup/invoice-backup-*.tar.gz        # 包内应含 db/invoice.db + uploads/uploads.tar.gz
```

可选：到 Google Drive 网页版确认 `invoice-backup` 目录里有该 tar.gz。

### B4. 挂 cron（每日 3:12 自动备份）

```bash
crontab -e
```

追加（用这个干净版，目录由脚本 `mkdir -p` 自建）：

```cron
# 每天 3:12（错峰）：单包备份 DB+uploads → 本地 /opt/backup/ + 上传 Google Drive 异地
12 3 * * * /opt/invoice/scripts/backup-drive.sh >> /var/log/invoice-backup.log 2>&1
```

确认：

```bash
crontab -l
```

### B5.（强烈建议）恢复演练——备份没验证=没备份

从 Google Drive 拉最近一个包，完整恢复验证数据能回来：

```bash
rclone copy invoice-drive:invoice-backup/ /opt/backup/restore/
tar xzf /opt/backup/restore/invoice-backup-*.tar.gz -C /opt/backup/restore/

cd /opt/invoice && docker compose stop backend

docker cp /opt/backup/restore/db/invoice.db invoice-backend:/app/data/invoice.db
docker exec invoice-backend sh -c 'rm -f /app/data/invoice.db-wal /app/data/invoice.db-shm'

mkdir -p /opt/backup/restore/uploads_extract
tar xzf /opt/backup/restore/uploads/uploads.tar.gz -C /opt/backup/restore/uploads_extract
docker run --rm \
  -v /opt/backup/restore/uploads_extract:/in \
  -v invoice-manager_backend-data:/data \
  alpine sh -c 'rm -rf /data/* && cp -a /in/* /data/'

docker compose start backend
```

验证：登录 → 列表页数据回来了、上传的 PDF 能预览下载。**若只是演练，务必别用假数据覆盖真数据**（可先对现有卷另做一次快照，或跳过覆盖上传文件那步仅验证 DB 恢复）。

---

## 常见问题

| 症状 | 处理 |
|---|---|
| `rclone lsf invoice-drive:` 报 401/失效 | OAuth client 没自建或用了共享 id → 重做 B1，`rclone config reconnect invoice-drive:` 重新授权 |
| 备份脚本报 `sqlite3` 不存在 | 镜像没装 sqlite3 → 确认 `server/Dockerfile` 含 `apk add sqlite`，重建镜像 |
| `backup-drive.sh` 上传失败但本地包在 | 脚本会报错且保留本地包可重试；看 `/var/log/invoice-backup.log` |
| Drive 网盘快满 | 免费 15GB，脚本默认 `RETENTION_DAYS=30` 自动清理；数据量大可调小该值 |
| `/metrics` 页打不开 | 确认后端升级成功（`docker compose logs backend` 无 ERROR），且已登录（该路由需认证） |

---

## 参考

- 备份脚本：`scripts/backup-drive.sh`（可调项见文件头：`BACKUP_DIR` / `DRIVE_REMOTE` / `DRIVE_FOLDER` / `RETENTION_DAYS`）
- 备份完整说明与恢复：`DEPLOY.md §7`
- 运维命令速查：`DEPLOY.md §8`
- 部署拓扑：`docs/deploy-dns.md`
