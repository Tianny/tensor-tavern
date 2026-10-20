#!/usr/bin/env bash
# Hify 一键启动脚本
# 流程：检查 MySQL/Redis → 构建后端 → 后台启动后端 → 轮询健康检查 → 前台启动前端
# 任何一步失败立即退出（退出码 1）；Ctrl+C / 任一步失败时，前后端进程一并清理。

set -u

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEB_DIR="$ROOT_DIR/hify-web"
PID_DIR="$ROOT_DIR/.run"          # PID 文件目录（stop.sh 依据这里停止进程）
BACKEND_LOG="/tmp/hify-backend.log"
FRONTEND_LOG="/tmp/hify-frontend.log"
BACKEND_HEALTH_URL="http://127.0.0.1:8080/api/v1/health"
BACKEND_WAIT_SECONDS=60

BACKEND_PID=""
FRONTEND_PID=""

# 进程是否存活（僵尸进程视为已死，避免 kill -0 误判）
alive() {
  ps -p "$1" -o stat= 2>/dev/null | grep -qv '^Z'
}

# 统一清理：前后端一并停止，最多各等 5s 优雅退出
# （Spring 关闭需要几秒，不等的话紧接着 make start 会撞"端口 8080 已被占用"，
#   而此时 PID 文件已删，stop.sh 也帮不上忙）
cleanup() {
  if [ -n "$FRONTEND_PID" ] && alive "$FRONTEND_PID"; then
    pkill -TERM -P "$FRONTEND_PID" 2>/dev/null   # vite 是 npm 的子进程，先杀子再杀父防孤儿
    kill "$FRONTEND_PID" 2>/dev/null
  fi
  if [ -n "$BACKEND_PID" ] && alive "$BACKEND_PID"; then
    echo "[INFO] 停止后端进程 (PID $BACKEND_PID)"
    kill "$BACKEND_PID" 2>/dev/null
  fi
  for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
    [ -n "$pid" ] || continue
    for _ in 1 2 3 4 5; do
      alive "$pid" || break
      sleep 1
    done
  done
  rm -f "$PID_DIR/backend.pid" "$PID_DIR/frontend.pid"
}

fail() {
  echo "[FAIL] $1" >&2
  cleanup
  exit 1
}

# Ctrl+C（INT）/ kill（TERM）：清理前后端后以 130 退出
trap 'cleanup; exit 130' INT TERM

step() { echo; echo "==> $1"; }

# ---------- 0. 依赖工具 ----------
step "检查依赖工具"
for cmd in nc mvn java npm; do
  command -v "$cmd" >/dev/null 2>&1 || fail "缺少命令：${cmd}，请先安装"
done
echo "nc / mvn / java / npm 均可用"

# ---------- 1. MySQL / Redis ----------
step "检查 MySQL (127.0.0.1:3306)"
nc -z -w 2 127.0.0.1 3306 || fail "MySQL 不可用，请先启动：docker start my-mysql"
echo "MySQL 可用"

step "检查 Redis (127.0.0.1:6379)"
nc -z -w 2 127.0.0.1 6379 || fail "Redis 不可用，请先启动：docker start my-redis"
echo "Redis 可用"

if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  fail "端口 8080 已被占用，请先停止已有后端进程"
fi

# ---------- 2. 构建后端 ----------
step "构建后端（跳过测试）"
(cd "$ROOT_DIR" && mvn -q -pl hify-app -am -DskipTests package) || fail "后端构建失败"
BACKEND_JAR="$(ls "$ROOT_DIR"/hify-app/target/hify-app-*.jar 2>/dev/null | grep -v original | head -n 1)"
[ -n "$BACKEND_JAR" ] || fail "构建成功但未找到 jar：hify-app/target/ 下没有 hify-app-*.jar"
echo "构建完成：$BACKEND_JAR"

# ---------- 3. 后台启动后端 ----------
step "后台启动后端（日志：${BACKEND_LOG}）"
nohup java -jar "$BACKEND_JAR" > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
mkdir -p "$PID_DIR" && echo "$BACKEND_PID" > "$PID_DIR/backend.pid"
echo "后端 PID：${BACKEND_PID}（PID 文件：$PID_DIR/backend.pid）"

# ---------- 4. 轮询健康检查 ----------
step "等待后端就绪（最长 ${BACKEND_WAIT_SECONDS}s）"
ready=0
for _ in $(seq 1 "$BACKEND_WAIT_SECONDS"); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "后端进程异常退出，日志末尾：" >&2
    tail -n 30 "$BACKEND_LOG" >&2
    fail "后端启动失败"
  fi
  resp="$(curl -s --max-time 2 "$BACKEND_HEALTH_URL" 2>/dev/null || true)"
  if echo "$resp" | grep -q '"code":200'; then
    ready=1
    echo "后端已就绪：$resp"
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "健康检查超时，日志末尾：" >&2
  tail -n 30 "$BACKEND_LOG" >&2
  fail "后端 ${BACKEND_WAIT_SECONDS}s 内未就绪：$BACKEND_HEALTH_URL"
fi

# ---------- 5. 启动前端 ----------
step "启动前端"
[ -d "$WEB_DIR/node_modules" ] || fail "前端依赖未安装，请先执行：cd hify-web && npm install"
cd "$WEB_DIR" || fail "进入 hify-web 目录失败"
nohup npm run dev > "$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!
echo "$FRONTEND_PID" > "$PID_DIR/frontend.pid"
echo "前端地址：http://localhost:5173（日志：${FRONTEND_LOG}）"
echo "前端 PID：${FRONTEND_PID}（PID 文件：$PID_DIR/frontend.pid）"
echo "Ctrl+C 同时停止前后端；单独停止后台运行的前后端用 ./stop.sh"
# 不用 wait：macOS bash 3.2 在 wait 期间收到 SIGINT 会把 trap 推迟到 wait 返回才执行，
# 信号会被吞掉。轮询前台 sleep，每轮之间 trap 都能正常触发（1s 内响应 Ctrl+C）。
while kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done
