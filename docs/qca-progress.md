# QCA 无人值守循环进度

## 环境事实（已实测，不要重复探测）
- 换源环境 maodouchat-mirrors：Gradle 8.14.5 已预置，init.d/mirror.gradle 已把依赖源改到腾讯云/阿里云，冷启动到「server 编译完成」约 6 分钟。
- server/ 不在 root build，跑它的测试用 `./gradlew -p server test`，没有 `:server:test` 这个任务。
- 全量 server test >8 分钟（125 个测试文件，每路由重建 H2 schema）→ 每轮验证用类粒度 --tests。
- 长构建必须后台跑 + 轮询日志，工具 60 秒无输出会看门狗误杀。
- app/ 侧 Android 构建尚未实测（SDK 未预装），不得声称已验证。

## 待办队列（按性价比）
1. ChatDetailRoute 第十二批状态收口：延续第十批的做法，继续把会话级流程开关 / 草稿选择集收进持有类（当前 2721 行）。**注意：本仓库 app/ 在云端无法编译验证，只能静态分析 + 出建议性 PR。** 另：第十一批已在 PR #54（分支 `qca/chatdetail-batch-11`）待人工审，本轮不叠加同族未审 PR，做之前先看 #54 是否已合。
2. ~~`CallSignalingValidators.isValidGroupSignalMetadata` 的查库后分支~~ → 2026-09-26 已完成（见每轮日志）。新遗留：`isValidGroupSignalMetadata` 的 `groupInvite=true + groupId 非空` 路径（调用方传邀请态时行为同 mesh 校验）无专门用例，性价比低，暂不排。
3. `RouteParsing.kt`（73 行，internal，零直接测试）：`receiveBoundedText` 的字节/字符双上限与 9.150 awaitContent 防空转值得钉测，但需 testApplication 挂 echo 路由，超出单轮时间盒。
4. 确认 docs/ 里是否缺 QCA 循环的使用说明。
5. （环境侧观察）progress 里「容器重置后需重打 exposed 兜底」这条可能已过时：本轮容器 `~/.gradle/caches` 里 exposed-dao 0.46.0 齐（pom+module 都在），不带兜底 init 脚本 server 编译+测试全绿。若某轮 :compileKotlin 真的 404 exposed-dao 再重打兜底，且注意**带 content 过滤的仓库加进 pluginManagement 会掐断 kotlin 插件解析**（见 2026-09-26 日志），优先试「只给 allprojects repositories 加、不动 pluginManagement」。

## 环境坑（修在环境侧、不进 git）
- **exposed-dao 兜底可能已不需要**：2026-09-26 实测本轮容器 `~/.gradle/caches` 已含 exposed 0.46.0 全套（含 exposed-dao pom+module），基线换源下 server 编译+测试绿。历史坑（腾讯云缺 exposed-dao 404）若复现再按待办 5 的注意点重打兜底。
- 换源后 server **增量**跑单类测试约 2-3 分钟（首轮全量编译约 6 分钟）。

## 已完成
- 2026-09-25：写入通道实测（前轮）——推分支 + 开 PR（#53）。
- 2026-09-25：`CallSignalingValidators` 纯逻辑单测 13 例，真实跑绿（见每轮日志）。
- 2026-09-26：`isValidGroupSignalMetadata` 查库后分支 + 合法 mesh 2/6 上界，H2 repo 层测试 4 例跑绿（见每轮日志）。

## 每轮日志
<!-- 每轮在此追加一行：日期 | 做了什么 | 实际验证命令与结果 | 未验证项 -->
- 2026-09-25 | 新增 `server/src/test/kotlin/com/maodouchat/server/common/CallSignalingValidatorsTest.kt`（149 行 / 13 用例）：钉死小写连字符信令类型形状、终止信令可空载荷、载荷 32768 上限、callId 100 上限 + 字符集 + 整段锚定、`isValidGroupSignalMetadata` 全部查库前守卫（空 groupId、空/非法 callId、非法 groupId、mesh 越界、重复成员、端点不在名单） | `./gradlew -p server test --tests "com.maodouchat.server.common.CallSignalingValidatorsTest"` → 首跑 13 tests 1 failed（两条合法 mesh 断言穿透守卫触发 `Please call Database.connect()`——测试写错非业务 bug；另踩反引号方法名含 `..` 编译错），删合法路径断言后重跑 → **BUILD SUCCESSFUL，tests="13" failures="0" errors="0"** | 未验证：查库后分支（进待办 2）；exposed-dao 镜像缺件用环境侧兜底绕过，未改仓库构建文件
- 2026-09-26 | 新增 `server/src/test/kotlin/com/maodouchat/server/common/CallSignalingGroupMetadataDbTest.kt`（86 行 / 4 用例）：PerUserStateIsolationRouteTest 同款 repo 层 H2 夹具，补 `isValidGroupSignalMetadata` 查库后三段（chat 不存在、direct 群用、成员不在群里整体拒——配对照正断言防恒 false 假绿）+ 前轮删掉的合法 mesh 2 人/6 人上界 | `./gradlew -p server test --tests "com.maodouchat.server.common.CallSignalingGroupMetadataDbTest"` → **BUILD SUCCESSFUL in 3m1s，tests="4" failures="0" errors="0"**（XML 报告核对） | 未验证：全量 server test（本轮只跑目标类）；`groupInvite=true` 路径无专项用例（进待办 2 备注）。环境插曲：曾按旧日志重打 exposed 兜底 init 脚本，反致 kotlin 插件解析失败（content 过滤仓在 pluginManagement 里会短路后续仓），已删除脚本并实测基线环境本来就全绿——该坑条目已按事实改写
