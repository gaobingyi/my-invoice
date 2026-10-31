#!/usr/bin/env bash
# 生产双后端切换：Java（invoice-backend）↔ Go（invoice-go），共享同一数据
# （/opt/invoice/data/{db,uploads}），systemd Conflicts 互斥、同一时刻只跑一个。
# 用法: scripts/switch-backend.sh java|go|status
# 机制: start 目标后端（Conflicts 自动停对方）+ enable/disable 对调
#       （保证 VPS 重启后仍自动起当前选中的后端）。
set -euo pipefail

HOST="${PROD_HOST:-root@103.232.94.248}"
JAVA=invoice-backend
GO=invoice-go

case "${1:-}" in
    java|go)
        TARGET=$1
        OTHER=$([ "$TARGET" = java ] && echo "$GO" || echo "$JAVA")
        UNIT=$([ "$TARGET" = java ] && echo "$JAVA" || echo "$GO")
        echo "切换到 $TARGET ..."
        # 单次 SSH 内完成（连接复用，避免多次握手）；ping 重试容忍 Java ~8s 启动
        ssh "$HOST" "
            systemctl start $UNIT
            systemctl enable $UNIT >/dev/null
            systemctl disable $OTHER >/dev/null
            ok=''
            for i in \$(seq 1 30); do
                curl -sf http://127.0.0.1:8080/api/auth/ping >/dev/null 2>&1 && { ok=yes; break; }
                sleep 1
            done
            echo \"active=\$(systemctl is-active $UNIT) other=\$(systemctl is-active $OTHER) ping=\${ok:-FAIL}\"
            [ -n \"\$ok\" ]"
        echo "已切换到 $TARGET（8080 ping 通，$OTHER 已停并取消自启）"
        ;;
    status)
        ssh "$HOST" "
            for u in $JAVA $GO; do
                en=\$(systemctl is-enabled \$u 2>/dev/null || true)
                printf '%-18s active=%-8s enabled=%s\n' \$u \"\$(systemctl is-active \$u 2>/dev/null || true)\" \"\${en:-unknown}\"
            done
            curl -sf http://127.0.0.1:8080/api/auth/ping >/dev/null 2>&1 && echo '8080 ping: OK' || echo '8080 ping: FAIL'
            curl -s http://127.0.0.1:8080/api/auth/ping"
        ;;
    *)
        echo "用法: $0 {java|go|status}"
        echo ""
        echo "  java    - 切到 Java 后端（Spring Boot，默认）"
        echo "  go      - 切到 Go 后端（轻量并行版）"
        echo "  status  - 查看两个后端 active/enabled 状态与 8080 ping"
        exit 1
        ;;
esac
