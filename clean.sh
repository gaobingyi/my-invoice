#!/usr/bin/env bash
# 清空数据：数据库 + 上传文件（本地开发 或 Docker 部署）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

# 检测运行模式
if command -v docker &>/dev/null && docker compose ps --status running 2>/dev/null | grep -q backend; then
    MODE="docker"
else
    MODE="local"
fi

echo "检测到模式: $MODE"
echo ""

if [ "$MODE" = "docker" ]; then
    echo "将要执行："
    echo "  docker compose down -v  （停容器 + 删除命名卷 backend-db）"
    echo "  docker compose up -d --build  （重建启动）"
    read -p "确认清空？[y/N] " confirm
    if [[ "$confirm" != [yY] ]]; then echo "已取消"; exit 0; fi
    cd "$ROOT"
    docker compose down -v
    docker compose up -d --build
    echo ""
    docker compose ps
else
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
        bash "$ROOT/dev.sh" stop 2>/dev/null
        sleep 1
        echo "已停止"
    fi

    # 删数据库（下次启动 schema.sql 自动重建）
    if [ -f "$DB" ]; then
        rm -f "$DB"
        echo "已删除数据库"
    fi

    # 删上传文件
    if [ -d "$UPLOADS" ]; then
        rm -rf "$UPLOADS"
        echo "已清空上传目录"
    fi

    # 重启服务
    echo -n "重启服务... "
    bash "$ROOT/dev.sh" start
fi
