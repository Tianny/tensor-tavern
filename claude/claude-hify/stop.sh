#!/usr/bin/env bash
# Hify 停止脚本：按 PID 文件停止前端和后端
# 策略：SIGTERM → 最多等 KILL_WAIT 秒 → 仍存活则 SIGKILL（子进程一并处理）

set -u

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$ROOT_DIR/.run"
KILL_WAIT=10

BACKEND_PID_FILE="$PID_DIR/backend.pid"
FRONTEND_PID_FILE="$PID_DIR/frontend.pid"

# 进程是否存活（僵尸进程视为已死，避免 kill -0 误判）
alive() {
  ps -p "$1" -o stat= 2>/dev/null | grep -qv '^Z'
}

# stop_by_pidfile <名称> <PID文件> <命令匹配正则>
stop_by_pidfile() {
  local name="$1" pidfile="$2" pattern="$3"

  if [ ! -f "$pidfile" ]; then
    echo "[SKIP] ${name}：PID 文件不存在（${pidfile}）"
    return 0
  fi

  local pid
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  if ! [[ "$pid" =~ ^[0-9]+$ ]]; then
    echo "[WARN] ${name}：PID 文件内容无效，已删除（${pidfile}）"
    rm -f "$pidfile"
    return 0
  fi

  if ! alive "$pid"; then
    echo "[SKIP] ${name}：进程已不存在（PID ${pid}），清理 PID 文件"
    rm -f "$pidfile"
    return 0
  fi

  # 防止 PID 复用误杀无关进程：核对进程命令是否匹配
  if ! ps -p "$pid" -o command= 2>/dev/null | grep -Eq "$pattern"; then
    echo "[WARN] ${name}：PID ${pid} 的命令与预期不符（应为 ${pattern}），跳过，请人工确认"
    return 1
  fi

  echo "[INFO] ${name}（PID ${pid}）：发送 SIGTERM（子进程一并发送）"
  pkill -TERM -P "$pid" 2>/dev/null   # npm 的子进程（vite/node）不会被孤儿化
  kill -TERM "$pid" 2>/dev/null

  local i
  for i in $(seq 1 "$KILL_WAIT"); do
    if ! alive "$pid"; then
      echo "[INFO] ${name}：已优雅退出"
      rm -f "$pidfile"
      return 0
    fi
    sleep 1
  done

  echo "[WARN] ${name}：${KILL_WAIT}s 内未退出，发送 SIGKILL"
  pkill -KILL -P "$pid" 2>/dev/null
  kill -KILL "$pid" 2>/dev/null
  sleep 1
  if alive "$pid"; then
    echo "[FAIL] ${name}：PID ${pid} 无法杀死，请人工处理"
    return 1
  fi
  echo "[INFO] ${name}：已被强制终止"
  rm -f "$pidfile"
}

# 先停前端（依赖后端），再停后端
rc=0
stop_by_pidfile "前端" "$FRONTEND_PID_FILE" 'vite|npm|node' || rc=1
stop_by_pidfile "后端" "$BACKEND_PID_FILE" 'hify-app.*\.jar' || rc=1

# 收尾检查端口
echo
for port in 8080 5173; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[WARN] 端口 ${port} 仍被占用，可能存在残留进程"
    rc=1
  fi
done
[ "$rc" -eq 0 ] && echo "[INFO] 前后端均已停止"

exit "$rc"
