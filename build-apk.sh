#!/usr/bin/env bash
# 毛豆聊天一键编译：自动定位 JDK 21，避免各终端 JAVA_HOME 差异导致编译失败。
# 用法：
#   ./build-apk.sh            # Debug APK（日常安装验证用）
#   ./build-apk.sh --release  # Release APK
set -euo pipefail
cd "$(dirname "$0")"

# ── 定位 JDK 21（与 scripts/use-jdk21.sh 相同优先级）──
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

if [ "${1:-}" = "--release" ]; then
  ./gradlew :app:assembleRelease \
    -PMAODOU_RELEASE_API_BASE_URL="${MAODOU_RELEASE_API_BASE_URL:-https://ci.invalid}" \
    -PMAODOU_RELEASE_WS_URL="${MAODOU_RELEASE_WS_URL:-wss://ci.invalid/ws}"
  echo "完成：app/build/outputs/apk/release/"
else
  ./gradlew :app:assembleDebug
  echo "完成：app/build/outputs/apk/debug/app-debug.apk"
fi
