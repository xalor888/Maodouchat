#!/usr/bin/env bash
# 将 WebRTC 原生库 .so 同步到服务端 resources，供自服按需下载（GET /api/webrtc/lib/{abi}）。
#
# 用途：基础 APK 已用 jniLibs.excludes 排除 libjingle_peerconnection_so.so（减 ~9.86MB），
# 首次通话前客户端从本服务下载该 .so 并 System.load 预加载。服务端把 .so 打包在
# classpath 资源中（server/src/main/resources/webrtc/...），首次请求时惰性解压到
# STORAGE_DIR/webrtc/ 后走 Ktor respondFile 提供 Range/断点续传。
#
# 用法：
#   1) 从 Gradle 缓存自动提取（需先构建过 :app）：
#        bash scripts/webrtc-sync-native.sh
#   2) 手动指定 .so 路径：
#        bash scripts/webrtc-sync-native.sh /path/to/libjingle_peerconnection_so.so
#
# 注意：该二进制不进版本库（.gitignore 已忽略），部署/CI 需先执行本脚本。

set -euo pipefail

ABI="arm64-v8a"
DEST="server/src/main/resources/webrtc/${ABI}/libjingle_peerconnection_so.so"
SRC="${1:-}"

if [[ -z "$SRC" ]]; then
  # 从 Gradle 缓存查找 stream-webrtc-android AAR 里的 .so
  SRC=$(find "${HOME}/.gradle/caches" -path "*stream-webrtc-android*" \
    -name "libjingle_peerconnection_so.so" 2>/dev/null | grep "/arm64-v8a/" | head -n1)
fi

if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  echo "错误：未找到 libjingle_peerconnection_so.so。先构建 :app，或手动传入路径。" >&2
  echo "用法: bash scripts/webrtc-sync-native.sh [path/to/libjingle_peerconnection_so.so]" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
cp "$SRC" "$DEST"
echo "已同步: $SRC -> $DEST"
ls -la "$DEST"
echo
echo "提示：确认 sha256 与 app 端一致（客户端用 ETag 做完整性校验）："
sha256sum "$DEST"
