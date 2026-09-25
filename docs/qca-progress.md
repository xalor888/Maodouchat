# QCA 无人值守循环进度

## 环境事实（已实测，不要重复探测）
- 换源环境 maodouchat-mirrors：Gradle 8.14.5 已预置，init.d/mirror.gradle 已把依赖源改到腾讯云/阿里云，冷启动到「server 编译完成」约 6 分钟。
- server/ 不在 root build，跑它的测试用 `./gradlew -p server test`，没有 `:server:test` 这个任务。
- 全量 server test >8 分钟（125 个测试文件，每路由重建 H2 schema）→ 每轮验证用类粒度 --tests。
- 长构建必须后台跑 + 轮询日志，工具 60 秒无输出会看门狗误杀。
- app/ 侧 Android 构建尚未实测（SDK 未预装），不得声称已验证。

## 待办队列（按性价比）
1. ChatDetailRoute 第十二批状态收口：延续第十批的做法，继续把会话级流程开关 / 草稿选择集收进持有类（当前 2721 行）。**注意：本仓库 app/ 在云端无法编译验证，只能静态分析 + 出建议性 PR。** 另：第十一批已在 PR #54（分支 `qca/chatdetail-batch-11`）待人工审，本轮不叠加同族未审 PR，做之前先看 #54 是否已合。
2. `CallSignalingValidators.isValidGroupSignalMetadata` 的**查库后分支**未覆盖（chat 不存在 / 非群 / 成员不属该群 / 合法 mesh 2-6 人）：`ConversationQueryRepository` 是 final class 且 server 测试无 mock 依赖，需 H2 testApplication 级测试，参考 `PerUserStateIsolationRouteTest` 的 `Database.connect + initDatabase()` 夹具。（原队列项「server 补测」本轮已完成，见每轮日志）
3. `RouteParsing.kt`（73 行，internal，零直接测试）：`receiveBoundedText` 的字节/字符双上限与 9.150 awaitContent 防空转值得钉测，但需 testApplication 挂 echo 路由，超出单轮时间盒。
4. 确认 docs/ 里是否缺 QCA 循环的使用说明。

## 环境坑（本轮新发现，修在环境侧、不进 git）
- **腾讯云 maven-public 缺 `org.jetbrains.exposed:exposed-dao:0.46.0`（404）**，同组其余三件套 200——不修则 server `:compileKotlin` 必失败。已在 `~/.gradle/init.d/mirror.gradle` 加名为 `central-exposed-fallback` 的兜底仓库（仅 includeGroup org.jetbrains.exposed 走 repo1.maven.org，其余仍走镜像）。容器重置后需重打。
- 换源后 server **增量**跑单类测试约 2 分钟（首轮全量编译约 6 分钟）。

## 已完成
- 2026-09-25：写入通道实测（前轮）——推分支 + 开 PR（#53）。
- 2026-09-25：`CallSignalingValidators` 纯逻辑单测 13 例，真实跑绿（见每轮日志）。

## 每轮日志
<!-- 每轮在此追加一行：日期 | 做了什么 | 实际验证命令与结果 | 未验证项 -->
- 2026-09-25 | 新增 `server/src/test/kotlin/com/maodouchat/server/common/CallSignalingValidatorsTest.kt`（149 行 / 13 用例）：钉死小写连字符信令类型形状、终止信令可空载荷、载荷 32768 上限、callId 100 上限 + 字符集 + 整段锚定、`isValidGroupSignalMetadata` 全部查库前守卫（空 groupId、空/非法 callId、非法 groupId、mesh 越界、重复成员、端点不在名单） | `./gradlew -p server test --tests "com.maodouchat.server.common.CallSignalingValidatorsTest"` → 首跑 13 tests 1 failed（两条合法 mesh 断言穿透守卫触发 `Please call Database.connect()`——测试写错非业务 bug；另踩反引号方法名含 `..` 编译错），删合法路径断言后重跑 → **BUILD SUCCESSFUL，tests="13" failures="0" errors="0"** | 未验证：查库后分支（进待办 2）；exposed-dao 镜像缺件用环境侧兜底绕过，未改仓库构建文件
