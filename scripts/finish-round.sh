#!/usr/bin/env bash
# 收尾一轮改动（G189b）。
#
# 为什么需要它：G156b / G172b / G183b / G185b / G188b 反复出现同一类失误——
# **顺序靠记忆**。「先追加台账、再同步 DIRECTION.md 的数字」这条规则我违反过五次；
# 每次都是同步完数字，回头又补了几句台账，字节数变了、门禁再红。
#
# 这个脚本把整条顺序固化成一次执行，人不再需要记得顺序：
#   1. 跑全量 app JVM 测试
#   2. 跑全量 server 测试
#   3. 把台账条目追加进 docs/full-project-refactor-checklist.md
#   4. 同步 DIRECTION.md §0 的数字（此时字节数/用例数都已定）
#   5. 复核新鲜度门禁
# ⚠️ 顺序为什么是「先测试、后追加台账」：字节数在追加后才会变，
# 所以同步必须晚于追加；而测试**不依赖**台账，可以最先前置跑。
# （第一版把追加放最前，结果第 2 步跑全量测试时门禁就因为字节数过期而红。）
#
# 用法：
#   bash scripts/finish-round.sh <台账条目文件> [提交信息...]
#
# 条目文件是纯文本，会原样追加到台账末尾（请以空行 + "### Gxxx — 标题" 开头）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENTRY="${1:?用法: bash scripts/finish-round.sh <台账条目文件> [提交信息...]}"
shift || true
[[ -f "$ENTRY" ]] || { echo "条目文件不存在: $ENTRY" >&2; exit 2; }

log() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

log "1/6 全量 app JVM"
if ! ./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain; then
    echo "app JVM 失败——先修测试，再重跑本脚本" >&2
    exit 1
fi

log "2/6 全量 server"
if ! (cd server && ../gradlew test --no-daemon --rerun-tasks --console=plain); then
    echo "server 失败——先修测试，再重跑本脚本" >&2
    exit 1
fi

log "3/6 追加台账条目（同步之前）"
cat "$ENTRY" >> docs/full-project-refactor-checklist.md
echo "台账现有 $(wc -l < docs/full-project-refactor-checklist.md) 行"

log "4/6 同步 DIRECTION.md §0 数字"
python3 scripts/sync-direction-numbers.py --write || {
    echo "同步被拒绝（多半是用例数不合理，见上面的提示）" >&2
    exit 1
}

log "5/6 复核新鲜度门禁"
if ! ./gradlew :app:testDebugUnitTest \
        --tests 'com.maodouchat.DirectionDocFreshnessTest' \
        --rerun-tasks --console=plain; then
    echo "门禁仍红——按失败信息手工检查" >&2
    exit 1
fi

log "6/6 提交"
git add -A
if [[ $# -gt 0 ]]; then
    git commit -q -m "$*"
else
    git commit -q -m "docs: 本轮记录与 DIRECTION.md 数字同步"
fi
git log --oneline -1
printf '\n\033[1m收尾完成。\033[0m 剩余未推送提交: %s\n' "$(git rev-list --count @{u}..HEAD 2>/dev/null || echo '?')"
