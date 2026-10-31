#!/usr/bin/env bash
# 清空本地开发数据：数据库 + 上传文件（dev.sh 起的后端）
set -euo pipefail

# 脚本在 scripts/ 下，仓库根 = 脚本目录的上一级
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "检测到模式: local"
echo ""

DB="$ROOT/server/data/invoice.db"
UPLOADS="$ROOT/server/uploads"

echo "将要删除："
[ -f "$DB" ] && echo "  数据库: $DB" || echo "  数据库: (不存在)"
[ -d "$UPLOADS" ] && echo "  上传目录: $UPLOADS/ ($(ls "$UPLOADS" 2>/dev/null | wc -l) 个文件)" || echo "  上传目录: (不存在)"

read -p "确认清空？[y/N] " confirm
if [[ "$confirm" != [yY] ]]; then echo "已取消"; exit 0; fi

# 停服务
if [ -f "$ROOT/.dev-backend.pid" ] && kill -0 "$(cat "$ROOT/.dev-backend.pid")" 2>/dev/null; then
    echo -n "停止后端... "
    bash "$ROOT/scripts/dev.sh" stop 2>/dev/null
    sleep 1
    echo "已停止"
fi

# 删数据库（下次启动 schema.sql 自动重建）。WAL 模式下 dev.sh 停后端超时
# 可能 kill -9 留下 invoice.db-wal / -shm，残留文件与新建空库组合属未定义
# 场景（SQLite 不保证），一并删除。
if [ -f "$DB" ]; then
    rm -f "$DB" "$DB-wal" "$DB-shm"
    echo "已删除数据库"
fi

# 删上传文件
if [ -d "$UPLOADS" ]; then
    rm -rf "$UPLOADS"
    echo "已清空上传目录"
fi

# 重启服务
echo -n "重启服务... "
bash "$ROOT/scripts/dev.sh" start
