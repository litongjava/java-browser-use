#!/usr/bin/env bash
#
# start-server - 以后台方式启动 deepseek-browser-use,并等 /playwright/health 通过
#
# 适用平台:macOS 与 Linux(与 Windows 的 scripts/run/start-server.{cmd,ps1} 行为对齐)。
#
# 为什么需要这个脚本:浏览器登录态是**会话级**的 —— 服务进程被杀,它启动的浏览器会跟着退出,
# 而 12306 这类站点的登录 cookie 是 session cookie,于是人工得重新登录一次。实测踩过:agent 的
# 后台任务被回收时带走了整棵进程树,mvn / java / Chrome 一起消失,人工白登录一次。
#
# 所以这里优先把服务放进**新的会话/进程组**再脱离当前进程树:Linux 用 setsid,有 python3 时用
# os.setsid()(macOS 默认没有 setsid),两者都没有才退回 nohup(能挡 SIGHUP,但不一定挡得住
# 按进程组回收,脚本会明确提示)。启动之后等 /playwright/health 通过,并把实际生效的引擎与
# profile 目录打出来。
#
# Usage:
#   scripts/run/start-server.sh [-p 10049] [-e chromium] [--profile-dir <dir>] [--jar <jar>] [-t 90] [-f]
#
#   -p, --port <port>        监听端口,默认 10049(同时决定默认 profile 目录 shared-<端口>)
#   -e, --engine <engine>    chromium(默认) / chrome / edge / firefox,传了覆盖服务端配置
#       --profile-dir <dir>  托管 profile 目录;**换目录等于换一套登录态**
#       --jar <jar>          用发行版 jar 启动(默认走开发态的 mvn spring-boot:run)
#   -t, --timeout <seconds>  等健康检查的最长秒数,默认 90(首次启动要拉起浏览器,慢一点正常)
#   -f, --force              端口上已有服务时也照常再起一个(通常不需要)
#   -h, --help               打印本帮助
#
set -euo pipefail

usage() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

json_escape() { # 只做 JSON 字符串最起码的转义(反斜杠与双引号)
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

http_get() { # url [timeout] -> body;失败返回非 0
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

json_string_field() { # json key -> 第一个匹配的字符串值
  local json=$1
  local key=$2
  printf '%s' "$json" \
    | grep -o "\"$key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
    | head -n 1 \
    | sed -E 's/^"[^"]*"[[:space:]]*:[[:space:]]*"//; s/"$//'
  return 0
}

# ---- 前置检查与失败归因 --------------------------------------------------

# 要用的那个 java 到底能不能跑;回显 "ok <版本行>" 或 "fail <原因>"
#
# 为什么需要:JAVA_HOME 指向一个与本机 CPU 架构不匹配的 JDK 时(macOS arm64 上装了 x86_64 的 JDK),
# java 自己就起不来 —— 实测报 `rosetta error: Attachment of code signature supplement failed`,
# 退出码 134。以前脚本照常往下走、等满超时,最后给一句「端口可能没被 -Dserver.port 覆盖」,
# 把人引到完全无关的方向(实测按那句话查了半天端口)。
java_probe() {
  local bin=$1 out code
  if [ ! -x "$bin" ]; then
    printf 'fail 找不到可执行的 java:%s' "$bin"
    return 0
  fi
  out="$("$bin" -version 2>&1)"
  code=$?
  if [ "$code" -ne 0 ]; then
    printf 'fail %s' "$(printf '%s' "$out" | tr '\n' ' ' | tr -s ' ' | cut -c1-200)"
    return 0
  fi
  printf 'ok %s' "$(printf '%s' "$out" | head -n 1)"
}

java_major() { # 参一:`java -version` 的第一行;回显主版本号(1.8 -> 8)
  local v major
  v="$(printf '%s' "$1" | sed -n 's/.*version "\([0-9][0-9.]*\).*/\1/p' | head -n 1)"
  major="${v%%.*}"
  if [ "$major" = "1" ]; then
    v="${v#1.}"
    major="${v%%.*}"
  fi
  printf '%s' "$major"
}

# 健康检查失败时,从日志里读出「真正的原因」,而不是笼统地让人「再等一会儿」
#
# 实测两种最误导人的失败都出在这里:
#   ① 运行的 JDK 比编译用的版本低 -> 日志里是 UnsupportedClassVersionError(class file version 65.0),
#      即「按 Java 21 编译,现在这个 JRE 只认到 61.0」;进程其实已经退出,日志看起来却像启动慢;
#   ② java 进程本身起不来(架构不匹配)-> err 日志里是 rosetta error / Abort trap。
diagnose_health_failure() {
  local out_log=$1 err_log=$2 text
  text="$(cat "$out_log" "$err_log" 2>/dev/null || true)"
  case "$text" in
    *UnsupportedClassVersionError*)
      echo "  [真正的原因] 运行这个服务的 JDK 版本太低:它是按 Java 21 编译的(class file version 65.0)。" >&2
      echo "               把 JAVA_HOME 指向 JDK 21+ 再启动。" >&2
      return 0 ;;
    *"rosetta error"*|*"Abort trap"*)
      echo "  [真正的原因] 这个 java 在本机根本跑不起来 —— 常见于 JDK 与本机 CPU 架构不匹配" >&2
      echo "               (macOS arm64 上装了 x86_64 的 JDK)。换一个与本机架构一致的 JDK 21+。" >&2
      return 0 ;;
    *"Address already in use"*)
      echo "  [真正的原因] 端口被占用:换一个 --port,或先停掉占用它的进程。" >&2
      return 0 ;;
    *"BUILD FAILURE"*)
      echo "  [真正的原因] Maven 构建/启动失败,真正的错就在上面日志里的 [ERROR] 行。" >&2
      return 0 ;;
    *Downloading*)
      echo "  [正在下载浏览器] 日志里有 Downloading … —— 这是 Playwright 在下载它管理的浏览器" >&2
      echo "               (首次使用或 Playwright 升级后,数百 MB、可能十几分钟),不是失败:下完再跑一次。" >&2
      return 0 ;;
  esac
  echo "  提示:端口可能没被 -Dserver.port 覆盖(看日志里的 'Server port:'),也可能这次启动确实慢 —— 再等一会儿看看。" >&2
}

start_detached() { # 参一:启动器路径;设置 LAUNCHED_PID 与 DETACH_MODE
  local launcher=$1
  if command -v setsid >/dev/null 2>&1; then
    DETACH_MODE="setsid"
    setsid bash "$launcher" >"$OUT_LOG" 2>"$ERR_LOG" </dev/null &
  elif command -v python3 >/dev/null 2>&1; then
    # macOS 默认没有 setsid;借 python3 的 os.setsid() 开一个新会话,再 exec 启动器
    DETACH_MODE="python-setsid"
    python3 -c 'import os, sys; os.setsid(); os.execv(sys.argv[1], [sys.argv[1]])' "$launcher" \
      >"$OUT_LOG" 2>"$ERR_LOG" </dev/null &
  else
    DETACH_MODE="nohup"
    nohup bash "$launcher" >"$OUT_LOG" 2>"$ERR_LOG" </dev/null &
  fi
  LAUNCHED_PID=$!
  disown 2>/dev/null || true
}

# ---- 解析参数 ------------------------------------------------------------
PORT=10049
ENGINE=""
PROFILE_DIR=""
JAR=""
TIMEOUT=90
FORCE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    -p|--port)
      if [ "$#" -lt 2 ]; then echo "--port 需要一个端口" >&2; exit 2; fi
      PORT=$2; shift 2 ;;
    -e|--engine)
      if [ "$#" -lt 2 ]; then echo "--engine 需要一个引擎名" >&2; exit 2; fi
      ENGINE=$2; shift 2 ;;
    --profile-dir)
      if [ "$#" -lt 2 ]; then echo "--profile-dir 需要一个目录" >&2; exit 2; fi
      PROFILE_DIR=$2; shift 2 ;;
    --jar)
      if [ "$#" -lt 2 ]; then echo "--jar 需要一个路径" >&2; exit 2; fi
      JAR=$2; shift 2 ;;
    -t|--timeout)
      if [ "$#" -lt 2 ]; then echo "--timeout 需要一个秒数" >&2; exit 2; fi
      TIMEOUT=$2; shift 2 ;;
    -f|--force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数:$1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$PORT" in
  ''|*[!0-9]*) echo "端口必须是数字,收到:$PORT" >&2; exit 2 ;;
esac
case "$TIMEOUT" in
  ''|*[!0-9]*) echo "超时必须是数字,收到:$TIMEOUT" >&2; exit 2 ;;
esac
case "$ENGINE" in
  ""|chromium|chrome|edge|firefox) ;;
  *) echo "引擎只能是 chromium / chrome / edge / firefox,收到:$ENGINE" >&2; exit 2 ;;
esac

# ---- 路径 ----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$REPO_ROOT/logs/server"
SERVER_DIR="$REPO_ROOT/playwright-server"
mkdir -p "$LOG_DIR"

HEALTH_URL="http://127.0.0.1:$PORT/playwright/health"
PID_FILE="$LOG_DIR/server-$PORT.pid"
OUT_LOG="$LOG_DIR/server-$PORT.out.log"
ERR_LOG="$LOG_DIR/server-$PORT.err.log"
LAUNCHER="$LOG_DIR/run-$PORT.sh"

if is_healthy && [ "$FORCE" -ne 1 ]; then
  echo "服务已经在 $PORT 上跑着(健康检查通过),不重复启动。"
  echo "要重启:先 bash scripts/run/stop-server.sh -p $PORT"
  exit 0
fi

# ---- 前置检查:要用的那个 java 到底能不能跑 ------------------------------
# 放在「已经在跑就早退」之后:只有真要启动时才值得花这点时间。开发态(mvn)直接失败,
# 用发行版 jar 时只告警(有人手上可能是按更低的 Java 版本编出来的老包)。
# 探的必须是「真正会跑起来的那个 java」:发行版那条路的启动器写的是 `java`(由 PATH 决定),
# 开发态的 mvn 优先用 JAVA_HOME。
JAVA_BIN=""
if [ -n "$JAR" ]; then
  JAVA_BIN="$(command -v java || true)"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi
JAVA_PROBE="$(java_probe "${JAVA_BIN:-java}")"
case "$JAVA_PROBE" in
  ok*)
    JAVA_VERSION_LINE="${JAVA_PROBE#ok }"
    JAVA_VERSION_MAJOR="$(java_major "$JAVA_VERSION_LINE")"
    case "$JAVA_VERSION_MAJOR" in
      ''|*[!0-9]*) : ;;
      *)
        if [ "$JAVA_VERSION_MAJOR" -lt 21 ]; then
          if [ -n "$JAR" ]; then
            echo "警告:当前 java 是 $JAVA_VERSION_LINE,低于本仓库要求的 Java 21+;" >&2
            echo "      如果这个 jar 是老版本编出来的可能仍能跑,跑不起来请看下面的日志归因。" >&2
          else
            echo "✗ java 版本太低:$JAVA_VERSION_LINE($JAVA_BIN)" >&2
            echo "  开发态要编译本仓库(pom.xml 里 java.version=21,产物是 class file version 65.0)," >&2
            echo "  低于 21 的 JDK 会在启动时报 UnsupportedClassVersionError。" >&2
            echo "  改法:export JAVA_HOME=<JDK 21+ 目录>(macOS 用 /usr/libexec/java_home -v 21 找)。" >&2
            exit 1
          fi
        fi ;;
    esac ;;
  fail*)
    echo "✗ 这个 java 跑不起来:${JAVA_BIN:-java}" >&2
    echo "  报错:${JAVA_PROBE#fail }" >&2
    if [ -n "${JAVA_HOME:-}" ]; then
      echo "  当前 JAVA_HOME=$JAVA_HOME —— 先确认它指向的 JDK 与本机 CPU 架构一致" >&2
      echo "  (macOS arm64 上装了 x86_64 的 JDK 就是这个报错)。" >&2
    fi
    echo "  改法:换一个 JDK 21+(macOS 看 /usr/libexec/java_home -V;/opt/homebrew/opt/openjdk@21 也常见)。" >&2
    exit 1 ;;
esac

# ---- 拼启动命令 ----------------------------------------------------------
JVM_ARG_LINE="-Dserver.port=$PORT"
if [ -n "$ENGINE" ]; then JVM_ARG_LINE="$JVM_ARG_LINE -Dbrowser.engine=$ENGINE"; fi
if [ -n "$PROFILE_DIR" ]; then JVM_ARG_LINE="$JVM_ARG_LINE -Dbrowser.profileDir=$PROFILE_DIR"; fi

if [ -n "$JAR" ]; then
  if [ ! -f "$JAR" ]; then
    echo "--jar 指向的文件不存在:$JAR" >&2
    exit 2
  fi
  JAR_PATH="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
  WORK_DIR="$REPO_ROOT"
  CMD_LINE="java $JVM_ARG_LINE -jar $(printf '%q' "$JAR_PATH")"
else
  MVN="$(command -v mvn || true)"
  if [ -z "$MVN" ]; then
    echo "找不到 mvn,要么把它加进 PATH,要么用 --jar 指定发行版 jar" >&2
    exit 1
  fi
  # mvn 在 playwright-server 目录里跑;运行目录(repo 根)通过 spring-boot.run.workingDirectory 传进去,
  # 这样 data/ 与 logs/ 都落在仓库里,方便事后翻截图与追踪日志
  WORK_DIR="$SERVER_DIR"
  CMD_LINE="$(printf '%q' "$MVN") spring-boot:run $(printf '%q' "-Dspring-boot.run.jvmArguments=$JVM_ARG_LINE") $(printf '%q' "-Dspring-boot.run.workingDirectory=$REPO_ROOT")"
fi

ENGINE_DISPLAY=$ENGINE
if [ -z "$ENGINE_DISPLAY" ]; then ENGINE_DISPLAY='默认'; fi

# ---- 生成启动器(带 exec,启动器 pid 即 java/mvn,便于整组回收)----------
{
  printf '%s\n' '#!/usr/bin/env bash'
  printf '# 由 scripts/run/start-server.sh 生成:端口 %s,引擎 %s\n' "$PORT" "$ENGINE_DISPLAY"
  printf 'cd %q || exit 1\n' "$WORK_DIR"
  printf 'exec %s\n' "$CMD_LINE"
} > "$LAUNCHER"
chmod +x "$LAUNCHER"

start_detached "$LAUNCHER"

echo "已启动(pid=$LAUNCHED_PID,方式=$DETACH_MODE),等健康检查…"
if [ "$DETACH_MODE" = "nohup" ]; then
  echo "  提示:本机没有 setsid 与 python3,只能 nohup 脱离;若宿主按进程组回收,服务仍可能被带走。"
fi

DEADLINE=$(( $(date +%s) + TIMEOUT ))
HEALTHY=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if is_healthy; then
    HEALTHY=1
    break
  fi
  sleep 0.8
done

if [ "$HEALTHY" -ne 1 ]; then
  echo "等了 $TIMEOUT 秒,http://127.0.0.1:$PORT 还没起来。最后 20 行日志:" >&2
  if [ -f "$OUT_LOG" ]; then echo "--- $OUT_LOG ---" >&2; tail -n 20 "$OUT_LOG" >&2 || true; fi
  if [ -f "$ERR_LOG" ]; then echo "--- $ERR_LOG ---" >&2; tail -n 20 "$ERR_LOG" >&2 || true; fi
  diagnose_health_failure "$OUT_LOG" "$ERR_LOG"
  exit 1
fi

# ---- 记录实际监听进程并落 pid 文件 --------------------------------------
LISTEN_PID="$(listener_pids | head -n 1 || true)"
if [ -z "$LISTEN_PID" ]; then LISTEN_PID=$LAUNCHED_PID; fi

cat > "$PID_FILE" <<EOF
{
  "port": $PORT,
  "pid": $LISTEN_PID,
  "launchedPid": $LAUNCHED_PID,
  "mode": "$(json_escape "$DETACH_MODE")",
  "engine": "$(json_escape "$ENGINE")",
  "profileDir": "$(json_escape "$PROFILE_DIR")",
  "startedAt": "$(date '+%Y-%m-%dT%H:%M:%S')",
  "launcher": "$(json_escape "$LAUNCHER")"
}
EOF

CONFIG="$(http_get "http://127.0.0.1:$PORT/playwright/config" 10 || true)"
echo "服务已就绪:http://127.0.0.1:$PORT"
if [ -n "$CONFIG" ]; then
  echo "  引擎=$(json_string_field "$CONFIG" engine)  托管 profile=$(json_string_field "$CONFIG" resolved)"
else
  echo "  (配置读不出来,健康检查已通过)"
fi
echo "  pid 文件:$PID_FILE"
echo "  停服务:bash scripts/run/stop-server.sh -p $PORT"
exit 0
