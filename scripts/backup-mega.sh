#!/usr/bin/env bash
#
# 每日 3 点异地备份到 MEGA（在 VPS 宿主机执行；去 Docker 化后直接读宿主数据目录）。
#
# 产出：单个 tar.gz（包内 db/invoice.db + uploads/uploads.tar.gz）→ 落到 /opt/backup/，
#       再经 rclone 上传到 MEGA 的 invoice-backup 目录异地保存。
#
# 与旧方案的差异：DB 与上传文件此前是两个独立文件且只存本机（非异地）；
# 本脚本把它们打成一个包，并上传 MEGA，作异地容灾（VPS 挂掉备份仍在）。
#
# 前提（一次性）：
#   1) 宿主机已装 rclone 并配好 MEGA remote（rclone config，选 mega，
#      填账号密码，见 README「备份与恢复 · 一次性准备」）。
#   2) 宿主机已装 sqlite3 CLI（apt install sqlite3；去 Docker 化后 DB 在宿主
#      文件系统）。
#   3) 数据目录 —— DB 在 /opt/invoice/data/db/invoice.db，
#      上传文件在 /opt/invoice/data/uploads/。
#
# 用法：bash /opt/invoice/scripts/backup-mega.sh
# cron 示例：12 3 * * * /opt/invoice/scripts/backup-mega.sh >> /var/log/invoice-backup.log 2>&1

set -euo pipefail

# 可调项
BACKUP_DIR="${BACKUP_DIR:-/opt/backup}"      # 本地中转 + 保留目录
MEGA_REMOTE="${MEGA_REMOTE:-invoice-mega}"   # rclone remote 名（MEGA）
MEGA_FOLDER="${MEGA_FOLDER:-invoice-backup}" # MEGA 下的目标目录名
RETENTION_DAYS="${RETENTION_DAYS:-30}"       # 本地/远端保留天数
# 注意：.env（含 JWT_SECRET / WX_SECRET / LLM_API_KEY）与 nginx 站点配置 /
# certs/ 不在本备份范围内——VPS 全损时需靠 README 手工重建这些配置。

# 心跳告警（dead-man switch）：成功 ping $HC_URL，任何失败路径 ping $HC_URL/fail；
# cron 与脚本双双挂掉由 healthchecks 侧超时兜底（周期 1 天 + 宽限期）。
# URL 取自 /opt/invoice/.env 的 HEALTHCHECK_URL（cron 无环境，脚本自己读）；
# 未配置 = 静默跳过（本地跑 / 尚未注册时不得报错也不得告警）。
HC_URL=""
if [ -f /opt/invoice/.env ]; then
    HC_URL="$(sed -n 's/^HEALTHCHECK_URL=//p' /opt/invoice/.env | head -1 | tr -d "\"'[:space:]")"
fi
# flock 让路时置 0：持锁的那个实例负责 ping，这里不重复也不谎报成功。
HC_PING=1

STAMP="$(date +%F-%H%M)"                     # 如 2026-08-27-0312
WORK="$BACKUP_DIR/work/$STAMP"
# 先写 .tmp，校验非空后 mv 到最终名：tar 中途被杀/磁盘满会留下半个包，在保留期
# 内伪装成有效备份；rename 同目录原子。
OUT="$BACKUP_DIR/invoice-backup-$STAMP.tar.gz"
OUT_TMP="$OUT.tmp"

# 统一出口：EXIT trap 在 bash 只能设一个，清理工作目录与心跳合并在此。
# 成功 ping $HC_URL，任何失败路径 ping $HC_URL/fail；URL 空 = 未配置，静默跳过。
cleanup() {
    local rc=$?
    rm -rf "$WORK"
    if [ "$HC_PING" = 1 ] && [ -n "$HC_URL" ]; then
        if [ "$rc" -eq 0 ]; then
            curl -fsS --max-time 10 "$HC_URL" >/dev/null 2>&1 || true
        else
            curl -fsS --max-time 10 "$HC_URL/fail" >/dev/null 2>&1 || true
        fi
    fi
}
trap cleanup EXIT

# 独占锁：cron 与手动同分钟重叠时，两个 run 会写同一个 $OUT，rclone 可能读到
# tar 到一半的文件 → 远端留下损坏副本且被同名跳过策略固化。flock -n 抢不到即退出。
exec 9>"$BACKUP_DIR/invoice-backup.lock"
flock -n 9 || { echo "[backup] 另一个备份进程正在运行，退出" >&2; HC_PING=0; exit 0; }

mkdir -p "$WORK/db" "$WORK/uploads"

echo "[backup] 开始 $STAMP"

# 1) SQLite 在线一致快照（服务运行中也安全：.backup 在 WAL 模式下拿到一致快照）。
#    去 Docker 化后宿主直接出快照，不再绕道 docker exec + docker cp。
if ! sqlite3 /opt/invoice/data/db/invoice.db ".backup '$WORK/db/invoice.db'"; then
    echo "[backup] 错误：SQLite .backup 失败" >&2
    exit 1
fi

# 2) 打包上传文件目录（宿主直读，不再起 alpine 容器）。
tar czf "$WORK/uploads/uploads.tar.gz" -C /opt/invoice/data/uploads .

# 3) 打成一个最终的压缩包，包内结构：db/invoice.db + uploads/uploads.tar.gz。
#    PDF 已不可压缩，外层再 gzip 收益极小，但保留 .gz 后缀与历史命名一致。
tar czf "$OUT_TMP" -C "$WORK" db uploads
[ -s "$OUT_TMP" ] || { echo "[backup] 错误：产出包为空" >&2; exit 1; }
mv "$OUT_TMP" "$OUT"
echo "[backup] 已生成 $OUT ($(du -h "$OUT" | cut -f1))"

# 4) 本地保留策略：清掉 N 天前的旧包。
find "$BACKUP_DIR" -maxdepth 1 -name 'invoice-backup-*.tar.gz' -mtime +"$RETENTION_DAYS" -delete

# 5) 上传 MEGA 异地备份。
#    rclone copy 对同名远端文件跳过（增量）；本脚本已用 flock 串行 + 本地原子 mv，
#    不会读到 tar 到一半的文件，远端不会固化损坏副本。
if ! rclone copy "$OUT" "$MEGA_REMOTE:$MEGA_FOLDER/"; then
    rm -f "$OUT_TMP"  # 残留 tmp 也清掉（trap 只清 WORK）
    echo "[backup] 错误：rclone 上传 MEGA 失败（本地包已保留，可重试）" >&2
    exit 1
fi
# 远端保留：删掉 N 天前的旧包（以 MEGA 文件修改时间计）。
rclone delete "$MEGA_REMOTE:$MEGA_FOLDER/" --min-age "${RETENTION_DAYS}d" || true

echo "[backup] 完成（本地 + MEGA 已上传）"
