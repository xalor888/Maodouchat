#!/usr/bin/env bash
# 毛豆聊天一键测试：在本机运行全部可离线执行的测试，自动定位 JDK 21。
# 用法：
#   ./run-tests.sh               # App 单测 + 服务端全量测试（CI 同款范围，无需设备）
#   ./run-tests.sh --app         # 只跑 App 单测
#   ./run-tests.sh --server      # 只跑服务端测试
#   ./run-tests.sh --lint        # App 单测 + Android Lint（CI 门禁项）
#   ./run-tests.sh --instrumented  # 仪器测试（需要模拟器或真机）
set -euo pipefail
cd "$(dirname "$0")"

# ── 定位 JDK 21（与 build-apk.sh 一致）──
jdk=""
for c in \
  "${JAVA_HOME_21:-}" \
  "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
  "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"; do
  if [ -n "$c" ] && [ -x "$c/bin/java" ]; then jdk="$c"; break; fi
done
if [ -z "$jdk" ]; then
  echo "错误：找不到 JDK 21。请安装：brew install openjdk@21" >&2
  exit 1
fi
export JAVA_HOME="$jdk"
echo "使用 JDK：$JAVA_HOME"
echo ""

mode="${1:-all}"
run_app=0; run_server=0; run_lint=0; run_instrumented=0
case "$mode" in
  --app) run_app=1 ;;
  --server) run_server=1 ;;
  --lint) run_app=1; run_lint=1 ;;
  --instrumented) run_instrumented=1 ;;
  all|"") run_app=1; run_server=1 ;;
  *) echo "未知参数：$mode（支持 --app / --server / --lint / --instrumented）" >&2; exit 1 ;;
esac

if [ "$run_app" = 1 ]; then
  echo "═══ App 单元测试（JVM，无需设备） ═══"
  ./gradlew :app:testDebugUnitTest --console=plain
  echo ""
fi

if [ "$run_lint" = 1 ]; then
  echo "═══ Android Lint（CI 门禁） ═══"
  ./gradlew :app:lintDebug --console=plain
  echo "报告：app/build/reports/lint-results-debug.html"
  echo ""
fi

if [ "$run_server" = 1 ]; then
  echo "═══ 服务端全量测试（内存数据库） ═══"
  ./gradlew -p server test --console=plain
  echo ""
fi

if [ "$run_instrumented" = 1 ]; then
  echo "═══ 仪器测试（需要模拟器或真机） ═══"
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  if "$SDK/platform-tools/adb" devices | grep -qE "device$|emulator"; then
    echo "检测到设备，开始运行…"
    ./gradlew :app:connectedDebugAndroidTest --console=plain
  else
    avds=$("$SDK/emulator/emulator" -list-avds 2>/dev/null || true)
    if [ -n "$avds" ]; then
      avd=$(echo "$avds" | head -1)
      echo "启动模拟器 $avd …"
      "$SDK/emulator/emulator" -avd "$avd" -no-snapshot-save >/dev/null 2>&1 &
      "$SDK/platform-tools/adb" wait-for-device
      while [ "$("$SDK/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do sleep 3; done
      ./gradlew :app:connectedDebugAndroidTest --console=plain
    else
      echo "没有可用模拟器。请先创建一个（二选一）："
      echo "  1) Android Studio → Device Manager → Create Device（推荐，自动下载系统镜像）"
      echo "  2) 或连接一台开启 USB 调试的真机后再跑 ./run-tests.sh --instrumented"
      exit 1
    fi
  fi
  echo ""
fi

echo "✅ 全部测试通过"
