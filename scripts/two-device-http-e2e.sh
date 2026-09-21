#!/usr/bin/env bash
# 真客户端 ↔ 真服务端 的端到端编排（G23 / M5 第一片）。
#
# 干三件事：在宿主上起一台真服务端（H2 + 种子用户）→ 轮询健康检查 → 用
# -PMAODOU_API_BASE_URL=http://10.0.2.2:<port> 在模拟器里跑指定的 instrumented 测试类。
# 无论成败都清理进程；失败时打印**可诊断输出**（服务端日志尾部 + 用例 XML 摘要）。
#
# 用法：
#   bash scripts/two-device-http-e2e.sh
#   E2E_PORT=18123 E2E_TEST_CLASS=com.maodouchat.e2e.TwoAccountHttpRoundTripTest bash scripts/two-device-http-e2e.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 默认**每次挑一个空闲端口**：固定端口会撞上前一次运行残留的服务端，于是健康检查
# 命中的是旧进程、测试却打在一个已经死掉的端口上（本轮真实踩到，表现为一堆 ConnectException）。
if [[ -n "${E2E_PORT:-}" ]]; then
  PORT="$E2E_PORT"
else
  PORT="$(python3 - <<'PYPORT'
import socket
s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()
PYPORT
)"
fi
# 两个类一起跑：主类（真客户端↔真服务端矩阵）+ 本地数据生命周期类。
# 后者**故意独立**——它的换号清理用例会真的销毁并重建进程内的 app 数据库，
# 混进主类会影响其余 22 个用例。AndroidJUnitRunner 的 `class` 参数接受逗号分隔的多个类。
TEST_CLASS="${E2E_TEST_CLASS:-com.maodouchat.e2e.TwoAccountHttpRoundTripTest,com.maodouchat.e2e.ClientDataLifecycleTest}"
SERVER_LOG="${E2E_SERVER_LOG:-$ROOT/build/e2e-two-device-server.log}"
RESULT_DIR="$ROOT/app/build/outputs/androidTest-results/connected/debug"
SERVER_MAIN="com.maodouchat.server.ApplicationKt"
HEALTH_TIMEOUT_POLLS="${E2E_HEALTH_POLLS:-60}"

mkdir -p "$(dirname "$SERVER_LOG")"

SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    # 只杀本次启动的进程树 + 仍然占着**本次端口**的进程。
    # 刻意不用 `pkill -f $SERVER_MAIN`：那会误杀别的运行（本轮就是这样把旧服务端杀在半路，
    # 造成健康检查命中旧进程、测试却连不上）。
    kill "$SERVER_PID" 2>/dev/null || true
    pkill -P "$SERVER_PID" 2>/dev/null || true
  fi
  local holders
  holders="$(lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$holders" ]]; then
    # shellcheck disable=SC2086
    kill $holders 2>/dev/null || true
  fi
  sleep 1
}
trap cleanup EXIT INT TERM

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[e2e] FAIL: 端口 $PORT 已被占用，拒绝在旧进程上跑（会得到假结果）" >&2
  exit 3
fi

# 反假绿：声明了外部数据库就必须先确认它真的连得上。
# 否则服务端起不来、健康检查超时，脚本只会报「服务端未就绪」——
# 那种失败无法区分「矩阵有问题」和「数据库配错了」。
if [[ "${E2E_DATABASE_URL:-}" == jdbc:postgresql:* ]]; then
  if ! command -v psql >/dev/null 2>&1; then
    echo "[e2e] FAIL: 声明了 PostgreSQL 但本机没有 psql，无法预先校验连通性" >&2
    exit 4
  fi
  pg_target="${E2E_DATABASE_URL#jdbc:postgresql://}"
  pg_host_port="${pg_target%%/*}"
  pg_db="${pg_target#*/}"; pg_db="${pg_db%%\?*}"
  pg_host="${pg_host_port%%:*}"; pg_port="${pg_host_port##*:}"
  [[ "$pg_host" == "$pg_host_port" ]] && pg_port=5432
  pg_user="${E2E_DATABASE_USER:-$(id -un)}"
  if ! psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" -c 'SELECT 1' >/dev/null 2>&1; then
    echo "[e2e] FAIL: 连不上声明的 PostgreSQL（host=$pg_host port=$pg_port db=$pg_db user=$pg_user）" >&2
    echo "[e2e]       这不是矩阵失败，是环境配置失败——修好再跑，不要当成用例红" >&2
    exit 4
  fi
  echo "[e2e] PostgreSQL 连通性预检通过（db=$pg_db）"
fi

echo "[e2e] 启动真服务端 port=$PORT  log=$SERVER_LOG"
# 数据库可切换（G62）：缺省仍走 H2 内存库（快测不变）；
# 设 E2E_DATABASE_URL / E2E_DATABASE_DRIVER 即可把同一套矩阵推到真 PostgreSQL 上跑。
# 这样「H2 快测 / PG 真源」是**同一份 harness、同一套用例**，而不是两份各跑一半的东西。
E2E_DATABASE_URL="${E2E_DATABASE_URL:-jdbc:h2:mem:two-device-http-e2e-$PORT;DB_CLOSE_DELAY=-1}"
E2E_DATABASE_DRIVER="${E2E_DATABASE_DRIVER:-org.h2.Driver}"
E2E_DATABASE_USER="${E2E_DATABASE_USER:-}"
E2E_DATABASE_PASSWORD="${E2E_DATABASE_PASSWORD:-}"
echo "[e2e] 数据库 url=$E2E_DATABASE_URL driver=$E2E_DATABASE_DRIVER"
APP_ENV=development \
HOST=0.0.0.0 \
PORT="$PORT" \
JWT_SECRET=e2e-local-secret-12345678901234567890 \
DATABASE_URL="$E2E_DATABASE_URL" \
DATABASE_DRIVER="$E2E_DATABASE_DRIVER" \
DATABASE_USER="$E2E_DATABASE_USER" \
DATABASE_PASSWORD="$E2E_DATABASE_PASSWORD" \
SEED_DEMO_USERS=true \
AUTH_RATE_LIMIT_PER_MINUTE=100 \
MASTER_ADMINS=u1 \
SMTP_HOST="" \
  bash -c 'cd server && exec ../gradlew run --no-daemon --console=plain' >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

ready=0
for i in $(seq 1 "$HEALTH_TIMEOUT_POLLS"); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/health/ready" 2>/dev/null || true)"
  if [[ "$code" == "200" ]]; then
    echo "[e2e] 服务端就绪（第 ${i} 次探测）"
    ready=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "[e2e] 服务端进程已退出，放弃等待" >&2
    break
  fi
  sleep 3
done

if [[ "$ready" != "1" ]]; then
  echo "[e2e] FAIL: 服务端未在预期时间内就绪" >&2
  echo "---- 服务端日志尾部 ----" >&2
  tail -40 "$SERVER_LOG" >&2 || true
  exit 2
fi

# 必须先删旧结果：编译失败时用例根本没跑，读旧 XML 会得到**假绿/假红**（G21/G22 各踩过一次）。
rm -f "$RESULT_DIR"/*.xml

echo "[e2e] 在模拟器里跑 $TEST_CLASS -- 真实 HTTP，指向 http://10.0.2.2:$PORT"
./gradlew :app:connectedDebugAndroidTest \
  -PMAODOU_API_BASE_URL="http://10.0.2.2:$PORT" \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.e2eHttp=1 \
  --console=plain >"$ROOT/build/e2e-two-device-gradle.log" 2>&1
gradle_status=$?

compile_errors="$(grep -cE '^e: ' "$ROOT/build/e2e-two-device-gradle.log" || true)"
echo "[e2e] gradle 退出码=$gradle_status 编译错误数=$compile_errors"

python3 - "$RESULT_DIR" "$gradle_status" "$compile_errors" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
result_dir, gradle_status, compile_errors = sys.argv[1], sys.argv[2], sys.argv[3]
files = sorted(glob.glob(result_dir + '/*.xml'))
if not files:
    print(f"[e2e] NO-RESULTS：没有任何用例结果（编译错误={compile_errors}，gradle 退出码={gradle_status}）")
    raise SystemExit(3)
tests = failures = errors = 0
for f in files:
    r = ET.parse(f).getroot()
    tests += int(r.get('tests') or 0)
    failures += int(r.get('failures') or 0)
    errors += int(r.get('errors') or 0)
    for tc in r.iter('testcase'):
        bad = list(tc.iter('failure')) + list(tc.iter('error'))
        print(('FAIL ' if bad else 'ok   ') + tc.get('classname', '') + '#' + tc.get('name', ''))
        for b in bad:
            print('      ' + ((b.text or b.get('message') or '').strip().splitlines() or [''])[0][:300])
print(f"[e2e] tests={tests} failures={failures} errors={errors}")
raise SystemExit(0 if (failures == 0 and errors == 0 and tests > 0) else 1)
PY
test_status=$?

if ! kill -0 "$SERVER_PID" 2>/dev/null; then
  echo "[e2e] 警告：测试期间服务端进程已不在（本次结果不可信）" >&2
fi

# 永久诊断：服务端「重启过几次」与「登录过几次」。
# G30 里一次 401 风暴（Token 无效或已过期）因为当时没有这些数字而无法归因——
# 服务端重启会丢掉内存库里的会话，重启次数是最先要看的一个数。
server_starts="$(grep -c 'Responding at' "$SERVER_LOG" 2>/dev/null || echo 0)"
login_attempts="$(grep -c 'login attempt' "$SERVER_LOG" 2>/dev/null || echo 0)"
echo "[e2e] 服务端启动次数=$server_starts 登录尝试次数=$login_attempts"

# 把本轮结果单独留一份：CI 里主 instrumented 套件的结果 XML 也会写在同一目录，
# 若不另存，上传的工件只会反映**最后一次**运行，51 个用例的证据就丢了。
mkdir -p "$ROOT/build/e2e-http-results"
cp "$RESULT_DIR"/*.xml "$ROOT/build/e2e-http-results/" 2>/dev/null || true

echo "[e2e] 清理服务端进程"
cleanup
SERVER_PID=""

if [[ "$test_status" != "0" ]]; then
  echo "---- 服务端日志尾部（诊断） ----" >&2
  tail -40 "$SERVER_LOG" >&2 || true
fi
echo "[e2e] 结束：用例状态=$test_status gradle 状态=$gradle_status"
exit "$test_status"
