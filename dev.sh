#!/usr/bin/env bash
# 本地开发启停脚本：后端 8080 + 前端 5173
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_PID_FILE="$ROOT/.dev-backend.pid"
FRONTEND_PID_FILE="$ROOT/.dev-frontend.pid"
BACKEND_LOG="$ROOT/.dev-backend.log"
FRONTEND_LOG="$ROOT/.dev-frontend.log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

status() {
    local running=true
    if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
        echo -e "  后端: ${GREEN}运行中${NC} (PID $(cat "$BACKEND_PID_FILE"), http://localhost:8080)"
    else
        echo -e "  后端: ${RED}未运行${NC}"
        running=false
    fi
    if [ -f "$FRONTEND_PID_FILE" ] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        echo -e "  前端: ${GREEN}运行中${NC} (PID $(cat "$FRONTEND_PID_FILE"), http://localhost:5173)"
    else
        echo -e "  前端: ${RED}未运行${NC}"
        running=false
    fi
    return $( [ "$running" = true ] && echo 0 || echo 1 )
}

start_backend() {
    if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
        echo -e "${YELLOW}后端已在运行 (PID $(cat "$BACKEND_PID_FILE"))${NC}"
        return
    fi
    echo -n "启动后端... "
    cd "$ROOT/server"
    nohup mvn spring-boot:run -q > "$BACKEND_LOG" 2>&1 &
    echo $! > "$BACKEND_PID_FILE"
    cd "$ROOT"
    # 等待后端就绪
    for i in $(seq 1 60); do
        if curl -sf http://localhost:8080/api/auth/ping >/dev/null 2>&1; then
            echo -e "${GREEN}就绪${NC}"
            return
        fi
        sleep 1
    done
    echo -e "${RED}超时（查看 $BACKEND_LOG）${NC}"
}

start_frontend() {
    if [ -f "$FRONTEND_PID_FILE" ] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        echo -e "${YELLOW}前端已在运行 (PID $(cat "$FRONTEND_PID_FILE"))${NC}"
        return
    fi
    echo -n "启动前端... "
    cd "$ROOT/web"
    nohup npm run dev > "$FRONTEND_LOG" 2>&1 &
    echo $! > "$FRONTEND_PID_FILE"
    cd "$ROOT"
    for i in $(seq 1 30); do
        if curl -sf http://localhost:5173 >/dev/null 2>&1; then
            echo -e "${GREEN}就绪${NC}"
            return
        fi
        sleep 1
    done
    echo -e "${RED}超时（查看 $FRONTEND_LOG）${NC}"
}

stop_backend() {
    if [ -f "$BACKEND_PID_FILE" ]; then
        local pid=$(cat "$BACKEND_PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            echo -n "停止后端 (PID $pid)... "
            kill "$pid" 2>/dev/null
            for i in $(seq 1 10); do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            kill -9 "$pid" 2>/dev/null || true
            echo -e "${GREEN}已停止${NC}"
        fi
        rm -f "$BACKEND_PID_FILE"
    fi
}

stop_frontend() {
    if [ -f "$FRONTEND_PID_FILE" ]; then
        local pid=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            echo -n "停止前端 (PID $pid)... "
            kill "$pid" 2>/dev/null
            for i in $(seq 1 5); do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            kill -9 "$pid" 2>/dev/null || true
            echo -e "${GREEN}已停止${NC}"
        fi
        rm -f "$FRONTEND_PID_FILE"
    fi
}

case "${1:-}" in
    start)
        start_backend
        start_frontend
        echo ""
        status
        ;;
    stop)
        stop_backend
        stop_frontend
        ;;
    restart)
        stop_backend
        stop_frontend
        sleep 1
        start_backend
        start_frontend
        echo ""
        status
        ;;
    status)
        status
        ;;
    log|logs)
        echo "=== 后端日志 (最后 30 行) ==="
        tail -30 "$BACKEND_LOG" 2>/dev/null || echo "(无日志)"
        echo ""
        echo "=== 前端日志 (最后 10 行) ==="
        tail -10 "$FRONTEND_LOG" 2>/dev/null || echo "(无日志)"
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status|logs}"
        echo ""
        echo "  start   - 启动后端 + 前端"
        echo "  stop    - 停止后端 + 前端"
        echo "  restart - 重启"
        echo "  status  - 查看运行状态"
        echo "  logs    - 查看日志"
        exit 1
        ;;
esac
