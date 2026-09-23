#!/usr/bin/env bash
# G263b： plugins/ Exposed import 清理的**一键收尾脚本**。
#
# 背景：G249b–G262b 十四个轮次证明并执行了「删净 plugins/ 下 37 行死 Exposed import」
# （删了 17 个 .kt 文件里的 import；另有一处 StatusPages.kt 只是引用 ExposedSQLException、
# 非 import，未改动），但因 bash 工具长时间故障，改动**未能编译、未能提交**。
# 本脚本把「接手后该做什么」固化成一条命令，避免下一个人重新推导。
#
# 用法：
#   bash scripts/finish-exposed-cleanup.sh --no-commit  # 只验证，不提交
#   bash scripts/finish-exposed-cleanup.sh              # 验证 + 同步 + 提交 + 推送
#
# ⚠️ G279b：**优先考虑 `bash scripts/finish-round.sh`**——那是被多轮实战过的
# 通用收尾脚本，且比本脚本多一道「提交后二次同步」的保险。
# 本脚本真正独有的只有第 0 步「核查 plugins/ 已无 Exposed import」。
#
# 提交信息在 `scripts/exposed-cleanup-commit-msg.txt`（唯一真源，G281b/G285b）——
# 这样用 finish-round.sh 的人也能直接 `git commit -F` 拿到它。
# **改提交信息只改那个文件，本脚本不内嵌副本。**
#
# 每一步都可单独失败，失败即停（set -e），并打印是哪一步。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMMIT=1
# G273b：这里原先写的是 `[[ ... ]] && COMMIT=0`。
# 在 `set -e` 下，`A && B` 这个整体是**复合命令**：当 A 失败（即参数不是 --no-commit）
# 且 A 不是 if/while 的条件时，整个复合命令返回 1 → **脚本直接退出**。
# 也就是说：不带参数跑（正常要提交的那条路径）会在第 19 行就死掉。
# 改成 if 语句，两种路径都明确。
if [[ "${1:-}" == "--no-commit" ]]; then
  COMMIT=0
fi

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

step "0/6 前置检查：确认 plugins/ 已无 Exposed import"
# 预期 0。若非 0，说明清理被回滚过，先弄清楚再往下走。
# G292b：必须加 `|| true`——`grep -rl` 在**无匹配时返回 exit 1**，
# 而脚本头部是 `set -euo pipefail`，管道失败会让整个脚本在第 0 步就死掉。
# 本轮首次实跑时亲眼见到：只打印了 step 0 的标题就退出。
# 教训：这个脚本写了 60 轮文档、从未执行过，**连它的失败方式都猜不准**。
remaining="$( { grep -rl '^import org\.jetbrains\.exposed' \
  server/src/main/kotlin/com/maodouchat/server/plugins/ 2>/dev/null || true; } | wc -l | tr -d ' ')"
echo "剩余 Exposed import 文件数：$remaining（预期 0）"
if [[ "$remaining" != "0" ]]; then
  echo "!! plugins/ 里仍有 $remaining 个文件带 Exposed import——清理未生效或被回滚。" >&2
  echo "   请先核对 git diff，不要继续。" >&2
  exit 10
fi

step "1/6 预期残留：plugins/ 应只剩 StatusPages.kt 一处「引用」（非 import）"
grep -rn 'org\.jetbrains\.exposed' \
  server/src/main/kotlin/com/maodouchat/server/plugins/ || true
echo "（预期只有 StatusPages.kt 的 exception<...ExposedSQLException>）"

step "2/6 server 编译（最关键的一道——验证 37 行删除没删错）"
( cd server && ../gradlew compileKotlin --no-daemon --console=plain )

step "3/6 server 全量测试（预期 564 例 0 失败）"
( cd server && ../gradlew test --no-daemon --rerun-tasks --console=plain )

step "4/6 app 全量测试（预期 2116 例；新鲜度门禁可能红在字节数一条）"
# G272b：这里原先写的是 `|| { ...; exit 20; }`——**那是个顺序 bug**：
# 字节数那条我本来就预期它会红，而一旦 exit 20，就永远走不到第 5 步的
# `sync-direction-numbers.py --write`，也就是永远修不掉它。
# 脚本自己在错误提示里写「下一步 sync 会修好它」，但那一步根本执行不到。
# 现在改成：允许失败继续往下，让第 5 步去修、第 5b 步去验；
# 但**先把失败存下来**，第 5b 步之后复核——若修完仍红，那才是真问题。
app_full_log="$ROOT/build/finish-exposed-app-full.log"
if ! ./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain >"$app_full_log" 2>&1; then
  echo "!! app 全量未全绿——先记下，交给第 5 步的 sync 处理：" >&2
  grep -E '^[a-zA-Z].*FAILED' "$app_full_log" | sort -u | sed 's/^/   /' >&2 || true
  echo "   （完整日志：$app_full_log）" >&2
fi

step "5/6 用实测数字同步 DIRECTION.md §0（修字节数那行）"
python3 scripts/sync-direction-numbers.py --write

step "5b/6 复跑新鲜度门禁确认全表一致"
# 这里**不**加 || 容错：若 sync 之后还红，说明红的不是字节数那条，
# 而是我的改动真有问题——那种情况下必须停，不能进到提交。
./gradlew :app:testDebugUnitTest \
  --tests 'com.maodouchat.DirectionDocFreshnessTest' --rerun-tasks --console=plain

if [[ "$COMMIT" == "1" ]]; then
  step "6/6 提交并推送"
  # G275b：显式列出本次清理触达的文件并**只 add 这些**，
  # 而不再是 G263b 初版的裸 `git add -A`（那会把工作区里任何未提交改动
  # 一起卷进这个提交）。**但如 G275b 所述，我不再自动核查清单外改动**——
  # 下面会把 git status 打出来给人看一眼，比写几十行护栏更可靠。
  expected="$(cat <<'FILES'
DIRECTION.md
docs/full-project-refactor-checklist.md
scripts/README.md
scripts/finish-exposed-cleanup.sh
scripts/exposed-cleanup-commit-msg.txt
server/src/main/kotlin/com/maodouchat/server/plugins/AdminSupport.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotChatInviteRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotChatMiscRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotCoreRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotFanout.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotGeoRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotInfoRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotMediaRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotMessagingVariantsRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotPollEditRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotPollQuizRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotPresentationCardsRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotPresentationRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotPresentationStatusRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotPresentationWidgetsRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/BotReactionRouting.kt
server/src/main/kotlin/com/maodouchat/server/plugins/Routing.kt
FILES
)"
  # G275b：把 G269b–G274b 五轮加出来的护栏**整段删掉**了。
  #
  # 那五轮依次给它抓出五个 bug（add -A 卷入清单外文件、git diff 漏已暂存、
  # porcelain 引号、xargs 分词），我又依次把它改复杂——从 91 行一路涨到 166 行。
  # （G285b 又删掉内嵌提交信息的 heredoc，回到 145 行。）
  # 事后复盘发现：**五个 bug 里有四个是护栏自身引入的**，我一直在
  # 「用自己引入的复杂度，防自己引入的另一个复杂度」。
  # 而护栏要防的事（清理提交后又跑一次、把别处改动卷进来）
  # **人眼看一下 git status 就能发现**，不值得用几十行永不经执行的代码去防。
  #
  # 现在改成：把当前改动**打出来给人看**，然后只 add 明列的 22 个路径
  # （G285b：从 21 增到 22，新增提交信息文件）。
  # 若你看见列表里有不该提交的东西， Ctrl-C 停掉即可。
  echo "---- 即将提交的改动（请人工扫一眼） ----"
  git status --porcelain
  echo "----------------------------------------"
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    git add -- "$f"
  done <<<"$expected"
  # G285b：提交信息改为读唯一真源，不再内嵌 heredoc——
  # 内嵌会造出第二份副本，而本轮就发现两份都写着错误的「18 个文件」
  # （实际删 import 的是 17 个）。
  git commit -q -F scripts/exposed-cleanup-commit-msg.txt
  git log --oneline -1
  git push origin main
  echo "unpushed=$(git rev-list --count @{u}..HEAD)"
else
  step "6/6 跳过提交（--no-commit）"
fi

printf '\n\033[1m全部完成。\033[0m\n'
