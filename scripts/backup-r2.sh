#!/usr/bin/env bash
#
# 每日 3 点异地备份到 Cloudflare R2（在 VPS 宿主机执行，需对 compose 有 docker 权限）。
#
# 产出：单个 tar.gz（包内 db/invoice.db + uploads/uploads.tar.gz）→ 落到 /opt/backup/，
#       再经 rclone 上传到 R2 的 invoice-backup bucket 异地保存。
#
# 与旧方案的差异：DB 与上传文件此前是两个独立文件且只存本机（非异地）；
# 本脚本把它们打成一个包，并上传 R2，作异地容灾。
#
# 前提（一次性）：
#   1) 宿主机已装 rclone 并配好 S3 remote（provider Cloudflare，见 DEPLOY.md §7.1）。
#   2) backend 镜像已装 sqlite3 CLI（server/Dockerfile 已 `apk add sqlite`）。
#   3) 数据卷 —— DB 在 backend-db(/app/data/invoice.db)，上传文件在 backend-data(/app/uploads)。
#
# 用法：bash /opt/invoice/scripts/backup-r2.sh
# cron 示例：12 3 * * * /opt/invoice/scripts/backup-r2.sh >> /var/log/invoice-backup.log 2>&1

set -euo pipefail

# 可调项
BACKUP_DIR="${BACKUP_DIR:-/opt/backup}"      # 本地中转 + 保留目录
R2_REMOTE="${R2_REMOTE:-invoice-r2}"         # rclone remote 名
R2_BUCKET="${R2_BUCKET:-invoice-backup}"     # R2 bucket 名
RETENTION_DAYS="${RETENTION_DAYS:-30}"       # 本地/远端保留天数
COMPOSE_DIR="${COMPOSE_DIR:-/opt/invoice}"   # docker-compose.yml 所在目录（restore 时用）

STAMP="$(date +%F-%H%M)"                     # 如 2026-08-27-0312
WORK="$BACKUP_DIR/work/$STAMP"
OUT="$BACKUP_DIR/invoice-backup-$STAMP.tar.gz"

mkdir -p "$WORK/db" "$WORK/uploads"
trap 'rm -rf "$WORK"' EXIT                  # 无论成败都清工作目录

echo "[backup] 开始 $STAMP"

# 1) SQLite 在线一致快照。
#    .backup 在 WAL 模式下仍能拿到一致快照；统一走 sqlite3 CLI（镜像已装），
#    不做 cp+checkpoint 的备选路径，避免复制到一半 WAL/SHM 拿到损坏的第二份 DB。
if ! docker exec invoice-backend sqlite3 /app/data/invoice.db ".backup '/tmp/invoice-backup.db'"; then
    echo "[backup] 错误：SQLite .backup 失败" >&2
    exit 1
fi
docker cp invoice-backend:/tmp/invoice-backup.db "$WORK/db/invoice.db"
docker exec invoice-backend rm -f /tmp/invoice-backup.db

# 2) 备份上传文件卷（只读挂载，打包 uploads 目录内容）。
docker run --rm \
    -v invoice-manager_backend-data:/data:ro \
    -v "$WORK/uploads":/out \
    alpine tar czf /out/uploads.tar.gz -C /data .

# 3) 打成一个最终的压缩包，包内结构：db/invoice.db + uploads/uploads.tar.gz。
tar czf "$OUT" -C "$WORK" db uploads
[ -s "$OUT" ] || { echo "[backup] 错误：产出包为空" >&2; exit 1; }
echo "[backup] 已生成 $OUT ($(du -h "$OUT" | cut -f1))"

# 4) 本地保留策略：清掉 N 天前的旧包。
find "$BACKUP_DIR" -maxdepth 1 -name 'invoice-backup-*.tar.gz' -mtime +"$RETENTION_DAYS" -delete

# 5) 上传 R2 异地备份。
#    --s3-no-check-bucket：R2 bucket 由控制台预建，跳过每秒 AWS 的 HeadBucket 探测。
if ! rclone copy "$OUT" "$R2_REMOTE:$R2_BUCKET/" --s3-no-check-bucket; then
    echo "[backup] 错误：rclone 上传 R2 失败（本地包已保留，可重试）" >&2
    exit 1
fi
# 远端保留：删掉 N 天前的旧包（以 R2 对象时间戳计）。
rclone delete "$R2_REMOTE:$R2_BUCKET/" --min-age "${RETENTION_DAYS}d" --s3-no-check-bucket || true

echo "[backup] 完成（本地 + R2 已上传）"
