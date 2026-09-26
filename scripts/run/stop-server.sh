#!/usr/bin/env bash
#
# stop-server - 停掉 deepseek-browser-use(先关任务与共享浏览器,再结束进程树,不留孤儿浏览器)
#
# 适用平台:macOS 与 Linux(与 Windows 的 scripts/run/stop-server.{cmd,ps1} 行为对齐)。
#
# 顺序很重要:**先关浏览器,再杀服务**。浏览器是独立进程,直接 kill 服务会把它留下来,
# 它继续占着 profile 目录,下一次 start 可能卡到启动超时(默认 60 秒)。
#
# 所以这里:
#   1. 调一次 /playwright/command 的 shutdown(关掉全部任务与共享浏览器);
#   2. 再按端口找到监听进程,连同它所在的进程组/子进程一起结束(开发态下是 mvn → java 两层);
#   3. 清理 pid 文件与启动器。
#
# 注意:关掉最后一个任务时浏览器就退出了,而 session cookie 型的登录态(12306 等)会随之失效 ——
# 持久 cookie 不受影响。所以「重启服务」这个动作本身是有代价的,别把它当成无痛操作。
#
# Usage:
#   scripts/run/stop-server.sh [-p 10049] [--keep-browser] [-q]
#
#   -p, --port <port>    服务端口,默认 10049
#       --keep-browser   只杀服务进程,不动浏览器(默认不做:那正是会留下孤儿浏览器的做法)
#   -q, --quiet          少打印
#   -h, --help           打印本帮助
#
set -euo pipefail

usage() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

info() {
  if [ "$QUIET" -ne 1 ]; then echo "$@"; fi
}

http_post() { # url body -> body;失败返回非 0
  local url=$1
  local body=$2
  local timeout=30
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time "$timeout" -X POST -H 'Content-Type: application/json' -d "$body" "$url" 2>/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T "$timeout" -O - --header='Content-Type: application/json' --post-data="$body" "$url" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$url" "$body" <<'PY'
import sys, urllib.request
req = urllib.request.Request(sys.argv[1], data=sys.argv[2].encode("utf-8"),
                             headers={"Content-Type": "application/json"}, method="POST")
try:
    print(urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace"))
except Exception:
    sys.exit(1)
PY
  else
    return 127
  fi
}

http_get() { # url [timeout] -> body
  local url=$1
  local timeout=3
  if [ "$#" -ge 2 ]; then timeout=$2; fi
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time "$timeout" "$url" 2>/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T "$timeout" -O - "$url" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$url" "$timeout" <<'PY'
import sys, urllib.request
try:
    print(urllib.request.urlopen(sys.argv[1], timeout=float(sys.argv[2])).read().decode("utf-8", "replace"))
except Exception:
    sys.exit(1)
PY
  else
    return 127
  fi
}

is_healthy() {
  local body
  body="$(http_get "http://127.0.0.1:$PORT/playwright/health" 3)" || return 1
  body="$(printf '%s' "$body" | tr -d '[:space:]')"
  case "$body" in
    *'"ok":true'*) return 0 ;;
    *) return 1 ;;
  esac
}

listener_pids() {
  local pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)"
  fi
  if [ -z "$pids" ] && command -v ss >/dev/null 2>&1; then
    pids="$(ss -ltnp 2>/dev/null | awk -v p=":$PORT" '$4 ~ p { if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4) }' | sort -u)"
  fi
  if [ -z "$pids" ] && command -v fuser >/dev/null 2>&1; then
    pids="$(fuser -n tcp "$PORT" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' || true)"
  fi
  if [ -n "$pids" ]; then printf '%s\n' $pids; fi
  return 0
}

kill_tree() { # pid signal:先整组 SIG,再递归子进程兜底
  local pid=$1
  local sig=$2
  if [ -z "$pid" ] || [ "$pid" -le 1 ] 2>/dev/null; then return 0; fi
  local pgid
  local mypgid
  pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
  mypgid="$(ps -o pgid= -p $$ 2>/dev/null | tr -d ' ' || true)"
  if [ -n "$pgid" ] && [ "$pgid" != "1" ] && [ "$pgid" != "$mypgid" ]; then
    kill -"$sig" "-$pgid" 2>/dev/null || true
  fi
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child" "$sig"
  done
  kill -"$sig" "$pid" 2>/dev/null || true
  return 0
}

# ---- 解析参数 ------------------------------------------------------------
PORT=10049
KEEP_BROWSER=0
QUIET=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    -p|--port)
      if [ "$#" -lt 2 ]; then echo "--port 需要一个端口" >&2; exit 2; fi
      PORT=$2; shift 2 ;;
    --keep-browser) KEEP_BROWSER=1; shift ;;
    -q|--quiet) QUIET=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数:$1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$PORT" in
  ''|*[!0-9]*) echo "端口必须是数字,收到:$PORT" >&2; exit 2 ;;
esac

# ---- 路径 ----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$REPO_ROOT/logs/server"
PID_FILE="$LOG_DIR/server-$PORT.pid"
LAUNCHER="$LOG_DIR/run-$PORT.sh"

# 1) 先让服务自己把任务与浏览器关掉(不留孤儿)
if [ "$KEEP_BROWSER" -ne 1 ]; then
  if RESPONSE="$(http_post "http://127.0.0.1:$PORT/playwright/command" '{"method":"shutdown","params":{}}')"; then
    info "shutdown 回执:$RESPONSE"
  else
    info "shutdown 没成功(服务可能已经没在跑)"
  fi
  sleep 0.8
fi

# 2) 按端口找监听进程,连同进程组/子进程一起结束
PIDS="$(listener_pids)"
if [ -z "$PIDS" ]; then
  info "端口 $PORT 上没有监听进程,服务应该已经停了。"
else
  for OWNER in $PIDS; do
    info "结束进程 pid=$OWNER 以及它的进程组/子进程"
    kill_tree "$OWNER" TERM
  done
fi

# 2b) 端口上没有监听进程时,退回 pid 文件里记的 pid(可能端口在 shutdown 后已释放)
if [ -z "$PIDS" ] && [ -f "$PID_FILE" ]; then
  FILE_PID="$(grep -o '"pid"[[:space:]]*:[[:space:]]*[0-9]*' "$PID_FILE" | grep -o '[0-9]*$' | head -n 1 || true)"
  if [ -n "$FILE_PID" ] && kill -0 "$FILE_PID" 2>/dev/null; then
    info "按 pid 文件结束残留进程 pid=$FILE_PID"
    kill_tree "$FILE_PID" TERM
  fi
fi

# 3) 等端口不再应答;顽固进程补一发 KILL
WAITED=0
while [ "$WAITED" -lt 12 ] && is_healthy; do
  sleep 0.5
  WAITED=$((WAITED + 1))
done
if is_healthy; then
  for OWNER in $PIDS; do
    info "端口 $PORT 还在应答,对 pid=$OWNER 补一发 KILL"
    kill_tree "$OWNER" KILL
  done
  WAITED=0
  while [ "$WAITED" -lt 10 ] && is_healthy; do
    sleep 0.5
    WAITED=$((WAITED + 1))
  done
fi

# 4) 清 pid 文件与生成的启动器
if [ -f "$PID_FILE" ]; then rm -f "$PID_FILE" || true; fi
if [ -f "$LAUNCHER" ]; then rm -f "$LAUNCHER" || true; fi

if is_healthy; then
  echo "端口 $PORT 还在应答,可能没杀干净(用 lsof -nP -iTCP:$PORT -sTCP:LISTEN 看看)" >&2
  exit 1
fi
info "服务已停止($PORT)。"
exit 0
