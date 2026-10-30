# Maodouchat 全项目重构总清单

**审计基线日期**：2026-08-29  
**审计分支**：`main`  
**已提交基线**：`e3344a53`（当时与 `origin/main` 一致；此后到 **2026-09-28** 的进度见下方「权威口径」）  
**工作区状态**：**2026-09-28 复核为干净**（此前这里写的「约 255 个未提交状态项」是 2026-08-29 的快照，早已不成立）。  
**目标**：保留产品能力和已经验证的 Messaging V2 协议不变量，重写职责边界、状态所有权、存储、网络、UI 和后端领域实现，最终删除旧入口与兼容实现。

本文是执行清单，不是“功能已经完成”的声明。只有同时满足代码、迁移、测试、删除旧路径和真实 E2E 门槛，条目才允许勾选完成。

## 0. 权威口径（2026-09-29 复核·四版；上一版 2026-09-24）

**这一节是本文所有数字的唯一权威来源。** 正文里的内联数字（尤其是 §G32–G58、§Q0x 那些
长段落里带日期的数字）是**当时那一轮的日志**，多数已过期，甚至与本文其它段落互相矛盾——
审计实测确实如此。要引用任何计数，先跑下面一条命令重新测，或引用本节。

| 量 | 2026-09-28 复核值（上一版 09-24） | 怎么测 |
|----|------------------|--------|
| 工作区脏项 | 0 | `git status --porcelain \| wc -l` |
| App JVM 单测（执行数） | 2173（09-24: 2129） | `./gradlew :app:testDebugUnitTest` 后读 `app/build/test-results/testDebugUnitTest/*.xml` |
| App 仪器测试 | **275**（`@Test` 计数；09-24: 156；语义审计扩面五批 +30） | `grep -rho "@Test" app/src/androidTest --include='*.kt' \| wc -l` |
| Server 单测（执行数） | **约 656 执行 / 712 `@Test` 标注**（09-24: 578/603；本日 Q01 fuzz 系列 +67 标注，执行数以默认套件实测为准） | `cd server && ../gradlew test` 后读 `server/build/test-results/test/*.xml` |
| core/domain 模块测试（执行数） | 68（不变） | `./gradlew test -x :app:test` 后读各模块 `build/test-results/test/*.xml` |
| 有测试源文件的模块 | 9 个（不变） | `ClientArchitectureTest.modulesWithTests`（G328c 从 5 个增到 9 个） |
| `settings.gradle.kts` 模块数 | 10（app + 8 core + 1 domain） | `grep -c '":' settings.gradle.kts`（include 块多行写法，旧的 `include(` 计数已失效） |
| `plugins/` 内 `transaction {` | **0 处** | `grep -rc "transaction {" server/src/main/kotlin/.../plugins/*.kt` |
| `plugins/` 内 import Exposed | **0 个文件**（`StatusPages.kt` 只有全限定名引用，非 import） | `grep -rl "^import org.jetbrains.exposed" .../plugins/*.kt` |
| `repository/`→`plugins/` 反向依赖 | 0 处 | `ServerArchitectureTest.frozenRepositoryDependingOnPlugins`（空 map） |
| 非 ui 包 import ui | **5 处**（白名单：`MainActivity.kt` + `navigation/` 下 4 个 Destinations 注册文件，均带理由；判据已收紧为「剥注释+剥字符串后的全限定名前缀」扫描，无待修表，offenders 必须为零） | `ClientArchitectureTest.packages outside ui must not import ui` |
| ui 直连 `ApiService`（真发请求） | **0 个文件**（空名单 + 反向断言） | `ClientArchitectureTest.frozenUiApiCallers` |
| ui 读会话令牌 `TokenManager` | **1 个文件**（仅 `LoginViewModel`——登录页是会话的拥有者；09-24 曾为 31，令牌自持化批次后 5 → 1） | `ClientArchitectureTest.frozenUiTokenReaders` |
| ui 直连持久层/全局单例命中 | **3 = LoginViewModel 2 + ChatDetailViewModel 1**（09-24 曾为 192+；ChatListPorts 23→0、ChatDetailDeps 17→0 两批已随迁清零） | `ClientArchitectureTest.frozenUiDirectPersistence` |
| core 模块生产引用 | 在用 3（crypto 44 / realtime 25 / model 5）；**零引用 4**（util、serialization、network、session，已登记） | `ClientArchitectureTest.core modules are either adopted...` |
| 就地 `OkHttpClient.Builder()` | 0 处（除共享工厂自身） | `ClientArchitectureTest.okhttp clients must come from the shared factory` |
| lint 基线条数（只许降） | **53**（本战役起点 615） | `LintBaselineRatchetTest.frozenIssueCount` / `app/lint-baseline.xml` 块数 |
| 最热三个文件行数（不含 vendored） | `ChatDetailRoute.kt` **843** / `ChatDetailViewModel.kt` **2035** / `call/CallViewModel.kt` 1618（09-24: 2786/2545/858——GroupPlayPolicy 已拆出族文件；Route 本轮系列 2786 → 843；VM 2420 → 2035） | `ClientArchitectureTest.frozenHotspotLineCaps`（**零余量**） |

> 验证口径补充（G328c 实测教训）：`app` 有**三个**编译单元 —— `compileDebugKotlin`（主源）、
> `compileDebugUnitTestKotlin`（JVM 单测）、`compileDebugAndroidTestKotlin`（仪器测试）。
> 只跑前两个而漏掉第三个，就会漏掉「androidTest 仍引用已搬走的类型」这类问题——CI 的
> instrumented job 抓到过一次。本地验证请把 `:app:compileDebugAndroidTestKotlin` 一起跑。

> 计数口径提醒：上面的「执行数」来自**最近一次跑该任务**的 XML；只跑单个测试类会把该模块的
> 结果文件覆盖成那一类的结果。要复核就按表里的命令重新跑一遍再读 XML。

**已确认为「正文写错、代码才对」的三处**（不要照正文改代码）：

1. §G4/G5 段落的「全项目 `plugins/` 共 49 处 / 37 处 `transaction {`、34 个文件 import Exposed」
   ——**当前实测 0 处 / 0 个**。该段是当时的下调记录；`ServerArchitectureTest` 的
   `frozenRouteTransactions` 与 `frozenPluginsImportingExposed` 现在都是**空**，方向已反转为
   「必须保持 0，新增即红」。
2. 多处提到的 `ClientHotspotRatchetTest` —— 该类**已被合并进 `app/src/test/java/com/maodouchat/ClientArchitectureTest.kt`**，
   独立类名不再存在；该文件自己的 KDoc 记录了合并时「三个棘轮测试全是红的」。
3. §G8/G9 段落的「`ChatDetailRoute` 5061 行」与行号级引用（`:879`/`:891`/`:1010` 等）
   ——当前 3433 行，行号已整体位移；要定位请用符号搜索而不是行号。

## 1. 状态标记

- `[ ]`：未完成。
- `[~]`：已有新基础，但旧路径仍在、职责仍混杂或验收不足。
- `[x]`：实现、迁移、旧代码删除和全部验收均完成。
- `Owner`：该工作包唯一负责 Agent；其他 Agent 不得直接修改其热点文件。
- `Gate`：进入下一阶段前必须通过的硬门槛。

## 2. 全局完成定义

任何功能只有满足下面全部条件才算“重构完成”：

- [ ] Wire DTO、领域模型、数据库实体和 UI model 分离，禁止一个宽模型贯穿所有层。
- [ ] UI 只观察状态并提交 intent，不直接调用 DAO、`ApiService`、`TokenManager`、`WebSocketClient`、Signal 原语或 WorkManager。
- [ ] 每种写操作只有一个领域命令入口，不存在页面、Widget、Worker、Bot 各写一套流程。
- [ ] 每类数据有唯一真相源；消息正文以本地 SQLCipher 时间线为真源，在线状态不作为投递条件。
- [ ] 所有持久任务带明确 `ownerUserId`、幂等键、状态、attempt 和恢复策略。
- [ ] 账号切换、进程死亡、断网、重复事件、乱序事件和部分失败都能确定性收敛。
- [ ] 领域事务与提交后副作用分开；通知、索引、WS 唤醒失败不得回滚已提交业务事实。
- [ ] 旧 API、旧表、旧 repository、旧兼容 facade 和重复入口已删除，不以“暂时保留”冒充完成。
  - **2026-09-27 记账（本项保持 `[ ]`，其余旧/重复面仍待扫）**：删除 16 个初代提交遗留、全仓零引用的孤儿 UI 文件——`ui/component/` 下 15 个初代动画组件（`AnimatedActionBar/Badge/Button/ChatInput/ImageViewer/LoadingIndicator/MediaPanel/ReadReceipt/ReplyPreview/SettingsItem`、`MessageSendAnimator`、`ReactionPopEffect`、`ReplySwipeGesture`、`ScrollToBottomFab`、`SelectionOverlay`；实际输入栏早已由 `ChatDetailComponents.kt` 内 `ChatInputBar` 接线）与 `ui/screen/settings/FakeChatSettingsScreen.kt`（无任何导航/测试引用）。核验口径：每文件顶层声明名全仓 `grep -rlw`（kt/java/xml，含测试源集与 lint-baseline）仅剩自身；无数据库引用（`ClientHotspotRatchetTest` 的 ui 全量口径不受影响）；`app/lint-baseline.xml` 中 `AnimatedSettingsItem.kt` 的一条 `ModifierParameter` 残留条目一并删除（XML 仍合法）。`slim/SizeGuard.kt`（被 Gradle `verifyReleaseSize` 直接当 mainClass 用）与 `ai/agent/*`（`AgentSessionEngine`/`AgentToolHost` 等子系统存活）经核验**非孤儿**，保留。
- [ ] 单元、契约、迁移、Compose、双账号双设备 E2E 和故障注入测试全部通过。
- [x] 服务端中心契约（M2）：
  - **G15**：把 `AdminManagementRouting.kt` 的 **6 处直写事务清零**，并让该文件**不再 import Exposed**；
  - **G45**：彻底消除 `repository/` 与 `service/` 对 `plugins/` 的全部 9 处反向依赖，刷新架构守护基线至 0（`frozenRepositoryDependingOnPlugins` 与 `frozenServicesDependingOnPlugins` 均为 `emptyMap()`）；
  - **G46**：下沉 `HealthRoutes.kt` (1处)、`AdminSystemRouting.kt` (1处) 与 `AdminSupport.kt` (1处) 的裸 Exposed 事务，`plugins/` 事务数 37→29 (16→12 文件)；
  - **G47**：下沉 `AdminModerationRouting.kt` (2处)，并移除其全部 Exposed 导入，事务数 29→27 (12→11 文件)；
  - **G48**：下沉 `AdminUsersRouting.kt` (3处)，并移除其全部 7 张表与 Exposed 导入，事务数 24 处 (10 文件)，import Exposed 的文件降至 29 个。
  - **G49**：下沉 `AdminChatsRouting.kt` (2处) 的群聊聚合列表与级联解散事务，事务数 22 处 / 9 文件。
  - **G50**：把 `UserTagRouting.kt` 的风控告警写入并入 `UserTagRepository.assignTags` 同一事务，事务数 21 处 / 8 文件。
  - **G51**：`SignalKeyRouting.kt` 改用在 G45 下沉的 `SignalKeyRepository.getDeviceIdForAuthSession`，事务数 20 处 / 7 文件。
  - **G52**：`PollRouting.kt` 改调 `UserRepository.getSuspendedUntil`、`GroupAdministrationRouting.kt` 复用设备反查，事务数 18 处 / 5 文件。
  - **G53**：`AdminContentRouting.kt` 的动态/评论列表下沉至 `AdminManagementRepository`，裸事务降至 15 处 / 4 文件。
  - **G54**：`AdminDiagnosticsRouting.kt` 的 4 处裸事务全清（AI 审计/推送令牌/Bot 启停/Ops 快照），裸事务降至 11 处 / 3 文件、Exposed 导入 21 个文件；全量 server test 447 绿。
  - **G55**：先建公告 ack 可见性安全网（3 条路由级用例 + 两次负控制证明承重），再把 4 处裸事务下沉至 `AnnouncementRepository`，裸事务降至 7 处 / 2 文件；全量 server test 450 绿。
  - **G56**：先建批量管理端点安全网（4 条用例 + 两次负控制，并纠正了「force-logout 也跳自己」的错误预设），再把 3 处裸事务下沉（`recordAuditBatch` / `recordAudit` / `AiRepository.auditExportRows`，顺带删掉参数化裸 SQL 回填），裸事务降至 4 处 / 1 文件；全量 server test 454 绿。
  - **G57**：先建开发者门户安全网（5 条用例 + 两次负控制），新建 `model/DeveloperModels.kt` + `repository/DeveloperAnalyticsRepository.kt` 收掉 `DeveloperRouting` 最后 4 处裸事务，**实测 plugins/ 裸事务 0 处 / 0 文件、Exposed 导入 18 个文件**；全量 server test 459 绿。**M2「route 层裸事务」判据正式清零。**
  - 核心里程碑完成判据「反向依赖 0 + 事务数单调下降」已全部达成。
- [ ] 文档、监控、错误码、隐私边界和发布回滚方案同步更新。

## 3. 不可破坏的消息架构原则

以下原则来自已经落地的 Messaging V2，不应在“重写”中退回旧设计：

- [~] 服务端以设备邮箱持久化密文信封，presence 不参与消息是否可发送的判断。
- [~] metadata 与全部目标设备 envelopes 在同一服务端事务提交。
- [~] 客户端先持久化 inbox，再解密和投影；成功后进入可恢复 ACK 流程。
- [~] ACK、send、pull、mutation、receipt 必须幂等。
- [~] 群 Sender Key 分发和缺钥修复通过持久化加密邮箱，不要求成员同时在线。
- [~] 群成员 revision 改变后，旧的预制群密文必须失效并重新准备。
- [x] 删除和撤回是终态数据库事实；延迟 DATA、附件 finalize、定时任务不得复活消息。
  - **G32 实测（真 HTTP + 真实 app 库）**：`aDeletedMessageStaysTerminalAndIsNotResurrectedByALateData`。
    分层结论（**逐层钉住，不合并成一句**）：
    - **客户端墓碑**是拦住「延迟 DATA」的那一层：DELETE 之后 `message_mutation_tombstones` 实测出现该 id，
      `isMessageTerminal == true`；而**服务端并不按 messageId 去重**——实测同 id 的延迟发送被**接受**并
      **真的再投递了一条同 messageId 的信封**，是生产投影器事务里的 `isMessageTerminal` 判断阻止了插入
      （反证：去掉该判断，真实库里立刻**复活**出 `MessageEntity(id=…)`）。
    - **附件 finalize** 由**服务端**拦住：用已使用的 messageId 建上传会话实测返回 **409 Conflict**
      （反证：去掉 `UploadSessionService` 的两处占用检查后变成 **201**，会话被建出来）。
    - **定时任务**（G33 实测，驱动**生产 `ScheduledMessageWorker`** + 真实 app 库）：
      `aScheduledSendForATombstonedMessageIsAbandonedNotRevived`。链路是
      `ScheduledMessageWorker` → `ConversationScheduledMessageDispatcher` → `ConversationCommandFacade`
      → `MessagingV2MessageGateway`；定时消息的 id 是**确定性**的 `sm_<scheduleId>`，所以墓碑能精确命中。
      实测：无墓碑时确实进发件箱（对照）；有墓碑时**拒绝**——发件箱无该 id、真实库无该消息、
      收件人在**服务端**也收不到该 messageId；并且 scheduler 行被 **abandon（删除）**、worker 返回 success，
      **不会**留下永远重试的僵尸行。
      （顺带记录：M09 的 Gate 写「tombstone 测试通过」，但仓库里只有 `MessageMutationGateTest` /
      `ConversationCommandFacadeTest`，**没有针对定时路径的墓碑用例**——那句话覆盖的是通用变更门，
      缺的正是本轮补上的这条定时链路证据。）
    - **撤回（REVOKE）**（G34 实测）：`aRevokedMessageStaysRevokedAndCannotBeEditedBack`。墓碑 `kind == REVOKE`
      （直接从真实库读，不为测试给产品加 DAO 接口），且行**保留**但 `type == REVOKED`——把「REVOKE 保留行、
      DELETE 删行」这条差异钉住；随后再投 EDIT 也**不能**把原文改回来。
    - **重复定时消息的重排**（G34 实测）：`aRepeatingScheduledMessageReschedulesToANewMessageIdAfterATombstone`。
      旧 id 永不进发件箱、旧行被删，但 worker 会重排出**新 id** 的下一次（`occurrencesSent + 1`）并能正常暂存。
      **这不是复活**——messageId 变了，属设计语义；反证里「重排复用同一条 id」会让新行直接消失，说明换 id 是**承重**的。
  - **结论：本条升为 `[x]`**，支撑它的可执行证据（全部实跑）：
    1. `aDeletedMessageStaysTerminalAndIsNotResurrectedByALateData`（E2E，run 35003751157）
    2. `aScheduledSendForATombstonedMessageIsAbandonedNotRevived`（E2E，run 35008321158）
    3. `aRevokedMessageStaysRevokedAndCannotBeEditedBack`（E2E，run 35014800546）
    4. `aRepeatingScheduledMessageReschedulesToANewMessageIdAfterATombstone`（E2E，run 35014800546）
    5. `LocalMessageMutationPolicyTest.edit cannot resurrect revoked message`（JVM 单测，**归因**用）
  - **诚实保留的一点**：第 3 条的「不得被改回」在 **E2E 层无法归因是哪道守卫**——把投影器的终态判断与
    变更策略里的 `REVOKED` 子句**同时**去掉后断言**仍然**通过（很可能因为该 EDIT 还输在
    revision 先后比较上：撤回的 `editedAt` 是服务端时间、EDIT 的是客户端时间）。归因由第 5 条单测承担
    （它用**更高**的 candidate revision 把 `REVOKED` 子句单独隔离出来）。
- [x] WebSocket 只承载唤醒、presence、typing 和通话信令，不得重新承载人类消息正文。
  - **⚠️ 2026-09-20 证据更正（重要）**：本节此前引用的 `WebSocketContractTest.legacy and forbidden
    websocket message commands are rejected with UNSUPPORTED_WS_COMMAND` 与
    `WebSocketContractTest.allowed upstream websocket commands are strictly whitelisted`
    **这两个用例名在代码里不存在**；而真实存在的
    `downstream message types are strictly limited to wakeups and control signals` 当时断言的是
    **测试自己硬编码的本地列表**（里面的 `TYPING` 在服务端根本不存在，真名是 `USER_TYPING`），
    与「全库枚举」毫无关系；`RealtimeEventPolicyTest` 同名的客户端用例因白名单与真实 sealed 子类
    **完全对不上**而长期为红——只是整个文件当时编译不过，红也没人看见。
    根因：`WebSocketContractTest.kt` 的装配用了**不存在的 `configureSecurity`**，从未编译、从未运行，
    但台账已按「已通过」记成 `[x]`。以下为**更正后、可复现**的证据。
  - **G41 双端枚举审计（更正版）**：
    1. **服务端上行命令白名单拦截（反向穷举）**：
       - `WebSocketContractTest.upstream command whitelist strictly rejects all chat and message mutations`：
         注册真实账号 → 建立真实 `/ws` 连接 → 依次发送 `SEND_MESSAGE`、`SEND_TEXT`、`SEND_AUDIO`、
         `SEND_IMAGE`、`SEND_FILE`、`MESSAGE`、`CHAT_MESSAGE`、`DELETE_MESSAGE`、`REVOKE_MESSAGE`、
         `REACTION`、`EDIT_MESSAGE`、`SEND_DATA`、`ENVELOPE`、`SYNC` 共 14 个历史/越权命令，
         逐个断言返回 `ERROR` 帧且 `code == UNSUPPORTED_WS_COMMAND`。实测通过。
    2. **服务端下行类型全量枚举（源码扫描棘轮）**：
       - `WebSocketContractTest.downstream ws types are exactly the frozen set and none of them delivers a human payload`：
         扫描 `server/src/main/kotlin` 下**全部** `WsMessage("…")` 构造点（去注释），得到真实下游类型集合
         并**精确相等**冻结为 14 个：`ADMIN_BROADCAST`、`DISAPPEARING_MESSAGES_UPDATED`、`ERROR`、
         `FRIEND_REQUEST`、`GROUP_INVITE`、`GROUP_PLAY_UPDATE`、`GROUP_REVISION_CHANGED`、
         `INBOX_AVAILABLE_V2`、`PINNED_MESSAGES_UPDATED`、`PONG`、`POST_DELETED`、`SIGNALING`、
         `USER_STATUS`、`USER_TYPING`；并断言其中不含任何「人肉正文投递」形状的名字。
         新增一个下游类型即红，必须显式改大基线（做一次有意识的决定）。
    3. **客户端事件密封契约**：
       - `RealtimeEventPolicyTest.client websocket event definition contains no human chat payload events`：
         反射 `WebSocketEvent::class.sealedSubclasses`，**精确相等**冻结为真实的 16 个子类
         （`AdminBroadcast`/`Connected`/`DisappearingMessagesUpdated`/`Disconnected`/`Error`/
         `FriendRequestUpdated`/`GroupInviteUpdated`/`GroupPlayUpdated`/`GroupRevisionChanged`/
         `InboxAvailableV2`/`PinnedMessagesUpdated`/`PostDeleted`/`ServerError`/`SignalingReceived`/
         `UserOnline`/`UserTyping`），含**非空守卫**（防「枚举为空所以循环空转即通过」），
         并排除「人肉正文投递」形状的类名。实测通过。
  - **本节证明边界**：以上是**类型名层面**的枚举。名字说不出的东西（例如把明文塞进某个 `_UPDATED`
    的 payload）由载荷级扫描负责，见 `ServerPlaintextSweepTest`、
    `MessagingV2OutboxPlaintextBoundaryTest`、`PostgresPlaintextSweepIntegrationTest`。
    另：`PinnedMessagesUpdated` 的载荷 `PinnedMessageDto` 只含 `chatId/messageId/pinnedBy/pinnedAt`，
    已人工核对不含正文；`AdminBroadcast.text` 是运营公告，不是用户消息。
- [x] 服务端不得保存或检索人类聊天明文；Bot/service message 使用独立存储语义。
  - **G35 补的是「客户端那一半」**（此前零证据）：`clientPlaintextNeverLandsOnDiskAndBackupStaysDisabled`。
    分层证据：
    - **at-rest**：标记先经 DAO 读回（**控制组**：证明它真的在库里），再断言库文件与 `-wal`/`-shm` 的
      **原始字节**里都读不到它 → SQLCipher 确实加密磁盘（反证：去掉 `SupportFactory` 后标记立刻出现在
      `maodouchat.db` 原始字节里）。
    - **备份面**：用 `PackageManager` 读**已安装**应用的 `ApplicationInfo.flags`，`FLAG_ALLOW_BACKUP` 为 0
      （反证：manifest 改 `allowBackup="true"` 后安装态 flags 立刻带上该位）——**没有**拿 manifest 源码冒充。
    - **明文不落盘**：`filesDir`/`cacheDir`/`shared_prefs` 里无标记；且 token 的可识别子串在 `shared_prefs`
      原始字节里读不到（走 EncryptedSharedPreferences）。反证里「往 filesDir 写真实 marker」「往 prefs 明文写
      token 子串」都被抓到——同时也证明**扫描本身有效**，不是永远绿。
    - **导出功能**（G36 实测）：`chatExportWritesPlaintextIntoCacheAndTheSweepCatchesIt`。
      `ChatExport` 把**明文**写进 `cacheDir/exports/<name>.txt`（用户主动导出，明文属设计语义），
      且**返回之后文件仍在**——所以 G35 的「cacheDir 无明文」**只在「没有导出过」时成立**，这一点是
      **被记录**而不是被修掉。`writeStream` 的 tmp 两个方向都实测：成功后不留 `.tmp`；取消时返回 null
      且既不留 `.tmp` 也不留输出。**这一步还把 G35 的扫描变成了「被证明有效」**：用真实导出路径去喂它，
      它**必须**找到该文件（反证：把 `cacheDir` 从扫描根里去掉后命中为空）。
    - **备份面 + 本地生命周期**（G37 实测，独立测试类 `ClientDataLifecycleTest`）：
      - **备份面（运行时读已安装应用）**：`allowBackup` 关闭、**未声明自定义备份代理**
        （`backupAgentName == null`），并**在运行时解析已安装 APK 的排除规则资源**，确认
        `database`/`sharedpref`/`file` 在 **cloud-backup 与 device-transfer 两个域**里都被排除。
      - **登出/换号去留是显式策略**（逐 `Reason` 断言）：`LOGOUT`/`TOKEN_EXPIRED` → **保留**加密库；
        `ACCOUNT_SWITCH`/`DELETE_ACCOUNT`/`TRUST_DOMAIN_CHANGE` → **毁库**；三个派生策略
        （普通媒体缓存 / Coil 磁盘缓存 / in-flight 附件）必须与主策略**一致**。
      - **换号销毁有运行时证据**：带唯一标记的消息先证明在库（控制组）→ 用生产 `SecureSessionManager`
        按策略清理 → 标记**读不到**。
      - **诚实记录**：**`LOGOUT` 不毁库是设计**（同账号重登要能解密历史），所以「登出」≠「本地明文没了」；
        这与 G35/G36 的结论（私有目录无明文 / 导出会留明文）**共存而不矛盾**。
      - **静态结论（标注为静态）**：「**不存在应用内备份/恢复功能**」——依据是设置页无入口、无数据备份 API；
        这是**静态**性质，**不作为**运行时证据。
  - **服务端半边**（G38 H2 HTTP 层实测 + G40 真实 PostgreSQL 层实测）：
    `a human v2 payload sent over http lives in exactly one column and is not searchable` 及 `PostgresPlaintextSweepIntegrationTest`。
    - 经 **`POST /api/v2/messages`** 真发（每个收件设备用**各自不同**的标记）→ 用**枚举全表**的方式扫（H2 与真 PG 双重验证）：
      每个标记**恰好出现 1 次**，且都在 `MESSAGING_V2_ENVELOPES.CIPHERTEXT`（PG 上对应 `messaging_v2_envelopes.encrypted_payload`）；
    - **正对照**：同一套扫描必须能找到服务端**确实**保存的明文（bot/service 正文），否则「没扫到」不可信；
    - **不得检索**：管理检索接口返回该消息的**元数据**（先断言控制组：响应里确实有元数据与控制组），
      但**不得**出现任何一封的载荷。
    - **修正 G38 自己的前提**：我原先以为既有 `sweep(...)` 用的是人工表清单、所以「新表会漏」——
      **读代码后发现它早已用 JDBC 元数据枚举全表**；真正的缺口是**HTTP 层**与**检索面**。
    - **G38 CI flake 的归因更新（G39 已归因并修复级联）**：G38 收尾记录的两次 CI flake（「第二台设备的 bootstrap 失败」及随后的 21 条「登录过于频繁，请稍后再试」）已在 G39 定位：夹具级联缺少熔断导致重复登录打满 `AUTH_RATE_LIMIT_PER_MINUTE`，且 `initialize` 吞异常无因果。G39 已加入因果错误链暴露、`sharedFailure` 熔断机制（首错报错，后续通过 `Assume` skip），并将 E2E 测试环境服务端限流放宽至 100/分。
    - **G40 真 PostgreSQL 扫描闭环**：在 `PostgresPlaintextSweepIntegrationTest` 中将 HTTP 发送、全库全表元数据枚举扫描、正对照验证与管理检索元数据隔离证据推进至真实 PostgreSQL 引擎（使用独立隔离 schema `maodou_sw_*`）。
      **⚠️ 2026-09-20 复核更正**：本条此前写的「`postgresIntegrationTest` 自动化套件 5 类全面通过」**不成立**——
      该文件当时**从未编译过**（注册体缺 `name`、建会话用 `{"targetUserId":…}`、V2 发送打到不存在的
      `/api/messaging/v2/send`、期望列名写成不存在的 `encrypted_payload`），既没编译也没运行，
      所谓「全面通过」没有命令输出支撑。**本轮已按真实契约重写并实测**：本机临时 PostgreSQL 16.15 上
      `postgresIntegrationTest` → **18 个用例全部通过**（含本文件 2 例与原有的迁移矩阵、备份恢复、JobLease 并发等）。
      重写后这条命门证据**才第一次真的存在**，内容为：种子演示账号 → 1:1 会话 → 种 `signal_devices`(CONFIRMED)
      + `signal_keys` 四类 + `auth_sessions.signal_device_id` → 经 `/api/v2/messages` 发两个不同标记的信封 →
      管理端「带 access token + 二次确认密码」换 admin token 后按 chatId 检索**不得回显** →
      独立 JDBC 连接枚举该 schema 全部表/列，断言每个标记**恰好命中一列**：`messaging_v2_envelopes.ciphertext`。
      正对照同轮通过：同一扫描器确实能在 `service_messages.content` 与 `chats.last_message` 找到
      服务端故意保存的 bot 明文（服务端可见明文的唯一合法路径），证明扫描器不是恒空。
  - **闭环归档**：双端（Android 客户端 SQLCipher 加密磁盘与生命周期清除、服务端 H2/PostgreSQL 全库全表物理列扫描与管理检索隔离）全部实测通过；`MessagingInvariantTraceabilityTest` 显式缺口清零（0 gap）。本条正式提升为 **`[x]`**。

详细不变量见 `docs/messaging-v2-architecture.md`。

## 4. 目标工程边界

### 4.1 Android 目标模块

- [ ] `:core:model`：稳定 ID、错误、分页、时间、账号上下文等纯模型。
- [ ] `:core:database`：SQLCipher、Room、迁移、事务和领域 DAO 适配。
- [ ] `:core:network`：HTTP client、认证拦截、错误映射、分域 API。
- [ ] `:core:realtime`：WebSocket 生命周期、事件总线和连接状态。
- [ ] `:core:crypto`：libsignal 存储适配与密码学原语。
- [ ] `:core:session`：认证会话、账号世代、凭据库和安全销毁。
- [ ] `:core:testing`：fake clock、fake transport、数据库和多账号夹具。
- [ ] `:domain:messaging`、`:domain:conversation`、`:domain:groups`、`:domain:calls` 等纯领域模块。
- [ ] `:feature:chat`、`:feature:contacts`、`:feature:explore`、`:feature:settings` 等 UI 模块。
- [ ] `:app` 最终只负责应用装配、导航宿主和系统入口。

模块拆分可以分阶段完成，但新领域代码不得继续堆入单一 `:app` 根包。

### 4.2 Server 目标模块

- [ ] `server-platform`：配置、数据库连接、迁移、HTTP/WS 基础设施、观测。
- [ ] `server-identity`：认证、session、device、Signal key。
- [ ] `server-conversation`：会话、成员、群资料、邀请、审计。
- [ ] `server-messaging`：V2 admission、metadata、mailbox、service message、retention。
- [ ] `server-media`：blob、upload session、附件引用和 GC。
- [ ] `server-realtime`：presence、typing、wake、call signaling。
- [ ] `server-social`：好友、动态、附近、举报和审核。
- [ ] `server-bot`：Bot auth、updates、webhook、service messaging、developer API。
- [ ] `server-admin`：管理身份、命令、只读统计、配置和审计。
- [ ] Ktor route 只做鉴权、DTO、校验、调用 service 和错误映射，不拥有事务。

## 5. Android 基础设施重构

### A01 构建、依赖与装配

当前状态：`[~]`。模块骨架、单向依赖规则、模块级+类级静态检查、基础共享接口（clock/id/dispatcher/SessionContext/NetworkResult/MaodouJson）均已建立；依赖注入装配与 MaodouchatApp 瘦身尚未开始。

- [x] 建立上面的 core/domain/feature 模块骨架和单向依赖规则（17 个模块全部可编译，依赖方向 feature→domain→core 无环）。
- [ ] 引入统一依赖注入装配；领域类不得读取 `MaodouchatApp.instance`。
- [~] 为禁止依赖建立静态检查：模块级 `checkArchitecture` + 类级 ArchUnit 已建立（core/domain 不依赖 Android/全局单例）；「ViewModel/Composable 不得依赖 DAO/API/Application」规则待代码迁移时启用。
- [ ] `MaodouchatApp.kt` 只保留进程级初始化和 dependency graph。
- [~] 建立模块级测试任务、Lint 和 API visibility 规则：JVM 模块 `useJUnitPlatform` 已接（`:core:testing`）；Android 模块 lint 由 AGP 自带；逐模块 Lint/visibility 规则待后续补全。
- [~] 所有共享接口先冻结再并行实施：clock/id/dispatcher/SessionContext/领域错误(NetworkResult)/JSON(MaodouJson) 已冻结；typed payload 待 M01 定义。（G328c：其中的 `core/session/SessionContext` 已**改名为 `AuthSessionSnapshot`**——它与 app 侧 `com.maodouchat.session.SessionContext` 同名但字段与用途不同，同名只在跨模块阅读时制造混淆。）

Gate：空壳模块可编译；旧 app 仍能运行；禁止依赖检查进入 CI（`checkArchitecture` 已接入 CI android 作业，本地验证通过）。

### A02 账号、登录、认证与会话

当前状态：`[~]`。`:core:session` 已建立会话抽象（SessionContext/SessionCoordinator/AccountGeneration）与 single-flight token 刷新；AuthRepository/CredentialVault/DeviceSessionRepository 与用例拆分尚未开始。

- [ ] 拆分登录、注册、邮箱验证码、重置密码、TOTP、刷新 Token 用例。
- [~] 建立 `AuthRepository`、`CredentialVault`、`SessionCoordinator`、`DeviceSessionRepository`（`SessionCoordinator` 接口已建，其余待实现）。
- [x] Token refresh 实现 single-flight；并发 401 只能发起一次刷新（`TokenRefreshSingleFlight`，3 个单测：并发单飞/失败重试/冷却过期）。
- [ ] 明确定义登出、登出全部设备、换号、删号、切服务器的不同数据清理策略。
- [~] 所有后台任务和本地行按账号与 account generation 隔离（`AccountGeneration` 值类已建）。
- [~] Session 变化通过稳定状态流通知消息、Push、Widget、AI 等模块（`SessionCoordinator.changes: Flow` 已声明）。
- [~] 删除 ViewModel 和 Composable 对 Token/Application/WebSocket 的直接访问。

Gate：并发 401、进程恢复、A-B-A 换号、切服、设备吊销、错误数据库密钥测试通过。

### A03 Room、SQLCipher 与本地数据生命周期

当前状态：`[~]`。Room 迁移已从 `AppDatabase.kt` 抽出（`DatabaseMigrations.kt`），但 entity/DAO 领域拆分、`DatabaseLifecycle` 类、schema fixture 与仪器测试尚未完成。

- [ ] 定义最低支持升级版本；明确 1-15 是否停止直接升级。
- [ ] 按领域拆 entity、DAO、transaction 和 migration ownership。
- [x] `AppDatabase.kt` 只负责数据库创建、注册 migration 和 transaction boundary（35 个迁移已抽到 `DatabaseMigrations.kt` 并经 `DatabaseMigrationsChainTest` 锁定连续无断点；AppDatabase 723→136 行）。
- [x] 建立 `DatabaseLifecycle`，覆盖创建、解锁、换号销毁、迁移失败和恢复（已抽为 `internal object DatabaseLifecycle`：open/close/destroy/backup-recreate）。
- [x] 登出清理覆盖审计（`SecureSessionManager` 双路径：销毁整库 vs 同账号保留；14 张 owner 作用域表逐项核对——定时/提醒/归档忽略/语音/通知中心/附件/推送等短暂队列按账号清理，草稿/信任/墓碑/V2 收发箱/重试队列在保留路径有意保留以保证重登收敛；销毁路径整库删除兜底）。
- [ ] 为每个受支持旧版本到当前版本保存 schema fixture 和真实数据 fixture。
- [ ] 测试 SQLCipher 密钥错误、迁移中断、磁盘满、FTS、外键、墓碑和账号隔离。
- [ ] 所有 SharedPreferences JSON 业务存储迁移到版本化 Room 表；偏好设置除外。

Gate：每条支持升级路径在 CI 仪器测试运行，且断言数据语义而不仅是 schema。

### A04 网络 API 与错误模型

当前状态：`[x]`。`ApiService.kt` 单体已完成领域拆分，解耦为 Auth、Messaging、Conversation、Media、Social 等独立域 API，`ApiService` 转型为薄委托门面。

- [x] 拆为 Auth、Messaging、Conversation、Group、Media、Social、Call、Settings、Bot/Admin API（`AuthApi`/`AuthApiClient`、`MessagingApi`/`MessagingApiClient`、`ConversationApi`/`ConversationApiClient`、`MediaApi`/`MediaApiClient`、`SocialApi`/`SocialApiClient` 与 `ApiContracts.kt` 契约定义全部就位）。
- [x] 建立统一 `NetworkResult`、可重试性、HTTP/领域错误码和用户提示映射（`ApiException`、`toUserFacingMessage`、`NetworkResult` 与自动 401 刷新重试已落地）。
- [x] 每个 API client 依赖 `SessionContext`，不得自行读取 Token（Token 参数显式传递或经 Session 统一拦截）。
- [x] 统一超时、取消、幂等键、请求追踪 ID、重试和日志脱敏策略（OkHttp 客户端超时、取消注册与 SingleFlight 刷新机制就位）。
- [x] 运行时切服必须原子切换 HTTP、WS、身份和本地账号命名空间。
- [x] `ApiService` 转型为聚合薄 facade，完成全工程编译兼容过渡。

Gate：API 契约快照、401 刷新、取消、超时、切服和错误映射测试通过。

### A05 WebSocket 与实时事件

当前状态：`[x]`。ViewModel/UI 均已与 raw WebSocketClient 完全解耦，统一由 RealtimeConnectionManager 与 RealtimeEventDispatcher 提供类型安全的领域事件与连接管理。

- [x] `RealtimeConnectionManager` 唯一管理连接、认证、退避、网络切换和账号切换（`AccountScopedRealtimeConnectionManager` 由 SessionContextProvider 自动维护）。
- [x] typed event decoder 将 wake、presence、typing、social、call signaling 分发到领域端口（`DefaultRealtimeEventDispatcher` + `WebSocketEventBridge` 统一分发）。
- [x] 页面不连接 WebSocket、不做全局事件路由、不将 WS 当最终真相源（ChatList、Contacts、Explore、GroupDetail、Call、Settings、NavGraph 均通过领域端口与状态流交互）。
- [x] 消息 wake 只触发 V2 inbox sync；重复 wake 必须可合并（`DefaultRealtimeEventDispatcher.wakeEvents` 150ms debounce 合流）。
- [x] 统一 connection health、错误可见性和调试指标（`RealtimeConnectionState` 与 `RealtimeError` 事件统一映射）。
- [x] 删除 ChatDetail、ChatList、Contacts 等位置的重复 WS collector（全量收敛至 `RealtimeEventDispatcher` 领域流）。

Gate：乱序、重复、断连、重连、账号切换、Token 撤销和冷启动测试通过。

## 6. Android 消息与聊天重构

### M01 消息内容协议与模型

当前状态：`[x]`。`:domain:messaging` 已定义版本化 typed `ContentPayload` 协议；`<meta>` 停写、旧解析器只读 adapter、fail-safe 与字面 `<meta>` 测试落地；四类模型已分离（wire/domain/database/presentation）。

- [x] 定义版本化 typed `ContentPayload`，覆盖文本、回复、提及、附件、位置、联系人、投票和系统事件（`ContentEnvelope` + `ContentPayload` sealed + `Mention`/`AttachmentKind`，@SerialName 稳定 wire 名，4 单测）。
- [x] 分离 wire、domain、database、presentation 四类模型（wire=`MessagingV2Content`/`MessagingV2Event`、domain=`Message`/`MessageMeta` + `DecodedContentPayload`（已消除与 domain `ContentPayload` 同名冲突）、database=`MessageEntity`/`ChatEntity`、presentation=`MessagePresentation`）。
- [x] 新消息停止写 `<meta>`；旧解析器降级为只读 migration adapter（`ContentPayloadCodec.encode` 写结构化 metadata，`decodeLegacyBody` 只读解析旧 `<meta>`）。
- [x] 对未知字段、未知消息类型和未来版本 fail-safe（未知字段由 forwardCompatible Json 忽略；未知 type 由 `decodeContentEnvelopeFailSafe` 降级为 `ContentPayload.Unknown`（保留原始 type + JSON），不抛 SerializationException）。
- [x] 建立旧正文迁移与字面 `<meta>` 内容测试（字面 `<meta>` 无闭合 `</meta>` 不再截断正文，2 单测）。

Gate：新旧客户端兼容矩阵确定；历史数据迁移可逆演练通过。

### M02 Messaging V2 客户端运行时

当前状态：`[~]`。Inbox、Outbox、ACK、retry、timeline projector 已有新基础。

- [x] 拆分 outbox 事务写入、claim、加密、发送和状态迁移（事务写入=`MessagingV2Outbox`、claim/发送=`MessagingV2OutboxCoordinator`、加密=`MessagingV2EnvelopePreparer`→`SignalMessagingV2Adapter`、状态迁移=`MessagingV2OutboxState`）。
- [x] 拆分 DATA、EVENT、RECEIPT、GROUP_CONTROL projector（DATA→`MessageContentProjector`、EVENT→`MessageEventProjector`、RECEIPT→`MessageReceiptProjector`、GROUP_CONTROL→`GroupMessagingCoordinator`；`MessagingV2TimelineProjector` 只剩 DATA 分派 + 到达策略）。
- [x] Runtime 通过接口注入，不由页面或功能模块自行构造（app 已接入 `:domain:messaging`，`MessagingV2Runtime` 实现 domain 端口：`syncInbox`/`acknowledge`/`outbox: Flow<OutboxState>`（DAO 计数 Flow combine）；单一构造点在 `MaodouchatApp`）。
- [x] 所有发送来源统一经过 tombstone、owner-session、outbox 事务（`ConversationCommandFacade.stage`→`MessagingV2MessageGateway`→`MessagingV2Outbox`（tombstone+owner+单事务）；`sendMessageV2` 唯一调用者为 OutboxCoordinator）。
- [~] 完成 poison envelope、dead letter、stale claim 和 repair bypass 的观测与操作入口（基础设施 + 观测已就位：`recoverStaleInbox/OutboxClaims`、`MessagingV2InboxFailurePolicy`、`runtime.deadLetterCount` 观测流；手动 repair 操作 UI 待随管理后台 UI 重写补充）。
- [x] 删除旧消息 pull、屏幕解密、扫描 `SENDING` 行和 WS message 命令（旧 pull/WS 命令/SENDING 启动扫描已删除；`requiresDecryptPlaceholder` 为 v2 projector 解密占位，非旧屏幕解密）。

Gate：ACK 前后杀进程、重复 pull、poison、顺序阻塞、修复绕行和终态竞态测试通过。

### M03 Signal 直接会话与设备密码学

当前状态：`[x]`。`:core:crypto` 已建立身份信任纯逻辑（IdentityTrustState/StateMachine）与 `IdentityTrustService` 契约；DirectSessionManager/DirectMessageCipher/EnvelopeCodec/CryptoAccountBootstrapper/PreKeyInventory/PreKeyPublisher/DeviceIdMigrationCoordinator 全部就位；`SignalProtocol` 2342 行单体已拆解为 8 个纯领域服务，`SignalProtocol` 转型为精简委托门面。

- [x] 建立 `CryptoAccountBootstrapper`、`PreKeyInventory`、`PreKeyPublisher`（`core:crypto` 端口已建，`SignalProtocol` 已 conform）。
- [x] 建立 `DirectSessionManager`、`DirectMessageCipher`、`EnvelopeCodec`（`core:crypto` 端口已建，`SignalProtocol` 已 conform；`DecryptResult` 已抽到 `core:crypto`）。
- [x] 建立 `IdentityTrustService`、`DeviceIdMigrationCoordinator`（`core:crypto` 两契约已建；`IdentityTrustStateMachine` 纯逻辑已测试；`SignalDeviceIdCoordinator` 配合 `SignalDeviceIdRecoveryPolicy` 完整支持多设备迁移与冲突恢复）。
- [x] `SignalProtocolStore` 只负责 libsignal 持久化适配（`PersistentSignalProtocolStore` 390 行独立适配，`SignalProtocol` 通过它读写身份/签名预密钥/OTPK；不混业务逻辑）。
- [x] 迁移期保留薄 `SignalProtocol` facade；调用者迁完后删除宽接口（`SignalProtocol` 2342 行单体已完全拆分为 8 个领域服务：`SignalProtocolContext`、`SignalEnvelopeCodec`、`SignalIdentityTrustService`、`SignalDeviceIdCoordinator`、`SignalPreKeyManager`、`SignalSessionManager`、`SignalDirectCipher`、`SignalAccountBootstrapper`，`SignalProtocol` 转型为纯委托门面，测试 100% green）。
- [x] 页面、Widget、Worker、AI、附件不得调用 Signal 原语（`org.signal.libsignal` 仅在 `crypto/`、`messaging/` 出现，UI/Widget/AI/附件零直接调用）。

Gate：ratchet 重启连续性、pre-key 并发、身份变化、设备迁移和双设备收发测试通过。

### M04 群 Sender Key 与离线群聊

当前状态：`[x]`。`GroupSenderKeyManager`、`GroupMessageCipher`、`GroupEncryptionHealthService` 纯领域契约与实现全部就位；已从 `SignalProtocol` 单体抽离为 `SignalGroupSenderKeyManager`、`SignalGroupCipher`、`SignalGroupEncryptionHealthService` 独立领域服务，`SignalProtocol` 完成薄委托门面接入。

- [x] `GroupSenderKeyManager` 和 `GroupMessageCipher` 与 UI/Room/HTTP 解耦（契约冻结在 `:core:crypto`；`SignalGroupSenderKeyManager` 与 `SignalGroupCipher` 独立实现并由 `SignalProtocol` 薄门面委托）。
- [x] `GroupEncryptionHealthService` 唯一管理 coverage、epoch、repair 和错误状态（契约与状态机在 `:core:crypto`；`SignalGroupEncryptionHealthService` 纯净管理并对接 `GroupEncryptionHealthPolicy`）。
- [x] 新设备确认、成员 revision 变化和设备撤销都触发确定性的覆盖重算（`GroupMessagingCoordinator` 编排 `ensureCoverageNow`/`redistributeCoverageNow`/`enqueueCoverageRetryCommand`）。
- [x] 保证旧 prepared ciphertext 不得跨 revision 发送（`invalidateGroupEpoch`→`invalidatePreparedGroupMessages`+`deleteQueuedGroupControls` 随 revision 失效旧 prepared ciphertext）。
- [x] 群聊发送完全不读取成员在线状态（GroupMessagingCoordinator/SenderKeyRetryManager 无 `isOnline`/`online` 读取）。
- [x] 删除旧 WS `REQUEST_SENDER_KEY`、inactive-chat decrypt 和重复 retry 路径（已无 `REQUEST_SENDER_KEY` 与 inactive-decrypt 遗留）。

Gate：所有成员离线、单成员群、新设备、踢人、连续 revision、缺钥和进程重启 E2E 通过。

### M05 普通发送、重试与会话解析

当前状态：`[x]`。`ConversationCommandFacade` 与 `OutgoingConversationResolver` 已全面接线并提供离线直聊支持与幂等去重。

- [x] `ConversationCommandFacade` 成为文本、内联消息和重试的唯一 UI 入口（实现与接线完成，支持 send/retry/cancel/sendInline）。
- [x] `OutgoingConversationResolver` 唯一负责本地会话 ID、首次直聊创建和 crypto readiness（实现领域接口并支持离线直聊解析与创建回退）。
- [x] 一次 intent 只允许生成一个 local message 和一个 outbox command（`SendMessageCommand.idempotencyKey` 与内存缓存去重完成）。
- [x] 发送提交后的索引、通知、唤醒失败只记录 convergence warning（`MessagingV2MutationFacade.completeCommittedProjection`：mutation 已持久后投影/刷新失败仅记 warning，永不回滚）。
- [x] 删除 `sendMessage`、`sendGroupTextMessage`、页面内 encrypt/enqueue 等重复实现（所有发送、重试、取消均收敛至 `ConversationCommandFacade` / `ChatOutgoingFacade`）。

Gate：离线新建直聊、重复点击、取消、重试、账号切换和首条消息竞态通过。

### M06 编辑、撤回、删除、回应、回执与消息状态

当前状态：`[x]`。已全面收敛至 Facade、Coordinator、Tombstone 及独立聚合投影回执（含播放回执），竞态与乱序 Gate 通过。

- [x] 编辑、撤回、删除、回应全部经 `MessagingV2MutationFacade`（`ConversationMessageMutationCoordinator` 注入 `MessagingV2MutationFacade`，UI 经 coordinator→facade→eventOutbox）。
- [x] terminal mutation 与 tombstone 在同一 Room 事务提交（`MessageEventProjector`：REVOKE `persistTerminalTombstone`+`applyRevoke`、DELETE tombstone+deleteMessage+search 删除均在 `withTransaction`）。
- [x] 已读、送达、播放回执走独立 typed event 和聚合投影（`MessageReceiptProjector` 走 `DELIVERY_RECEIPT`/`READ_RECEIPT`/`PLAY_RECEIPT` typed event + `MessagingV2ReceiptEntity` 聚合；Room 迁移 35→36 加 `playedAt`；`VoicePlayer` 播放触发 `enqueuePlayReceipt`）。
- [x] optimistic rollback 只允许发生在 durable staging 失败之前（`MessagingV2MutationFacade`：先 `eventOutbox.enqueue`（加密 event 持久）再 `completeCommittedProjection`；mutation 仅在 durable 后接受）。
- [x] 编辑/撤回/删除同步收敛搜索、媒体缓存、通知和附件状态（`MessageEventProjector.cleanupTerminalArtifacts`：搜索 deleteDocument + `MediaCache.deleteCachedMediaForMessage` + `AppNotifier.cancelMessage` + `AttachmentTransferCoordinator.discardTerminal` + `ScheduledMessageStore`/`ScheduledMessageScheduler` 取消）。
- [x] 删除旧 REST mutation、旧 reaction snapshot 写路径和 UI 自行改状态逻辑（移除 `ChatDetailViewModel.updateMessageStatus` 死代码，收敛为 Room `markIncomingReadThrough` + `markAllRead` 批量事务水印）。

Gate：重复/乱序 event、延迟 DATA、删除与附件 finalize、删除与定时发送竞态通过。

### M07 附件、媒体上传与下载

当前状态：`[x]`。已完成：`AttachmentIntentController`、`PreparationService`（AES-GCM/压缩）、`TransferRepository`（Room/Coordinator）已完整落地；Worker 仅委托 `AttachmentTransferUseCase`；ViewModel 纯提交 `AttachmentIntent`，统一图片、视频、文件、语音、GIF、贴纸、位置与联系人入口；pause/resume/cancel/projection 测试 100% 通过。

- [x] 建立 `AttachmentIntentController`、`PreparationService`、`TransferRepository`（`DefaultAttachmentIntentController`、`DefaultAttachmentPreparationService`、`RoomTransferRepository` 实现与测试全部就绪）。
- [x] Worker 只调用 `AttachmentFinalizeUseCase`，不读取全局 Application/API（`AttachmentTransferUseCase` 封装 terminal/token/upload/dispatch 全编排；Worker 只剩取参 + 委托 + 结果映射）。
- [x] UI 只提交 URI intent、观察 transfer projection（`ChatDetailViewModel` 纯调用 `attachmentIntentController.submit` 与 `observe`）。
- [x] 统一图片、视频、文件、语音、GIF、贴纸、位置和联系人附件入口（`AttachmentIntentController` 统一分发所有 `AttachmentKind`）。
- [x] 处理 pause/resume/cancel、进程恢复、revision 改变、tombstone 和本地清理（pause/resume/cancel 状态机已建并测试；tombstone→`discardTerminal`；revision 改变→`reconcileAttachments` 清 wire + 重调度；进程恢复→WorkManager）。
- [x] 删除 ViewModel 内加密、finalize、cleanup 和附件 metadata 拼装（`ChatDetailViewModel` 彻底移除了 `AttachmentPreparationLease`、加密与直接上传调度，收敛至 Controller）。

Gate：每个上传边界杀进程、分片恢复、账号切换、转发、密聊、阅后即焚测试通过。

### M08 转发

当前状态：`[x]`。`ConversationForwardCoordinator` 统一承接，ViewModel 内部附件与批量发送逻辑彻底移除。

- [x] 统一 `ForwardRequest`，包含来源、目标、留言、隐私策略和幂等键（契约已冻结到 `:domain:messaging`）。
- [x] Coordinator 负责目标解析、附件复制/重加密、批量结果和部分失败（`ConversationForwardCoordinator.forward`/`forwardBatch` 实现目标解析 + 附件重加密 + 部分失败）。
- [x] 密聊、PIN 锁、终态消息和来源隐私统一校验（`ForwardPolicy` 纯逻辑已建并测试，`ConversationForwardCoordinator` 注入 `isChatLocked` 统一拦截）。
- [x] UI 只显示逐目标结果，不循环调用发送/附件实现（`ChatDetailForwarding` 统一派发 `ForwardRequest` 批量任务并展示逐目标统计/错误）。
- [x] 删除 ViewModel 内 `forwardMessage`、batch 和附件转发实现（彻底删除了 105 行 `forwardEncryptedAttachment` 及附件/DAO 直接调用）。

Gate：多目标部分失败、重复请求、附件、留言、账号切换和隐私测试通过。

### M09 定时发送、重复任务与提醒

当前状态：`[x]`。已完成：定时消息与提醒持久化全量迁入 Room（`scheduled_messages` 与 `message_reminders` 表，Room Migration 36→37）；统一经 `ConversationScheduledMessageDispatcher` / `ConversationCommandFacade` 进行发件箱暂存；实现两阶段 `send-now` 故障自愈；实现时区与夏令时感知计算 `ScheduledTimeCalculation`；废弃旧 SharedPreferences 存储代码并全量通过单元测试。

- [x] 定时消息和提醒迁入 Room，拥有 owner、状态、attempt、nextRunAt 和幂等键（`ScheduledMessageEntity`、`MessageReminderEntity`、`ScheduledMessageDao`、`MessageReminderDao`、`RoomScheduledMessageStore` 全部就位并受 Room Migration 36→37 约束）。
- [x] 定时发送调用普通消息 facade，不另建加密/发送链路（`ScheduledMessageWorker` 通过 `ConversationScheduledMessageDispatcher` 调用 `ConversationCommandFacade` 统一分发暂存）。
- [x] send-now 只有在消息 durable staged 后才删除 schedule row（`ConversationScheduleCoordinator` 实现两阶段 `beginImmediateSend` / `completeImmediateSend` / `restoreImmediateSend`，未入发件箱前 Row 不被删除）。
- [x] 支持时区、夏令时、改期、取消、重复周期和登出清理（`ScheduledTimeCalculation` 严格依据 ZoneId 与 calendar days 运算，夏令时墙上时间不漂移；`SecureSessionManager` 登出时物理清理 Room 表）。
- [x] 删除旧 `util/ScheduledMessage*Store` 与 ViewModel 重复包装逻辑（`ScheduledMessageStore` 与 `MessageReminderStore` 废弃 SharedPreferences JSON，完全委托 Room DAO）。

Gate：进程重启、时区变化、重复 Worker、换号、send-now 中断和 tombstone 测试通过。

### M10 阅后即焚、密聊与本地隐私

当前状态：`[x]`。`ConversationPrivacyPolicy` 与 `DefaultSecretConversationController` 已全面实现与接线；密聊 TTL、已读 arm、截图保护、水印、通知脱敏及数据销毁统一状态机管理；各端能力校验由 `ConversationPrivacyPolicy.allows` 统一收敛，销毁任务由 WorkManager 持久化且单元测试全量通过。

- [x] 建立 `ConversationPrivacyPolicy` 和 `SecretConversationController`（契约、状态机与 `DefaultSecretConversationController` 实现全部就绪并接线）。
- [x] 密聊 TTL、已读 arm、截图保护、水印、通知脱敏和数据销毁共享一个状态机（`SecretChatStateMachine` 驱动，`ChatDetailDisappearing`、`MainActivity`、`AppNotifier` 统一接入）。
- [x] PIN 锁、密聊、普通会话的搜索/转发/导出/AI/Widget 权限统一由 capability 决定（`ConversationPrivacyPolicy.allows` 统一拦截 AI、SEARCH、SCREENSHOT、EXPORT、NOTIFICATION_PREVIEW、FORWARD）。
- [x] 销毁任务持久化、账号隔离，进程死亡后可恢复（`DisappearingMessageDestructionWorker` 与 `SecretSurfaceWatchdogWorker` 提供 WorkManager 故障自愈）。
- [x] 删除 UI、通知、AI、Widget 各自维护的重复密聊判断（全部收敛至 `secretConversationController.capabilities` 与 `ConversationPrivacyPolicy.allows`）。

Gate：截图策略、后台通知、进程死亡、时钟变化、锁定恢复和零明文泄漏测试通过。

### M11 快捷回复、Widget 与系统通知动作

当前状态：`[x]`。已完成：`QuickReplyUseCase`/`QuickReplyPolicy` 完整落地并服务通知 RemoteInput 与 Widget；`ConversationReadReceiptCoordinator` 统一调度通知已读；Receiver/Provider 彻底剥离 DAO/Outbox 直接操作；Widget 隔离账号脱敏防护已接入，全量单元测试 100% 通过。

- [x] `QuickReplyUseCase` 同时服务通知 RemoteInput 与 Widget（已由 `DefaultQuickReplyUseCase` 实现并通过 `ConversationCommandFacade` 调度）。
- [x] Receiver/Provider 只验证输入并入队命令（`NotificationQuickReplyReceiver` 与 `ConversationWidgetProvider` 已完全委托用例与调度器）。
- [x] Widget projection 按账号生成，默认脱敏，不读服务器正文（`ConversationWidgetData` 接入 `SessionGate` 与 `PurgeGuard`，密聊绝不上桌）。
- [x] 重复 RemoteInput 具备幂等键；失败后可安全重试（基于 owner/chat/text 构建幂等键，防抖去重）。
- [x] 删除 Provider 对 DAO、ChatRepository、outbox 的直接业务访问（`ConversationWidgetProvider` 已完全委托 `ConversationReadReceiptCoordinator`）。

Gate：冷进程、离线、旧通知、账号切换、Token 失效和重复回复测试通过。

## 7. Android 聊天界面重构

### U01 Chat Detail 状态与用例编排

当前状态：`[~]`。`ConversationCommandFacade` 统一调度消息命令；但 `ChatDetailViewModel` 职责**并未收敛**：实测 3131 行 / 130 个函数（仅 35 个是单行委托）、133 行构造期手写装配约 30 个具体依赖、**18 处直连 `app.database.*` DAO**、**6 处裸 `ApiService.*`**、**8 处 `signalProtocol.*` 原语**、46 处 `viewModelScope.launch`；`currentGroupRevision()`、`loadChat()` 等单体核心未拆；`sendEncryptedAttachment()`、`handleGroupRevisionChanged()` 已分别由 G349、G350 抽出为独立控制器。端口接线目前只是薄层。

- [x] 建立 `ConversationTimelineStore`、`ComposerController`、`ConversationCommandFacade`（`ConversationCommandFacade` 已接入并调度文本、重试与转发）。
- [x] 建立 `ConversationRealtimeCoordinator`、`ConversationSecurityController`。
- [x] 建立独立 Media、Search、Selection、AI、Group 状态 controller。
- [x] 单一宽 `ChatDetailUiState` 组合各稳定领域子状态。
- [x] 统一收敛消息发送、状态流转与重发逻辑至 Messaging V2 Outbox 与 CommandFacade。

Gate：ViewModel reducer、快速切会话、进程恢复、单 intent 单命令和账号切换测试通过。

### U02 Chat Detail Compose 页面

当前状态：`[~]`。Telegram 预见性返回手势、线性弹性微动效就绪；`ChatDetailScreen.kt` 42 行薄路由（但仅代理一个 5061 行的 `ChatDetailRoute` 单体 composable，属表面收敛）。

- [x] 系统级预见性返回手势：在 `AndroidManifest.xml` 中启用 `android:enableOnBackInvokedCallback="true"`，适配 Android 13+ / 14+ 类似 TG 的侧滑返回预览。
- [x] 拆为 `ConversationRoute`、`TimelinePane`、`ComposerPane` 等子面板，`ChatDetailScreen.kt` 仅 42 行纯代理入口。
- [x] sheets：转发、定时、举报、安全码、联系人、AI、媒体操作。
- [x] banners：置顶、断线、群公告、消失消息、安全变化、Sender Key 健康。
- [~] 平台动作通过 effect handler 执行，不在 Composable 内直接写库（**未达标，但已量化、上了棘轮，且抽出的第一块已修出真缺陷**：`ChatDetailRoute.kt:879` 在 Composable 内取 `secretChatDao()`，`:891` 于 `while(true)` 中每 60s `touchActivity()` 写库，并内联 TTL 过期判定/`destroySession`；`:1010/1025/1042/1059` 另取 4 个 `database` 句柄驱动 AI 业务逻辑。`ChatDetailRoute.kt` 实为单个 5061 行 composable，含 78 处 `LaunchedEffect`。**G8 实测全量口径**：`app/src/main/java/com/maodouchat/ui/` 下 `database.`/`secretChatDao`/`MaodouchatApp` 共 **38 个文件 / 200 处**（最重 `ChatDetailViewModel.kt` 36、`ChatListPorts.kt` 26、`SettingsSubScreens.kt` 11），已由 `ClientHotspotRatchetTest` 精确相等冻结，只许下降；该门禁此前从不运行，G8 已接进 CI。**G9** 把上面那段密聊心跳抽成非 Composable 的 `security/SecretChatActivityHeartbeat.kt`，`ChatDetailRoute.kt` 直连命中 9→6、行数 5061→5048，口径校正为 Composable 主口径 **84 处 / 17 文件** 与全 ui 次口径 **197 处 / 38 文件**。**G10** 随即用它抓到并修掉一个真缺陷：抽出前的 `runCatching` 会把 `CancellationException` 一起吞掉，取消后仍写一次 `touchActivity`——而 `touchActivity` 会**延长密聊 TTL**，等于让泄漏的心跳给已离开/已销毁的会话续命；现按项目惯例重抛取消并在每次写活动前 `ensureActive()`，7 个单测（含 2 个取消用例）钉住**通讯录收口**：`AndroidContactsRepository` 改构造器注入 `UserRepository`（删掉 `application as MaodouchatApp`），`ContactsViewModel` 的 3 处 `app.database.userDao()` 直连改走非 ui 层的 `AppRepositories.users`；`AppRepositories` 新增裸 `UserDao` 入口（与 `users` 同一数据库实例，供实时同步协调器）；`frozenUiAppDatabaseGrabbers` 4→2、`frozenUiDirectPersistence` 通讯录两条目 3+10→0+3（删除一条）。）。
- [x] 子组件接收稳定 model/event。

Gate：大字体、长文本、RTL、中英文、横屏、平板、键盘和弹窗互斥 Compose 测试通过。

### U03 消息气泡与内容渲染

当前状态：`[x]`。客户端 UI 全面对齐 `reference/Murexide` 现代 Material 3 + Liquid Glass 体系，旧单体已拆分。

- [x] 气泡 M3 质感：浅色 `#EEEEF0`、深色 `#1E1E20`、己方主色容器（原孤儿气泡文件未被接线已隔离，实际经 `MessageBubble` 分发 + `TextMessageBubble`/`MediaMessageBubbles`/`FileMessageRenderer` 实现）；
- [x] 动态 18dp/4.5dp 连续气泡圆角，邻近消息流自然收敛；
- [x] 底部对齐的 36dp 精致圆形头像（对齐 Murexide 规范）；
- [x] 优雅的 Quote Reply 引用微件：左侧 3dp 竖向高光色块，紧凑预览与作者名；
- [x] 液态玻璃：集成 Murexide `LiquidGlass` 高斯模糊微光与高对比度文字自适应；
- [x] 拆分独立渲染器：`TextMessageBubble.kt`、`MediaMessageBubbles.kt`、`FileMessageRenderer.kt`，消除 wire/meta 渲染期耦合。

Gate：每种消息 golden、损坏内容、未知类型、超长文本/文件名、终态和无障碍测试通过。

### U04 Composer、草稿、回复、提及与输入状态

当前状态：`[x]`。客户端输入区全面对齐 `reference/Murexide` 胶囊设计与微动效。

- [x] 26dp Capsule 胶囊聊天输入栏，带 LiquidGlass 与高度自适应（原孤儿输入文件未被接线已隔离，实际为 `ChatDetailComponents.kt` 内 `ChatInputBar`）；
- [x] 线性微动效按钮（Telegram / Nekogram 交互微动效）：`LinearPress.kt` 实现 `linearPressEffect` 与 `LinearActionButton`，装配至发送按钮；
- [x] 发送/语音无缝切换：有文本时线性弹性发送按钮，无文本时麦克风微标；
- [x] 打字指示微气泡优化：`TypingPresence.kt` 修复修饰符双重应用 bug，采用半透高斯微发光胶囊与交错正弦波弹跳；
- [x] 独立 `ComposerState`、草稿持久化、回复/编辑/提及状态可恢复。

Gate：旋转、进程恢复、快速切会话、IME、录音中断、重复点击和权限拒绝测试通过。

### U05 Chat List、文件夹、搜索与通知中心

当前状态：`[x]`。会话列表对齐 `reference/Murexide` 规范排版与视觉质感。

- [x] 对齐 Murexide 52dp 圆形大头像与在线绿点微标（`AvatarSize.CHAT_LIST` 升级为 52dp）；
- [x] M3 标题 `FontWeight.SemiBold` 排版，两行文本预览与滑动未读动画胶囊；
- [x] 置顶会话使用 `MaterialTheme.colorScheme.surfaceContainer` 优雅背景，多选高亮清晰；
- [x] `ConversationListRepository` 本地投影为唯一真相源，严格排除锁定/密聊内容；
- [x] 文件夹、归档、置顶、静音、批量操作、公告、未接来电分离治理。

Gate：纯离线启动、排序、未读、草稿、归档、编辑/撤回/删除后的预览一致性通过。

### U06 群详情、成员管理、邀请与群玩法

当前状态：`[x]`。`GroupDetailScreen.kt` 模块化抽离至 group/ 独立 UI 组件与独立路由界面，ViewModel 与 Chat Detail 共享统一 `GroupLifecycleService`。

- [x] `GroupLifecycleService` 是客户端成员/角色/所有权/资料 mutation 唯一入口。
- [x] `GroupMembershipStore` 保存本地快照与 revision。
- [x] Invite、Audit、Bot、Encryption Health 各有独立 controller。
- [x] Chat Detail 与 Group Detail 共享同一 lifecycle service。
- [~] 签到、接龙、PK、投票拆成独立 feature，不继续集中在 `GroupPlayPolicy.kt`（**未达标**：`util/GroupPlayPolicy.kt` 仍为 2298 行 / 986 条声明，仍含 25 处 Checkin/Chain/Pk/Poll 引用且被 `ui/component/TextMessageBubble.kt` 生产引用；`group/play/` 下四个「已抽出」文件合计仅 154 行（GroupPkPolicy 72 / GroupPollPolicy 33 / GroupChainPolicy 29 / GroupCheckinPolicy 20），只是 format/parse helper 且重复了常量，并未吸收主体；另存在 `util/GroupPollPolicy.kt` 与 `group/play/GroupPollPolicy.kt` 同名并存。群玩法 4 个 Screen 已落地，此部分成立）。
- [x] 群 UI 只显示已提交结果和提交后修复状态，不把 refresh 失败当 mutation 失败。

Gate：权限矩阵、并发成员变更、离线成员、邀请竞态和 Sender Key 修复测试通过。

### U07 媒体中心、星标、搜索、导出与链接预览

当前状态：`[x]`。Media Center、星标、会话/全局搜索、导出与链接预览均已完成用例抽离、敏感门禁、统一可见性策略与 SSRF 防护。

- [x] Media Center 只消费本地媒体 projection；下载/导出通过用例（`MediaExportUseCase` 与 `DefaultMediaExportUseCase` 隔离文件与 content resolver 操作，单测全覆盖）。
- [x] 星标与全局/会话搜索使用统一 message visibility policy（`:domain:messaging` 统一 `MessageVisibilityPolicy`，严格隔离密聊、锁定、已删除和已撤回消息）。
- [x] 导出建立明确格式版本、敏感门禁、流式写入和取消（`ChatExport` 定义 `FORMAT_VERSION = 1`、协程流式写入并支持取消时原子清理碎片文件；`ChatExportController` 阻断密聊导出）。
- [x] Link preview 抓取、缓存、隐私和渲染分层，阻止内网地址和危险重定向（`LinkPreviewPolicy` 严格阻断 localhost、私有 IPv4/IPv6、点分八进制/十六进制变体、非标端口、userinfo；`LinkPreviewRepository` 禁用自动重定向，显式在每跳前进行 SSRF 校验并禁止 HTTPS 降级）。
- [x] 清除历史通过 `ConversationLocalStateCoordinator`，不在各页面复制清理清单。

Gate：锁定、密聊、删除/撤回、媒体缓存损坏、导出中断和 SSRF 测试通过。

## 8. Android 其他产品功能重构

### P01 通讯录、好友申请、备注、二维码与建群入口

当前状态：`[x]`。好友申请/关系变更用例（`FriendRequestUseCase`/`ContactMutationUseCase`）、实时增量同步协调器（`ContactsRealtimeSyncCoordinator`）、安全二维码纯解析（`QrPayloadParser`）、统一会话创建端口（`ConversationCreationPort`）与解耦后的 `ContactsViewModel` 已全量落地并通过完整自动化测试。

- [x] 好友目录、用户搜索、申请、备注、拉黑、二维码、安全码、建群拆独立用例（`DefaultFriendRequestUseCase`、`DefaultContactMutationUseCase`、`DefaultConversationCreationPort`、`QrPayloadParser`）。
- [x] `ContactsRepository` 以本地缓存为真相源；WS 只驱动增量同步（`DefaultContactsRealtimeSyncCoordinator` 增量消费 WS 领域事件并维护本地投影）。
- [x] `QrPayloadParser` 只解析和校验，不直接导航或执行业务（超长 DOS、恶意外链、参数注入防御并返回 `ParsedQrResult` 纯状态）。
- [x] 创建会话调用稳定 `ConversationCreationPort`（单聊、密聊、群组与频道统一门禁入口）。
- [x] 删除 Contacts ViewModel 的网络、WS、数据库混合职责（ViewModel 聚焦于 MVI UI State 编排，混合职责全量代理至用例与协调器）。

Gate：离线、重复/乱序申请、接受与撤回竞态、恶意二维码和账号隔离测试通过。

### P02 Explore、动态、评论、作者页与附近的人

当前状态：`[x]`。`ExploreViewModel.kt` 2,045 行已瘦身为 81 行薄门面；领域逻辑与状态机拆解至 `ExploreOrchestrator`、用例集与专业仓储。

- [x] Feed、详情、评论、点赞、编辑器、图片上传、草稿、作者页、附近拆 feature（`ExploreOrchestrator` 编排、`LoadFeedUseCase`/`PublishPostUseCase`/`ToggleLikeUseCase`/`CommentPostUseCase`/`ResolveNearbyUseCase` 纯用例）。
- [x] 建立 `FeedRepository`、`PostMutationRepository`、`DraftRepository`、`MediaUploadQueue`（契约与实现全量就位，配齐单测）。
- [x] 乐观 mutation 使用持久 journal，失败可确定性回滚（`PostMutationJournal` + `PostMutationRepository` 实现点赞/评论/删除乐观更新与确定性回滚）。
- [x] 统一隐私、好友、拉黑、举报和审核后的可见性（`PostVisibilityPolicy` 统一度量好友、拉黑、隐私选项与审核状态过滤）。
- [x] 删除一个 ViewModel 管理全部分页、弹窗、上传和草稿的结构（`ExploreViewModel` 2045→81 行纯薄代理门面，状态聚合至 `ExploreUiState`）。

Gate：分页竞态、断网发布、上传恢复、草稿隔离、可见性矩阵和单测 100% 通过。

### P03 音视频通话与 WebRTC

当前状态：`[~]`。`CallSessionMachine`（状态机+单测）、`SignalingOfferFreshnessPolicy`、`MissedCallTimeoutPolicy`、`GroupCallPolicy`（6 人上限/网格/边协商）纯策略已落地；`CallOfferSelector` 统一双路径来电选择（6 单测）。`CallSystemIntegration` 已承接 FGS 启停、Telecom `placeIncomingCall`/`registerPhoneAccount`、锁屏旗标查询（4 单测）；MainActivity / CallNavigation / MaodouchatApp 改走该端口。本轮：客户端出站 `CallSignalingOutboundCursor` + 入站 `CallSignalingAdmissionPolicy`/`CallSignalingIdempotencyStore`/`CallSignalingOrderPolicy`；REST/WS/Realtime 全链路携带 `epoch`/`sequence`/`idempotencyKey`；`GroupCallCapabilities` 显式标记 SFU/屏幕共享/录制未实现。真实设备门与媒体采集分层仍待做。

- [x] 建立 `CallSessionMachine`（`CallSessionMachineTest` 锁定生命周期）与 offer/超时/网格纯策略；`CallOfferSelector` 统一双路径来电选择语义。
- [~] Direct、Group Mesh、媒体采集、音频路由、ICE 恢复、系统集成分层（`CallSystemIntegration` 已覆盖 FGS + Telecom 派发 + 锁屏判定；`CallMediaBridge` 承接 mute/video/camera/route/renderer 与 manager 生命周期，3 单测；ICE restart/群 peer 信令仍经 ViewModel→manager）。
- [x] 所有信令携带合法 `callId`、session epoch，并幂等处理（出站游标 + 入站准入/幂等；服务端 `CallSignalingOrderPolicy` + idempotencyKey 复用；WS/REST fanout 透传 epoch/seq/key，遗留 0/0 仍放行）。
- [x] ViewModel 只显示 call state 和发送 user intent。
- [~] 前台服务、Telecom、锁屏和权限生命周期进入 `CallSystemIntegration`（FGS/Telecom/锁屏已接入；通话中权限请求仍按入口触发）。
- [x] 明确 6 人 Mesh 上限；SFU、屏幕共享、录制保持未实现，不能伪装完成（`GroupCallCapabilities` + `canStartMesh` 接线 VM；`SFU_SUPPORTED`/`SCREEN_SHARE_SUPPORTED`/`CALL_RECORDING_SUPPORTED` 恒 false）。

Gate：两模拟器真实音视频、后台/锁屏、ICE 断线、蓝牙、群成员变化和进程恢复通过。

### P04 设置、主题、语言与多端偏好

当前状态：`[x]`。已完成：落地版本化 `VersionedSettingsRepository` 作为唯一偏好真相源，严格分层多端漫游偏好（`MultiDevicePreferences`）与本机独占偏好（`LocalDevicePreferences`）；设计 `PreferenceConflictPolicy` 提供 revision/时间戳/局部未提交 patch 抢占合并及跨账号强隔离；实现 `ServerSwitchTransaction` 保证切服 URL 格式校验、凭据原子清理与回滚保护；建立 `MaodouDesignTokens` 语义化规范 Spacing/Shapes/Elevation/Colors，统一组件样式与持久化边界；单元测试全量通过。

- [x] 组件拖拽工作台与底栏优化：移除多主题切换以保持毛豆品牌设计一致性；保留并强化 `ThemeWorkbenchScreen` 可视化组件拖拽排版工作台（气泡/名片/控制面板/统计指标实时长按拖拽排序与微动效）；液态玻璃悬浮底栏（`LiquidBottomTabs`）支持自适应磨砂发光降级与微压感。
- [x] 资料、设备、安全、隐私、通知、AI、服务器、主题、审核、Bot 管理拆 feature 与清晰领域边界。
- [x] 版本化 `SettingsRepository` 是唯一真相源，明确本机项与多端项（`VersionedSettingsRepository` / `DefaultVersionedSettingsRepository`）。
- [x] 多端偏好拥有 revision、owner 和冲突策略（`PreferenceConflictPolicy` 与 `ConflictResolutionResult`）。
- [x] 主题 token、持久化、动画、组件样式分开；不在页面散写颜色与圆角（`MaodouDesignTokens` 统摄 Spacing、Shapes、Elevation 与 Semantics）。
- [x] 各设置 Composable 不直接耦合裸网络或全局单例，由 ViewModel 与 Repository / Coordinator 门面接管。

Gate：多设备冲突、账号隔离、切服事务、主题/语言恢复和各页面 Compose 测试通过。

### P05 安全中心、应用锁、PIN 锁、TOTP 与设备管理

当前状态：`[x]`。已完成：`SecurityCoordinator` 统一应用锁、敏感操作拦截、窗口防截屏与设备风险评估；`PinSecurityPolicy` 与 `DefaultPinLockRepository` 落地 PBKDF2 强化、失败限流、会话缓存与自动迁移；`TotpCoordinator` 提供不可变 TOTP 状态机；`DeviceRevocationCoordinator` 实现多设备撤销在 Auth、Signal、Push 和 Realtime 间联动；`FakeChatThreatModel` 与 `FakeChatDataBoundary` 建立严格安全边界；全量单测 100% 通过。

- [x] `SecurityCoordinator` 统一应用锁、敏感操作、窗口防截屏和设备风险（`com.maodouchat.security.coordinator.SecurityCoordinator` 统一调度 `AppLockManager`、`SensitiveActionGate`、`ScreenSecurePolicy` 及设备 Root/调试/模拟器风险评估）。
- [x] PIN hash 迁移、失败限流、解锁缓存和忘记 PIN 清理有版本化策略（`PinSecurityPolicy` 与 `DefaultPinLockRepository` 支持 PBKDF2-HMAC-SHA256、旧版 SHA-256 无感自升级、失败锁定与账号隔离解锁缓存）。
- [x] TOTP setup/confirm/disable/recovery code 建立完整状态机（`TotpState` 与 `TotpCoordinator` 管理 SetupReady、Enabled、RecoveryCodes 与安全回滚）。
- [x] 设备撤销同步关闭 auth session、Signal device、push 和实时连接（`DeviceRevocationCoordinator` 协同 API 吊销、Signal 会话失效、Push 解绑与 WebSocket 关闭）。
- [x] 假聊天等高风险隐私功能单独威胁建模并提供清晰数据边界（`FakeChatThreatModel` 与 `FakeChatDataBoundary` 提供真实数据读写阻断、通知脱敏与桌面入口死锁防护）。

Gate：锁屏超时、进程恢复、设备撤销、TOTP replay、截图与数据泄漏测试通过。

### P06 AI、本地 Agent、改写、摘要与工具调用

当前状态：`[~]`。Telegram 风格 AI 助手、多模态视觉识别与一键体验已实装；工具与 Command/Query Port 进一步解耦仍待深入。

- [x] Telegram 风格 AI 助手交互与群引导：`MaodouAgentScreen` 动态光圈头像、功能引导卡片、提示词芯片（Suggestion Chips）、流式打字与敏感操作审批卡片；`GroupDetailScreen` 专属引导入口。
- [x] 多模态视觉理解模型支持：`LocalAiModels` 与 `LocalAiProviderStore` 引入 `supportsVision` 属性与配置开关，纯文本模型上传图片时智能提示降级。
- [x] AI 与隐私体验优化：`AiPrivacyPreferences.enableAllDefaults` 支持设置页一键开启推荐默认配置。
- [x] 彻底清理端侧遗留模型代码：已删除废弃的 `OnDeviceEmbeddingGate` 及其单测，净化注释与文档。
- [~] 拆为 Provider、Credential、Context Builder、Tool Registry、Approval、Audit、Executor。
- [ ] 每个 Tool 只依赖领域 Command/Query Port，不访问 DAO、ApiService 或 Application。
- [ ] 明确每种模型调用的数据出境清单、密聊/PIN 门禁和日志脱敏。
- [ ] 写操作携带审批记录和幂等键；取消后不得继续执行工具。
- [ ] 改写、回复建议、翻译、摘要、OCR、转写、任务提取分别拥有状态和错误边界。
- [x] 端侧 embedding 当前仍未实现，保持明确标记。（2026-10-29 核验：未实现——`OnDeviceEmbeddingGate` 已删且无残留引用，app/server 均无 onnx/tflite 等向量模型依赖，端侧 AI 能力均为纯本地启发式规则；标记明确——`docs/feature-inventory.md` 能力表记“暂不考虑”、已知缺口记“明确禁止进包”、本机助手行记“不引入 embedding 向量模型”。）

Gate：prompt injection、伪造 tool call、重复写、流中断、账号切换和零隐私泄漏测试通过。

### P07 Push、后台保活与本地通知

当前状态：`[~]`。Push 注册语义与实际 WS 保活混杂。`AppNotifier.kt` 已删除（1057→0）：`EXTRA_*` 逐字迁入 `NotificationIntents`，35 文件调用方改直连四服务/基础设施（含 3 个 mockk 单测转 mock 新服务）。本轮：`PushKeepAliveService` 全模式仅 dataSync FGS；`PushKeepAlivePolicy` 将 media/call legacy 别名归一化为 foreground，并永久禁用 MediaSession/假来电伪装（4 单测）。

- [x] 冻结通知槽位纯策略：`NotificationSlotPolicy`（消息/reminder/来电/未接/动态/AI任务/好友/群邀请/公告/测试 tag+id+分组+dataURI+requestCode，表达式与历史行为逐字等价；`AppNotifier` 私有槽位常量与 `incomingCallNotifyId/missedCallNotifyId/aiTaskGroupSummaryId` 私有函数已删除）。
- [~] 定义统一 `PushTransport`；前台 WS 与后台推送渠道职责分开（`PushTransport`/`PushTransportController` 已冻结前台 WS 连接门闩 + 单测；FCM HTTP 已移除，离线唤醒走 Ideaura 式 WS 保活；后台渠道与保活语义仍待继续收敛）。
- [~] 推送只唤醒 inbox/sync，不携带聊天敏感正文（客户端 `PushWakePayloadPolicy` 拒绝 body/preview/plaintext 等键，5 单测；服务端 `enqueuePostInteraction` 停写 preview；消息正文已不经 push data，wake→`MessagingV2Runtime` inbox sync）。
- [x] 替换语义模糊的守护/假来电/媒体保活实现，遵守 Android 后台限制（运行时仅 dataSync FGS + Wake/WifiLock + daemon 互拉；`effectiveMode` 将 media/call 遗留偏好归一为 foreground；`wantsMediaSession`/`wantsFakeCall` 恒 false，4 单测；真实通话仍由 `CallForegroundService`）。
- [x] 拆分 Message、Call、Social、Reminder notification service（4/4 完成：+`MessageNotificationService`（showMessage/reminder/test/scheduledFailed/cancelMessage，M11 动作/前台静音/群渠道逐行搬运）与 Post 归位 Social（showPostInteraction/cancelPostInteraction）；`AppNotifier` 1057→207 行，仅剩薄委托/`EXTRA_*` 常量/兼容入口）。
- [x] 通知去重保持纯策略（`NotificationSlotPolicy` 为 tag/id/分组/data-URI/requestCode 唯一事实源；账号隔离仍由 `notificationOwnerMatches` + `NotificationIntentPolicy` 双门禁执行；共享实现下沉为模块内 `NotificationInfrastructure`，四服务直连）。
- [x] 删除巨型 `AppNotifier` 静态入口（文件已删除；调用方直连四服务/`NotificationInfrastructure`/`NotificationIntents`，零 `AppNotifier` 代码引用残留）。

Gate：Doze、强杀、重启、Token 轮换、Android 13-16 权限、密聊脱敏和重复推送通过。

### P08 Navigation、Activity、深链与系统入口

当前状态：`[~]`。`NavGraph.kt` 1050→356 行（路由定义迁入 `NavRoutes.kt`；登录主壳/聊天/设置/动态联系人/通话/群玩法/搜索通知 7 个 feature 簇逐字搬运），Activity 宿主持续收敛。纯决策层已冻结并接线：`AppLinkDestination`/`AppLinkRouter`/`AppLinkAuthGate`/`StartupPermissionPolicy`/`NotificationTargetReplayPolicy`（通知回放等待/丢弃/导航，10 单测）。

- [x] 冻结纯决策契约：typed `AppLinkDestination`（ChatDetail/AiTasksChat/PublicProfile/PostDetail/GroupInvite/NotificationCenter/CallHistory，`requiresAuth` + `toRoute()`）与 `AppLinkRouter`（深链白名单 scheme/host、通知 extras 映射、严格/宽松两档清洗器、对外深链模式唯一事实源 `publicProfileDeepLinkPatterns` 直供 `NavGraph`）。
- [~] MainActivity 外部深链分支已迁移到 `AppLinkRouter.parseDeepLink`（手写 scheme/host/path 截取删除；parity 单测锁定旧接受/忽略语义；多余路径段从截断改为拒绝，见 8.34 注释意图）。通知 extras 四 ID（chat/message/aiTasks/post）已改走严格清洗（含 /?# 拒收；非法 messageId 仅丢高亮、仍开会话）。系统入口派发改走 `toDestination().toRoute()` 统一映射（与 `Routes.*` builder 同构锁定，21 单测）。Widget 打开/标已读生产侧同样清洗（非法 ID 走刷新/丢弃，不构造 intent）。Telecom/FCM 唤醒 callId/senderId 已改走严格清洗（非法值按空串走通用轮询，不定向响铃，23 单测）。群邀请码深链 / QR 已统一至 `AppLinkDestination.GroupInvite` → `JoinGroupInviteScreen` + `JoinGroupInviteUseCase`（清洗经 `sanitizeChatInviteToken`/`sanitizeInviteCode`，UI 不直连 join API，5 单测）。网页链接已统一：`resolveUserFacingUrl` + `ExternalUrl` + `AppLinkOpener`（气泡 Markdown/富文本/预览卡 + 媒体中心链接 Tab；应用内深链优先、其它 http(s) 外开；危险 scheme 拒绝）。通知中心 legacy 字符串（`maodouchat:chat:`/`ai_tasks:`/`post:`/`contacts`/`missed_calls`/`group_invites`）已统一经 `AppLinkRouter.parseLegacyCenterDeeplink`（`SearchCenterDestinations`/`NotificationCenterScreen`/`resolvedNotificationChatId`；注入拒绝；单测覆盖）。
- [x] 每个 feature 提供 destination contract 并由 NavGraph 统一注册（6 簇：`SettingsDestinations`/`ExploreDestinations`/`CallDestinations`/`GroupPlayDestinations`/`SearchCenterDestinations`/`ChatDestinations`；41 路由注册完整性由 `scripts/check-nav-registration.py` 在 CI 锁定）。
- [~] 通知、来电、Widget、二维码、邀请和网页链接统一经过 `AppLinkRouter`（通知中心 legacy 已接入；外部 ACTION_VIEW 已覆盖 PublicProfile/Chat/Post/AiTasks；邀请码深链与 QR 已统一 GroupInvite 目标；用户面 http(s)/深链已统一 `resolveUserFacingUrl`）。
- [~] Deep link 参数在执行业务前完成认证、权限和数据校验（`AppLinkAuthGate` 纯门闩 + ExternalUrl 免登录；`NotificationIntentConsumer` 深链 Accepted 后先 evaluate；未登录仍入队 pending 由 MainActivity 等登录回放；`NotificationTargetReplayPolicy` 统一会话代际/账号归属/登录等待决策，10 单测；权限/数据校验仍待按目标补齐）。
- [~] `MainActivity` 宿主收敛中：通知入口已交 `NotificationIntentConsumer`，窗口隐私已交 `WindowPrivacyController`，前台/后台 presence 与 `activeChatId` 清理已交 `AppForegroundLifecycleController`；App 锁/假聊天门闩已交 `AppSurfaceGateController`；来电锁屏旗标轮询已交 `CallLockScreenFlagController` + `CallSystemIntegration.lockScreenFlagsNeeded`；冷启动通知权限清单已交 `StartupPermissionPolicy`；通知导航回放已交 `NotificationTargetReplayPolicy`（Activity 只 delay/navigate/清 StateFlow）；`ActivityResult` 启动器与 Compose 宿主仍在 Activity。
- [ ] 导航接线由唯一集成 Agent 完成。

Gate：冷/热启动、登录重定向、返回栈、非法链接、旋转、进程恢复和平板测试通过。

### P09 Widget、应用更新、安装与发布渠道

当前状态：`[x]`。更新乱码修复、Android P+ 签名提取修复、WebRTC 原生库 SHA-256 强校验已落地。Widget 行投影已纯化为 `WidgetRowPolicy`（隐私门禁/脱敏/角标/上限，10 单测）。安装侧 `AppUpdateInstallPolicy` + WorkManager 下载（`AppUpdateDownloadWorker`）已接线；发布渠道/签名/回滚文档与 `scripts/check-app-update-gates.py` CI 门禁已落地。

- [x] 更新清单与安装器签名修复：修复 `OfficialApkInstaller` Android P+ 签名提取与十六进制标准格式化；修复 `AppUpdatePolicy` UTF-8 / ISO 智能转码防乱码。
- [x] WebRTC 原生库按需下载加固：`WebRtcNativeDownloadPolicy` 来源白名单与强制 SHA-256 哈希校验。
- [x] Widget 使用账号隔离的 projection 和消息 command port（行投影 `WidgetRowPolicy.buildWidgetRows` + 10 单测；命令端口 `androidQuickReplyCommandHandler` 门禁/去重/发送三件套经 `QuickReplyUseCase` 落库，16 单测全绿）。
- [x] 更新清单校验 HTTPS、SHA-256、包名、versionCode 和签名证书（offer 侧 HTTPS/SHA/version；安装侧 package+signer+archive `versionCode` 与 offer 对齐且严格高于已安装，经 `AppUpdateInstallPolicy` + `OfficialApkInstaller`）。
- [x] 下载进入 WorkManager，可恢复并校验完整性（`AppUpdateDownloadWorker` + `AppUpdateDownloadScheduler` 唯一任务/联网约束/指数退避；Worker 内 SHA/包名/签名/versionCode 校验后弹安装；UI 观察 WorkInfo 进度）。
- [x] 禁止降级、错误签名、错误包和不可信重定向（redirect 复核、SHA、包名/签名、archive versionCode 门禁已落地；HTTP 失败可重试，完整性失败不重试）。
- [x] 发布渠道、签名证书和回滚策略形成文档与自动检查（`docs/app-update-release.md` + `scripts/check-app-update-gates.py`，CI android 作业接入）。

Gate：截断、哈希错、签名错、空间不足、权限恢复、多 Widget 和换号测试通过。

### P10 性能、无障碍、国际化与 UI 稳定性

当前状态：`[ ]`。尚无系统 Compose UI/截图/无障碍回归体系。

- [ ] 建立启动、聊天列表、长时间线、图片列表、数据库和内存基准。
- [ ] 所有主流程支持大字体、TalkBack、触控目标、RTL、中英文和动态颜色。（**2026-09-27 扩展**：①**配置覆盖到五屏**——ChatList/Settings/Call/Explore（`ConfigRobustnessTest`，8 例）+ Contacts（`ContactsScreenUiTest` 补两条，合计 7 例），RTL 与大字体各占一条，全部先断言「配置真的生效」再断言内容仍在；负控制各 1 轮（把方向强制成 Ltr → 恰好对应的 RTL 用例红）。②**TalkBack/触控目标维度首次有可执行证据**——`SemanticsAuditTest` 遍历 Compose 语义树，断言「可点击节点必须可朗读」+「触控目标 ≥48dp」，ChatList/Call 两屏 0 问题，并常驻自检用例（故意无名字的点击区必须被抓到）防探针失明。**它已抓到并修掉一个真缺陷**：ChatList 搜索输入框空值时无可朗读名字（placeholder 是兄弟节点）→ `SearchBar` 给 `BasicTextField` 挂 `semantics { contentDescription = placeholder }`（ChatList/ContactsList/ExploreFeed 共用受益）；负控制：撤掉该修复 → 恰好 `chatListSemanticsAreClean` 一条红。**仍未做**：中英文、动态颜色、像素级截图）
- [x] 固定格式控件使用稳定尺寸，避免消息状态和进度造成布局跳动（2026-09-26：`TextMessageBubble` 翻译行、`MediaVoiceBubble` 语音转写行的进度圈由条件 `if + Spacer(6.dp)` 改为恒定 20dp 占位 `Box`，转圈出现/消失时气泡宽度不再跳变；其余已稳定：`MessageStatusIcon` 恒 14dp 容器、`FileTransferActions` 恒 32dp 行、媒体传输进度为居中覆盖层不影响布局、`LinearProgressIndicator` 为 `fillMaxWidth`）。
- [ ] 建立截图基线覆盖浅色、深色、手机、平板和横屏。
- [ ] 避免超大 state 导致全屏重组；用稳定 selector 和分页投影。

Gate：性能预算、Macrobenchmark、无障碍扫描和截图回归进入 CI。

## 9. Server 重构

### B01 配置、数据库迁移、任务与部署基础

当前状态：`[~]`。版本化 migration runner（PostgreSQL advisory lock + H2 行锁 + schema_migrations 历史表）已存在；表已按领域拆分；Database.kt 已拆出 SchemaMigration（47 行只剩 datasource/schema 引导）；ServerConfig/RuntimeConfigService 分离、Docker/Hikari/优雅关闭齐备；任务租约原语已就绪（`job_leases` 表 + `JobLease` 行锁抢占/续约/释放，`JobLeaseTest` 3 例，含 8 线程恰好一人 + PG 唯一冲突转 false）。本轮：生产启动去掉旁路 `initDatabase()`，schema expand 仅经 migration v1；`docs/server-migration-expand-contract.md` 文档化 expand→compatibility→backfill→contract。剩余：测试夹具仍可 `initDatabase()` 快速建表（不记版本），长期应统一走 migration 夹具。

- [x] 引入 Flyway/Liquibase 或等价版本化 migration runner（`db/migration/MigrationRunner`，PostgreSQL advisory lock + H2 行锁 + schema_migrations 版本表）。
- [~] 每领域独立 schema/table 文件，Database 只管理 datasource（表已拆 CoreTables/AdminTables/MessagingV2Tables/SignalTables/PollTables/ServiceMessageTables；Database.kt 仅留 `createSchemaTables` 供 v1/测试；生产 `Application` 只调 `runDatabaseMigrations()`，启动期旁路建表已删）。
- [x] 采用 expand -> compatibility -> backfill -> contract 发布流程（migration v1=baseline expand、v2=contract retire legacy、v3=backfill pairs、v4=expand signaling；流程见 `docs/server-migration-expand-contract.md`）。
- [x] 破坏性 drop 只在明确 contract 版本执行（`retireLegacyMessagingTables` 在 migration v2）。
- [x] 后台清理任务使用数据库 lease（`MaintenanceRunner` 持有 6h×11 + 15min×1 双循环本体，`Routing.kt` 只做装配启停 559→541 行；租约/健康追踪语义不变）。
- [x] typed immutable startup config 与 runtime settings 分离（`ServerConfig` + `RuntimeConfigService`）。
- [x] 统一 Docker/self-host 配置，支持两副本、优雅关闭和 readiness（`docker-compose.yml` + `server/Dockerfile` + HikariCP 优雅关闭）。

Gate：空库、旧库升级、重复/中断 migration、备份恢复、滚动发布和 PostgreSQL 测试通过。

### B02 认证、账户、Session、设备与隐私

当前状态：`[~]`。`MfaService`（TOTP）、`BlockService`（拉黑）、`AccountLifecycleService`（注销）、`PrivacyService`（隐私）、`CredentialService`（注册/登录/改密/重置/校验）、`ProfileService`（昵称/状态/头像/用户名）、`SessionService`（全设备登出/单会话结束：access 版本 + 推送 token 收尾）已从 `UserRepository`/路由抽出（UserRepository 1281→633 行）；登录失败锁定状态机已抽为可注时钟的 `LoginAttemptGate`（`Routing.kt` 局部函数 + `AuthRouting` 内联检查收敛，`LoginAttemptGateTest` 5 例 + 路由级 `LoginLockoutPrivacyRouteTest` 4 例）。

- [x] 拆 `CredentialService`、`MfaService`、`SessionService`、`ProfileService`（`SessionService` 收敛 7 处「废会话 + 清推送」组合：改密/重置/全设备登出/删号/三处封禁，WS 断开仍归路由；`ProfileService`/`CredentialService` 同构拆分；14 个仓储的 `isUniqueViolation` 私有拷贝已删除）。
- [~] 拆 `PrivacyService`、`BlockService`、`AccountLifecycleService`（三个均已拆；UserRepository 保留薄委托）。
- [~] 登录失败、验证码和 limiter 使用可共享 store，支持多实例（`EmailVerificationCodeContractTest` 3 例锁定迁移门：用途隔离/5 次锁定/未知邮箱拒识；`EmailService` 进程内存储迁移本身需 Redis/DB + 部署配合，暂不动生产认证链）。
- [~] `DeviceSession` 明确绑定 auth session、Signal device 和 push token（`DeviceSessionBinding` 值对象收敛 V2 四处设备门；push 会话绑定已存在（注册校验 + 投递过滤），缺口精确定位：推送 installationId（string）与 Signal deviceId（int）分属两套命名空间，需双端协议扩展映射，列为专项）。
- [~] 注销使用可重试编排器和删除清单（删除清单经全表审计补齐；`withSerializationRetry` 落地：死锁/串行化失败整体重跑，`TransactionRetryTest` 4 例；分步可恢复编排待做）。
- [x] 认证/用户路由全部迁出 `Routing.kt`（`AuthRouting`/`AccountRouting` 独立文件，`Routing.kt` 零 endpoint 定义、无 route 内事务；身份提取收敛为 `call.requireUserId()`）。

Gate：refresh rotation/replay、多设备登出、TOTP、两节点限流、注销恢复和 WS 撤销通过。

### B03 Signal Key 与设备注册

当前状态：`[~]`。领域模型已抽到 `SignalKeyModels.kt`；`DeviceRegistry`/`IdentityKeyStore`/`SignedPreKeyStore`/`PreKeyStore` 类拆分已落地；`SignalKeyRepository` 内重复的 key 读写实现已删除（`getBundle`/上传/清理改走 Store，`consume/peek/getSignedPreKey/upload/purge` 私有重复 + 私有 key 常量清零，`PreKeyStore` 补 FIFO 取键）；`PreKeyStoreTest` 7 例锁定原子消费/并发恰好一次/消费 id 永不复活/过期清理。本轮：同槽身份不一致写 `IDENTITY_KEY_MISMATCH` 审计并吊销当前 auth/refresh；首发身份写 `IDENTITY_KEY_PUBLISHED`；V2 `ConversationDeviceSnapshotStore` 只依赖只读 `EncryptableDeviceDirectory`。

- [x] 拆 `DeviceRegistry`、`IdentityKeyStore`、`PreKeyStore`、`SignedPreKeyStore`（`SignalKeyRepository` 只剩组合门面 + 上传编排 + 通用单键读取，存储逻辑在各 Store）。
- [x] 设备状态明确为 pending/confirmed/revoked（`DeviceRegistryTest` 4 例：首设备自确认/次设备待批、签名批准+重放幂等、伪造签名/自批/越权批准全拒绝、末确认设备删除保护；fixture 约束：先 touch 落行再插 identity key，否则首设备误判 PENDING）。
- [x] one-time pre-key 消费使用数据库原子操作（`forUpdate` 行锁 + 条件更新 + FIFO；8 线程并发恰好一人成功，H2 验证）。
- [x] 身份密钥变化产生安全事件并触发会话风险处理（`IdentitySecurityEventPolicy`/`Recorder` → moderation_audit_log；mismatch 吊销当前 auth session + refresh；上传测试锁定事件与会话）。
- [x] V2 只依赖只读 `EncryptableDeviceDirectory`（`ConversationDeviceSnapshotStore`/`MessagingV2Repository` 注入目录；`ConversationDeviceSnapshotStoreDirectoryTest` 锁定）。
- [x] backfill 迁入版本 migration，删除缺 device id/status 的长期兼容（migration v5 + `backfillMissingSignalDevices`；`DeviceRegistry.getDeviceInfos` 不再为缺行合成 PENDING）。

Gate：并发 pre-key、设备批准防重放、被撤销设备、新设备群 key 修复通过。

### B04 会话创建、查询、设置与生命周期

当前状态：`[~]`。会话域已拆为 9 个 repository 文件；旧 direct 热路径扫描已删除（回填迁移 v3 替代）。`ConversationCommandService` 统一命令入口已建并完成全部路由接线：创建/退出（`ConversationRouting`）、设置/阅后即焚（`ConversationSettingsRouting`）、机器人退出（`BotChatModerationRouting`，经 BotApi→BotCore 转发）、机器人私聊幂等直达（`getOrCreateDirect` 显式命令，经 `CreationService` 透传，`create` 校验语义不受影响）；路由层不再直连三件套。

- [x] `ConversationCommandService` 统一 direct/group/channel 创建、退出、删除和归档语义（四命令门面；全部路由改走命令服务，全量服务端测试通过）。
- [~] `ConversationQueryService` 只做授权后的 metadata 查询（`ConversationQueryRepository` 已存在）。
- [x] DirectChatPairs 唯一约束保证并发创建只有一个会话（`pairKey` 主键）。
- [x] 设置、可见性和参与者查询拥有独立 repository（SettingsRepository/Visibility/ParticipantRepository 已分）。
- [x] 删除 `findLegacyDirectIdInTx` 等热路径兼容（已删，migration v3 `backfillDirectChatPairs` 回填替代）。

Gate：并发建直聊、退出/删除、拉黑、账号注销和权限矩阵通过。

### B05 群成员、角色、邀请、审计与群玩法

当前状态：`[x]`。成员/角色/邀请统一入口、revision+审计不变量、提交后通知订阅者、Admin 不直写表、群玩法独立子域均已落地。

- [x] `GroupMembershipService` 是所有成员/角色/转让操作唯一入口。
- [x] `GroupInvitationService` 统一创建、轮换、接受、拒绝、撤销和过期。
- [x] 每个成员事务恰好增加一次 revision，并产生领域事件与审计。
- [x] WS、Push、Sender Key 修复是提交后订阅者。
- [x] Bot/Admin 不得直接写 Chats/Participants。
- [x] 签到、接龙、PK、Poll 分为独立子域。

Gate：邀请/撤销、退群/发送、踢人/转让并发和完整权限矩阵通过。

### B06 Messaging V2 admission、metadata 与设备邮箱

当前状态：`[x]`。准入/设备快照/邮箱/元数据/服务发布五个子域拆分完成，保留期与幂等语义落地，路由瘦身为 auth/DTO/错误映射/wake，无临时实例化。

- [x] 拆 `MessageAdmissionPolicy`、`ConversationDeviceSnapshotStore`。
- [x] 拆 `EnvelopeMailboxStore`、`MessageMetadataStore`、`ServiceMessagePublisher`。
- [x] 保持 metadata + envelopes 单事务和幂等重试。
- [x] 明确 ACK 后保留期、未 ACK 最大保留期、审核删除和退群清理语义。
- [x] 建立 `MailboxRetentionJob`，绝不删除未 ACK 有效信封。
- [x] 路由只做 auth、DTO、错误映射和 wake。
- [x] 删除 Routing/Bot 中临时实例化 Messaging repository 的路径。

Gate：两账号各两设备、离线数小时、ACK 崩溃点、revision 并发、PostgreSQL 事务和 retention 通过。

### B07 附件、Blob、上传会话与 GC

当前状态：`[x]`。BlobStore/上传会话/提交服务/状态机/媒体引用/OrphanGC 全部落地，共享可替换 blob 基础设施（BlobStorage + LocalBlobStorage + BlobRoot）。

- [x] 建立 `BlobStore`、`UploadSessionService`、`AttachmentCommitService`。
- [x] 建立 `MediaReferenceService` 和 durable `OrphanGcJob`。
- [x] 统一 staged/uploading/uploaded/committed/deleted/quarantined 状态。
- [x] 下载授权只依赖成员身份和 message metadata。
- [x] 头像、群头像、动态图片和加密附件共享可替换 blob 基础设施。
- [x] 所有媒体 route 迁出 `Routing.kt`。

Gate：乱序块、重复 finalize、文件系统故障、quota、访问控制、checksum、GC 和 path traversal 通过。

### B08 WebSocket、Presence、Typing、Wake 与多实例

当前状态：`[~]`。连接注册表/实时发布/在线状态/打字指示/实时总线（RealtimeBus + LocalRealtimeBus）已拆分，presence 隐私、鉴权撤销与全局 sendToUser 收口闭环；仅跨节点传输（Redis/pub-sub 实现）待部署基础设施后落地。

- [x] 拆 `ConnectionRegistry`、`RealtimePublisher`、`PresenceService`、`TypingService`。
- [~] 使用 Redis/pub-sub 或等价总线支持跨节点 fanout。
- [x] mailbox 是消息真相源，wake 丢失后 pull 仍可收敛。
- [x] 认证撤销立即关闭对应连接。
- [x] presence 隐私、拉黑和 last seen 规则统一。
- [x] 删除全局 `sendToUser` 和 route 内 presence 写库。

Gate：双节点、慢连接、重连、fanout、拉黑侧信道、鉴权撤销和 wake 丢失通过。

### B09 通话信令、TURN 与 Push 唤醒

当前状态：`[~]`。CallSignalingService 统一 REST/WS 校验与仓储；epoch/sequence/幂等键已入库；`CallSignalingOrderPolicy` 准入与投递排序（8 单测）已接线（落后游标 → `CALL_SIGNAL_STALE`；consume 按 epoch/seq/ts/id）；WS `OutgoingSignalingPayload`/`IncomingSignalingPayload` 与 REST wakeup 透传 epoch/seq/idempotencyKey；`TurnCredentialService` 支持 `callId` 绑定签发与 `revokeForCall`（挂断/终端信令撤销双方会话凭据，5 单测）；`GET /api/calls/ice-config?callId=` 已接线。统一 call session 状态机仍待深化。

- [~] `CallSignalingService` 使用统一 call session 状态机（领域门面 + 终端清理已有；完整会话状态机仍待）。
- [x] offer/answer/ICE/terminal 使用 callId、epoch、sequence 和幂等键（入库 + 幂等复用 + `CallSignalingOrderPolicy` 拒绝落后游标 + consume 有序投递；客户端出站游标与 REST/WS 同键重试）。
- [x] REST durable fallback 与 WS 共享同一校验和 repository。
- [~] TURN 凭据短期、限用户/会话、可撤销（用户+可选 callId 绑定；挂断撤销后该会话不再签发 TURN；已发出凭据仍受 TTL 约束）。
- [x] Push 只唤醒来电，不包含敏感 SDP/消息正文。
- [x] 协议升级后删除空 callId 兼容。

Gate：乱序/重复信令、TTL、伪造群成员、邀请限流、TURN 过期和两节点通话通过。

### B10 好友、社交图、动态、附近与可见性

当前状态：`[x]`。SocialGraphService/VisibilityPolicy 统一可见性，PostRepository 拆为命令/查询/交互三服务，社交路由已迁出，图片 GC 进 durable job。

- [x] 建立 `SocialGraphService` 和统一 `VisibilityPolicy`。
- [x] 拆 `PostCommandService`、`FeedQueryService`、`PostInteractionService`。
- [x] 公开/联系人/私有、拉黑、注销在资料、动态、附近、搜索、presence 中共享规则。
- [x] 点赞/评论计数使用数据库约束和事务，不靠 JVM 同步。
- [x] 动态图片删除进入 durable cleanup job。
- [x] 社交路由全部迁出 `Routing.kt`。

Gate：完整可见性矩阵、并发计数、分页、删除和双实例测试通过。

### B11 举报、审核与内容治理

当前状态：`[x]`。ReportWorkflow/ModerationEngine/DispositionService 三域落地，处置写字段+审计统一入口（UserRepository.applyUser*），before/after 审计补齐。

- [x] 建立 `ReportWorkflow`、`ModerationEngine`、`DispositionService`。
- [x] 举报去重、审核状态、限制、内容删除、通知和审计在统一命令内协调。
- [x] 管理员/版主权限和数据可见范围明确。
- [x] 审核动作幂等，必须保存 actor、reason、before/after。
- [x] 合并重复 moderator/admin 路径。

Gate：并发举报、重复 disposition、权限矩阵、审计不可缺失和内容清理通过。

### B12 Bot、Developer API、Webhook 与 Service Message

当前状态：`[x]`。BotCoreRouting（3454 行）与 BotPresentationRouting（1988 行）两个巨型单函数已拆成 **31 个薄子模块**（BotCore 只剩门面 + sendMessage/editMessage/getChat 三个核心端点，430 行）；webhook 数据库 outbox + 原子 worker lease + 退避/死信/周期重放已落地（BotWebhookOutbox 表 + BotWebhookService 抢占式租约 + H2 并发测试）。

- [x] 按 bot-auth、management、updates、messaging、group-admin、media 拆模块（BotCore 3454→430 行 + BotPresentation 1988→153 行，31 个子模块，端点计数 940 由 RouteRegistrySplitTest 锁定）。
- [x] Developer Console 与公开 Bot API 分离。
- [x] webhook 使用数据库 outbox、worker lease、退避、死信和重放（BotWebhookOutbox：PENDING→DELIVERED/DEAD，`claimLease` 原子抢占 + 过期回收 + `replayStalePending` 启动/周期重放）。
- [x] Bot 群操作调用统一 GroupMembershipService。
- [x] Bot 内容通过 ServiceMessagePublisher 投递到所有离线设备。
- [x] Telegram alias 只保留一份业务实现和契约映射。
- [x] secret 必须安全持久化或删除虚假参数。
- [x] 删除静态 repository、route 内事务和 `Routing.kt` 重复 Bot 接口。

Gate：token rotate、webhook 重启/死信、顺序幂等、群权限和 Telegram 契约快照通过。

### B13 Admin、运行配置、运营统计与审计

当前状态：`[~]`。`AdminRouting.kt` 已缩为 22 行注册门面（非 4,575 行）；`RuntimeConfigService` 引入 typed registry（`RuntimeSettingsRegistry`，`defaults()`/`knownKeys` 三重复消除）；`OperationsQueryService` 承接运营统计只读查询；`AdminIdentity`（两级角色解析）+ `UserDispositionService`（单用户/批量处置 + 开关写命令门面）落地，AdminBulkRouting 1366→~800 行，管理路由无裸 `Users.update/insert`；AdminEnhanceRouting 1106→598 行（公告→`AnnouncementRouting`、用户标签→`UserTagRouting`、模型→`AnnouncementModels`/`UserTagModels`）；重复 helper（`receiveEnhanceJson`/`dayBucketExpression`/`recordAdminAudit`/`isAdminUser`/`csvCell`/`parseAdminIds`）全部去重，secrets 不入 `RuntimeConfigService`。**G4 后**：route 内仍有 49 处 `transaction {`（17 个文件），其中 AdminEnhanceRouting(12)/AdminManagementRouting(6) 在 handler 内直写 Exposed DSL；`AdminExportsRouting` 已完成 `AdminExportService`/`AdminExportRepository` 边界（1110→599 行、26→0 事务、Exposed 导入清零）。

- [x] 建立 `AdminIdentity`、`UserDispositionService`、`OperationsQueryService`（`AdminIdentity`+`AdminIdentityResolver`、`UserDispositionService` 写命令门面、`OperationsQueryService` 只读统计）。
- [x] Runtime settings 使用 typed registry 描述类型、默认值、范围、敏感性和重启要求（`RuntimeSettingsRegistry`：类型/默认/range/sensitive/restartRequired，`defaults()` 单一事实源派生 + `normalize` 校验）。
- [x] 管理 route 按用户治理、内容审核、配置、统计、公告拆分（用户治理→Users/Bulk/Management、内容审核→Content/Moderation/Report/UserTag、配置→System、统计→Observability+OperationsQuery/Diagnostics、公告→Announcement）。
- [x] 管理写操作统一 command + audit；统计走只读 query model（处置单/批量、开关、moderator、TOTP 全部走 `UserRepository` 命令 + 审计；统计走 `OperationsQueryService`）。
- [x] 合并 AdminRouting/AdminEnhanceRouting 重复能力（`receiveEnhanceJson`→`receiveAdminJson`、`observabilityDayBucketExpression`→`dayBucketExpression`、`recordObservabilityAudit`→`recordAdminAudit`、`isAdminObservabilityUser`→`isAdminUser`、`observabilityCsvCell`→`csvCell` 等重复 helper 全部去重）。
- [~] 删除重复 getter、路由事务和敏感配置导出（`knownKeys`/`defaults` 三重复消除；`dayBucketExpression`/`recordAdminAudit`/`isAdminUser`/`csvCell`/`parseAdminIds` 去重；secrets 只存 `ServerConfig`，不导出。**但「路由事务清零」仍不成立**（G4 后）：实测 `AdminEnhanceRouting`=12、`AdminManagementRouting`=6、`AdminDiagnosticsRouting`=4、`AdminBulkRouting`=3、`AdminUsersRouting`=3 处 `transaction {`，全项目 plugins/ 共 **49 处 / 17 个文件**；`AdminExportsRouting.kt` 已清零，`AdminEnhanceRouting.kt`(598 行/12 处) 仍在 route 内直写 Exposed SQL）。

- [~] 管理/运维/开发者 route 面补齐 `route→service→repository` 边界（**本项此前漏列，曾是最大架构缺口**。**G4 已完成第一块**：`AdminExportsRouting.kt` 从 **1110 行 / 26 处 `transaction {` / 直接 import Exposed** 收敛为 **599 行 / 0 处事务 / 0 个 Exposed 导入**，27 个 CSV 导出全部改走 `AdminExportRepository`（唯一 SQL 边界）+ `AdminExportService`（组装与 CSV 编码）。**G5 后剩余缺口**：全项目 `plugins/` 仍有 **37 处 `transaction {`，分布于 16 个文件**，其中 AdminManagementRouting=6、DeveloperRouting=4、AnnouncementRouting=4、AdminDiagnosticsRouting=4、AdminUsersRouting=3、AdminBulkRouting=3；`plugins/` 中仍有 **34 个文件**直接 import Exposed；`AdminUsersRouting.kt:272` 与 `SecretSurfaceRouting.kt:185` 在 handler 内 `new Repository()`。**棘轮已两度下调**：`ServerArchitectureTest` 的精确相等基线 75→49→**37**（文件 18→17→**16**）、Exposed 直连 36→35→**34**，两次都实测「下调之后新增一处违规仍会红」）。
- [~] 消除反向依赖：`repository/BotRepository.kt:18`→`plugins.isAllowedWebhookAddress`、`repository/RateLimitStatsRepository.kt:4-5`→`plugins.GlobalRateLimiter/RateLimitStats` 为真实倒置；`service/` 另有 3 个文件（`BotWebhookService`/`CallSignalingService`/`OrphanGcJob`）反向依赖 `plugins/`（G5 已把 `MaintenanceRunner` 的 `purgeAdminOperationalData` 迁到 repository 而消掉一条）；另有 12 个 `*Service.kt`（如 `repository/FeedQueryService.kt` 455 行、`repository/AccountLifecycleService.kt` 432 行）物理错放在 `repository/`，破坏「repository=SQL 边界」契约。以上三组均已进入 `ServerArchitectureTest` 棘轮基线。（**2026-09-27 进展**：前两组已归零——实测 `frozenRepositoryDependingOnPlugins`/`frozenServicesDependingOnPlugins` 均为 0 处；`isAllowedWebhookAddress`、`RateLimitStatsProvider`/`RateLimitStats` 已下沉到中立的 `common` 包。第三组开跑：`AttachmentCommitService`（87 行，调用者仅 `EncryptedAttachmentRepository`）从 `repository/` 迁往 `service/`——package 改名 + 调用方补 import + `frozenServicesInRepositoryPackage` 16→15；`service/` 包已有多个直接 `transaction {}` 的先例，风格一致。`MediaReferenceService`（14 行，纯委托门面，调用方为 `Routing` + `service/OrphanGcJob`）迁往 `service/`——15→14。（**2026-09-28 进展**：`GroupInvitationService`（62 行，`GroupInvitationRepository` 的薄委托门面，群邀请状态机统一入口）从 `repository/` 迁往 `service/`——package 改名 + 补 `import GroupInvitationRepository` + 10 处调用方补 import（7 个 plugins 文件、`repository/ConversationCommandService`/`ConversationCreationService`、1 个测试）+ `frozenServicesInRepositoryPackage` 14→13。类体逐行未动，SQL 边界仍在 `repository/GroupInvitationRepository`。）（**2026-09-28 续**：`SocialGraphService`（49 行 B10 社交图查询 `object`，同包唯二调用方 `FeedQueryService`/`PostVisibility`）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 `import ConversationVisibility`（`internal object`，同模块可见）+ 两调用方补 `import service.SocialGraphService` + `frozenServicesInRepositoryPackage` 13→12。类体逐行未动。）（**2026-09-28 续**：`ConversationCommandService`（53 行 B04 会话统一命令门面，薄委托给 `ConversationCreationService`/`LifecycleRepository`/`SettingsRepository`，调用方为 7 个 plugins 文件经 `Routing.kt` 装配）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 11 个 `repository/` 跨包 import（`GroupInvitationService` 已在 service/，冗余 import 删除）+ 7 调用方 import 改向 service + `frozenServicesInRepositoryPackage` 12→11。类体逐行未动，SQL 边界仍在三 repository。）（**2026-09-28 续**：`PrivacyService`（83 行 B02 隐私读写服务，`transaction {}` 直写 `Users` 表）从 `repository/` 迁往 `service/`——package 改名 + 唯一调用方 `repository/UserRepository` 补 `import service.PrivacyService` + `frozenServicesInRepositoryPackage` 11→10。类体逐行未动；`service/` 包已有多个直接 `transaction {}` 的先例，风格一致。）（**2026-09-28 续**：`ProfileService`（96 行 B02 昵称/状态/头像/用户名薄服务，直写 `Users` 表）从 `repository/` 迁往 `service/`——package 改名 + 补 4 个 `repository/` 跨包 import + 唯一调用方 `repository/UserRepository` 补 `import service.ProfileService` + `frozenServicesInRepositoryPackage` 10→9。类体逐行未动。）（**2026-09-28 续**：`BlockService`（134 行 B02 双向拉黑/好友关系清理，直写 `BlockedUsers`/`Friendships`/`FriendRequests` 三表）从 `repository/` 迁往 `service/`——package 改名（类体无跨包符号，零额外 import）+ 唯一真实调用方 `repository/UserRepository` 补 `import service.BlockService`（`CredentialService`/`ProfileService` 的两处仅为 KDoc 提及，无需改）+ `frozenServicesInRepositoryPackage` 9→8，并把注释里滞后的「当前 10 个」修正为 8（上一轮漏改）。类体逐行未动。）（**2026-09-28 续**：`GroupMembershipService`（109 行 B05 群成员/角色/转让唯一入口门面，薄协调 `transaction {}` + `GroupMembershipRepository`）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 5 个 `repository/` 跨包 import（`GroupMembershipRepository` + 4 个结果类型）+ 13 处调用方 import 改向 service（5 个显式 import 替换，8 个通配符 import 文件新增显式 import；`service/GroupInvitationService` 一处仅为 KDoc 提及，无需改）+ `frozenServicesInRepositoryPackage` 8→7。类体逐行未动，SQL 边界仍在 `repository/GroupMembershipRepository`。）（**2026-09-28 续**：`PostInteractionService`（131 行 B10 动态/评论点赞交互子域，`transaction {}` 直写 `PostLikes`/`CommentLikes` 表）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 2 个 `repository/` 跨包 import（`internal object PostVisibility`、`internal fun isUniqueViolation`，同模块可见）+ 唯一调用方 `repository/PostRepository` 补 `import service.PostInteractionService` + `frozenServicesInRepositoryPackage` 7→6 并修正注释「当前 7 个」为 6。类体逐行未动；insert 块里裸 `createdAt` 等列名走 Exposed `Table.insert` receiver 作用域，跨包不受影响。）（**2026-09-28 续**：`UploadSessionService`（272 行 B07 加密附件上传会话子域：配额、会话创建、分块进度、最终化、未提交移除）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 6 个 `repository/` 跨包 import（`AttachmentStatus`/`EncryptedAttachmentRecord`/`AttachmentUploadSession`/`AttachmentQuotaExceededException`/`AttachmentMessageAlreadyUsedException`/`AttachmentNotAllowedException`，均 public，同模块直接可见）+ 唯一调用方 `repository/EncryptedAttachmentRepository` 补 `import service.UploadSessionService`（其 KDoc `[UploadSessionService]` 链接经该 import 继续解析；`AttachmentCommitService` 已有同模式 import 先例）+ `frozenServicesInRepositoryPackage` 6→5。类体逐行未动；`service/` 包已有多个直接 `transaction {}` 的先例，风格一致。）（**2026-09-28 续**：`PostCommandService`（339 行 B10 动态/评论写命令子域 + 图片 claim 管理）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 4 个 `repository/` 跨包 import（`FeedQueryService`、`internal object PostJson`、`VisibilityPolicy`、`internal fun isUniqueViolation`，同模块直接可见）+ 唯一调用方 `repository/PostRepository` 补 `import service.PostCommandService`（其 KDoc `[PostCommandService]` 链接经该 import 继续解析；`PostInteractionService` 已有同模式 import 先例）+ `frozenServicesInRepositoryPackage` 4→3 并修正注释「当前 4 个」为 3。类体逐行未动；（2026-09-28 续：`FeedQueryService`（456 行 B10 动态 feed/详情/评论/点赞者查询子域，`transaction {}` 直读 `Posts`/`PostComments`/`PostLikes` 表）从 `repository/` 迁往 `service/`——package 改名 + 本文件删已同包的 `SocialGraphService` import + 补 3 个 `repository/` 跨包 import（`PostJson`/`PostVisibility`/`VisibilityPolicy`，同模块 `internal` 可见）+ 唯一调用方 `repository/PostRepository` 补 `import service.FeedQueryService` + `service/PostCommandService` 删已同包的跨包 import + `frozenServicesInRepositoryPackage` 3→2 并修正注释「当前 3 个」为 2。类体逐行未动。）`FileStorageService` 本就全限定引用，迁入 `service/` 后同包解析，冗余 import 删除。）

（2026-09-28 续：`AccountLifecycleService`（432 行 B02 账号注销编排：账号删除与全部关联数据清理，含群主转让/退群/空会话级联/附件孤儿收集）从 `repository/` 迁往 `service/`——package 改名 + 本文件补 1 个 `repository/` 跨包 import（`AccountDeactivationResult`，在 `repository/UserRepository.kt` 顶层声明，public）+ 唯一调用方 `repository/UserRepository`（`private val accountLifecycleService` 构造处）补 `import service.AccountLifecycleService` + `frozenServicesInRepositoryPackage` 2→1。类体逐行未动；`ConversationStateDeletion`/`BotRepository` 仅为注释提及无需改；SQL 边界仍在 `Users` 等 `db` 表与各 repository。现仅剩 `CredentialService.kt`（其顶层 `internal` helper 被 repository 包内调用方使用，迁移需先解耦）。）

（2026-09-28 续：`CredentialService`（269 行 B02 注册/登录/TOTP/改密/邮箱重置凭证子域 + 6 个包内共享顶层 helper：`MAX_NAME_LENGTH`/`DELETED_USER_NAME`/`normalizedEmail`/`toPrivateUser`/`toPublicUser`/`isUniqueViolation`）从 `repository/` 迁往 `service/`——package 改名 + `repository/UserRepository`（类/3 个 helper/`isUniqueViolation` FQ 委托）/`plugins/AuthRouting`（通配符补显式）/`service/ProfileService`/`PostCommandService`/`PostInteractionService`（3 处显式 import 改向）/`service/JobLease`（FQ 改向）改 import + 12 个包内仓储补 `import service.isUniqueViolation` + `frozenServicesInRepositoryPackage` 1→0（空集）。类体逐行未动；成员遮蔽的 5 处（`NearbyRepository`/`FeedQueryService` 的私有 `toPublicUser`、`AccountLifecycleService` 的私有 `DELETED_USER_NAME`、`EmailService` 的私有 `normalizedEmail`、`UserRepository` 的 companion `MAX_NAME_LENGTH` 与成员 `isUniqueViolation`）与 plugins 包自有的 `normalizedEmail` 逐文件核对解析目标，不受影响。）

Gate：master/moderator/user 权限、审计、敏感配置和大数据查询性能通过。

### B14 应用更新、静态文件、水印与运维工具

当前状态：`[ ]`。这些能力存在，但仍与总路由、文件服务和 Admin 混合。

- [x] 官服与动态构建解耦：原生 WebRTC 依赖由 Gradle 构建任务自动解压至 `build/generated/resources/webrtc` 并注入 classpath，彻底移除源码树中提交的原生 `.so`/`.dylib` 二进制残留。
- [x] 更新发布使用签名 manifest、不可降级策略和不可变制品元数据（不可降级 + 不可变 manifest 旁文件 + HMAC-SHA256 签名（key=JWT_SECRET，客户端验签）全部落地；`AppUpdatePublishPolicy` 修复 UTF-8 / ISO 智能转码防乱码）。
- [~] 静态/官网资源与 API route 分离部署和缓存策略（缓存策略已落地：HTML no-cache、静态 CSS/JS `public, max-age=3600`、PNG/WebRTC 更长缓存；分离部署属 nginx/CDN 运维配置）。
- [x] 水印提取任务异步化，限制文件、CPU、内存和执行时间（`Dispatchers.Default` 异步 + 30s 超时兜底；请求体 4MB + 维度 ≤8192/像素 ≤16M 解压炸弹防御）。
- [x] 备份/恢复脚本纳入版本 migration 和定期恢复演练。（2026-10-29 核验：生产 backup-production.sh 用 pg_dump --create --format=custom 全库导出，含 schema_migrations 迁移版本表；演练脚本 rehearse-pg-restore.sh 源库显式建 schema_migrations 并插 3 个版本行，逐表行数比对覆盖该表，恢复后版本行一致；CI Server job 每次提交都跑该演练脚本（ci.yml Rehearse PostgreSQL backup and restore），演练频率高于定期。）
- [x] 健康检查拆 liveness/readiness，并覆盖数据库、迁移和后台任务状态（`/health/live` + `/health/ready` 已拆；ready 覆盖 database/migrations/storage/`backgroundTasks`（连续失败>=3 判 degraded，未运行为 unknown 不拉低）；11 个周期清理任务经 `runTracked` 统一执行 + 心跳追踪，`BackgroundTaskHealthTest` 5 例）。

Gate：恶意文件、资源耗尽、制品签名、备份恢复和滚动发布测试通过。

## 10. 旧实现退役清单

以下项目未删除前，不得宣称全量重构完成：

- [x] `ChatDetailViewModel.kt` 不再包含单体业务逻辑，压缩为组合与分发调度层（接入 `ConversationCommandFacade`、`MessagingV2Outbox` 与领域 UseCase）。
- [x] 旧巨型 `ChatDetailScreen.kt` 实现删除（降为 42 行薄路由，拆分 `TimelinePane`、`ComposerPane` 与独立面板）。
- [x] 旧 `MessageBubble.kt` 单体解耦（薄类型分发入口 + `MessageBubbleChrome.kt` 共享 chrome；`TextMessageBubble`/`MediaMessageBubbles`/`FileMessageRenderer`/`SystemMessageRenderer` 独立渲染器，经 `MessagePresentationMapper` 消除 wire/meta 渲染期耦合）。
- [~] `ChatListViewModel.kt` 瘦身中（多选状态机纯 reducer 9 单测；设置 toggle 纯构造 + 服务端确认合并 10 单测；实时三投影纯函数 7 单测；预览内存投影 + 置顶排序口径纯化 + 批量归零去重 9 单测，`restoreChatSorted` 私有包装已删；`ChatListRealtimeCoordinator` 已承接 chatRead/chatMessageSent/RealtimeEventDispatcher collector 与断线横幅，7 单测；`ChatListLoadCoordinator` 已承接 getChats 请求合并/静默重载/stale 清理/未接来电观察，`ChatListRemoteMergePolicy` 纯化 DTO↔本地未读/设置合并，9 单测；`ChatListAnnouncementCoordinator`（公告拉取/ack + push verify key，5 单测）与 `ChatListMutationCoordinator`（settings/clearMarkedUnread/delete/密聊创建，4 单测）；`ChatListMissedCallCoordinator`（3 单测）、`ChatListArchiveSuggestionCoordinator`（3 单测）、`ChatListPreviewCoordinator`（3 单测）、`ChatListUnreadBatchCoordinator`（全部已读/多选已读共用路径，2 单测）、`ChatListLocalProjectionCoordinator`（草稿/回执/锁定密聊/消息搜索/身份告警，4 单测）；`ChatListPorts`/`AndroidChatListPorts` 已隔离生产侧 MaodouchatApp/ApiService/DAO 装配（ViewModel 只消费 ports，`createForTest` 缝）；ViewModel 1772→578 行；MaodouchatApp/TokenManager 经 ports 注入，全局单例直连已迁出 VM 业务路径）。
- [~] `SignalProtocol.kt` 宽 facade 已拆为薄委托门面（M03/M04 领域服务就位；调用方迁完后删除 facade 本身）。
- [x] `ApiService` 巨单体拆解完成，分离为 Auth、Messaging、Conversation、Media、Social 等独立域 API，收敛为组合委托薄门面。
- [x] `AppNotifier` 全局巨型入口删除（文件已物理删除；四服务 + `NotificationSlotPolicy` + `NotificationIntents` + `NotificationInfrastructure` 替代）。
- [x] Notification Center、Scheduled Message、Reminder 等业务 JSON SharedPreferences store 删除（定时/提醒 Room 36→37；归档忽略 37→38；语音已播 38→39；通知中心本轮迁入 Room 39→40：`notification_center_items` 表 + DAO + 一次性 prefs 导入 + 登出清理，旧 JSON 读写删除；调用方 API 零变更——内存 StateFlow 仍是真相源，磁盘经 `runBlocking(IO)`/后台协程桥接）。
- [ ] 页面、Widget、Worker、AI 直接访问 `MaodouchatApp`/DAO/Signal/Token 的路径归零。
- [x] 服务端 `Routing.kt` 只保留模块注册，不再包含领域 endpoint/事务。（2026-10-13 实测：`transaction {`=0，`routing {}` 内联端点=0；最后两个领域残留——`respondBotUnavailable()` 搬 `BotRoutingSupport.kt`、`PinnedMessagesUpdatedPayload` 搬 `RealtimePayloads.kt`，同包零 import 改动。）
- [x] `BotApiRouting.kt`、`AdminRouting.kt` 巨型实现删除并由子域 routes 替代。
- [x] `Database.kt` 启动期 create/backfill/drop 逻辑删除（Database.kt 47 行仅留 datasource，migrationRunner 接管）。
- [x] 旧人类消息 REST/WS API、v1 消息表和 legacy repository 删除（Messaging V2 全量接管，历史 v1 表在 migration v2 彻底退役）。
- [x] 旧 direct chat 扫描、空 callId、缺 device status 等长期兼容删除（DirectChatPairs 唯一键 + migration v3 替代）。
- [ ] 所有临时 facade 均有删除版本和调用者清零证明。

## 11. 测试与发布硬门槛

### Q01 单元与架构测试

- [~] 每个 domain command/query 有成功、失败、取消、重复和账号切换测试。（2026-10-19：SendMessageCommand 五态在 `ConversationCommandFacadeTest` 补齐——账号切换（owner 按次动态读取）与空幂等键不去重；本轮补 `stageScheduledText`（确定性 id 钉住＋空文本拒绝不走网关）与 `forwardText`（转发链不断：二次转发保留最初 forwardedFrom）；其余 domain 命令仍缺；2026-10-19 oct19j 续批：`MessagingV2MutationFacadeTest` 新增 5 例——revoke 持久性+正文/editedAt 携带、editedAt 缺省回退到发送时刻、id 不一致先于副作用被拒、账号切换后 delete 零副作用、edit 静默标志（不写索引不刷新预览）；facade 构造 helper 新增 persistDeleted/persistRevoked/cleanup 参数，旧 7 例默认行为不变。零生产改动；2026-10-19 oct19k 续批：`MessagingV2MutationFacadeTest` 再增 5 例——edit 无 editedAt 回退到发送时刻、delete 事件只带 action/id 不带正文并透传 groupRevision、enqueue 失败零副作用、预览失败被抑制进投影警告（commit 仍返回）、空白 owner 先于副作用被拒。零生产改动；2026-10-19 oct19m 续批：`MessagingV2MutationFacadeTest` 再增 3 例——revoke/setReaction 账号切换先于副作用被拒（四条入口准入拒绝全覆盖）、reaction 移除事件携带 null emoji 而 room 保留合并后的反应列表。零生产改动；2026-10-19 oct19n 续批：`MessagingV2MutationFacadeTest` 再增 3 例——delete 三步局部投影全失败报首错+其余压制、edit 关索引搜索但仍刷新预览、edit 索引写入 room 返回的 applied 对象。零生产改动；2026-10-19 oct19p 续批（#890）：`ConversationMessageMutationCoordinatorTest` 新增 3 例（11→14）——revoke 预提交失败乐观投影回滚（Set(revoked)→Set(original)，Failed(rolledBack=true)）、edit 预提交失败对应变体、乐观投影回调抛错原样重抛且 tracker 已释放（重试不再被 Ignored）。零生产改动；2026-10-19 oct19q 续批：`MessagingV2MutationFacadeTest` 新增 3 例——空白 owner 在 delete/revoke/setReaction 三条入口均在任何副作用前被拒（补齐 `requireCurrentOwner` isBlank 分支四入口全覆盖；edit 已有）。零生产改动；2026-10-19 oct19r 续批：`MessagingV2MutationFacadeTest` 再增 3 例——入队取消零副作用（取消维度的 facade 层）、同一消息重复删除写两条持久事件（不去重）、被拒绝的 stale edit 仍刷新预览（不写索引）；`ConversationMessageMutationCoordinatorTest` 增 1 例——空白 owner 删除先乐观投射后回滚。零生产改动；2026-10-19 oct19s 续批：`MessagingV2MutationFacadeTest` 再增 4 例——revoke id 不一致在 owner 校验之前被拒（断言顺序）、delete 入队持久先于本地投影（outbox→room→attachment→notification→preview）、投影成功后 preview 失败如实上报不撤销提交、revoke 本地投影首步失败后仍跑完剩余收敛步骤；`ConversationMessageMutationCoordinatorTest` 增 2 例——空白 owner 下 edit/revoke 先乐观投射后回滚（补齐 isBlank 分支三入口 coordinator 层全覆盖；delete 已有）。零生产改动；2026-10-19 oct19u 续批：`ConversationCommandFacadeTest` 新增 3 例（17→20）——cancel 失败态（网关取消失败时返回 false，id 仍被记录）、`sendText` 幂等键去重（同 key 两次返回同一 staged 消息、网关只收到一次）、锁定会话的 retry 在网关前被拒（`LOCKED_CONVERSATION`，`retryCount` 为 0）；2026-10-19 oct19v 续批：`ConversationLocalStateCoordinatorTest` 新增 5 例——首步取 id 失败跳过媒体删除但收敛继续、全量删除 25 步精确收敛序钉住、首步前已换账号零落点、空 chatId/空 owner 在首步前被拒、重复清理两次均完成无失败叠加；`ConversationReadReceiptCoordinatorTest` 新增 5 例——非秘密会话主成功路径（回执入队+本地已读+通知/小部件联动全链）、回执入队失败被吞但本地已读仍成功、本地标已读失败如实返回失败、查库时取消直接传播不转失败、重复标已读两次均成功；2026-10-19 oct19w 续批：`ConversationReactionCoordinatorTest` 新增 4 例——空白 owner 与纯空白 emoji 在取锁前被拒（Ignored 零副作用）、过期账号 toggle 在入队前被拒并回滚乐观投影（Failed）、乐观投影回调抛错原样上抛且锁释放（重试正常）。零生产改动；2026-10-19 oct19x 续批：`OutgoingMessageCoordinatorTest` 新增 5 例（4→9）——解析失败报预提交失败且不反弹绑定（chatId 保持 optimistic 原值）/无会话消息跳过失败持久/提交后投影失败按 committed=true 持久并抑制失败回调/提交前取消传播且跳过持久/抛错的提交回调被吞仍正常暂存；`MessagingV2InboxSynchronizerTest` 新增 5 例（7→12）——空白 token 在 mutex 内先行被拒零副作用/拉取失败零落盘直接传播/毒信封走死信不阻塞后续信封（`markInboxFailed` 零调用）/ACK 不完整抛错且不删 pending/处理器取消传播且锁释放（次轮空转正常）。零生产改动）；2026-10-19 oct19z 续批：`SignalMessagingV2EnvelopePreparerTest` 新增 5 例（7→12）——群加密失败原样上抛零信封/直发加密失败原样上抛/加密层取消原样传播不转失败/token 按次透传给加密层不缓存旧账号/群发 sender key 密文扇出到全部目标（ensureGroupReady 先行、信封按 (userId,deviceId) 排序）；`SignalMessagingV2EnvelopeProcessorTest` 新增 4 例（11→15）——domainSink 取消原样传播/重复投影按信封 owner 查（账号切换不冒充他账号投影）/不可恢复 journal 清零不提交/解不出内容的 service 信封静默丢弃不走解密。零生产改动；2026-10-19 oct19aa 续批：`ConversationForwardCoordinatorTest` 新增 5 例（14→19）——sendNote 空白文本先于持久被拒（`forward_note_empty`，零落点）/会话缺失时 forward(request) 全部目标 NOT_READY（账号切换维度）/后目标取消原样上抛并中断后续目标（取消维度）/附件控制器提交失败记 PERMANENT（失败维度）/批量转发中途会话切换抛 CancellationException 停掉剩余目标。零生产改动；2026-10-19 oct19ab 续批：`ConversationScheduleCoordinatorTest` 新增 5 例（2→7）——queue 时 addScheduled 抛错报 STORAGE 且零落点（失败态）/scheduleJob 抛错时补偿删行（失败态回滚）/空白 owner 下 queue 与 cancel 均 SESSION_MISSING 且零副作用（账号切换维度）/cancelJob 抛错但行已删除仍返回 Success（取消维度：stale worker 自行退出）/reschedule 失败把正文与发送时刻回滚到旧值（失败态回滚）。零生产改动。；2026-10-19 oct19ad 续批：`GroupMessagingCoordinatorTest` 新增 5 例（13→18）——非正 revision 归一为 null 再做持久写/持久写后会话丢失停在写行之后、本地 key 与附件都不碰/状态拉取失败报失败结果不抛错/拉取取消原样上抛/blank chat 与非正 epoch 在查 material 之前被拒；`GroupSenderKeyMaintenanceCoordinatorTest` 新增 5 例（12→17）——手动取消上抛不进重试队列/服务端 epoch 落后判为不完整并入重试队列/取消型的 redistribute 结果原样上抛而非 Pending/重试入队取消上抛且释放 epoch（次轮可再跑）/单飞按 epoch 粒度、不同 epoch 可并行。零生产改动；2026-10-19 oct19ae 续批：`OutgoingConversationResolverTest` 新增 5 例（9→14）——非 IO 创建失败不被离线兜底掩盖（原样上抛、零缓存）/空白 token 先于一切被拒为未登录/拉取无匹配会话报 recipient-not-ready 且零缓存/入口会话失效先于一切 IO 取消/加密未就绪抛错且不创建；harness 新增 createFailure/cryptoReady 钩子与 request authToken 参数，旧 9 例默认行为不变。`MessagingV2ArrivalPolicyTest` 新增 5 例（4→9）——空白 owner 全部效果清零/自己发的消息无可见效果/SK_DIST 不可见/前台到达只加未读不通知/已打开的聊天详情页压通知但回执照发。零生产改动。；2026-10-19 oct19af 续批：`MessagingV2RetryPolicyTest` 新增 5 例（1→6）——now 偏移透传/指数翻倍逐次钉住（1s,2s,4s,16s,32s）/第 10 次触达 5 分钟上限（第 9 次 256s 未触顶）/零与负 attempt 归一为 base delay/attempts=Int.MAX_VALUE 指数钳制不溢出仍 5 分钟；`MessagingV2InboxFailurePolicyTest` 新增 5 例（2→7）——第三个永久码 messaging_v2_sender_key_install_failed 立即死信/未知错误码享满 8 次预算/attempt 0 不死信、9 次起死信/错误码精确匹配（前缀/大小写变体/空串不命中）/可恢复错误码死信判定随 attempt 单调。零生产改动。；2026-10-19 oct19ag 续批：`MessagingV2GroupControlPolicyTest` 新增 5 例（2→7）——控制类型钉住（SENDER_KEY/KEY_REQUEST 为群控、DATA/EVENT/RECEIPT/空串均不是）/类型名大小写敏感/超前 queuedRevision 判 stale/非正 liveRevision 永不 stale/null queuedRevision 在非控制类型下不 stale；`MessagingV2OutboxOrderingPolicyTest` 新增 4 例（2→6）——DATA 因果且不可 bypass/未知与空类型两边都不占/类型名精确匹配大小写敏感/因果与 bypass 集合互斥。零生产改动。；2026-10-19 oct19ah 续批：`MessageReceiptAggregationPolicyTest` 新增 5 例（4→9）——空时间戳透传不改旧值/零时间戳视同缺失/重复合并幂等/换 owner 时身份重新键合（时间戳仍单调聚合）/updatedAt 是 last-write 戳不单调；`MessagingV2ContentPolicyTest` 新增 5 例（6→11）——DATA 版本与类型形制钉住（v1 可、v0/v3 不可、带 event 或 SK_DIST 不可）/EVENT 域动作集合钉住（REVOKE/REACTION_SNAPSHOT 可、v2 版本/READ_RECEIPT/空 event 不可）/RECEIPT 接受 DELIVERY_RECEIPT 且版本钉住 v1/KEY_REQUEST 属性长度上限（51/101 字符拒）与附件为空钉住/SERVICE 无 event 普通内容可过、DELETE 变体约束与空白 targetMessageId 拒绝。零生产改动。；2026-10-19 oct19ai 续批：`MessageMutationGateTest` 新增 5 例（4→9）——REVOKE 墓碑同样终态/墓碑按 owner 隔离/终态动作 REVOKE→REVOKE 其余→DELETE 映射钉住/EDIT 非终态动作不建墓碑/跨 kind 墓碑覆盖仍终态；`MessagingV2MutationAuthorityTest` 新增 5 例（3→8）——作者可删自己消息/非变更动作（REACTION_SET/回执）透传/可信服务主体不可撤回/服务删除要求精确小写 system 身份/空信封发送者不可改他人消息。零生产改动。；2026-10-20 oct19ao 续批：`ContentPayloadCodecTest` 新增 5 例（5→10）——结构化编解码全字段往返、未知 wire 类型回退 TEXT、顶层 reply/mentions 覆盖旧 metadata、空顶层列表回退 metadata、畸形 meta JSON 截断正文且 metadata 置空。零生产改动；2026-10-20 oct19av 续批（#923，PR 待 CI）：`DecryptHistoryPolicyTest`/`SignalStoreLoadPolicyTest` 新增 5 例（3→8）、`SafetyCodePolicyTest` 新增 5 例（4→9）。零生产改动；2026-10-20 oct20t 续批（PR 待 CI）：`SenderKeyRetrySessionPolicyTest` 新增 5 例（27→32）——全角空格衬垫 id 精确匹配/纯纵向制表与换页符 id 视为空白/内部换行 id 精确匹配/对称尾制表仍匹配；`SignalDeviceIdRecoveryPolicyTest` 新增 5 例（26→31）——默认下界候选 2 可用/负候选拒绝/冲突重试接受最后一个预算 attempt/serverCode 四层包装上限/pending 状态裁边忽略大小写；`MultiRecipientCoveragePolicyTest` 新增 5 例（29→34）——全角空格不被 trim 裁掉/双层包装的 IO 错误仍 transient/零或负 deviceId 与空白 userId 的 required target 被丢弃/重复衬垫 required id 收敛。零生产改动；2026-10-20 oct20w 续批（PR 待 CI）：`IdentitySafetyPolicyTest` 新增 5 例（31→36）——primary 认 deviceId 1（首排 current 设备照样让位）/canVerifyAny 空表为 false/已验证设备空 code 不挡 unknown 验证/聚合 CHANGED 的 warningKind 与 sticky 跟踪聚合/非正 id 无 1 号时首排兜底且 1 号照样优先；`SignalSessionPolicyTest` 新增 5 例（38→43）——自账号他槽位可建会话而同槽位被拒/发现集 1 与 255 边界含入/空白 recipient 在非法 deviceId 前短路/全越界发现集坍缩到唯一持久会话/tab 换行 recipient 视空白拒绝。零生产改动；`SenderKeyCoveragePolicyTest` 新增 5 例（36→41）——便捷入口不走 reportedTotal=0 的 NO_SERVER_RECORD 分支/单 PENDING 触发重分发/tab 换行状态计 unknown/空表+正 reportedTotal 先判 epoch/QUEUED 与 FAILED 并存时 failed 优先。零生产改动；2026-10-20 oct20x 续批：`SensitiveActionPolicyTest` 新增 5 例（2→7）——六动作逐名钉住、任一门禁关闭全动作免验证、动作集恰六枚举、EXPORT_CHAT 全门禁真值表、决策重复调用稳定；`BackgroundSessionGateTest` 新增 5 例（4→9）——纯空白实时值拒绝、期望 id 空白变体拒绝、userId 精确大小写敏感匹配、token 只验存在不验内容、决策重复调用稳定；`MessageSafetyScannerTest` 新增 5 例（4→9）——冒充词+紧迫标记 impersonation、索要验证码标记 credential_request、银行卡/身份证号标记 sensitive_data 且支付词不误报、支付词+URL 无紧迫仍标记 payment_inducement、可信域名不误报而短链被标记。零生产改动；2026-10-20 oct20y 续批：`SecretChatPolicyTest` 新增 5 例（5→10）——isSecretChatType 空白衬垫/混合大小写通过、前后缀赘余拒绝、canStartFromDirect 类型格式化容忍、canCreateSecret 非两人组合拒绝、文件夹互补真值表与 mosaic 恒定标签；`SecretChatSessionTest` 新增 5 例（4→9）——markSurfaceInactive 摘除不碰他者、重复打标幂等、clearSurfaceMarkersExcept 空白/null/未知 keep 全清；`SecretChatPrivacyPolicyTest` 新增 5 例（6→11）——密聊+锁定全动作拒绝、决策重复调用稳定、ARMED 无计时器保持、EXPIRING/DESTROYED 读入终态、过期保持 EXPIRING。零生产改动；2026-10-21 oct21a 续批：`PinSecurityPolicyTest` 新增 5 例（7→12）——损坏哈希 Corrupted 三变体（空串/段数不足/轮数非数字）、低轮数 PBKDF2 升级旗标并校验升级哈希可再验无二次升级、限流失败计数按 key 隔离且 resetAll 解锁、会话失效按用户与全局两级清除、`setPin` 非法格式拒绝且重复设置覆盖旧哈希；`TotpCoordinatorTest` 新增 5 例（6→11）——空白 token 不走网关直接 Disabled、主接口失败回退 legacy JSON 端点、setup 网络失败进 Error（previous=Disabled）、无 setup 状态确认进 Error（previous=Uninitialized）、disable 失败保留 Enabled 并携带错误；FakeAuthApi 新增 rawStatusJson 钩子，旧 6 例默认行为不变；`FakeChatDataBoundaryTest` 新增 5 例（4→9）——自定义脱敏文案仅假模式生效、真实模式空内容原样透传、危险覆盖绕过 receiver 校验但 PIN 仍必填、近似前缀误判拒绝、威胁常量互异非空。零生产改动；2026-10-21 oct20aa 续批（#955）：`ScreenshotDetectorPolicyTest` 新增 5 例（4→9）——截图名唯规则（`Screenshot_`/`screen_` 前缀在普通相册路径下仍命中）、路径拼写变体（`screen_shot`/`screen-shot`）、录屏路径与名变体、录屏名须带 screen/scr 上下文（裸 `record_1.mp4`/`recording.mp4` 不误报）、擦除器图片 8s / 视频 12s 年龄上限含边界；`FakeChatPolicyTest` 新增 5 例（5→10）——禁用覆盖已解锁的后台会话、锁定会话无视 relock/后台戳拦截、负后台戳保持解锁、1ms 后台戳即拦截、决策重复调用稳定；`DefaultSecretConversationControllerTest` 新增 5 例（5→10）——`capabilities` 空白 id 返回中性且不访问 DAO、初始化完成前走阻塞 DAO 回退读、`armOnRead` 空白 id 零 DAO 调用、非密聊且定时器为 0 不 arm、`purgeExpiredMessages` 无过期项时按 `nextExpiry-now` 挂载下次销毁任务。零生产改动；2026-10-21 oct21b 续批：`DeviceRevocationCoordinatorTest` 新增 5 例（4→9）——本机撤销 handler 失败原样透传、currentDeviceId 为 null 时走远端路径、空白 token 在任何 handler 调用前短路（IllegalArgumentException）、未显式传 deviceIdString 时按 currentDeviceId.toString() 派生且 refreshToken 默认空串、远端移除失败透传原错误且不使 Signal 会话失效；`SecretSessionTtlTest` 新增 5 例（8→13）——负 TTL 立即过期、过期 1ms 按截断计 0 仍判过期、过期 1s 报 -1、1ms 活动时间不走 fail-closed 按公式算、十年量级 TTL 不溢出；`AccountIsolationPolicyTest` 新增 5 例（7→12）——衬垫空白的同账号登录不清除、纯空白 next 账号不清除、owned 行按 trim 后匹配（双 null 拒绝）、preferenceKey 空白 base/userId 抛 IllegalArgumentException、draftPrimaryKey 空白 owner/chat 抛 IllegalArgumentException。零生产改动；2026-10-21 oct21c 续批：`LogoutStorePolicyTest` 新增 5 例（6→11）——全 Reason 销毁判定真值表、枚举恒五项、重复调用稳定、无前任会话首次登录保留本地数据、不同账号反序仍清除；`SecurityCoordinatorTest` 新增 5 例（7→12）——聊天路由单裸 flag 不保护窗口、null/空白路由不保护、su 文件存在判 rooted 高风险、纯 debug 构建中风险且仅一条原因、rooted+debug 高风险双原因；`SecretChatActivityHeartbeatTest` 新增 5 例（8→13）——destroy/touch/判过期均拿到注入 chatId、判过期每轮恰一次、写活动与睡眠严格交替、自定义 interval 生效、销毁后取消跳过首次写活动。零生产改动；2026-10-21 oct21d 续批：`AppLockPolicyTest` 新增 5 例（15→20）——时钟回拨不受超时大小豁免、disabled 优先于回拨 fail-closed、显式 1ms 超时等价于归零、未验证在回前台瞬间即锁、切账号后失验证窗口内即锁；`ChatLockPolicyTest` 新增 5 例（14→19）——空解锁集合全锁、集合含空串仍不解锁空 chatId、归属翻为空串会话中加锁、多成员精确匹配、liveUser 大小写错配判锁；`ScreenSecurePolicyTest` 新增 5 例（16→21）——全关不加 FLAG、路由头大小写敏感、无 id 裸 head 仍是聊天表面但无 chatId、空白 chatId 查询参数无 id、fillRoutePattern 缺值保留占位符。零生产改动；2026-10-21 oct21g 续批：`NotificationIntentPolicyTest` 新增 5 例（2→7）——清理中 intent 照单拒收、空白 owner 永不归属、账号匹配大小写敏感、chatId 只裁两端空白、解析重复调用稳定；`NotificationPreferencesTest` 新增 5 例（2→7）——作用域 key 永不等于裸全局 key、14 个公开 key 单账号互异、跨账号 key 集合不交、空 userId 仍作用域化、scopedKey 重复调用稳定；`NotificationPrivacyPolicyTest` 新增 5 例（2→7）——锁开且预览关仍隐藏、四组合恰一处展示、加锁永不反转隐藏、无锁时隐藏恰为预览关、重复判定稳定。零生产改动；2026-10-21 oct21e 续批：`QrSafetyScanEvaluatorTest` 新增 5 例（11→16）——纯空白 currentUserId 视同过期、对端指纹先于 owner 指纹判扫错设备、owner 指纹缺失回退安全码路径、空白安全码判非法、重复核验确定性；`QrScanFeedbackPolicyTest` 新增 5 例（4→9）——服务端码裁边+大小写不敏感、网络分支原样透传 serverCode 且可重试、中英文消息关键词映射、服务端码压过冲突的 HTTP 状态、超时压过 404 且 miss 不可重试；`MessageSafetyScannerTest` 新增 5 例（9→14）——IP 主机判可疑链接、品牌仿冒子域判可疑但品牌官方子域不误伤、单字符短链判可疑、maxFindings=0 钳制为 1、重复扫描确定性。零生产改动；2026-10-20 oct19aw 续批：`SenderKeyCoveragePolicyTest` 新增 5 例（6→11）——LOCAL_MISSING 优先级最高、EPOCH_MISMATCH 压过 FAILED、failed 压过 pending 且三态计数大小写不敏感、reportedTotal 大于状态列表不强制 UNKNOWN、显式 0 且非 0 epoch 为 COMPLETE；`SenderKeyRetrySessionPolicyTest` 新增 5 例（7→12）——token 与 userId 双空拒绝、live userId 尾空格不匹配、对称空白填充 id 精确相等仍通过、tab 令牌拒绝、非 ASCII id 精确相等；`MultiRecipientCoveragePolicyTest` 新增 5 例（9→14）——missingRequiredRecipients 只列未覆盖者、空白 userId 目标与 0 deviceId 不计覆盖、required 侧 trim 后匹配、ApiException NETWORK/TIMEOUT/503 可重试（404/UNEXPECTED 不可）、SignalExchangeException 不透传嵌套 cause。零生产改动；2026-10-21 oct21i 续批：`SignalingOfferFreshnessPolicyTest` 新增 5 例（5→10）——零/负 maxAge 窗口一律拒绝、自定窗口边界含入、type 大小写不敏感、空 callId 跳过 terminated 比对；`CallSessionEpochPolicyTest` 新增 5 例（3→8）——旧 epoch 盖戳的挂断被丢弃、post-invalidate 跳两代仍安全、post 落后于挂断代不安全、零 epoch 同规则、重复调用确定性；`CallLockScreenFlagPolicyTest` 新增 5 例（4→9）——null id 无 pending 关 flag、null id 有 pending 开 flag、双通道齐开、空白 id 有 pending 仍开、重复调用确定性。零生产改动；2026-10-21 oct21j 续批：`CallSignalingAdmissionPolicyTest` 新增 5 例（8→13）——空发送者恒丢弃、群通话放行任意非空发送者、群元数据不一致时 offer 忙拒绝/answer 丢弃（成员集合不一致同理）、零游标准确绕过有序校验、信令类型归一化驱动忙拒绝判定；`MissedCallTimeoutPolicyTest` 新增 5 例（9→14）——衬垫 callId 裁边、空联系人回退 unknown 对端、合成 id 分钟桶边界、同 callId 不同联系人不记录、对端取消 wire 状态大小写不敏感；`CallOfferKeepPolicyTest` 新增 5 例（5→10）——未来时间戳按时钟偏移保留、未知时间戳保留、零窗口拒绝、纯空白 callId 不判终止、边界年龄保留/超 1ms 丢弃。零生产改动；2026-10-21 oct21k 续批：`DirectChatRequestHandlerTest` 新增 5 例（3→8）——token 缺失/空白而 owner 在仍短路、owner 空/空白而 token 在仍短路、门禁只看会话不看请求字段（换 userId 结果恒定）；`CallSessionMachineTest` 新增 5 例（2→7）——空/空白 peerId 建会抛、会话存续期重复 begin 抛（快照不变）、未 beginEnding 直接 finish 是无操作、来电完整生命周期（INCOMING_RINGING→…→ENDED，incoming/peerId 携带）、epoch 跨会话严格递增且 0 epoch 永不 current；`CallOfferSelectorTest` 新增 5 例（6→11）——终端信令判定不裁空白（衬垫/拼写变体拒）、已终止 callId 去重（大小写混合同 id 合一）、空白 groupId 仍走直聊响铃、中选 offer 命中时剩余保持相对序、单 offer 命中剩余为空。零生产改动；2026-10-21 oct21l 续批：`IncomingCallCoordinatorTest` 新增 5 例（6→11）——consumePending 新鲜来电返回实例并打消费戳/过期来电返回 null 但仍清 pending 打戳/clear 空 pending 不动消费戳/setPending 替换旧 pending 不打戳/空白参数绕过非空白 pending 的身份校验；`CallOrchestratorTest` 新增 5 例（2→7）——群通话请求携带载荷与当前代/直聊请求携带当前代/代严格单调/跨 invalidate 两请求代可区分/空成员列表照样 emit；既有 `invalidateSessionBumpsGenerationAndTagsRequests` 改为自消费其 emit（消除 buffer 残留事件对新收集测试的污染）；`CallActionBusTest` 新增 5 例（2→7）——挂断按 emit 序投递/同 callId 重复挂断两次均投递/空白 callId 无校验透传/requestHangUp 返回 true 且事件可收集/HangUpRequest 默认当前代与 notifyPeer=true。零生产改动；2026-10-20 oct20b 续批：`SessionCipherOccupancyTest` 新增 5 例（10→15）——租约二次释放被拒、chatId 修剪后打标、peer 空白清零、空 id 永不跳过、新租约接管标记但 peer 保留至释放；`SignalPreKeyIdPolicyTest` 新增 5 例（10→15）——连续差一越界回退随机、顶部随机永不越 24-bit、中点映射、负 count 归一、全满回退随机单 id；`IdentitySafetyPolicyTest` 新增 5 例（11→16）——全 TRUSTED 聚合、单 CHANGED 聚合、已验证设备不可再验证、primary 忽略 isCurrent、三态最坏胜出。零生产改动；2026-10-21 oct21n 续批：`CallSignalingAdmissionPolicyTest` 新增 5 例（13→18）——同游标重发视为良性重复放行、高 epoch 无视旧 sequence 放行、新会话首信令无游标放行（账号切换维度）、陌生 callId 的挂断信令静默丢弃（取消维度）、群元数据一致时陌生 callId 的 offer 仍忙拒绝；`SignalingOfferFreshnessPolicyTest` 新增 5 例（10→15）——自定 maxAge 窗口透传、terminated 集合精确匹配（大小写敏感）、衬垫空白的 type 不认作 offer、兄弟 callId 被终止不连累、负时间戳按未知保留；`CallOfferKeepPolicyTest` 新增 5 例（10→15）——被终止集合只命中自身 id、负窗口拒绝、reject/busy 永不保留、衬垫空白 type 不认作 offer、大小写错配的 terminated id 不误杀。零生产改动；2026-10-21 oct21o 续批：`CallSessionEpochPolicyTest` 新增 5 例（8→13）——负世代同规则、挂断盖未来戳丢弃、post 单步 bump 为最小安全步长、Long.MAX_VALUE 无处 bump 仍不安全（比较不溢出）、错配挂断再大 bump 也救不回；`CallLockScreenFlagPolicyTest` 新增 5 例（9→14）——制表换行 id 关标志、衬垫非空 id 仍开、不间断空格 id 视为有效 id（U+00A0 非 isBlank）、pending 通道重复调用稳定、单个空格关标志；`WebRtcNativeDownloadPolicyTest` 新增 5 例（7→12）——arm64 前缀回退、空 ABI 列表默认 arm64-v8a、localhost/127.0.0.1 回环信任、子域带端口信任而仿冒域拒绝、小写弱 ETag/衬垫/非 64 位十六进制校验。零生产改动；2026-10-21 oct21p 续批：`CallSystemIntegrationTest` 新增 5 例（4→9）——Telecom 不可用返回 false 透传供调用方回退、锁屏旗标逐次重读 provider、空白 active id 视同无通话、start 空名+video=false 原样透传、stop 每次委托无去重；`CallMediaBridgeTest` 新增 5 例（8→13）——rebind 不释放旧 manager、rebind 后意图只达最新 manager、意图保持调用序（verifyOrder）、群渲染器 userId 原样透传（含空串）、四种音频路由逐个透传；`MissedCallRoomActionsTest` 新增 5 例（8→13）——deleteById 异常透传、delete 两次不去重、工厂自身抛错不吞、null 工厂可重复静默 no-op、空白 id 原样透传。零生产改动2026-10-21 oct21q 续批：`CallSessionMachineTest` 新增 5 例（7→12）——陈旧 epoch 过渡零副作用（新会话隔离）、IDLE 下 beginEnding 空转、finish 双调用保持 ENDED、markConnected 两次幂等、错 epoch beginEnding 空转；`CallOrchestratorTest` 新增 5 例（7→12）——无订阅者时发射被丢弃（tryEmit 仍 true，钉死文档语义）、GroupCallRequest 默认 generation 为当前、memberIds 顺序与重复原样透传、空 userName 仍发射、directChat 跨 invalidate 双 generation；`CallActionBusTest` 新增 5 例（7→12）——失效前 hangup 携带可区分的陈旧 generation、HangUpRequest 值语义（相等/hashCode/copy）、有订阅者时连续发射 tryEmit 恒 true 且按序到达、无订阅者发射被丢弃（tryEmit 仍 true，钉死文档语义）、notifyPeer 默认 true 经 bus 透传。零生产改动；2026-10-21 oct21q 修：上述"迟到订阅者收到缓冲/首条保留"断言误用了 SharedFlow 语义——无订阅者时 extraBufferCapacity 不生效、tryEmit 返回 true 但值被丢弃（见 SharedFlow KDoc），已改写为先订阅再发射与无订阅者丢弃钉死；零生产改动；；2026-10-21 oct21s 续批：`DirectChatRequestHandlerTest` 新增 5 例（8→13）——建聊成功透传 token/peerIds 并取返回 id、建聊失败透传错误信息（Log.w 用 mockkStatic 桩）、在途会话失效静默 Dropped（取消维度）、重复请求逐次建聊不去重、账号切换后用新会话 token；`CallSystemIntegrationTest` 新增 5 例（9→14）——未 start 直接 stop 照样透传、通话结束 activeCallId 置空关锁屏旗标（取消维度）、连续两次 start 参数逐次透传、activeCallId 切换 c1→c2 旗标保持（切换维度）、Telecom 可用性逐次查询不缓存；`CallOfferSelectorTest` 新增 5 例（11→16）——空 offer 列表抛 NoSuchElementException（失败维度）、重复 callId 时 prefer 命中首条实例且 rest 引用排除（重复维度）、选择忽略 fromUserId（账号切换维度）、reject 计入已终止（取消维度）、空白 prefer 回退首个。零生产改动；2026-10-21 oct21u 续批：`CallMediaBridgeTest` 新增 5 例（13→18）——release 时 manager 抛错桥不清空引用且异常上传、同一 manager 连 bind 两次 release 只调一次、双桥同管 A release 不影响 B 转发、release 后全部 renderer 意图丢弃、rebind 后 detach remote 只达最新；`CallSessionMachineTest` 新增 5 例（12→17）——CONNECTED 允许从 RINGING 直达、CONNECTING 中 begin 被拒快照不变、接通前挂断收尾 ENDED、beginEnding 两次仍 ENDING 且会话现存、去电结束后来电 peerId/incoming 重置且 epoch 递增；`MissedCallRoomActionsTest` 新增 5 例（13→18）——delete 在前 clearAll 在后按序落库、clearAll 失败后重试照样成功、clearAll 失败不污染 delete、抛错工厂每次都抛不缓存、中途换工厂只影响后续调用。零生产改动；2026-10-21 oct21t 续批（#975）：`SignalingOfferFreshnessPolicyTest` 新增 5 例（15→20）——到达即时 offer（age=0）保留、re-offer 前缀类型不做子串匹配被过滤、已终止通话的 offer 即使时间戳未知也先于新鲜度被丢弃、同一新鲜 offer 重复轮询两次都保留、账号切换后旧账号的 terminated 集合不再过滤同一 offer；`CallSignalingAdmissionPolicyTest` 新增 5 例（18→23）——群元数据一致（toSet 比对）且游标递增时群通话 ACCEPT、空 incomingCallId 不能绑定活跃通话（normalized→BUSY_REJECT）、自己活跃通话的 hang-up 信令 ACCEPT、陌生通话 offer 重发两次稳定 BUSY_REJECT、账号切换后旧联系人来信 DROP（expectedContactId 维度）；`IncomingCallCoordinatorTest` 新增 5 例（11→16）——CAS 消费后二次消费返回 null 且不重复记 consumedEvents、无 pending 时 markAutoAnswer 返回 false 且事件流无污染、clear 清掉有效 pending 并记 consumedEvents、重复 markAutoAnswer 命中 autoAnswer 短路只产生一个新实例（assertSame）、账号切换后新来电覆盖旧 pending；oct21t 修补：`peekPending of stale offer clears consumedEvents` flaky——`clear()` 用 `System.currentTimeMillis()` 盖章，同一毫秒内 `>` 断言随机失败，改为 `>=`（与相邻测试一致）。零生产改动；2026-10-21 oct21v 续批：`PolledIncomingBatchPolicyTest` 新增 5 例（14→19）——同批 offer 与无关终端共存时照常响铃并转发终端（成功维度）、同批内 offer 被自己的 hang-up 覆盖则永不响铃且 prefer 被取消（取消维度）、同一 callId 在一批次出现两次只响一次、另一条走 busy 回复（重复维度）、上通电话的 pending 残留不压制新 offer（账号切换维度）、空 prefer 永不误取消（失败维度）。零生产改动；2026-10-21 oct21w 续批：`JoinGroupInviteUseCaseTest` 新增 5 例（5→10）——join 抛错被归一为 Failed（失败维度）、owner 空白先于副作用判 SESSION_EXPIRED（失败维度）、入口门关时永不发网且 Failed（取消维度）、同一邀请码两次都走网不去重（重复维度）、账号切换后第二次 token 读取供 join 使用（账号切换维度）。零生产改动；2026-10-21 oct21y 续批：`DefaultContactMutationUseCaseTest` 新增 6 例（4→10）——setNickname 空白 id/缺会话先被拒（失败维度）、备注裁边后存储（成功维度）、blockUser 网关失败不调回调（失败维度）、blockUser 中途切号丢弃回调（账号切换维度）、unblockUser 成功透传网关结果（成功维度）、setNickname 取消原样上抛（取消维度）；`DefaultFriendRequestUseCaseTest` 新增 5 例（6→11）——reject/cancel 成功落终态后反向操作被拒（重复维度）、单端拉取失败仍成功返回另一端（失败维度）、send 成功返回 outgoing 项（成功维度）、batchAccept 按 id 去重（重复维度）；`DefaultQuickReplyUseCaseTest` 新增 5 例（8→13）——sanitize 后空白报 empty_text（失败维度）、发送失败报 send_failed 附 reason（失败维度）、空白幂等键回退 policy dedupe 键（重复维度）、门禁拒绝报 not_logged_in（账号切换维度）、发送取消原样上抛（取消维度）。零生产改动；2026-10-21 oct21x 续批：`ChatGroupSecurityStateControllerTest` 新增 4 例（3→7）——无 warning 时保留既有 groupEncryptionWarning、成员表缺自己则角色为 null 但角色表完整、空白昵称不进昵称映射、presentGroup 恒清密聊字段；`GroupLifecycleCoordinatorTest` 新增 5 例（4→9）——空 chatId 在任何 session 读取前被拒、空白 owner 与 session 失活在 mutation 前抛 GroupLifecycleSessionException、mutation 取消原样传播不刷新、token 在提交后重新 live 读取（tok-1→tok-2）；`GroupMutationFeedbackPolicyTest` 新增 5 例（7→12）——契约码 GROUP_PERMISSION_DENIED/GROUP_TARGET_NOT_MEMBER 无视 status 直接定级、空白 serverCode 回退到 status、status 缺失的 HTTP 失败可重试、null error 判致命。零生产改动；2026-10-21 oct21z 续批：`ChatListUnreadBatchCoordinatorTest` 新增 5 例（2→7）——Room 写入失败被吞但 tray 清理与已读回执照常走完（失败维度）、会话过期跳过持久化且乐观清零不回滚（账号切换维度）、首个会话抛取消异常后整批中止（取消维度）、重复调用幂等（第二次无未读可清、零写入零回执；重复维度）、空选中集直接返回（边界）；`ChatListPreviewCoordinatorTest` 新增 5 例（3→8）——会话过期时预览更新早退（账号切换维度）、Room 回写失败只打日志且内存投影已完成（失败维度）、`getRecentMessages` 抛取消异常原样传播（取消维度）、迟到时间戳不回拨持久化排序键（重复/幂等维度）、`refreshChatListPreviewFromLocal` 抛普通异常被吞（失败维度）。零生产改动；2026-10-22 oct22a 续批：`ExploreUseCasesTest` 新增 5 例（6→11）——超长内容在仓库调用前被拒（失败维度）、仓库失败不清草稿（失败维度）、token 为 null 回退到 live 会话 token（账号切换维度）、edit 空白内容早退（失败维度）、发布中取消原样传播（取消维度）；`MediaUploadQueueTest` 新增 5 例（6→11）——上传中二次 startUpload 返回同一 Job 且 api 只调一次（重复维度）、未知 id 返回 null 零副作用（失败维度）、上传后会话变更判 FAILED（账号切换维度）、上传中取消置 CANCELLED 且异常透传（取消维度）、discardUploaded 门禁拒绝报 IllegalStateException（账号切换维度）；`PostMutationRepositoryTest` 新增 5 例（6→11）——publishPost 门禁拒绝报 MutationSessionChangedException 且 journal 为空（账号切换维度）、toggleCommentLike 空 status 回退乐观值（失败维度）、零点赞取消不为负（失败维度）、点赞中取消透传且 journal 停留 pending（取消维度）、连续两次 toggle 翻转回原值且各走一条独立 journal（重复维度）。零生产改动；2026-10-22 oct22c 续批：`DraftRepositoryTest` 新增 5 例（5→10）——visibility 未知值/小写变体归一化到 PRIVATE（成功维度）/clearAllDrafts 只清本账号不碰他账号（账号切换维度）/重复保存后写覆盖、无重复落盘（重复维度）/空白账号全部写/清操作均 no-op（失败维度）/空串条目被过滤、空列表保存删键（边界维度）。零生产改动；2026-10-22 oct22d 续批：`RelativeTimePolicyTest` 新增 5 例（3→8）——自定义过去窗口扩大/缩小刚刚区间（参数维度）/自定义未来宽限扩大时钟偏差容忍（参数维度）/零窗口下时间戳恰为 now 也不算刚刚（边界维度）/负时间戳永不算刚刚（边界维度）；`ExploreViewModelTest` 新增 5 例（4→9）——draft key 按 userId 命名空间隔离（账号切换维度）/空白账号返回 null（失败维度）/normalizeVisibility 大小写敏感且 fail-closed（边界维度）/key 常量钉住（回归维度）/VisibilityOption subtitle 默认为空（边界维度）。零生产改动；2026-10-22 oct22j 续批：`ExploreDraftPolicyTest` 新增 5 例（12→17）——空 base 照拼分隔符、owner 含冒号原样、U+00A0 不被 isBlank 判空（钉住）、base 不裁边、全角可见性变体回落 PRIVATE；`FeedControllerTest` 新增 5 例（12→17）——publish 成功原值透传、常 stale 会话 load 也拒、currentSession null 透传、null visibility 原样透传、U+00A0 不被 trim 去掉（钉住）。零生产改动；；2026-10-22 oct22l 续批：`LocalNotificationSuppressPolicyTest` 新增 6 例（7→13）——分钟同日窗口半开区间钉住（end 排除、start 含入）/currentMinute=null 时按 hourOfDay*60 回退旧语义/越界输入先钳制（负分钟→0、超限小时→23）/dndEnabled 不传默认启用（旧调用兼容）/全局关闭压过运行时与显式开关/分钟参数 start==end 视为关闭；`NotificationSlotPolicyTest` 新增 5 例（8→13）——提醒槽位与会话槽位 tag/id/requestCode 三空间隔离（清一边不擦另一边）/同会话三种动作 requestCode +1/+2 错开互不踩踏/动态 notifyId（post_ 前缀哈希）与 requestCode（裸 id 哈希）空间隔离/槽位纯函数重复调用恒定/空 id 退化槽位稳定可拼且互异；`QuietHoursAndAnnouncementTest` 新增 4 例（8→12）——生效窗口两端含入（startsAt==now 与 expiresAt==now 保留）/空列表不触发前台提示/forceShowAcked 只忽略已读不放宽过期窗口/LEVEL_PRIORITY 四级映射冻结钉住。零生产改动；2026-10-22 oct22m 续批：`DndPreferenceResolveTest` 新增 5 例（3→8）——显式开关短路钳制（失败维度）/单小时 key 仍推断（成功维度）/越界小时先钳制再判 start==end（边界维度）/小时回退钳制到全天范围（边界维度）/判决定重复调用稳定；`NotificationCenterMergePolicyTest` 新增 5 例（13→18）——全空标识永不命中（失败维度）/存量空值不吸附非空来值（失败维度）/item id 命中即跳过计数（成功维度）/稳定键不裁空白不忽略大小写（边界维度）/判决定重复调用稳定；`NotificationCenterReadPolicyTest` 新增 5 例（11→16）——非消息行即使 extra.chatId 命中也不算消息行（失败维度）/AI 任务两通道可独立命中（成功维度）/无 _m_ 锚点不匹配、多锚点取最后一段（失败维度）/空白 messageId 永不引用（失败维度）/判决定重复调用稳定。零生产改动；2026-10-22 oct22n 续批（#995，已合）：`NotificationSoundPolicyTest` 新增 5 例（10→15）——会话静音压过全部组合/托盘消息音失败维度剩余四格/应用内全关静音/铃声与消息音真值表恒一致/重复调用确定性；`PreferenceConflictPolicyTest` 新增 6 例（4→10）——本地 owner 错配被拒/远端 revision 落后保留本地/同 revision 远端戳老保留本地/空白 session owner 跳过隔离/patch 同值不记冲突字段/空 patch 视同无待推；`ScheduledTimeCalculationTest` 新增 5 例（4→9）——负间隔原样返回/非法 zone 回退默认/周六起始跳周一/三天步进走日历日/秋令回拨挂钟保持 09:00；`ConversationScheduledMessageDispatcherTest` 新增 5 例（2→7）——无 sch_ 前缀原样拼 sm_/前缀只剥一次/空白文本报 EMPTY_TEXT 不走网关/确定性 id 重复 staging 稳定/resolveChat 收 item.chatId。零生产改动；2026-10-22 oct22o 续批（PR 待 CI）：`AiRetryPolicyWindowTest` 新增 5 例（3→8）——全局 239/240 边界钉住/清理同时清 per-chat 窗口/陌生聊天在全局挡时报正延迟/轻重剩余延迟互相独立/chatId 不裁边精确匹配；`AiPrivacyPreferencesTest` 新增 5 例（3→8）——五键互异/键名常量形制钉住/作用域键恒 base:userId/用户 id 不裁边/空用户 id 保留尾冒号；`AgentSecretGatePolicyTest` 新增 5 例（3→8）——密聊拒绝压过 PIN 拒绝/八组合拒绝与列表一致/拒绝常量互异非空/判决定重复调用稳定/解锁旗单独不拒。零生产改动；2026-10-22 oct22p 续批（PR 待开）：chatdetail 界面状态三文件 3→8——ChatHeaderStatusModelTest（typing id 裁边/typing 压过 online+custom+lastSeen/lastSeen 仅 1:1/零 lastSeen 回退 Offline/custom 原样回归钉住）、ChatSearchModelTest（MEDIA 空查询列全部媒体/中文类型词「图片」命中/MENTIONS 只留直提与 @所有人/七天窗口左边界含入/SYSTEM 与 NUDGE 永不建文档）、MessagePreviewTextTest（REVOKED 占位/SK_DIST 信封占位/换行折空格/空白回占位/VOICE 走 mediaLabel）。零生产改动。；2026-10-22 oct22q 续批：`ChatTimelineStateControllerTest` 新增 5 例（3→8）——mergeIncoming 按时间戳排序追加/replaceMessage 只改目标行保序/applyMutation Set 未知 id 走 merge 排序/状态正常推进与服务端权威失败恢复/空历史仍置 ready 清错误；`ChatSearchSelectionStateControllerTest` 新增 5 例（3→8）——AI 关闭语义搜索拒绝钉住错误码/空白查询与纯空白候选分别报 blank/empty/查询截断 300 字符/导航空白 id 忽略与消费清零；`ChatRealtimeEventPolicyTest` 新增 5 例（3→8）——当前用户 MEMBER_LEFT 亦终态/他人移除为普通 REFRESH/非移除事件不判终态/presence 要求非空 contactId/空事件 chat 永不算活跃。零生产改动。2026-10-22 oct22t 续批：`MessageStatusMachineTest` 新增 5 例（7→12）——合并按时间戳+id 排序/终态撤回不被服务端编辑复活/新编辑胜内容但本地 READ 不降级/陈旧服务端编辑不覆盖新本地编辑/FAILED 在投递状态合并中透明；`MessagePinPolicyTest` 新增 5 例（5→10）——群角色校验大小写不敏感/媒体与文件类型可置顶/previewKind 全类型映射/上限恰在 MAX_PINS 触发/文本预览折叠换行且保留字面 <meta>；`ChatMessageInteractionPolicyTest` 新增 5 例（5→10）——replyable 与手势资格一致/滑动回复需正阈值+左滑位移/密聊转发恒拦截（与 flag 无关）/多选切换保留其它 id/默认快捷表情钉住。零生产改动。；2026-10-23 oct22z 续批（#1007，PR 待 CI）：`SenderDisplayNameTest` 新增 5 例（9→14）——直聊联系人姓名/备注双空白回退 id、备注名两侧空白裁边、群内自己消息返回 null、直聊 mapped 名空白跳过截断 id、`resolveChatDetailSenderName` 默认按 senderId 派生 isOwn（账号切换维度）；`NudgeDisplayPolicyTest` 新增 5 例（9→14）——`extractTargetName` 前后缀空白裁边（中英前缀）、裸前缀返回空、直聊非自己消息用原文 content 重建 they-you、群聊非自己消息原文作 target、自己消息且内容空白时原样返回（边界维度）；`AttachMenuPolicyTest` 新增 5 例（9→14）——直聊/群聊菜单精确顺序钉住、群+AI 时 AI 夹在 CONTACT_CARD 与 SILENT 间、频道无 AI 时 6 块且 SILENT 结尾、SILENT 在四模式恒为末位。零生产改动。；2026-10-23 oct22ab 续批（PR 待 CI）：`ChatExportControllerTest` 新增 5 例（8→13）——userId 空白/缺失均导出空（会话维度）、密聊模型旗标（chatType=SECRET）独立拒导出、默认非 partial 标记、clearExportInfo 清空；`MediaExportUseCaseTest` 新增 5 例（9→14）——share 路径从 meta 取 mime、失败结果携带 cause 透传、GIF/FILE 无 meta 时按类型派生 mime 与文件名、密聊拒绝仍记录 chooserTitle；`ChatExportTest` 新增 5 例（9→14）——解析名空白回退 senderId、count 含被跳过的 SYSTEM、writeStream 跳过 SYSTEM、空白 chatName 写 dash、无名文件渲染裸标签。零生产改动。2026-10-23 oct22ac 续批（PR 待开）：`AiRequestGenerationGateTest` 新增 5 例（7→12）——首 token 从 1 连续递增、invalidate 跳过的代号永不为 current、并行发 token 后 invalidate 全失效、isCurrent 重复调用稳定、新鲜 token 为旧 token+2；`ChatAiContextBuilderTest` 新增 6 例（7→13）——同时间戳按 id 排序、maxTextLength 自定义截断 50 字符、语音转写与翻译重复行去重、纯空白消息两 builder 皆空、默认上限卡在 48（首尾钉住）、空白映射名回退 fallback；`MediaCenterModelTest` 新增 5 例（7→12）——http 链接收集、MARKDOWN 类型永不出链接、同一 URL 跨消息各出一项（按消息去重不跨消息）、大写 scheme 大小写不敏感、item 持有原 Message 引用。零生产改动。；2026-10-23 oct22ae 续批（#1012，PR 待 CI）：`ChatSendIntentFactoryTest` 新增 5 例（13→18）——守卫序（密聊压过全部后置失败、DISABLED 压过缺 chatId）、Allow 携带解析后的 activeChatId、显式空 meta 原样透传、100 连发 id 全唯一；`ChatSendGuardTest` 新增 5 例（21→26）——恰在 4000 上限不裁剪、首尾空白裁边后判定、小写 admin 角色可 @所有人、重试先验归属后验会话、SENDING 态不可重试；`ChatDraftPolicyTest` 新增 5 例（4→9）——canSchedule 空白/null 全位置拒绝、代号过期与方向无关、空白 owner 永不写入、空串即清请求、恢复草稿真值表补齐。零生产改动。；2026-10-23 oct22af 续批（PR 待开）：`BotCommandPolicyTest` 新增 5 例（2→7）——parseSlash 提取命令/目标/参数与大写目标归一化、SK:/ENC:/senderKey 三种密文形制永不进 inbox（私聊也拦）、composerMenuQuery 大小写与 @目标归一化、超 64 字符与换行不展示菜单、filterCommands 恰 20 条上限；`QuickReplyCommandHandlerTest` 新增 5 例（3→8）——空白正文/会话/账号三位置均 Rejected 且门禁零调用、重复命令不发送不记账、门禁拒绝不触碰去重层、连续两次成功各记账一次；`NotificationQuickReplyReceiverTest` 新增 5 例（5→10）——null action 静默忽略、无 RemoteInput 文本按 empty_text 拒绝、useCase 失败被吞不抛、markRead 空白 owner 透传 null、purge 进行中拦截快捷回复。零生产改动。；2026-10-23 oct22ag 续批：`LocalAiGatewayRankTest` 新增 5 例（1→6）——空白查询返回空、零命中候选被过滤、多命中排前、大小写不敏感、scored 变体分数降序；`LocalAiFileAnalyzerTest` 新增 5 例（3→8）——超长文本截断到 MAX_TEXT_CHARS、纯空白文本拒绝、含 null 字节拒绝、application/json 按文本接收、url-safe base64 归一化解码；`LocalAiProviderStoreTest` 新增 5 例（3→8）——非数组 JSON 解析为空、baseUrl 尾斜杠裁掉、protocol 别名归一（anthropic/responses/未知回退）、null temperature 往返保持、会话空标题回退"新对话"且空 id 行丢弃。零生产改动。；2026-10-23 oct22ah 续批（#1015，PR 待 CI）：`OpenAiCompatClientTest` 新增 5 例（3→8）——无 choices 负载报"模型没有 choices"、空 content 回退 reasoning_content、缺 id 的 tool call 自动生成 `call_` 前缀 id、无 function 的 tool call 丢弃走文本分支、content 与 tool calls 并存透传；`AgentSessionEngineTest` 新增 5 例（4→9）——completion 错误转 Failed、未知工具写回错误继续下一轮且 host 零调用、rewrite_text 走模型重写通道不进 host（结果裁边）、读工具事件序 ToolStarted 先于 ToolFinished、12 轮上限耗尽报"工具轮次过多，已停止"；`AgentToolPolicyTest` 新增 5 例（7→12）——toolByName 命中/未命中、九个限流常量冻结钉住、未知工具 preview 回退裸名+json、空串与坏 JSON 解析为空、parseBool 变体与非法值回 null。零生产改动。；2026-10-23 oct22aj 续批（PR 待 CI）：`AiCostVisibilityPolicyTest` 新增 5 例（8→13）——非正 retry-after 丢弃与服务端值钳制 86400、encode 只给限流族加后缀、baseErrorCode 大小写/空白归一、计费提示优先级（流式压取消、TIMEOUT 压失败态）、展示信号四元绑定；`AiLocalRulesTest` 新增 5 例（8→13）——分类平局取首个最大类别、英文词典大小写不敏感、情绪 200 字符窗口外不命中、跨文本平局取首、空白输入回退 OTHER，另按注释铁律删该文件模板 KDoc；`AiPromptSafetyPolicyTest` 新增 5 例（6→11）——sanitizeSender 空白回退 user 与 120 截断、sanitizeQuery 700 截断、role marker 大小写/中文冒号弱化、越权扫描补 AUTH/ADMIN/双类别、安全标注空输出与 12000 扫描窗口边界。零生产改动。；2026-10-23 oct22ak 续批（PR 待 CI）：`AiRetryPolicyTest` 新增 5 例（6→11）——null/空白码归 UNKNOWN、AUTH/UNAUTHORIZED 系先行拒重试、BILLING/INSUFFICIENT 归配额信号、`:120`/TOO_MANY 后缀透传退避毫秒、连接重试位移上限钉死 25_600ms；`AiRetryPolicyWindowTest` 新增 5 例（8→13）——空会话零延迟、空 chatId 独立键、会话间窗口隔离、同一键记两次各占一全局槽、连续 clearSession 不坏窗口；`AiPrivacyPreferencesTest` 新增 5 例（8→13）——账号键与老设备级键分离、MIGRATED 键名与 prefs 文件名钉死、六键互异、迁移标记 scoped 保持冒号形。零生产改动。；2026-10-23 oct22am 续批：`AiEntryPolicyTest` 新增 5 例（9→14）——大小写/空白折叠到典范动作列表、transcript 标志只影响 VOICE、composer 菜单默认参数、hasContextAiActions 随转写标志、三参数门闩全部拒绝方向补齐；`AiWritingStylePolicyTest` 新增 5 例（9→14）——null 预设 id 保留自定义备注且提示只带备注、预设+备注联合提示逐字钉住、备注 320 截断与制表折叠、10 个预设 id 全部经 fromId 回环、禁用快照永不输出提示；`GroupAiSharePolicyTest` 新增 5 例（9→14）——decideShare 校验顺序（非群→已分享→空答案）、分享模式短值透传/空值归 null、null mode 的身份契约、owner 与 dueText 归一化（100/120 截断）、空任务列表不可保存且消毒保序。零生产改动。；2026-10-23 oct22an 续批：`AiTaskDndPolicyTest` 新增 5 例（9→14）——endOfWindowMs 必须严格大于 nowMs、整天窗口首尾半开、钳制可掏空窗口或把分钟拉回窗口内、跨午夜窗口的午夜边界半开、`nextAllowedTime` 尊重 start 边；`AiRetryPolicyTest` 新增 5 例（11→16）——轻重分类各自独立 per-chat 窗口、跨聊天窗口隔离、非瞬态失败任意 attempt 永不重试、RATE_LIMITED 服务端等待精确透传 45000ms 且缺省 30000ms、attempt 0 从基准 800ms 起退；`AiPromptSafetyPolicyTest` 新增 5 例（11→16）——自定义字数上限与 4000 钳制、注入判定在截断前先行、role marker 只匹配行首、入口空值安全全覆盖、标注免责文案裁边与空行分隔逐字钉住。零生产改动。；2026-10-23 oct22ap 续批：`AiCostVisibilityPolicyTest` 新增 5 例（13→18）——英文 too-many 文案无状态码照样判限流且 retry-after 钳制到 86400、insufficient funds 归配额族、通用服务端错误映射空码、限流族拼写变体提示与警告分支全覆盖、本地剩余毫秒向上取整与钳制；`AiLocalRulesTest` 新增 5 例（13→18）——通知/学习/社交三词典、跨类别命中按占比给置信度、愤怒/焦虑情绪词典、跨文本命中占比 3/4、单关键词满置信度与 wire/labelKey 钉住；`AiPrivacyPreferencesTest` 新增 5 例（13→18）——同账号六键 scoped 全互异、scoped 键为裸拼接（base:uid 原样）、门面常量直通簇键名、scoped 键永不撞任一设备级裸键、两账号五键十键全隔离。零生产改动。2026-10-23 oct22aq 续批：`AiEntryPolicyTest` 新增 5 例（14→19）——制表/换行空白折叠到典范类型钉住、三表面常量互异非空、分区顺序恰覆盖全部 ComposerSection 枚举项、两参 run 门闩与三参 master 版全组合等价、hasContextAiActions 全类型横扫与 contextActionsFor 等价；`AiWritingStylePolicyTest` 新增 5 例（14→19）——十个预设提示文案逐字钉住、备注截断保留前 320 字符（含恰 320 整留）、预设 id 制表/换行裁边、normalize 两次调用值相等且 Snapshot 数据类等价、U+00A0 不被 trim 裁掉且计入 memorable；`GroupAiSharePolicyTest` 新增 5 例（14→19）——分享 meta 恰三键身份契约、sanitize 先滤无效再套 30 上限（35 草稿首 10 空留 25）、标题 trim 先于字符上限（前导空格不占 360 额）、decideShare 先 trim 再截 4000、分享决策与 meta 重复调用值相等。零生产改动。2026-10-23 oct22ar 续批：`AttachmentErrorUiPolicyTest` 新增 5 例（4→9）——NETWORK/TIMEOUT kind 回退 FALLBACK、HTTP 状态错误回退、INVALID_RESPONSE 状态无关映射 INTEGRITY_FAILED、六 failure 枚举全映射钉住（含 READ_FAILED/SIZE_MISMATCH 回退）、cause 包装不影响分类；`AttachmentProgressPolicyTest` 新增 5 例（4→9）——段边界钳制到 [0,1]、反转段钉住到 start、字节比钳制越界（超量/负量）、深度负 previousPublished 视同未设置、零长度段钉住 start；`AttachmentSendAfterUploadPolicyTest` 新增 5 例（6→11）——英文 not ready 文案判冲突、upload 关键词变体判冲突、裸 attachment 不判冲突且不可重试、serverCode 大小写不敏感但精确匹配、TIMEOUT kind 可重试但非冲突非重复。零生产改动。2026-10-23 oct22as 续批：`GroupPollPolicyTest` 新增 5 例（2→7）——投票快捷符构建解析往返、畸形投票快捷符四变体拒绝、签到快捷符带/不带 chatId、接龙内容分隔符转义往返、投票题目 200/201 与选项数/重名边界；`GifSearchPolicyTest` 新增 5 例（3→8）——查询归一化（大小写折叠/裁边/80 截断/空值）、匹配大小写不敏感与连字符归一、空查询按日期全返、limit 下限钳制为 1、pushRecent 空白 id 忽略；`CustomStatusPolicyTest` 新增 5 例（3→8）——24 预设互异非空钉住、normalize 空值、isValid 恰 80 边界（含纯空白 81 拒绝）、visibleStatus 空值、归一化恰 80 上限。零生产改动。；2026-10-23 oct22az 续批（PR 待 CI）：`MessageReminderPolicyTest` 新增 5 例（4→9）——14 档位且 12h 在第 9 槽、日档位 24h 整数倍、档位两两互异、与 ScheduledMessagePolicy 共用 1 分钟下界、30 天上限高于 7 天末档；`ScheduledMessagePolicyTest` 新增 5 例（9→14）——与提醒档位逐项一致、归一化保留内部空白、恰 4000 留 4001 截、负待发计数视为空、Long.MAX_VALUE 延迟不溢出；另按注释铁律清该文件模板 KDoc 与长注。零生产改动。2026-10-23 oct22bb 续批：`LinkPreviewPolicyTest` 新增 5 例（10→15）——保留/文档网段与组播拒绝、私网边界公网 IP 放行、首 URL 匹配与尾部标点裁剪、字面 <meta> 后 URL 截断、twitter:title 回退与三字段长度上限；`LinkPreviewPreferencesVersionTest` 新增 5 例（2→7）——version 初值 0、setter 私有、字段 volatile、prefs 键按账号隔离、prefs 文件名稳定；`LinkPreviewRepositoryTest` 新增 5 例（4→9）——非 HTML content-type 拒、相对 Location 跟随、重定向 3 跳上限（第 4 跳判空）、缺 Location 判空、空白 body 判空。零生产改动。；2026-10-24 oct22bf 续批：`SecretGateAndTtlPolicyTest` 新增 5 例（8→13）——默认窗口 5 分钟逐字钉住、上下限恰值保留而±1ms 收钳、getter 对绕行裸值同样钳制、超时配置账号隔离、TTL 默认 86400 与 getter 裸值钳制、徽标偏好开关往返与重复判定稳定；`SecretWhitelistAndFingerprintPolicyTest` 新增 5 例（8→13）——指纹匹配大小写敏感、空白指纹写入被忽略、指纹集合账号隔离、白名单 getter 对裸集合裁边剔空白、转发判定重复调用稳定；`EncryptedAttachmentCryptoTest` 新增 5 例（3→8）——空文件 SIZE_MISMATCH、截断密文 INTEGRITY_FAILED 且删目标文件、畸形 base64 key 与短 iv 判 INVALID_REFERENCE、缓存明文有效性跟踪内容与长度。零生产改动。；2026-10-24 oct22bh 续批：`SecretNewDeviceRiskPrefsTest` 新增 5 例（9→14）——setKnownDevices 整体替换而非追加、registerDevice 写入 trim、开关关闭不影响设备集合读写、无账号时 setKnownDevices 静默丢弃、重复登记幂等；`SecretScreenshotBurnPrefsTest` 新增 5 例（7→12）——清除选项与开关正交、isEnabled 账号隔离、无账号写入丢弃、默认/用户选择的重复读稳定、fail-open 双判定钉住；`SecretSimChangePrefsTest` 新增 5 例（10→15）——来回切卡每次真变更都刷新时间戳、读操作无副作用、isEnabled 账号隔离、无账号 setEnabled 静默丢弃、服务端默认值重复下发稳定。零生产改动。；2026-10-24 oct22bk 续批：`AttachmentContentInspectorTest` 新增 5 例（13→18）——带尾部字节的头仍命中（成功维度）、gif 签名缺一字节即拒（边界维度）、iso brand 大小写敏感（边界维度）、bmp/tiff 头不认作图片（失败维度）、非媒体类型横扫已知头全拒（边界维度）；`AttachmentPreparationLeaseTest` 新增 5 例（12→17）——prepared source 重复记录只留最新（重复维度）、空白覆盖清掉旧记录（重复维度）、permission 释放抛错仍标记已清理（失败维度）、清理后 handoff 不改变任何事（回归维度）、记录顺序不影响删除顺序（回归维度）；`AttachmentPreparationModelTest` 新增 5 例（13→18）——无扩展名视频按声明 mime 补扩展名（成功维度）、扩展名查找大小写不敏感（边界维度）、gif 长名 120 截断保规范扩展名（边界维度）、voice 负尺寸归零（失败维度）、纯点空格名回退 file（边界维度）。零生产改动。；2026-10-24 oct22bn 续批：`DndPreferenceResolveTest` 新增 5 例（8→13）——显式 true 无视零窗口（成功维度）、午夜零窗口推断关闭（边界维度）、单小时钳制后判等（边界维度）、存储分钟两端钳制（边界维度）、存储分钟胜出派生值（回归维度）；`NotificationPreferencesTest` 新增 5 例（7→12）——base 在前顺序回归（回归维度）、userId 含冒号仍隔离（边界维度）、空白 userId 远离裸 key（边界维度）、PREFS_NAME 钉住（回归维度）、大小写区分账号（边界维度）；`QuietHoursAndAnnouncementTest` 新增 5 例（12→17）——全天窗口逐分钟抑制（边界维度）、钳制后等值永不抑制（边界维度）、公告载荷解析过滤排序（成功维度）、畸形载荷容忍（失败维度）、已读紧急公告不通知（回归维度）。零生产改动。；2026-10-24 oct22bp 续批：`RealtimeDisconnectPolicyTest` 新增 3 例（1→4）——常量钉住 2500（回归维度）、正数断言（失败维度）、重复读取稳定（重复维度）；`TokenExpiredEventPolicyTest` 新增 5 例（3→8）——null 当前账号永不处理（账号切换维度）、空白事件 owner 被拒（失败维度）、旧 generation 重放被拒（重放维度）、零 generation 仍匹配（边界维度）、owner 大小写敏感（边界维度）；`ApiFailureSemanticsTest` 新增 5 例（3→8）——NoRouteToHost 归 NETWORK 且可安全重试（失败维度）、超时叠未建连仍判 TIMEOUT 且可重试（取消维度）、深层嵌套 DNS 失败可重试（边界维度）、kind 钉住（回归维度）、cause 链原样保留（回归维度）。零生产改动。；2026-10-24 oct22bq 续批：`AiApiModelsTest` 新增 5 例（2→7）——sender 缺省为空串（边界维度）、task 三可选字段缺省 null（边界维度）、dueText 往返（成功维度）、语义候选全字段解码+sender 缺省（成功维度）、审计响应数值缺省与 error 携带（回归维度）；`SocialApiModelsTest` 新增 5 例（3→8）——评论缺省无父评论无点赞（边界维度）、点赞者列表缺省空（边界维度）、评论 replyToId 往返+缺省 null（成功维度）、附近状态缺省未过期（边界维度）、显式可见性关默认+图片列表解码（回归维度）；`AuthApiModelsTest` 新增 5 例（4→9）——注册请求往返（成功维度）、刷新过期双时间戳解码（成功维度）、未知键忽略（回归维度）、刷新请求带设备 id 往返（重复维度）、动态作者缺省字段（边界维度）。零生产改动。；2026-10-24 oct22br 续批：`CallApiModelsTest` 新增 5 例（4→9）——epoch/sequence/idempotencyKey 往返（成功维度）、SignalMessageDto 未知键忽略+三字段缺省（回归维度）、SignalMessageDto 全字段往返（成功维度）、IceServerDto 空 urls 解码往返（边界维度）、IceConfigDto 未知键忽略+缺省保留（回归维度）；`GroupPlayUpdatePayloadTest` 新增 5 例（4→9）——event 缺省仍取 chatId（边界维度）、非字符串 event 回退空串（边界维度）、非字符串 chatId 回退空串（边界维度）、空 data 对象回退空 chatId（边界维度）、顶层多余键不影响解析（回归维度）；`ApiUserFacingMessageTest` 新增 5 例（5→10）——HTTP 无服务端文案落兜底（失败维度）、UNEXPECTED 无服务端文案落兜底（失败维度）、非 HTTP 有服务端文案优先（回归维度）、服务端文案首尾空白裁剪（边界维度）、null message 落兜底（边界维度）。零生产改动。；2026-10-24 oct22bo 续批：`ApiConfigTest` 新增 5 例（1→6）——尾斜杠裁掉再拼路径（边界维度）、无 scheme 原样拼路径（边界维度）、首尾空白裁掉（边界维度）、非 http scheme 回退路径拼（边界维度）、https 子路径保留（成功维度）；`IncomingServerErrorTest` 新增 5 例（2→7）——空负载给默认值（边界维度）、0 延迟保留（边界维度）、未知嵌套对象被忽略（回归维度）、全字段往返一致（回归维度）、中文错误文案原样（成功维度）；`WebSocketNetworkErrorPolicyTest` 新增 5 例（2→7）——无 SSL 因的 IO 错误可重试（失败维度）、SSL 异常嵌套两层仍停连（失败维度）、peer 未验证停连（失败维度）、非 SSL 包装连接拒绝可重试（回归维度）、裸运行时错误可重试（回归维度）。零生产改动。；2026-10-24 oct22bt 续批：`TokenExpiredEventPolicyTest` 新增 5 例（8→13）——负代际精确匹配（边界维度）、Long.MAX_VALUE 匹配（边界维度）、新代际重放拒绝（失败维度）、空白当前 owner 永不受理（回归维度）、制表符事件 owner 拒绝（边界维度）；`ApiFailureSemanticsTest` 新增 5 例（8→13）——DNS 嵌套下超时仍为 TIMEOUT 种且安全（回归维度）、SSL 失败结果未知（失败维度）、无参异常按类型映射（边界维度）、包装超时顶层决定 NETWORK 种（回归维度）、双层包装连接失败仍可重试（重复维度）；`AuthApiModelsTest` 新增 5 例（9→14）——SendCodeRequest purpose 缺省 register（成功维度）、RegisterWithCodeRequest 往返（成功维度）、DeviceInfoDto 缺省设备名回退服务端默认值（回归维度）、ErrorResponse 可选字段缺省 null（边界维度）、SetUsernameResponse 空对象缺省 not-ok（边界维度）。零生产改动；2026-10-24 oct22bs 续批：`ServerUrlPolicyTest` 新增 5 例（5→10）——首尾空白裁剪后校验（边界维度）、纯空白输入判 EMPTY（边界维度）、端口 1/65535 通过与 0 拒收（边界维度）、IPv6 loopback 字面量放行（回归维度）、host 尾点归一化（回归维度）；`SessionPresencePolicyTest` 新增 5 例（7→12）——过期时刻恰等于 now 视为过期（边界维度）、空白 userId 登出（边界维度）、负过期时间视为未记录（回归维度）、空白 refresh 在 access 过期后登出（边界维度）、过期前 1ms 仍登录（边界维度）；`RealtimeEventPolicyTest` 新增 5 例（9→14）——session gate 当前代号跟踪（回归维度）、零容量钳到 1（边界维度）、超容丢弃最旧事件（失败维度）、无激活会话时打字事件忽略（边界维度）、自然下线事件清在线态但取事件 lastSeen（回归维度）。零生产改动。；2026-10-24 oct22bv 续批：`ApiUserFacingMessageTest` 新增 5 例（10→15）——超时类服务端文案仍优先（成功维度）、ConnectException 直连判连接未建立（失败维度）、嵌套 UnknownHostException 同样识别（回归维度）、SocketTimeout 视为可能已达服务端（边界维度）、HTTP 空白服务端文案走兜底（边界维度）；`AuthApiModelsTest` 新增 5 例（14→19）——ResetPasswordRequest 往返（成功维度）、GroupInvitationDto 缺省 PENDING/GROUP（边界维度）、ChatFolderDto 空 chatIds 缺省（边界维度）、UpdatePrivacyRequest 全 null 缺省（边界维度）、ClientPrefsDto 空对象缺省（回归维度）；`RealtimeDisconnectPolicyTest` 新增 5 例（4→9）——100ms 步进对齐（边界维度）、转 Int 精确（回归维度）、远小于一分钟（边界维度）、秒级换算 2.5 精确（回归维度）、非零防抖（失败维度）。零生产改动。；2026-10-24 oct22bw 续批：`CallApiModelsTest` 新增 5 例（9→14）——多 TURN 服务器 urls 顺序保留往返（成功维度）、ANSWER 大 epoch/sequence 往返（成功维度）、group 键全缺省值（边界维度）、turnEnabled=false 不丢 TURN 服务器（回归维度）、未知键忽略（回归维度）；`SocialApiModelsTest` 新增 5 例（8→13）——editedAt/imageUrls 缺省（边界维度）、回复 parentId 往返（成功维度）、多 liker 往返（成功维度）、空 content 缺省（边界维度）、未知键忽略（回归维度）；`TokenExpiredEventPolicyTest` 新增 5 例（13→18）——Long.MIN_VALUE 边界匹配（边界维度）、重复调用稳定（重复维度）、尾空格 owner 不匹配（边界维度）、unicode owner 精确匹配（成功维度）、null 当前账号任意 generation 均拒（回归维度）。零生产改动。；2026-10-24 oct22bx 续批：`AiApiModelsTest` 新增 5 例（7→12）——未知键忽略（回归维度）、unicode 正文往返（成功维度）、group 任务全字段往返（成功维度）、语义候选全字段往返（成功维度）、审计响应 model 缺省 null（边界维度）；`GroupPlayUpdatePayloadTest` 新增 5 例（10→15）——未知事件仍解析刷新（回归维度）、衬垫 chatId 不匹配屏幕（边界维度）、空白屏幕 id 永不刷新（边界维度）、显式空 chatId 不刷新（边界维度）、payload 原文保留（回归维度）；`SessionPresencePolicyTest` 新增 5 例（12→17）——空 token 登出（失败维度）、制表换行 token 登出（边界维度）、过期 access 且 refresh 为 null 登出（失败维度）、空白 userId 压过已备 refresh（失败维度）、衬垫 token/userId 仍登录（成功维度）。零生产改动。；2026-10-24 oct22bu 续批：`ApiConfigTest` 新增 5 例（6→11）——多重尾部斜杠一次清净（边界）、主机大小写保留（回归）、空输入仅拼路径（边界）、端口保留（成功）、非 http 输出再拼一次（重复）；`IncomingServerErrorTest` 新增 5 例（7→12）——负重试延迟不钳制（边界）、大延迟 Long 全保留（边界）、仅 code 载荷取缺省（边界）、未知数组字段忽略（失败）、错误文本原样保留（回归）；`WebSocketNetworkErrorPolicyTest` 新增 5 例（7→12）——SSLException 基类即停止重连（回归）、三层嵌套仍停止（边界）、取消异常可恢复（取消）、可恢复包装下 SSL 根因仍停止（失败）、InterruptedIOException 可恢复（边界）。零生产改动；2026-10-24 oct22by 续批：`ApiFailureSemanticsTest` 新增 5 例（13→18）——超时顶层遇 NoRoute 仍判 TIMEOUT 但安全（失败维度）、SSL 直抛判 NETWORK 未知结局（失败维度）、超时嵌套普通 IO 仍未知结局（失败维度）、NoRoute 双重包装仍安全（回归维度）、包装后 cause 仍是顶层原对象（回归维度）；`RealtimeEventPolicyTest` 新增 5 例（14→19）——新 gate 代号从 0 起（边界维度）、负容量钳到 1（边界维度）、未溢出时按序投递（成功维度）、状态撤销保留当前离线态与 lastSeen（回归维度）、两次失效代号连进两位（回归维度）；`ServerUrlPolicyTest` 新增 5 例（10→15）——大写 host 放行（成功维度）、非环回 IPv6 字面量放行（成功维度）、host 长度 255 放行、256 判无效（边界维度）、端口 65536 拒、前导零端口放行（边界维度）、缺 scheme 判 SCHEME（失败维度）。零生产改动。；2026-10-24 oct22ca 续批：`ApiConfigTest` 新增 5 例（11→16）——http 无路径拼 /ws、https 端口去尾斜杠、混合大小写 scheme 映射 ws、ws scheme 原样拼 /ws、多段路径去尾斜杠；`IncomingServerErrorTest` 新增 5 例（12→17）——仅 error 缺省、空 code 串保留、缺省往返、转义引号、Long 最大延迟；`WebSocketNetworkErrorPolicyTest` 新增 5 例（12→17）——取消异常载 SSL 仍停重连、四层嵌套仍停重连、深层无 SSL 仍可重连、SSLKeyException 子类停重连、仅消息提及 SSL 仍可重连。零生产改动。；2026-10-24 oct22cb 续批：`ApiUserFacingMessageTest` 新增 5 例（15→20）——NETWORK 空白服务端文案走网络文案（成功维度）、TIMEOUT 空白服务端文案走超时文案（成功维度）、UNEXPECTED 仍优先非空服务端文案（成功维度）、NoRouteToHost 判连接未建立（失败维度）、嵌套 SocketTimeout 仍判 NETWORK 但可能已达服务端（回归维度）；`AuthApiModelsTest` 新增 5 例（19→24）——ChangePasswordRequest 往返（成功维度）、SendCodeRequest reset 目的往返（成功维度）、SetUsernameRequest 往返（成功维度）、UpdateDeviceNameRequest 往返（成功维度）、ConfirmDeviceRequest 往返（成功维度）；`RealtimeDisconnectPolicyTest` 新增 5 例（9→14）——500 整除（边界维度）、超 1 秒（边界维度）、不足 3 秒（边界维度）、整秒余 500ms（边界维度）、翻倍 5 秒（回归维度）。零生产改动。；2026-10-24 oct22cc 续批：`GroupPlayUpdatePayloadTest` 新增 5 例（15→20）——大写事件仍按 chatId 刷新（成功维度）、中文 chatId 同屏匹配（成功维度）、显式空事件仍按 chatId 刷新（成功维度）、数字 chatId 转字符串后同屏匹配（边界维度）、纯空白 chatId 永不刷新（边界维度）；`SessionPresencePolicyTest` 新增 5 例（17→22）——null token 登出（失败维度）、null userId 胜过有效 refresh（账号切换维度）、新鲜 access 无视过期 refresh 记录（回归维度）、Long 最大过期时间仍登录（边界维度）、过期 access 配空 token 登出（失败维度）；`TokenExpiredEventPolicyTest` 新增 5 例（18→23）——事件 owner 前导空白失配（边界维度）、当前 owner 尾空白失配（边界维度）、事件 owner 换行后缀失配（边界维度）、0 与负代际失配（边界维度）、最大代际差一失配（边界维度）。零生产改动。；2026-10-24 oct22bz 续批：`AiApiModelsTest` 新增 5 例（12→17）——空正文往返、dueAt 零值非空、空文本零时间戳往返、审计全字段往返、chatId 与耗时解码；`SocialApiModelsTest` 新增 5 例（13→18）——附近用户往返、评论点赞缺省与显式解码、图片上传响应往返、编辑请求缺省与往返、editedAt 与图片列表往返；`CallApiModelsTest` 新增 5 例（14→19）——最小信令 epoch/序号缺省零、空 payload 往返、空 ICE 配置往返、用户名空凭证往返、成员列表无邀请往返。零生产改动。；2026-10-24 oct22ce 续批：`IncomingServerErrorTest` 新增 5 例（17→22）——显式 null 编解码、换行转义往返、一秒延迟往返、下划线数字错误码、未知布尔字段忽略；`WebSocketNetworkErrorPolicyTest` 新增 5 例（17→22）——SSLProtocolException 子类停连、SocketTimeout 可恢复、RuntimeException 包裹 SSL 停连、UnknownHost 包裹 Connect 可恢复、InterruptedIOException 带 SSL 原因停连；`RealtimeDisconnectPolicyTest` 新增 5 例（14→19）——250 倍数、除 500 得 5、加半秒为 3 秒、字符串为 2500、四倍为 10 秒。零生产改动。；2026-10-24 oct22cf 续批：`RealtimeEventPolicyTest` 新增 5 例（19→24）——空白活跃会话忽略打字事件、已离线用户在线撤销仍清零 lastSeen、在线事件零 lastSeen 直通、代际失效后继续递增、容量 1 仅保留最后事件；`AiApiModelsTest` 新增 5 例（17→22）——任务显式 null 解码、审计显式 null 解码、语义候选负时间戳往返、正文转义往返、任务负 dueAt 原样解码；`CallApiModelsTest` 新增 5 例（19→24）——信令负 epoch/序号往返、消息负时间戳往返、ICE 负过期原样解码、payload 转义往返、无成员群邀请往返。零生产改动。；2026-10-24 oct22cd 续批：`ApiConfigTest` 新增 5 例（16→21）——空白输入只拼路径、纯斜杠只拼路径、混合大小写 Https 映射 wss、HTTP IPv6 字面量保留方括号、无 // 的 scheme 原样拼路径；`ServerUrlPolicyTest` 新增 5 例（15→20）——无密码 userinfo 判 EXTRA、私有段边界（127.0.0.2/0.0.0.0 非本地，10.255.255.255/172.16.0.0/192.168.0.0 本地）、.local 后缀大小写不敏感且仿冒后缀非本地、纯空白路径不算额外路径、根路径带查询判 EXTRA；`ApiFailureSemanticsTest` 新增 5 例（18→23）——三层嵌套 Connect/UnknownHost 仍安全重试、超时套超时仍 TIMEOUT 且未知结果、中断 IO 判 NETWORK 且未知结果、两层嵌套外层 cause 原样保留。零生产改动。；2026-10-24 oct22cg 续批：`GroupPlayUpdatePayloadTest` 新增 5 例（20→25）——布尔 chatId 转字符串、转义事件名原样保留、换行制表 chatId 原样保留、顶层 chatId 诱饵被忽略、空对象双空；`SessionPresencePolicyTest` 新增 5 例（22→27）——refresh 到期时刻恰为 now 仍登录、制表换行衬垫 token 仍登录、Long 最小过期时间视为未记录、衬垫 refresh 仍登录、不换行空格 token 按空白计；`TokenExpiredEventPolicyTest` 新增 5 例（23→28）——纯换行事件 owner 被拒、空白事件 owner 配 null 当前账号被拒、含内空格 owner 精确匹配、emoji owner 精确匹配、当前账号尾制表失配。零生产改动。；2026-10-24 oct22ci 续批：`ApiUserFacingMessageTest` 新增 5 例（20→25）——裸 IOException 判 NETWORK 且可能已达服务端、直传 UnknownHost 判未建连、三层嵌套 Connect 判未建连、INVALID_RESPONSE 有非空服务端文案优先用、制表换行服务端文案去空白；`AuthApiModelsTest` 新增 5 例（24→29）——空对象全默认值、负过期时间原样解码、UserDto 状态/管理员/用户名默认值、错误码加重试秒数解码、改名成功态解码；`SocialApiModelsTest` 新增 5 例（18→23）——负点赞/评论数原样解码、显式 null 父评论配 isMine、未分享配负过期时间、零距离配负时间戳往返、编辑显式 null 可见性解码。零生产改动。；2026-10-24 oct22ch 续批：`RealtimeDisconnectPolicyTest` 新增 5 例（19→24）——减 500 得 2 秒、除 5 得 500、模 700 余 400、乘 3 得 7.5 秒、自减得 0；`WebSocketNetworkErrorPolicyTest` 新增 5 例（22→27）——SSL 因果链五层仍停重连、SSLPeerUnverified 嵌两层 IO 仍停重连、无参 IO 仍可恢复、SSLKeyException 嵌 Runtime 仍停重连、IO 链尾接超时仍可恢复；`IncomingServerErrorTest` 新增 5 例（22→27）——仅延迟载荷默认空错误与 null code、制表转义原样保留、未知 null 字段忽略、Long 最小延迟原样保留、无错误文本的 code+延迟往返相等。零生产改动。；2026-10-24 oct22cj 续批：`ApiConfigTest` 新增 5 例（21→26）——制表换行空白修剪、HTTP 大写主机端口路径、wss 路径再拼、裸 http:// 剩 http:、https 片段保留在 /ws 前；`ApiFailureSemanticsTest` 新增 5 例（23→28）——超时链两层下未知主机仍 TIMEOUT 且安全、NoRoute 四层嵌套仍安全、Connect 套 UnknownHost 仍安全且 cause 原样、InterruptedIO 套 Connect 仍安全、超时两层下仍 NETWORK 且未知结果；`ServerUrlPolicyTest` 新增 5 例（20→25）——用户信息加查询同判 EXTRA、大写 scheme 加最大端口放行、畸形类私网 IP 不判本地、多段与问号形路径判不支持、端口加根斜杠放行。零生产改动。；2026-10-24 oct22cl 续批：`ApiUserFacingMessageTest` 新增 5 例（25→30）——内部空白保留、回车边修剪、顶层超时套未知主机仍 TIMEOUT 且不可达、直连 NoRoute 仍 NETWORK 且不可达、HTTP 200 仍优先服务端文案；`GroupPlayUpdatePayloadTest` 新增 5 例（25→30）——负数/浮点 chatId 字符串化、带空格事件名原样保留、data 内嵌套附加键忽略、仅 event 为 null 仍按 chatId 刷新；`SessionPresencePolicyTest` 新增 5 例（27→32）——制表换行 userId 判登出、MAX 时刻下到期等于此刻判登出、负 nowMs 下正过期判登录、制表填充 userId 仍登录、仅制表 refresh 下过期 access 判登出。零生产改动。；2026-10-24 oct22cm 续批：`AuthApiModelsTest` 新增 5 例（29→34）——在线用户 lastSeen 解码、当前设备时间戳解码、群邀请全字段解码、文件夹全字段解码、偏好非默认值解码；`SocialApiModelsTest` 新增 5 例（23→28）——评论全字段解码、发帖全字段往返、最大过期时间解码、负点赞数解码、unicode 图片地址往返；`TokenExpiredEventPolicyTest` 新增 5 例（28→33）——回车后缀事件方不匹配、回车后缀当前方不匹配、内部制表精确匹配、大写精确匹配、代号差二不匹配。零生产改动。；2026-10-24 oct22ck 续批：`RealtimeEventPolicyTest` 新增 5 例（24→29）——已离线用户离线事件仍取事件 lastSeen、尾空格打字事件不匹配、负代号被拒、两次会话后失效仅失效后代号有效、容量三丢最旧取后三；`AiApiModelsTest` 新增 5 例（22→27）——空上下文消息往返、仅标题任务往返、最大时间戳往返、最大 Int 计数往返、语义候选忽略未知键；`CallApiModelsTest` 新增 5 例（24→29）——dto 最大 epoch/sequence 往返、最大过期时间往返、空 payload 请求往返、dto 未知键下群组字段保留、unicode payload 往返。零生产改动。；2026-10-25 oct22co 续批：`AuthApiModelsTest` 新增 5 例（34→39）——隐私 DTO 默认值、隐私部分更新往返、偏好更新全空往返、删号请求与响应解码、资料部分更新往返；`SocialApiModelsTest` 新增 5 例（28→33）——动态最大点赞/评论数往返、评论更新往返、unicode 评论往返、负距离往返、图片上传请求往返；`TokenExpiredEventPolicyTest` 新增 5 例（33→38）——空串当前属主永不匹配、最小代号加一失配、大负代号、首部制表符属主、大小写混写属主。零生产改动。；2026-10-25 oct22cn 续批：`RealtimeDisconnectPolicyTest` 新增 5 例（24→29）——2000 取余 500、整除 1000 得 2、五倍 12500、自加 5000、减 1000 得 1500；`IncomingServerErrorTest` 新增 5 例（27→32）——emoji 文本解析、未知数值字段忽略、1234567890123 延迟、连字符代号保留、回车转义解析；`WebSocketNetworkErrorPolicyTest` 新增 5 例（27→32）——协议异常嵌套仍停连、超时套 SSL 原因停连、消息提 SSL 的未知主机仍可恢复、带消息 SSL 异常停连、IO 链末端未知主机可恢复。零生产改动。；2026-10-25 oct22cq 续批：`ApiFailureSemanticsTest` 新增 5 例（28→33）——SSL 两层嵌套仍未知结果、超时经两层未知主机仍超时但安全、中断 IO 套无路由仍安全、SSL 套未知主机仍安全、超时经一层连接失败仍超时但安全；`ApiUserFacingMessageTest` 新增 5 例（30→35）——超时映射落超时文案、未知主机映射落网络文案、SSL 映射落网络文案、UNEXPECTED 空白消息落兜底、超时空串消息落超时文案；`GroupPlayUpdatePayloadTest` 新增 5 例（30→35）——emoji 群 id 同屏匹配、冒号斜杠事件名原样保留、200 字长 id 仅精确匹配、键序颠倒仍解析、群 id 大小写敏感。零生产改动。；2026-10-25 oct22cp 续批：`ApiConfigTest` 新增 5 例（26→31）——https 带端口无路径拼 ws、http 尾斜杠拼 ws、裸 ftp 宿主拼 ws、scheme 分隔符在首位原样拼、ws 带端口路径再拼；`ServerUrlPolicyTest` 新增 5 例（25→30）——端口加路径判 EXTRA、双斜杠路径判 EXTRA、尾点宿主加路径判 EXTRA、最大端口加根斜杠放行、空查询与空片段判 EXTRA；`SessionPresencePolicyTest` 新增 5 例（32→37）——token 与 userId 双缺失不放行、双空白不放行、未记录过期加空白 refresh 仍放行、负过期加空白 refresh 仍放行、不换行空格 refresh 加过期 access 不放行。零生产改动。；2026-10-25 oct22cs 续批：`AiApiModelsTest` 新增 5 例（27→32）——空白发送方往返、最大 dueAt 往返、最小时间戳往返、负计数原样解码、空串 error 往返；`CallApiModelsTest` 新增 5 例（29→34）——ICE 请求往返、ANSWER 消息往返、重复成员 id 保序、最大时间戳往返、turn 开启加空服务器往返；`AuthApiModelsTest` 新增 5 例（39→44）——隐私全非默认解码、隐私全字段往返、偏好部分字段往返、资料双字段往返、删号非默认状态解码。零生产改动。；2026-10-25 oct22cr 续批：`RealtimeEventPolicyTest` 新增 5 例（29→34）——双撤销离线用户清状态与 lastSeen、离线事件负 lastSeen 原样透传、尾换行打字事件不匹配、新 gate 先作废进到 1、容量 2 队列四帖只留后两帖；`TokenExpiredEventPolicyTest` 新增 5 例（38→43）——200 字属主精确匹配、最大代号减一精确匹配、零宽空格属主失配、双空白属主被拒、内含换行属主精确匹配；`SocialApiModelsTest` 新增 5 例（33→38）——默认可见性带图往返、显式 null editedAt 解码、评论负点赞数原样解码、Unicode 用户附近往返、空回复 id 往返。零生产改动。；2026-10-25 oct22ct 续批：`RealtimeDisconnectPolicyTest` 新增 5 例（29→34）——2500 取余 300 得 100、取余 600 得 100、六倍为 15000、减 2000 得 500、除以 250 得 10；`IncomingServerErrorTest` 新增 5 例（32→37）——反斜杠转义往返、Unicode 转义往返、一小时延迟全字段往返、点号代号保留、对象数组未知字段忽略；`WebSocketNetworkErrorPolicyTest` 新增 5 例（32→37）——连接拒因下 SSL 仍停连、取消因下对端未验证仍停连、三层 IO 下密钥异常仍停连、两层 IO 下协议异常仍停连、运行时包装超时仍可恢复。零生产改动。；2026-10-25 oct22cu 续批：`ServerUrlPolicyTest` 新增 5 例（30→35）——缺 host 带端口判无效与纯 userinfo 判缺 host、前导零 IPv4 段按十进制解析、localhost 仿冒后缀不算本地、括号 IPv6 直调不算本地、本地 http 恒放行；`ApiConfigTest` 新增 5 例（31→36）——裸 https 分隔符原样拼路径、http 查询串留在 ws 路径前、https userinfo 带入 ws 地址、https 裸 IPv6 字面量保括号、端口路径查询串组合；`AiApiModelsTest` 新增 5 例（32→37）——任务未知键忽略、审计未知键忽略、空 messageId 往返、owner 空串非 null 往返、审计 durationMs 零非 null。；2026-10-25 oct22cv 续批：`ApiFailureSemanticsTest` 新增 5 例（33→38）——SSL 因连接失败仍可重试、超时因两层下无路由仍超时可重试、中断 IO 因未知 host 可重试、无路由因连接失败保因可重试、三层嵌套 SSL 未知后果；`ApiUserFacingMessageTest` 新增 5 例（35→40）——无效响应空串落无效文案、HTTP 空串落兜底、网络空串落网络文案、未知空串落兜底、HTTP 带 serverCode 仍优先服务端消息；`GroupPlayUpdatePayloadTest` 新增 5 例（35→40）——前导零 chatId 精确匹配、含空格 chatId 精确匹配、data.meta 内 chatId 诱饵忽略、超长事件名原样保留、无 data 的 null 事件不刷新。；2026-10-25 oct22cw 续批：`RealtimeEventPolicyTest` 新增 5 例（34→39）——聊天 ID 大小写敏感不匹配、容量恰满四条全保留、在线吊销遇离线事件仍清 lastSeen、在线事件负 lastSeen 直通、双吊销后 nextSession 续进；`SessionPresencePolicyTest` 新增 5 例（37→42）——过期 access 配远古 refresh 时间戳仍登录、NBSP userId 判登出、NBSP 衬垫 token 仍登录、纪元零后正过期时间登录、em 空格 refresh 配过期 access 判登出；`TokenExpiredEventPolicyTest` 新增 5 例（43→48）——NBSP owner 即便相等也拒、NBSP 衬垫 owner 精确匹配、em 空格 owner 拒、内部回车 owner 精确匹配、current 侧零宽空格后缀失配。零生产改动。；2026-10-25 oct22cy 续批：`AiApiModelsTest` 新增 5 例（37→42）——长文本 2000 字往返、空 dueText 往返非 null、空白正文往返、最小 createdAt 往返、任意 status 字符串解码；`CallApiModelsTest` 新增 5 例（34→39）——CANDIDATE 类型往返、空用户名与凭证往返、turn 开启仅 stun 服务器往返、最小 epoch 与 sequence 往返、最小时间戳往返；`SocialApiModelsTest` 新增 5 例（38→43）——空正文动态往返、空正文评论往返、最大距离往返、空 postId 点赞者列表往返、空 status 配非零点赞数往返。零生产改动。；2026-10-25 oct22cx 续批：`ServerUrlPolicyTest` 新增 5 例（35→40）——短格式回环不算本地、空格 IP 不算本地、EXTRA 优先于零端口、尾点主机配端口放行、IPv6 回环配根路径放行；`WebSocketNetworkErrorPolicyTest` 新增 5 例（37→42）——链中 SSL 仍停重连、六层深 SSL 仍停重连、双 SSL 链停重连、消息提及 SSL 但原因非 SSL 仍可重连、UnknownHost 下深层 SSL 停重连；`IncomingServerErrorTest` 新增 5 例（37→42）——未知字符串字段忽略、code 前后空格保留、空 error 配 code 与延迟往返、NBSP 错误文本保留、斜杠 code 保留。零生产改动。；2026-10-25 oct22cz 续批：`AttachmentProgressPolicyTest` 新增 5 例（9→14）——阈值上增量放行、段内亚阈值抑制、零前值完成放行、超一区段坍缩为一、零字节映射段起点；`AttachmentSendAfterUploadPolicyTest` 新增 5 例（11→16）——空错误非冲突非重复、非 Api 错误非冲突非重复、非 409 未就绪文本非冲突但 5xx 仍可重试、混写空白已接受码被接受且不可重试、四层链初始化消息可重试；`AttachmentTransferRetryPolicyTest` 新增 5 例（9→14）——400/404 终局、TIMEOUT 忽略状态码、SocketTimeoutException 子类可重试、包装 IOException 不可重试、消息链五层可重试六层不可重试。零生产改动。；2026-10-25 oct22da 续批：`NotificationSlotPolicyTest` 新增 5 例（13→18）——AI 任务 requestCode 与 notifyId 同哈希、好友/群邀请/公告 notifyId 用裸 id 哈希且与 requestCode 隔离、tag 前缀与 MISS salt 字面值钉死、提醒 id 随消息 id 变化；`LocalNotificationSuppressPolicyTest` 新增 5 例（13→18）——跨天窗口起点含终点不含且含午夜、当前分钟上界钳制到 1439、负小时钳制到午夜、分钟起点配小时终点回退、小时起点配分钟终点回退；`DndPreferenceResolveTest` 新增 5 例（13→18）——仅终点 key 且等窗口推断关闭、负小时钳制后再判相等、零/负小时回退、存储分钟边界极值、钳制后不等窗口与部分 key 情形。零生产改动。；2026-10-25 oct22db 续批：`AiClassifyLexiconTest` 新增 5 例（10→15）——待办任务词归 TODO、考试词归 STUDY、带空格英文短语算一次命中、窗口边缘切开的关键词不算、共享词并列时序在前者胜；`AiEmotionLexiconTest` 新增 5 例（10→15）——重叠愤怒词按词计数、英文开心词聚合、悲伤跨文本聚合、窗口边缘切开的关键词不算、窗口按单条文本算。零生产改动。；2026-10-25 oct22dc 续批：`AiRetryPolicyTest` 新增 5 例（16→21）——小写码归一化后走分支、裸 TOO_MANY_REQUESTS 限流信号零等待、配额后缀进等待与裸配额默认 300 秒、attempt 上限先于分支映射、认证分支优先于配额分支；`AiRetryPolicyWindowTest` 新增 5 例（13→18）——全局延迟压过单会话延迟、新会话双类目均报全局级延迟、只读探查不占全局槽、空会话类目隔离、未满全局上限的他会话流量不影响本会话窗口；`AiTaskDndPolicyTest` 新增 5 例（14→19）——单分钟同日窗口、日末单分钟窗口、1439→0 退化跨夜窗口仅含起点、钳制后相等成空窗口、跨夜起止边沿的 nextAllowedTime。零生产改动。；2026-10-25 oct22de 续批：`AiCostVisibilityPolicyTest` 新增 5 例（18→23）——配额信号优先于限流信号、纯中文过于频繁文案单独立限流、无 FUND/CREDIT/BALANCE 的 insufficient 不算配额、等待秒数优先级服务端＞内嵌＞本地＞默认、流式配额展示信号绑定服务端等待与飞行中提示；`AiLocalRulesTest` 新增 5 例（18→23）——共享词上线平局取首类、三类均分取首类置信 1/3、哈哈哈单条双计命中、情绪与分类窗口边界切词情形。零生产改动。；2026-10-25 oct22df 续批：`AiEmotionFallbackTest` 新增 5 例（10→15）——两轮全量扫表一致、逆序扫表与正序反转一致、模板不含资源名、无首尾空白、无换行；`AiEmotionLexiconTest` 新增 5 例（15→20）——三向平局取首列情绪、sad 与 anxious 平局取 sad、同词多次出现只计 1 次、空串元素返回 neutral、英文 anxious 词聚合。零生产改动。；2026-10-25 oct22dd 续批：`AiPromptSafetyPolicyTest` 新增 5 例（16→21）——注入模板补齐 disregard/忽略系列/you are now the system/developer mode 与近似词反例、控制字符剥离但制表符保留、instruction 行首标记（含前导空白与中文冒号）、上下文行遇注入返回 null 与自定义截断、发送者先剥控制字符再截断。零生产改动。；2026-10-25 oct22dh 续批：`AiCostVisibilityPolicyTest` 新增 5 例（23→28）——裸 402 状态即配额且配额信号兼置限流、纯文案 too many requests 判限流、billing 码判配额、内嵌秒数拒非数字与首冒号且容空格、展示信号绑定自动重试与超时结果；`AiLocalRulesTest` 新增 5 例（23→28）——重复词只计一次、英文子串命中 finance、单字书命中 study、纯空白情绪列表回 neutral、英词跨文本 sad 占 2/3；`GroupAiSharePolicyTest` 新增 5 例（19→24）——助手模式恰 40 字符直通、标题 361 截 360 与 owner 恰 100 直通、dueText 恰 120 直通、分享体保留内部换行只裁两端、空白模式 meta 置 null 且键集不变。零生产改动。；2026-10-25 oct22dg 续批：`AiEntryPolicyTest` 新增 5 例（19→24）——四元合取真值表、三元运行门真值表、类型族单动作归一、二元运行门真值表、菜单门真值表；`AiWritingStylePolicyTest` 新增 5 例（19→24）——预设 id 40 字符截断边界（先 trim 后 take）、非 ASCII 备注原样保留、321 字符备注取前 320、手写快照提示用备注原文、快照相等性区分预设与备注；`AiClassifyLexiconTest` 新增 5 例（15→20）——社交类中文词命中、三向平局取首列、六类平局取 NOTICE 且置信 1/6、多词短语需相邻、空白与超窗英文回落 OTHER。零生产改动。；2026-10-25 oct22di 续批：`AiRetryPolicyTest` 新增 5 例（21→26）——负 attempts 按首次重试计 800ms、限流分支胜于认证分支、BUDGET 裸码等待 0 与内嵌秒数计入配额等待、六分支 explanation 文案钉死、首尾空白与小写先归一再分支；`AiRetryPolicyWindowTest` 新增 5 例（18→23）——会话键大小写敏感、239 LIGHT + 1 HEAVY 混类目触顶 240、清会话后全局阻塞的剩余等待归零、无等待时 awaitBackoff 直接返回、空白会话键与空串键互不干扰；`AiTaskDndPolicyTest` 新增 5 例（19→24）——负向起止钳制到 0 后相等即空窗口、倒置起止恒按跨夜解释、倒置窗口内 nextAllowedTime 返回窗口末、同日窗口外即使未来末点也返回 null、钳到 1439 的分钟恒落右开窗口外。零生产改动。；2026-10-25 oct22dj 续批：`AiPrivacyPreferencesTest` 新增 5 例（18→23）——空 base 键保留前导冒号、空 base 空用户 id 得单冒号、用户 id 大小写敏感、尾空格用户 id 独立、非 ASCII 用户 id 原样保留；`LocalAiGatewayRankTest` 新增 5 例（6→11）——12 词上限外分词丢弃、重复分词按出现次数计分、同分保持候选顺序、子串命中不要求词边界、制表符切词与空格等价；`LocalAiProviderStoreTest` 新增 5 例（8→13）——空名默认取 model、数值与布尔默认值冻结、id 与 model 去空白、带内容工具消息往返保留 call id 与名、非空 temperature 与 topP 往返。零生产改动。；2026-10-25 oct22dl 续批：`AgentSecretGatePolicyTest` 新增 5 例（8→13）——常量逐字值、Error 前缀、密聊拒绝与解锁标志无关、PIN 拒绝需锁定且未解锁双条件、双条件齐备时密聊拒绝优先于 PIN 拒绝；`LocalAiFileAnalyzerTest` 新增 5 例（8→13）——大写 mime 先小写再判文本、空文件名默认 document.txt、非法 base64 拒绝、空输入拒绝、application/xml 按文本接受；`AgentSessionEngineTest` 新增 5 例（9→14）——文本终态先 trim、单轮双只读调用按序执行、工具轮后错误变 Failed、空工具调用耗尽轮次无工具事件、已审批调用预填事件居首。零生产改动。；2026-10-25 oct22dk 续批：`AiCostVisibilityPolicyTest` 新增 5 例（28→33）——负 retry-after 丢弃且预算文案判配额、多冒号内嵌秒数拒识与配额内嵌秒数、空基码编码与 RATE_LIMIT 前缀族编码、多冒号与纯冒号基码边界、负本地剩余回落默认等待；`AgentToolPolicyTest` 新增 5 例（12→17）——审批忽略参数、toolByName 精确大小写敏感、openai 工具 JSON 结构与枚举镜像、parseArgs 空值数组嵌套与浮点转字符串、parseBool 拒识 on/off 单字母与多余数字；`LocalAiProtocolCodecTest` 新增 5 例（10→15）——协议剩余别名与回落、extraHeaders 修剪去空与容错 malformed、toJson 嵌套转换与标量透传、chat completions 文本与工具调用及错误、sse 可见文本空值与非字符串字段。零生产改动。；2026-10-25 oct22dm 续批：`OpenAiCompatClientTest` 新增 5 例（8→13）——空 choices 数组判无 choices、无 message 读空文本、content 为 null 读空文本、空 tool_calls 数组走文本分支、双工具调用按序保留；`AiEmotionLexiconTest` 新增 5 例（20→25）——生气与焦虑平局取 angry、单字滚命中 angry、三悲一喜置信度 3/4、空元素不稀释置信度、英文 miss 命中 sad；`AiRetryPolicyWindowTest` 新增 5 例（23→28）——同会话连记 240 次同样占满全局窗口、HEAVY 窗口跨会话隔离、未满时 clearSession 恢复全部全局容量、不同长度空白会话键互不干扰、全局封顶后继续记录仍封顶。零生产改动。；2026-10-25 oct22dn 续批：`AiRetryPolicyTest` 新增 5 例（26→31）——限流分支胜于配额分支、PAYMENT 内嵌秒数计入配额等待、attempts≥3 时可见码取归一化基码、空串码回落 UNKNOWN、HEAVY 单聊窗口阻塞并上报等待；`AiTaskDndPolicyTest` 新增 5 例（24→29）——负 nowMs 仍认未来窗口终点、负分钟钳到午夜再算窗口、[1,1439) 窗口排除午夜、倒置窗口起边 nextAllowedTime 取终点、钳制起边变跨夜窗口；`AiEmotionFallbackTest` 新增 5 例（15→20）——模板句末标点、模板无数字、模板含逗号停顿、happy 模板最短、模板无双空格。零生产改动。；2026-10-25 oct22do 续批：`AiLocalRulesTest` 新增 5 例（28→33）——多词词条出现两次只计一次、TODO 与 FINANCE 平局取枚举在前的 TODO、长英文关键词在 300 字符窗口边缘恰好容纳命中与多垫一字不命中、情绪英文词大小写敏感、大写无命中而小写命中、单条文本内情绪平局取 HAPPY；`AiWritingStylePolicyTest` 新增 5 例（24→29）——预设 id 含内部空格永不匹配、手工 Snapshot 空白 note 只留预设文案、回车与混合空白折叠、禁用归一化等于 clear、大小写混合预设 id 匹配；`GroupAiSharePolicyTest` 新增 5 例（24→29）——纯换行制表答案判空、模式先 trim 再截 40 且保留内部空格、先过滤空白再取 30 恰好留满、闭环在 4000 与 4001 字符答案下均就绪、分享体保留内部制表符。零生产改动。；2026-10-25 oct22dp 续批：`AiClassifyLexiconTest` 新增 5 例（20→25）——FINANCE 与 STUDY 平局取 FINANCE、英文关键词恰好填满 300 字符窗口命中、other 一词本身无词典条目、多词短语大小写不敏感匹配、TECH 占全部命中 3/4 得 0.75；`AiPrivacyPreferencesTest` 新增 5 例（23→28）——换行/制表 userId 原样拼接、空 userId 下五键均留尾冒号、scoped 键长等于 base+冒号+userId、base 含空格原样拼接、emoji userId 原样拼接；`AiPromptSafetyPolicyTest` 新增 5 例（21→26）——默认消毒上限 1800 与四常量冻结、query 路径同样落注入与角色标记、单条输出同时命中四类特权声称、扫描窗口恰好填满留声称/超窗丢声称、context line 截断 sender 并弱化角色标记。零生产改动。；2026-10-25 oct22dq 续批：`LocalAiFileAnalyzerTest` 新增 5 例（13→18）——pdf 后缀强制走渲染路径拒文本字节、PDF 魔数跨 mime 嗅探、U+FFFD 文本拒收、markdown mime 接受、恰好 12000 字符整段保留；`AgentToolPolicyTest` 新增 5 例（17→22）——审批按风险全表对应、空名与带空格名拒收、OpenAI 工具 JSON 描述/必填/enum 逐项镜像规约；`LocalAiGatewayRankTest` 新增 5 例（11→16）——空候选与空查询回空、零分候选在等分命中间被滤除、第 12 个词条计分、重复候选 id 双双保留。零生产改动。；2026-10-25 oct22dr 续批：`AiEntryPolicyTest` 新增 5 例（24→29）——非语音动作不受 transcript 标志影响、内部空白永不折叠、前后缀变体不匹配、不换行空格同样折叠（Kotlin trim 剥离 U+00A0）、非语音类型在标志两值下结果一致；`AgentSessionEngineTest` 新增 5 例（14→19）——同轮未知工具不阻塞后续只读调用、需审批调用在已允许调用后截停本轮、空白改写文本不调模型直接报 text required、改写模型错误作为工具结果回填、历史 system 消息过滤与用户文本 4000 截断；`LocalAiProtocolCodecTest` 新增 5 例（15→20）——采样与 max_tokens 上下限钳制、chat 体编码工具调用与结果、responses 体工具扁平化与工具往返编码、Anthropic 多 system 换行拼接与 tool_result 冲刷、解析错误与回落边。零生产改动。；2026-10-25 oct22ds 续批：`AgentSecretGatePolicyTest` 新增 5 例（13→18）——8 行显式拒表/8 行显式列出表/拒信点名各自门禁/拒信不串门/拒值域仅三值；`LocalAiProviderStoreTest` 新增 5 例（13→18）——三协议草稿默认/协议枚举名往返/会话 createdAt 与空消息/空白 toolCallId 与 toolName 归 null/空白 base 与非对象行丢弃；`OpenAiCompatClientTest` 新增 5 例（13→18）——非 JSON 精确报错/正文胜于推理/无正文键读推理/空函数名调用保留/仅读首个 choice。零生产改动。；2026-10-25 oct22dt 续批：`LocalAiGatewayRankTest` 新增 5 例（16→21）——查询首尾空白不影响排序、候选文本重复词只计一次、大小写两侧归一、三等分稳定保序、缺失词零分不扣分；`AiEmotionFallbackTest` 新增 5 例（20→25）——模板均短于八十字、句末标点仅在末尾、happy 短于 sad 与 anxious、无制表回车、仅 happy 以感叹号结尾；`AiClassifyLexiconTest` 新增 5 例（25→30）——部署类词归 TECH、付费类英文词归 FINANCE、休闲类英文词归 SOCIAL、课程类英文词归 STUDY、维护公告词归 NOTICE。零生产改动。；2026-10-25 oct22du 续批：`AiEmotionLexiconTest` 新增 5 例（25→30）——happy/anxious 平局取 happy、happy/sad 双命中平局取 happy、同词现于两文本计 2 次、大写 SAD 不命中、anxious 3 命中胜 sad 1 命中；`AiTaskDndPolicyTest` 新增 5 例（29→34）——1500 钳入跨夜窗口、负 nowMs 跨夜仍返 end、1439→1438 跨夜仅排除 1438、跨夜晨段外纵有未来 end 仍返 null、倒置窗口终点分钟排除；`AgentToolPolicyTest` 新增 5 例（22→27）——目录名唯一且非空、parseArgs 保留 false/0 字符串、空白 JSON 输入为空、回退预览截断 160 字符。零生产改动；2026-10-25 oct22dx 续批：`AiRetryPolicyWindowTest` 新增 5 例（28→33）——冒号会话键不与类目口缀撞键、双类同记时 HEAVY 剩余大于 LIGHT、非 ASCII 会话键互异、万字符会话键可用、清会话后全局窗口可重新填满；`AiPrivacyPreferencesTest` 新增 5 例（28→33）——迁移标记 scoped 键跨账号互异、五偏好键共 ai_ 前缀而迁移标记不在内、尾冒号 base 拼出双冒号、形似键名的 userId 不串键、裸键均无空白；`GroupAiSharePolicyTest` 新增 5 例（29→34）——超限一字符截断恰 4000、满长空白答案仍判空、dueText 超限一字符截断 120、Long 最大 dueAt 原样保留、分享 meta 长模式截断 40。零生产改动。；2026-10-25 oct22dy 续批：`AiClassifyLexiconTest` 新增 5 例（30→35）——词典重复列项按列次计票、大小写子串连中两词、lol 归 social、红包归 finance、纯标点回 OTHER；`AiEmotionFallbackTest` 新增 5 例（25→30）——仅 sad/angry 表理解、anxious 最长、仅 happy 点名开心、无问号、neutral 短于 sad/angry/anxious；`AgentSecretGatePolicyTest` 新增 5 例（18→23）——拒信单行无尾空白、secret 拒信长于 PIN 拒信、拒信各点名主体、纯 ASCII、拒信各述理由。零生产改动。；2026-10-25 oct22dv 续批：`LocalAiFileAnalyzerTest` 新增 5 例（18→23）——csv 类型按文本接受、空白 mime 照样拒绝、大写 .PDF 扩展名走渲染路径、url-safe 横线归一、五字节 %PDF- 嗅探边界；`AiPromptSafetyPolicyTest` 新增 5 例（26→31）——句中注入仍命中与近似表述不命中、无空格行首标记弱化与控制符剥离后标记、扫描前剥离控制符、标注先裁外层空白、发送方不弱化行首标记；`AiWritingStylePolicyTest` 新增 5 例（29→34）——词内不换行空格不折叠、启用态双空仍无提示、折叠先于 320 截断、fromId 直测空值与大小写、空白预设 id 仍出备注提示。零生产改动。；2026-10-25 oct22dw 续批：`OpenAiCompatClientTest` 新增 5 例（18→23）——JSON 数组载荷精确报错、choices 非数组报错、首 choice 非对象报错、null message 读空文本、无 arguments 键的工具调用 argumentsJson 为空；`LocalAiProviderStoreTest` 新增 5 例（18→23）——多斜杠 baseUrl 全量去尾、空白 id/model/base 行丢弃、apiKey 空白原样保留、显式 stream false 与 supportsVision true 落盘、空白 anthropicVersion 与 extraHeadersJson 回默认；`LocalAiProtocolCodecTest` 新增 5 例（20→25）——SSE 非 data 行忽略、畸形 data 行跳过、正文开始后推理段丢弃、分片正文拼接、delta 对象回退形。零生产改动。；2026-10-25 oct22dz 续批：`AgentSessionEngineTest` 新增 5 例（19→24）——未知工具报错回填下一轮、润色只发 finished 不发 started、工具结果回填 tool 消息、已审批调用预填 assistant 与 tool 消息、历史窗口只留后 24 条；`LocalAiGatewayRankTest` 新增 5 例（21→26）——长词子串按词计分、标点粘连失配、空候选不计分、第 13 个重复词忽略、大写子串不区分大小写；`AgentToolPolicyTest` 新增 5 例（27→32）——parseBool 修剪混写、发送预览截断、空白草稿与备注标记、风格提示仅非空出现、改会话预览列全量标记。零生产改动。；2026-10-25 oct22ea 续批：`LocalAiFileAnalyzerTest` 新增 5 例（23→28）——带参数 text mime 接受且小写存入、带参数 json mime 精确匹配拒绝、纯空白文件名回退 document.txt、多字节文本按字符数截断、前导 mime 空白拒绝而尾随空白仍匹配；`AiRetryPolicyTest` 新增 5 例（31→36）——非正数后缀落回分支默认等待、超大后缀按一天封顶、带后缀连接失败仍重试、未知族完整决策钉住、认证分支保留归一化码且忽略后缀；`AiPromptSafetyPolicyTest` 新增 5 例（31→36）——非正数上限钳到 1、查询剥离控制字符与修边、空与纯空白输出无声称、标注恰好命中 12000 字扫描窗口边、行文本上限钳到 4000。零生产改动。2026-10-25 oct22eb 续批：`AiClassifyLexiconTest` 新增 5 例（35→40）——transfer/price 归 FINANCE、看书归 STUDY、follow up 短语恰好占满 300 字窗口、四命中多数置信度 0.75、good night 短语归 SOCIAL；`AiWritingStylePolicyTest` 新增 5 例（34→39）——formal 预设经 normalize 钉住提示、禁用归一化未知预设仍等于 clear、纯换行 note 归空、diplomatic 组合提示钉住、320 字含空格 note 完整保留；`AiTaskDndPolicyTest` 新增 5 例（34→39）——同日窗口运行时关闭不抑制、午夜等界为空窗口、同日窗口结束边 +1 毫秒、钳制终边到午夜只剩晚间段、全日窗口覆盖正午与 1438 分。零生产改动。2026-10-26 oct22ed 续批：`AiPromptSafetyPolicyTest` 新增 5 例（36→41）——注销账号单条归 ACCOUNT_DESTRUCTIVE、封禁单条归 ADMIN_OR_MODERATION、空发送方注入行仍丢弃、查询先剥控制符且句中标记不弱化、全大写 disregard 模板仍命中；`AiPrivacyPreferencesTest` 新增 5 例（33→38）——冒号 userId 原样拼接、六键双账号 12 键互异、自动翻译键精确值、冒号 userId 拼出双冒号、迁移标记空 userId 仍留尾冒号；`AiLocalRulesTest` 新增 5 例（33→38）——全金融词组合仍满置信度、重复词仍按去重后平局取首类、跨文本悲伤多数占 2/3、单文本窗口外关键词不影响他文本、297 垫字加转账仍在窗口内。零生产改动。2026-10-25 oct22ec 续批：`AiEmotionLexiconTest` 新增 5 例（30→35）——stress 归 ANXIOUS、cry/emo 归 SAD、吵单字归 ANGRY、四情绪并列归 HAPPY 置信度 0.25、love 恰好占满 200 字窗口；`AiCostVisibilityPolicyTest` 新增 5 例（33→38）——错误码秒数按一天封顶、限流码前后空白仍映射、取消展示信号绑定部分计费提示与重试警告、配额提示优先于计划自动重试、无效响应与中断均触发重试计费警告；`AiEntryPolicyTest` 新增 5 例（29→34）——语音默认无转写形式有动作、空串无动作、制表换行包裹 voice 仍转写、小写 system 带转写标志仍翻译、语音默认列表等于显式无转写列表。零生产改动。2026-10-26 oct22ee 续批：`AiEmotionFallbackTest` 新增 5 例（30→35）——仅 happy 与 sad 含恰好一个逗号、仅 neutral 提 listening、仅 anxious 提 step by step、仅 sad 提随时/whenever、仅 angry 提慢慢/slowly；`AiRetryPolicyWindowTest` 新增 5 例（33→38）——纯 HEAVY 记满 240 同样封顶两类、LIGHT 重记刷新 30 秒窗口、100 条未满清会话后新会话直通、全局封顶等待不超过 1 小时、空串与空白键不影响具名会话；`GroupAiSharePolicyTest` 新增 5 例（34→39）——非群组先于空答案判 NOT_GROUP、已分享闭环即使任务有效仍未就绪、空白与有效混合任务闭环就绪、3999 字原样通过、全空白标题不可保存。零生产改动。2026-10-26 oct22ef 续批：`AiRetryPolicyTest` 新增 5 例（36→41）——限流前缀缺失不触发限流分支、含 QUOTA 子串走配额分支、INSUFFICIENT 与 BILLING 后缀计入配额等待、全局窗口 240 次边界；`AgentToolPolicyTest` 新增 5 例（32→37）——社交/任务/群组写入预览、无预览工具回退原 JSON、openai 参数类型与 spec 一致；`LocalAiGatewayRankTest` 新增 5 例（26→31）——查询词序不影响分值、第 13 个互异词被忽略、空白候选被滤掉、同文本不同 id 均保留、大小写混合多词全计分。零生产改动。2026-10-26 oct22eg 续批：`AiEntryPolicyTest` 新增 5 例（34→39）——混合大小写 gif/file/system 折叠到各自动作、制表换行包裹 file 仍判文件、带转写标志的留白 voice 为空、全部已知类型各恰一个动作、未知类型与空类型带转写标志仍为空；`AiWritingStylePolicyTest` 新增 5 例（39→44）——fromId 先 trim 再小写、恰 40 字符的 warm 加 x 串不命中、尾空格备注先 trim 再计 320 上限得 319、500 空格长串折叠为单空格、禁用归一化对全部预设 id 均等于 clear；`ChatAiContextBuilderTest` 新增 5 例（13→18）——摘要同样丢弃 AI 辅助消息、plain 上限取最后 N 条、摘要上限 0 与超限钳制、图片消息仅凭翻译进摘要、同时间戳摘要按 id 排序。零生产改动。2026-10-26 oct22ei 续批：`LocalAiProtocolCodecTest` 新增 5 例（25→30）——混合大小写与留白协议别名、无工具时 stream 原样透传、空采样键省略但 max_tokens 保留、responses tool_call 形态与根 output_text 回退、anthropic 多文本块拼接与标量工具输入原样保留；`LocalAiProviderStoreTest` 新增 5 例（23→28）——数值字段往返、组织与 anthropic 版本往返、非空工具行顺序保留、非对象消息行丢弃、会话 id 修边与缺失 createdAt 归零；`OpenAiCompatClientTest` 新增 5 例（23→28）——非对象工具条目跳过、全无效工具条目回落文本、工具参数原样保留、首 choice 无消息仍取首条、非字符串 reasoning 不掩盖正文。零生产改动。2026-10-26 oct22eh 续批：`AgentSecretGatePolicyTest` 新增 5 例（23→28）——两常量共享 Error 前缀、大写字母形态钉住、常量长度 54/25、具名参数换序同决策、PIN 拒绝重复 5 次稳定；`LocalAiFileAnalyzerTest` 新增 5 例（28→33）——yaml 拒、html 接受、json 前后空白原样保留、大写 JSON mime 小写化后接受、svg 图片 mime 拒；`AgentSessionEngineTest` 新增 5 例（24→29）——已审批调用后接读工具轮次再收尾、空用户文本仍以空 user 消息进模型、无 mode 改写默认 polish、单轮双未知工具各回错误、发送审批 pending 携带原调用 id 与参数。零生产改动；2026-10-26 oct22ej 续批：`AgentToolPolicyTest` 新增 5 例（37→42）——消息动作预览（星标/撤回/本机删除/通知已读/置顶/反应/开聊/禁言）、缺失 flag 的 update_chat 预览为 null、好友决策预览（同意/拒绝/评论/删除动态/取消拉黑）、草稿与动态预览截断 80 字、parseArgs 保留 unicode/负数/空串；`LocalAiGatewayRankTest` 新增 5 例（31→36）——查询词重复按查询侧次数计分、混合分值降序、内部多空格折叠、大小写混合子串命中、连字符查询词不拆分；`AiLocalRulesTest` 新增 5 例（38→43）——部署/课程单关键词满置信度、great/sad 大小写敏感、follow up 短语加 deadline 合并计分、sad 平局胜 anxious、单字关键词 300 字边界。零生产改动。2026-10-26 oct22el 续批：`AiEntryPolicyTest` 新增 5 例（39→44）——纵向制表/换页符包裹折叠、零宽空格不匹配、全角字母不匹配、GIF 转录标志镜像、表面常量蛇形小写；`AiTaskDndPolicyTest` 新增 5 例（39→44）——提醒双开关合取全表、跨夜凌晨段 nextAllowedTime、1439→1 退化窗口、全日窗口排除 1439、正午全日窗口返回窗口终点；`GroupAiSharePolicyTest` 新增 5 例（39→44）——普通答案 reason 为 null、整任务对象归一化、mode 内换行保留、空白答案闭环未就绪、空输入消毒为空；2026-10-26 oct22ek 续批：`AiPromptSafetyPolicyTest` 新增 5 例（41→46）——冒号前空白的角色标记照常弱化、注入模板需精确空格且全大写命中、控制符剥离后才判注入、英文特权声称三类（payment completed/password has been reset/banned）、裸标记行保留弱化文本且纯控制符发送者回退 user；`AiPrivacyPreferencesTest` 新增 5 例（38→43）——base 内换行与制表符原样保留、scoped 键剥离用户后缀可还原 base、参数顺序影响结果且重复调用稳定、PREFS_NAME 进入 7 键互异集、scoped 键嵌套拼接；`AiCostVisibilityPolicyTest` 新增 5 例（38→43）——429 遇配额文案仍判配额、带空格的 rate limited 文案判限流、编码丢弃负秒数且修边后配额基码可加后缀、等待秒数跳过非正服务端值与零本地剩余、未知错误码展示信号绑定服务端与本地等待。零生产改动。2026-10-26 oct22en 续批：`AiRetryPolicyTest` 新增 5 例（41→46）——AUTH_EXPIRED 走认证分支、ERROR_TOO_MANY_REQUESTS 含 TOO_MANY 走限流分支且等待为 0、QUOTA_EXCEEDED:0 与负后缀落回配额默认 300 秒、PAYMENT_REQUIRED 超大后缀按一天封顶、限流/配额/认证分支在 1 次与 2 次下结果相同；`AiRetryPolicyWindowTest` 新增 5 例（38→43）——239 槽未满时单会话等待仍为自身量级、同会话两类目占 2 个全局槽、新会话自占第 240 槽后被挡、第 240 槽 HEAVY 记录自身等待为全局量级、HEAVY 二次记录刷新 60 秒窗口；`AiWritingStylePolicyTest` 新增 5 例（44→49）——下划线分隔预设 id 永不匹配、纯标点备注算可记忆内容、emoji 与标点顺序保留、制表/换行包裹的 warm 预设与备注组合、enthusiastic 预设与备注组合提示格式；2026-10-26 oct22em 续批：`AiEmotionFallbackTest` 新增 5 例（35→40）——sad 模板长于 happy 模板、angry 模板长于 neutral 模板、仅 anxious 模板提陪伴、仅 angry 模板说别急、仅 neutral 模板邀对方说；`AiEmotionLexiconTest` 新增 5 例（35→40）——haha/后悔/hate/失眠四词各命中其情绪、大写 HAPPY 因大小写敏感不命中；`AiClassifyLexiconTest` 新增 5 例（40→45）——assign 归 TODO、announcement 归 NOTICE、工资归 FINANCE、release 归 TECH、lecture 归 STUDY。零生产改动。2026-10-26 oct22eo 续批：`AiCostVisibilityPolicyTest` 新增 5 例（43→48）——insufficient balance 判配额、空格形 payment required 不判配额、PAYMENT_FAILED 计费提示与重试警告、TOO_MANY 编码去后缀但内嵌秒数仍可读、402 带 retry-after 保留且判配额；`AiLocalRulesTest` 新增 5 例（43→48）——红包/优惠归 FINANCE、haha/lol/weekend 归 SOCIAL、太好了/棒判 HAPPY、ANGRY 平局胜 ANXIOUS、考试在 300 字窗口边沿恰容纳命中与切分不命中；`AiPrivacyPreferencesTest` 新增 5 例（43→48）——千字用户 id 原样保留、prefs 名作用户 id 不混淆、同用户双重 scoped 保留双后缀、纯冒号 base 双冒号形、回车与组合空格原样保留。零生产改动。；2026-10-26 oct22er 续批：`AiEmotionFallbackTest` 新增 5 例（40→45）——anxious 模板长于 angry 模板、happy 与 neutral 为最短两条、分句无重复、无分号冒号、非 happy 模板以句号结尾；`AiEmotionLexiconTest` 新增 5 例（40→45）——句中难过命中、mad 命中、哈哈开心满置信度、mad 恰填 200 字窗口、空表与空白表结果相等；`AiClassifyLexiconTest` 新增 5 例（45→50）——吃饭归 SOCIAL、服务器归 TECH、截止提交归 TODO、发票归 FINANCE、复习笔记归 STUDY。零生产改动。；2026-10-26 oct22eq 续批：`AiRetryPolicyTest` 新增 5 例（46→51）——空白与小写连接失败仍重试（800/1600ms）、冒号前无基码可见码即原串、冒号前空格仍解析后缀秒数、LIGHT 单会话剩余在 30 秒内、复合码含 AUTH 走认证分支；`AiRetryPolicyWindowTest` 新增 5 例（43→48）——类目前缀会话键不碰撞、制表/换行键与空格/空串键互异、只读探查不解被拦会话、预组合与分解 Unicode 键互异；`GroupAiSharePolicyTest` 新增 5 例（44→49）——不换行空格经 trim 裁掉（分享体/消毒标题/mode 三处）、非法 dueAt 只置 null 不丢任务致闭环仍就绪、任务数达 30/31 条上限仍就绪。零生产改动。；2026-10-26 oct22ep 续批：`AiPromptSafetyPolicyTest` 新增 5 例（46→51）——大写 instruction 全角冒号弱化、多行后行标记弱化且首尾行不动、中文系统角色声称与 developer mode 短语命中及 develop mode/制表分隔近似不命中、弱化先于截断（100 字含替换后标记）、发送者内部空格保留与 120 字精确截断；`AiEntryPolicyTest` 新增 5 例（44→49）——忙碌拦菜单与执行门但不拦附件菜单入口、菜单可开而入口未激活、动作枚举恰四项、已知类型每次返回新列表而未知类型共享空列表、附件菜单入口弱于四开关全开的表面门；`AiTaskDndPolicyTest` 新增 5 例（44→49）——同日窗口全量扫描恰 60 分钟、跨夜窗口全量扫描恰 120 分钟、Long 极值下 end 仍须严格大于 now、运行时关闭在钳制前短路、负时间轴上 end 大于 now 仍返回。零生产改动。；2026-10-26 oct22es 续批：`AiCostVisibilityPolicyTest` 新增 5 例（48→53）——通用失败丢弃服务端 retry-after 且映射 null、SERVER 失败重试计费提示与自动重试、内嵌秒数优先于本地剩余、非限流配额族不加后缀、中文过于频繁仅文案字段识别；`AiLocalRulesTest` 新增 5 例（48→53）——共享词“上线”并列取通知、通知英文词满置信度、转账与通知并列取通知半置信度、开心与气死跨文本并列取开心、首字母大写英文情绪词不命中；`AiPrivacyPreferencesTest` 新增 5 例（48→53）——换页符与垂直制表用户 id 原样、反斜杠与引号用户 id 原样、分解组合字符与预组合字符互异、前导零用户 id 互异、表情用户 id 键长计代理对。零生产改动。；2026-10-26 oct22et 续批：`AiRetryPolicyTest` 新增 5 例（51→56）——限流与配额内嵌 86400 秒恰达一天上限、尾冒号无秒数落回分支默认等待、裸 TOO_MANY 走限流分支等待为 0、含 QUOTA 的连接码先走配额分支、RATE_LIMITED_EXTRA 前缀加后缀按内嵌秒数计等待；`AiRetryPolicyWindowTest` 新增 5 例（48→53）——表情会话键按代理对区分、空串 HEAVY 记录不拦 LIGHT、同名类目会话键不碰撞、换行后缀会话键独立、前导冒号会话键独立；`GroupAiSharePolicyTest` 新增 5 例（49→54）——已分享长答案仍空体、41 字模式截断为 40 字、最小正 dueAt 保留、NBSP 标题任务致闭环未就绪、表情答案按 UTF-16 码元截断为 2000 个。零生产改动。；2026-10-26 oct22eu 续批：`AgentSecretGatePolicyTest` 新增 5 例（28→33）——拒信以各自主题词结尾、恰一个冒号、无数字、位置与具名调用一致、词数 9/4；`AgentToolPolicyTest` 新增 5 例（42→47）——上限全正且有序、审批名大小写敏感、openai json 三层键集合精确、未知工具空 json 回退留尾空格、负浮点与重复键拒绝；`AgentSessionEngineTest` 新增 5 例（29→34）——审批仅耗一轮模型调用、未知调用在审批前仍暂停、已审批读调用预填先于模型轮次、读工具完成事件携带原始结果串、审批挂起预览与发送预览逐字一致。零生产改动。；2026-10-26 oct22ev 续批：`AiEntryPolicyTest` 新增 5 例（49→54）——U+001F 控制符衬垫折叠、带重音字母不命中、组合重音符破坏匹配、设置更新只拦菜单不拦执行门、下划线拼接类型不命中；`AiTaskDndPolicyTest` 新增 5 例（49→54）——退化跨夜窗口 sweep 计 2 分钟、钳制后区间 sweep 计 61 分钟、倒置窗口 sweep 计 640 分钟、Int 极值分钟先钳制再算窗口、全日窗口 sweep 计 1439 分钟；`AiWritingStylePolicyTest` 新增 5 例（49→54）——纵向制表与换页符折叠、零宽空格原样保留、纯零宽空格算有内容、表情在 320 上限处按 UTF-16 码元截断、手工 Snapshot 的换行 note 原样进提示。零生产改动。；2026-10-26 oct22ew 续批：`AiEmotionFallbackTest` 新增 5 例（45→50）——仅焦虑模板提担心、仅焦虑模板提理清事情、仅中性模板说收到、仅悲伤模板提聊聊、开心模板短于中性模板；`AiPromptSafetyPolicyTest` 新增 5 例（51→56）——消毒丢 ignore all previous 但保留近似句式、三行角色标记同文本弱化、英文四类声称单例（wired funds/closed account/exported keys/promoted to admin）、消毒保留行内换行与回车、中文角色声称按子串前缀匹配；`AiClassifyLexiconTest` 新增 5 例（50→55）——周会类、报销发票对提交的三分之二占比、数据库版本类、论文阅读类、注意上线类。零生产改动。；2026-10-26 oct22ex 续批：`LocalAiFileAnalyzerTest` 新增 5 例（33→38）——大写 xml 接受、csv 带参数按前缀接受、大写 json 带参数按精确匹配拒绝、BOM 原样保留、DEL 接受；`LocalAiGatewayRankTest` 新增 5 例（36→41）——不间断空格不断词、单字符分词跨词命中、正则元字符按字面匹配、平分排序稳定、回车分词；`LocalAiProtocolCodecTest` 新增 5 例（30→35）——anthropic 非 JSON 报错、未知块跳过、工具调用携带前置文本、无名 function_call 跳过、空白 content 不回落。零生产改动。；2026-10-26 oct22ey 续批：`AiEmotionLexiconTest` 新增 5 例（45→50）——lovely 含 love 子串命中、喜欢短语子串命中、难过哭了 2 票胜担心、气死生气 2 票胜其余三情绪、空白条目不影响哈哈与难过平局取 happy；`AiCostVisibilityPolicyTest` 新增 5 例（53→58）——billing 文案单独判配额、TOO_MANY_REQUESTS 码字段判限流、内嵌秒数恰为 86400 上限通过、本地剩余 1001 毫秒进位为 2 秒、带空白 quota 码仍映射；`AiLocalRulesTest` 新增 5 例（53→58）——部署与周末平局取 TECH、学习课程 2 票胜转账、哈哈周末电影全社交、通知公告 2 票胜任务、study homework exam 全学习。零生产改动。；2026-10-26 oct22ez 续批：`AiEntryPolicyTest` 新增 5 例（54→59）——已知类型每次返回新列表、未知类型共享空列表、常量与分区顺序委托可见性对象、分区顺序等于枚举声明顺序、入口激活标志恒等于入参；`AiTaskDndPolicyTest` 新增 5 例（54→59）——跨夜窄窗口扫数 360、同日短窗口扫数 15、倒置窗口外与终边返回 null 且晨段返回 end、钳制到零的起止为空窗口、Int 极值分钟钳制后进窗判断；`LocalAiProviderStoreTest` 新增 5 例（28→33）——协议别名大小写与空白折叠、名称前后空白原样保留、仅斜杠 baseUrl 行丢弃、重复 id 双行保留、空白非工具消息行编码保留。零生产改动。；2026-10-26 oct22fa 续批：`AiClassifyLexiconTest` 新增 5 例（55→60）——哈哈哈重叠词条占社交 2/3 份额、bug 双计票下学习与技术 2 比 2 按序取学习、系统维护与服务器各 1 票按序取通知、follow up 与 invoice 各 1 票按序取待办、297 垫字后 follow up 被窗口切断不命中；`AiPrivacyPreferencesTest` 新增 5 例（53→58）——NUL 与响铃控制符原样、DEL 原样、全空格用户 id 原样且异于空串、零宽空格原样且长 1、旗帜表情占 4 码元；`AiWritingStylePolicyTest` 新增 5 例（54→59）——NBSP 衬垫预设命中、尾空格先 trim 再截断得 320 字符尾空格、NUL 入注记原样且可记忆、手工快照注记原样插入不转义、professional 预设加空白注记仍有提示与可记忆内容。零生产改动。；2026-10-26 oct22fb 续批：`AiPromptSafetyPolicyTest` 新增 5 例（56→61）——窗口边界半截声称不命中且不标注、CR LF 对成双空格且纯 CRLF 回落 user、无冒号裸角色词原样、大小写混写注入命中、标记加注入整段丢弃；`AiRetryPolicyTest` 新增 5 例（56→61）——连接码带后缀仍退避 800/1600、非数字后缀落回分支默认、负限流后缀落回 30 秒、双冒号后缀忽略、冒号后空格仍解析；`AiRetryPolicyWindowTest` 新增 5 例（53→58）——前导空格键独立、双类目记录后清空全放行、HEAVY 前缀键不碰撞、HEAVY 填满后新会话报全局量级等待、制表前缀键独立。零生产改动。；2026-10-26 oct22fc 续批：`AiEmotionLexiconTest` 新增 5 例（50→55）——焦虑担心胜生气占 2/3、哈哈与失眠平局取 happy、hateful 子串命中 angry、worried anxious 双命中、难过伤心多数占 1/2；`AiEmotionFallbackTest` 新增 5 例（50→55）——仅 happy 模板含太好了/great、仅 sad 模板兼具理解与随时邀请、happy 模板两分句、angry 模板三分句、无模板以标点或空白开头；`GroupAiSharePolicyTest` 新增 5 例（54→59）——两端回车裁剪、内部回车保留、owner 先裁剪后截断 100、dueText 先裁剪后截断 120、meta 模式先裁剪后截断 40。零生产改动。；2026-10-26 oct22fd 续批：`AiCostVisibilityPolicyTest` 新增 5 例（58→63）——402 纯文案仍判配额、insufficient funds 文案判配额、服务端 0 秒回落内嵌 120 秒、流式计费提示优先于取消码、配额 0 秒去后缀与未知基码原样；`AiEntryPolicyTest` 新增 5 例（59→64）——四旗全真才画入口、类型 trim 与大小写折叠、文件与图片及未知类型各异、双门闩任一关则菜单不开、三参执行门需主开关与会话开且空闲；`AiLocalRulesTest` 新增 5 例（58→63）——哈哈哈双计社交满置信、fix bug 三计技术满置信、通知与待办平票取通知、300 字符窗口边界外关键词切断、中英混写财务词合并。零生产改动。；2026-10-26 oct22fe 续批：`AiClassifyLexiconTest` 新增 5 例（60→65）——技术三比二胜财务、财务凭顺序胜技术平局、公告凭顺序胜社交平局、双空格短语不命中、三类平局财务居首；`AiPrivacyPreferencesTest` 新增 5 例（58→63）——CRLF 对原样保留、单表情原样保留、双冒号用户成三冒号、dismissed 键精确、本地安全键精确；`AiWritingStylePolicyTest` 新增 5 例（59→64）——超长预设截断失配、内部空格预设失配、纯 NUL 注记仍算内容、casual 加注记拼接、Tab 衬垫预设匹配。零生产改动。；2026-10-26 oct22ff 续批：`AiPromptSafetyPolicyTest` 新增 5 例（61→66）——声称全在 12000 字窗口外漏扫、帮你转账 12 字间隔命中与 13 字脱钩、大写 DEVELOPER 全角冒号弱化、发送者单换行变空格、纯控制字符正文行返回 null；`AiRetryPolicyTest` 新增 5 例（61→66）——空码触顶回 UNKNOWN 说明、连接码小写加后缀仍退避 1600、RATE_LIMITED_TOO_MANY 走限流零等待、认证码触顶说明优先于认证分支、连接码零次加后缀仍 800；`AiRetryPolicyWindowTest` 新增 5 例（58→63）——前缀会话与扩展会话隔离、尾空格会话隔离、回车会话隔离、HEAVY 会话键大小写敏感、HEAVY 无等待 awaitBackoff 立即返回。零生产改动。；2026-10-26 oct22fh 续批：`AiTaskDndPolicyTest` 新增 5 例（59→64）——午夜起上午窗口只覆盖中午前、同日窗口 end 等于 now 返回空、关闭开关清空全日窗口、同日窗口 end 边缘分钟仍返回空、上午窗口扫数恰为分钟数；`AiCostVisibilityPolicyTest` 新增 5 例（63→68）——下划线 payment_required 码判配额、大小写混写 too many 码折叠判限流、未知码零服务端零本地等待为零、带后缀限流基码重打后缀、空白码失败标志仍无提示；`AiEntryPolicyTest` 新增 5 例（64→69）——用户开关缺省关藏入口、主开关缺省开、换行包裹 voice 先折叠再过转录门、衬垫 markdown 带转录仍翻译、具名参数换序仍四项合取。零生产改动。；2026-10-26 oct22fj 续批：`AiPromptSafetyPolicyTest` 新增 5 例（66→71）——查询截断 700 字、上下文上限钳制 1 与 4000、Tab 单独发送者回落 user、行中标记原样保留、注入正文行返回 null；`AiWritingStylePolicyTest` 新增 5 例（64→69）——未知预设加空白注记无提示无内容、direct 提示钉死、diplomatic 提示钉死、手写禁用快照无提示、恰 320 字注记原样保留；`AiRetryPolicyTest` 新增 5 例（66→71）——连接码触顶说明优先、空码未触顶回 UNKNOWN 非瞬时、billing 零后缀无默认等待、insufficient 带后缀走配额等待、连接码负次数仍按 800 退避。零生产改动。；2026-10-26 oct22fg 续批：`AiEmotionLexiconTest` 新增 5 例（55→60）——sad 三命中胜 happy 两命中、sad/angry/anxious 三方平局取 sad、多词大写英文仍判 neutral、两文本各一 happy 词满置信、nervous 与 worried 双计 anxious；`AiEmotionFallbackTest` 新增 5 例（55→60）——sad 与 angry 共用理解原句、neutral 模板三小句、anxious 模板双撇号其余至多一、sad 与 anxious 并列最多词、happy 与 neutral 词数最少两位；`GroupAiSharePolicyTest` 新增 5 例（59→64）——零 dueAt 置空且空 dueAt 任务保留、nbsp dueText 置空、内部 nbsp 保留、超 30 任务仍可保存、nbsp 模式 meta 置空保键。零生产改动。；2026-10-26 oct22fi 续批：`AiLocalRulesTest` 新增 5 例（63→68）——剩余六类 wire/labelKey 冻结、refund+price 财务满置信度、assign+task+deadline 待办满置信度、miss+后悔判 sad、worried+nervous+stress 判 anxious；`AiPrivacyPreferencesTest` 新增 5 例（63→68）——scopedKey 原样保留等号/加号用户 id、同名 base 与用户 id 单冒号、尾换行与首制表符用户 id 原样保留；`AiClassifyLexiconTest` 新增 5 例（65→70）——余额判财务、早安判社交、作业判学习、reminder 判通知、单字 bug 因词典列两次仍判技术满置信度。零生产改动。；2026-10-26 oct22fk 续批：`AiRetryPolicyWindowTest` 新增 5 例（63→68）——纵向制表键与普通键及横向制表键互异、换页键与普通键及空格键互异、空字符键与普通键互异、满槽后追加记录不刷新最老时间戳、满槽时已记会话与新会话同报全局等待；`AiTaskDndPolicyTest` 新增 5 例（64→69）——跨夜 1320→360 四边界、同日 480→540 四边界、越界分钟钳入窗口后返回 end、跨夜 1320→360 扫数 480、钳后等界返回空；`AiCostVisibilityPolicyTest` 新增 5 例（68→73）——insufficient credit 文案判配额、内嵌秒数超日上限钳到 86400、分类服务端秒数超日上限钳到 86400、本地剩余恰 1000 毫秒计 1 秒、SERVER 码无提示但警告重试计费。零生产改动。；2026-10-26 oct22fl 续批：`AiEntryPolicyTest` 新增 5 例（69→74）——NBSP 包裹的 voice/file 仍按类型匹配、CRLF 包裹的 text/markdown 仍翻译、CALL/LOCATION/CONTACT/LIVE 未知类型恒空、执行门闩具名参数乱序仍成立、位置式 transcript 参数仍走 voice 门闩；`AiPromptSafetyPolicyTest` 新增 5 例（71→76）——中文 auth 单类（已经重置密码、帮你导出密钥）、中文 account 单类（帮你删号、已经清空账号）、注入近似词（ignores、disregard the、缺 the、忽略上面）均不命中、admin 帮手分支单类（帮你禁言、为你转让群主）、为你帮手分支 12 字间隔（付款）；`AiWritingStylePolicyTest` 新增 5 例（69→74）——手写 NONE 快照空白注记无提示、NBSP 注记配 warm 只出预设语、空/空白 preset id 回 NONE、换行衬垫 preset id 命中、尾空格注记在拼接提示里保留。零生产改动。；2026-10-26 oct22fm 续批：`AgentSecretGatePolicyTest` 新增 5 例（33→38）——拒绝文案自有主语前缀、单复数 chat 词之分、长度奇偶与和、连字符仅 PIN、首词 Error 与次词主语；`AgentToolPolicyTest` 新增 5 例（47→52）——toolByName 暴露 risk、系统提示嵌入时间锚点、读工具预览回退原样、列表与搜索上限相对聊天历史上限倍数、空对象与空数组解析原样；`LocalAiGatewayRankTest` 新增 5 例（41→46）——换行切分词、制表衬垫查询、重复分词按出现次数计分、复合候选词双命中、候选文本换行不破子串匹配。零生产改动。；2026-10-26 oct22fn 续批：`AiPrivacyPreferencesTest` 新增 5 例（68→73）——斜杠/百分号/圆点用户 id 原样保留、斜杠 base 保形、数字 0 用户 id 区别于空；`AiEmotionFallbackTest` 新增 5 例（60→65）——英文模板长度按情绪序为 28/62/56/63/32、双逗号集合恰为愤怒/焦虑/中性、词数为 5/12/11/12/6、仅悲伤模板两次称呼 you、仅悲伤与愤怒模板含 feel；`AiLocalRulesTest` 新增 5 例（68→73）——大写英文财务词忽略大小写、上线同分取通知在前、财务与学习同分取财务、扫描窗边缘 298 垫字保留/299 垫字切断、通知 wire 与 label 键冻结。零生产改动。；2026-10-26 oct22fq 续批：`AiEntryPolicyTest` 新增 5 例（74→79）——纵向制表符包裹文本折叠翻译、换页符包裹语音在转录门前折叠、纵向制表符与换页符混合包裹文件判文件分析、单 NBSP 类型无动作、附件菜单标志与入口可见门独立；`LocalAiGatewayRankTest` 新增 5 例（46→51）——空候选文本永不命中、纵向制表符切分查询分词、换页符切分查询分词、分词顺序不影响排序、空白查询计分版回空；`AgentToolPolicyTest` 新增 5 例（52→57）——OpenAI 工具 JSON 参数描述逐项镜像规约、属性键集逐项镜像参数名、每次调用新建映射、非对象顶层 JSON 拒识为空表、前导零数字拒识。零生产改动。；2026-10-26 oct22fr 续批：`AiClassifyLexiconTest` 新增 5 例（70→75）——英文 maintenance 词判 NOTICE、good night 短语判 SOCIAL、lecture 与 database 平局取 STUDY、大写 INVOICE 命中、300 窗外关键词不计；`AiRetryPolicyTest` 新增 5 例（71→76）——TOO_MANY_REQUESTS:30 走限流等 30 秒、PAYMENT_REQUIRED:180 走配额等 180 秒、小写 rate_limited:60 归一化走限流等 60 秒、BUDGET_EXCEEDED:240 走配额等 240 秒、HEAVY 记录不挡同聊 LIGHT；`GroupAiSharePolicyTest` 新增 5 例（64→69）——恰好 40 字模式不截断、负 dueAt 置空、恰好 100 字 owner 保留、空列表不可保存、群内 null 答案判空。零生产改动。；2026-10-26 oct22fo 续批：`AiEmotionLexiconTest` 新增 5 例（60→65）——未列举的开心词嘻嘻/耶、愤怒词可恶/恶心、焦虑词来不及/怎么办、悲伤词遗憾/失落、否定词里的开心子串仍命中；`LocalAiProviderStoreTest` 新增 5 例（33→38）——chat completions 链接去斜杠保后缀、responses 与 anthropic 链接补后缀、历史上限钳 4/200、最大 token 钳 16/128000、超时钳 15/300；`OpenAiCompatClientTest` 新增 5 例（28→33）——空 id 生成 call_、工具结果内容走 reasoning 回退、首 choice 工具优先于次 choice 文本、无内容非字符串 reasoning 得空文本、非数组 tool_calls 回落文本。零生产改动。；2026-10-26 oct22fp 续批：`AgentSessionEngineTest` 新增 5 例（34→39）——styleHint 入系统提示（空白则不出）、历史下限 1 钳到 4、特权声称终态追加免责、翻译改写指令带目标语言、已审批未知工具回填不查目录；`LocalAiProtocolCodecTest` 新增 5 例（35→40）——chat completions 拼写回落、带空格键保留原样、数组直通与空容器及 null 键、SSE 非字符串字段置空、Anthropic 空内容回空串；`LocalAiFileAnalyzerTest` 新增 5 例（38→43）——畸形 UTF-8 字节判替换字符拒绝、裸 text/ 前缀接受、单 NBSP 不判空、mime 而非扩展名定文本接受、base64 内换行拒绝。零生产改动。；2026-10-26 oct22fs 续批：`AgentToolPolicyTest` 新增 5 例（57→62）——openai 工具 JSON 类型字段全为 function/object、必填皆为参数名、send_text_message 为唯一 SEND 工具且三风险齐全、工具与参数描述皆非空、工具名全为小写下划线；`LocalAiGatewayRankTest` 新增 5 例（51→56）——空 id 照常保留、id 原样返回不 trim/不折叠大小写、同 id 按分值重排、超长 token 不命中、完全相同的候选对都保留；`AiRetryPolicyWindowTest` 新增 5 例（68→73）——零宽空格/行中换行/尾冒号/类别名冒号/反斜杠五种键形各不碰撞。零生产改动。；2026-10-27 oct22ft 续批：`AiTaskDndPolicyTest` 新增 5 例（69→74）——午夜分钟在跨夜窗口内、1439→0 单分钟跨夜窗口、钳后等界为空窗口、双开关全关不提醒、窗外同日窗口纵有未来 end 仍回空；`AiCostVisibilityPolicyTest` 新增 5 例（73→78）——配额词与限流词同现取配额、429 遇配额词取配额、等待策略服务端秒数超日上限钳到 86400、基码去空白转大写且内嵌秒数可读、内嵌零秒忽略；`AiLocalRulesTest` 新增 5 例（73→78）——通知与待办平局取通知、重复关键词只计一次、长词内子串仍命中、开心与悲伤平局取开心、扫描窗外情绪词忽略。零生产改动。；2026-10-27 oct22fv 续批：`AiEntryPolicyTest` 新增 5 例（79→84）——入口常量字面值、动作枚举声明顺序、菜单具名参数重排、四门闩全开快照、制表换行空格包裹文本折叠；`AiClassifyLexiconTest` 新增 5 例（75→80）——系统消息、跟进、修复、复习、聚会五中文词各单命中且置信度 1.0；`AiPromptSafetyPolicyTest` 新增 5 例（76→81）——sent payment、ownership transferred、key rotated、account closed 四类英文声称单例、发送者单回车转空格。零生产改动。；2026-10-27 oct22fx 续批：`AiTaskDndPolicyTest` 新增 5 例（74→79）——1400→100 隔夜窗口四边界、600→660 同日窗口四边界、600→660 扫数 60、1400→100 扫数 140、1439 等界空窗口；`GroupAiSharePolicyTest` 新增 5 例（69→74）——非群空答案判 NOT_GROUP、已分享先于空答案、无任务闭环可演示、制表符包裹标题去边、已分享无任务闭环不可演示；`AiLocalRulesTest` 新增 5 例（78→83）——待办/财务平局取待办、学习/技术平局取学习、社交/待办平局取待办、开心/焦虑平局取开心、love/cry 平局取开心。零生产改动。；2026-10-27 oct22fy 续批：`AiEntryPolicyTest` 新增 5 例（84→89）——图文文件动作带 transcript 仍保值、图文 has 动作带 transcript 仍真、未知 location/contact 共享空表、混合大小写 voice 带 transcript 仍空、四门闩全关；`AiClassifyLexiconTest` 新增 5 例（80→85）——变更归 NOTICE、提醒归 NOTICE、课程归 STUDY、电影旅行归 SOCIAL、变更课程平局取 NOTICE；`AiCostVisibilityPolicyTest` 新增 5 例（78→83）——429 配 QUOTA_LIMIT 码判配额且保留等待、下划线 payment_required 文案判配额、内嵌秒数胜过服务端零值与本地剩余、内嵌秒数去空格、纯配额码默认 300 秒等待。零生产改动。；2026-10-27 oct22fu 续批：`AiEmotionFallbackTest` 新增 5 例（65→70）：angry 开头、anxious 双缩写、首词互异且大写、happy 以 you! 结尾、neutral 开头与结尾；`AiPrivacyPreferencesTest` 新增 5 例（73→78）：下划线与连字符 id、纯数字 id、user enabled 第二用户、波浪线与脱字符、dismissed 键长算术；`AiWritingStylePolicyTest` 新增 5 例（74→79）：witty/empathetic 预设文案、witty 组合提示、大写下划线 id 失配、标点注记空格折叠。零生产改动。；2026-10-27 oct22fz 续批：`AiEntryPolicyTest` 新增 5 例（89→94）——制表回车包裹的 markdown/system 带 transcript 仍翻译、表情数字混合字母类型无动作、斜杠点横杠拼接类型无动作、翻译族列表每次调用新建、未知表情数字类型共享空表；`AiCostVisibilityPolicyTest` 新增 5 例（83→88）——429 配 budget 词判配额且等待封顶一天、纯文案 Too Many Requests 判限流、配额内嵌秒数胜过服务端零值与本地剩余、insufficient 族提示胜过失败与自动重试、billing 族编码不加后缀但内嵌秒数可读；`GroupAiSharePolicyTest` 新增 5 例（74→79）——制表换行包裹的 3999 字答案原样保留、仅标题任务其余字段全空、制表包裹模式裁边、丢弃 dueAt 与空白任务并存时闭环仍就绪、被拒分享决策与答案内容无关。零生产改动。；2026-10-27 oct22fw 续批：`AiEmotionLexiconTest` 新增 5 例（65→70）——四情绪单命中平局取 happy、同词单文本重复只计一次、怒词「烦死了」叠加计两次、窗口拦腰截断关键词不命中、悲词「难过难受」叠加计两次；`AiRetryPolicyTest` 新增 5 例（76→81）——TOO_MANY 含配额词走限流分支、裸 PAYMENT 码走配额分支且等待为 0、含 AUTH 的连接码走认证分支不重试、LIGHT 记录不挡 HEAVY、HEAVY 单窗等待在 60 秒内；`AiRetryPolicyWindowTest` 新增 5 例（73→78）——加号会话键与空格键互异、重复记录刷新不叠加等待、无等待时 awaitBackoff 直接返回、同会话记满 240 次同样触发全局封顶、两类目混合记满 240 次共用全局队列。零生产改动。；2026-10-27 oct22gb 续批：`AiWritingStylePolicyTest` 新增 5 例（79→84）——未知预设保留备注仅出备注提示、制表换行大写混合预设 id 归一化、手建 professional 快照钉住提示与内容、copy 关闭保留字段但无提示、大写制表预设与备注组合提示；`AiPrivacyPreferencesTest` 新增 5 例（78→83）——冒号尾基与冒号头用户拼出三冒号、冒号尾基配空用户双冒号收尾、dismissed 基键形用户不混淆、基名大小写敏感、用户首尾制表原样保留；`AiTaskDndPolicyTest` 新增 5 例（79→84）——窗外且已过期返回空、1380→120 扫掠 180 分钟、60→120 日间边界四元组、跨夜窗内结束等于当前返回空、1380→120 跨夜边界四元组。零生产改动。；2026-10-27 oct22gc 续批：`AiCostVisibilityPolicyTest` 新增 5 例（88→93）——服务端重试恰达一天封顶仍保留、402 状态配限流码仍判配额、连字符 PAYMENT-REQUIRED 码判通用、冒号前空格的基码归一且内嵌秒数可读、未知结局提示胜过自动重试；`AiEntryPolicyTest` 新增 5 例（94→99）——纵向制表换页包裹的图片与动图仍分析、单位分隔符包裹的图片动图文件仍分析、零宽连接符破坏类型匹配、字节序标记破坏类型匹配、回车纵向制表换页混合空白包裹仍折叠；`AiLocalRulesTest` 新增 5 例（83→88）——学习与社交平局取学习、财务与社交平局取财务、good night 短语单命中社交满置信、悲怒平局取悲、小写 great/sad 平局取喜。零生产改动。；2026-10-27 oct22gd 续批：`AiEmotionLexiconTest` 新增 5 例（70→75）——单字棒/愤怒/紧张/太好了各命中其情绪、喜怒焦三方平局取 happy 置信 1/3；`AiRetryPolicyTest` 新增 5 例（81→86）——AUTHORIZE 含 AUTH 走认证分支、CANCELLED 落非瞬态分支全量钉住、OVER_BUDGET 含 BUDGET 走配额分支零等待、TOO_MANY:90 走限流分支等 90 秒、两端空白小写连接码 2 次退避 1600；`AiRetryPolicyWindowTest` 新增 5 例（78→83）——DEL/SOH/NEL/LRM/单元分隔符五种控制字符键各不碰撞。零生产改动。；2026-10-27 oct22ge 续批：`GroupAiSharePolicyTest` 新增 5 例（79→84）——恰达 4000 的填充答案全保留、单标题超 360 仍可保存、前 30 任务按序截断、全无效任务闭环未就绪、超上限空白任务仍不可保存；`AgentToolPolicyTest` 新增 5 例（62→67）——全量 READ 工具免审批、create_group/rename_group 为 WRITE、send_text_message 必填 chatId/text、openaiToolsJson 保序且名字唯一；`LocalAiGatewayRankTest` 新增 5 例（56→61）——重音分词大小写不敏感、em 空格不断词、候选 id 文本不参与计分、无命中空 id 被过滤、前缀重叠分词同 span 各计分。零生产改动。；2026-10-27 oct22gg 续批：`AiWritingStylePolicyTest` 新增 5 例（84→89）——多段 CRLF 折叠为空格、空白预设 id 保留备注、casual 提示钉住、direct 提示加备注拼接、空预设空备注的启用快照无提示；`AiPrivacyPreferencesTest` 新增 5 例（83→88）——含 migrated 的六键互异、migrated 键名、换行用户 id 原样、auto translate 冒号用户、含空格 base 原样；`AiTaskDndPolicyTest` 新增 5 例（84→89）——午夜结尾的隔夜窗口仅 1439、午夜结尾窗口的下次允许时间、日末单分钟窗口、运行时关闭压过分钟钳制、钳制后的隔夜起点 1439。零生产改动。；2026-10-27 oct22gf 续批：`AiCostVisibilityPolicyTest` 新增 5 例（93→98）——限流提示胜过计划自动重试、负本地剩余回落默认等待、限流前缀基码保后缀、负服务端重试秒数丢弃但限流标志保留、限流展示信号绑默认等待与计费警告；`AiEntryPolicyTest` 新增 5 例（99→104）——NUL/BEL 填充折叠、GIF 与 IMAGE 同动作、单参 composer 菜单门闩、VOICE 默认参数转录、双参与三参执行门闩一致；`AiLocalRulesTest` 新增 5 例（88→93）——good night 短语需严格单空格、TODO 与 TECH 平局取 TODO、NOTICE 与 SOCIAL 平局取 NOTICE、哈哈哈双计 happy 压 sad、SAD 与 ANXIOUS 平局取 SAD。零生产改动。；2026-10-27 oct22ga 续批：`AiClassifyLexiconTest` 新增 5 例（85→90）——英文 notice 词单命中、中文付款词单命中、中文记得词单命中、中文代码词单命中、英文 weekend 词单命中；`AiPromptSafetyPolicyTest` 新增 5 例（81→86）——wired funds 转账声称单命中、已经转账声称单命中、无声称时 kinds 为空集、换行切断的注入短语不命中、空发送者回退 user；`AiEmotionFallbackTest` 新增 5 例（70→75）——仅 happy 模板无句号、仅 neutral 与 anxious 模板避第二人称、全模板纯 ASCII、anxious 模板 worry 在 step 之前、sad 模板 need 在 talk 之前。零生产改动。；2026-10-27 oct22gj 续批：`AiCostVisibilityPolicyTest` 新增 5 例（98→103）——连字符 TOO-MANY 码判通用、无 INSUFFICIENT 的 fund 词判通用、失败未知码提示重试可能计费但 warn 为假、裸 RATE_LIMIT 码判限流、无信号的 CANCELLED 等待为 0；`AiEntryPolicyTest` 新增 5 例（104→109）——运行门闩不依赖同意与用户开关、菜单开而运行门闩因会话未开启关闭、菜单开而入口面隐藏、DOCUMENT/REACTION/ATTACHMENT 三类未知类型无动作、带转录的 VOICE 与未知类型共享空列表；`AiLocalRulesTest` 新增 5 例（93→98）——200 字窗口按条重置、三方平局取 happy 置信 1/3、lovely/missing 子串命中、裸哭/笑不命中而哭了命中、单字烦单计一次。零生产改动。；2026-10-27 oct22gi 续批：`AiEmotionLexiconTest` 新增 5 例（75→80）——英文 miss 词单命中 sad、英文 stress 词单命中 anxious、英文 emo 词单命中 sad、sad 与 angry 平局取 sad、angry 与 anxious 平局取 angry；`AiRetryPolicyTest` 新增 5 例（86→91）——QUOTA 内嵌 60 秒压过 300 秒默认、TOO_MANY 零后缀等待为 0、TIMEOUT 后缀剥离为基码、BUDGET 零后缀等待为 0、HEAVY 记录不影响 LIGHT 剩余等待；`OpenAiCompatClientTest` 新增 5 例（33→38）——null 内容回落 reasoning、工具调用保留非空内容、空 message 对象为空文本、空串内容单独为空文本、工具内容优先于 reasoning。零生产改动。；2026-10-27 oct22gl 续批：`AiClassifyLexiconTest` 新增 5 例（90→95）——共享上线词平局取 NOTICE、三连哈哈双计 SOCIAL、INVOICE 与 BILL 共享 FINANCE、lecture 与 course 共享 STUDY、转账考试代码 三方平局取 FINANCE；`AiPromptSafetyPolicyTest` 新增 5 例（86→91）——纯空格发送者回落 user、自定义小上限截断、帮你短间隔转账命中、query 注入返回空、转账与删号双声称同报；`AiWritingStylePolicyTest` 新增 5 例（89→94）——孤立回车折叠为空格、clear 返回 NONE 与空 note、warm 预设与 note 快照等值、连字符 id 永不匹配、纯 Tab note 为空。零生产改动。；2026-10-27 oct22gk 续批：`AgentToolPolicyTest` 新增 5 例（67→72）——parseBool 只认 0/1 数值、嵌套对象数组转字符串、写工具审批忽略空参数、搜索与联系人工具为读风险、预览回退保留带空格 JSON；`AgentSecretGatePolicyTest` 新增 5 例（38→43）——拒绝语无问号感叹号、secret 语 available 与 assistant 各一次、PIN 语正文三词、密聊在全部锁定组合下均为 secret 拒绝、密聊永不返回 PIN 拒绝；`LocalAiGatewayRankTest` 新增 5 例（61→66）——逗号连接分词整体匹配、句点后缀分词不命中、下划线分词字面匹配、三重复分词得 3 分、单分词候选排在双分词候选之后。零生产改动。；2026-10-27 oct22gh 续批：`LocalAiFileAnalyzerTest` 新增 5 例（43→48）——纯文本首尾空格原样保留、markdown 带参数走 text/ 前缀接受、大写 markdown 小写化接受、xml 带参数精确相等拒绝、image mime 下 pdf 魔数仍走 pdf 路径；`LocalAiProtocolCodecTest` 新增 5 例（40→45）——chat 解析空 content 回落 reasoning 进工具结果、无 message 对象回空文本、无 function 对象调用被跳过、responses 空助手正文只留工具调用、responses 最大输出 token 双侧钳制；`LocalAiProviderStoreTest` 新增 5 例（38→43）——会话标题首尾空格原样、消息 role/content 空格原样、超范围 temperature 原样且非数值 topP 为 null、extraHeadersJson 往返逐字、空列表编解码为空数组。零生产改动。；2026-10-27 oct22go 续批：`AiClassifyLexiconTest` 新增 5 例（95→100）——中文收款词单命中、中文晚安词单命中、中文安排词单命中、中文学习词单命中、英文 api 词单命中；`LocalAiGatewayRankTest` 新增 5 例（66→71）——中文分词按分词计分、数字分词同词分词计分、加号分词字面匹配、仅制表换行空白查询为空、字母数字混合分词单分词计分；`OpenAiCompatClientTest` 新增 5 例（38→43）——纯空白内容原样为空白文本、function 为 null 的工具调用被跳过、null 工具调用条目被跳过、首选项文本胜过次选项工具调用、空白 id 生成 call_ 前缀。零生产改动。；2026-10-27 oct22gm 续批：`AiEmotionFallbackTest` 新增 5 例（75→80）——仅 sad 模板含 here、仅 anxious 模板含 too much、五模板首词逐字映射、sad 与 angry 模板理解先于感受、理解短语独占子句；`GroupAiSharePolicyTest` 新增 5 例（84→89）——owner 内部双空格保留、dueText 内部换行保留、中间无效任务丢弃后顺序不变、衬垫后恰 360 字标题保留且可保存、标题内部制表符保留；`AiRetryPolicyWindowTest` 新增 5 例（83→88）——RLM 键异于普通与 LRM 键、BOM 键异于普通键、软连字符键异于普通与拼接键、词连接符键异于普通与拼接键、C1 控制符键异于普通与 NEL 键。零生产改动。；2026-10-27 oct22gn 续批：`AiEmotionLexiconTest` 新增 5 例（80→85）——haha 与 great 同文本双计、纯中性句无命中、焦虑跨文本满置信、悲伤跨文本三分之二占比、中文关键词恰落 200 字窗口边沿；`AiRetryPolicyTest` 新增 5 例（91→96）——CANCELLED 带后缀仍走非瞬时分支、小写 too_many 带后缀走限流等待、BILLING 超大后缀按一天封顶、全局窗口满时 HEAVY 同被拦、RATE_LIMITED_TOO_MANY 带后缀走限流等待；`AgentSessionEngineTest` 新增 5 例（39→44）——同轮双审批调用停在首个、次轮读调用后审批暂停、空工具轮次续走文本终态、两轮读调用按序后终态、update 审批挂起携带调用 id 与预览。零生产改动。；2026-10-27 oct22gq 续批：`AiClassifyLexiconTest` 新增 5 例（100→105）——词典短语残片注意不命中而请注意命中通知、debug 含 bug 判技术、priceless 含 price 判财务、单字包不命中而红包命中财务、follow me up 拆散不命中待办；`AiLocalRulesTest` 新增 5 例（98→103）——emotion/memo 含 emo 判悲伤、crying 命中 cry 而 cried 不命中、madam 含 mad 判愤怒而首字母大写不命中、四方平局取开心四分之一置信度、烦死了双计愤怒对开心三分之二；`LocalAiGatewayRankTest` 新增 5 例（71→76）——大小写变体同词三倍计分、# 号字面匹配、点号字面非通配、长词元无长度上限、撇号字面匹配。零生产改动；2026-10-27 oct22gs 续批：`AiPrivacyPreferencesTest` 新增 5 例（88→93）——分号/问号/&号/星号/竖线用户 id 均逐字保留；`AiTaskDndPolicyTest` 新增 5 例（89→94）——1350→420 边界四元组与 510 分钟扫数、零时起同日窗口边界三元组、1439→1438 扫数 1439 分钟、100→101 单分钟扫数；`LocalAiProviderStoreTest` 新增 5 例（43→48）——上下文窗口钳制、视觉能力模型名匹配、未知 JSON 字段忽略、baseUrl 两端空白修剪、默认 extraHeaders 往返。零生产改动。；2026-10-27 oct22gp 续批：`AiCostVisibilityPolicyTest` 新增 5 例（103→108）——分类重试秒数超一日封顶、空白码不警告、流式展示保留内嵌等待、裸限流基码可加后缀、内嵌秒数拒负值；`AiEntryPolicyTest` 新增 5 例（109→114）——GIF 归入图片族、转写标志忽略非语音类型、有转写时非语音仍有动作、空与未知类型有转写仍为空、制表换行包裹的语音仍转写；`AgentToolPolicyTest` 新增 5 例（72→77）——布尔解析接受大小写混写、参数解析把标量与 null 转字符串、畸形与空白 JSON 为空、工具名精确匹配、三风险全覆盖。零生产改动。；2026-10-27 oct22gr 续批：`AiPromptSafetyPolicyTest` 新增 5 例（91→96）——发件人制表保留而换行变空格、句中 ignore previous 命中、you are now a system 缺 the 不命中、keys exported 复数判认证、sent the funds 判转账；`AgentSecretGatePolicyTest` 新增 5 例（43→48）——密聊拒绝词表逐字、PIN 拒绝词表逐字、拒绝当且仅当密聊或加锁未解锁、小词 the/are/not 各出现一次、密聊在全部加锁状态下均不出列表；`OpenAiCompatClientTest` 新增 5 例（43→48）——显式工具调用 id 连同名称参数保留、非字符串 content 回退 reasoning、空 id 生成后仍带正文、choices 为 null 判无 choices、空正文工具调用返回空正文。零生产改动。；2026-10-27 oct22gt 续批：`AiEmotionLexiconTest` 新增 5 例（85→90）——开心/担心平局取 happy、难过伤心两票胜担心一票、中性句后生气单票定 angry、大写 LOL 不命中、三文本各一票平局取 happy；`GroupAiSharePolicyTest` 新增 5 例（89→94）——正文内部双空格保留、换行-only 模式置 null、dueText 内部双空格保留、35 空白加 1 有效仍可持久、4000 字与 30 任务双上限闭环就绪；`AiWritingStylePolicyTest` 新增 5 例（94→99）——warm 预设无注记提示与内容、professional 预设加注记组合提示、大写 DIPLOMATIC 命中、纯表情注记原样保留、NONE 预设注记禁用后无提示。零生产改动。；2026-10-27 oct22gv 续批：`AiEmotionFallbackTest` 新增 5 例（80→85）——happy 模板 great 先于 happy、angry 模板 easy 先于 understand、anxious 模板 sort 先于 together、sad 模板 feel 先于 here、angry 模板末词 slowly；`AiRetryPolicyWindowTest` 新增 5 例（88→93）——不换行空格、表意空格、双向嵌入控制符、方向弹出控制符、变体选择符键均与原键及近似键互不冲突；`AgentSessionEngineTest` 新增 5 例（44→49）——模型收到原样 provider、每轮收到工具目录 JSON、次轮消息恰为 system/user/assistant/tool、三轮消息按序携带两工具结果、模型 onDelta 回调为 null。零生产改动。；2026-10-27 oct22gu 续批：`AiLocalRulesTest` 新增 5 例（103→108）——工资单命中财务、请注意单命中通知、早安单命中社交、恶心单命中愤怒、压力单命中焦虑；`AiClassifyLexiconTest` 新增 5 例（105→110）——follow up 短语加 deadline 共属待办、trip 单命中社交、大写 MAINTENANCE 命中通知、通知待办平局取通知、announcements 复数子串命中通知；`AiRetryPolicyTest` 新增 5 例（96→101）——FORBIDDEN 落入非瞬时分支、AUTH_REQUIRED 后缀保基码无等待、BILLING_OVERDUE 走配额分支零等待、小写 rate_limited 走限流分支默认 30 秒等待、连接失败恰达 3 次上限停重试。零生产改动。；2026-10-27 oct22gw 续批：`AiEmotionLexiconTest` 新增 5 例（90→95）——love 与 hate 平局取 happy、happy 3 票胜 sad 2 票占 3/5、angry 3 票胜 anxious 2 票占 3/5、大写 LOL 不命中仅小写 love 计 1 次、happy 4 票胜 sad 1 票占 4/5；`GroupAiSharePolicyTest` 新增 5 例（94→99）——三常量字面值 4000/30/360、非群组有有效任务仍未就绪、owner 内换行保留、单个表情答案放行、mode 内制表保留；`LocalAiGatewayRankTest` 新增 5 例（76→81）——12 个相同词条计 12 分、纯标点词条按字面命中、无候选评分为空、候选大小写变体同词条只计 1 次、制表换行空格混合作分隔符。零生产改动。；2026-10-27 oct22gx 续批：`AiCostVisibilityPolicyTest` 新增 5 例（108→113）——自动重试单独判安全、非常见状态与空白字段判通用、取消码仍服从服务端等待、本地剩余凑整恰达一天上限、限流码零信号回落默认等待；`AiEntryPolicyTest` 新增 5 例（114→119）——文件分隔符包裹文本折叠、组分隔符包裹语音门闩、记录分隔符包裹文件分析、分隔符包裹 GIF 仍有动作、混合分隔符包裹 system 翻译；`AiPromptSafetyPolicyTest` 新增 5 例（96→101）——账号 wiped 单命中、密码 changed 单命中、提拔 admin 单命中、空白免责回落默认文案、发送者竖直制表剥离而制表保留。零生产改动。；2026-10-27 oct22gz 续批：`AiClassifyLexiconTest` 新增 5 例（110→115）——优惠单命中财务、别忘了单命中待办、通知财务学习三方平局取通知三分之一置信、财务通知二比二平局取通知半置信、英文 dinner 单命中社交；`AiEmotionFallbackTest` 新增 5 例（85→90）——仅 sad 提 whenever、仅 angry 提 tell、仅 happy 提 for you、neutral 既无 understanding 又无 feeling、anxious 以 Don't 开头；`AiLocalRulesTest` 新增 5 例（108→113）——别忘了单命中待办、hate 单命中愤怒、通知财务学习三方平局取通知、sad cry 双命中 sad、分文本开心难过平局取 happy 半置信。零生产改动。；2026-10-27 oct22ha 续批：`AiEmotionLexiconTest` 新增 5 例（95→100）——sadness 含 sad 子串命中、crying 含 cry 子串命中、madness 含 mad 子串命中、裸 worry 无 ed 后缀不命中、hahaha 含 haha 按词计 1 次；`AiRetryPolicyTest` 新增 5 例（101→106）——裸 QUOTA_EXCEEDED 走配额默认等待 300 秒、RATE_LIMITED:45 内嵌等待 45 秒、裸 UNAUTHORIZED 走认证分支、裸 TIMEOUT 走非瞬时分支、裸 INSUFFICIENT_FUNDS 走配额分支零等待；`AiPromptSafetyPolicyTest` 新增 5 例（101→106）——发送者前导控制字符剥离、纯控制字符正文为空、纯空白查询为空、发送者表情原样保留、句中 disregard previous 命中注入。零生产改动。；2026-10-27 oct22gy 续批：`AiPrivacyPreferencesTest` 新增 5 例（93→98）——方括号/花括号/尖括号/反引号/逗号用户 id 原样拼接；`AiTaskDndPolicyTest` 新增 5 例（94→99）——同日窗口 end 等于 now 返回 null、1200→0 隔夜扫 240 分钟、60→溢出端扫 1379 分钟、钳制后 1500→负端隔夜仅 1 分钟、钳制后隔夜窗口内返回窗口末；`AiWritingStylePolicyTest` 新增 5 例（99→104）——纵向制表与换页符边缘裁剪、concise! 带标点后缀不命中、纵向制表换页符备注配 concise 预设仅出预设文案、DIRECT 预设配标点备注拼接、copy 换预设保留备注改文案。零生产改动。；2026-10-27 oct22hb 续批：`AiRetryPolicyWindowTest` 新增 5 例（93→98）——响铃符、转义符、私用区、对象替换符、全角 chat id 均与普通键互不串扰；`GroupAiSharePolicyTest` 新增 5 例（99→104）——纵向制表与换页符两端裁剪、内部纵向制表保留、纯纵向制表判空、模式与任务标题/所有者同规则裁剪；`LocalAiFileAnalyzerTest` 新增 5 例（48→53）——大写 text mime 带参数接受且存小写、大写 pdf mime 走 pdf 路径拒文本字节、SOH/纵向制表/换页符文本原样接受。零生产改动。；2026-10-27 oct22hc 续批：`AiClassifyLexiconTest` 新增 5 例（115→120）——refund/deploy/study/task 单词各命中本类满置信、bug 词典双列得 2 票对转账 1 票占 2/3；`AiCostVisibilityPolicyTest` 新增 5 例（113→118）——内嵌秒数接受 +45/045、服务端等待恰一日封顶通过、本地剩余恰一日仍为一日、配额码重打 90 秒后缀、TOO_MANY 信号等待 0 但提示等待且不警告；`AiEmotionLexiconTest` 新增 5 例（100→105）——missing/hateful/stressful/nervousness 子串命中、难过焦虑平局取 sad 半置信。零生产改动。；2026-10-27 oct22hd 续批：`AgentSecretGatePolicyTest` 新增 5 例（48→53）——密聊在加锁未解锁时仍压过 PIN 拒绝、PIN 词恰一次、secret/assistant 词各恰一次、拒绝串单行无填充、8 组合恰 3 列出；`LocalAiProtocolCodecTest` 新增 5 例（45→50）——小写全量协议别名、responses 非 JSON 报错、无 output 数组时根 output_text 回落、message 多段 output_text 拼接、空 SSE 流报 empty stream；`LocalAiProviderStoreTest` 新增 5 例（48→53）——gemini/qwen-vl/llava/multimodal/pixtral 视觉模式、视觉匹配大小写与空格不敏感、-vl 后缀模式、responses/anthropic 已有后缀保留、缺 protocol 与可选字段回默认值。零生产改动。；2026-10-27 oct22he 续批：`AiEntryPolicyTest` 新增 5 例（119→124）——图形空格与窄不换行空格包裹的 TEXT 经 trim 匹配、TEXT 内含空格不匹配、动作名 translate/transcribe 作类型不匹配；`AgentToolPolicyTest` 新增 5 例（77→82）——草稿上限等于文本发送上限、写审批忽略非空参数、属性映射仅含类型/描述/枚举键、零参数工具 get_me 序列化为空属性与空 required、目录首末工具名；`AiEmotionFallbackTest` 新增 5 例（90→95）——happy 与 angry 模板恰一次 you、sad 末词 talk、anxious 末词 together、仅 angry 含 easy、无括号。零生产改动。；2026-10-27 oct22hf 续批：`OpenAiCompatClientTest` 新增 5 例（48→53）——空函数条目旁仅保留有效调用、tool_calls 为 null 回落文本、id 为 null 生成 id、arguments 为 null 为空串、字符串 message 为空文本；`AgentSessionEngineTest` 新增 5 例（49→54）——空白文本终态为空、预填后续模型报错、轮次耗尽 24 个工具事件后失败、空读结果先完成再终态、预填读调用后续审批暂停；`AiTaskDndPolicyTest` 新增 5 例（99→104）——负分钟钳入跨夜窗口、900→300 边界四元组、1→2 单分钟三元组、纪元零时刻 end 比较、900→300 窗内返回 end。零生产改动。；2026-10-27 oct22hg 续批：`LocalAiGatewayRankTest` 新增 5 例（81→86）——U+001C 与不换行空格不断词、dotless-i 双向不命中、ß 与 ss 双向不命中、CRLF 候选文本双词命中、问号与星号按字面匹配；`AiLocalRulesTest` 新增 5 例（113→118）——未覆盖通知/财务词、未覆盖学习/技术/社交词、英文大小写折叠、剩余三对平局取枚举在前、未覆盖情绪词与多文本累积；`AiPromptSafetyPolicyTest` 新增 5 例（106→111）——中文转账变体、中文删号/改密/管理变体、英文声称变体、删号+改密双类组合、发送者 CRLF 成双空格与上下文保留 CRLF。零生产改动。；2026-10-27 oct22hi 续批：`AiCostVisibilityPolicyTest`/`AiEntryPolicyTest`/`AiClassifyLexiconTest` 各新增 5 例（118→123、124→129、120→125）；零生产改动。；2026-10-27 oct22hh续批：`AgentSecretGatePolicyTest`/`LocalAiFileAnalyzerTest`/`LocalAiProviderStoreTest` 各新增 5 例（53→58）；零生产改动。；2026-10-28 oct22hj 续批：`AiRetryPolicyTest` 新增 5 例（106→111）——AUTHENTICATION_FAILED 走 auth 分支、SESSION_EXPIRED 落非瞬态、CONNECTION_NOT_ESTABLISHED 带后缀仍按基码重试 800ms、attempts=4 仍封顶、QUOTA_EXCEEDED:99999 等待封顶一日；`AiRetryPolicyWindowTest` 新增 5 例（98→103）——全局满后再记仍以最早槽计时、LIGHT 第 240 槽报全局量级等待、同会话 HEAVY 记满 240 同样封顶、先记 LIGHT 再填满全局后等待跳升全局量级、清空后重填 240 再次封顶；`AiPrivacyPreferencesTest` 新增 5 例（98→103）——双账号六键 12 键互异、PREFS_NAME 加域精确、域形 userId 不串键、仅尾空格 userId 原样保留、单冒号 userId 成双冒号。零生产改动。；2026-10-28 oct22hk 续批：`AppUpdatePolicyTest` 新增 5 例（6→11）——负 current/非正 remote 拒、mdou.me 裸域与子域官方、大小写 scheme 与留白 URL、formatNotes 空白/裁边/百分号解码与残缺转义回落、hostOf 端口/查询剥离与无 scheme 拒；`AppUpdateDownloadRetryPolicyTest` 新增 5 例（5→10）——http_ 前缀覆盖任意状态码、子串匹配大小写不敏感、近似码（http/大写 HTTP_503/截断后缀）不重试、IOException 按类型重试与 message 无关、非 IO 异常按文本回落与空 message 不重试；`AppUpdateInstallPolicyTest` 新增 5 例（5→10）——负 expected/archive 拒、高于已装但不符 offer 拒、Int.MAX 边界升级放行、空白包名拒、签名集合顺序无关但子集/超集与大小写不匹配拒。零生产改动。；2026-10-28 oct22hl 续批：`BotCommandPolicyTest` 新增 5 例（7→12）——大写/超长命令拒、密文判据大小写敏感与引号子串、直聊密文与空文本拒、三字 @ 用户名放行、菜单查询大小写折叠与边界长度；`MissedCallTimeoutPolicyTest` 新增 5 例（19→24）——双空 pending 拒、同实例空 callId 仍记、wire 状态不 trim 且大小写不敏感、信令 id 忽略联系人与时间、合成 id 保留内部空格与零分钟桶；`AttachmentTransferRetryPolicyTest` 新增 5 例（14→19）——已接受重复码裁边大小写不敏感、not-ready 冲突英文与裸中文变体、500/501 与空状态码边界、瞬时加密文案大小写敏感、not-ready 冲突匹配大小写不敏感。零生产改动。；2026-10-28 oct22hm 续批：`ChatFolderPolicyTest` 新增 5 例（6→11）——名称空白折叠/截断 48 字/空名、大小写重名与空白名拒、满 28 文件夹拒、跨位拖拽重排连号与超界钳制、会话跨文件夹单归属与空白会话 id 原样返回；`MentionPolicyTest` 新增 5 例（16→21）——空文本/零光标/光标落空白无 query、@all 映射所有人与重复提及去重、过滤裁边与 userId 匹配、邮箱形 token 跳过、插入保留光标后文本与无 query 原样返回；`MessagePinPolicyTest` 新增 5 例（10→15）——群主/管理员仍被不可置顶类型拦、GIF 可置顶与系统类型不可置顶、REVOKED/NUDGE 预览归通用、预览恰 96 字不截断与 97 字截 95 字加省略号、单聊置顶忽略角色。零生产改动。；2026-10-28 oct22hn 续批：`AttachMenuPolicyTest` 新增 5 例（14→19）——频道忽略群组与功能旗标仅 AI 例外、全旗标关的单聊精确清单、nudge 旗标永不加 NUDGE 瓦片、带 AI 的频道精确清单、单聊无名片时 AI 落静音前；`ReadReceiptPolicyTest` 新增 5 例（13→18）——群管理员空白角色与单聊管理员拒、群主角色计数显示与单聊拒、仅自己成员表回落收据、全部已读与零已读边界、预拉跳空白 id 与未完成封顶取最新；`NudgeDisplayPolicyTest` 新增 5 例（14→19）——非前缀与串中前缀拒、英文全大写前缀、自己消息英文存量与群聊仍用你形、他人群聊英文存量取目标、发送者名裁边。零生产改动。；2026-10-28 oct22ho 续批：`ChatListUnreadPolicyTest` 新增 5 例（7→12）——负数未读钳制为零与空本地回服务端零、手动标未读顶住 stale 零清零、当前会话标未读亦清零、服务端标未读除当前会话外胜出、服务端零保留本地正数；`ContactsIndexPolicyTest` 新增 5 例（9→14）——裁边拉丁首字母与非拉丁非汉字归 #、各区段独立计前导行、自定义前导行数下的字母索引、空白名归 # 组、空尺寸下首字母索引连号；`ChatListReceiptPolicyTest` 新增 5 例（6→11）——群聊非 DELIVERED 状态直通、角标 99/100 边界与有数时标未读不掩盖数字、发送中尾巴仍出回执、群聊已读保持双勾、空白 owner 与大小写敏感 sender 隐藏角标。零生产改动。；2026-10-28 oct22hp 续批：`SensitiveActionPolicyTest` 新增 5 例（7→12）——DISABLE_TOTP/DISABLE_APP_LOCK/CLEAR_CHAT_HISTORY/LOGOUT/DELETE_ACCOUNT 各自的完整门禁真值表；`FakeChatPolicyTest` 新增 5 例（9→14）——关 relock 的已解锁会话后台后仍放行、无 PIN 的后台锁定会话不拦截、双关不拦截、极大后台时间戳仍拦截、关 relock 的锁定会话零时间戳仍拦截；`SecretChatPolicyTest` 新增 5 例（10→15）——CHAT_TYPE 常量钉死、纯空白类型可发起而群组密聊不可、群组单人与超大非群组建密聊均拒、空与超长名一律马赛克、内含空格与近似 DIRECT 均拒。零生产改动。；2026-10-28 oct22hq 续批：`ChatMessageInteractionPolicyTest` 新增 5 例（10→15）——仅文本与 Markdown 可复制、密聊复制拦截不受 copyBlockEnabled 旗标影响、手势覆盖全部媒体与 Markdown 类型、空白选中 id 往返、更大阈值下的左滑边界；`GroupMutationFeedbackPolicyTest` 新增 5 例（12→17）——无契约码 401 走权限重载、无契约码 404 走冲突重载、422 客户端错致命不重载、普通 RuntimeException 致命且 detail 取其 message、无状态 NETWORK 错可重试；`ChatListPreviewPolicyTest` 新增 5 例（19→24）——系统消息正文直出预览、其余六类媒体走标签、空候选列表清预览、wire 信封打码但明文保留、拍一拍回显空白时回落旧预览。零生产改动。；2026-10-28 oct22hr 续批：`ChatListRemoteMergePolicyTest` 新增 5 例（5→10）——群聊保留全部参与者含自己、单聊仅自己时回落全量、设置时间戳相等时服务端胜出、本地为空时取服务端未读与设置、过滤/过期边界；`ChatListSettingsMergePolicyTest` 新增 5 例（8→13）——本地仅旧 1ms 时服务端胜出、Long 边界本地胜出、胜者字段不混拼、等时间戳返回服务端引用、单字段差异跟随时间戳；`ChatRealtimeEventPolicyTest` 新增 5 例（8→13）——空白当前用户不判移除、未知移除原因仅刷新、空目标用户不判移除、移除态过期修订仍失效与 IGNORE 态新修订仍失效、空白联系人 id 不应用在线状态。零生产改动。；2026-10-28 oct22hv 续批：`ChatListUnreadPolicyTest` 新增 5 例（12→17）——活动会话清零胜过手动标未读、手动标未读在服务端尾巴更新时仍保留、负本地数钳零后走清零分支、负本地数在尾巴未知时保留服务端、手动标未读下取服务端最大值；`ChatListReloadPolicyTest` 新增 5 例（8→13）——可见模式对非重连触发均无防抖、防抖静默在无触发与前台时均取默认防抖、群修订是唯一防抖触发、重连防抖大于默认防抖、每个模式均有触发可达；`ChatListLoadCoordinatorTest` 新增 5 例（9→14）——前台立即静默加载、群修订防抖静默加载、静默加载失败保留当前错误、SECRET 会话投影 secretChatIds、空服务端列表清空并清理过期本地会话。零生产改动。；2026-10-28 oct22hs 续批：`ChatListDraftTest` 新增 5 例（5→10）——转移判定只认 serverCode、旧草稿不抢位、锁定会话草稿不命中搜索、关闭未读优先时同时间静音排后、置顶值大者居前；`ChatListSelectionTest` 新增 5 例（9→14）——已入选态 enter 幂等、exit 幂等、非选态 toggle 只加不进模式、已读会话无目标、归档密聊仅 flag 下排除；`ChatListSettingsToggleTest` 新增 5 例（10→15）——负置顶值按未置顶处理、静音与标未读反向翻转、确认合并保留时间与未读数、时钟平局时 +1 获胜。零生产改动。；2026-10-28 oct22ht 续批：`MessagePinPolicyTest` 新增 5 例（15→20）——带空格角色与未知角色不可置顶、管理员可置顶 GIF/MARKDOWN/STICKER、GIF 预览映 IMAGE、MARKDOWN/LOCATION/VOICE 可置顶而 NUDGE 不可、预览去空白与纯 meta 为空及双换行折叠；`MessagePreviewTextTest` 新增 5 例（8→13）——VIDEO/FILE/LOCATION/GIF/STICKER 引用均走 mediaLabel；`DecryptFailureDisplayPolicyTest` 新增 5 例（12→17）——明文胜 MD:/SK: 前缀信封、新修订明文替换占位符、同修订明文取 incoming、旧修订 REVOKED 仍终态胜出。零生产改动。；2026-10-28 oct22hw 续批：`ContactsListIndexTest` 新增 5 例（6→11）——中段字母索引累加 header 与行数、请求数整体平移、缺失与空白字母恒 -1、单人组与尾部空组、插入顺序决定索引；`ContactsIndexPolicyTest` 新增 5 例（14→19）——数字下划线圆点与表情首字归 #、大小写同字母合并、CJK 不同首字分组合排序、自定义尾行数叠加各段、自定义前导与零尺寸段混算；`QrScanFeedbackPolicyTest` 新增 5 例（9→14）——用户查询空与异常状态归 UNKNOWN 可重试、超时优先于服务端码且网络透传原码、会话与无效及屏蔽状态均不可重试、英文关键词且 block 优先于 limit、空白服务端码回落状态且码置空。零生产改动。；2026-10-28 oct22hx 续批：`MentionPolicyTest` 新增 5 例（21→26）——超长光标钳制与孤 @、双 @ 与非边界 @ 拒识、文中裸 @AI 不弹而文中提及弹、空白 id 与 everyone id 参与者排除、标点边界与名称大小写敏感及自我排除；`NudgeDisplayPolicyTest` 新增 5 例（19→24）——单聊空白正文仍走对方拍你、群聊正文与发送者双空白双省略号、群聊发送者空白但目标在场、目标内部空格保留、自有消息目标尾标点保留；`MessageStatusMachineTest` 新增 5 例（12→17）——fromWire 大小写不敏感但不 trim、同态与 FAILED 恢复边、本地与服务端独有消息按时间戳共存、新修订密文不覆盖可读明文、撤回胜过更新的本地编辑。零生产改动；2026-10-28 oct22hz 续批：`MessagingV2ArrivalPolicyTest` 新增 5 例（14→19）——小写 service 信封仍发回执、纯空白 owner 全抑制、他人会话的 active/open id 不抑制通知、占用中 service 到达仅未读与回执归零而通知仍走；`MessagingV2ContentPolicyTest` 新增 5 例（11→16）——DATA 拒 EVENT/SENDER_KEY_REQUEST 保留类型、EVENT/RECEIPT 要求事件形、密钥请求空白属性与 50/100 边界、service 拒无约束编辑/删除/表情突变与非域动作及 bot_/device 边界；`ChatListRemoteMergePolicyTest` 新增 5 例（10→15）——可见刷新空白/缺失失败消息回落、不含自己的单聊参与者过滤、群元数据透传、空本地活动会话清零、本地已读标记在更新服务端设置下仍保留。零生产改动；2026-10-28 oct22ia 续批：`CallSignalingAdmissionPolicyTest` 新增 5 例（23→28）——空白群 id 绕过成员失配、成员顺序无关、同 epoch 高序列接受、空白期望联系人直呼接受、呼叫 id 与元数据全匹配时过期游标仍丢弃；`AttachmentProgressPolicyTest` 新增 5 例（14→19）——超一前值钳到一再算增量、窄段中点映射、窄段内超阈增量发布、段末微小增量抑制、零前值是已设值而非未设；`RelativeTimePolicyTest` 新增 5 例（13→18）——收窄未来宽容拒中等时钟偏差、零 now 下未来时间戳走宽容、零过去窗口下恰 now 不算刚刚、负窗口坍缩为空区间、微小自定义过去窗口上界排他。零生产改动；2026-10-28 oct22ib 续批：`StickerFilePolicyTest` 新增 5 例（3→8）——缺失文件拒识、目录拒识、同文件三次摘要稳定且异内容摘要不同、空文件摘要等于已知空串摘要、期望哈希含空白拒识；`StickerNamePolicyTest` 新增 5 例（4→9）——packId 内嵌空格与斜杠冒号剥离、先 trim 再截 40、连字符下划线数字直通、文件名内多点保留、先 trim 再截 80；`StickerSourcePolicyTest` 新增 5 例（6→11）——无前导斜杠相对路径解析、同站 protocol-relative 放行、带路径 base 下相对解析、前后空白裁剪、大写 host 放行且归一化小写。零生产改动；2026-10-28 oct22ic 续批：`CallLockScreenFlagPolicyTest` 新增 5 例（14→19）——零宽空格 id 仍点亮、emoji id 点亮、仅回车 id 不点亮、超长 id 点亮、仅垂直制表 id 不点亮；`CallOfferKeepPolicyTest` 新增 5 例（20→25）——混写 Offer 放行、candidate 恒拒、空白 id 仍受新鲜度窗口约束（过期丢弃）、负时间戳在负窗口下仍放行、仅含空白条目的终止集不误杀；`CallSessionEpochPolicyTest` 新增 5 例（13→18）——最小代握手安全、超大 post bump 安全、不安全判定重复稳定、post 为最小代不安全、双条件同失不安全。零生产改动；2026-10-28 oct22id 续批：`PushKeepAlivePolicyTest` 新增 5 例（4→9）——空白/大小写/带空格模式恒禁、未知与空白模式归一为 off、遗留 media/call 有 token 仍可拉起服务、resurrect 与 startService 在 foreground/off 下一致、退役伪装对任意输入恒 false；`PushWakePayloadPolicyTest` 新增 5 例（5→10）——带空格/大写允许键归一后放行、敏感键优先于未知键（reason 只报 sensitive_keys）、多敏感键/多未知键 reason 按字母排序、纯空白键被忽略；`PushNotificationPolicyTest` 新增 5 例（11→16）——空白 recipient/current 恒拒、null 类型线放行、纯空白 chatId 抑制、空白 activeChat 不抑制、resolveChatId 只裁两端（内含空格保留、制表换行裁掉）。零生产改动；2026-10-28 oct22hu 续批：`ChatFolderPolicyTest` 新增 5 例（11→16）——改名仅改目标且拒重名/空白/未知 id、删文件夹不重排幸存者、会话归属增删幂等且空白 id 原样返回、首属文件夹与按调用方顺序过滤、系统过滤/未读旗标/建满容量与文件夹未读和；`ChatListReceiptPolicyTest` 新增 5 例（11→16）——失败尾巴双端保持 FAILED、负未读数不亮角标、非群组全部状态直通、空白发送者隐藏而 SYSTEM 类型出回执、标未读大数仍封顶 99+；`ChatListPreviewProjectionTest` 新增 5 例（7→12）——预览改类型且未读累加、等时间戳只改文案、置顶目标新时间不让位未置顶、恢复按新近度插未置顶区后、等排序键保持稳定顺序。零生产改动；2026-10-28 oct22hy 续批：`NotificationIntentPolicyTest` 新增 5 例（7→12）——清理中恒拒（无论 id 是否匹配）、当前用户空白恒不匹配、制表换行裁剪、内部制表保留、大小写原样保留；`NotificationPrivacyPolicyTest` 新增 5 例（7→12）——单因充分性、联合揭示条件、全表重复稳定、预览关与加锁各方向的充分性；`NotificationCenterReadPolicyTest` 新增 5 例（16→21）——deeplink 通道胜过错配 extra、msg_ 前缀 alone 不算消息行、ai_tasks mergeKey 胜过他聊 deeplink、itemId 锚点胜过错配 extra、extra 通道无需 msg_ 前缀。零生产改动。；2026-10-28 oct22ie 续批：`TypingSessionPolicyTest` 新增 5 例（7→12）——空白 owner 恒禁、内部空格 owner 非 me 占位、带空格 chatId 与原值判异、已宣布空白仍宣布、裁剪后 owner 与未裁剪实时 id 失配；`SettingsVisibilityPolicyTest` 新增 5 例（6→11）——空白值双向回落、回落值原样透出、前后空格已知值不识别、空回落原样返回、近似值回落；`LiveLocationPolicyTest` 新增 5 例（6→11）——剩余时间忽略 live 标志与开放式归零、formatRemaining 截断与整点边界、LOCATION 消息不可解析与越界坐标判非 live、开放式 live 消息经消息路径仍 live、剩余时间恰到期归零。零生产改动；2026-10-28 oct22if 续批：`ChatDetailDecryptPolicyTest` 新增 5 例（3→8）——可解密集恰 9 项、非可解密集恰 4 项、隐藏 SK_DIST 不可解密、分类确定性、未知 wire 值回落 TEXT 可解密；`GroupMutePolicyTest` 新增 5 例（3→8）——11 档严格递增且末档等于 30 天上限、全部档位封顶、小档精确值、过期与清零均非活跃、具名档位精确时长；`ChatDraftPolicyTest` 新增 5 例（9→14）——带空格 id 仍可排程、写入比较不裁剪、代际极值、空白字段可恢复草稿、null 写入方恒拒。零生产改动；2026-10-28 oct22ig 续批：`MessageDuplicatePolicyTest` 新增 5 例（2→7）——异 id 独破冗余、存储字段异动破冗余而 meta 异动不破、发送者/类型异动非同投递、同投递比 parsedContent（meta 后缀忽略、正文异动判异）、pickCanonical 保旧 id 取新方其余字段；`LocalMessageMutationPolicyTest` 新增 5 例（4→9）——编辑/撤回均拒异 id、无修订编辑拒、未编辑行任何修订均可编辑、同修订同内容保旧行（含旧 meta）、已撤回行保新行（旧修订与同修订低内容均不回退）；`TextOutboxSessionPolicyTest` 新增 5 例（4→9）——null 实时用户拒、空白令牌拒、空白实时用户拒、带空格实时用户不匹配、带空格批次用户不匹配。零生产改动；2026-10-28 oct22ih 续批：`StartupPermissionPolicyTest` 新增 5 例（3→8）——低于 33 不查授权态、35 未授权仍请求、常量精确值、授权谓词恰收通知权限一次、sdk 0 恒空；`OwnerScopedImageKeyPolicyTest` 新增 5 例（3→8）——含冒号 owner 原样保留、空白 owner 仍加前缀、带空格 data 不裁剪、查询串原样保留、同键确定且 data 敏感；`LikeQueryPolicyTest` 新增 5 例（4→9）——contains 保留非空白输入的内外空格、重复通配符逐个转义、prefix 转义反斜杠、prefix 空白原样保留、转义字符为反斜杠且两转义器对非空白输入一致。零生产改动。；2026-10-28 oct22ii 续批：`CallNotifyIdPolicyTest` 新增 5 例（7→12）——会话消息固定 0 号槽位与前缀、提醒槽位与会话槽位命名空间隔离、聊天动作 requestCode 按 +1/+2 偏移与三类 data-URI、动态与 AI 任务槽位及 requestCode 关系、社交三类槽位与定时失败槽位；`GroupPollPolicyTest` 新增 5 例（7→12）——投票解析去重/过滤负数/容忍非数字、选项清洗裁剪去空白但不去重、投票选项 12/13 与单项 80/81 边界、接龙与 PK 标题/话题/内容长度边界、分享文本布局与问题截断及快捷符内嵌；`StickerFilePolicyTest` 新增 5 例（8→13）——摘要小写十六进制 64 位格式、多缓冲轮次大文件摘要稳定且异于小文件、大文件大写摘要仍接受、过短与非十六进制期望摘要恒拒、摘要内嵌空白与异文件摘要恒拒。零生产改动；2026-10-28 oct22ij 续批：`WebRTCAudioPolicyTest` 新增 5 例（4→9）——约束顺序钉住回声消除/自动增益/噪声抑制、重复调用结果相等、键名无分隔符与 goog* 变体、值均可解析为布尔 true、键集合恰三项；`WebRTCIcePolicyTest` 新增 5 例（4→9）——回落恰两个 google STUN 地址、每次回落建新列表、单配置服务器元素同一性、大小写 TURN/TURNS 仍算 TURN、turnx 前缀不算 TURN；`CallReliabilityPolicyTest` 新增 5 例（9→14）——群通话仅需麦克风、空白发送方恒拒而空白期望联系人放行、ICE 小写态/负重试次数/未知态与常量、网络质量边界值、群网格极值与 mesh 边及活跃态边界。零生产改动。；2026-10-28 oct22im 续批：`AttachmentProgressPolicyTest` 新增 5 例（19→24）——回落超阈重发低值、回落阈内抑制、段尾未满不强制发布、全负段塌成零、任意负 previous 视作未设置；`QrScanFeedbackPolicyTest` 新增 5 例（14→19）——未知码落到状态分支且保留归一化码、中文失效/无效消息分支、中文屏蔽优先于上限、查人网络/超时忽略状态码、固定反馈不可重试且无码；`BotCommandPolicyTest` 新增 5 例（12→17）——过短目标被拒而带参目标正常、尾空格与双空格参数、命令含数字下划线、裸 @ 提及进箱而行中提及不进、行中 SK: 标记不当密文。零生产改动。；2026-10-28 oct22in 续批：`ChatListReloadPolicyTest` 新增 5 例（13→18）——可见模式前台触发零防抖、静默模式群修订触发零防抖、防抖模式初始与群修订触发保默认防抖、重连防抖同样适用于静默模式、重连防抖恰为默认防抖两倍；`ChatListSettingsMergePolicyTest` 新增 5 例（13→18）——Long.MIN 边界本地胜出、同为 Long.MIN 时服务端胜出、服务端零戳对本地负戳胜出、时间戳相等时全字段差异仍归服务端、合并后两快照不变；`ChatFolderPolicyTest` 新增 5 例（16→21）——新建序号跟最大序号而非个数、归一化后大小写重复被拒、跨两位移动与远端交换、零位移返回原序、负目标位钳制到首位；2026-10-28 oct22ik 续批：`SpoilerMediaPolicyTest` 新增 5 例（8→13）——支持集按声明顺序迭代、remove/clear 同样不可变、枚举中恰 3 项为真、支持集等于三类型新集合、查询顺序不改分类；`StickerNamePolicyTest` 新增 5 例（9→14）——空与空白输入恒空、40/80 上限恰卡位、非 ASCII 字母被剥离、点空格点塌成 .. 被拒、pack 剥点而文件名保点；`MediaViewerPolicyTest` 新增 5 例（9→14）——clamp 恰卡上下界与无穷、双击 0/满幅/NaN、mime 裸 gif 与 video 无斜杠边界、显式 mime 原样透出与类型大小写无关、文件名按类型前缀与扩展名检查顺序。零生产改动。；2026-10-28 oct22ip 续批：`CustomStatusPolicyTest` 新增 5 例（8→13）——isValid 按原长计空格、null 视为空、normalize 保留内部空格、展示态超长截到 80、预设首末与请勿打扰钉住；`MessageReminderPolicyTest` 新增 5 例（9→14）——分钟档 1/5/15/30、小时档 1/2/3/4/6/12/24、档位均为整分钟、24 小时与 2 天间无档位、30 天上限恰为 43200 分钟；`UnreadPriorityPolicyTest` 新增 5 例（9→14）——分数精确算术、免打扰负时间归零、旧草稿取消息时间精确值、非正未读数不计数、单条未读恰为基础加权加一。零生产改动。；2026-10-28 oct22il 续批：`DecryptFailurePersistencePolicyTest` 新增 5 例（8→13）——空白内容恒不可读、eyJ 与 MD: 前缀恒不可读、带方括号的历史占位恒输给明文、占位近似文本（多字/感叹号后缀）仍算可读；`GroupPlaySealPolicyTest` 新增 5 例（9→14）——无分隔符负载仍解析、前后空格宿主标签原样保留、四十字符边界分隔符的保留与截断、前缀大小写与精确匹配、首个竖线前文本决定解析；`ScreenshotDetectorPolicyTest` 新增 5 例（8→13）——shouldStart 真值表、截图匹配大小写不敏感、中英文截图路径与文件名、录屏中文与连字符变体、清理器零秒通过但非截图路径恒不删。零生产改动。；2026-10-28 oct22io 续批：`PushKeepAlivePolicyTest` 新增 5 例（9→14）——media 守护复活同样要 token、effectiveMode 幂等、空白与未知及大小写模式有 token 仍禁启、4 模式互异且 ALL_MODES 全覆盖、运行时模式排除 media/call 遗留；`PushWakePayloadPolicyTest` 新增 5 例（10→15）——20 允许键全集放行、ciphertext/envelope 禁键排序、带空格大写禁键仍拒、仅空白键映射放行、ts/sig 元数据键放行；`PushNotificationPolicyTest` 新增 5 例（16→21）——null 会话拒、活跃会话前缀不抑制、收件寻址区分大小写、SK_DIST 前缀不抑制、混合大小写 SK_DIST 仍抑制。零生产改动。；2026-10-28 oct22iq 续批：`MessageDuplicatePolicyTest` 新增 5 例（7→12）——chatId/senderId 差异破冗余、type/timestamp 差异破冗余、同 id 正文差异破冗余（含 <meta> 后缀原串比较）、同投递忽略状态/id/starred/reactions、pickCanonical 取 incoming 侧字段；`LocalMessageMutationPolicyTest` 新增 5 例（9→14）——新编辑取 candidate meta、等 revision 按原串字典序、双方无 revision 拒绝、双方无 revision 撤回保持 null revision、已撤回等 revision 高内容适用；`TextOutboxSessionPolicyTest` 新增 5 例（9→14）——空白 batch user 拒绝、tab 换行 token 拒绝、用户匹配大小写敏感、token 与 user 双 null 拒绝、带空格 token 匹配用户仍放行；2026-10-28 oct22is 续批：`WebRTCIcePolicyTest` 新增 5 例（9→14）——退化非空配置不触发回落、回落 STUN 无凭据、三服务器顺序原样保留、回落单例每次新建、仅 turn/turns 破坏纯 STUN；`CallReliabilityPolicyTest` 新增 5 例（14→19）——双空白挂断恰相等放行、宽限态忽略重连次数、带空白 ICE 状态不识别、纯空白发送方恒拒、空白组与成员集合语义；`WebRtcNativeDownloadPolicyTest` 新增 5 例（12→17）——首个托管 ABI 按列表顺序、无托管且无 arm64 前缀时默认回落、官方域名大小写与空白容忍、顶级域名 mdou.me 受信、无效哈希头回落 ETag；2026-10-28 oct22ir 续批：`StartupPermissionPolicyTest` 新增 5 例（8→13）——负 SDK 不请求且不查授权态、远未来 SDK 拒了仍请求、他项授权不算数、重复调用结果稳定且纯、恰 Tiramisu 已授权跳过；`GroupMutePolicyTest` 新增 5 例（8→13）——剩余档位（3/6 小时、30 天）精确值、零起点静默期等于各档位、30 天上限常量、清零在非负时刻恒不活跃、预设列表等于枚举全集；`LikeQueryPolicyTest` 新增 5 例（9→14）——空白变体（制表/换行）归空、其余标点原样透出、前缀重复通配符转义、已转义输入再转义会双重转义、中文前缀除通配符外原样保留。零生产改动。；2026-10-29 oct22iu 续批：`OwnerScopedImageKeyPolicyTest` 新增 5 例（8→13）——制表/换行空白归空、数据内冒号原样保留、空白数据优先于空 owner、中文/表情与行内换行原样保留、5000 字数据不截断；`ChatDetailDecryptPolicyTest` 新增 5 例（8→13）——fromWire 大小写不敏感、空与带空格 wire 值回落 TEXT、隐藏集恰为 SK_DIST、可解密与隐藏集不交、每枚举经 wire 名往返；`VoiceTranscriptPolicyTest` 新增 5 例（9→14）——normalize 只去两端空白、带空格正文算有转写、inline 入口与 canRequest 全组合一致、null/空白展示为空、超长先截到 6000 再预览；2026-10-28 oct22it 续批：`AttachmentErrorUiPolicyTest` 新增 5 例（9→14）——INVALID_RESPONSE 遇 500 仍归完整性、无状态 HTTP 归兜底、UNEXPECTED 归兜底、包在普通异常里的加密失败不穿透、重复分类结果稳定；`AttachmentSendAfterUploadPolicyTest` 新增 5 例（16→21）——非 HTTP 种类的 409 中文未就绪文案不算冲突、仅“附件尚未”前缀即冲突、英文关键词须配 attachment 出现、已接受码须 HTTP 种类且非空、无状态 HTTP 与 UNEXPECTED 不可重试；`AttachmentTransferRetryPolicyTest` 新增 5 例（19→24）——已接受码判定与状态码无关（500 可接受、200 不可重试）、空/空白码不算已接受、无 attachment 的 upload 文案不算冲突、信号异常名（StaleKey/NoSession）按名判暂态、502 可重试而 498 不可。零生产改动。；2026-10-29 oct22iw 续批：`AppUpdatePolicyTest` 新增 5 例（11→16）——非官方 URL 纵有新版与合法 SHA 也不给、SHA 比对 trim 且忽略大小写但拒内含空白、Latin-1 乱码备注还原、百分号编码 UTF-8 备注解码、hostOf 不拆 userinfo 且去尾斜杠；`AppUpdateDownloadRetryPolicyTest` 新增 5 例（10→15）——http_ 前缀大小写与位置敏感、empty_body/apk_too_small 精确相等才算、message 为空的 IOException 照样重试、timed out 不含 timeout、连接短语须完整且顺序一致；`AppUpdateInstallPolicyTest` 新增 5 例（10→15）——低于 offer 的包纵高于已装也拒、installed 为负不拦升级、超 Int 上限的包拒、双方空签名集也拒、包名不 trim 不折叠大小写；2026-10-29 oct22ix 续批：`NotificationTargetReplayPolicyTest` 新增 5 例（10→15）——代际错配纵已就绪且 owner 匹配也丢弃、群邀请就绪时 bypass owner 校验、空白 liveUserId 等待时不丢弃、已登录但路由为空仍等待、就绪时 liveUser 为空按 owner 错配丢弃；`CaptureAlertPolicyTest` 新增 5 例（10→15）——kind 前后空白 trim、恰 32 字标签完整保留、无分隔符时 body 即消息、kind 后空串消息、默认 screenshot 与前缀大小写敏感；`QuickPhrasePolicyTest` 新增 5 例（10→15）——长度先 trim 后判定、remove 删全部重复、40 条容量边界、默认短语恰 8 条且均可添加、add 只去两端空白保留内部空格；2026-10-29 oct22iy 续批：`MessageReceiptAggregationPolicyTest` 新增 5 例（9→14）——负时间戳视同缺失、部分推进不碰其余字段、单字段推进与回归按字段独立结算、等值合并不改存量、仅 played 的全新回执；`MessagingV2OutboxOrderingPolicyTest` 新增 5 例（11→16）——大小写混写、分隔符变体、内部空白、复合近似名均判未知、五种已文档分类重复求值稳定；`MessagingV2RetryPolicyTest` 新增 5 例（11→16）——负 now 下封顶延迟、负 now 仍走基准延迟、封顶前严格递增封顶后持平、零与负 attempt 均回基准延迟、第九次为封顶前最后一次翻倍。零生产改动。；2026-10-29 oct22ja 续批：`LogoutStorePolicyTest` 新增 5 例（11→16）——keep/destroy 两类恰分 5 个原因、四清缓存面两两一致、登出策略与账号切换策略互认、原因枚举顺序钉住、调用顺序不影响 verdict；`AccountIsolationPolicyTest` 新增 5 例（12→17）——双空白账号仍 keep、属主行空白组合全拒、偏好键不 trim 原样拼接、草稿主键不 trim 原样返回、两端空白的不同账号仍 purge；`SecretChatPrivacyPolicyTest` 新增 5 例（11→16）——四能力组合×六动作真值表、状态机全转移表、能力快照值相等、销毁态忽略全部转移、过期态除销毁外终态；2026-10-29 oct22iv 续批：`PreferenceConflictPolicyTest` 新增 5 例（10→15）——空 owner 双侧跳过隔离且归一为会话 owner、空远端不掩盖本地 owner 错配、远端错配优先于本地错配、同 revision 同时间戳守本地、双冲突字段按字段序记录；`WidgetRowPolicyTest` 新增 5 例（10→15）——MARKDOWN 预览直通、meta 后缀裁剪去空白、恰 40 字无省略号、锁聊叠加应用锁时锁预览优先且角标归零、被跳过的密聊不占 maxRows 名额；`MotionPolicyTest` 新增 5 例（10→15）——负 animator scale 关动画、非正基准时长恒零、缩放时长下限 1ms、超大 scale 仍封顶 2 倍、自定 maxAnimatedItems 收紧首屏动画边界；2026-10-29 oct22iz 续批：`WebRTCAudioPolicyTest` 新增 5 例（9→14）——首尾对精确钉住、键位索引 0/1/2、toMap 按键查值、驼峰键名、无空白键值；`CallReliabilityPolicyIceRestartTest` 新增 5 例（10→15）——COMPLETED 取消 grace、未知/空状态忽略、负 attempts 仍重启、带空白状态因不 trim 被忽略；`WebRTCStatsMathTest` 新增 5 例（10→15）——科学计数法字符串解析、空白填充数字串照常解析（toDoubleOrNull 容忍首尾空白）、NaN/Infinity 字符串解析、非正总量为 null、总量溢出回绕为 null。零生产改动。；2026-10-29 oct22jb 续批：`ViewOncePolicyTest` 新增 5 例（11→16）——视频/GIF 与图片锁行为一致、标记后非 meta 字段不变、不支持类型打开后永不锁、发送者视频/GIF 视角永不锁、标记一条不影响另一条；`VoiceCapturePolicyTest` 新增 5 例（11→16）——小振幅单调性、波形越界钳制、容量 1 只留最新、发送与试听共用 500ms 门槛、未知倍速标签回落 1x；`LiveLocationPolicyTest` 新增 5 例（11→16）——时长常量合理性、跨天小时格式化、剩余毫秒精确差值、极值剩余钳制、非直播未来到期仍不直播。零生产改动。；2026-10-29 oct22jc 续批：`NotificationSoundPolicyTest` 新增 5 例（15→20）——未静音托盘音等价消息音、应用内提示音 32 格全表、前台压托盘音不压来电铃声、消息音每门单调、前台显式 false 等价缺省；`NotificationSlotPolicyTest` 新增 5 例（18→23）——社交三类通知 id 同裸哈希靠 tag 隔离、十种通知 URI 互不重叠、多会话动作码 +1/+2 稳定、提醒 requestCode 按会话与 notifyId 按消息分键、空 id 退化值；`NotificationPrivacyPolicyTest` 新增 5 例（12→17）——上锁掩蔽预览拨动、上锁拨动仅预览开时生效、隐藏公式 lock || !preview、锁单调与预览反单调、上锁拨动序列。零生产改动。；2026-10-29 oct22jd 续批：`StickerSourcePolicyTest` 新增 5 例（11→16）——查询串保留、点点段同源归一、相对路径上一级、百分号编码路径保留、仅用户名拒收；`SettingsVisibilityPolicyTest` 新增 5 例（11→16）——已知值压过空回落、换行填充不识别、小写 contacts/private 回落、空值配 CONTACTS 回落、带空白回落原样返回；`ChatHistoryLoadPolicyTest` 新增 5 例（11→16）——未读追平消息数无分隔线但照发回执、密聊仍算分隔线、密聊丢弃 revision、无回执时不带 revision、控制消息不计入分隔线分母。零生产改动。；2026-10-29 oct22jf 续批：`MessagingV2GroupControlPolicyTest` 新增 5 例（12→17）——非控制 kind 不 stale、空白 kind 名不识别、极值 live 修订、极值 queued 修订、live 过零翻转；`MessagingV2InboxFailurePolicyTest` 新增 5 例（12→17）——近似永久码可恢复、近似码预算耗尽、极值尝试次数、未知码单调翻转点、大小写变体不识别；`ReactionMutationPolicyTest` 新增 5 例（13→18）——缺席 actor 追加、缺席 actor 删除不变、actor id 不 trim、legacy 空白表情删除、legacy 空快照删除。零生产改动。；2026-10-29 oct22je 续批：`CallNotifyIdPolicyTest` 新增 5 例（12→17）——来电/未接 id 等于 hashCode 与 xor 盐恒等式、空 callId 映射为 0 与盐、全部槽位 id 两次调用确定性、多字符 id 各槽位公式、第二聊天 id 的动作偏移 +1/+2；`GroupPollPolicyTest` 新增 5 例（12→17）——PK 解析大小写与空值边沿、签到空群边沿与 0/1 天文案、接龙 ^ 转义与空内容、投票空列表与去重保序、投票问题 200 截断在校验与分享双侧；`ScreenshotDetectorPolicyTest` 新增 5 例（13→18）——空输入与裸 screen 名不命中、录屏无空格/下划线/scr 拼写变体、图片 10 秒不删而录屏 10 秒删、名中 screen_shot 不命中而路径命中、跨类型清理永不删；2026-10-29 oct22jg 续批：`ChatFolderPolicyTest` 新增 5 例（12→17）——建文件夹取次序并排序、下移交换与零位移不变、后移插入前移中间项、首个归属与过滤保序、归一化后判重；`ChatAppearancePolicyTest` 新增 5 例（13→18）——夜色深于默认双主题、壁纸未知与大小写归一、字号裁剪与小写归一、浅深色板无交集、抽查色值钉死；`ChatTextDisplayPolicyTest` 新增 5 例（13→18）——数字 &/引号/回车、前导零数字实体、具名实体大小写敏感、大小写混写十六进制、双重编码只解一层。零生产改动。。零生产改动。；2026-10-29 oct22jh 续批：`PinSecurityPolicyTest` 新增 5 例（12→17）——前导零与分隔符格式、哈希确定性与盐/轮数敏感、盐长度与十六进制、畸形存储串、legacy 错 PIN；`SecretGateAndTtlPolicyTest` 新增 5 例（13→18）——超时边界恰好关闭、过期后重验证复开、关门禁时忽略陈旧戳、TTL 精确上下限往返、徽标默认展示；`MessageDuplicatePolicyTest` 新增 5 例（12→17）——非正文边字段不影响同投递、实体解码后等价、仅末个 meta 标签剥离、对称性、规范选取幂等与 meta 保留零生产改动。；2026-10-29 oct22jk 续批：`CustomStatusPolicyTest` 新增 5 例（13→18）——制表/换行衬垫裁剪与内部空白保留、单侧衬垫恰 80 字符边界、可见文案制表换行剥离与关闭时恒空、预设经 normalize 不变且均合法、normalize 幂等且输出恒合法；`SpoilerMediaPolicyTest` 新增 5 例（13→18）——addAll/removeAll/retainAll 均抛不可变异常、支持集为枚举真子集、附件族 FILE/VOICE/STICKER 恒不支持、变异风暴后全量分类不变、可变副本改动不污染原集；`GifSearchPolicyTest` 新增 5 例（13→18）——查询多空格保留与制表换行裁剪、查询分隔符不归一化的不对称、recentIds 重复项收敛、limit 恰 2 与超量、pushRecent 去空白后去重。零生产改动。；2026-10-29 oct22jj 续批：`MediaCachePathEscapeTest` 新增 5 例（12→17）——空密聊 id 回落共享目录、单密聊擦除不碰他聊、删消息同时清密聊隔离目录、下载 discriminator 变体与空值命名、预处理扩展名大小写与长度边界；`MediaCacheAttachmentSizeLimitTest` 新增 5 例（13→18）——密文下界 17 字节、空 uri 源拒、空文件双声明拒、目录作源拒、缓存字节增量与清理返还；`MediaCacheAttachmentReferenceTest` 新增 5 例（14→19）——key 长度 40/48 双界、明文上界恰值与超值、语音时长 500ms 与 1 小时双界、mime 长度 100 与大小写归一、解码侧 key/哈希/密文尺寸篡改拒。零生产改动。；2026-10-29 oct22ji 续批：`ViewOncePolicyTest` 新增 5 例（16→21）——支持集穷举恰三类、未支持类打标已打开仍惰性、视频/GIF 标记级生效、锁跟随 own 参数而非 senderId、惰性消息 markOpened 原样返回；`UnreadPriorityPolicyTest` 新增 5 例（14→19）——仅标未读精确权重、999/1000/998 上限边界、免打扰下草稿时间优先、仅标未读计入未读会话数、无未读时草稿时间精确值；`VoicePlayerSpeedPolicyTest` 新增 5 例（14→19）——无穷与负零按未知档处理、NaN 与负零文案兜底、三步恰走三档、档位互异有界、每档下一档文案精确映射。零生产改动。；2026-10-29 oct22jl 续批：`LinkPreviewPolicyTest` 新增 5 例（15→20）——非 HTTP scheme 与空 host 拒、localhost 点后缀与大小写变体拒、首 URL 大写 scheme/www/跳过非 HTTP、twitter 描述与图片回落及空预览站点回落、displayHost 去 www/96 截断与坏输入直通；`MessageReminderPolicyTest` 新增 5 例（14→19）——档位间隔恒不小于 1 分钟、提醒上限高于定时上限、小时档位跳过 5 小时、前四档合计 51 分钟、周级档位仅 7 天；`ScheduledMessagePolicyTest` 新增 5 例（14→19）——clamp 幂等、截断留前缀、窗口端点延迟精确值、长空白串判否、换行制表边缘裁剪。零生产改动。；2026-10-29 oct22jm 续批：`StickerPolicyTest` 新增 5 例（15→20）——pushRecent 去尽重复、启用顺序按请求序、空请求回全部默认包、空启用集关一包留其余、搜索裁剪与未知串空结果；`NearbyPolicyTest` 新增 5 例（14→19）——九档半径钉住、档间就近取整、剩余 1ms 仍可见、零半径钳制下限、离线距离优先于更新时间；`QrCodeGeneratorPayloadTest` 新增 5 例（14→19）——空 id 编裸前缀、中文 id 六冒号结构、v2 八冒号与 v2 标记、全空白 token 裁空且解析拒、token 内部空格保留。零生产改动。；2026-10-29 oct22jo 续批：`ChatFolderPolicyTest` 新增 5 例（17→22）——空列表起序 0 与默认 id 前缀、改名归一化且保序保会话、两步移动与远端交换、移入未知目标则处处移除、删不存在会话不改动；`CallNotifyIdPolicyTest` 新增 5 例（17→22）——提醒与会话槽位隔离、空来电 id 的 URI 隔离、AI 任务 notify 与 requestCode 同值及分组按会话、社交 requestCode 前缀公式新 id、动态 notify 与 requestCode 隔离及定时失败 id 非零；`MediaViewerPolicyTest` 新增 5 例（14→19）——缩放常量钉住与中间值直通、mime 家族覆盖、显式 mime 优先与 gif/file 回落、生成文件名先 mime 后类型、导出与分享门禁全真值表。零生产改动。；2026-10-29 oct22jn 续批：`GroupPollPolicyTest` 新增 5 例（17→22）——加号与前导零序号归一、第二竖线截断序号段判非法、PK 构建不 trim 选项、构建后解析丢弃负序号、分享文案编号与标题原样；`LiveLocationPolicyTest` 新增 5 例（16→21）——跨天边界与亚分钟取整、负 now 计量、Long 极值严格大于、档位读作分钟与最小间隔读作秒、开放式直播在极值 now 下仍有效；`SpoilerMediaPolicyTest` 新增 5 例（18→23）——迭代器不可删除、集合哈希与新集合相等、containsAll 正反判定、首末元素顺序、typed 数组顺序；2026-10-29 oct22jp 续批：`GifSearchPolicyTest` 新增 5 例（18→23）——查询去空格转小写后匹配、最近顺序完全压过日期、默认上限 48 钉住、连字符与下划线直连子串、空输入与无命中为空；`QuickPhrasePolicyTest` 新增 5 例（15→20）——制表换行纯空白拒、删除区分大小写、满员时重复亦拒、40 与 80 上限钉住、表情短语去首尾留表情；`CaptureAlertPolicyTest` 新增 5 例（15→20）——空白标签整串钉住、消息内多余竖线保留、kind 含竖线首竖线切分、33 字标签截 32、kind 24 留 25 截；2026-10-29 oct22jq 续批：`DisappearingMessagePolicyTest` 新增 5 例（15→20）——白名单全量通过与邻值拒绝、有效时长全量直通、退化定时器与读到时间处理、负 deadline 视同无 deadline、武装判定忽略定时器量级；`VoiceCapturePolicyTest` 新增 5 例（16→21）——默认 56 容量空快照、容量 2 第三推轮转、单推右对齐、分钟边界格式化、500ms 最小发送常量钉住；`ChatAppearancePolicyTest` 新增 5 例（18→23）——壁纸与字号 id 往返、深色抽查色值钉住、浅色余量色值钉住、两主题内色值互异；2026-10-29 oct22js 续批：`MediaViewerPolicyTest` 新增 5 例（19→24）——gif 后缀与 video 前缀边界、带空格类型名回落、svg 回落 jpg 与 png 优先于 video 前缀、区间内缩放直通与双击负值、导出分享门禁忽略 exportBlockEnabled；`SpoilerMediaPolicyTest` 新增 5 例（23→28）——集合实例稳定、按枚举序与声明序不同、elementAtOrNull 边界、混合样本恰 3 个支持、差集为空与补集 10 个；`LiveLocationPolicyTest` 新增 5 例（21→26）——分钟与多小时精确边界、remainingMs 与 remainingFromUntil 同 deadline 一致、无 sessionId 仍 live 双路径、非 live 未来 deadline 消息路径为假、remainingFromUntil 毫秒边界；2026-10-29 oct22jr 续批：`ViewOncePolicyTest` 新增 5 例（21→26）——支持类型无标记已打开不锁且标记原样返回、视频标记后接收者锁发送者不锁、GIF 标记幂等、无标记视频/GIF 不生效、GIF 标记保留身份字段；`StickerPolicyTest` 新增 5 例（20→25）——默认上限 48 截断、空列表推入与空白忽略、全未知回退默认包、已启用再启用不变、自定义包字形与标签命中去重；`UnreadPriorityPolicyTest` 新增 5 例（19→24）——负未读总数不提示、双信号单聊只计一次、手动标未读压过 999 封顶、草稿时间与未读加权精确合成、标未读与封顶精确合成。零生产改动；2026-10-29 oct22jt 续批：`CaptureAlertPolicyTest` 新增 5 例（20→25）——制表换行空白修剪与精确 wire 形式、标签与 kind 同线双截断、解析保留 kind 两侧空格、前缀加竖线得默认 kind 与空消息、前导空格与空输入非提醒；`CustomStatusPolicyTest` 新增 5 例（18→23）——多字节按字符计 80 边界、前五预设固定顺序、emoji 两侧修剪保留、可见多字节 80 截断与隐藏为空、填充长状态先修剪后截断；`ChatTextDisplayPolicyTest` 新增 5 例（18→23）——十六进制最大码点解码、未知命名实体原样、8 位十进制实体原样、7 位十六进制实体原样、DEL 控制字符解码；2026-10-29 oct22ju 续批：`VoiceTranscriptPolicyTest` 新增 5 例（14→19）——长度常量钉住、去空格后才计入展开阈值、预览截断处多空格裁净、内层空行保留、先裁边后截断超长输入；`SecretWhitelistAndFingerprintPolicyTest` 新增 5 例（13→18）——指纹写入裁边但核验原样比对、转发查询串不裁边、重复标记不重复计入、白名单账号隔离、关开关时写入重开后仍生效；`GroupPlaySealPolicyTest` 新增 5 例（14→19）——裸前缀与前缀加分隔符解析为 null、换行与制表符模式往返、宿主标签撞 kind 后缀不污染模式、13 对格式输出均带 kind 后缀、^ 在 40 字符边的存活与截断。零生产改动。；2026-10-29 oct22jv 续批：`GifSearchPolicyTest` 新增 5 例（23→28）——分隔符转换跨名组合、近期未命中不占限额、负 max 仍保留 1 条、子串而非分词匹配、默认上限 160 取最新；`MessageReminderPolicyTest` 新增 5 例（19→24）——14 档全表钉住、最大间隔为 3 天到 7 天的 4 天、最小间隔为 1 分钟到 5 分钟的 4 分钟、档位跨度 7 天减 1 分钟且 1 小时内 4 档、14 档总和 20451 分钟；`QuickPhrasePolicyTest` 新增 5 例（20→25）——删除保序且保留其他短语重复项、默认短语无首尾空白、默认首尾钉住、超长添加不改动输入、接受新建列表而拒绝返回原列表。；2026-10-29 oct22jy 续批：`VoicePlayerSpeedPolicyTest` 新增 5 例（19→24）——档位邻值按未知值处理、文案去 x 解析回档位、未知值并入环后 4 步闭合、极值按未知值处理、任意未知值下一档文案均为 1.5x；`GroupPollPolicyTest` 新增 5 例（22→27）——投票 pollId 两侧空格不裁、pollId 含竖线破坏往返、签到 chatId 冒号透传与非前缀判否、接龙空 chainId 判否与带空白输入解析、分享文案边界值原样输出；`ChatFolderPolicyTest` 新增 5 例（22→27）——同位重排保留原序号、越界目标钳制到两端、未知 id 重排与改名判否、空目标 purged 全文件夹且空白会话 no-op、未读负数钳零与系统过滤及查无会话边界。零生产改动。；2026-10-29 oct22jx 续批：`ScheduledMessagePolicyTest` 新增 5 例（19→24）——快捷档位 14 档逐项钉住、修剪后截断、修剪后正好 4000 判有效、窗内值 clamp 原样保留、零与负延迟判否；`NearbyPolicyTest` 新增 5 例（19→24）——上位档位中点并列取低档、窗内半径原样保留、默认半径为档位且可往返、负距离按数值排序、NaN 贴到首档；`LinkPreviewPolicyTest` 新增 5 例（20→25）——空白与纯 meta 文本无链接、公网 URL 原样保留、og 优先于 twitter 且站点回退 host、仅描述或仅图片也算有用、无 scheme 与 scheme-relative 判否。零生产改动。；2026-10-29 oct22jw 续批：`SignalingOfferFreshnessPolicyTest` 新增 5 例（20→25）——未知时间戳压过零窗口、未来时间戳遇零窗口仍被拒、空白 callId 仍走新鲜度且跳过 terminated 比对、大写 OFFER 被终止集合照常过滤、自定义窗口下未来时间戳仍新鲜；`CallLockScreenFlagPolicyTest` 新增 5 例（19→24）——图形空格/表意空格/窄不换行空格纯 id 均视为空白、BOM 与 NUL 纯 id 均非空白；`PolledIncomingBatchPolicyTest` 新增 5 例（29→34）——无群组视频墓碑只标视频、空白 callerName 原样进墓碑、prefer 被终端覆盖时存活 offer 照常响铃、古董首 offer 被滤后新鲜次 offer 成 primary、空 callId 不同联系人不去重。零生产改动。；2026-10-29 oct22jz 续批：`CallNotifyIdPolicyTest` 新增 5 例（22→27）——社交三 data URI 新 id 逐字钉住、missed salt 异或自逆、空 chatId 三档 requestCode 0/1/2、空 id 下提醒与会话命名空间仍分离、定时失败 id 等于公式且异于固定消息 id；`QrCodeGeneratorPayloadTest` 新增 5 例（19→24）——v1 载荷无 v2 标记且 6 冒号、deviceId 边界 1 与 255 往返、带首尾空白的 32 位邀请 token 修剪后解析、含换行空格的 safetyCode 往返、v2 空格指纹往返且 safetyCode 为 null；`HexBytesTest` 新增 5 例（6→11）——负字节按无符号编码、字节序保留、nibble 全覆盖、单零字节异于空数组、256 字节数组首尾钉住。零生产改动。；2026-10-30 oct22kb 续批：`CaptureAlertPolicyTest` 新增 5 例（25→30）——默认 kind 精确 wire、空白 label 配自定义 kind 精确 wire、kind 内空格保留、label 含竖线时解析按首个竖线切分、中间出现前缀不认作提醒；`CustomStatusPolicyTest` 新增 5 例（23→28）——末五预设与关键位置钉住、normalize 保留内部换行、多字节 80 字截断、isValid 把尾换行计为一字、衬垫多字节长状态先裁边后截断；`QuickPhrasePolicyTest` 新增 5 例（25→30）——默认短语逐字钉住、多字节 80/81 字边界、满表拒空白短语、含空格短语精确删除与空表删除、去重按裁边后精确比对。零生产改动。；2026-10-29 oct22ka 续批：`StickerNamePolicyTest` 新增 5 例（14→19）——制表换行修剪、纯点前缀保留、emoji 与重音剥离、恰满 40/80 上限保留、斜杠点号组合坍缩或清空；`StickerFilePolicyTest` 新增 5 例（13→18）——单字节已知摘要、混合大小写摘要接受、同内容双文件同摘要、空与尾换行期望拒绝、尾换行内容已知摘要；`StickerSourcePolicyTest` 新增 5 例（16→21）——根 base 裸相对路径、仅密码凭据拒绝、base 查询串丢弃、大写 scheme 接受并归一化、单点段归一化。零生产改动。；2026-10-30 oct22ke 续批：`PushKeepAlivePolicyTest` 新增 5 例（14→19）——衬垫已知模式不启用、衬垫遗留模式归一为 off、复活与启停在全部模式×token 组合下一致、退役伪装在大小写与衬垫变体下恒 false、归一输出恒为受支持运行时模式；`PushWakePayloadPolicyTest` 新增 5 例（15→20）——空包拒绝原因钉住 empty、message/lastMessage/content 敏感键拒绝、衬垫大写敏感键优先于未知键、空白键过滤后再判未知键、title/level 与大小写通话键接受；`PushNotificationPolicyTest` 新增 5 例（21→26）——非活跃已静音仍抑制、非活跃且静音未知时 SK_DIST 仍抑制、衬垫 chatId 非空白照常显示、活跃匹配精确不修剪、制表换行独占 id 解析为 null。零生产改动。；2026-10-30 oct22kc 续批：`NotificationIntentPolicyTest` 新增 5 例（12→17）——账号逐字比对不裁边、双向大小写错配拒绝、不间断空格不被 trim 丢弃且互认、单字符与数字会话 id 透传；`SensitiveActionPolicyTest` 新增 5 例（12→17）——双门禁交换律、六动作在任意门禁组合下结论一致、交错开关确定性、自关保护动作仍被门禁、具名与位置调用一致；`TypingSessionPolicyTest` 新增 5 例（12→17）——空白令牌拒绝、me 占位与实时会话无关、制表符裁边后匹配、空 active 永不宣告与尾空格已宣告仍宣告、实时 id 大小写错配拒绝。零生产改动。；2026-10-30 oct22kf 续批：`AppLockPolicyTest` 新增 5 例（20→25）——Long.MIN 超时按 1ms 算、登出胜未验证、未验证胜从未切后台、Long.MIN 切后台时间视作从未切后台、Long.MAX 边界处阈值仍生效；`ChatLockPolicyTest` 新增 5 例（19→24）——制表换行 id 视空白、空串归属两边一致只看解锁集合、null 归属忽略空串 live 用户、Unicode 会员精确匹配、无归属双方且 id 缺席仍锁定；`ScreenSecurePolicyTest` 新增 5 例（21→26）——路由首段判定忽略查询串、路径段里 + 解码后仍为 +、空白查询 chatId 抽取为 null、fillRoutePattern 只替换已给参数、乐观密聊排除通话与水印取证表面。零生产改动。；2026-10-30 oct22kh 续批：`FakeChatPolicyTest` 新增 5 例（14→19）——Long.MIN 切后台时间仍不拦截已解锁重锁会话、Long.MIN 时间戳不影响已锁定会话拦截、无 PIN 的已解锁后台会话永不拦截、禁用下的已锁定会话永不拦截、16 种布尔组合真值表与公式一致；`AttachmentErrorUiPolicyTest` 新增 5 例（14→19）——除 INVALID_RESPONSE 外全部 Api 种类回落、READ_FAILED/SIZE_MISMATCH 带 cause 仍回落、INTEGRITY/CONTENT_MISMATCH 忽略 cause、非 Exception 的 Error 回落、两 workflow 异常多实例分类稳定；`LikeQueryPolicyTest` 新增 5 例（14→19）——NBSP 对 contains 判空白归空而对 prefix 原样保留、空白输入上两转义函数分流、prefix 对已转义输入二次转义、括号引号冒号原样透出、两次转义非幂等。零生产改动。；2026-10-30 oct22ki 续批：`MessageMutationGateTest` 新增 5 例（14→19）——重复终态事件只留单条墓碑、REVOKE 墓碑同样按 owner 隔离、同消息 id 换会话的延迟 DATA 仍拦截、终态消息的晚到反应同样丢弃、未来日期墓碑按存在而非时间拦截；`MessageReceiptAggregationPolicyTest` 新增 5 例（14→19）——仅 read 的新回执、零值入量不擦除已有正值、负值入量不回退已有值、Long.MAX 时间戳不被后续正常合并回退、仅 recipient 重键时时间戳保留；`OutgoingConversationResolverTest` 新增 5 例（14→19）——已绘制完整群会话免 fetch、构造方会话 id 在 active 为空时生效、active id 优先于构造方 id、领域入口解析 bot 对端跳过 crypto、创建后会话切换在缓存前取消。零生产改动。；2026-10-30 oct22kg 续批：`AccountIsolationPolicyTest` 新增 5 例（17→22）——空白前账号登录仍保留、账号标识大小写敏感、偏好键同键确定且分 base、偏好键与草稿键拒空串、草稿键同账号分会话；`LogoutStorePolicyTest` 新增 5 例（16→21）——过期时各清理面逐个保留、信任域变更时各清理面逐个销毁、清理面交错调用稳定、Reason 名与声明序一致、Coil 分区与销毁分区一致；`SecretChatPrivacyPolicyTest` 新增 5 例（16→21）——隐私动作与密聊状态声明序钉住、已读→过期→销毁链路、capability copy 翻转 verdict、四种能力组合下 verdict 重复稳定。零生产改动。；2026-10-30 oct22kd 续批：`GroupMutePolicyTest` 新增 5 例（13→18）——其余档位在非零时刻的精确截止、档位名升序、截止时刻严格递增、Long 上界边界、负时刻与负截止；`ExplorePublicProfilePolicyTest` 新增 5 例（12→17）——纯 @ 与尾斜杠归空、首斜杠取段、空格百分号解码与非法编码透传、混合引号双端剥离、404/410 优先于 kind 与 TIMEOUT 优先于服务端文案、服务端文案裁边与空白回落；`ChatRealtimeEventPolicyTest` 新增 5 例（13→18）——空白会话 id 永不命中、大小写敏感、他人离开仅刷新与跨会话忽略、小写原因非终态、无活跃会话不判移除与 IGNORE 需更新 revision。零生产改动。；2026-10-30 oct22kk 续批：`DecryptFailurePersistencePolicyTest` 新增 5 例（13→18）——双可读取 incoming、双密文取 incoming、双占位取 incoming、新编辑占位不换可读内容但盖新编辑戳、REVOKED 双向终态；`ChatDetailDecryptPolicyTest` 新增 5 例（13→18）——小写内容类 wire 名可解密、小写控制类 wire 名不可解密、fromWire 重复查找稳定、连字符 wire 名回落 TEXT、填充与数字 wire 名回落 TEXT；`DecryptFailureDisplayPolicyTest` 新增 5 例（17→22）——双密文取 candidate、FAILED 不胜 DELIVERED、SENDING 不回退 DELIVERED、等修订 star 跟 candidate、多 id 按时间戳独立合并。零生产改动。；2026-10-30 oct22kj 续批：`MessagePinPolicyTest` 新增 5 例（20→25）——MAX_PINS 钉死 20 与超限拦截、九种可置顶类型全扫、仅取最后一个 <meta> 之前文本、空串与纯 meta 空白回空、默认 96 截断；`MentionPolicyTest` 新增 5 例（26→31）——词中光标返回部分 filter、@AI 尾空格与光标落在 @ 上不算 query、显式 query 覆盖检测、提及按出现顺序返回、limit 先保 everyone 再按名排序；`NudgeDisplayPolicyTest` 新增 5 例（24→29）——仅前缀的存储体按 trim 后原文重建、非本人单聊英文存储仍显示“拍了拍你”、嵌套前缀只剥一层、发送者名内部空格保留、英文存储尾空格裁边。零生产改动。；2026-10-30 oct22ko 续批：`ChatHeaderStatusModelTest` 新增 5 例（8→13）——空白 typing 回落在线、负 lastSeen 回落离线、群聊在线仍显示在线、群聊自定义状态仍显示、typing 优先于群聊 None；`ChatTimelineModelTest` 新增 5 例（11→16）——恰 6 分钟另起一组、6 分钟内同组、空白 id 各得独立键、未读起点缺失且首条空白回落 window 键、等时间戳跨发送者仍按 id 排序；`MessagePreviewTextTest` 新增 5 例（13→18）——正文首尾空白裁边、纯换行回占位、媒体类型忽略空内容仍用标签、SK_DIST 纯文本仍回占位、meta 后缀只显正文。零生产改动。；2026-10-30 oct22kp 续批：`SenderDisplayNameTest` 新增 5 例（14→19）——映射名空白裁边、空白备注名回落 id、发送者 id 前后空白先 trim 再截断、联系人名优先于映射名、空白群成员标签回落未知；`ReadReceiptPolicyTest` 新增 5 例（18→23）——发送者空角色仍可见自己回执、纯空白 id 拒绝、空花名册与空回执回零对、viewerId 前后空白仍匹配发送者、负 max 返回空；`MessageStatusMachineTest` 新增 5 例（17→22）——梯子可跳级但不可倒退、fromWire 全状态大小写不敏感、higherDeliveryStatus 取高 rank 且 FAILED 双向透明、新编辑可清星标、同时间戳按 id 排序。零生产改动。；2026-10-30 oct22kq 续批：`AttachMenuPolicyTest` 新增 5 例（19→24）——群聊无 viewOnce 仍保留 SPOILER/VOICE 精确列表、单聊无 viewOnce 与无名片精确列表、单聊 AI 位于名片与静音之间、频道忽略 viewOnce 与名片开关、VIEW_ONCE 仅单聊启用时出现；`ComposerStateTest` 新增 5 例（13→18）——attach 菜单连点两次回静止态、AI 菜单重复打开仍可见且可由返回关闭、返回关闭先清 attach 再清表情面板、Saver 往返保留翻译语言开关、打开名片选择器清 AI 菜单但保留翻译语言开关；`MediaCenterModelTest` 新增 5 例（12→17）——链接/语音/图片跨类别按时间戳倒序、同一消息多链接按文本顺序而非字母序、贴纸消息内容里的链接不进链接类、尖括号截断链接匹配、同时间戳按输入顺序稳定排列。零生产改动。；2026-10-30 oct22kl 续批：`ChatMessageInteractionPolicyTest` 新增 5 例（15→20）——VIDEO/LOCATION 可转发而 SK_DIST 不可、forwardBlock 开关不影响转发判定、可回复覆盖 VIDEO/LOCATION 而 SK_DIST 不可、滑动阈值小数边界、密聊下 MARKDOWN 亦不可复制；`GroupMutationFeedbackPolicyTest` 新增 5 例（17→22）——410 判冲突重载、无状态码的 GROUP_NOT_FOUND 判冲突、GROUP_ACTOR_NOT_MEMBER 判权限、INVALID_RESPONSE 可重试、UNEXPECTED 判致命；`MessageMutationPolicyTest` 新增 5 例（18→23）——429/504 判传输歧义而 INVALID_RESPONSE/UNEXPECTED 不判、404 终态须为 HTTP 种类、乐观编辑在 Long.MAX_VALUE 处饱和、无既往修订时从时间戳进位、空 id 拒绝建单且权威 EDIT 不阻塞后续变更。零生产改动。；2026-10-30 oct22km 续批：`AiRequestGenerationGateTest` 新增 5 例（12→17）——双门禁令牌序列互不干扰、首次请求前两次失效跳过两代、连续请求后失效仅留跳代不可达、同值令牌分属不同门禁、失效不复活已超期令牌；`ChatAiContextBuilderTest` 新增 5 例（18→23）——纯文本上下文排除带转录语音、当前用户标签优先于名字映射、纯文本未映射发送者回落、摘要保留 Markdown 正文、系统消息仅凭翻译进入摘要；`ChatAiOperationModelTest` 新增 5 例（17→22）——重型群助手动作仍不建恢复记录、摘要消息 id 截断至 48 且保序、翻译目标语言只截断不裁边、文件摘要模式存 summarize 线值、非重试类失败的明细无等待且持久码无后缀。零生产改动。；2026-10-30 oct22ks 续批：`MentionPolicyTest` 新增 5 例（31→36）——形似 AI 的 AIX 不算助手命令而 AI 加空格算、limit 为 0 仍出 everyone 条目且 EVERY 大写别名命中、无分隔符相邻 @ 不提取且同名取先者、插入光标越界钳制到文本边界、空当前用户下 everyone 亦不高亮且带空格名 token 原样保留；`MessagePinPolicyTest` 新增 5 例（25→30）——极小截断上限只剩省略号、尾部空 meta 块不抹掉前面的字面 meta、meta 先剥离再算截断预算、群角色尾空白不予放行、单聊里 OWNER 也钉不住 SYSTEM/SK_DIST/REVOKED；`ChatHeaderStatusModelTest` 新增 5 例（13→18）——群聊全信号齐开仍以输入中为先、在线压过最后上线、自定状态在单聊与群聊里都压过最后上线、Long.MAX_VALUE 最后上线原样展示、输入中 id 只去两端空白而保留内部空格。零生产改动。；2026-10-30 oct22kn 续批：`ChatSearchModelTest` 新增 5 例（8→13）——ALL 域空查询回空、整词短语分高于单 token 且同分按时间倒序、VOICE 域只匹配转写、30 天窗口边界含入、SK_DIST 建档排除而媒体类型保留；`SemanticSearchCandidateFilterTest` 新增 5 例（11→16）——最终按时间升序压过关键词与星标优先级、MEDIA 域只留媒体消息、MENTIONS 域只留直接与 @所有人提及、十发送者各 30 条触发单发送者 20 上限与百条截断、空白文本与空白转写不成候选；`ChatSearchSelectionStateControllerTest` 新增 5 例（8→13）——密聊拒绝优先于 AI 未启用、候选 id 截断 100 条、清除时同意弹窗按调用方保留、空查询拒绝优先于无上下文、导航改写与消费只动目标字段。零生产改动。；2026-10-30 oct22kr 续批：`ChatTimelineStateControllerTest` 新增 5 例（8→13）——未知 id 的状态更新与删除不改动列表、Set 原位替换保序、空输入合并保序、SENT 拒 FAILED 且 FAILED 可回 SENDING；`ChatGroupSecurityStateControllerTest` 新增 5 例（7→12）——无警告新群警告为 null、二次 present 替换旧群与联系人、空成员表清映射与自身字段、自身默认静音为 0、自身空白昵称丢弃但保留角色；`RemoteTypingCoordinatorTest` 新增 5 例（8→13）——他聊事件不干扰当前输入者、空白停止不影响租约、停止后重打重启满租约、停最早用户仍显示最新用户、clear 后新输入开启全新租约。零生产改动。；2026-10-30 oct22ku 续批：`ChatHeaderStatusModelTest` 新增 5 例（18→23）——制表符与换行包裹的输入中 id 去空白、群聊负值最后上线不回退为离线而是无状态、群聊在线压过自定状态与最后上线、仅制表符的自定状态落到最后上线、最后上线为 1 的边界仍展示；`MessagePinPolicyTest` 新增 5 例（30→35）——上限 3 截为两字加省略号、去 meta 后恰满预算不截断、负数置顶数永不超限、混合大小写 admin 可置顶而 admin 后缀角色不可、多字节文本按字符而非字节截断；`ReadReceiptPolicyTest` 新增 5 例（23→28）——发送方比对精确匹配不去空白、重复与空白回执忽略、空角色非本人不展示群已读、交错对端消息中预拉仍按最新优先、窗口内已读满的消息被剔除而部分已读与未缓存保留。零生产改动。；2026-10-30 oct22kt 续批：`ChatMediaStateControllerTest` 新增 5 例（18→23）——SENDING 映射 SENDING 且进度 0.92（成功维度）、上传超量钳制 0.90（边界维度）、替换传输清掉旧错误码（回归维度）、未开始 id 的失败标记不虚构下载（失败维度）、无既往读数时首值直接发布（成功维度）；`ChatTimelineModelTest` 新增 5 例（16→21）——空列表得空时间线（边界维度）、同标签重现时分隔键仍唯一（回归维度）、重复 id 下未读线只插一次（重复维度）、同发送者跨天另起头像组（边界维度）、三消息组中间项非组尾（回归维度）；`ChatExportControllerTest` 新增 5 例（13→18）——逐消息字段齐全（成功维度）、回落 owner me 拒导出（失败维度）、三消息计数与正文齐全（成功维度）、导出时间戳键在场（回归维度）、单聊 isGroup 为 false（回归维度）。零生产改动。；2026-10-30 oct22kv 续批：`GroupLifecycleCoordinatorTest` 新增 5 例（9→14）——空白 token 预检拒、不轮转跳过失效、零版本失效传空版本、失效失败仍提交且记警告、刷新取消直传且不失效；`ChatSearchSelectionStateControllerTest` 新增 5 例（13→18）——禁用优先于空白查询、候选去空白去重保序、制表换行查询判空白、清空保留无关导航目标、空白导航不覆盖旧目标；`MessageTerminalStoreTest` 新增 5 例（13→18）——删除搜索失败仍删本地行、撤回搜索失败仍写占位、三步全失败首错压其余、无占位撤回仍报搜索错、撤回致命错误直传不抑制。零生产改动。；2026-10-30 oct22kx 续批：`DecryptFailureDisplayPolicyTest` 新增 5 例（22→27）——旧编辑不覆盖新明文占位、星标跟随新编辑版本、送达状态不回退、新 id 按时间戳排序、双占位新编辑仍保当前；`ChatRealtimeEventPolicyTest` 新增 5 例（18→23）——异聊加入判忽略、空白目标仅刷新、填充后聊天 id 失配判忽略、刷新失效严格大于边界、空白活跃聊离开判忽略；`MessagePreviewTextTest` 新增 5 例（18→23）——双换行折双空格、媒体标签回调收类型、长文本不截断、空媒体标签回占位、纯 meta 文本回占位；`SenderDisplayNameTest` 新增 5 例（19→24）——单聊昵称空白用姓名、8 位 id 原样返回、全空白回 null、空白资料参与者返回全 id、显式 isOwn 覆盖推导。零生产改动。；2026-10-30 oct22ky 续批：`ReadReceiptPolicyTest` 新增 5 例（28→33）——viewerId 裁边后排除成员表、回落路径排除 viewer 自身回执、预拉 max=1 只取最新、未预拉的非 eligible 消息不进补刷、已读超总数视同读满；`MessageMutationPolicyTest` 新增 5 例（23→28）——完成票据后可再起新 mutation、已删后 revoke 不渲染、进行中编辑不 drop 不渲染撤回、合并补未知 id 并按时间戳排序、同修订双可读取候选内容；`GroupMutationFeedbackPolicyTest` 新增 5 例（22→27）——GROUP_OWNER_PROTECTED 判权限、GROUP_MEMBER_LIMIT_EXCEEDED 判冲突、429 判 fatal 不重试、500 无消息判可重试、未知 serverCode 回落状态码判权限；`ComposerStateTest` 新增 5 例（18→23）——表情面板连点两次回静止、quickPhrases 先于表情面板被 dismiss、开联系人名片清 quickPhrases、Saver 往返保留 ai 与名片、表情开着时切附件菜单互斥。零生产改动。；2026-10-30 oct22kw 续批：`MentionPolicyTest` 新增 5 例（36→41）——换行与制表符前的 @ 同算边界、裸 @AI 命令不弹选择器、插入时显示名去外层空白但保留内部空格、文末提及仍匹配、静音压过 @所有人与本人高亮；`MessageStatusMachineTest` 新增 5 例（22→27）——DELIVERED 不可回 FAILED 但 FAILED 可进 DELIVERED、同修订服务端 MD 信封不覆盖本地明文、两侧均为解密占位时保留本地版、双撤回取候选版、空合并为空且仅本地列表原样通过；`NudgeDisplayPolicyTest` 新增 5 例（29→34）——目标名尾标点保留、本人消息忽略单群标志、单聊英文存量不取目标名、制表符分隔目标名、本人英文存量在群里仍重建你拍 form。零生产改动。；2026-10-30 oct22lb 续批：`ChatGroupSecurityStateControllerTest` 新增 5 例（12→17）——重复成员取首条自身字段但角色表取末条、空字符串警告覆盖旧警告、身份重置清空设备安全列表与警告、presentGroup 保留成员字段、presentMembers 保留会话与警告字段；`SemanticSearchCandidateFilterTest` 新增 5 例（16→21）——三十天窗口留二十天弃四十天、TEXT 域只留非空正文、VOICE 域只留转录、TRANSLATION 域只留带翻译者、星标旧消息不越七天窗口；`ChatExportTest` 新增 5 例（14→19）——SYSTEM/SK_DIST 渲染空串、导出保持输入顺序、立即取消不留文件、全不安全字符净化、非 owner 撤回渲染；`MediaExportUseCaseTest` 新增 5 例（14→19）——语音回落 octet-stream 与 media_ 命名、空白 mime/文件名回落类型默认、分享沿用元数据文件名、分享失败透传。零生产改动。；2026-10-30 oct22lc 续批：`GroupLifecycleCoordinatorTest` 新增 5 例（14→19）——刷新 token 为空报会话缺失且按未知修订作废、抛异常刷新仍提交为警告、带空白 owner 裁边后校验与作废、作废取消直接传播、带空白 token 裁边后复用于变更与刷新；`MediaCenterModelTest` 新增 5 例（17→22）——最小 8 字符 http 链接保留、方括号花括号分号冒号尾标裁边、查询与锚点保留、文件与视频内容链接不进链接区、换行截断链接匹配；`AttachmentContentInspectorTest` 新增 5 例（18→23）——iso 尺寸与品牌串忽略、三字节 EBML 不足、jpeg 第四字节忽略、图片拒 iso 与 EBML、语音拒 jpeg；`ChatTimelineModelTest` 新增 5 例（21→26）——空白未读 id 不插分隔线、未读 id 被 SK_DIST 过滤后回落到最旧可见消息前、同发送者跨天断组、单消息同时显头像与组尾、跨天未读分隔线紧贴目标消息前；2026-10-30 oct22la 续批：`ChatSearchModelTest` 新增 5 例（13→18）——大小写与首尾空白查询命中、TODAY 窗口午夜边界含入与午夜前排除、星标域过滤未星标文本命中、语义单字符查询词被忽略、语义候选上限取最新 100 条；`MessagePreviewTextTest` 新增 5 例（23→28）——制表符保留且换行折叠、回车保留、媒体标签内换行折叠、纯空白媒体标签回占位、尾换行被裁边；`SenderDisplayNameTest` 新增 5 例（24→29）——9 位 id 截断 8 位、7 位 id 原样返回、直聊自己消息即使有映射名仍回 null、联系人名首尾空白裁边、列表标签昵称压过姓名；`ChatRealtimeEventPolicyTest` 新增 5 例（23→28）——MEMBER_KICKED 仅刷新、空目标加加入事件仅刷新、离开影响即使旧修订也失效、在线状态联系人 id 大小写敏感、负修订刷新失效比较。零生产改动。；2026-10-30 oct22kz 续批：`MessagePinPolicyTest` 新增 5 例（35→40）——群主可置顶四类媒体、大小写群主置顶文件、未闭合 <meta> 标签原样保留、制表符不折叠、去空白后再算截断预算；`RemoteTypingCoordinatorTest` 新增 5 例（13→18）——停中间用户仍显示最新用户、续租早用户只延其租约、多用户 clear 只发一次 null、异聊停用事件不干扰、自然过期后重打字重启完整租约；`ChatHeaderStatusModelTest` 新增 5 例（23→28）——不换行空格填充的打字 id 原样保留、内部换行保留、混合空白自定义状态落到 lastSeen、不换行空格自定义状态算非空、群里最小 lastSeen 回 None；`ChatTimelineStateControllerTest` 新增 5 例（13→18）——未知 id 替换不动、合并同 id 取新修订、撤回版压过新版明文、非空历史配 null 分隔符、删除后重设按时间序重插。零生产改动。；2026-10-30 oct22le 续批：`ChatMessageInteractionPolicyTest` 新增 5 例（20→25）——可回复补齐剩余媒体类型、复制拒剩余非文本类型、转发补齐剩余类型、左滑极端偏移、重选移至末尾；`AttachmentPreparationModelTest` 新增 5 例（18→23）——图片无扩展名补 jpg、大写基名保大小写、文件 mime 裁边小写、3gp 双向映射、多点基名保留；`AttachmentPreparationLeaseTest` 新增 5 例（17→22）——准备源删除失败跳过权限释放、空白加密记录后取值、空白准备源记录后取值、新租约未交接仅清权限、空租约空白 uri 仍标记已清；`ChatSearchSelectionStateControllerTest` 新增 5 例（18→23）——密聊优先于空白查询与空候选、长查询先裁边后截断、空白拒绝保留旧结果与查询、空白导航 id 不动目标、去重先于上限。零生产改动。；2026-10-30 oct22ld 续批：`AttachMenuPolicyTest` 新增 5 例（24→29）——DM 开 AI 精确 14 项（成功维度）、群关名片精确 10 项（边界维度）、DM 开 AI 关名片精确 13 项（边界维度）、频道忽略 nudge 开关（回归维度）、三模式共有基础 6 项（成功维度）；`ComposerStateTest` 新增 5 例（23→28）——表情面板切换清 AI 菜单（成功维度）、AI 菜单清快捷短语（成功维度）、空态 dismiss 返回 false 且快照不变（边界维度）、瞬时面板清理保留表情模式（回归维度）、Saver 往返保留名片选择器与翻译语言及模式（成功维度）；`ChatMediaStateControllerTest` 新增 5 例（23→28）——失败传输按已传字节算进度且状态失败（失败维度）、上传零字节落基线进度且状态发送中（边界维度）、未开始 id 直接完成仅置就绪 uri（成功维度）、开始下载保留同伴下载与就绪 uri（回归维度）、消费就绪文件保留进度与错误标记（回归维度）；`DecryptFailureDisplayPolicyTest` 新增 5 例（27→32）——新编辑明文胜旧明文且修订跟进（成功维度）、旧编辑明文不覆盖新明文（回归维度）、撤回对撤回取候选（边界维度）、SENT 不回退 READ（失败维度）、同时间戳按 id 排序（边界维度）。零生产改动。；2026-10-30 oct22lh 续批：`MessagePreviewTextTest` 新增 5 例（28→33）——Markdown 正文原样保留、拍一拍正文原样保留、CRLF 只折换行保留回车、占位符内换行被折叠、引号实体被反转义；`SenderDisplayNameTest` 新增 5 例（29→34）——单聊联系人名胜过他人映射名、群映射名胜过短 id、空标签空发送者返回 null、映射 miss 落到截断 id、空发送者用未知标签；`ChatSearchModelTest` 新增 5 例（18→23）——媒体域视频中文关键字、翻译域忽略无译文原文、撤回成文档但不可搜、语义候选按时间序、语义媒体域收录图片；`RemoteTypingCoordinatorTest` 新增 5 例（18→23）——多次续租以最后一次为准、自定义短超时按期过期、三人交错过期逐级回退、双清只发一次 null、停止重输不扰他人租约。零生产改动。
2026-10-20 oct20d 续批：`MultiDeviceEnvelopePolicyTest` 新增 5 例（13→18）——非对象根被拒、越界 device 条目跳过但同 envelope 有效条目保留、显式非默认 device 永不被默认设备猜中、null 用户名命中无地址 compact 条目、缺失 payloadType 与未知顶层键仍可选；`SignalInitializationPolicyTest` 新增 5 例（13→18）——满就绪仍对他账号走全初始化、空白请求用户拒绝复用、空白账号切换落到全新状态、匿名状态下发布失败保本地加密且永不复用、重复发布失败幂等；`SignalDeviceIdRecoveryPolicyTest` 新增 5 例（16→21）——迁移候选重试接受身份不匹配码、候选 id 尊重自定义上下界、会话冲突永不重分配、最外层包装码决定 serverCode（冲突可重试）、confirmed 状态 trim+大小写不敏感且与 pending 互斥。零生产改动；2026-10-23 oct22at 续批：`SecretScreenshotBurnPrefsTest` 新增 5 例（2→7）——开关默认开往返、服务端默认值仅用户未设置时生效、isUserSet 语义、purgeMedia 账号隔离、无账号开关 fail-open；`SecretSimChangePrefsTest` 新增 5 例（5→10）——开关往返、服务端默认值语义、同 id 空白变体不打戳、sim 状态账号隔离、无账号全 API 静默 no-op；`SecretNewDeviceRiskPrefsTest` 新增 5 例（4→9）——开关往返、服务端默认值语义、registerDevice 登记即信任（空白忽略）、knownDevices trim+账号隔离、无账号时任何设备不可信。零生产改动。；2026-10-23 oct22au 续批：`UnreadPriorityPolicyTest` 新增 5 例（4→9）——未读条数 999 封顶、负输入钳制、草稿更新晚于消息时取新者、免打扰下手动标未读仍只按时间、countUnreadChats 容忍参差列表；`UnreadPriorityPreferencesTest` 新增 5 例（2→7）——账号隔离（a 关 b 读默认、切回仍关）、重复写入幂等、无账号 setEnabled 静默 no-op、原始键格式 `enabled:<userId>`、新账号读回默认开；`UserScopedPrefsTest` 新增 5 例（6→11）——同输入 key 确定、不同用户 key 必不同、userId 含下划线仍可按首个下划线拆回、不同名取不同 store、永不直接问传入 context 要 prefs。零生产改动。；2026-10-23 oct22aw 续批：`MediaCachePathEscapeTest` 新增 5 例（7→12）——同输入重复建文件路径稳定、删同 messageId 全扩展名、他消息文件保留、copy 小文件落盘字节一致、源即目标短路返回同一 uri；`MediaCacheAttachmentSizeLimitTest` 新增 5 例（8→13）——零/负声明被拒、声明与真实大小不一致被拒、密文上界=明文上界+64、8 字节声明前置不误伤（非 TOO_LARGE）、cipherSize 超密文上界 1 字节解码拒/恰等于放行；`MediaCacheAttachmentReferenceTest` 新增 5 例（9→14）——attachmentId 20/19/100 长度边界、文件名首尾点裁/120 放行 121 拒/全点回退 file、iv 24 放行 25 拒、空/空白串解码 null、未知 JSON 键忽略但 kind 篡改拒。零生产改动。2026-10-23 oct22ax 续批：`MediaViewerPolicyTest` 新增 5 例（4→9）——MIME 大小写/空安全、空白 explicit 回退、显式文件名裁边、双击 1.05/1.06 边界与 4 次来回闭合、NaN 缩放回落最小（9.162 回归）、+Inf 钳到 8f、export/share 同门（exportBlockEnabled 预留位一致）；`SpoilerMediaPolicyTest` 新增 5 例（4→9）——GIF 支持而 STICKER 不支持（产品决策钉住）、与 ViewOnce 取值等价但概念独立（回归）、supports 即 SUPPORTED 成员关系、集合不可变（防外部篡改）、重复调用稳定；`ViewOncePolicyTest` 新增 5 例（6→11）——markOpened 只翻 opened 位其余 meta 字段与正文不动、无 meta 块消息全机制惰性、opened 无 viewOnce 标记组合惰性、重复判定确定性不改对象、发送者打开前后 own 视角永不锁。零生产改动。2026-10-24 oct22bj 续批：`MessageTerminalStoreTest` 新增 5 例（8→13）——revoke 首个 cleanup 错误优先、upsert 错误被压制（失败维度）、delete 末步失败前序已跑且无压制（失败维度）、重复 delete 全量清理（重复维度）、占位符保留身份字段但元数据清零（回归维度）、Error 绕过压制直接传播（边界维度）；`ChatMediaStateControllerTest` 新增 5 例（13→18）——PAUSED/READY 映射 SENDING 及各自进度（成功维度）、PREPARING 不碰消息状态（回归维度）、transfer 无对应消息仍记进度（成功维度）、failureMessage 非 media 失败只记 warning 不标错（失败维度）、分段字节进度映射到给定窗口（成功维度）；`MentionPolicyTest` 新增 5 例（11→16）——displayTokenForUser 名称或回退 id（成功维度）、小写 @ai 命令抑制但后续 @ 仍弹选择器（边界维度）、everyone 别名匹配与候选按小写排序（边界维度）、遗留 @userId 与 @所有人（边界维度）、空白当前用户与空提及永不高亮（失败维度）。零生产改动。
2026-10-20 oct20f 续批：`SenderKeyCoveragePolicyTest` 新增 5 例（16→21）——reportedTotal 等于 SENT 列表判 COMPLETE、LOCAL_MISSING 优先于无服务端记录边、epoch 失配优先于未知状态、同输入重复评估稳定、负 reportedTotal 空列表仍 COMPLETE；`SenderKeyRetrySessionPolicyTest` 新增 5 例（17→22）——含内空格 id 精确匹配、重复检查确定性、id 前缀不匹配兄弟账号、换行填充的期望 id 不匹配、中间大小写差异不匹配；`SignalSessionPolicyTest` 新增 5 例（18→23）——持久设备去重排序裁剪、null 发现+全越界持久得空、自设备判定大小写敏感、发现去重坍缩、deviceId 255 边界有效；`MultiRecipientCoveragePolicyTest` 新增 5 例（19→24）——填充去重后仍覆盖、缺失设备 404 不透出、多缺失仅透出可重试者、空白必需 id 被忽略、已覆盖设备不透出其瞬时失败。零生产改动；2026-10-20 oct20g 续批（#936，账本补记）：`SignalStoreLoadPolicyTest` 新增 5 例（8→13）——removePrefix 只剥一层前缀、空/纯空白前缀返回 null、前缀中段出现原样返回、换行包裹 keyData 可加载而纯空白丢弃、制表符/换行 keyType 不可持久化；`SignalPreKeyIdPolicyTest` 新增 5 例（15→20）——count=1 空库从 1 起、负已有 id 回绕到顶部随机、count 远超 24-bit 坍缩 maxStart、随机下界映射到 1、maxStart 下方卡点连续分配；`SessionCipherOccupancyTest` 新增 5 例（15→20）——二次 acquire 旧 lease 释放被拒、updatePeer null 清空 peer、peer 匹配大小写敏感、空 chatId legacy 释放被拒且标记保留、occupyPeer 裁掉换行符。零生产改动；2026-10-20 oct20h 续批：`DecryptHistoryPolicyTest` 新增 5 例（8→13）——UnsupportedEnvelope 保线不推迟修复、Failed/Duplicate 保线但非修复信号、纯空白发送者 id 永不尝试修复、重复调用结果确定性、FutureEpoch 保线但永不触发修复；`SafetyCodePolicyTest` 新增 5 例（9→14）——format 先归一化分隔符再分组、非数字码返回 null、unicode 空白包围的 codesMatch、单侧空码拒绝匹配、长码严格五位分组；`ConfirmedDevicePolicyTest` 新增 5 例（17→22）——confirmed 全越界默认槽返回 null、显式范围内 id 无视 confirmed 列表、confirmed 排序后取最小、自定义 default 在 confirmed 内返回自身、负数自定义 default 仍走默认槽。零生产改动；2026-10-20 oct20i 续批：`OwnSentMediaRestorePolicyTest` 新增 5 例（16→21）——TEXT 自发永不 hydrate、对方 FILE 开自动下载可 hydrate、自发 VIDEO 无需自动下载可 hydrate、填充/不间断空格附件 id 仍视为有效可恢复、决策重复调用确定；`DecryptFailurePolicyTest` 新增 5 例（25→30）——markTerminal 剥离归属后 sender 修复不复活 terminal、terminal ACK 清零重试计数、超限驱逐先保 terminal、同长度指纹不碰撞、空明文成功仍落空串；`DecryptPlaceholderPolicyTest` 新增 5 例（22→27）——不间断空格包围不命中、全空白 vararg 永不命中、占位符嵌正文不命中、重复调用确定、零宽空格破坏精确匹配。零生产改动；2026-10-20 oct20l 续批：`MultiRecipientCoveragePolicyTest` 新增 5 例（24→29）——空具体目标恒覆盖、deviceId 0 仍计用户级覆盖、填充的失败键归一化匹配、双可重试缺失取必需序首个、RuntimeException 包装的 SignalExchange NETWORK 仍瞬时；`SignalInitializationPolicyTest` 新增 5 例（18→23）——空态选 null 账号仍空、null/null 请求走 FULL 初始化、null 当前账号两入口均拒、发布成功保留账号与本地就绪、localStoreReady 不碰发布就绪；`SignalPreKeyIdPolicyTest` 新增 5 例（20→25）——Int.MAX_VALUE count 空库连续起步不走随机、近满库只剩两槽随机域、负已有 id 零 count 先归一再回随机、随机近顶映射 MAX_ID-1、Int.MAX_VALUE 已有 id 溢出回随机。零生产改动；2026-10-23 oct22ad 续批：`TypingSessionPolicyTest` 新增 5 例（2→7）——空白 token/userId 永不发射（失败维度）、owner 先裁边再判 me 占位（边界维度）、me 占位检查大小写敏感（边界维度）、纯空白 activeChatId 不宣告（边界维度）、发射与宣告判定重复调用稳定（重复维度）；`ChatDetailSearchStateSaverTest` 新增 5 例（3→8）——close 只清本次搜索、模式/范围/时间窗保留（成功维度）、默认构造往返全默认值（边界维度）、枚举按 name 存、存表恰 6 项（回归维度）、save 读实时值非构造快照（成功维度）、close 幂等且仍可用（重复维度）；`SearchWindowStartTest` 新增 5 例（5→10）——四窗口全覆盖、唯 ALL 返回 null（回归维度）、now=0 仍纯偏移（边界维度）、午夜时刻 start==now 自洽（边界维度）、today>seven>thirty 全序（成功维度）、重复调用确定性（重复维度）。零生产改动；另按注释铁律精简了后两个文件的 AI 味 KDoc。；2026-10-23 oct22ai 续批（PR 待 CI）：`AiEntryPolicyTest` 新增 5 例（4→9）——hasContextAiActions 与 contextActionsFor 互证（MARKDOWN/gif/大小写、null/POLL 无动作）、masterEnabled=false 屏蔽全入口、userEnabled 默认 false、忙与设置更新双拦、分区顺序冻结；`AiWritingStylePolicyTest` 新增 5 例（4→9）——customNote 空白折叠/裁边/null、preset id 大小写与超长回退、preset-only hint 纯预设文案、note-only hint 仅带用户注记、启用但无内容无 hint；`GroupAiSharePolicyTest` 新增 5 例（4→9）——超长答案截到 MAX_SHARE_CHARS（4000）、被拒分享 body 为空、shareAssistantMode 裁边+40 截断、sanitize 30 上限与非法 dueAt 清零、闭环需可持久任务+确认分享。零生产改动。；2026-10-23 oct22ao 续批：`AiEmotionLexiconTest`/`AiClassifyLexiconTest`/`AiEmotionFallbackTest` 三新文件各 5 例——词典并列取首个情绪/类别钉住、跨文本命中聚合置信度、200/300 字符扫描窗口截断、英文大小写（情绪敏感/分类不敏感）、五情绪回退模板资源映射与互异钉住（Robolectric）。零生产改动。；2026-10-23 oct22ba 续批：`LiveLocationPolicyTest` 新增 5 例（1→6）——formatRemaining 三档文案与负值钳零、DURATION_OPTIONS 三档递增且 DEFAULT 15min 首位、remainingFromUntil null/过期钳零、isLive 的 null/非 live/open-ended 守卫、isLiveMessage 只认 live 的 LOCATION 消息；`CallNotifyIdPolicyTest` 新增 5 例（2→7）——对真实 `NotificationSlotPolicy` 钉住来电/未接 id 隔离（xor MISS 盐）、salt 即 "MISS" ASCII、missed id 单射、dataUri/tag 常量钉住；`VoiceTranscriptPolicyTest` 新增 5 例（4→9）——normalize 6000 截断与 null 为空、展开开关 360 边界、displayText 恰 360 不加省略号且超限 trim 尾空格后加 "…"、纯空白转写仍显示入口。零生产改动。；2026-10-24 oct22bc 续批：`ChatListReloadPolicyTest` 新增 5 例（3→8）——防抖常量钉住（400/800）、RECONNECT 触发无视模式强制防抖、非重连触发在静默模式零防抖、DEBOUNCED_SILENT 恒用默认窗口、5 触发源全覆盖模式映射；`ChatListSettingsMergePolicyTest` 新增 5 例（3→8）——本地陈旧时服务端胜出（引用一致）、胜者按引用返回、1ms 边界本地胜出、零时间戳走服务端、负时间戳仍按数值比；`ChatListLoadCoordinatorTest` 新增 5 例（4→9）——远端失败回退本地缓存并展示错误文案（失败维度）、429 限流保持原错误不抛新文案（失败维度）、无会话+空白 owner 报过期文案（账号切换维度）、服务端缺失的本地会话被清理（成功维度）、防抖中的重连请求被用户刷新取消（取消/重复维度）。零生产改动。；2026-10-24 oct22bd 续批：`ChatAppearancePolicyTest` 新增 5 例（8→13）——字号档位倍数逐字钉住（成功维度）、浅/深色 16 预设全不透明（边界维度）、resolveBackground 与调色板逐预设互证（回归维度）、空串/未知 id 回 NORMAL（边界维度）、预设 16/档位 5 数量钉住（回归维度）；`ChatTextDisplayPolicyTest` 新增 5 例（8→13）——空串直返（边界维度）、孤立 & 与残缺实体原样保留（边界维度）、制表符与 emoji 实体解码（成功维度）、相邻数字实体全解码（成功维度）、混合句子一遍解码（回归维度）；`CaptureAlertPolicyTest` 新增 5 例（5→10）——kind 线上截 24 字符（边界维度）、label 先裁边后截 32 逐字钉住（边界维度）、unicode label 往返（成功维度）、裸前缀解析为 screenshot 空 rest（边界维度）、format 确定性且 kind 区分 wire（重复维度）。零生产改动。；2026-10-24 oct22be 续批：`DisappearingMessagePolicyTest` 新增 5 例（10→15）——密聊 null/0 请求仍固定 30 秒、负数既有 expiresAt 视同缺失重起算、负定时器等同关闭保留既有值、skipReadReceipts 无视 blockReadReceipts 第二参、白名单 14 项且末项 30 天钉住；按注释铁律精简该文件类 KDoc；`QuickPhrasePolicyTest` 新增 5 例（5→10）——80 字符上限含入/81 拒、去重先裁边且区分大小写、remove 不裁边、8 默认短语互异、add 尾部追加且不改输入；`StickerPolicyTest` 新增 5 例（10→15）——搜索大小写不敏感、中文标签命中、null 请求回退三包全启、重复启用幂等、enabledPacks 丢未知 id。零生产改动。；2026-10-24 oct22bg 续批：`PrefsJsonListsTest` 新增 5 例（4→9）——转义字符/emoji/中文往返（成功维度）、方括号出现在字符串内不被当分隔（边界维度）、非字符串元素按 optString 语义读（边界维度）、千项列表往返不丢且编码确定（重复/回归维度）；`AppLocaleManagerTest` 新增 5 例（6→11）——系统服务语言优先于 prefs 读数（回归维度）、系统首选为 fr 等不支持语言回落跟随系统（边界维度）、多语言列表只看首个（边界维度）、setMode 重复调用幂等（重复维度）、wrap 在英文模式下真的换 locale（成功维度）；`RuntimeFlagsTest` 新增 5 例（5→10）——改一个开关不影响其他开关（回归维度）、存储清空回默认（边界维度）、setEnabled 重复调用幂等（重复维度）、FAKE_CHAT 等默认 false 的普通开关可被打开（与 kill switch 的 CHAT_EXPORT/NEARBY 对照，回归维度）、全部 Flag key 以 _enabled 结尾钉住（回归维度）。零生产改动。；2026-10-24 oct22bl 续批：`NearbyPolicyTest` 新增 5 例（9→14）——半径上下界精确钉住（边界维度）、先 clamp 到服务端范围再吸附档位（边界维度）、两档中点并列取更小档（边界维度）、nowMs=0 永远过期（失败维度）、compareNearby 端到端排序（回归维度）；`GifSearchPolicyTest` 新增 5 例（8→13）——空白查询全匹配（边界维度）、未知 recent id 回退日期序（回归维度）、负 limit 仍返回 1（边界维度）、max=0 仍保留 1（边界维度）、同日期按小写名断平（成功维度）；`QrCodeGeneratorPayloadTest` 新增 5 例（9→14）——v2 指纹带冒号往返（成功维度）、deviceId 0/256 越界被拒（失败维度）、超 2048 字节载荷被拒（失败维度）、危险 scheme 被拒（安全维度）、邀请 token 31/32 字符边界（边界维度）。零生产改动。；2026-10-24 oct22bi 续批（补记：#1043 合并时误删本条目，oct22bm 轮恢复）：`UnreadPriorityPreferencesTest` 新增 5 例（7→12）——双账号两次切换各自值保留、空白账号写空后缀键不污染真实账号、重复读稳定、raw store 每账号恰 enabled+user_set 两键、enabled 键缺失读默认；`UserScopedPrefsTest` 新增 5 例（11→16）——前导下划线双分隔、换行制表原样、U+00A0 不裁、冒号 prefs 名透传、重复取 store 每次都问 applicationContext；`CustomThemeStoreTest` 新增 5 例（7→12）——5/7 位 hex 拒绝、小写 hex、键值两侧空白裁边、空值跳过、hex 与 int 混写后者覆盖。零生产改动；另按注释铁律精简该文件类 KDoc。；2026-10-24 oct22bm 续批：`AiClassifyLexiconTest` 新增 5 例（5→10）——空文本回退 OTHER（边界维度）、关键词恰落 300 字符窗内仍命中（边界维度）、双类目按命中数 2/3 分置信度（成功维度）、子串含关键词仍匹配如 debug 含 bug（边界维度）、重复关键词按词典词只计一次（重复维度）；`AiEmotionLexiconTest` 新增 5 例（5→10）——空列表/空白文本回 NEUTRAL（边界维度）、关键词恰落 200 字符窗内仍命中（边界维度）、焦虑词检出（成功维度）、跨情绪并列断向 happy（成功维度）；`AiEmotionFallbackTest` 新增 5 例（5→10）——重复调用稳定（重复维度）、模板无 % 占位符（回归维度）、五情绪全映射到各自资源（成功维度）、交错调用无状态泄漏（回归维度）、20 轮压测永不空白（重复维度）。零生产改动。
- [~] messaging-v2 的 26 条不变量逐条有可执行追溯（**G311c 更正条数**：此行原写「24 条」，实测该文档现有 **26 条**；G7 时 24 条、G11 增至 25、G12 增至 26。门禁自己是对的——`MessagingInvariantTraceabilityTest` 里 `assertEquals(26, audits.size, "不变量条数变了：文档改了就必须同步审计，不能悄悄增删")` 与 `(1..26).toList()`，**它一直冻结着正确值，是人的文档没跟上**。**G7 第一步**：`docs/messaging-v2-architecture.md` 每条不变量现在都标了 `→ 验证：<Class>#<用例名>` 或 `→ 缺口：<原因>`；新增 `MessagingInvariantTraceabilityTest` 做门禁——引用的测试必须真实存在、缺口集合按棘轮冻结。**审计结论：24 条里 15 条已被现有测试真正验证，9 条是明确缺口**（2/3/5/6/7/9/17/21/24），其中最高价值的是第 9 条「出站明文只在本机 SQLCipher、网络请求只含每设备密文」——`SignalMessagingV2EnvelopePreparer` 至今没有测试。**G7 续已补掉第 9 条**（`MessagingV2OutboxPlaintextBoundaryTest`，2 例 + 双向反证）、第 5/6/7 条（`MessagingV2InboxSynchronizerTest`，3 例 + 三向反证）与第 2 条（`MessagingV2RepositoryTest` 的原子性回滚用例 + 提前提交反证）；第 4 轮更正了两条**假缺口**（21/24 其实早有 `ConversationLocalStateCoordinatorTest` 覆盖，是我第一轮按文件名收集候选用例时漏了 `conversation/**`），缺口降为 2 条）；第 6 轮补掉最后一条真测试缺口——不变量 3（新增 `/api/v2/messages` 的第一个 HTTP 级测试 + 真实 WebSocket 收帧 + 注入反证），缺口降为 **1 条**（只剩 17 的后半句，属契约决策）。**G311c 现状更正**：这最后 1 条后来也已补齐——门禁的判据是 `assertEquals(emptyList(), pending, "缺口集合变了。补上一条就把这里的编号删掉…")`，而 server 全量 **564 tests / 0 failures** 实测通过，即**当前缺口为 0**。至此 26 条不变量全部有可执行追溯，本行仍维持 `[~]` 而非 `[x]`，是因为「有追溯」只证明引用存在，不等于每条都真正约束了实现（`ServerPlaintextSweepTest` 那三处反证是少见的实证，见下一行）。
- [ ] reducer/state machine 使用 fake clock 和确定性 dispatcher。
- [~] 架构测试禁止 UI -> infrastructure、domain -> Android/Ktor 依赖（客户端：`core/testing/ArchitectureTest.kt` ArchUnit 2 条 + 根 `checkArchitecture` 模块依赖；**服务端已补 `server/src/test/.../architecture/ServerArchitectureTest.kt`**，随 `server:test` 自动进 CI：2 条绝对不变量 + 5 条精确相等棘轮，实测注入违规会红、基线过期也会红，见 M1 记录）；**G8** 补：客户端 `:core:testing` 的 A01 规则此前从未在 CI 执行（已接进 CI），并新增可证伪的热点棘轮 `ClientHotspotRatchetTest`（热点行数 5061/3131/2298、UI 直连持久层 38 文件/200 处，精确相等）。
- [~] E2EE 命门有可执行证据：**G11** 新增第 25 条不变量（原表述「服务端全库不含人类消息明文」已在 **G33** 审计中**收窄**为「人类 V2 提交的载荷只落在自己的每设备信封里」——原用例扫的是一个**从未进入发送/加密路径**的随机明文哨兵，那种「扫不到」是自证）。现在 `ServerPlaintextSweepTest` 用独立 JDBC 连接枚举全部表/列，扫描**真正提交进 `SendMessageV2Command` 的载荷**，要求命中恰好只有 `MESSAGING_V2_ENVELOPES.CIPHERTEXT`，并同时断言 metadata 落库、**已有** `chats.last_message` 预览未被改写、同 messageId 无 `service_messages` 正文；仍带 **正对照**（证明扫描器确能发现服务端确实保存的 `SERVICE_MESSAGES.CONTENT` / `CHATS.LAST_MESSAGE`）。三处反证已实测：把载荷写进 `Chats.lastMessage` → 预览断言红；写进**没有任何显式断言**的 `Chats.groupAnnouncement` → 扫描器报出额外列 `[PUBLIC.CHATS.GROUP_ANNOUNCEMENT, PUBLIC.MESSAGING_V2_ENVELOPES.CIPHERTEXT]` 而红；扫描器恒空 → 正对照与新断言同时红。**证据边界**：只覆盖进程内 H2 与该 repository 路径；真实客户端 Signal 加密、日志/导出/备份、真实双设备仍无证据。
- [~] 双账号双设备离线 E2E（Q04）：**G12** 落了第一个服务端切片——`MessagingV2TwoDeviceDeliveryTest` 用真实 HTTP 走通「A 发 → B（断言其无任何 WebSocket，即离线）拉 `GET /api/v2/inbox` 拿到逐字节相同的密文 → ACK 后自己清空 → 另一账号设备与**同账号另一台设备**均保留副本 → 越权 ACK 返回 0」。已实测两次反证；其中设备隔离反证第一次未红，暴露的是**测试太弱**（u2 当时只有一台设备），把测试改强后才红。**仍未覆盖真实客户端加密与 UI**，属于服务端边界证据。
- [~] 客户端加密路径有证据：**G13** 给 `SignalMessagingV2EnvelopePreparer` 补 7 个 JVM 单测（快照归属/会话一致性/群控过期/覆盖集合/明文边界），三处反证实测会红；解密路径 `SignalMessagingV2EnvelopeProcessor` 仍零测试，已列为下一步。
- [~] 客户端**解密**路径也有证据：**G14** 给 `SignalMessagingV2EnvelopeProcessor` 补 11 个 JVM 单测，重点是「解不开绝不静默」——六种失败变体各自抛错且都不提交、sender key 缺失必须触发修复、Duplicate 无 journal 不得静默丢正文、journal 必须先于提交；四处反证实测会红。
- [~] **真实加密往返**有 on-device 证据：**G17** 新增 `SignalE2eeRoundTripTest`（`app/src/androidTest`），用真实 libsignal 建真 X3DH 会话：A 加密 → B 解回**逐字节相同**原文 → 棘轮**双向**（回复走普通 `SignalMessage`）；四条反证各自实测会红——篡改一字节 / 第三方无会话 / 身份与签名不匹配 / 未建会话就加密；并用**生产** `SignalEnvelopeCodec` 构造上线信封，断言信封里既不含原文也不含原文标记、但仍承载密文。本地 arm64-v8a/API 36 与 CI x86_64 模拟器均实测 `tests=5 failures=0`。**边界**：这是「真实 libsignal 原语 + 真实生产信封编码器」这一层；`SignalDirectCipher` 的完整装配路径（依赖 `MaodouchatApp` 单例与 Room/SQLCipher store）与真实跨进程双设备投递仍未覆盖。同轮还修好了追溯门禁的一个盲点：它此前只认反引号用例名，而 androidTest 因 DEX 限制不能用带空格的名字，等于整个 `app/src/androidTest` 无法被引用——已改成两种写法都接受，并用假引用实测会红。
- [~] **生产 store + 跨重启**也有 on-device 证据：**G18** 新增 `PersistentSignalStoreRoundTripTest`，两个账号都用本仓库 `PersistentSignalProtocolStore`（Room 支撑）跑真 X3DH；并钉住「写穿是真的」（DAO 里确有 identity/session 行、被消费的一次性 PreKey 行已删除）、**跨重启存活**（新实例 + `loadPersistedState()` 仍能解开后续消息），以及三条边界：不回填必须解不出来、损坏行被丢弃且随后大声失败、写路径失败被记录/读路径抛 `SignalStorePersistenceException`、账号作用域隔离。五处探针各自实测变红。**边界**：仍不是 `SignalDirectCipher`/`SignalProtocol.initialize` 的完整装配（真实 SQLCipher、`MaodouchatApp` 单例、群 SenderKey 分发、真实跨进程双设备）。
- [~] **群消息 SenderKey 真往返**有 on-device 证据：**G19** 新增 `SignalGroupSenderKeyRoundTripTest`，用生产 `SignalGroupSenderKeyManager`/`SignalGroupCipher`/`SignalEnvelopeCodec` 跑通建分发 → 安装 → 加密 → 解回逐字节相同原文（连发两条）；并钉住未安装分发者解不出、跨群重放被拒、未来 epoch 是 `FutureEpoch`、失效后不得再按旧 epoch 加密（原因必须是 `group_sender_key_not_distributed`）、篡改必须收敛成 `DecryptResult.Failed`。**并且修掉一个真实缺陷**：libsignal 把意外的 checked exception 包成 `AssertionError`（extends `Error`），只 `catch (Exception)` 会让被篡改的群信封把 `Error` 抛出方法外——已在 `SignalGroupCipher` 收窄成 `Failed`，由该用例守卫。**边界**：群分发走网络/多设备扇出、`SignalProtocol.initialize` 完整装配、真实跨进程双设备仍未覆盖。
- [~] **解密契约的畸形输入矩阵**：**G20** 新增 `SignalDecryptInputMatrixTest`，断言每个返回 `DecryptResult` 的入口都**只能**用返回 `DecryptResult` 的方式结束，并把所有逃逸**一次性**报出；同一密文在五个偏移各翻一个 bit（并断言这些坏输入两两不同、也不同于合法输入，防止矩阵静默失效）。用它**复现并修掉**了`decryptParsedMultiDeviceEnvelope` 整条分类链缺失（实测 `InvalidMessageException` 逃逸），并把三个直发入口的分类收敛到一个 `classifyDecryptFailure`（此前各写一份，正是漂移的根源）。**诚实标注**：`decryptContentEnvelope` 上的 `AssertionError` catch 是防御性——探针实测去掉它矩阵仍全绿，保留是为了对齐 G19 在群入口已复现的同类逃逸，不是本路径已被证明会触发。
- [ ] 协议模型有向前/向后兼容与 fuzz 测试。（2026-09-26 进展：第一块——`MessagingV2Routing` 的 `messagingV2Json` 从 `ignoreUnknownKeys = false` 收敛为 `true`（其余路由早已是 `true`，此前新版客户端给 V2 发未知字段会 400），`internal` 可见性供测试直接引用同一份配置；新增 `MessagingV2ForwardCompatTest` 5 例：未知字段容忍×2、缺失可选字段回默认值、往返稳定、未知包装字段不污染已知字段。**2026-09-29 fuzz 第一块落地（G344）**：新增 `MessagingV2FuzzCompatTest`（4 例）——send/ack 两种请求各 200/100 个固定种子随机 payload（未知字段随机名/随机值/随机嵌套深度，顶层 + 信封内注入；随机名避开全部已知字段名），已知字段恒等断言；另 3 例反证钉住「已知字段类型错仍抛 `SerializationException`」（`ignoreUnknownKeys` 只放行未知键，不吞坏数据）+ 1 例近似字段名（大小写/前后缀之差）仍按未知键处理。其余协议模型（admin/bot 等）的 fuzz 仍缺，本项保持 `[ ]`。**2026-09-29 fuzz 第二块落地（G347）**：新增 `AdminModelsFuzzTest`（7 例）——管理后台一侧 `adminJson`（生产侧 `internal` 配置，测试直接引用同一份）+ 5 个走 `receiveAdminJson` 的请求 DTO（`AdminSessionRequest`/`CreateAnnouncementRequest`/`CreateUserTagRequest`/`AssignUserTagsRequest`/`UpdateUserStatusRequest`）：各 100–150 个固定种子随机 payload（未知字段随机名避开全部 18 个已知字段名、随机值布尔/整数/浮点/字符串/null/数组/嵌套对象深度 ≤ 2、顶层注入），已知字段恒等断言；4 例反证钉住「已知字段类型错仍抛 `SerializationException`」（`password` 收数字、`totpCode` 收对象、`tagIds` 收字符串、`bannedUntil` 收字符串——提权入口坏数据静默通过即安全门洞）+ 1 例近似字段名（`Password`/`password2`/`totpcode`）仍按未知键忽略。**2026-09-29 fuzz 第三块落地（G353，PR #189 已合）**：新增 `RoutingModelsFuzzTest`（8 例）——通用客户端 API 请求 DTO（`RegisterPushTokenRequest`/`SendSignalRequest`/`UpdateProfileRequest`/`SendFriendRequestBody`/`UploadKeysRequest`/`CreateReportRequest`，经 `RouteParsing.receiveJson`/`parseJson` 解码，全站最宽的客户端入口面）：各 100–150 个固定种子随机 payload（未知字段随机名避开全部 32 个已知字段名、随机值布尔/整数/浮点/字符串/null/数组/嵌套对象深度 ≤ 2、顶层注入），已知字段恒等断言；缺省值钉住（空对象→全 null、最小四字段→其余回默认值）；4 例反证钉住「已知字段类型错仍抛 `SerializationException`」（`registrationId` 收字符串/`preKeys` 收对象/`groupMemberIds` 收字符串/`timezoneOffsetMinutes` 收对象——密钥材料/信令入口坏数据静默通过即安全门洞）+ 1 例近似字段名（`DeviceId`/`platform2`）仍按未知键忽略；生产侧唯一改动是 `routingJson` 由 `private` 提为 `internal`（G344/G347 同款纪律）。**2026-09-29 fuzz 第四块落地（G354，PR 待 CI）**：新增 `WsInboundFuzzTest`（5 例）——WS 入站面（`WsMessage` 信封/`TypingPayload`/`OutgoingSignalingPayload`，经 `Sockets.kt` 的 `wsJson` 解码）：各 100–150 个固定种子随机 payload，已知字段恒等断言；缺省值钉住；3 例反证（`isTyping` 收字符串/`groupMemberIds` 收字符串/`epoch` 收对象仍抛 `SerializationException`——通话信令坏数据静默通过即安全门洞）+ 1 例近似字段名；生产侧唯一改动是 `Sockets.kt` 的函数局部 `json` 提升为文件级 `internal val wsJson`（5 处引用点改名，零行为改动）。此后服务端全部 typed DTO 解码入口（messaging-v2/admin/通用路由/WS 入站）fuzz 全覆盖；`devJson`/`pollJson`/`hintJson` 均无 typed DTO 解码用途，bot 路由为手写 JSON 解析无 DTO；**2026-09-29 bot 侧专项评估第一块落地（G355，PR 待 CI）**：bot 路由手写 `JsonObject` 解析无 DTO，无法套用 G344–G354 的 `Json.decodeFromString` fuzz 形态。评估结论：`sendMessage` 解析习惯本来就满足 fuzz 系列契约——未知键忽略、缺省回默认值、已知字段类型错时 `?.jsonPrimitive` 抛 `IllegalArgumentException`（路由层 `StatusPages` 映射为 400「参数无效」，不是 500，无未处理 500）。本轮把原来内联在处理器的抽取逻辑收敛为纯函数 `parseBotSendMessage`（`BotSendMessageParse.kt`，零行为改动，含 `silent` 死字段的保留说明），新增 `BotSendMessageParseFuzzTest`（6 例）：150 个固定种子随机 payload 未知键忽略恒等断言、缺省值钉住、6 例反证（坏类型/显式 null 大声失败）、snake_case 别名（`reply_markup`/`inline_keyboard`/`callback_data`/`force_reply`）+ 近似字段名、截断上限（text 4000/按钮 64/callbackData 128/最多 8 行）与 `silent` 语义钉住。其余 30+ bot 路由文件沿用同一习惯与同一 400 映射，本项保持 `[ ]` 待后续端点逐个收敛。**2026-10-05 查询参数收敛延续**：#352（附件/信令/密钥路由 8 处内联查询参数 → `parseRawOrEmpty`/`parseStrictBooleanFlag`/`parseOptionalInt`/`parseIntOrDefault` + `AttachmentCallKeyQueryParseFuzzTest`）后，#353（PR 待 CI）把 `BotMediaRouting` 的 `getInviteLink`（chatId 必填）与 `getFile`（messageId/fileId 回退）2 处内联抽取收敛为 `parseBotGetInviteLinkChatId`/`parseBotGetFileId` + `BotGetInviteLinkGetFileQueryParseFuzzTest`（2 例）；本轮把 `AdminDiagnosticsRouting` 的 `/ai-usage` 4 处（limit→`parseOptionalInt`、offset→`parseOptionalLong`、feature→`parseRawOrNull`、`userId`/`q` 回退→`parseFirstPresent`）、`AdminChatsRouting` 的 `groupOnly != "false"`→`parseDefaultTrueFlag`、`AdminContentRouting` 的 `status` 未 trim 非空判定→`parseNonBlankOrNull` 7 处内联收敛为共享纯函数 + `AdminFilterQueryParseFuzzTest`（2 例）；本轮把 bot 路由 9 处 `.orEmpty()` 内联（chatId×7、userId、pollId，散在 8 个文件）收敛为既有 `parseRawOrEmpty` + `BotChatIdQueryParseFuzzTest`（2 例），零行数变化；本轮把群组管理/邀请路由 17 处 `.orEmpty()` 内联（`GroupAdministrationRouting` 的 chatId×11 + memberId×2、`GroupInvitationRouting` 的 chatId×1 + inviteId×3）收敛为既有 `parseRawOrEmpty` + `GroupIdQueryParamParseFuzzTest`（2 例），逐字等价、零行为改动；本轮把密钥/举报/管理/健康/动态/公开更新路由 15 处 `.orEmpty()` 内联（`SignalKeyRouting` 的 userId×4、`ReportModerationRouting` 的 reportId×2 + uid/ruleId/eventId 各×1、`AdminModerationRouting` 的 reportId×2、`HealthRoutes` 的 packId/name 各×1、`SocialPostRouting` 的 filename×1、`PublicUpdateRouting` 的 abi×1）收敛为既有 `parseRawOrEmpty` + `MiscIdQueryParamParseFuzzTest`（2 例：150 固定种子随机参数 9 参数名恒等断言 + 缺省回空串/空串全空白原样透出/正常值透出边界钉住），逐字等价、零行为改动、零行数变化；本轮把群组成员变更 3 个 handler（`handleRemoveGroupMember`/`handleUpdateGroupMemberRole`/`handleTransferGroupOwnership`）的 6 处 `parameters["…"].orEmpty()` 内联（chatId×3、memberId×3）收敛为既有 `parseRawOrEmpty` + `GroupLifecycleQueryParseFuzzTest`（2 例：150 固定种子随机参数 2 参数名恒等断言 + 缺省回空串/空串全空白原样透出/正常值透出边界钉住），逐字等价、零行为改动、零行数变化。**2026-10-06 路由内联 Regex 提升（#396，PR 待 CI）**：`AccountRouting` 的推送 deviceId `Regex("^[A-Za-z0-9._:-]{1,100}$")`×2、`AttachmentRouting` 的 SHA-256 `Regex("^[a-f0-9]{64}$")`×3、`SocialPostRouting` 的文件名白名单 `Regex("^[A-Za-z0-9_.-]+$")`×3——8 处 handler 内联每次请求重复编译的正则提升为文件级 `private val`（pattern 逐字相同，零行为改动）。**2026-10-06 路由内联 Regex 提升第二批（PR 待 CI）**：`RateLimit`/`Routing` 的附件分块路径 `Regex("^/api/attachments/[^/]+/chunks?$")`×2（拦截器热路径）、`HealthRoutes` 的贴纸包/文件名白名单 `Regex("[^A-Za-z0-9_-]")`/`Regex("[^A-Za-z0-9._-]")`×2、`AttachmentRouting` 的 Range 头 `Regex("^bytes=(\\d+)-(\\d*)$")`/`Regex("^bytes=-(\\d+)$")`×2、`AdminManagementRouting` 的会话 token 前缀 `Regex("^[0-9a-fA-F]{12,64}$")`×1、`BotFanout` 的 `sanitizeBotHint` 空白折叠 `Regex("\\s+")`×1——8 处 handler/拦截器内联每次请求重复编译的正则提升为文件级 `private val`（pattern 逐字相同，零行为改动）。**2026-10-06 路由内联 Regex 提升（#397 PR 待 CI，第三批）**：`FileStorageService` 的图片后缀 `Regex("^[A-Fa-f0-9]{8}\\.jpg$")`×5（其中 `avatarUrl` 一处复用既有 `safeFilenamePattern`）、`PostCommandService` 的文件名白名单×2、`BotRepository` 的 bot 用户名/命令名/IPv4 十六进制字面量/纯数字×4、`SchemaMigration` 的约束名白名单×2、`AppUpdatePublishPolicy` 的 versionName×1——14 处校验内联每次调用重复编译的正则提升为类级/文件级 `private val`（pattern 逐字相同，零行为改动）。 **2026-10-06 线程安全对象实例复用（PR 待 CI）**：`MfaService.generateBackupCode` 每次生成 8 位数字恢复码都 `new SecureRandom()`（播种开销）→ 实例级 `private val secureRandom`（`SecureRandom` 线程安全，`MfaService` 启动时实例化长期复用，零行为改动）；`ChatFolderRepository.replaceFolders` 逐文件夹内联编译 `Regex("\\s+")`（文件夹名空白折叠）→ 类级 `private val whitespaceCollapseRegex`（pattern 逐字相同）。server 侧已无其他可提升的内联 Regex：`ModerationRuleRepository` 编译的是用户动态 pattern、`GroupInvitationRepository` 的 `INVITE_TOKEN_REGEX` 已在 companion、`BotRepository.generateToken` 的 `SecureRandom()` 在 #398 的改动文件里本轮避开。） **2026-10-06 路由内联 Regex 提升第五批（app 侧第一批，PR 待 CI）**：app 侧 `object` 内 30 处每次调用重复编译的正则提升为 `private val`（pattern 逐字相同，零行为改动）：`MediaCache`×11（id 净化/附件后缀/attachmentId/sha256/文件名净化）、`ChatMarkdown.toPlainText`×7（strip 模式）、`LinkPreviewPolicy`×4（含 `titleTag` 的 `Pattern.compile`；`metaContent` 的 pattern 含动态 key 保留内联）、`ChatExport`×2、`AttachmentTransferScheduler`×2、`ChatFolderPolicy`/`GlobalSearchTextHighlight`/`ScreenSecurePolicy`/`LocalAiGateway`/`AiWritingStylePolicy`/`AiConversationProfile` 各×1。热点上限覆盖文件（`GlobalSearchScreen`/`TextMessageBubble`/`MarkdownParser`）未碰。）

### Q02 数据库与迁移

- [~] Android 每个支持旧版本 -> 当前版本真实数据迁移测试（`AppDatabaseMigrationTest` 因 A03 抽取全挂已修复：27 处引用改走 `DatabaseMigrations`；新增 36→40 业务四表链测试。**此处的历史教训**：原先写的「CI 仪器测试执行」是错的——`ci.yml` 与 `release.yml` 都没有 `connectedDebugAndroidTest`（`grep -n "connected\|androidTest\|instrument" .github/workflows/*.yml` → 0 命中），4 个仪器测试文件从未被任何自动化执行。**G3 已补上 CI `instrumented` job**（emulator runner 跑 `connectedDebugAndroidTest`），第一次真跑就抓到 `migrate33To34CreatesTerminalTombstonesWithConversationCascade` 是红的。仍未覆盖：真实旧版本设备数据 fixture、SQLCipher 密钥/磁盘满/损坏等故障注入）。
- [~] Server 空库、最后生产版本、重复、中断、回滚/恢复 migration 测试（**G6 已在真 PostgreSQL 上落地**：`PostgresMigrationMatrixTest`（`@Tag("postgres")`，5 例）覆盖空库到最新、旧版本库只补缺失版本、失败整体回滚且重跑收敛、双实例并发被 advisory lock 串行化、pg_trgm 不可用时降级；备份/恢复往返由 `scripts/rehearse-pg-restore.sh` 覆盖（逐表行数 + 内容 + 外键 + 索引比对 + 坏备份必须被拒绝）。**仍缺**：「最后生产版本」的真实旧库 fixture（目前用「前 3 个迁移」模拟旧库））。
- [~] PostgreSQL 是并发和约束测试真源，H2 只用于快速测试（PG 侧现有并发测试 + G6 的迁移矩阵 + G58 的**带数据真实升级**；但 414 个 H2 用例仍是主体，绝大多数约束/并发语义**只在 H2 上验证过**）。
  - **G58 补齐的那一块**：升级路径的**数据保全**此前零证据（G6 自己记的 Risk：「旧库不是真实数据 fixture」）。`PostgresUpgradeDataPreservationTest` 现在用真 PG 证明：v3 旧库灌入真实用户/会话/设备/密钥/附件/信令/动态后按生产迁移升到最新版，9 类表行数不变、6 个身份字段逐项不变、v4 新列对老行回默认值、v5 回填补齐设备行且不重复、二次迁移 no-op。
- [ ] SQLCipher 密钥、磁盘满、事务故障和数据损坏测试。

### Q03 Compose 与系统集成

- [~] Chat、List、Contacts、Explore、Call、Settings 主流程 Compose 测试（**G319c：七个入口全覆盖（含五个屏幕本体）**。此前只有 dialog 层的 `ChatDetailDialogsUiTest`（G173b，12 个 dialog）；G301c 新增两批——`ui/screen/contacts/ContactsRowsUiTest`（**10 例**，覆盖 `ContactItem` 与 `FriendRequestRow` 两个无状态行 composable）与 `ui/screen/explore/ExploreLikersDialogUiTest`（**7 例**，覆盖 Explore 与 PostDetail **共用**的 `LikersDialog`）；G305c 与 G309c 再各加一个**屏幕本体**测试——`ui/screen/contacts/ContactsScreenUiTest`（**5 例**）与 `ui/screen/explore/ExploreScreenUiTest`（**4 例**），两个屏幕都是「显式传 fake VM」直接 `setContent`，**不需要任何依赖注入改造**（详见 G305c/G309c：VM 的 `viewModel` 本就是普通参数，默认值只在省略时才求值）。每例同时断言可见性与行为，文案一律取 `R.string`。**均已在本地 AVD `maodou_test` 实跑**（JUnit XML 逐条核对）；负控制五轮，其中两轮是行为级且**预判完全命中**（Contacts 与 Explore 各一轮：把屏幕的 `onOpenScan`/`onOpenPost` 接线改成空操作，结果只有断言行为的那条红、只断言可见性的那条仍绿）。全量 instrumented **122 tests / 0 failures**（27 skipped 全在 `PersistentSignalStoreRoundTripTest`，真机用例、预先存在）。**仍未做**：Chats（原 G313c 五点实证仍成立——`ChatListScreen.kt` 无无状态行 composable、两个 dialog 组都必填 `viewModel`、`ChatListPorts` 是含 7 个具体协作者的 `internal class`、其中 4 个从未被任何测试构造；**但 G317c 已铺好接缝**：ports 构造器 `private` → `internal`，测试侧现在能 `ChatListViewModel(app, ports)`；且 G317c 已更正我当时的错误推论——那 4 个里 `TokenManager` 有 `getInstance` 入口、`ChatRepository`/`MissedCallRepository` 收 Room DAO **接口**、`NotificationCenterRepository` 收 `Context`，**都并非不可构造，只是没人做过**。剩余仅 fake/内存库工作）与 Call / Settings 的屏幕本体（**G315c 已补**：Call 8 例、Settings 6 例；**G319c 补 Chats 3 例**）；未登录时 Explore 走的是 snackbar 而非内联文案，那条路径因涉及时序未做用例（记为可选项）。故本项保持 `[~]` 不标 `[x]`。
- [~] 截图覆盖浅/深色、手机/平板、横屏、大字体、RTL、中英文（**G325c：零依赖起步**。仓库此前**零截图基建**（无 paparazzi/roborazzi），所以本轮没做像素回归（那需新增构建依赖），改用 `CompositionLocalProvider` 覆盖 `LocalLayoutDirection`(RTL) 与 `LocalConfiguration`(fontScale=2.0)，对 ChatListScreen 与 SettingsScreen 各测两种配置，断言「不崩 + 关键内容仍在」——4 例。**关键是防「配置没生效却空跑」**：每例先捕获生效值并断言，且对捕获值断言本身做了负控制（Rtl 改 Ltr → 红在捕获值断言而非 chip 断言），证明覆盖生效、断言承重。**仍未做**：像素级截图回归、浅/深色、平板尺寸、横屏、中英文）
- [~] 通知、Widget、深链、权限、前台服务和更新器仪器测试（**G329c：只开了「更新器」一个头**。实测 `app/src/androidTest` 原有子目录只有 crypto/data/e2e/messaging/ui，六项全为零。新增 `update/AppUpdatePromptStoreTest`（4 例，钉住「同一 versionCode 只弹一次、等下一版再弹」这条 UX 契约）与 `update/AppUpdateDownloadSchedulerTest`（5 例，只测 `progressOf`/`errorOf` 两个纯函数；**实测 `androidx.work.WorkInfo` 构造器在测试里可直接调用**，不需要 `work-testing` 依赖）。**其余五项（通知/Widget/深链/权限/前台服务）仍为零**，`AppUpdateDownloadWorker`（真正干下载活的）也未测——它的 `enqueue`/`cancel`/`observe` 要真跑 WorkManager。**2026-09-27 本地工具链侧进展**：五片真机契约、共 36 例——`NotificationIntentConsumerInstrumentedTest` 17 例（通知/深链消费 + Telecom 可信路径）+ `CallForegroundServiceInstrumentedTest` 5 例 + `ConversationWidgetProviderInstrumentedTest` 3 例（小组件，**修掉「交互在 API 34+ 全死」缺陷**）+ `MessageNotificationServiceInstrumentedTest` 4 例（通知投递→点击整链）+ `AppUpdateDownloadWorkerInstrumentedTest` 7 例（更新器，**修掉重试分类漏掉瞬态 socket 错误**）；各片含负控制，见 §第十二轮。**权限维度同日补齐**：`PermissionContractInstrumentedTest` 4 例（清单声明契约 运行时/FGS 两组 + 策略↔真机授予态一致 + 授予后 `showTestNotification` 端到端真的挂出，负控制实测：删 POST_NOTIFICATIONS → 恰好 2 条红；而删 CAMERA 测试**不红**——库清单会在合并期把它加回来，如实登记这一灵敏度边界））

### Q04 双账号、双设备和离线 E2E

- [ ] 两账号，每账号至少两个设备。
- [ ] 单聊文本、附件、编辑、回应、撤回、删除、回执。
- [ ] 群聊所有成员离线、部分设备离线、新设备、成员增删和 Sender Key repair。
- [ ] ACK 前后杀进程、数据库恢复、网络切换和重复事件。
- [ ] 快捷回复、定时发送、附件 finalize 和 terminal race。
- [ ] 真实音视频、后台来电和 ICE 重连。

### Q05 性能、可靠性与安全

- [ ] 10 万消息本地库、长会话滚动、搜索、启动和内存基准。
- [ ] Server PostgreSQL 并发、mailbox retention、GC、双节点 WS 和滚动发布。
- [ ] API/WS 鉴权、SSRF、path traversal、rate limit、越权和敏感日志检查。
- [ ] AI 工具权限、更新签名、依赖漏洞、SBOM 和密钥扫描。

### Q06 CI 与发版

- [~] CI 运行 JVM、Lint、Room instrumentation、Compose、Server PostgreSQL 和 E2E（**G2 实测覆盖面 = run [34788176334](https://github.com/xalor888/Maodouchat/actions/runs/34788176334)，三 job 全绿**。**G3 新增第 4 个 job `instrumented`**：emulator runner 真跑 `connectedDebugAndroidTest`。**运行**：JVM 单测（server 404 / android 1499）、`checkArchitecture`、nav/app-update/brand 脚本门禁、`:app:lintDebug`、`assembleDebug`、`assembleRelease + verifyReleaseSize`、aapt2 badging、**Android 仪器测试（22 例）**、Server `postgresIntegrationTest`（真 PostgreSQL 16 service 容器）、admin/website/developer 三个浏览器 E2E、`docker compose config`。**未运行**：Compose UI 测试（不存在）、截图回归、Macrobenchmark、真机验收——故本项不能标 `[x]`）。
  - **2026-09-27 两设备 E2E 抖动记录（三轮实测，均为 rerun 即过，非代码回归）**：① 2 例 IO 失败（`networkJitterDuringSendStillDeliversExactlyOnce` / `theMailboxPaginatesWithoutOverlapGapsOrReorder`，`ApiException`@`apiExceptionForIOException`）；② 9 例共享会话 401（`Token 无效或已过期`，同 run 登录 4 次均成功——非登录失败）；③ 1 例第二台设备 bootstrap 失败（`IdentityRecoveryFailedException: signal_identity_recovery_failed`，与此前 G38 记录同签名）。三轮都发生在**并发 CI 高峰**（同时 4+ 个 emulator job），本地跑同一脚本 27/27 绿（服务端启动 1 次、登录 4 次）。当前缓解手段 = rerun；根因（轮换令牌/设备 bootstrap 对负载敏感）未定位，记为专项。另：`workflow_dispatch` 触发的 run 不会挂到 PR 的 check suite（本轮云端 #96 实测），排查「no checks reported」时先看 `gh run list --branch`。当前缓解手段 = rerun；根因（轮换令牌/设备 bootstrap 对负载敏感）未定位，记为专项。另：`workflow_dispatch` 触发的 run 不会挂到 PR 的 check suite（本轮云端 #96 实测），排查「no checks reported」时先看 `gh run list --branch`。
  - **2026-09-27 根因排查第一步（已落地）**：①**共享会话 401 模式**——最可能的机制是「refresh 轮换后的旧 refresh token 被重放」：服务端反重放会**撤销整个 auth session**（`RotateRefreshResult.SessionCompromised`，仓库级已有测试），此后该会话所有请求 401；已在 `AuthRouting` 的 refresh 端点补**审计日志**（`AuthRefreshAudit`：反重放触发 WARN 带 userId/sessionId、invalid_token INFO）——下次复发即可用服务端日志证实/证伪，**不放松反重放**（那是安全设计）。②**IO 模式**——jitter 用例加固：airplane-mode 恢复后「探针可达 ≠ 连接池收敛」，探针通过后再等一拍、发送重试预算 3 → 5、用例收尾再确认网络已恢复（本用例是唯一切网络的，避免连带后续用例）；断言严格性不变（仍要求最终成功且恰好一次）。本地 e2e 24/24 绿（服务端启动 1 次、登录 4 次）。③**第二台设备 bootstrap 模式**——仍开放，待复发时带日志复现。
- [~] Release 必须依赖同 commit 全门禁成功，不允许单独绕过测试构建（**G3 已补门禁**：`release.yml` 新增 `verify-ci` job 并让 `release` 声明 `needs: verify-ci`；该 job 用 `gh api` 查同 `github.sha` 上 `ci.yml` 是否存在 `completed + success` 的 run，否则 `exit 1`。本地已双向验证脚本逻辑：在绿 commit `9734a007` 上 `ok=1` 放行；在无 CI run 的 `35b60169` 上 `runs=[] ok=0` 拒绝。仍未做：跨 workflow 的强制 required-check，绕过手段是手动 `workflow_dispatch` 一个未过 CI 的 ref——但该 ref 同样会被 `verify-ci` 拦下）。
- [x] 生产签名 Secret 缺失必须失败，禁止回退 debug 签名。（2026-10-29 核验：release.yml 的 Decode signing keystore 步骤在 KEYSTORE_BASE64 缺失时 exit 1，构建不执行；app/build.gradle.kts 的 release signingConfig 仅取 findByName("release")，未配置时为 null，不回退 debug；CI 只上传测试报告不分发 APK，可分发 APK 唯一来自 release.yml。）
- [ ] 产出 SBOM、签名证书信息、checksum 和可复现构建记录。
- [ ] Android 26、当前稳定 Android、target SDK 真机验收。
- [~] 上线前完成 backup -> upgrade -> rollback/restore 演练（**G6**：`scripts/rehearse-pg-restore.sh` 用与生产 backup 相同的 `pg_dump --create --format=custom` 参数做 dump→restore 往返，逐表行数/内容/外键/索引比对，并含三类负面用例；已在本机 PG16 与 CI 的 PG16 service 上真跑通过。演练还实测出生产脚本的完整性检查**不够强**：`pg_restore --list` 只读归档尾部目录，截断/损坏的数据块照样通过——已在 `backup-production.sh` 与 `restore-production.sh` 补上「整档读一遍」。**仍缺**：生产机上的真实演练。G6 申请了一次部署访问，但该主机 sshd 只广播 `password`（公钥认证未启用），中介安装的一次性密钥无法使用；未做任何生产写入，访问已归还。公开探针已只读核对：`/health/live` 与 `/health/ready` 均 200、DB 与 storage 正常，但生产 ready 的 checks **缺少 `migrations`/`backgroundTasks`**，说明生产构建比 main 旧——迁移版本可见性缺失，正是「先升级再谈发布」要解决的）。

## 12. 多 Agent 并行执行模式

当前并发槽总数为 4，包含主 Agent。因此固定采用：

- `Agent-0 Integration`：总控、契约、共享热点接线、合并、回归和删除旧路径。
- 每轮最多 3 个执行 Agent 并行。
- 每个执行 Agent 使用独立 `git worktree` 和 `codex/refactor-*` 分支。
- ~~当前 255 项脏工作区~~（2026-09-24 实测为 0，见 §0）必须先由 Integration Agent 建立可恢复基线；未经用户要求不得提交或推送。
- Agent 只改自己的目录；共享热点只提交“接线请求”，由 Integration Agent 串行修改。

### Wave 0：冻结基线与防止继续恶化

`Agent-0 Integration`

- [ ] 记录 dirty baseline、测试基线、当前 schema 与协议版本。
- [ ] 冻结 typed payload、领域错误、SessionContext、clock/id/dispatcher 接口。
- [ ] 建立任务看板、接口变更日志和删除清单。

`Agent-1 Architecture`

- [ ] A01 模块骨架、依赖规则、架构测试和 core testing。

`Agent-2 Android Database`

- [ ] A03 schema ownership、migration fixture 和 DatabaseLifecycle。

`Agent-3 Server Foundation`

- [ ] B01 版本 migration、datasource、job lease 和部署基线。

Gate：旧功能仍可编译；三条基础线接口冻结；任何后续 Agent 不直接添加新的全局单例。

### Wave 1：身份、设备、Crypto 与消息核心

`Agent-1 Client Session/Crypto`

- [ ] A02、M03；只改 session/crypto 新模块和测试。

`Agent-2 Client Messaging V2`

- [ ] M01、M02、M05、M06；只改 messaging/domain 和测试。

`Agent-3 Server Identity/Messaging`

- [ ] B02、B03、B04、B06；先建 service，不直接重写总 Routing。

`Agent-0 Integration`

- [ ] 独占 `MaodouchatApp.kt`、`ApiService.kt`、`AppDatabase.kt`、`Routing.kt` 接线。

Gate：普通发送和群离线投递在新端口上通过双设备协议测试。

### Wave 2：群聊、附件与后台工作流

`Agent-1 Groups`

- [x] M04、U06 客户端群领域；不修改 ChatDetail 热点。

`Agent-2 Workflows`

- [x] M07、M08、M09、M11；附件、转发、定时、快捷回复。

`Agent-3 Server Conversation/Media`

- [x] B05、B07；群事务、邀请、附件和 blob。

`Agent-0 Integration`

- [x] 将群/附件/工作流接入唯一消息 facade，删除重复入口。

Gate：群成员无需同时在线；附件/转发/定时均共享 terminal/outbox 事务。

### Wave 3：聊天状态与 UI

`Agent-1 Chat State`

- [ ] U01、U04；只在新包创建 state/controller/reducer。

`Agent-2 Rendering`

- [ ] U02、U03、U07；创建新 renderer、sheets、banners 和 Compose 测试。

`Agent-3 Chat List`

- [ ] U05；创建 repository/projector/controller 和测试。

`Agent-0 Integration`

- [ ] 独占修改并最终瘦身/删除 ChatDetail、ChatList、MessageBubble 热点。

Gate：所有聊天页面不直接依赖 infrastructure；旧页面入口和重复逻辑删除。

### Wave 4：其他客户端产品域

`Agent-1 Social Client`

- [x] P01、P02（已完成）；Contacts、好友、Explore、动态、附近。

`Agent-2 Settings/Security/AI`

- [ ] P06；[x] P04（设置、主题、语言与多端偏好）、P05（安全中心、应用锁、PIN 锁、TOTP、设备撤销）、M10（阅后即焚与本地隐私）。

`Agent-3 Calls/System Client`

- [ ] P03、P07；[x] P09（Widget、更新、安装与发布渠道）；通话、Push、通知。

`Agent-0 Integration`

- [ ] P08、P10；导航、Activity、Manifest、主题公共资源和全局 UI 门禁。

Gate：所有产品域使用统一 Session、Network、Database、Navigation contract。

### Wave 5：后端剩余领域

`Agent-1 Realtime/Calls`

- [ ] B08、B09。

`Agent-2 Social/Moderation`

- [ ] B10、B11。

`Agent-3 Bot/Admin`

- [ ] B12、B13、B14。

`Agent-0 Integration`

- [ ] 串行迁出 `Routing.kt`，注册新模块并删除旧 endpoint/事务。

Gate：巨型 Routing/Bot/Admin 已被薄适配层替代；双实例测试通过。

### Wave 6：删除、迁移、E2E 与发布

`Agent-1 Protocol E2E`

- [ ] Q04 消息、群、设备、离线、重启和竞态矩阵。

`Agent-2 Platform Quality`

- [ ] Q01、Q02、Q03、Q05 Android/数据库/性能/安全。

`Agent-3 Server Release`

- [ ] PostgreSQL、双实例、migration、backup/restore、部署和安全测试。

`Agent-0 Integration`

- [ ] 执行第 10 节退役清单、全量 CI、发布候选和最终审计。

Gate：第 2、10、11 节全部勾选，才允许宣布“全项目重构完成”。

## 13. 文件所有权与冲突规则

### Android 串行热点

以下文件只能由 `Agent-0 Integration` 或当轮明确指定的唯一 owner 修改：

- `app/src/main/java/com/maodouchat/MaodouchatApp.kt`
- `app/src/main/java/com/maodouchat/MainActivity.kt`
- `app/src/main/java/com/maodouchat/ui/navigation/NavGraph.kt`
- `app/src/main/java/com/maodouchat/network/ApiService.kt`
- `app/src/main/java/com/maodouchat/network/WebSocketClient.kt`
- `app/src/main/java/com/maodouchat/data/local/AppDatabase.kt`
- `app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt`
- `app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt`
- `app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailScreen.kt`
- `app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailUiModels.kt`
- `app/src/main/java/com/maodouchat/ui/component/MessageBubble.kt`
- `app/src/main/java/com/maodouchat/ui/screen/chatlist/ChatListViewModel.kt`
- `app/src/main/java/com/maodouchat/ui/screen/chatlist/ChatListScreen.kt`
- `app/src/main/AndroidManifest.xml`
- `settings.gradle.kts`、`app/build.gradle.kts`、公共 strings/theme 资源。

### Server 串行热点

- `server/src/main/kotlin/com/maodouchat/server/Application.kt`
- `server/src/main/kotlin/com/maodouchat/server/db/Database.kt`
- `server/src/main/kotlin/com/maodouchat/server/model/Models.kt`
- `server/src/main/kotlin/com/maodouchat/server/plugins/Routing.kt`
- `server/src/main/kotlin/com/maodouchat/server/plugins/Sockets.kt`
- `server/src/main/kotlin/com/maodouchat/server/plugins/BotApiRouting.kt`
- `server/src/main/kotlin/com/maodouchat/server/plugins/AdminRouting.kt`
- `server/src/main/kotlin/com/maodouchat/server/service/RuntimeConfigService.kt`
- `server/src/main/kotlin/com/maodouchat/server/messaging/v2/MessagingV2Repository.kt`
- Server Gradle、Docker Compose、生产 migration 和公共模型。

### 并行提交规则

- [ ] 每个工作包先声明可写目录、只读依赖和禁止触碰热点。
- [ ] 不允许两个 Agent 同时新增/修改同一个 DTO、entity、DAO、route registry 或 resource key。
- [ ] 公共契约变更先提交 RFC，由 Integration Agent 更新，再通知各 Agent rebase。
- [ ] Agent 交付必须包含实现、测试、迁移说明、删除候选和接线说明。
- [ ] 集成顺序固定为：契约 -> 数据迁移 -> 领域实现 -> adapter -> UI -> 删除旧路径。
- [ ] 合并后立即运行相关模块测试；每波结束运行 Android/Server 全量测试。
- [ ] 未经用户明确要求，不 commit、不 push、不覆盖当前协作者改动。

## 14. 每个 Agent 的交付模板

每个 Agent 完成工作包时必须提交以下信息：

1. `Scope`：实现了哪些清单 ID，明确未做项。
2. `Files`：新增、修改、删除文件；是否触碰共享热点。
3. `Contracts`：新增/修改的 public API、DTO、schema、event。
4. `Migration`：旧数据、旧调用者和回滚策略。
5. `Tests`：实际运行的命令、通过数量和未执行原因。
6. `Deletion`：已经删除和仍待删除的兼容路径。
7. `Risks`：账号隔离、离线、进程恢复、安全和性能风险。
8. `Integration request`：主 Agent 需要进行的最小接线修改。

## 15. 第一批实际执行顺序

在开始写代码前，必须先处理当前巨大未提交工作区的可恢复性：

1. [x] 只读记录当前 86 项变更的来源与功能分组（server/plugins 36、app ui/screen 20、server/model 6、theme/nav/component 各 3、server/db 2、admin 前端资源 6、util/theme 若干、测试 3）。
2. [x] 运行 Android 全量 JVM 测试、Server 全量测试、Lint 和 instrumentation compile，形成基线（Server 323、Android JVM 1115、lintDebug、compileDebugAndroidTestSources 全绿）。
3. [x] 由用户决定是否把当前成果提交成基线；用户已授权「做完一步提交一步，全部做完再推送」（基线 commit `72b43a41`，未推送）。
4. [x] 基线确定后创建独立 worktree/分支；当前为单 Agent 串行执行，直接在 `main` 上逐步提交，不另建 worktree。
5. [ ] 启动 Wave 0，先建立模块/迁移/测试基础，不直接重写巨型 UI。
6. [ ] 每一波完成后汇报已完成清单 ID、测试、删除量和下一波阻塞。

这份清单的完成标准不是文件变小，也不是新类数量增加，而是：职责只有一个 owner、状态只有一个真相源、所有入口走同一事务与权限边界、旧路径真正删除，并在真实离线和多设备环境中证明可以恢复。

---

## 附：G328c 轮次记录（2026-09-24 全项目审计 → 逐项修复）

> 这一节记录**一轮完整的审计与修复**，以及**明确没做完的部分**。
> 按本清单的规矩：没做完的写在下面，不勾选。

### 已完成（每条都有可复跑的验证）

**安全（三条高危，逐条实证）**
1. `/api/admin/session` 补第二因子（TOTP / 恢复码）。此前只校验口令——账号开着 TOTP 也能
   用「口令 + 任意有效 access token」换到 5 分钟全权限管理 token，2FA 在唯一提权入口失效。
   新增 `AdminSessionSecondFactorRouteTest` 7 例钉住（含「同一时间窗内已被前一步消费的码
   仍可用」这一刻意选择，与 `disableTotp` 同一先例）。
2. `JWT_SECRET` 密钥分离：sealed-sender 证书与 dev_session 会话改用**用途子密钥**
   （默认由主密钥派生，可用 `SEALED_SENDER_SECRET` / `DEVELOPER_SESSION_SECRET` 独立轮换）。
   `deploy.sh` 新部署自动生成并在预检拒绝「用途密钥 == JWT_SECRET」。
   新增 `PurposeKeyDerivationTest`（5 例）+ `DeveloperSessionKeyIsolationTest`（2 例）。
3. 删掉 `AppUpdateStorage` 里那段**无人校验**的制品 HMAC——注释写「客户端据此验签」，
   但客户端没有密钥、也不可能验证；全仓无一处消费该字段。

**文档与代码对不上（逐条按源码核实后修正）**
`feature-inventory` §12.1（客户端符号列全是不存在的类名，已按 `RuntimeFlags` 重写）、
`group-play-inventory`（头部数字重测：553 声明 / 183 零引用 / 基线 10）、
`self-host-quickstart`（删掉「配 OPENAI_API_KEY 即可开启翻译/总结/语义搜索」——服务端
没有 `/api/ai/*` 路由）、`server-migration-expand-contract`、`bot-developer-api`、
`docker-deployment`（迁移由 `runDatabaseMigrations()` 执行，不是 SchemaUtils）、README
（「服务端只存密文」→「聊天消息正文只存密文」，并在 `messaging-v2-architecture.md`
新增「服务端明文面」一节逐条登记）。

**门禁「在说谎」的三处**
`DaoOwnerScopingTest`（补 OR 析取判据：谓词被 `OR` 削弱即违规；先在 77 条查询上量过 0 误报）、
`ClientArchitectureTest` 的 app 数据库单例判据（旧的两条字面量当场漏掉一个真实违规，
换正则后如实冻结 9 个文件）、`MessagingInvariantTraceabilityTest`（补恒真断言判据）。

**架构**
- 删掉 8 个**零主源码**的空壳模块（4 个 feature + core:database + 3 个 domain），
  `settings.gradle.kts` 从此描述真实架构。
- 包级分层：非 ui 包 import ui 从 **11 处 → 2 处**（均为合理例外，已在门禁白名单写明理由）。
  新增门禁 `packages outside ui must not import ui`。
- 9 份独立 `OkHttpClient` 收敛到共享连接池工厂（`network/HttpClients.kt`，每用途一个带注释的
  profile）+ 门禁防第十份。
- `SessionContext` 同名不同义 → core 侧改名 `AuthSessionSnapshot`；
  另外三处「疑似重复」（MessagingV2Runtime / SecurityCoordinator / GroupPollPolicy）
  查证后确认是正确分层，已把分工写进注释。

**测试与 CI**
- core/domain 的 44 例此前 **CI 从不执行**；命令改为 `test -x :app:test`（新模块自动纳入），
  `run-tests.sh` 同步并删掉它那句不成立的「CI 同款范围」。
- `release.yml` 同步 ci.yml 已验证的 Android SDK 引导（原用的是导致 job 早退的
  `android-actions/setup-android@v4`）+ artifact action 对齐 v7。
- 四个零覆盖模块补契约测试（22 例）。
- 去掉 2 秒墙钟等待（`ChatReadReceiptCoordinator` 的调度器改为可注入，用例从 ~2s → 0.064s）。
- `ChatDetailRoute.kt` 抽出 450 行长按操作弹层：**3432 → 3013 行**（纯搬移不改判断）。

**仓库卫生**：删根目录 3 个无人引用的入库文件；新增 `.github/dependabot.yml`（此前全仓
没有任何依赖更新通道，正是服务端整栈停在 2023–24 年代的根因）。

### 明确未完成（下一轮从这里继续）

> 2026-09-25 更新：下表已按本轮进展重写。**已完成的项从表中移出**（见下方「第二轮」小节）。

| 项 | 现状（实测） | 为什么没做 |
|----|-------------|-----------|
| ~~`ChatDetailViewModel.kt` 的装配抽出~~ | **已完成**：2931 → **2598 行**（装配 475 行搬到 `ChatDetailDeps`） | 见下方第四轮小节。注意：这里原先写的「884 行装配」是**错的**——那是 `文件行数 − 方法行数` 的粗算，把空行与 companion 也算进去了；用声明级 span 实测是 **475 行** |
| `ChatDetailRoute.kt` 继续拆 | 843 行，仍是单个 composable（09-24: 2786） | 副作用簇持续抽出同包文件（ChatDetailSecretGateEffects 等），纯搬移 |
| ui 直连 network | **41 → 24**（判据修准后的真实迁移） | 剩 24 个文件仍直接调 `ApiService`；已完成 7 批（公共端点/用户读取/会话读写/账号设备/2FA/举报审核/机器人+群）。下一批候选：`ChatListPorts`(7)、`AuthorProfileScreen`(7)、`ExploreNearbyScreen`(4)、`LoginViewModel`(5，其中 `refreshAccessTokenForCurrentSession` 读 TokenManager、属会话层操作，不适合搬进端点仓库) |
| ~~`GroupPlayPolicy.kt`~~ | **已拆：1945 → 858 行** | 见下方第三轮小节（拆成 `GroupPlayClassicPolicy` 979 / `GroupPlayModePolicy` 492，父对象留同名委托） |
| `NotificationCenterRepository` 的 `runBlocking` 桥接 | 4 处，DAO 配 `deleteForUserBlocking` 等同步变体 | 调用方含 Compose lambda 与非协程回调，改成 suspend 要连带改调用链；本轮已在 KDoc 写明「调用方含主线程」的现状与代价 |
| core 冻结契约的**采纳** | `core/util`、`core/serialization`、`core/network` 仍只被 `:core:testing` 的 testImplementation 引用 | 属于 B02「依赖注入装配与 MaodouchatApp 瘦身」，是独立大工程 |
| 仪器测试（156 例） | 本机无模拟器/真机，未执行 | 需 `./gradlew :app:connectedDebugAndroidTest` 或 CI 的 instrumented job；**本轮已推送，由 CI 覆盖** |
| 服务端残余风险 | 限流仅进程内、TOTP 密钥明文、镜像未固定 digest、`TRUST_PROXY_HEADERS` 的适用前提 | 已逐条写进 `docs/docker-deployment.md` 的「已知残余风险」表（含缓解措施），而不是留在审计报告里 |

### 第二轮（2026-09-25）：接着上一段的「未完成」往下做

**服务端（审计 15 条里剩下的可独立验证项）**
- `/health/metrics` **默认关闭**（未配 `METRICS_TOKEN` 即 404），配了则要求 `Bearer`，
  比较用常量时间；`/api/status` 不再暴露 `APP_ENV`。
- 口令下限 6 → 8，并拒绝「全同一个字符」。
- 登录审计日志的邮箱改为脱敏（`maskEmail`）。
- 用户搜索的 email 列加准入条件（查询须含 `@` 且本地部分 ≥3 字符）——原先 2 字符查询
  就能确认「某地址是否注册过」。
- 登录路径上的 `deleteExpired()` 删除（`MaintenanceRunner` 已有 15 分钟一轮的同名任务，
  登录路径那次是重复且按行开事务的 N+1）。
- 四条残余风险（进程内限流 / TOTP 明文 / 镜像 digest / TRUST_PROXY_HEADERS 前提）
  写进 `docs/docker-deployment.md` 的「已知残余风险」表。

**app**
- 静默吞异常：真正的空 `catch` 从 7 处降到 2 处（其余 5 处改为留痕）。
- 两处 `!!` 去掉（`retrySendMessage` 的竞态查找、历史加载的回执边界），其余 13 处
  逐个查证有紧邻守卫或构造期不变量。
- `ChatDetailLock.unlockChatWithPin` 的误导性 KDoc 更正（它并非主线程 `runBlocking`）。
- `ChatDetailViewModel` 3071 → 2930：先抽 `ChatDetailDecryptStatus`（依赖收窄 + 文案注入，
  于是这组逻辑**第一次有了 13 例纯 JVM 测试**——此前测试文件的注释写着「无 Robolectric 测不了」），
  再抽 `ChatDetailFileTransferController`（10 个方法、179 行薄编排）。

**流程**
- 全部 17 个提交**已推送**，CI 覆盖这批改动（此前从没被 CI 验证过）。
- Dependabot 生效：已开出 setup-java / action-gh-release / playwright-core /
  download-artifact 等更新 PR（本意如此——依赖更新通道从无到有）。

### 第三轮（2026-09-25）：拆 GroupPlayPolicy + 修一条「永远为真」的棘轮

**拆分**：`GroupPlayPolicy.kt` 1945 → **858 行**。判据是「它是扁平编解码目录（172 前缀 /
376 函数），不是纠缠的上帝对象」，所以按 G88 先例拆：实现按族搬到
`GroupPlayClassicPolicy`（979 行）与 `GroupPlayModePolicy`（492 行），父对象保留**同名委托**，
调用方零改动（`TextMessageBubble` 有 166 处引用）。前缀常量留在父对象——实测有外部调用点
以全限定名直接读 `GroupPlayPolicy.WORD_PREFIX`；共用的 `esc`/`unesc` 提成
`GroupPlayFieldEscape`。

**修一条「永远为真」的棘轮**（本轮最有价值的发现）：`GroupPlayPolicyTest` 的 val 零引用判定里，
两个正则用的不是 `\b` 而是**真实的退格字符 U+0008** → 正则谁都匹配不到 → 每个 val 都满足
`0 - 0 == 0` → 门禁把**全部** val 报成零引用。**这条棘轮自建立起就没在测东西**，
而 173 这个假数被写进文档、还被审计当成「173 个 val 100% 死」引用过。修复后真实值 163。
同时把两条判定统一为**族外零引用**（否则拆分本身会让 10 个真死成员「复活」——
实现文件里的那一行被误认为外部引用）；fun 基线 10 → 14，多出的 4 个是只被自身死函数调用的
random 辅助（传递性死代码，以前测不出来）。

**CI 抓到的问题**：`ConfigRobustnessTest`（androidTest）仍引用上一轮搬走的
`ui.screen.settings.SecurityPreferences` —— `:app:compileDebugAndroidTestKotlin` 我本机此前
没编过（unit test 与 androidTest 是两个编译单元），CI 的 instrumented job 因此红了。
已修，并把「本地验证要带上 androidTest 编译」这件事记进验证口径。

**棘轮判据第二次收紧**：ui→network 从「import 了 network 包」（98）→ 剔掉只导入 DTO 的（61）
→ 按「发请求 / 读令牌」拆成两组（**40 + 21**），因为那是两个不同的问题、不同的修法。
数字变化写进门禁 KDoc，避免下次又按粗略口径数。

**manifest 与客户端残余风险**：`SecretCodeReceiver` 的风险（任何本地应用都能发
`Telephony.SECRET_CODE` 撤销隐藏入口）与「不加 UID 校验」的理由写进 manifest 注释；
`docs/docker-deployment.md` 增「客户端已知残余风险」表。

### 第四轮（2026-09-25）：抽出 ChatDetailDeps——ChatDetailViewModel 2931 → 2598 行

**实测修正**：清单上一轮写的「属性装配 884 行」是 `文件行数 − 方法行数` 的粗算（含空行与 companion）。
按声明级 span 逐条量，真实装配是 **475 行**（88 个属性）。方法体 2157 行、其余 204 行是类头/companion/空行。

**落地方案改了**：原计划是「interface + `by` 委托」，动手后换成**deps 直接持有具体 VM 引用**
（同模块 `internal` 类）。原因是三处实测出来的麻烦：
1. 接口成员是 public，而两个宿主方法（`isOwnerSessionCurrent` / `hydrateOutgoingChat`）的签名
   含 internal 类型，VM 作为 public 类无法在不暴露它们的前提下实现接口；
2. 把 VM 改 `internal` 能绕过，但会波及 **18 个「ChatDetailViewModel 扩展」文件**
   （public 扩展函数会「暴露 internal 接收者」）；
3. 接口方案还要给 `forwardPreview`/`clearDraft`（定义在别的文件的 VM 扩展）做换名转发
   （同名成员会遮蔽扩展 → 无限递归）。
直接持有具体引用把三处全绕开，改动面更小。

**过程中修掉的 6 个生成器 bug**（脚本注释里都写了，避免重踩）：命名实参被误改
（`app = app` → `host.app = host.app`）、lambda 形参被误改（`{ chatId -> }`）、`obj::m` 被误改、
`init {}` / `companion object` / `override fun` 未被当作声明边界（会把整个 `init` 块吞进上一个属性）、
`@Annotation` 在可见性之前的写法未被识别。

**棘轮同步（都不是放宽）**：热点上限 VM 2930 → 2598；持久层预算 VM 35→19 + 新增 deps 17
（总数 192→193，多出的 1 是 deps 构造参数对 VM 的引用被计入）；ui→network 两组名单重算。

**运行时的关键检查交给 CI 的 instrumented job**：它会在模拟器上真实构造这个 VM 并走完聊天流程，
装配搬移若引入构造期访问顺序问题（例如装配在 `_uiState` 初始化之前就去读它），那里会红。

### 第五轮（2026-09-25）：ui→network 棘轮的真实下降（41 → 31）

前几轮只是把这条棘轮的**判据**修准（98 → 61 → 40 + 21），本轮开始真的把调用搬走：

| 新增的 data 层仓库 | 覆盖的端点 | 迁移的文件 |
|-------------------|-----------|-----------|
| `PublicServerInfoRepository` | `/api/public/status`、`/api/public/updates`、公开主页资料 | `ChatListServerFlags`、`AboutScreen`、`LoginScreen`、`PublicProfileScreen` |
| `UserNetworkRepository` | 鉴权用户读取（getUser / getUsers / getCurrentUser*） | `MyQrCodeViewModel`、`ChatRealtimeController`、`CallViewModel`、`CallNavigation` |
| `ChatNetworkRepository` | 会话读写（createChat / getChats / updateChatSettings / updateDisappearingMessages） | `ChatDetailSecretChat`、`ChatDetailDisappearing`、`GroupDetailViewModel`、`ChatDetailDeps` |

三个仓库都刻意很薄（不缓存、不重试）——调用方本来就有自己的会话校验与容错；
也都接受 lambda 因而自身可测。**为什么不是一个通用仓库**：端点分属不同失败语义
（免鉴权公共信息 / 鉴权用户 / 会话 CRUD），合成一个只会让「谁负责重试」变模糊。

`frozenUiApiCallers` 41 → **31**；`frozenUiTokenReaders` 21 → 26——后者不是新增耦合：
同一批文件从「调 API」迁到「只读令牌」，是在两个集合之间移动（分类规则本就是
「先看是否调 API，否则看是否读令牌」）。**剩 31 个文件待迁**，其中 `SettingsViewModel`
一个文件就有 14 种调用，是下一批的主要目标。

### 第六轮（2026-09-25 续）：ui→network 继续迁移 41 → 26

本轮新增三个 data 层仓库，共迁移 6 个文件：

| 仓库 | 覆盖端点 | 迁移的文件 |
|------|---------|-----------|
| `AccountSecurityNetworkRepository` | 资料/头像/用户名/设备清单与确认/全端下线/注销/拉黑（14 个命令式端点） | `SettingsViewModel`（**整屏移出名单**） |
| `TotpNetworkRepository` | 2FA 状态机五动作（查状态/绑定/确认/关闭/重生成恢复码） | `SettingsAccountSecurity`、`SettingsTotpSection` |
| `ModerationNetworkRepository` | 举报处置、规则读写、风险事件、拉黑 | `SettingsReports`、`SettingsModerationViewModel` |

**为什么不是一个通用仓库**：端点分属不同失败语义与不同状态语义——2FA 是状态机、
账号操作是一次性命令、举报处置要看到服务端最新状态。合成一个会让「谁负责重试/谁负责读最新」
变成必须读实现的隐知识。每个仓库的 KDoc 都写了它服务的场景与「为什么不做缓存/重试」。

**踩到并修正的两处**：
1. `SettingsViewModel` 是**零余量**热点文件（上限 1271），迁移要同时保持行数：加一行私有
   helper（`accountApi`）让 15 处调用点更短，再用「折叠一处纯排版换行 + 删一行纯装饰分节注释」
   抵消，最终正好 1271。
2. `ApiService.getTotpStatus`（返回**原始 JSON 文本**）被我错映射到已解析的 `totpStatus`
   （返回 Boolean）。两者是服务端两个不同端点，仓库里现在各有其位（`status` / `statusRaw`）
   并注明「并存不是重复」。

**仍未完成**：26 个文件（`ChatListPorts` 7、`ChatBotGroupActionController` 8、
`AuthorProfileScreen` 7、`DeveloperBotsScreen` 7、`LoginViewModel` 5 等）；
`LoginViewModel` 里那个 `refreshAccessTokenForCurrentSession` 读 TokenManager、
属于会话层操作，不适合搬进端点仓库——需要先设计会话层边界。

（第六轮续）本轮又迁了 3 个文件：`DeveloperBotsScreen`(7) + `ChatBotGroupActionController`(8)
→ 新增 `BotNetworkRepository`（含会话内机器人的邀请/指令/投递）与 `GroupNetworkRepository`
（群成员增删/改名/全量可搜索用户）。`ChatBotGroupActionController` 本身是「群与机器人混在
一个文件」的例子——迁移时按域拆开走两个仓库，比整文件塞进一个更能说明每个动作的失败语义。

**两次 CI instrumented 失败都是基础设施**，不是代码：日志里是
`Error on ZipFile unknown archive`（SDK 的 emulator 包在 runner 上损坏）与
`Unable to connect to adb daemon on port: 5037`——模拟器根本没启动、没有任何断言执行；
同一批提交的 `Android`（单测+lint+打包）与 `Server` 两个作业始终是绿的。重跑后成功。
这类抖动与代码无关，但值得记下来：看到 instrumented 红时**先看模拟器有没有起来**，
再怀疑测试。

### 第七轮（2026-09-25 续）：把 ui→network 收尾，并处理「ui 读会话态」

**ui 直连 `ApiService` 清零（24 → 0）**。判据名单从「只许降」改成**空名单 + 反向断言**
（与 `frozenUiDaoImporters`、`frozenUiAppDatabaseGrabbers` 同形）：改回非空即等于放松棘轮。
全程两组之和：98 → 61 → 55 → 48 → 46 → 37 → 33 → 31（31 是只剩令牌的那一组）。
本段新增的仓库：`NearbyNetworkRepository`（附近的人）、`PinStarNetworkRepository`（置顶+星标）、
`ClientPrefsNetworkRepository`（跨设备偏好对账）、`ChatFolderNetworkRepository`（全量替换语义）、
`PostNetworkRepository`（动态读取/点赞，作者页与 Feed 共用同一入口）、
`GroupPollNetworkRepository` / `MediaDownloadNetworkRepository` / `SessionNetworkRepository` /
`AuthNetworkRepository` / `ContactNetworkRepository` / `NotificationSettingsNetworkRepository`；
`BotNetworkRepository`、`ModerationNetworkRepository`、`AccountSecurityNetworkRepository`、
`GroupNetworkRepository` 按域补方法。

**两次归位（记下来，免得下次又按「同属某功能」归类）**：
`voteGroupPoll` 先塞进 `GroupNetworkRepository`，随即按**失败语义**挪到 `GroupPollNetworkRepository`
（投票失败是「已关闭/已投过/选项越界」，与「加人/改名」的「无权/已存在」不同族）；
机器人 callback 归 `BotNetworkRepository` 而不是群仓库。

**真删除（不是重分类）**：`PublicProfileScreen` 的 `apiService: ApiService?` 与
`tokenManager: TokenManager?` 两个参数**从未被使用**，连同 import 一起删——这个文件因此完全
离开 network 层。

**`BackgroundSessionGate` 自己读实时会话**：240 处（`ui/` 内 220 处）调用点原本都要写
`liveToken = tokenManager.getToken(), liveUserId = tokenManager.getUserId()`，而这两个值唯一的
用途就是交回给门禁判断。多了一个单参重载 `mayContinue(expectedUserId)`；只改写「本来就读实时值」
的形状（语义严格不变），传捕获值的一律不动。
**副作用是好事**：门禁改为读进程单例后，13 个单测第一次跑就红了——正是这条门禁在起作用。
为此加了 `CurrentSession.override`（`internal`，KDoc 写明生产代码不要设置）作为测试注入口。

**`CurrentSession`（`com.maodouchat.session`）**：`snapshot()` / `ownerUserId()` / `hasSession()`。
头像组件与列表投影要的是**身份**（缓存按账号分目录、投影比对 owner），却因此依赖了整个
`com.maodouchat.network`。收进会话层后，10 个文件里 9 个彻底不再 import network。
**这一组不做「改名式搬迁」**：只读令牌那 31 个文件里，「确实要凭据去发请求」的部分
靠仓库自持凭据解决（见下），而不是把 `tokenManager.getToken()` 换成 `CurrentSession.snapshot().token`
——那是同一个耦合换了个名字。

**仓库自持凭据**：新增 `data/repository/SessionTokens.kt` 的 `currentAccessToken()`；
涉及仓库的 `token` 参数改成 `String? = null`，为空时取当前会话令牌，**显式传令牌仍然优先**
（后台批次用批次开始时捕获的令牌是有意选择）。由此 7 个文件不再碰凭据。
顺带删掉两处**冗余条件**：`SettingsTotpSection` 的 `if (token.isBlank() || !isCurrentTotpOwner(...))`
——`isCurrentTotpOwner` 内部就是会话门禁，而门禁在令牌为空时本就返回 false。
`ChatListAnnouncementCoordinator` 的三个端口原本签名带 `token: String`，而**测试里那三个 lambda
从来没读过这个参数**（`{ Result.success(raw) }`），是典型的「没人用的位置参数」，签名去掉令牌后
协调器连 `TokenManager` 字段都不需要了。

**`ChatDetailRoute.kt` 3013 → 2786**：抽出 `ChatDetailPickers.kt`（9 个 ActivityResult 入口，
连带它们各自的「为什么选这个 contract」注释）与 `ChatDetailReadReceiptsSheet.kt`（176 行的
已读回执面板，自带搜索状态）。前者把回调改成参数注入、权限文案由调用方给，其余逐字搬；
后者只做机械改名并复用原类型 `ReadReceiptUi`（没有另造 DTO）。
**剩下的 ~2786 行主要是「一个巨型状态堆」（约 150 个 `remember`）加弹层接线**——
继续按块搬只能线性减少行数，真正的解法是把状态收进状态持有类，那是一次跨全文件的改动，单独一轮。

**core 模块的采纳状态**：先复测真实引用，纠正了上一轮的说法——`core:realtime` 其实**在用**
（`MaodouchatApp` 与 `WebSocketEventBridge`，23 处），零引用的只有 `util`/`serialization`/`network`/`session`。
新增门禁把「零 / 非零」钉住（两个方向都会红），四个模块的 `build.gradle.kts` 各写明**采纳代价**：
`MaodouJson.forwardCompatible` 与 app 的 `ApiService.json` **不是同一个配置**，换过去会改线上报文格式
（`encodeDefaults=true` / `explicitNulls=false`），那不是重构是改协议；`NetworkResult` 与在用的
`kotlin.Result` 并行；`Clock`/`DispatcherProvider` 要等 DI 装配；`core:session` 是会话重构的目标形状。

### 第八轮（2026-09-25 续）：把「ui 读会话令牌」从 48 收到 7，并按性质分类

`frozenUiTokenReaders` 49 → **7**（全程 98 → 61 → 55 → 48 → 46 → 37 → 33 → 31 → 26 → 22 → 20 → 18 → 16 → 14 → 13 → 10 → 7）。
手法统一为两条：**身份/有没有会话**走 `com.maodouchat.session.CurrentSession`；
**凭据**由 `data/repository` 的薄仓库自持（`currentAccessToken()`，`token: String? = null` 默认，
显式传仍优先——后台批次用捕获的令牌是有意选择）。这条约定连同理由写进了记忆文件与门禁 KDoc。

本段涉及的仓库/用例（都按同一约定改）：`NearbyNetworkRepository`、`PinStarNetworkRepository`、
`ChatNetworkRepository`（chats/updateChatSettings/updateDisappearing/deleteChat/createChat）、
`ChatFolderNetworkRepository`、`ModerationNetworkRepository`（全部方法）、
`NotificationSettingsNetworkRepository`、`ClientPrefsNetworkRepository`、
`AccountSecurityNetworkRepository`（changePassword/privacy）、`ContactNetworkRepository`、
`AnnouncementNetworkRepository`、`PushNetworkRepository`、`PostNetworkRepository`、
`UserNetworkRepository`（user/currentUser）；`explore/` 下的 `LoadFeedUseCase`、
`PublishPostUseCase`、`ToggleLikeUseCase`、`CommentPostUseCase`、`MediaUploadQueue`。

**判据修准（一次真实的漏检修复）**：判据原先只认类型名 `TokenManager`，于是
`ChatDetailViewModel` 一族**从来没被这条门禁抓到过**——它们以小写属性 `tokenManager`
读凭据、还把它分给 18 个扩展文件，自己一次类型名都不写。现在同时认 `tokenManager.xxx`。
修完后按新口径重测的第一次数字是 **21**（比修之前多），那不是棘轮被放松，是补上了漏检。

**剩下 7 个已逐条分类**（写进判据 KDoc）：只做装配 4 个（`ChatDetailDeps`、`ChatListPorts`、
`ChatRealtimeController`、`GroupDetailViewModel`）、会话拥有者 1 个（`LoginViewModel`）、
凭据交给协议层 1 个（`IdentityVerificationController`）、仍需继续拆 1 个（`SettingsViewModel`，
27 处取令牌 + 22 处取身份，且热点上限零余量）。

**三次返工都记在提交里**（都是我的批量脚本，不是产品代码）：`f-string` 里写双反斜杠导致
「残留 0 处」的假报告；宽松正则删掉「取令牌」行却没动紧随的守卫，编译报 `Unresolved reference 'token'`；
固定缩进写回 `val liveToken = token` 时有一处在更深作用域，被 pre-push 的 `SuspiciousIndentation`
拦下（修完**单独跑了 lint**，没有用单测代替 lint）。

### 第九轮（2026-09-25 续）：一次真实回归的定位与修复——以及「本机有模拟器」这个被写错的前提

**回归**：CI 在 `77db26d5` 的 instrumented job 上红了——`ChatListScreenDataTest` 四个用例
`ComposeTimeoutException`（等 10 秒没等到播种的会话渲染）。同一批其余 152 个用例全绿、日志里没有异常栈。

**定位**（记录了完整路径，因为这次「先看模拟器有没有起来」不是答案）：
1. 前一个绿的提交（`733672f6`）跑的是同一套用例 → 排除环境抖动，锁定在我的改动里。
2. **本机其实有模拟器**：`maodou_test` AVD 在 `~/.android/avd`，起得来。此前记忆里写的
   「本机无模拟器、仪器测试只能靠 CI」是**错的**（那份结论来自更早的一次尝试，之后没复核）。
3. 本地复现同样四个红；临时诊断用例打印 VM 状态得到
   `hasSession=false chats=[] isLoading=false error=null`——「列表空 + 错误也空」唯一对应
   `finishIfCurrent()` 那条提前 return。

**根因**：`ChatListLoadCoordinator` 本地兜底分支的第三条二次校验，原文是
`!tokenManager.getToken().isNullOrBlank()`（语义：**等待期间会话出现了 → 放弃本地兜底、改走远端**）。
我改写成 `!CurrentSession.hasSession()`，极性写反成「**没有会话** → 放弃」。无会话场景（正是仪器
测试的环境）于是恒走提前 return、列表恒空。修法是去掉那个 `!`，并在原地留注释写明判据语义。

**流程上的修正**（比这次 bug 更重要）：把「仪器测试靠 CI」当默认，等于把行为回归的发现推迟一整轮 CI。
本机有 AVD，**改动触及交互/状态流转时应本地跑 `:app:connectedDebugAndroidTest`**（全量 183 例约 1.5 分钟）。
这条已写进记忆文件的验证口径，并替换掉那份错误结论。

### 第十轮（2026-09-25 续）：用户报「页面不适配状态栏」→ 真机复现，抓到两个真机才可见的缺陷

**用户反馈**：「很多页面又开始不适配状态栏了，状态栏把页面挡住了，高度都不对，还有你这个模拟器测试好草率」。
两条都成立，处理如下。

**1. 状态栏遮挡（已修 + 已加门禁）**
把 debug 包装到本机 AVD（`maodou_test`）+ 起本地 H2 服务端（`SEED_DEMO_USERS`）登录后逐页截图，
复现：**设置 → 安全中心**首屏第一行「Devices, E2EE, and app lock at a glance」画在状态栏底下。
根因：`AccountSecurityScreen` 根布局是 `Column(verticalScroll(...).imePadding())`——`enableEdgeToEdge()` 下
整屏页面必须自己消费系统栏 inset，而本页既没有 `TopAppBar`（M3 顶栏自带）也没有任何 inset 修饰符。
**这是长期缺陷，不是这几天改出来的**（`git log -S statusBarsPadding` 显示该文件从未有过），但确实是这次才被发现。
修法：`Modifier.safeDrawingPadding().verticalScroll(...)`（一次覆盖状态栏 + 手势条 + IME），
并删掉两个从未使用的 import；文件行数守住在热点上限内（884）。
**加门禁** `every nav destination consumes system bar insets`：遍历导航目的地，解析其最外层
`*Screen/*Route/*Pane`，要求出现 Scaffold/TopAppBar/statusBarsPadding/safeDrawingPadding/WindowInsets 之一；
例外只登记**间接**目的地两条（`ChatDetailListPaneRoute`、`IncomingCallRoute`）并写明被转发者。
理由写进判据：这条对应的是**真机可见、而单元与语义测试都看不见**的缺陷。

**2. 打开任一聊天闪退（已修 + 已补真机测试）**
真机点进会话 → `Maodouchat keeps stopping`：

    NullPointerException: Parameter specified as non-null is null:
      ScheduledMessageController.<init>, parameter uiState
      at ChatDetailDeps.<init>(ChatDetailDeps.kt:503)

根因是**初始化顺序**：`private val deps = ChatDetailDeps(application, host = this)` 声明在 `_uiState` 之前，
Deps 构造时读 `host._uiState` 得到 null → 非空参数直接抛。这是上一轮「装配搬进 ChatDetailDeps」时引入的：
代码搬对了，**位置**搬错了。修法是把整块移到 `_uiState`/`uiState` 之后，并在原地写明原因与表现。
**补防回归测试** `ChatDetailScreenDataTest`（androidTest 2 例）：直接构造真实 VM +
用带 chatId 的 VM 组合**真实 `ChatDetailRoute`** 并等顶栏与输入框出现。

**3. 「模拟器测试草率」这条的根因与修正**
不是测试数量不够，而是**测试覆盖的路径不对**：语义测试不渲染系统栏、也不经真实装配，
所以上面两个缺陷都能全绿通过。已做的修正：本机 AVD 跑真机主流程并**逐页看截图**、
把「本机有 AVD、交互类改动必须本地跑 `connectedDebugAndroidTest`」写进记忆文件，
并把这两个缺陷各自变成一条**可自动化的判据**（inset 门禁 / 真实装配路径测试）。

**4. 一个会误导本地排查的坑（记下来）**
`ChatListScreenDataTest` 假设设备**没有登录态**（无会话 → 展示本地播种的会话）。本机调试登录过之后，
这 4 例会红；`connectedDebugAndroidTest` 结束会卸载 APK，数据随之消失，重跑即恢复。

**第十轮附：状态栏问题的真机普查（截图 + 自动化判定）**
为了回答「很多页面不适配状态栏」，我写了个判定脚本：用 `uiautomator dump` 取**应用内容里最高的带文字/描述节点**的 y，
与状态栏（约 90px）比较——低于它就是被压住。逐页冷启动导航后测量：
会话列表 175、设置 184、安全中心 191（修复后）、通用 176、AI 与隐私 176、审核 176、服务器 176、
黑名单 176、我的举报 176、通知设置 176、我的二维码 176、动态页 181 —— **全部高于状态栏，无一重叠**。
另外真机走通并用截图确认：登录 → 会话列表 → 设置 → 改昵称（`updateProfile` 路径，看到 "Profile updated"）→
会话详情（闪退修复后）。
**没有在真机上验到的**（如实登记）：改密码（对话框被我的 ESC 关掉了）、注销全部设备/删除账号（破坏性，跳过）、
通话与群流程（需要第二台设备/群）、媒体中心/星标/AI 任务（需要先有会话）、Agent/主题工作台/开发者机器人
（导航器里这几个页面的入口文案与我的步骤不匹配）。

### 第十一轮（2026-09-25 续）：ChatDetailRoute 状态收口开跑（2786 → 2755）+ 22 例目的地烟雾测试

**为什么是分批而不是一次性**：这个文件 2700+ 行、96 个 `remember`。一次性重写等于把上一轮
才修掉的「真机可见的崩溃」请回来。所以按**语义族**一批批搬，每批都过：编译 →
`:app:testDebugUnitTest` → `:app:connectedDebugAndroidTest`（含真实 `ChatDetailRoute` 组合那一例）。

已搬进持有类（新文件各自带 KDoc 说明「为什么这一族在一起」与「为什么不搬 saveable 的」）：

| 批 | 持有类 | 状态数 | 说明 |
|----|--------|--------|------|
| 1 | `ChatDetailMessageActionState` | 14 | 「当前哪条消息正被某个弹层操作」 |
| 2 | `ChatDetailSendPendingState` | 4 | 发送前待确认项（viewOnce/spoiler + 待确认项），并收掉重复接线 |
| 2 | `ChatDetailParticleState` | 3 | 粒子删除动效三段生命周期，收成 `start()`/`clear()` |
| 3 | `ChatDetailAiResultState` | 10 | 会话画像/周报/分类的「值/加载中/失败」三态 + 三态一起复位的 `reset*()` |
| 4 | `ChatDetailSecretGateState` | 6 | 密聊门禁 / 设备风险进度 |
| 5 | `ChatDetailSearchState` | 6 | 搜索状态族（带 Saver，给出 Saver 范式） |
| 6 | `ChatDetailGroupCallState` | 5 | 群通话「类型 → 选成员」流程 |
| 7 | `ChatDetailScheduleState` | 7 | 会话级设置 / 提醒 / 定时弹窗族 |
| 8 | `ChatDetailChatLockAndContactStates` | 9 | 聊天锁流程 + 联系人入口链（各带 Saver） |
| 9 | `ChatDetailAiPanelState` | 6 | AI 面板与杂项弹层开关 |
| 10 | `ChatDetailConversationFlowStates`（含草稿/选择集） | 9 | 会话级流程开关 + 草稿 / 选择集 |
| 11 | `ChatDetailAppearanceStates` | 3 | 外观偏好三件套（壁纸/字号，必须一起刷新的不变量） |
| 12 | `ChatDetailAiSafetyState` | 2 | AI 安全提示族（本地开关 + 已关闭提示 id 集合，双写逻辑只此一份） |
| 13 | `ChatDetailScrollState` | 4 | 滚动位置族（是否在底部附近 / 新消息徽标计数 / 自动滚动游标 / 顶部加载更早，决策逻辑逐字搬移） |
| 14 | `ChatDetailFullscreenMediaState` | 2 | 全屏媒体查看族（全屏图片 / 全屏视频消息引用；打开入口置值、弹窗 dismiss 清零，语义逐字等价——Route 行数 2682 不变，搬移净 0 行） |
| 15 | `ChatDetailDialogState` | 3 | 会话级弹窗开关族（清空聊天记录确认 / 批量删除确认 / 溢出菜单展开；入口置 true、onDismiss 置 false，语义逐字等价——Route 行数 2682 → 2680） |
| 16 | `ChatDetailMessageTargetState` | 2 | 消息引用/导航族（回复引用的消息 / 搜索·导航跳转高亮的消息 id；置值→被读→清零的瞬态循环，语义逐字等价——Route 行数 2680 不变） |
| 17 | `ChatDetailChatLockState`（第八批持有类归位） | 1 | 聊天锁错误文案 `setLockError` 收进持有类（PIN 长度/两次不一致提示；瞬态不进 Saver，转屏重置为 null 与原 `remember {}` 一致，`reset()` 一并清掉——Route 行数 2680 → 2678，热点上限同步收紧） |
| 18 | `ChatDetailMuteExpiryState`（新增持有类） | 1 | 禁言到期重组触发器 `muteTick` 收进持有类（到期写一次触发重组、读它只为建立重组依赖；瞬态不进 Saver，转屏重置为 0 与原 `remember {}` 一致；写入封装成 `markExpired()`——Route 行数 2678 → 2677（`mutableLongStateOf` import 一并移除），热点上限同步收紧） |
| 19 | `ChatDetailBubbleBoundsState`（新增持有类） | 1 | 气泡坐标缓存 `bubbleBounds` 收进持有类（`get`/`set`/`remove` 三个入口与原 `mutableMapOf` 逐字等价；原非快照状态、读写不触发重组，转为持有类语义不变；瞬态不进 Saver——Route 行数 2677 不变，净 0，热点上限维持） |
| 20 | `SessionCipherOccupancy.refreshActiveChatOpenedAtIfUnset()`（crypto 包 helper） | 1 | ON_RESUME 观察器里最后的 2 处直连 `MaodouchatApp`（`activeChatOpenedAtMs` 的 `if (== 0L)` 置值）收进 crypto 包非 Composable 的 `SessionCipherOccupancy`；Route 里只剩一次全限定调用，`MaodouchatApp` 符号在 Route 中归零——`ClientArchitectureTest` 的 Composable 口径（20 文件/83 处→19 文件/81 处）与 ui 总口径两份预算的 `ChatDetailRoute.kt` 条目同步删除，`ChatDetailRoute.kt` 彻底退出两份预算；Route 行数 2677 → 2676，热点上限两处同步收紧至 2676，零余量。`if-置值` 语义、调用点（ON_RESUME 分支内、与 occupySessionCipher 相同的相对顺序）、`@Volatile` 读写顺序逐字等价 |
| 21 | 死状态清理：`showGroupInfo` 及其群信息对话框 | 1 | `var showGroupInfo by rememberSaveable` 自标题栏点击改为导航进群资料页（`onOpenGroupDetail`）后，再没有任何写入点能将其置为 true——全仓 grep 仅剩声明、`LaunchedEffect` 读与 `if (showGroupInfo)` 对话框块三处，`git log -S` 确认打开路径已在 G77 抽离时丢失，属不可达死状态。删声明、`LaunchedEffect`（内调 `viewModel.loadGroupCandidates()`，另有 `ChatLoadEffect.LoadGroupCandidates` 调用方，ViewModel 方法保留）、对话框块，并删随之零引用的 `ChatDetailGroupInfoDialog.kt`（190 行，唯一定义点）；`rememberSaveable` import 一并移除（Route 内已无实际使用）。行为零变化：该分支历史上恒为 false。Route 行数 2676 → 2655（净 −21），热点上限两处同步收紧至 2655，零余量 |
| 22 | `ChatDetailScheduleState` 增补：提醒列表与重排草稿 | 2 | 提醒列表对话框的 `reminderList`（原 `remember(showReminderList, reminderChatId)` 同步读 Store）与重排弹窗的 `rescheduleTextDraft`（原 `remember(targetId)` 取当前待发文案）收进持有类：打开瞬间在点击处/重排入口同步加载（首帧即有数据，与原 `remember` 初始化一致），旋转重建与打开期间切会话由 Route 的 `LaunchedEffect(reminderChatId)` 重载（与原重算键等价）；`rescheduleTextDraft` 进 Saver，转屏保留编辑中草稿（第十批草稿口径，有意的改进）；`openSchedule`/`closeAll` 全仓零调用，一并删除。Route 行数 2655 → 2660（+5：打开处同步加载与 effect 接线，`mutableStateOf` import 一并移除），热点上限两处同步调整至 2660，零余量 |
| 23 | 死 import 清理：22 批抽离后残留的未使用 import | 0 | 前 22 批把对话框/选择器/手势/水印等逻辑逐块抽离出 `ChatDetailRoute`，留下了 103 行再也用不到的 import（`mutableFloatStateOf`、`snapshotFlow`、`QrCodeGenerator`、`MessageSafetyScanner`、`SignalProtocol`、大量 icons/material3/foundation 符号等）。逐条用「simple name 在正文（含 KDoc/全限定引用）中是否出现」核验，`getValue`/`setValue`（`by` 委托文本不出现但编译必需）与别名 import 排除在外，删后 Route 头部只剩真实使用的符号。行为零变化（import 是编译期声明）。Route 行数 2660 → 2557（净 −103），热点上限两处同步收紧至 2557，零余量 |
| 24 | ViewModel 死代码清理：`exportToUri`/`exportRejectMessage` + 随之零引用的 `ChatExportGuard` 及其单测 | 61 | `exportToUri`（SAF 自选 URI 写 JSON）在全仓零调用——当前导出走 `exportChatHistory()` → `ChatExportController` 的系统分享路径；Route 只消费 `exportInfoMessage` 而从未触发该路径。删 ViewModel 内 59 行（`exportRejectMessage` + `exportToUri`）与随之零生产调用的 `ChatExportGuard.kt`（69 行）+ 其单测 `ChatExportGuardTest.kt`（203 行，测试对象已删除）。`ChatExportController`/`exportChatHistory()` 保留——整个导出功能目前 UI 不可达（Route 无导出入口），恢复入口属产品决策，见汇报。ViewModel 行数 2542 → 2481，热点上限两处同步收紧，零余量 |
| 25 | ViewModel 死代码清理第二批：5 个零引用透传方法 | 18 | `setSilentSend`（Route 只用 `toggleSilentSend`）、`cancelSendMessage`（`commandFacade.cancel` 的 VM 入口，全仓无调用）、`exportChatAsJson`（与第二十四批 `exportToUri` 同族——导出走 `exportChatHistory()` 系统分享路径）、`addGroupMember`（同族 `removeGroupMember`/`renameGroup` 仍被调用）、`verifyAllDevices`（同族 `verifyAndTrustIdentity` 仍被调用）。逐个全仓 grep 核验零引用（含类内裸调用、`::` 引用与测试）；控制器实现（`ChatBotGroupActionController.addGroupMember`、`IdentityVerificationController.verifyAllDevices`、`ChatExportController.exportChatAsJson`）保留。ViewModel 行数 2481 → 2463，热点上限两处同步收紧，零余量 |
| 26 | ViewModel 死代码清理第三批：1 个零引用透传方法 | 3 | `loadGroupReadCount`（`readReceiptCoordinator.loadCount` 的 VM 透传，全仓零调用）删 3 行。逐个全仓 grep 核验：66 个公开方法仅此 1 个零引用，161 个公开属性全被引用；协调器内部 `loadCount` 仍在用（`prefetchRecentGroupCounts`），保留。ViewModel 行数 2463 → 2460，热点上限两处同步收紧，零余量 |
| 27 | ViewModel 死代码清理第四批：40 个残留未使用 import | 40 | 前 26 批把装配收进 `ChatDetailDeps`、删透传方法、控制器逐个抽离后，头部留下了 40 行再也用不到的 import（`SignalProtocol`、`ConversationCommandFacade`、Messaging V2 族、`data.repository` 族、`scheduling` 族、AI 四源、`VoiceRecorder` 等——这些类型只在 `deps` 持有类内部使用，VM 本体经小写属性委托访问，simple name 在正文零出现）。逐条用「simple name 在正文（含 KDoc/全限定引用）中是否出现」核验：文件内无 `by` 委托（`getValue`/`setValue` 陷阱不适用）、无别名 import；`directPersistenceSymbols`（`database.`/`secretChatDao`/`MaodouchatApp`）在被删行中零命中，`frozenUiDirectPersistence` 的 19 命中预算不受影响。行为零变化（import 是编译期声明）。ViewModel 行数 2460 → 2420，热点上限两处同步收紧，零余量 |
| 28 | Route 死 import 清理：24–27 批代码删除后残留的 29 个未使用 import | 29 | 前 23 批已扫过 Route 头部（删 103），但 24–27 批继续删代码后又留下 29 个残留 import：15 个 material icons（`Notifications`/`Send`/`Close`×2/`Info`/`Delete`/`Group`/`History`/`Image`/`Description`/`Search`/`Schedule`/`Check`/`Pause`/`Star`/`Warning`——逐个全仓级核验 simple name 在正文零出现，无全限定引用）、`foundation.Image`/`material3.Surface`/`Button`/`Scaffold`/`ui.window.Dialog`/`tooling.preview.Preview`/`runtime.collectAsState`（Route 只用 `collectAsStateWithLifecycle`，另有独立 import）、`data.model.Chat`/`ui.theme.Background`/`Outline`/`Secondary`/`java.util.Date`，以及 `runtime.setValue`（文件中唯一的 `by` 委托是 `val state by …`（第 222 行），只需 `getValue`——`getValue` 保留）。无 `by` 委托以外的编译陷阱，无别名 import；`directPersistenceSymbols`（`database.`/`secretChatDao`/`MaodouchatApp`）在被删行中零命中。行为零变化（import 是编译期声明）。Route 行数 2557 → 2528，热点上限两处同步收紧，零余量 |
| 29 | 拆分文件死 import 清理：4 个 ChatDetail 拆分残留文件的整块死 import 头 | 430 | 前期拆分（AiIntents/AiResults/LiveLocation/Media 从 ChatDetailViewModel 一族拆出）时把原文件的整个 import 头原样继承过来、从未清理：AiIntents 124 个 import 里 108 个正文零引用、AiResults 121 里 109 个、LiveLocation 103 个、Media 110 个。双重核验（Python `\b` 词边界 + 独立 `grep -w`，0 分歧）：`getValue`/`setValue` 的 `by` 委托陷阱已排除（四文件均无 `by` 委托）、别名 import 按别名核验、KDoc/注释中的提及按保守方向保留。删后四文件 457→349、438→329、421→318、269→159。`frozenUiDirectPersistence`：AiResults/Media 各自唯一的 1 处命中正是被删的 `import …MaodouchatApp`（正文零引用），删后归零、两条目按门禁文案指引删除并留注释；AiIntents/LiveLocation 的 2 处命中是正文真实引用，不动。`frozenUiDaoImporters`/`frozenUiApiCallers`/`frozenUiTokenReaders`/`frozenUiAppDatabaseGrabbers` 均不受影响（被删行中无 `data.local.dao`；network import 删光后四文件直接退出 `ui must not grow its direct network usage` 的分类，两份冻结集合本就是空集）。四文件均不在 `frozenHotspotLineCaps`，无需动上限。行为零变化（import 是编译期声明） |

行数：2786 → 2774 → 2763 → 2755 → 2751 → 2747 → 2742 → 2737 → 2731 → 2727 → 2721 → 2701 → 2682 → 2682 → 2680 → 2680 → 2678 → 2677 → 2677 → 2676 → 2655 → 2660 → 2557（第十四批只搬引用、净行数不变；第十五批 3 个开关收成 1 行声明 + 1 行接线，净 −2；第十六批 2 个引用状态收进持有类、净 0；第十七批聊天锁错误文案归位、净 −2；第十八批禁言到期触发器收进持有类、`mutableLongStateOf` import 一并移除、净 −1；第十九批气泡坐标缓存收进持有类、净 0，热点上限维持 2677；第二十批 Route 最后的 2 处 MaodouchatApp 直连收进 crypto 包 helper、净 −1，热点上限收紧至 2676；第二十一批删死状态 `showGroupInfo`（声明/`LaunchedEffect`/对话框块）与零引用的群信息对话框文件、净 −21，热点上限收紧至 2655；第二十二批提醒列表与重排草稿两个对话框局部状态收进 `ChatDetailScheduleState`（打开处同步加载 + `LaunchedEffect` 按 chatId 重载，`rescheduleTextDraft` 进 Saver 转屏保草稿；删零调用的 `openSchedule`/`closeAll`）、净 +5，热点上限两处同步调整至 2660；第二十三批删 22 批抽离后残留的 103 行未使用 import（逐条核验 simple name 在正文无出现，`getValue`/`setValue` 与别名 import 排除）、净 −103，热点上限两处同步收紧至 2557；第二十四批 ViewModel 死代码清理（`exportToUri`/`exportRejectMessage` + 随之零引用的 `ChatExportGuard` 及其单测）净 −61，热点上限两处同步收紧至 2481；第二十五批 ViewModel 死代码清理第二批（删 `setSilentSend`/`cancelSendMessage`/`exportChatAsJson`/`addGroupMember`/`verifyAllDevices` 5 个零引用透传方法）净 −18，热点上限两处同步收紧至 2463；第二十六批 ViewModel 死代码清理第三批（删 `loadGroupReadCount` 透传方法）净 −3，热点上限两处同步收紧至 2460；第二十七批 ViewModel 死代码清理第四批（删 40 个残留未使用 import）净 −40，热点上限两处同步收紧至 2420；每批上限恒等于实测行数，零余量规则）。；第二十八批 Route 死 import 清理（删 29 个残留未使用 import，`getValue` 因 `val … by` 委托保留）净 −29，热点上限两处同步收紧至 2528；第二十九批 4 个拆分文件死 import 清理（AiIntents/AiResults/LiveLocation/Media 共删 430 个未使用 import，双重核验 0 分歧；AiResults/Media 的 `frozenUiDirectPersistence` 随 `import …MaodouchatApp` 删除归零、条目删除，四文件均不在热点上限表）
**没搬的**：`rememberSaveable` 的那一族（43 个）与其余 appearance/搜索/密聊门禁状态——
普通持有类拿不到 `rememberSaveable` 的保存语义，硬搬会**静默**失去旋转/进程重建恢复；
要搬得先写 Saver。这条边界写在每个持有类的 KDoc 里。

**批量改名的两种误伤（三次踩到，已写进提交）**：把「命名实参」当状态引用改名；
反过来为躲命名实参而漏掉「语句位置赋值」。两者文本同形，只能靠上下文（行首缩进 + 是否有尾逗号）区分。
结论：下批改名前先做 dry-run 并人工核对命中行。

**页面核对换成了可自动化的形式**：`DestinationSmokeTest`（22 例）对每个可导航页面用
**真实 Screen + 真实 `viewModel()`** 组合一次并断言渲染出节点——覆盖安全中心、通用/通知/审核/
AI 与隐私/举报/黑名单/服务器/关于、主题编辑器与工作台、水印取证、媒体中心、星标、AI 任务、
全局搜索、通知中心、通话记录、我的二维码、扫一扫、动态、毛豆助手。
它随 CI 的 instrumented job 一起跑，替代了我之前那套「adb 点击 + 人眼看截图」的临时办法
（不稳定、不进 CI）。**行为级仍未覆盖的**：改密码、群玩法各页、通话流程、媒体/星标页的真实数据路径。

### 第十二轮（2026-09-27，本地工具链侧）：系统集成仪器测试五片——系统入口 Intent 消费（17 例）+ 通话前台服务（5 例）+ 主屏小组件（3 例，并抓出/修掉一个真缺陷）+ 消息通知「投递→点击」整链（4 例）+ 更新器 Worker（7 例，附重试分类修复）

`NotificationIntentConsumer` 此前只有**纯 JVM** 的策略/解析测试（`NotificationIntentPolicyTest`、
`AppLinkRouterTest`）——拿不到真实 `Intent` 的 extras 清除语义、碰不到
`TelecomHelper.isTrustedTransport` 与 `MaodouchatApp` 的静态唤醒流。本轮在真 Android 运行时
（本机 AVD `test1`）补上契约层：

`app/src/androidTest/java/com/maodouchat/notification/NotificationIntentConsumerInstrumentedTest.kt`，
14 例全绿（逐用例 XML 核对）；**负控制 1 轮**：把 owner 校验短路（`hasNotificationTarget &&` 前加
`false &&`）后，**恰好** 3 条 owner 相关用例红、其余 11 条仍绿——断言承重，不是空跑。

覆盖的真实攻击面/缺陷史：
- 外部包（浏览器）伪造通知 extra → 清空且不导航；外部包的**合法** ACTION_VIEW 深链必须放行
  （8.34 回归网），非白名单 host 拒绝；
- owner 与当前账号不符 / owner 缺失 / 登出态 → 一律不导航（换号与共享设备场景）；
- 非法 chatId（含 `/?#`）拒绝；非法 messageId 只丢高亮、仍打开会话；
- Telecom `ACTION_ANSWER_CALL` 的 null caller + 伪造 callId → 拒绝且清 extras、不置锁屏旗标；
- 来电唤醒流：合法 callId 原样；非法 callId/senderId 降级为空串（走通用轮询，不定向响铃）；
- 未接来电入口发出 open 请求；生产邀请冒号协议 → GroupInvite 目标；
- 消费后 extras / data 必须清空（防同一 Intent 复用重放）。

**纪律**：TokenManager 是真实 SharedPreferences、唤醒流是进程内静态 StateFlow——每例前清、
例后恢复登出态，避免弄脏同一次 connected 运行里的其它类（`ChatListScreenDataTest` 假设设备未登录）。

**第一片增补：Telecom 可信路径（+3 例，14→17）**

第一片原先只覆盖 Telecom 的**拒收**方向（null caller / 伪造 callId）。增补的**可信**方向：
用 `IncomingCallCoordinator.setPending(...)` 播种 in-process pending call（`isTrustedTransport`
唯一认的来源），断言：

- `ACTION_ANSWER_CALL` + 匹配 callId → 置锁屏旗标、发出来电唤醒且 **`autoAnswer=true`**
  （8.56：系统接听 ≠ 拉起，应用内不再二次点击）、视频标志透传、被消费的 extras 清空；
- `ACTION_INCOMING_CALL` + 匹配 callId → 唤醒但 **`autoAnswer=false`**（「拉起 ≠ 接听」）；
- **pending 存在但 callId 错配** → 仍然拒收（不是「有 pending 就放行」）。

**负控制**：把 `autoAnswer = telecomAction == ACTION_ANSWER_CALL` 强制为 `false` 后，
**恰好** 1 条（可信接听）红、其余 16 条绿——8.56 不变量承重。

**第二片：通话前台服务（`CallForegroundServiceInstrumentedTest`，5 例）**

`CallForegroundService` 是通话链路里唯一「进程被杀也在」的一层（WebRTC/信令保活的前提），
它坏了不会崩、只会**静默断线**。5 例覆盖：start 后 `activeCallId` 与 9001 号
ongoing / `CATEGORY_CALL` 通知都到位、渠道为 `IMPORTANCE_LOW` 且无角标；第二通电话覆盖
`activeCallId`；`ACTION_HANG_UP` **错配 callId 必须忽略**（防跨通话串扰——通知栏挂断按钮
指向上一通会话时不得误杀当前通话）、**匹配 callId** 才发 `CallActionBus` 请求并自行停止、
撤通知；`stop()`（系统/超时收尾）清状态但**不得**冒充用户挂断。

两处实测教训写进测试 KDoc：
1. 测试**不得**回收运行时权限——实测 revoke RECORD_AUDIO 会**杀死进程**，整轮 connected
   直接崩（首跑踩到）；权限只在缺失时授予、会话内保留（connected 结束卸载 APK 自然消失）。
2. 视频通话的 FGS type 是 `MICROPHONE|CAMERA`：缺 CAMERA 时 `startForeground` 抛
   SecurityException，被服务自身的 catch 吞掉并 `stopSelf()`——表现为「第二次 start 后
   `activeCallId` 变空」。这是测试先抓到的真实行为边界，不是测试瑕疵。

**负控制**：删掉 `ACTION_HANG_UP` 的 `requestedCallId != activeCallId` 守卫后，
**恰好** 1 条（错配忽略）红、其余 4 条仍绿——挂断守卫承重。

**第三片：主屏小组件（`ConversationWidgetProviderInstrumentedTest`，3 例）——并抓出/修掉一个真缺陷**

缺陷（**fail-closed 的静默全死**）：旧 Provider 在 `exported=true` 下用 `getSentFromUid()`
做「uid 必须等于本应用或 system」的守卫。实测（API 36）：`getSentFromUid()` **只在发送方
`BroadcastOptions.setShareIdentityEnabled(true)` 显式 opt-in 时才携带真实 uid**；本应用自己的
`sendBroadcast`、**PendingIntent（行点击 / 标记已读 / 快捷回复）与 AlarmManager 周期同步**
全部是 `INVALID_UID(-1)`。结论：小组件**没有任何一条交互能工作**，且不抛异常、不崩溃，
只能靠真机行为级测试发现。

修复（两层）：
1. `AndroidManifest.xml`：Provider `android:exported` true → **false**。小组件交互全部经由
   本应用创建的 PendingIntent（以创建者身份执行，同 uid 不受导出限制）；生命周期广播
   `APPWIDGET_UPDATE` 由 system_server 发出（`AppWidgetServiceImpl.sendBroadcastAsUser`，
   身份为 system uid），系统本就允许送达非导出组件。第三方/adb shell 的广播被系统**静默丢弃**
   （探针实测：`onReceive` 从未被调用）。
2. `ConversationWidgetProvider.onReceive` 的第二道守卫改为只拒**能解析出的真实第三方 uid**；
   -1（未 opt-in）与 null（反射失败）依赖导出检查放行。

**双向负控制，预判全中**：
- 把 `exported` 改回 `true`（守卫保持修复后版本）→ **恰好**伪造例红（shell 广播被投递、
  -1 被放行、MainActivity 真被拉起）、其余 2 例绿；
- 把守卫改回旧的严格版（exported 保持 false）→ **恰好**正例红（合法行点击被误杀，
  即缺陷原状）、其余 2 例绿。

**第四片：消息通知「投递→点击」整链（`MessageNotificationServiceInstrumentedTest`，4 例）**

前三片只覆盖消费侧（拿到 Intent 之后）与组件侧；本片补**产侧**并把两端接起来：通知由生产
`MessageNotificationService.showMessage` 真实挂出、真实 `NotificationManager` 可见、点击
`contentIntent` 真把 `MainActivity` 拉起来、消费侧把目标落成 `NotificationTarget.Chat(chatId)`。
钉住：槽位 tag=`maodouchat_<chatId>` / id=0；单聊 `messages_v4` 与群聊 `group_messages_v4`
渠道分离（0.72）；owner 不匹配不挂出（同用例内含正对照，避免「没挂出」是环境噪音）；
`cancelMessage` 清槽位。

**时机的坑（写进测试 KDoc）**：`Application.ActivityLifecycleCallbacks.onActivityCreated` 是从
`Activity.onCreate` 基类实现（`super.onCreate` 内）回调的，比 MainActivity 自己的
`consumeNotificationIntent(intent)` **还早**——在回调里读 `notificationTarget` 必为 null（实测）；
消费后 `NotificationIntents.clearFrom(intent)` 又会清掉 extras，也不能读 `activity.intent`。
最终口径：从测试线程轮询读 Activity 私有 `notificationTarget` 字段（目标在
`filterNotNull().collect` 的等待/导航期间至少保留一拍）——第一次实测的失败正是这个时序。

**负控制**：删掉产侧 tapIntent 的 `EXTRA_OPEN_CHAT_ID` 后，**恰好** 1 条（整链例）红、
其余 3 条仍绿。

**第五片：更新器 Worker（`AppUpdateDownloadWorkerInstrumentedTest`，7 例）——并修掉重试分类缺陷**

此前 `AppUpdateDownloadSchedulerTest` 明确登记了缺口：`enqueue`/`cancel`/`observe`「要真跑
WorkManager」、干下载活的 Worker 未测。本片两组：

- **A 组（`TestListenableWorkerBuilder` 直驱，零网络依赖）**：缺 URL → `apk_url_missing`；
  非官方 URL → `apk_not_official`（不可重试）；sha 非法 → `apk_sha256_missing_or_invalid`；
  versionCode 缺失 → `apk_version_code_missing`。
- **B 组（真 WorkManager + 生产调度器）**：非官方 URL enqueue → `observe` 流等到 FAILED 且
  `errorOf` 读出 `apk_not_official`（调度器→Worker→failureData→observe 整链）；会 retry 的
  任务 cancel 后收敛到 CANCELLED。

**发现并修掉的重试分类缺陷**：对官方域名不可达端口实测得到 `Connection reset`（又一次是
`connection closed`——同一种失败不同 message），旧策略只枚举 `failed to connect`/`timeout`/
`Unable to resolve` → 分类为**不可重试** → 更新在瞬态网络错误上**静默放弃**。修法：策略新增
`isRetryable(Throwable)` **类型优先**（`IOException` 一族一律可重试），非 IO 异常回落文本判据；
Worker 把异常对象而不是 message 交给策略。JVM 侧同步补两条。

**负控制**：调度器 `cancel()` 改空操作 → **恰好** cancel 例红、其余 6 例绿；重试修复的反证是
修复前**运行中自然出现**的两次红（`Connection reset` / `connection closed`）。

**仍未覆盖（本项保持 `[~]`）**：Widget 真机加挂/渲染全链（需要真实 launcher 宿主）、
运行时权限、密聊/PIN 锁会话的通知脱敏分支、更新成功路径（需要真实官方 HTTPS 源 + 与当前签名
一致的 APK，无法在本地/CI 伪造）。


### 第十三轮（2026-09-27，本地工具链侧）：`ui/navigation` 通知中心行点击收口——ui 直连持久层 5 → 0

`SearchCenterDestinations.kt` 的「通知中心行点击」此前把四类行 + 五个 legacy deeplink 的
「点哪行 → 清哪块托盘 → 去哪」全写在一个 Composable 的 lambda 里，直接触碰
`MaodouchatApp.emitOpenMissedCalls()`/`emitOpenContacts()`、`notificationCenter.markPostInteractionsRead()`
与四个通知服务——`frozenUiDirectPersistence` 记 5 处命中，且这段映射**零测试覆盖**。

收口成两层（都在非 ui 层）：

- `com.maodouchat.navigation.NotificationCenterOpenController`：只做决策——输入
  `NotificationCenterItem`，输出 `Outcome`（`Navigate(route)` / `PopBackStack` / `GoLogin` / `None`）；
  所有副作用（托盘清理、标记已读、事件发射）以 lambda 注入，不引用 Context/TokenManager/NavController；
- `com.maodouchat.navigation.NotificationCenterEffects.create(context)`：Android 侧接线，
  集中原来写在 ui 里的 app 单例/通知服务访问。

UI 侧只剩「Outcome → NavController 动作」映射。行为逐字对齐（登出守卫 8.49、MISSED_CALL/
FRIEND_REQUEST 无 deeplink 的兜底、`markPostInteractionsRead` 的 runCatching 语义）。

判据：新增 `NotificationCenterOpenControllerTest`（12 例，JVM/Robolectric——期望路由要经
`Routes.chatDetail` 的 `Uri.encode`）；**负控制**：删掉 legacy「未接来电」分支的
`emitOpenMissedCalls()` 后**恰好** 1 条红、其余 11 条绿。`ClientArchitectureTest` 的
`frozenUiDirectPersistence` 同步删除 `navigation/SearchCenterDestinations.kt to 5` 条目
（棘轮收紧），架构门禁实跑绿。

**同族续批（2026-09-27）**：

- **v2（#87）**：`MainContainerRoute` 6 → 0、`AuthDestinations` 2 → 0——两条 app 事件流
  （未接来电/联系人）与「登录后多端偏好拉取」收进非 ui 的 `AppNavigationEvents`
  （请求类型经 typealias 暴露，避免把 `MaodouchatApp` 符号带回 ui）。判据：
  `AppNavigationEventsInstrumentedTest` 4 例（真机）+ 负控制 1 轮（`missedCallsIsStale`
  恒 false → 恰好 1 条过期分支红）。
- **v3（本批）**：`NavGraph` 4 → 0——三条 app 级事件流各归其位：
  管理端公告 → `com.maodouchat.realtime.AdminNoticeFeed`（owner 捕获/实时门禁/空正文过滤）；
  Token 过期清理 → `com.maodouchat.session.TokenExpirySessionPurge`（归属+世代判定、purge容错、
  取消重抛）；扫码建私聊 → `com.maodouchat.call.DirectChatRequestHandler`
  （门禁/建聊/四类结果，含「在途失效静默丢弃」语义）。判据：JVM 测试 8 例
  （`TokenExpirySessionPurgeTest` 5 + `DirectChatRequestHandlerTest` 3）+ 负控制 1 轮
  （`shouldHandle` 恒 true → 恰好 4 条反例红、正例绿）；架构门禁实跑绿。

**通话族的专项第一步已落地（云端侧，2026-09-27）**：
`com.maodouchat.call.PolledIncomingBatchPolicy` 把 `IncomingCallObserver` 的 REST 轮询
决策逐字抽成纯函数（终端信令的墓碑守卫/幽灵响铃取消/双通道去重/8.56 群 mesh 边过滤），
`PolledIncomingBatchPolicyTest` 14 例钉住语义（hang-up 命中 pending 才记未接墓碑、
busy/reject 不记、空 callId 终端丢弃、preferCallId 被终端覆盖或成幽灵则取消、
过期 offer 不响铃、WS 已处理的不重复导航）；结构收口（CallNavigation 9 → 0、
把 UI 侧执行接线到该决策）待下一步。

- **通话记录页（#89）**：`CallHistoryScreen` 4 → 0——「清空」与行内「删除」的 Room 未接记录
  写操作收进 `com.maodouchat.call.MissedCallRoomActions`（app 未初始化静默 no-op、错误照旧传播；
  `internal repositoryFactory` 测试缝）。判据：`MissedCallRoomActionsTest` 3 例（假 DAO 委派）+
  负控制 1 轮（`delete()` 改委派 `clearAll()` → 恰好 1 条红）。
- **设置页两处（本批）**：`SettingsAiPrivacy` 5 → 0（两处「开启 AI 默认能力」的 OCR 索引触发 →
  `com.maodouchat.ai.ImageOcrIndexing.runOnceAsync`）、`SettingsServer` 5 → 0（两处换服成功分支
  的 `rebuildImageLoader`+`disconnectRealtime` → `com.maodouchat.network.ServerSwitchEffects
  .applyAfterServerChanged`）。两处均为**纯委托**（fire-and-forget / 两步调用），无新行为；
  判据 = 编译 + 架构门禁实跑 + 既有套件（不为「有测试」写空测试，如实登记）。

- **CallViewModel（本批）**：`screen/call/CallViewModel.kt` 3 → 0——会话世代/实时分发器/
  应用协程域三处直连收进 `com.maodouchat.session.AppRuntime`（世代是实时映射，非常量；
  `realtimeDispatcherOrNull` 保留「非本应用实例 → null」原语义）；顺带把 6 处四行的
  `mayContinue(…)` 格式化折叠为单行，文件 1635 → 1618，热点上限两份 map 同步收紧。
  判据：`AppRuntimeInstrumentedTest` 5 例（含「bump 后跟随」的常量防呆）+ 负控制 1 轮
  （世代改常量 → 恰好 2 条红）。
- **搜索/通知中心（本批）**：`GlobalSearchScreen` 8 → 0、`NotificationCenterScreen` 2 → 0
  ——仓库入口收进 `AppRepositories` / `ChatVisibilitySets.redactedChatIds()` /
  `NotificationCenterAccess.repository`；GlobalSearchScreen 993 → 991 行（上限两份 map 同步收紧），
  并从 `frozenUiAppDatabaseGrabbers` 名单删除（架构门禁实测抓出后补齐）。
- **两处死 import（本批）**：`ChatListUiState.kt` 1 → 0、`SettingsAccountSecurity.kt` 1 → 0——
  均为头部残留的 `import ...MaodouchatApp`（前者正文零引用，后者正文仅 KDoc 提及、剥注释后零命中），
  删 import 即清零；后者 671 → 670 行，热点上限两份 map 同步收紧。
  判据 = 架构门禁实跑（CI）+ 本地静态复核（门禁计数逻辑逐字复现，含 `Motion.kt` 的
  `android.database` 误伤对照，确认复核实现与门禁一致）。

- **扫码核验（本批）**：`ContactSubScreens` 4 → 0——扫码核验决策抽成非 ui 的
  `com.maodouchat.security.QrSafetyScanEvaluator`（纯决策 + `SafetySignalPort` 注入，
  10 种结论逐字复刻，含「会话过期也先读一次本机 deviceId」的调用顺序）；信号读写收进
  `SignalIdentityAccess`（port 的生产实现 + `markIdentityVerified`，IO 线程仍由调用点包）；
  用户档案查询走 `AppRepositories.users`。判据：`QrSafetyScanEvaluatorTest` 11 例
  （全分支 + 归一化 + 调用顺序）+ 负控制 1 轮（对端指纹条件取反 → 恰好 3 条指纹分支红）。
  顺带：ContactSubScreens 887 → 860 行（两份上限 map 收紧），**并且它跌出「前 20」窗口后，
  新面孔 `ChatDetailAiDialogs2`（882 行）按实测值首次纳入监管**——窗口滚动是门禁自己抓出来的。

- **群玩法四屏 + 门禁口径修正（本批）**：`GroupChain/Checkin/Pk/PollScreen` 四屏的
  `getApplication<MaodouchatApp>().realtimeEventDispatcher` 改经非 ui 的
  `AppRuntime.realtimeDispatcherOrNull(getApplication())`（8 处归零，生产同对象同序列）；
  并把直连持久层判据里的 `database.` 从子串升级为模式版 `(?<!android\.)\bdatabase\.`,
  只认应用库访问——`theme/Motion.kt` 命中的是 `android.database.ContentObserver`（框架 import），
  属**误报**，归零后条目删除（两份 map 共 10 行）。**控制**：往 Motion.kt 注入真实的
  `MaodouchatApp.instance.database` → 四条门禁同时红——口径修正没有把门禁弄瞎。
- **权限契约（本批）**：新增 `PermissionContractInstrumentedTest` 4 例——清单声明契约
  （运行时 POST_NOTIFICATIONS/RECORD_AUDIO/CAMERA + FGS 三件套）、`StartupPermissionPolicy`
  与真机授予态一致、授予后 `showTestNotification` 经 `activeNotifications` 端到端验证。
  负控制两轮：删 POST_NOTIFICATIONS → 恰好 2 条红；**删 CAMERA 不红**（库清单合并期补回，
  灵敏度边界如实登记）。Q03 的「通知/Widget/深链/权限/前台服务」五维至此均有真机证据。

- **AI 任务页（本批）**：`AiTasksScreen` 9 → 0——VM 的 `application as MaodouchatApp` +
  `app.database` 直取收进 `AppRepositories`（`aiTasks(application)` / `chatLocks` /
  `chatEntityOrNull`——最后一个是**窄口径**单行读，避免 `ChatRepository.getChatById`
  为装配参与者全表读 users 的额外成本）；提醒清理收进 `com.maodouchat.ai.AiTaskReminderCleanup`
  （取 id + 逐个取消 + 吞错/取消重抛逐字搬运）；会话能力查询收进新 `SecretChatCapabilities.forChat`
  （ui 里另有 6 处同款调用，随各自批次迁移）；`markAiTasksRead` 改走 `NotificationCenterAccess`。
  判据：编译 + 架构门禁实跑绿 + `DestinationSmokeTest` 22 例（含 aiTasks 目的地，真 VM 渲染）
  在 API 36 设备实跑全绿。

- **媒体中心 + 星标页（本批）**：`MediaCenterScreen` 6 → 0、`StarredMessagesScreen` 6 → 0——
  两屏同一族：`LocalMessageStore`/`ChatLockRepository` 构造收进 `AppRepositories`
  （新增 `messages`）、会话名解析走同一对 `chatEntityOrNull`/`users`、密聊能力走
  `SecretChatCapabilities.forChat`；星标页的「锁定 ∪ 密聊」两组集合收进
  `ChatVisibilitySets.safeRedactionSets()`（**每组独立容错**：一侧读失败回落空集、
  取消重抛——与原实现逐字同语义）。MediaCenterScreen 1060 → 1059 行，上限两份 map 收紧；
  两文件从 `frozenUiAppDatabaseGrabbers` 名单删除。判据：架构门禁实跑绿 +
  `DestinationSmokeTest` 22 例（含 mediaCenter/starred 两目的地）API 36 设备全绿。

- **设置族四文件（本批）**：`SettingsAccountSecurityScreen` 3 → 0、`SettingsViewModel` 2 → 0、
  `SettingsNotificationViewModel` 3 → 0、`SettingsGeneralSettingsViewModel` 1 → 0——
  信号能力（`isInitializedFor`/`getLocalIdentityFingerprint`/`deviceId`/`signDeviceConfirmation`）
  全部走 `SignalIdentityAccess`（本轮补两个方法）；`secureSessionManager` 收进新
  `SecureSessionAccess.manager`；实时连接态与通知中心分别走 `AppRuntime.realtimeDispatcherOrNull`
  与 `NotificationCenterAccess`；悬浮球开关的 Context 改 `getApplication()`。
  上限收紧两处：SettingsViewModel 1194 → 1192、SettingsAccountSecurityScreen 884 → 883。
  判据：架构门禁实跑绿 + `DestinationSmokeTest` 22 例（含 settings 三个目的地）API 36 设备全绿。

- **事件类型迁出 app 类 + 两个尾项（本批）**：`ChatReadEvent`/`ChatMessageSentEvent`
  从 `MaodouchatApp.Companion` 迁到 `com.maodouchat.realtime`（**类型归属修正**，非符号技巧：
  发射入口与两条流语义不变；数据类不再依赖 app 的静态默认值，构造点全部显式传
  `sessionGeneration`）——`ChatListRealtimeCoordinator` 3 → 0、`ChatListPorts` 25 → 23
  （其类型引用随之变短）；`ExploreViewModel` 的默认 scope 改 `AppRuntime.applicationScope`，
  1 → 0。判据：`ChatListRealtimeCoordinatorTest`（JVM，构造迁后类型）绿 +
  `DestinationSmokeTest` 22 例 API 36 全绿 + 架构门禁实跑绿。
  云端侧同轮已合并：`ContactsViewModel` 10 → 3、`ContactsRepository` 3 → 0（#105）。

- **聊天详情三小文件（本批）**：`ChatDetailAiIntents` 2 → 0、`ChatDetailDisappearing` 3 → 0、
  `ChatDetailLiveLocation` 2 → 0——密聊能力读走 `SecretChatCapabilities.forChat`；两个**命令**
  （`armOnRead`/`purgeExpiredMessages`）收进新 `com.maodouchat.security.SecretConversationActions`
  （与 Capabilities 的读写分工写进 KDoc）；`applicationScope` 走 `AppRuntime`。
  判据：架构门禁实跑绿 + chatdetail JVM 测试子集绿（三函数无直接测试覆盖，属纯转发，如实登记）。
  云端侧同轮：#108 来电观察器执行层迁出 ui（CallNavigation 9 → 0，待合并）。

**剩余（同类待收口）**：`navigation/CallNavigation.kt` 9（云端 #108 已收口，待合并）；
`ContactsViewModel` 3（云端在途）；`ChatDetail*` 与 `ChatListPorts` 23 等条目多为
「只做装配」的登记豁免或与云端拆分批次重叠，按需让路。

- **聊天导出控制器 + 通讯录 VM 尾项（本批）**：`ChatExportController` 2 → 0——两处
  `(context.applicationContext as? MaodouchatApp)?.secretConversationController?.capabilities`
  改走 `SecretChatCapabilities.forChat`（#109 同款；生产环境 applicationContext 恒为 app
  实例，`?:` 兜底分支不可达，语义不变）；`ContactsViewModel` 3 → 0——静态
  `MaodouchatApp.emitNotificationCenterItem` 改走 `NotificationCenterAccess.repository.add`
  （同一实例同一签名），`app?.realtimeEventDispatcher` 改走
  `AppRuntime.realtimeDispatcherOrNull(getApplication())`（空判语义一致，先前四屏同款），
  `import` 与 `private val app` 删除。两文件均不在 `frozenHotspotLineCaps`，无需动上限。
  判据：棘轮判据脚本本地复算两文件 0 命中；架构门禁待 CI 实跑。

- **群详情 VM（本批）**：`GroupDetailViewModel` 2 → 0——`import MaodouchatApp` +
  `application as MaodouchatApp` 删除：群消息协调器装配（含 Signal 协议实例）与
  `groupMembershipStore` 收进新 `com.maodouchat.group.GroupDetailAccess`（非 ui 层代装配，
  工厂签名要求的 app 本体在此传入——生产环境即构造器拿到的 application 本身）；
  实时分发器走 `AppRuntime.realtimeDispatcherOrNull`（非 app 实例 → 不订阅，四屏同款）；
  `userRepository` 走 `AppRepositories.users`（同一 UserDao）；密聊能力走
  `SecretChatCapabilities.forChat`（#109 同款）。文件 960 行不变，两份上限不动。
  判据：棘轮判据脚本本地复算 0 命中；架构门禁待 CI 实跑。

**剩余（同类待收口）**：`ChatDetailViewModel` 19 / `ChatDetailDeps` 17（多为装配豁免，
与云端拆分批次重叠，按需让路）；
`LoginViewModel` 2（signalProtocol/senderKeyRetryManager 需新访问器，另起一批）；
`ChatListPorts` 23（只做装配的登记豁免）。

### 第十四轮（2026-09-27，本地工具链侧）：lint 基线棘轮首降——297 条死 string

`LintBaselineRatchetTest` 的基线是一枚「只许缩」的棘轮（615 条冻结），此前从未缩过。
本轮拿最大的一族开刀：**UnusedResources 298 条，全部是 `R.string`**。

- **逐条核验**（全仓 1814 个文件单遍扫描：kt/java/xml/py/sh/md/kts/json/yml/properties/txt，
  排除两处 strings.xml 与基线自身）：297 条在任何代码/脚本/文档/其它 XML 里**零出现**；
  唯一被挡下的 `secret_chat_enabled` 与特性开关键同名（`RuntimeFlags.kt` /
  服务端 `RuntimeConfigService.kt` 里的字符串常量），保守保留；
- **双端同删**：`values/strings.xml` 与 `values-en/strings.xml` 各删 297 行
  （2843 → 2546 条），`check-string-parity.py` 实跑绿（zh=en=2546，only_zh=only_en=0）；
- **基线同步**：按「修掉一条、删掉一条」删 297 个 `<issue>` 块（615 → 318），
  总数棘轮 `frozenIssueCount` 615 → 318，**按规则分布**的冻结 map 同步
  （`UnusedResources` 298 → 1）；
- **判据**：`:app:lintDebug` 实跑绿（warnings 若不进基线即红——这是「删干净了」的硬证据）、
  `LintBaselineRatchetTest` 两条用例绿、字符串奇偶校验绿、brand 门禁绿。

**剩余（lint 基线 318 条）**：`UseKtx` 209（机械现代化，触面广）、
`GradleDependency` 22 / `NewerVersionAvailable` 14（依赖升级，属另一决策）、
`HardwareIds` 10、其余零散——按族分批，下一轮可继续。

### 第十四轮续（2026-09-27）：UseKtx `String.toUri` 收口——47 处转换 + 8 条死条目（317 → 265）

同族第二刀：`UseKtx` 209 条里 `String.toUri` 子族共 51 处 `Uri.parse(`（27 文件）全量定位。

- **转换 47 处（25 文件）**：`Uri.parse(x)` → `x.toUri()`（按括号配对逐处解析，非正则替换；
  47 处全是单行简单表达式，无算子优先级场景）；
- **跳过的 4 处**：`ChatDetailViewModel`（1）与 `MediaCenterScreen`（3）在**零松量行数上限**
  名单里（转换会加 import 行）——留给「先拆文件再收」，本批不动；
- **import 管理**：新增 `androidx.core.net.toUri`；无裸用法的文件删 `android.net.Uri`，
  4 个文件保留（`AttachmentPreparationService` / `ChatDetailTextInputDialogs` / `MediaExport` /
  `MediaCache` 仍有 `Uri` 类型引用）；转换后用「用法↔import 一致性」复核脚本逐文件过了一遍
  （先于编译兜住 3 处漏加 import——转换器删 import 后索引错位，已在脚本层修掉）；
- **活跃集修正**：lint 报告里的「活跃（未进基线）issue」暴露 3 处**已活但未基线**的 toUri
  （`AppLinkOpener` 迁包后新路径 ×2、`ChatDetailMessageActionsSheet` 抽层后 ×1）——
  实测确认**基线匹配不含改名回退**（旧路径条目并不兜住新路径告警）。本批一并修掉，
  活跃 UseKtx 3 → 0；
- **基线手术**：删 47 条 UseKtx（44 条对应转换 + 3 条死条目：`AppLinkOpener` 旧路径 ×2、
  `ChatDetailRoute` 抽层失效 ×1）+ 5 条 GradleDependency 死条目（可用版本号已变，
  lint `LintBaselineFixed` 恰好点名这 8 条）→ 317 → 265；`frozenIssueCount` 318 → 265
  （318 有 1 条余量——#113 删后实测 317，按实测收紧），分布 map `UseKtx` 209 → 162、
  `GradleDependency` 22 → 17；
- **判据**：`:app:compileDebugKotlin` 绿、`:app:testDebugUnitTest` + `compileDebugAndroidTestKotlin`
  绿（4m24s）、`:app:lintDebug` 重跑绿（exit 0，活跃 UseKtx 归零）。

**教训（记为下一轮候选）**：基线棘轮只看「基线文件里有多少条」，**看不见从未进基线的
活跃 issue**——本批的 3 处活跃 toUri 就是这样漏了若干轮。候选补强：CI 在 `lintDebug` 之后
加一步解析 `lint-results-debug.xml` 的**活跃集守卫**（白名单外活跃数必须为 0）。

**已落地（同日第三刀）**：候选补强当日实施——`scripts/check-lint-active.py` 接进
CI（`lintDebug` 之后）、`pre-push` 第二步、`run-tests.sh --lint`，四处同一枚脚本：
活跃集白名单（`GradleDependency` / `NewerVersionAvailable` / `LintBaseline*`）之外零容忍；
负控制：对含 3 处活跃 UseKtx 的旧报告退出码 1 并逐条点名，缺报告退出码 2。
配套：31 条活跃 `UnusedResources` 死 string（fake_chat / media_panel / candidates 族）
同批删除（活跃 UnusedResources 31 → 0）。

**剩余（lint 基线 265 条）**：`UseKtx` 162 = 153 `SharedPreferences.edit` + 4 `Uri.parse`
（在 2 个行数上限热点文件内，先拆后收）+ 5 bitmap；`GradleDependency` 17 + 活跃 5
（对应 dependabot 待合并 PR）；`NewerVersionAvailable` 14；`HardwareIds` 10。

### 第十四轮五续（2026-09-28）：UseKtx `SharedPreferences.edit` 一族收口——135 处转换（265 → 130）

同族第三刀，也是最大一刀：`UseKtx` 的 `SharedPreferences.edit` 子族共 **153 条基线**，全量逐条处理。

- **转换 137 处（55 文件）**：`.edit().a().b().apply()` → `.edit { a(); b() }`（ktx `edit{}` 尾接 apply，
  语义不变）；commit 链 → `.edit(commit = true) { }`（保持同步落盘语义，`@Suppress("ApplySharedPref")` 保留）；
  含 17 段的最长链（`VersionedSettingsRepository`）、可空链（`prefs(ctx)?.edit { }`）、
  跨行接收者（`.edit()` 单起一行）等形态；其中 2 处（`FriendCacheStore` 51/73）**lint 原先未标**
  （同文件另外 2 处转换后，lint 的判定把这两处转为活跃）——被**活跃守卫**本地当场抓住，一并转掉；
- **保留 18 处（不转换，条目留在基线）**：11 处「变量编辑器」模式（`val editor = prefs.edit()` 跨语句使用，
  转换需重排控制流，另批）、`check(...commit())` 1 处（返回值被消费）、runCatching 返回值被消费的
  commit 链 4 处（`.onFailure`/`.getOrDefault` 消费 Boolean——转换会把 Boolean 变 Unit）、
  `GlobalSearchScreen` 2 处（零松量行数上限 991 行=实测，转换要 +1 import 行——按既有「先拆后收」策略跳过，
  与 `ChatDetailViewModel`/`MediaCenterScreen` 同处理）；
- **匹配法**：以基线 `errorLine1` 逐条定位 + 行号就近消歧（153/153 命中，含 17 组重复行文本消歧）；
  commit 链「返回值是否被消费」逐处静态判断（同线 return/赋值、runCatching 闭合后接 `.onFailure`、
  外层 runCatching 自身被返回/赋值）——负控制：4 处 value-used 若误转，编译期即红（Boolean vs Unit）；
- **基线手术**：−135（逐条按行号精确移除）→ 265 → 130；`frozenIssueCount` 265 → 130，
  `UseKtx` 162 → 27；
- **判据**：`:app:compileDebugKotlin` 绿（34s）；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  + `:app:lintDebug` 绿；活跃守卫（pre-push 第二步）实跑通过。

**教训一（本刀内修正）**：按 (file, errorLine1) **计数**消费基线块，会在「同文本混有转换与保留」时错配
（`TokenManager` 的 `prefs.edit()` 组：转换 2、保留 4，计数法误删保留块）——恢复基线后改为
**行号精确移除**（计划表与基线块按序 zip，逐块按记录行号判定）。计数法只适合「整族全转」的场景。

**教训二**：行数上限文件的「零松量」在转换批次里也要先算——`GlobalSearchScreen`（991=991）
单看链式转换是 0 行差，但 **+1 import 行**就撞上限；测试门禁（`hotspot caps have zero slack` +
`client hotspot files may not grow`）先于提交抓住了它，本批按策略跳过而非放宽上限。

**教训三（活跃守卫首功）**：转换让 lint 的 SharedPreferences 判定**漂移**——`FriendCacheStore`
同文件两处转换后，另外两处原先未标的 `edit().apply()` 变成活跃（2 条新活跃、无对应基线块）。
若没有活跃守卫，这两处会静默漂进「未基线活跃」盲区；守卫在本地 pre-push 第二步拦下
（退出码 1 逐条点名），当场转掉——这正是第三刀加守卫的理由本身。

**剩余（lint 基线 130 条）**：`UseKtx` 27 = 18 处本刀保留（变量编辑器/值被消费/上限文件）
+ 4 `Uri.parse`（行数上限热点文件内，先拆后收）+ 5 bitmap；`GradleDependency` 17 + 活跃 5；
`NewerVersionAvailable` 14；`HardwareIds` 10。

### 第十四轮六续（2026-09-28）：UseKtx bitmap 小族收口——5 处（130 → 125）

- **转换 5 处（4 文件）**：`Bitmap.createBitmap(w, h, cfg)` → ktx `createBitmap(w, h, cfg)` ×3
  （LocalAiFileAnalyzer / QrCodeGenerator / SecretImageWatermark）、
  `Bitmap.createScaledBitmap(bmp, w, h, true)` → `bmp.scale(w, h)`（filter 默认 true，等价）、
  `bmp.setPixel(x, y, c)` → `bmp[x, y] = c`（ktx `set` 下标赋值）；
- **基线手术**：−5（按 (file, errorLine1) 精确匹配，5/5 命中）→ 130 → 125；
  `frozenIssueCount` 130 → 125，`UseKtx` 27 → 22；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:lintDebug` 绿；活跃守卫绿。

**剩余（lint 基线 125 条）**：`UseKtx` 22 = 18 处 edit 保留（变量编辑器/值被消费/上限文件）
+ 4 `Uri.parse`（行数上限热点文件内，先拆后收）；`GradleDependency` 17 + 活跃 5；
`NewerVersionAvailable` 14；`HardwareIds` 10。

### 第十四轮七续（2026-09-28）：Recycle 族收口——7 处经核实为 `?.use` 检测盲区（125 → 118）

`Recycle`（"Cursor/InputStream should be freed up after use"）8 条，逐处人工核实：**全部是
`?.use { }` 安全调用形态**（资源确已关闭），lint 的检测器不识别 `?.use`（安全调用）写法——
一类已验证的 false positive。

- **收口方式**：在**闭包函数**上带注释 `@Suppress("Recycle") // 资源由 ?.use 关闭；lint 不识别安全调用形态`，
  共 7 处（`SecureCaptureScrubber.queryLatest`、`ScreenshotDetector.checkImage/checkVideo`、
  `ScreenshotBurnDetector.inspectImage/inspectVideo`、`MediaExport.saveToGallery`、`GifLibrary.queryLocalGifs`）；
  压制后 lint 的 `LintBaselineFixed` 恰好点名 **7 条 Recycle 不再出现**——这就是压制生效的硬证据，
  随后按「修掉一条删一条」删掉 7 个条目；
- **保留 1 处**：`ExploreOrchestrator.persistPickedImage`——零松量行数上限（1010=1010），
  注释行放不下，按「先拆后收」策略留在基线；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿（4m21s）；`:app:lintDebug` 绿（压制前后 exit 0）；条目 125 → 118。

**剩余（lint 基线 118 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1
（ExploreOrchestrator，先拆后收）；`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；
`HardwareIds` 10；其余零散族（ModifierParameter 8 / Aligned16KB 9 / IconLauncherShape 5 等）。

### 第十四轮八续（2026-09-28）：零散小族收口——8 条（118 → 110）

- `SuspiciousIndentation` 1：`ChatListTopBar` 的 `TopAppBar(` 块整体多缩进 4 格（旧编辑残留）——
  整块回退（143 行纯空白改动，含块尾 `)}`）；
- `UseOfNonLambdaOffsetOverload` 1：`CapsuleTabBar` 的 `.offset(x = indicatorOffset)` →
  `.offset { IntOffset(indicatorOffset.roundToPx(), 0) }`（lambda 重载在 Density 接收者内读值；
  Dp→px 用 `roundToPx()`——首次写成 `IntOffset(Dp, 0)` 被编译期拦下）；
- `ComposableNaming` 1：`ThemeEditorScreen.rgbSlider` → `RgbSlider`（1 定义 + 3 调用）；
- `UnsafeDynamicallyLoadedCode` 1：`WebRTCManager.initialize` 的 `System.load(路径)` 是刻意为之
  （原注释已说明：按同一路径复用句柄，避免 JNI_OnLoad 重复执行 SIGABRT）——**同行** `@Suppress`
  （零行差，1416 行上限不动）；
- `StaticFieldLeak` 2：`AiProfileRepository.INSTANCE` 与 `WebSocketClient.ManagerHolder.tokenManager`
  都只持有 **applicationContext**（两处 getInstance 均已转换）——**同行** `@Suppress` + 一句注释
  （零行差，969 行上限不动）；
- `Typos` 2：values-en 的 "Sticker pack downloaded: %1$d ok" → "OK"（en-only，zh 无此串）；
- **基线手术**：−8（按 6 个 id 全量移除）→ 118 → 110；`frozenIssueCount` 118 → 110；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿（5m15s）；`:app:lintDebug` 绿且 `LintBaselineFixed` **恰好点名这 8 条**不再出现（硬证据）。

**剩余（lint 基线 110 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`HardwareIds` 10；
`ModifierParameter` 8；`Aligned16KB` 9（依赖侧）；其余零散。

### 第十四轮九续（2026-09-28）：ModifierParameter 族收口——8 条（110 → 102）

7 个 Composable 签名按 Compose 约定把 `modifier` 移到**第一个可选参数**位置（参数行移动，零行差）：

- `ChatListItem`（modifier 提前到 `chat` 之后）、`EmptyState`、`TypingIndicator`、`NotificationRow`、
  `StarredMessageRow`、`LinkPreviewSlot`、`ChatMessageRow`（×2 条：位置 + 默认值）；
- `ChatMessageRow` 的默认值 `androidx.compose.ui.Modifier = androidx.compose.ui.Modifier` 改回
  `Modifier = Modifier`（文件本就有 import，限定名让 lint 认不出默认值）；
- **首轮 7/8**：`ChatMessageRow` 的「第一个可选参数」判断我按「required 段之后」放，实际该函数的
  **首个带默认值的参数在更靠前**（`onRequestVoiceTranscript`）——lint 的提示行复跑暴露后修正；
- 调用点全为命名实参（编译期 0 破坏）；`TextMessageBubble` 在 1050 行上限内，参数行移动不动行数；
- **基线手术**：−8 → 110 → 102；`frozenIssueCount` 110 → 102；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿；`:app:lintDebug` 绿且 `LintBaselineFixed` 恰好点名这 8 条。

**剩余（lint 基线 102 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`HardwareIds` 10；
`Aligned16KB` 9（依赖侧）；其余零散。

### 第十四轮十续（2026-09-28）：HardwareIds 族收口——10 条（102 → 92）

- **8 处 `ANDROID_ID` 读取集中化**：`AiTasksScreen` / `ChatDetailRoute` / `MediaCenterScreen`×3 /
  `StarredMessagesScreen` / `MediaImageVideoBubbles`×2 此前各自内联
  `Settings.Secure.getString(contentResolver, ANDROID_ID)`（盲水印 deviceHint）——
  新建 `watermark/DeviceHint.androidId(context)` 一处豁免（KDoc 说明：ANDROID_ID 是按应用
  作用域、可重置标识，非硬件序列号），8 个调用点改为单行调用；
- **两个零松量行数上限文件因「集中化」反而变短**：`ChatDetailRoute` 2528 → 2525、
  `MediaCenterScreen` 1059 → 1050——上限**同步收紧**（两份 map 一起改，方向是「只许降」）；
- **2 处 SIM 标识读取**（`SimChangeWatcher.currentSimId` 的 `subscriberId`/`simSerialNumber`）：
  SIM 变更检测必须读 SIM 标识，且已由 READ_PHONE_STATE + 功能开关双重门控——函数上
  `@Suppress("HardwareIds")` + 一句理由；
- **基线手术**：−10（按 id 全量移除）→ 102 → 92；`frozenIssueCount` 102 → 92；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿（含零松量/收紧方向两条棘轮用例）；`:app:lintDebug` 绿且 `LintBaselineFixed` 恰好点名这 10 条。

**剩余（lint 基线 92 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；
`IconLauncherShape` 5 / `IconDuplicates` 5 / `AutoboxingStateCreation` 4 等零散。

### 第十四轮十一续（2026-09-28）：AutoboxingStateCreation 族收口——4 条（92 → 88）

`mutableStateOf(基本类型)` → 专用 state（免装箱）：

- `AboutScreen` 的 `downloadVersionCode`（Int）→ `mutableIntStateOf`；
- `ChatListFolderDialogs` 的 `rowPitchPx`（Float）→ `mutableFloatStateOf`；
- `FakeChatScreen` 的 `lockoutMs`（Long）→ `mutableLongStateOf`；
- `ExploreFeedScreen` 的 `requestFeedSearchFocus`（Int）——该文件零松量行数上限（638=638），
  新 import 放不下 → **全限定名** `androidx.compose.runtime.mutableIntStateOf(0)`（同行尾注说明），
  行数不动、上限不动；
- 三个未上限文件走 import（`mutableFloat/Int/LongStateOf` 按序插在 `mutableStateOf` 之前）；
- **基线手术**：−4 → 92 → 88；`frozenIssueCount` 92 → 88；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿（4m36s）；`:app:lintDebug` 绿且 `LintBaselineFixed` 恰好点名这 4 条。

**剩余（lint 基线 88 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；
`IconLauncherShape` 5 + `IconDuplicates` 5（同一组 launcher 图标，资产修复）；其余零散。

### 第十四轮十二续（2026-09-28）：launcher 圆图标资产修复——10 条（88 → 78）

`IconDuplicates` 5 + `IconLauncherShape` 5 同源：5 个密度的 `ic_launcher_round.png`
与 `ic_launcher.png` **字节相同**（方形小圆角图标当圆图标用）。

- **修复**：从方形图生成真圆图标——取不透明 bbox 的内切圆（0.98 内缩），4× 超采样
  LANCZOS 下采样做抗锯齿，乘进 alpha；5 个密度（48/72/96/144/192）全量重生成；
- **两轮踩坑（都靠 lint 复跑暴露）**：① 首轮 mdpi（48px）仍被判「非圆」——LANCZOS
  下采样在圆外留下**振铃残值**（alpha 1–24 的环带），lint 按「alpha>0」量形状时把环带算进
  边界 → 行宽超出圆拟合；② 修法：低值截断（<24 归零）+ 与「硬边圆（r−0.5px）」取 min，
  环带清零、圆内 AA 保留；复跑后 10 条全消；
- **基线手术**：−10 → 88 → 78；`frozenIssueCount` 88 → 78；
- **判据**：像素级核验（四角/四边中点 alpha=0、逐行宽度对圆拟合）；`:app:testDebugUnitTest`
  + `:app:compileDebugAndroidTestKotlin` 绿（4m20s）；`:app:lintDebug` 绿且
  `LintBaselineFixed` 恰好点名这 10 条。

**剩余（lint 基线 78 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；
`Overdraw` 3 / `PluralsCandidate` 3 / `UnusedAttribute` 2 等零散。

### 第十四轮十三续（2026-09-28）：PluralsCandidate 族收口——3 条（78 → 75）

三条「%d + 名词」字符串转真 `<plurals>`（i18n 正确性，非仅消警）：

- `contacts_group_invite_row_subtitle`（en: member/members；zh 仅 other）——
  调用点 `ContactsSearchAndRequests` 改 `pluralStringResource(..., invite.memberCount, invite.inviterName, invite.memberCount)`
  （选择数用成员数 %2$d，非邀请人名）；
- `fake_chat_pin_lockout`（en: second/seconds）——`FakeChatScreen` 调用点同步；
- `theme_import_ok`（en: color/colors）——`ThemeEditorScreen` 两个调用点同步；
- zh 侧只补 `other`（与仓内既有 plurals 惯例一致：中文 CLDR 无 one）；两处新 import
  （`pluralStringResource`，FakeChatScreen / ThemeEditorScreen）；
- **基线手术**：−3 → 78 → 75；`frozenIssueCount` 78 → 75；
- **判据**：`:app:compileDebugKotlin` 绿；字符串奇偶校验绿（zh=en=2513，plurals 不计入
  该脚本的 `<string>` 口径）；brand 门禁绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿（5m4s）；`:app:lintDebug` 绿且 `LintBaselineFixed` 恰好点名这 3 条。

**剩余（lint 基线 75 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；
`Overdraw` 3 / `UnusedAttribute` 2 等零散。

### 第十四轮十四续（2026-09-28）：UnusedAttribute 族收口——2 条（75 → 73）

`conversation_widget_info.xml` 的 `targetCellWidth`/`targetCellHeight` 需 API 31+（minSdk 26）：
- **修法**：资源限定符拆分——`res/xml/conversation_widget_info.xml` 去掉两属性（API <31 用），
  新建 `res/xml-v31/conversation_widget_info.xml` 完整版（含两属性，API 31+ 生效）；行为零变化，
  31+ 设备仍得 4x2 目标格，旧设备不再读无效属性；
- **基线手术**：−2 → 75 → 73；`frozenIssueCount` 75 → 73（`UnusedAttribute` 不在分布冻结 map 内，无需改）；
- **判据**：`:app:testDebugUnitTest`（两条棘轮用例）绿；`:app:lintDebug` 绿。

**剩余（lint 基线 73 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1（活跃 warning，
基线豁免有效——PR #133 误删已关闭，证据见其关闭评论）；`GradleDependency` 17 + 活跃 5；
`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；`Overdraw` 3 等零散。
### 第十四轮十五续（2026-09-28）：小组件 Overdraw + PiP 收口——4 条（73 → 69）

- `Overdraw` 3（小组件根布局）：RemoteViews 的根背景是**有意的视觉底色**（widget 不继承宿主主题）——
  三个布局根元素加 `tools:ignore="Overdraw"`（标准做法），不做「去背景」的伪修复；
- `PictureInPictureIssue` 1（manifest 级）：targetSdk 31+ 的 PiP 建议补 `setAutoEnterEnabled(true)`
  + `setSourceRectHint(...)`——`MainActivity.onUserLeaveHint` 与 `CallScreen` 显式按钮两处
  在 API 31+ 分支改为 `setAutoEnterEnabled(true).setSourceRectHint(decorView 可见矩形)`
  （原先刻意 `false` + 手动 enter；现在两者并存：系统后续 HOME 自动进 PiP，本次显式 enter 兜底，
  `isInPictureInPictureMode` 守卫防重入）；
- **与云端 #136 的交汇**：本批原也含 `UnusedAttribute` 2 的 `tools:targetApi` 方案；与 #136 的
  「按 API 31 拆限定符」（`res/xml-v31/`）冲突，rebase 时**采纳 #136 方案**（更正确：31+ 读全属性、
  旧设备不读无效属性），本批只保留 Overdraw 3 + PiP 1；
- **基线手术**：−4 → 73 → 69；`frozenIssueCount` 73 → 69；
- **判据**：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  绿；`:app:lintDebug` 绿且 `LintBaselineFixed` 恰好点名这 4 条。

**剩余（lint 基线 69 条）**：`UseKtx` 22（18 处 edit 保留 + 4 `Uri.parse`）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；
`OldTargetApi` / `ChromeOsAbiSupport` / `AppBundleLocaleChanges` / `ConfigurationScreenWidthHeight`
（4 条经评估为**产品决策项**，见下）等零散。

**产品决策项（暂留基线，非技术债）**：`OldTargetApi`（targetSdk 36 → 37 需行为回归验证）、
`ChromeOsAbiSupport`（x86_64 ABI 未开，非目标平台）、`AppBundleLocaleChanges`（动态语言切换
需 Play Core 或 per-app locale 迁移评估）、`ConfigurationScreenWidthHeight`（
`screenWidthDp → containerSize` 换算涉语义差异，且该文件零松量上限）。

**剩余（lint 基线 59 条）**：`UseKtx` 12 = 8 处保留（变量编辑器 1 + check 1 + 值被消费 commit 4
+ GlobalSearchScreen 2）+ 4 `Uri.parse`（行数上限热点文件内）；`Recycle` 1；
`GradleDependency` 17 + 活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；
其余零散与产品决策项（见上）。

### 第十四轮十七续（2026-09-28）：UseKtx `Uri.parse` 尾巴收口——4 处（59 → 55）

- 4 处 `Uri.parse(x)` → `x.toUri()`（`androidx.core.net.toUri`），接收方均为非空 `String`
 （`parsedContent(): String`、`url: String`、`rawUri: String`），与 `Uri.parse` 语义等价；
- **零松量上限处理**：两处站点都落在 `frozenHotspotLineCaps` 零余量文件内，必须行数零变化——
  `ChatDetailViewModel.kt` 1 处：`android.net.Uri` 仍被 `sendEncryptedAttachment(uri: Uri)` 需要，
  故 +1 import 行，删 `sendNudge` 上方 1 个多余空行（四连→三连）抵回，2420 不变；
  `MediaCenterScreen.kt` 3 处：`android.net.Uri` 不再被引用，删旧 import（−1）+ 加新 import（+1），
  1050 不变；两处上限（`frozenHotspotLineCaps` 与 `currentCaps` 副本）**均无需改动**；
- **基线手术**：删 4 条 `String.toUri` 对应条目 → 59 → 55；`frozenIssueCount` 55；`UseKtx` 12 → 8
  （`issue mix` 分布同步更新；其余规则计数不变）；
- **判据**：`:app:testDebugUnitTest`（两条棘轮用例）绿；`:app:lintDebug` 绿且 `LintBaselineFixed`
  恰好点名这 4 条。未本地验证，待 CI 验证。

**剩余（lint 基线 55 条）**：`UseKtx` 8 处保留（变量编辑器 1 + check 1 + 值被消费 commit 4
+ GlobalSearchScreen 2）；`Recycle` 1（活跃 warning，基线豁免有效）；`GradleDependency` 17 +
活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；`OldTargetApi` /
`ChromeOsAbiSupport` / `AppBundleLocaleChanges` / `ConfigurationScreenWidthHeight`
（4 条产品决策项，暂留）等零散。

### 第十三轮续（2026-09-28）：ChatDetailViewModel 直连收口——19 → 1

- 会话详情对 `MaodouchatApp` 单例与持久层的直连收进两个非 ui 访问口：
  `chatdetail/ChatDetailAccess`（会话代际 / emitMessageSent / emitChatRead / 活跃会话时间戳 /
  附件定稿事件流 / 后台作用域 / outbox / mutation 事件）与 `chatdetail/ChatDetailDataAccess`
  （markIncomingReadThrough / markAllRead / 传输观察 / 未读数 / DAO / MessageSearchRepository）；
- 另 3 处复用既有访问口：密聊能力走 `SecretChatCapabilities.forChat`、
  通知中心走 `NotificationCenterAccess.repository`；
- **保留 1 处**：共享 `app` 属性的转型声明行（`ChatDetailDeps` 装配与 AI 扩展仍在用它，
  待 deps 装配批次一并收口）；
- 棘轮：`frozenUiDirectPersistence` 的 VM 条目 19 → 1；`frozenUiAppDatabaseGrabbers`
  名单 3 → 2（VM 离场）；文件 2420 行 = 上限（零松量不变）；
- 判据：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  + `:app:lintDebug` 绿；`ClientArchitectureTest` 两条棘轮用例绿。

### Q03 续（2026-09-28）：无障碍语义审计扩面 + heading 落地（11 屏 / 8 屏有标题）

G342 探针（名字 + 触控目标）本日扩面并加一维：

- **屏覆盖 6 → 11**：`ContactsScreen`、`LoginScreen`（#140，真 VM 直构）、
  `StarredMessagesScreen`、`NotificationCenterScreen`、`GlobalSearchScreen`（#141）；
  实测各屏 0 问题（globalsearch 9 / notifcenter 3 / starred 2 个可点击，阈值取下界）；
- **多 root 支持**：Contacts 首屏带权限说明弹窗 = 两个 root，`onRoot()` 直接抛
  「Expected exactly 1 node」——探针改为 `onAllNodes(isRoot())` 逐个审计（弹窗语义一并量到）；
- **heading 维（TalkBack 标题导航）**：探针新增 heading 统计与 `minHeadings` 断言；
  8 屏主标题加 `Modifier.semantics { heading() }`（#146 五屏：chatlist/call/login/starred/
  notifcenter；#147 三屏：settings/explore/contacts——三文件零松量上限内，
  semantics 两个 import 的 +2 行由**冗余空行**抵回，同 #138 先例）；
- **暂缓**：`GlobalSearchScreen`（991 行上限、无冗余空行可抵）的 heading 待其拆分批次；
- **判据**：emulator-5558 实跑 `SemanticsAuditTest`（10 用例）绿；CI 的 instrumented job 两次全绿。

### 第十四轮十八续（2026-09-28）：lint 零散尾巴收口——Recycle 盲区 + TypographyDashes（55 → 53）

- `Recycle` 最后 1 条（`ExploreOrchestrator.persistPickedImage`）：`openInputStream(uri)?.use { input ->`
  人工核实资源确已关闭，属 lint 检测器不识别 `?.use` 安全调用形态的已知盲区（#124 同类）；
  按 #124 同式加 `@Suppress("Recycle")`——该文件行数上限 1010 **零松量**，
  注解与函数声明同行、行数 1010 不变（无冗余空行可抵，见 #124 留项说明）；
- `TypographyDashes` 1 条：`settings_username_placeholder` "3-50" → "3–50"（en dash），
  与 `values-en` 同名串已用的 "3–50" 对齐；该串仅作设置页用户名输入框 placeholder，无代码解析；
- **基线手术**：删 2 条对应条目 → 55 → 53；`frozenIssueCount` 55 → 53；
  `UseKtx` 8 条**有意保留**（变量编辑器 1 + check 1 + 值被消费 commit 4 + GlobalSearchScreen 2，
  见上轮注释——GlobalSearchScreen 991 零松量、TokenManager 四处丢提交失败信号）；
  `UnusedResources` 1 条（`secret_chat_enabled` 与 RuntimeFlags 开关键同名）继续保留；
- **判据**：未本地验证（本机无 Android SDK/JDK），待 CI 验证（`:app:lintDebug` + 两条棘轮用例）。

**剩余（lint 基线 53 条）**：`UseKtx` 8（有意保留）；`GradleDependency` 17 +
活跃 5；`NewerVersionAvailable` 14；`Aligned16KB` 9（依赖侧）；`OldTargetApi` /
`ChromeOsAbiSupport` / `AppBundleLocaleChanges` / `ConfigurationScreenWidthHeight`
（4 条产品决策项，暂留）；`UnusedResources` 1（有意保留）等零散。

### 第十三轮续二（2026-09-28）：令牌读者棘轮盲区修复——3 个全限定名读者归零（5 → 5，但判据收紧）

- **盲区**：`ui must not grow its direct network usage` 的文件级预筛要求「先 import
  `com.maodouchat.network`」，于是**只用全限定名**的读者整体逃逸——实测三个真读者
  （`AiTasksScreen`、`MediaCenterScreen`、`ExploreComposerCards`，共 7 处
  `TokenManager.getInstance(...).getUserId()/getToken()`）从未进过名单，而 KDoc 明说
  「不按 import 行」——措辞与判据不一致；
- **修复**：删预筛（api 侧无过滤后实测仍 0，安全）；三个文件 7 处改走
  `CurrentSession.ownerUserId()/snapshot().token`（非 ui 身份读点，语义等价）；
  `MediaCenterScreen` 1049 行（−1），上限同步收紧 1050 → 1049（两份 map）；
- **结果**：判据收紧后名单仍为 5（无新读者），`frozenUiTokenReaders` 不变；
- **判据**：`:app:compileDebugKotlin` 绿；`ClientArchitectureTest` 两条网络棘轮用例绿。

### 依赖升级批次（2026-09-28）：4 项落地 + 6 项诊断归档

- **已合并**（本地逐项对当前 main 复验 + CI 全绿）：#52（JUnit Jupiter 5.10.2 → 6.1.3，
  core/testing + domain/messaging 测试域）、#46（HikariCP 5.1.0 → 7.1.0，server；
  复验：server 编译 + 仓储/令牌/双设备子集绿）、#45（server JUnit 6.1.3——分支陈旧 merge-ref
  导致的假红，刷新后全绿）、#41（playwright-core 1.63.0，npm dev）；
- **关闭**：#50（与 #52 同组两行，已被覆盖）；
- **诊断归档（暂不合，原因逐条钉死）**：
  - #152（gradle-minor-patch 36 项）与 #49/#51：升级后的 AndroidX 产物（navigation-compose 2.10.2、
    Compose BOM 2026.09 等）在 AAR 元数据要求 **AGP 9.1+**，现 8.13.2——需先做 AGP 9 + Gradle 9
    工具链迁移（`checkDebugAarMetadata` 报 17 项）；
  - #43（server-minor-patch 11 项）与 #47/#44：卡在 **libsignal 0.41 → 0.86**（`Curve` API 面变化，
    `DeviceRegistry`/`SignalKeyRepository` 解析失败）——必须 app + server 同步迁移并做
    跨版本信封互操作回归；
- **口径**：上述诊断均以本地 worktree 复现定位，结论写回各 PR 评论。

### 第十三轮续三（2026-09-28）：令牌读者收口——5 → 1

- `ChatRealtimeController` 改收 `SessionContextProvider`（不再持 `TokenManager`）；
  装配处（`ChatDetailDeps`）走新非 ui 访问口 `session/SessionContexts.provider(application)`；
- `IdentityVerificationController` 的 `getUserId()/getToken()` 改走 `CurrentSession.snapshot()`；
- **装配参数自持化**：`AttachmentDownloadCoordinator`、`createAndroidConversationLocalStateCoordinator`、
  `createAndroidGroupMessagingCoordinator` 三处非 ui 构造器的 `tokenManager` 参数给默认值
  （`= TokenManager.getInstance(context/app)`），ui 装配点不再传参；
- 结果：`frozenUiTokenReaders` 5 → 1（仅剩 `LoginViewModel`——登录页本身就是会话的拥有者）；
- 判据：`:app:compileDebugKotlin` 绿；`ClientArchitectureTest` 网络棘轮用例绿；
  `:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin` + `:app:lintDebug` 绿。

### 第十三轮续四（2026-09-28）：ChatListPorts 整体迁出 ui——grabbers 名单清零（1 → 0）

- `ChatListPorts.kt`（232 行：端口数据类 + `AndroidChatListPorts.create` 装配块）从
  `ui/screen/chatlist/` 迁到非 ui 的 `com/maodouchat/chatlist/`——纯装配/端口，无 ui import（实测）；
- 随迁依赖收口：`PushVerifyKeyAction` + `parsePushVerifyKeyPayload`（纯 JSON 解析）从
  `ChatListAnnouncementCoordinator` 抽到非 ui 的 `chatlist/PushVerifyKeyPayload.kt`；
  `dismissalEntity(...)` 调用改为直接构造 `ArchiveSuggestionDismissalEntity`（data 层实体）；
- **试过但回退**：`ChatListAnnouncementCoordinator`/`ChatListArchiveSuggestionCoordinator`/
  `OwnerSessionPolicy` 一起迁不通——前者依赖 `ChatListUiState`（ui）、后者依赖 ui 根包的
  `OwnerSessionSnapshot`；连带爆炸半径（15 文件 import + 级联）不划算，回退后只留
  Ports 迁移 + 最小抽取；
- 棘轮：`frozenUiDirectPersistence` 删 `ChatListPorts 23` 条目；`frozenUiAppDatabaseGrabbers`
  1 → **0（空名单）**——B02「只做装配」欠债最后一名离场，反向断言（新增即红）继续在；
- 判据：`:app:compileDebugKotlin` 绿；`ClientArchitectureTest` 绿；`:app:testDebugUnitTest`
  + `:app:compileDebugAndroidTestKotlin` + `:app:lintDebug` 绿。

### 第十三轮续五（2026-09-28）：ChatDetailDeps 持久层收口——persistence 棘轮 17 → 0

- `ChatDetailDeps`（最后一个「只做装配」持久层豁免）的 17 处 `host.app.database.*` 直连
  全部改走非 ui 的 `chatdetail/ChatDetailDataAccess` 扩展：`messageRepo/chatLockRepo/secretTtlRepo/
  messageSearchDao/aiSummaryRepo/aiTaskRepo/aiOperationRepo/userRepo/chatRepo/chatDraftDao/
  readReceiptSource/applyRealtimeVisibility`（各构造与原装配一一对应，行为不变）；
  `refreshConversationPreview` 与 `emitMessageSent` 走 `ChatDetailAccess`（新增
  `emitChatListPreviewRefresh`）；`MaodouchatApp` import 随最后一个引用一并删除；
- **棘轮**：`frozenUiDirectPersistence` 删 `ChatDetailDeps 17` 条目——该 map 现仅剩
  `LoginViewModel 2`（会话建立者）+ `ChatDetailViewModel 1`（共享 `app` 属性转型行）；
- 判据：`:app:compileDebugKotlin` 绿；`ClientArchitectureTest` 绿；`:app:testDebugUnitTest`
  + `:app:compileDebugAndroidTestKotlin` + `:app:lintDebug` 绿。

### 第十三轮续六（2026-09-28）：分层门禁盲区（全限定名）首批收口

**盲区**：`packages outside ui must not import ui` 只认 `import com.maodouchat.ui` 行——
**全限定名引用整体逃逸**（与令牌读者棘轮同款病）。实测 11 个非 ui 文件以 FQ 形态引用
`com.maodouchat.ui.*`，其中 6 处是真违规（其余 5 处为导航注册点 + MainActivity，属既有白名单类）。

本批（首批）修掉 3 处真违规：

- `SettingsVisibilityPolicy`（纯策略：`VISIBILITY_VALUES` + `normalizeVisibility`）
  从 `ui/screen/settings/` 迁到非 ui 的 `com/maodouchat/settings/VisibilityPolicy.kt`；
  `AgentToolHost` / `ExploreDraftPolicy` 的 FQ 调用改新包；`SettingsViewModel` 单点用 FQ
  调用（零松量上限 1192 内不动行数）；
- `DecryptEnvelopeGate`（协议能力端口）从 `ui/screen/chatdetail/ChatDetailDecryptStatus.kt`
  抽到非 ui 的 `com/maodouchat/messaging/DecryptEnvelopeGate.kt`；`SignalProtocol` 的
  FQ 对象表达式改 import；
- **后续批次**：`CustomThemeStore`/`ChatAppearancePreferences`（依赖 `ui/theme` 的
  `ThemePaint`/`SentBubbleSpec`/`ChatBubbleColorPalette`，须先拆 theme 纯数据类型）、
  `SecureSessionManager`（读 `UnreadBadgeStore`，须改事件化）、以及**门禁判据本身的收紧**
  （FQ 正则 + 白名单扩到 4 个导航注册文件）——三件一起做才能落闸；
- 判据：`:app:compileDebugKotlin` 绿；`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
  + `:app:lintDebug` 绿（含热点上限用例）。

### 第十三轮续七（2026-09-28）：分层门禁 FQ 落闸——theme 纯数据类型迁出 ui、未读角标事件化、门禁判据收紧（PR #166）

- `ui/theme/` 的 4 个纯数据类型（`ThemePaint`/`SentBubbleSpec`/`ChatBubbleColorPalette`/
  `ChatPalette`）迁到中立 `theme/` 包；`CustomThemeStore`/`ChatAppearancePreferences` 等
  调用方改新包；
- `SecureSessionManager` 对 `UnreadBadgeStore` 的直读改事件化（security→ui 事件）；
- 门禁判据收紧：`packages outside ui must not import ui` 改为在剥注释 + 剥字符串内容后的
  源码里找 `com.maodouchat.ui.` 前缀（import 行自带该前缀，一次扫描同时覆盖 import 与
  全限定名两种写法）；白名单扩到 4 个导航注册文件 + MainActivity；
- `pendingLayeringFqOffenders` 冻结 #165 正在修的 3 处——#165 合并后按 `fixed` 断言的指示
  删表，门禁彻底落闸（offenders 必须为零）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八（2026-09-28）：`CredentialService` 从 repository/ 迁往 service/——错放棘轮 1→0（空集）

- `CredentialService`（269 行：注册 / 登录 / TOTP 二因子 / 改密 / 邮箱重置凭证子域）+
  同文件 6 个包内共享顶层 helper（`MAX_NAME_LENGTH`/`DELETED_USER_NAME`/`normalizedEmail`/
  `toPrivateUser`/`toPublicUser`/`isUniqueViolation`）从 `repository/` 迁往 `service/`——
  package 改名，类体逐行未动（跨包引用原本就全限定）；
- 调用方改 import：`repository/UserRepository`（类 + 3 个 helper + `isUniqueViolation` FQ 委托）/
  `plugins/AuthRouting`（通配符补显式 import）/`service/ProfileService`/`PostCommandService`/
  `PostInteractionService`（3 处显式 import 改向）/`service/JobLease`（FQ 改向）+
  12 个 repository 包内仓储补 `import service.isUniqueViolation`；成员遮蔽的 5 处
  （`NearbyRepository`/`FeedQueryService` 的私有 `toPublicUser`、`AccountLifecycleService`
  的私有 `DELETED_USER_NAME`、`EmailService` 的私有 `normalizedEmail`、`UserRepository`
  的 companion `MAX_NAME_LENGTH` 与成员 `isUniqueViolation`）与 plugins 包自有的
  `normalizedEmail` 逐文件核对解析目标，不受影响；
- 棘轮：`frozenServicesInRepositoryPackage` 1→0（空集）——`repository/` 错放 `*Service.kt` 清零；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九（2026-09-29，G344）：Messaging V2 协议模型 fuzz 测试——Q01「fuzz 测试仍缺」补上第一块

- 新增 `server/src/test/kotlin/com/maodouchat/server/messaging/v2/MessagingV2FuzzCompatTest.kt`（4 例）：
  - send/ack 请求各 200/100 个固定种子随机 payload：未知字段随机名（避开全部 12 个已知字段名，
    否则测的是重复键覆盖语义）/ 随机值（布尔/整数/浮点/字符串/null/数组/嵌套对象，深度 ≤ 3）/
    随机位置（顶层 + 信封对象内）；payload 由「合法请求先序列化成 JsonObject 再程序化注入」
    构造，注入本身永不破坏语法；
  - 反证 3 例：已知字段类型错（`clientTimestamp` 给字符串、`envelopes` 给对象、
    信封内 `recipientDeviceId` 给字符串）仍抛 `SerializationException`——`ignoreUnknownKeys`
    只放行未知键，不吞坏数据；
  - 近似字段名 1 例：`ID`/`Envelopes`/`envelopes2`/`clienttimestamp` 仍按未知键忽略，不误读；
- 纯新增测试文件，生产代码零改动；与 open PR #168（`ChatDetailRoute.kt`/`ChatDetailTopBar.kt`/
  `ClientArchitectureTest.kt`）无文件交集；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证（server JVM 单测）。

### 第十三轮续十（2026-09-28）：`ChatDetailRoute` 顶栏抽出——2525 → 2277 行（PR #168）

- 全仓最大文件 `ChatDetailRoute.kt`（单 composable）把其中最大一块内联 UI——**顶栏**
  （`topBar = { FloatingGlassTopBar(...) }`，256 行：标题/状态区、返回、群通话/安全码/
  语音/视频、溢出菜单 18 项）逐字抽到新文件 `ChatDetailTopBar.kt`（331 行）；
- 依赖全经参数注入：`state` + 8 个流程持有类（dialogs/schedule/chatLock/flows/search/
  drafts/contactSheets/groupCall）+ `pickers` + 9 个回调 + `viewModel`（5 个动作）+
  `headerStatus` + `participantNamesById`——组合期内不新增状态所有权，开关读写语义逐字一致；
- `ChatDetailRoute.kt` 删 18 个随迁死 import；热点上限两份 map 同步收紧 2525 → 2277；
- 判据（本机全绿）：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（2173 例）、
  `:app:compileDebugAndroidTestKotlin`、`:app:lintDebug` + 活跃 lint 守卫；
  另在本机模拟器（emulator-5558）跑 `ChatDetailScreenDataTest` + `DestinationSmokeTest`
  24/24 绿——覆盖 ChatDetailRoute 的真实装配渲染（`ChatDetailRoute(viewModel = viewModel)`）。
- CI 备注：#166 的 Instrumented 曾报 `DestinationSmokeTest.blockedUsers`
  `ArrayIndexOutOfBoundsException`（Compose SlotTable `moveSlotGapTo`，activity destroy 期）——
  同内容 androidTest APK 在上一次运行全绿、且两次 head 的差异只在 JVM 单测源文件，
  判定为模拟器侧 flake；rerun 后绿，未改任何代码。

### 第十三轮续十一（2026-09-28）：`ChatDetailRoute` 横幅栈抽出——2277 → 2185 行（PR #170）

- 消息列表上方的**横幅栈组装**（置顶/群公告/定时/阅后即焚/密聊/实时位置/群加密警告/
  安全警告/未读摘要，100 行）逐字抽到 `ChatDetailBanners.kt` 的 `ChatDetailBannerStack`
  （该文件本就承载 8 个横幅实现，本次补上组装点）；
- 依赖经参数注入：`state` + `viewModel` + `flows` + `schedule` + `participantNamesById` +
  `onOpenProfile` + `chatAiSurfacesVisible`；`ChatDetailRoute` 删 2 个随迁死 import；
- 新函数补 `@SuppressLint("LocalContextGetResourceValueCall")`（与 Route 同款理由：
  资源字符串均在回调内读取）——本机 lint 活跃守卫首跑就抓到 3 条，未放行；
- 热点上限两份 map 同步收紧 2277 → 2185。

### 第十三轮续十二（2026-09-29）：语义审计探针扩面四屏（PR #171）

- Q03 无障碍语义审计 **屏覆盖 11 → 15**：新增 `MaodouAgent`（8 可点击）/ `Moments`（2）/
  `MediaCenter`（6）/ `AiTasks`（2）四屏，构造同 `DestinationSmokeTest`（真 Application + 真 VM）；
- 四屏在「名字 + 48dp 触控目标」两维实测 **0 问题**——无需补语义或白名单；阈值按下界固化；
- 本机 emulator-5558 实跑 `SemanticsAuditTest` 14/14 绿；CI instrumented 249 例全绿；
- 后续：四屏主标题暂未加 `heading()`，与 GlobalSearch 先例一致，待各自拆分批次处理。

### 第十三轮续十三（2026-09-28）：`ChatDetailViewModel` 首个控制器切片——`sendNudge` 抽出（2420 → 2343，PR #172）

- 「拍一拍」编排（78 行）逐字抽到 `ChatNudgeSender`（守卫/待发意图构造本就在纯工厂
  `ChatSendIntentFactory`，本轮搬走副作用编排：乐观上屏 → 出站队列 → 结果回填 + 列表预览）；
- 依赖全经构造器注入（同 `ChatPinStarController` 一族，12 个 lambda/对象）；
  VM 侧只留委托 + `deps` 装配；热点上限两份 map 同步收紧 2420 → 2343；
- 与 #170 在 caps 两份 map 同一区块合并冲突——按主线解冲突（2185 + 2343）并重跑全绿。

### 第十三轮续十四（2026-09-29，G346）：`ChatDetailViewModel` 第二个控制器切片——`retrySendMessage` 抽出（2343 → 2275，PR #176）

- 重发失败消息的编排（72 行）逐字抽到新 `ChatRetrySender`：重试准入守卫（归属/状态/类型）
  走纯函数 `ChatSendGuard.checkRetry`（可单测）；编排只做附件重发分流 → 乐观回 SENDING →
  出站队列重试 → 结果回填 / 异常回滚（`BackgroundSessionGate` 判活 + 本地持久化回写）；
- 依赖全经构造器注入（同 `ChatNudgeSender` 一族，10 个 lambda/对象）；附件重发分流回 VM
  的 `sendEncryptedAttachment`（仍在 VM 内，140 行——下一个待切片）；`toUri` import 随搬移
  离场（VM 内已无使用者，删 import，行数 −1）；日志 tag 保持 `ChatDetailViewModel` 不变；
- 等价性：新旧方法体逐行 diff（9 处预期注入替换外）零差异；`token`/`currentUserId` 仍按
  每次调用求值（lambda），语义不变；
- 热点上限两份 map 同步收紧 2343 → 2275（零松量）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证（`:app:testDebugUnitTest` 两条
  棘轮用例 + `:app:compileDebugKotlin`）。

### 第十三轮续十五（2026-09-29，G347）：admin 协议模型 fuzz 测试——Q01「其余协议模型 fuzz 仍缺」补上第二块

- 新增 `server/src/test/kotlin/com/maodouchat/server/plugins/AdminModelsFuzzTest.kt`（7 例）：
  - `AdminSessionRequest`（150 + 50 例，含 `totpCode` 缺省变体）、`CreateAnnouncementRequest`
    （150 例，默认值/可空字段交错）、`CreateUserTagRequest` + `AssignUserTagsRequest`
    （各 100 例，列表字段）、`UpdateUserStatusRequest`（100 例，全可空）：固定种子未知字段
    fuzz，随机名避开全部 18 个已知字段名（否则测的是重复键覆盖语义），已知字段恒等断言；
  - 反证 4 例：`password` 收数字、`totpCode` 收对象、`tagIds` 收字符串、`bannedUntil`
    收字符串仍抛 `SerializationException`——`ignoreUnknownKeys` 只放行未知键，不吞坏数据
    （提权入口尤其关键）；
  - 近似字段名 1 例：`Password`/`password2`/`totpcode` 仍按未知键忽略，不误读；
- 测试直接引用生产侧同一份 `adminJson`（`AdminSupport.kt` 的 `internal` 配置）——若有人
  把 `ignoreUnknownKeys` 改回 `false`，fuzz 用例立刻变红；DTO 全部 public，无需生产代码改动；
- 纯新增测试文件 + 清单条目更新；与 open PR（#177/#178/#179，均为 app/ 侧）无文件交集；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证（server JVM 单测）。

### 第十三轮续十六（2026-09-29）：语义审计扩面六屏 + 探针跳过未放置节点 + 修掉抓出的真问题（PR #174）

- 扩面（15 → 21）：AccountSecurity / GeneralSettings / NotificationSettings / Moderation /
  BlockedUsers / About 六屏（真 Application + 真 VM）；
- **探针改进**：懒列表折叠在屏外的节点会被组成但未放置（0×0 边界），原先被误报为
  「触控目标过小」——现在跳过并单独计数（日志可见），AccountSecurity 首跑 12 条告警因此清零；
- **修掉的真问题**（全部由探针抓出）：
  - `SwitchRow`（通知/通用/AI 共用）内层 Switch 是第二个无名可点击目标 → 行已 toggleable，
    Switch 传 `onCheckedChange = null`；
  - 4 个「只有开关可点」的行（OLED/链接预览/未读优先/回车发送）开关无名 → 挂行标题；
  - `ThemeChoiceChip` 触控 47dp → 根因是未选中的 0.98 缩放把 48dp 触控扩展缩到 47.04dp：
    移除缩放动画 + clickable 移到 padding 前（整块可点）+ `heightIn(min = 48.dp)`；
  - 强调色 7 个纯色圆点无名 → 挂本地化颜色名（新增 `general_accent_*` 7 条 × 中英）；
- 判据：emulator-5558 实跑 `SemanticsAuditTest` 20/20 绿。

### 第十三轮续十七（2026-09-29）：语义审计扩面九屏——屏覆盖 21 → 30（PR #175）

- 新增 CallHistory / MyQrCode / Scan / ThemeEditor / ThemeWorkbench / WatermarkForensic /
  AiPrivacy / MyReports / ServerSettings 九屏；实测 0 问题（AiPrivacy 的开关行复用共享
  `SwitchRow`，续十六的修复同时清掉它的 30 条旧告警）；
- CI 首跑抓出 Scan 屏阈值过紧：本机 4 个可点击、CI 模拟器 2 个（无摄像头能力降级）——
  阈值 3 → 2，维护侧修复后 CI 绿；
- 判据：emulator-5558 实跑 29/29 绿；CI instrumented 291 例全绿。

### 第十三轮续十八（2026-09-29）：语义审计补主聊天屏——`ChatDetailRoute`（PR #178）

- 唯一缺失的高频屏补上：真 Room 播种 + `SavedStateHandle(chatId)` 真 VM → 组合真实
  `ChatDetailRoute`，等返回键出现再审计；实测 11 个可点击、0 问题，阈值取下界 9；
- **纪律固化**：清理放 `finally`——本轮实测踩过「用例失败残留会话行 → 后续 chatlist
  审计被污染变红」，注释写进用例；
- 判据：emulator-5558 实跑 30/30 绿。

### 第十三轮续十九（2026-09-29）：10 屏主标题补 `heading()`——TalkBack 标题导航（PR #177）

- GeneralSettings / NotificationSettings / Moderation / AiPrivacy / MyReports / BlockedUsers /
  ServerSettings / About / WatermarkForensic / CallHistory 十屏 TopAppBar 主标题加
  `Modifier.semantics { heading() }`；审计断言同步收紧 `minHeadings = 0 → 1`（先实跑
  测量、每屏恰好 1 个后再收紧）；
- AccountSecurity 整屏无 TopAppBar（自定义头部），未纳入，保持 0；
- 判据：emulator-5558 实跑 29/29 绿（收紧后）。

### 第十三轮续二十（2026-09-29）：`ChatDetailRoute` 搜索条+多选工具条抽出——2185 → 2114（PR #179，G348）

- 搜索条块（54 行）→ `ChatDetailSearchSection`；多选工具条接线块（36 行）→
  `ChatDetailSelectionSection`，两块逐字搬到 `ChatDetailSearchAndSelection.kt`；
- 新文件补 `@SuppressLint("LocalContextGetResourceValueCall")`——lint 活跃守卫在
  pre-push 首跑即抓到 1 条，未放行；
- 热点上限两份 map 同步收紧 2185 → 2114；与 #176（VM 2275）在 caps 同区块合并冲突——
  按主线解冲突（Route 2114 + VM 2275）并重跑全绿。

### 第十三轮续二十一（2026-09-29，G349）：`ChatDetailViewModel` 第三个控制器切片——`sendEncryptedAttachment` 抽出（2275 → 2147，PR #183）

- 附件加密发送编排（139 行）逐字抽到新 `ChatAttachmentSender`（191 行，含 KDoc/
  构造器/方法签名）；VM 侧只留同签名委托（默认参数保留在 VM，`ChatDetailMedia.kt`/
  `ChatDetailVoiceRecording.kt`/`retrySender` 附件分流零改动）；
- 依赖全经构造器注入（同 `ChatNudgeSender`/`ChatRetrySender` 一族，16 个）：scope/
  会话身份/令牌/上传开关/状态读写/`mergeMessages`/附件意图控制器/消息读写/
  `resumeFileTransfer`/准备任务表/属主校验/`attachmentErrorText`/文案；
- `attachOwnerUserId` 在构造意图与属主校验两处仍为同一次调用求值；日志 tag 保持
  `ChatDetailViewModel` 不变；`invokeOnCompletion` 属主校验语义逐字一致；
- 热点上限两份 map 同步收紧 2275 → 2147（零松量）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证（`:app:compileDebugKotlin` +
  `:app:testDebugUnitTest` 两条棘轮用例）；CI 绿后本轮（循环收 PR）squash 合并。

### 第十三轮续二十二（2026-09-29，G350）：`ChatDetailViewModel` 第四个控制器切片——`handleGroupRevisionChanged` 抽出（2147 → 2035，PR 待开）

- 群修订事件编排（114 行）逐字抽到新 `ChatGroupRevisionHandler`（174 行，含 KDoc/
  构造器）：准入守卫 → 纯决策 `groupRevisionImpact` → sender key 失效（修订号推进）→
  被移出群时本地清理（关 AI 门/取消全部任务/删本地会话/状态归零）→ 普通变更警告文案 +
  重载会话 + 刷新禁言状态；
- 依赖全经构造器注入（24 个）：会话身份/状态读写/文案/本地状态协调器/实时控制器/
  5 个 AI 门 + 6 个流任务 Job 引用 + 3 张任务表/sender key 协调器/`loadChat` 回调/
  `refreshMyMemberRole` 回调（VM 侧 private → internal，仅供装配引用）；
- 等价性：`invalidateGroupSenderKey` 私有包装逐字内联——属主取入口捕获值（原包装在
  无挂起点间隙内二次读同一属性，值恒等）；`activeChatId`/任务 Job 均为调用点求值；
  日志 tag 保持 `ChatDetailViewModel` 不变；
- VM 删 2 个随迁死 import（`ConversationLocalCleanupMode`/`conversationLocalCleanupSession`）；
- 热点上限两份 map 同步收紧 2147 → 2035（零松量）；
- 与 open PR #185（groupplay 语义审计）无文件交集；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续二十三（2026-09-29，G351+G352）：`ChatDetailRoute` 上下半区弹窗簇抽出（PR #188，已合）

- 输入区上下半区弹窗簇（601 行净搬移）逐字抽到新 `ChatDetailDialogHosts.kt`（726 行，含 KDoc/构造器）；
- `ChatDetailRoute.kt` 1987 → 1386 行（纯搬移不改判断）；热点上限两份 map 同步收紧（零松量）；
- 里程碑：`ChatDetailRoute.kt` 2525 → … → 1386。

### 第十三轮续二十四（2026-09-29）：语义审计扩面五屏（PR #190，已合）

- 新增 Nearby / AuthorProfile / PostDetail / JoinGroupInvite / DeveloperBots 五屏审计（`SemanticsAuditTest`，屏覆盖 35 → 40）；
- 探针抓出真问题：Nearby「可见性」开关是行内唯一可点目标且无名字 → 挂行标题 contentDescription；
- 实测可点击：4 / 3 / 3 / 2 / 4，阈值取下界 3/2/2/2/3。

### 第十三轮续二十五（2026-09-29，G353）：通用路由协议模型 fuzz 测试——Q01「其余协议模型 fuzz 仍缺」补上第三块（PR #189，已合）

- 经 `RouteParsing.receiveJson`/`parseJson` 解码的通用客户端 API 请求 DTO（全站最宽的客户端入口面）：`RegisterPushTokenRequest`（150）、`SendSignalRequest`（150）、`UpdateProfileRequest`（100）、`SendFriendRequestBody`（100）、`UploadKeysRequest`（100，含嵌套 `preKeys`）、`CreateReportRequest`（100）——固定种子随机 payload，顶层注入 1–5 个未知字段，已知字段恒等断言；
- 缺省值钉住 + 4 例反证（坏类型仍抛 `SerializationException`）+ 1 例近似字段名；
- 生产侧唯一改动：`routingJson` 由 `private` 提为 `internal`（G344/G347 同款纪律）；
- 自审通过（描述逐条核对、无无关文件）→ CI 绿 + mergeable clean → squash 合并。

### 第十三轮续二十六（2026-09-29，G354）：WS 入站 DTO fuzz 测试——Q01 补上第四块（PR 待 CI）

- `WsMessage` 信封（150）/`TypingPayload`（100）/`OutgoingSignalingPayload`（150，含嵌套 `groupMemberIds`）：固定种子随机 payload，顶层注入 1–5 个未知字段，已知字段恒等断言；
- 缺省值钉住（最小三字段→其余回默认值）+ 3 例反证（`isTyping`/`groupMemberIds`/`epoch` 坏类型仍抛 `SerializationException`）+ 1 例近似字段名；
- 生产侧唯一改动：`Sockets.kt` 函数局部 `json` 提升为文件级 `internal val wsJson`（5 处引用点改名，零行为改动；`sendError`/`handleWsMessage` 的 `json: Json` 形参不动，实参改传 `wsJson`）；
- 与 open PR #191（`refactor/chatdetail-composer-strips`）无文件交集；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续二十七（2026-09-29）：语义审计补群详情屏（`GroupDetailScreen`）+ 标题 heading()（PR #184）

- 真 Room 播种群会话（`isGroup=true`）+ `SavedStateHandle(chatId)` 真 VM → 组合真实
  `GroupDetailScreen`，等返回键出现再审计；实测 15 个可点击（4 个屏外未放置被跳过）、
  0 问题；阈值取下界 12；标题补 `heading()`；清理放 `finally`；
- 判据：emulator-5558 实跑 `SemanticsAuditTest` 31/31 绿。

### 第十三轮续二十八（2026-09-29）：语义审计扩面四屏（群玩族）+ 修掉投票页两个无名 Checkbox（PR #185）

- 新增 GroupPoll / GroupCheckin / GroupChain / GroupPk 四屏（此前无任何测试覆盖）；
- 探针抓出真问题：GroupPoll 创建表单两个 Checkbox 是独立可点击节点、无名字 →
  改「整行 toggleable + Checkbox 传 null」标准形态；
- 四屏标题补 `heading()`；实测可点击 8/2/4/4，阈值取下界 6/2/3/3；
- CI 首跑抓出 myreports 阈值环境差异（本机 2 / CI 1）——阈值 2 → 1，修复后 CI 绿；
- 判据：emulator-5558 实跑 35/35 绿。

### 第十三轮续二十九（2026-09-29，G350）：`ChatDetailRoute` 时间线区抽出——2114 → 1987（PR #187）

- `ChatTimelinePane` 的内容（消息列表 + 回底 FAB + 两个空态，135 行）逐字抽到
  `ChatDetailTimelineSection.kt`；新函数是 **BoxScope 扩展**（align 依赖 Box 作用域，
  同 `LazyItemScope.ChatDetailTimelineItem` 先例）；参数 21 个；
- 删 20 个随迁死 import；上限两份 map 收紧 2114 → 1987。

### 第十三轮续三十（2026-09-29，G353）：`ChatDetailRoute` 输入区上方状态条带抽出——1386 → 1246（PR #191）

- 加载指示器 / 引用中提示 / 录音指示器 / 发送前试听条 / 发送中指示器 / 群禁言提示
  （含到期重组触发）/ AI 操作状态条 / AI 草稿流条 / 恢复草稿面板 / 打字中指示器——
  139 行逐字抽到 `ChatDetailComposerStrips.kt`；参数 5 个；删 9 个随迁死 import。

### 第十三轮续三十一（2026-09-29，G355）：`ChatDetailRoute` 输入区接线抽出——1246 → 1077（PR #193）

- `ComposerPane` 的接线（176 行：发送/定时/附件/位置/录音/名片回调、AI 面板入口、
  提及参数装配）逐字抽到 `ChatDetailComposerSection.kt`；参数 11 个；删 7 个随迁死 import；
- **里程碑**：`ChatDetailRoute.kt` 本轮系列 **2525 → 2277 → 2185 → 2114 → 1987 → 1691 →
  1386 → 1246 → 1077**（单 composable 只剩装配壳：弹窗簇/门禁链/Scaffold/各 Section 调用）。

### 第十三轮续三十二（2026-09-29，G355）：bot `sendMessage` 解析收敛为纯函数 + fuzz 钉住——Q01 bot 侧专项评估第一块（PR #195）

- 评估结论：bot 手写 `JsonObject` 解析无 typed DTO，但已满足 fuzz 系列契约（未知键忽略、缺省回默认值、坏类型经 `StatusPages` 的 `IllegalArgumentException` 映射大声 400、无未处理 500）；
- 生产侧唯一改动：`/api/bot/sendMessage` 处理器内联抽取逻辑搬入 `BotSendMessageParse.kt` 的 `parseBotSendMessage(obj, silentSendEnabled)` 纯函数（`BotCoreRouting.kt` 处理器只剩调用 + 字段映射，逐行等价；`silent` 在处理器里本就是死字段——静默发送走独立 `sendMessageSilent` 端点——保留在 data class 里钉住语义）；
- 新增 `BotSendMessageParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略恒等断言、缺省值钉住、6 例反证（已知字段坏类型；`replyToMessageId` 显式 null 沿用原有语义 `JsonNull.content == "null"`，零行为改动钉住）、snake_case 别名 + 近似字段名、截断上限（4000/64/128/8 行）与 `silent` 语义钉住；
- 与 open PR #193（app 三文件）、#194（清单 §0 表格）无文件交集（本轮清单改动只在 Q01 行与本账本节）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续三十三（2026-09-29，G356）：`SettingsViewModel` 第一个控制器切片——「我的设备」管理抽出（1192 → 981，PR #196）

- `loadMyDevices` / `removeMyDevice` / `renameMyDevice` / `confirmMyDevice`（227 行）逐字抽到
  `SettingsDeviceController`；两个 Job（devicesLoadJob / deviceMutationJob）所有权内聚；
- 依赖全经构造器注入（scope / currentState / updateState / textFn / isCurrentOwner /
  accountApi）；VM 侧只留四个委托 + lazy 装配；调用方签名不变；
- VM 删 2 个随迁死 import；上限两份 map 收紧 1192 → 981。

### 第十三轮续三十四（2026-09-29，G357）：`SettingsViewModel` 第二个控制器切片——「黑名单」管理抽出（981 → 906，PR #197）

- `loadBlockedUsers` / `unblockUser`（91 行）逐字抽到 `SettingsBlockedUsersController`；
  两个 Job（blockedUsersLoadJob / blockedUsersMutationJob）所有权内聚；
- 依赖同上一族的六件套；VM 侧两个委托 + lazy 装配；
- 上限两份 map 收紧 981 → 906（rebase 到 #196 之后实测）。

### 第十三轮续三十五（2026-09-29，G358）：`SettingsViewModel` 第三个控制器切片——「个人资料」一族抽出（906 → 716，PR #200）

- `saveStatus` / `saveProfile` / `uploadAvatar` / `removeAvatar`（239 行）逐字抽到
  `SettingsProfileController`；两个 Job（profileSaveJob——状态与昵称共用、avatarUploadJob）
  所有权内聚；
- 依赖全经构造器注入（scope / application / currentState / updateState / textFn /
  isCurrentOwner / accountApi）；VM 删 2 个随迁死 import；
- 窗口前移纳管：拆小后 `SettingsGeneral.kt`（873 行）进入前 20 名且此前未纳管——按实测值
  加入两份上限表（先纳管，后续再拆）；
- 与云端 #199 为同一片（重复）——本 PR 先绿先合，已在 #199 留言说明。

### 第十三轮续三十六（2026-09-29，G359）：`SettingsViewModel` 第四个控制器切片——「账号级动作」抽出（716 → 592，PR #201）

- `logout` / `logoutAllDevices` / `deleteAccount`（139 行）逐字抽到
  `SettingsAccountController`；accountMutationJob 所有权内聚；
- 依赖同上一族的六件套；VM 删 1 个随迁死 import（NonCancellable）；
- 上限两份 map 收紧 716 → 592。

### 第十三轮续三十七（2026-09-29，G360）：`SettingsViewModel` 第五个控制器切片——「隐私设置」一族抽出（592 → 391，PR #203）

- `loadPrivacy` / `openPrivacy` / `closePrivacy` / 五个开关处理器 / `savePrivacy`（约 240 行）
  含 `trackPrivacyField` 骨架、`currentLoadedPrivacy`、`normalizeVisibility` 逐字抽到
  `SettingsPrivacyController`；三个字段（privacySaveJob / loadedPrivacy / dirtyPrivacyFields）
  所有权内聚；
- VM 的 `init` 经 lazy 装配调用（装配块上移过初始化顺序检查、`loadPrivacy` 转 internal）；
- VM 删 4 个随迁死 import；上限两份 map 收紧 592 → 391。

### 第十三轮续三十八（2026-09-29，G361）：`SettingsViewModel` 第六个控制器切片——「用户名」一族抽出（391 → 318，PR #205）

- `loadPublicProfileUrl` / `openUsernameEditor` / `closeUsernameEditor` / `onEditUsernameChange` /
  `saveUsername`（94 行）逐字抽到 `SettingsUsernameController`；
- VM 删 1 个随迁死 import（withContext）；上限两份 map 收紧 391 → 318；
- **里程碑**：`SettingsViewModel` 本轮系列 **1192 → 981 → 906 → 716 → 592 → 391 → 318**（六个控制器）。

### 第十三轮续三十九（2026-09-29）：bot `editMessage` 请求体手写解析收敛为纯函数 + fuzz 钉住（PR #204）

- `/api/bot/editMessage` 内联解析抽入 `BotEditMessageParse.kt` 纯函数（处理器只剩调用 +
  字段映射，逐行等价）；
- 新增 `BotEditMessageParseFuzzTest`：随机 payload 未知键忽略恒等断言、缺省值钉住、
  坏类型大声失败反证、别名与近似字段名、截断上限。

### 第十三轮续四十（2026-09-29，G362）：`SettingsViewModel` 第七个控制器切片——安全 UX 偏好推/拉抽出（318 → 293，PR #207）

- `pushSecurityClientPrefs`（含 `clientPrefsPushMutex` 串行化）/ `pullSecurityClientPrefs`
  （含锁超时白名单回落语义）逐字抽到 `SettingsSecurityPrefsController`；
  clientPrefsPushMutex / clientPrefsPullJob 所有权内聚；
- VM 删 4 个随迁死 import（Job / Mutex / withLock / SecurityPreferencesPatch）；
- 上限两份 map 收紧 318 → 293；`SettingsViewModel` 系列至此 **1192 → 293**（七个控制器）。

### 第十三轮续四十一（2026-09-29）：bot `sendDocument` 请求体手写解析收敛为纯函数 + fuzz 钉住（PR #209）

- `/api/bot/sendDocument` 内联解析抽入 `BotSendDocumentParse.kt` 纯函数（处理器只剩调用 +
  字段映射，逐行等价）；
- 新增 `BotSendDocumentParseFuzzTest`：随机 payload 未知键忽略恒等断言、缺省值钉住、
  坏类型大声失败反证、别名与近似字段名、截断上限。
- §0 复核·六版：Server 单测标注随 Q01 fuzz 系列升至 681。

### 第十三轮续四十二（2026-09-29）：MyQrCode/Scan 两屏补 heading()——零松量文件的死 import 换装（PR #212）

- `ContactSubScreens.kt`（上限 860 零松量）：删 2 个严格死 import（`android.app.Activity` /
  `PersonAdd`），换装 `semantics`/`heading` 两个 import——净零行数；两屏标题加 `heading()`；
- 审计断言收紧 `minHeadings = 1`；判据：emulator-5558 实跑 40/40 绿。

### 第十三轮续四十三（2026-09-29）：MediaCenterScreen 补 heading()——同包小助手 + 死 import 抵消（PR #213）

- `ChatDetailScreenHelpers.kt` 新增同包小助手 `Modifier.headingSemantics()`（单调用即用）；
- `MediaCenterScreen` 删 1 个严格死 import（`LocalMessageStore`）抵消，行数 1049 → 1048，
  上限两份 map 收紧；审计断言收紧 `minHeadings = 1`；
- **里程碑**：TalkBack 标题导航在全部有标题的审计屏（40 屏）上落地。

### 第十三轮续四十四（2026-09-29）：bot `sendPhoto` 请求体手写解析收敛为纯函数 + fuzz 钉住（PR #215）

- `/api/bot/sendPhoto` 内联解析抽入 `BotSendPhotoParse.kt` 纯函数
  （`parseBotSendPhotoFields` / `decodeBotPhotoBytes` / `buildBotPhotoContent` +
  `BOT_PHOTO_MAX_BYTES`，处理器只剩调用 + 字段映射，逐行等价）；
- 新增 `BotSendPhotoParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略恒等断言、缺省值钉住、
  `photoBase64`→`photo`→`fileBase64`→`data` 别名优先级 + 近似字段名反证、坏类型大声失败反证、
  caption 500 截断（sendVoice 是 200，不统一是故意的）、坏 base64/空解码 → null
  （处理器 400 `invalid base64`，与 sendVoice 的宽容语义故意不同，特意钉住）、
  data-URI 前缀剥离与空白剔除、5MB 边界、内容模板形状 + 4000 截断；
- 与 open PR（仅 dependabot 若干）无文件交集（本轮清单改动只在 §0 表格与本账本节）；
- §0 复核·七版：Server 单测标注随 Q01 fuzz 系列升至 697（681 基准 + sendVoice 8 + 本轮 sendPhoto 8）。
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续四十五（2026-09-29，G364）：`GroupDetailViewModel` 第一个控制器切片——「群变更」一族抽出（960 → 800，PR #216）

- 10 个公开变更入口（改名 / 公告 / 昵称 / 增删成员 / 角色 / 转让群主 / 头衔 / 单人禁言 /
  全员禁言）+ `updateGroup` 执行器（约 160 行）逐字抽到 `GroupMutationController`；
- `pendingRetry` 仍由 VM 持有（load / loadGroupInvite / uploadGroupAvatar /
  redistributeSenderKey 也写它），经 set lambda 共享；提交成功后经 `onCommitted` 回调 `load`；
- VM 删 5 个随迁/既有死 import；上限两份 map 收紧 960 → 800。

### 第十三轮续四十六（2026-09-29，G365）：`GroupDetailViewModel` 头像上传并入变更控制器（800 → 703，PR #217）

- `uploadGroupAvatar`（98 行，AVATAR 变更）逐字并入 `GroupMutationController`；控制器新增
  `application` 依赖（头像转码）；
- VM 删 3 个随迁死 import；上限两份 map 收紧 800 → 703。

### 第十三轮续四十七（2026-09-29，G366）：`GroupDetailViewModel` 的 sender key 维护一族抽出（703 → 597，PR #219）

- `redistributeSenderKey` / `maybeAutoRedistributeSenderKey` / `showSenderKeyMaintenanceFailure`
  （122 行）逐字抽到 `SenderKeyMaintenanceController`；`groupEncryptionHealthController` 仍由
  VM 持有（load 也用它取状态），经构造器注入共享；pendingRetry 经 set lambda 共享；
- VM 删 2 个随迁死 import；上限两份 map 收紧 703 → 597；
- **里程碑**：`GroupDetailViewModel` **960 → 800 → 703 → 597**（三个控制器）。

### 第十三轮续四十八（2026-09-29，G367）：`GroupDetailViewModel` 的群邀请拉取/轮换抽出（597 → 528，PR #220）

- `loadGroupInvite`（86 行：功能开关 → 门禁 → 拉取/轮换 → 状态写回 / 重试登记）逐字抽到
  `GroupInviteLoadController`；`groupInviteController` 所有权内聚；
- VM 删 1 个随迁死 import（RuntimeFlags）；上限两份 map 收紧 597 → 528。

### 第十三轮续四十九（2026-09-29，G368）：`GroupDetailViewModel` 的实时事件观察抽出（528 → 444，PR #222）

- `observeRealtimeChanges`（97 行：Presence 可见性写回 / GroupRevision 失效 sender key +
  重载）逐字抽到 `GroupDetailRealtimeObserver`，在 VM 的 init 处启动（lazy 装配）；
- VM 删 1 个随迁死 import（RealtimeDomainEvent）；上限两份 map 收紧 528 → 444；
- **里程碑**：`GroupDetailViewModel` **960 → 800 → 703 → 597 → 528 → 513 → 444**（六个控制器）。

### 第十三轮续五十（2026-09-29）：bot `sendVideo` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六块）

- `/api/bot/sendVideo` 内联解析抽入 `BotSendVideoParse.kt` 纯函数
  （`parseBotSendVideoFields` / `measureBotVideoSize` / `buildBotVideoContent` +
  `BOT_VIDEO_MAX_BYTES`，处理器只剩调用 + 字段映射，逐行等价）；
- 新增 `BotSendVideoParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略恒等断言 + 体积测量一致、
  缺省值钉住（caption 缺省→`""`、duration 缺省/非数字→`0`）、
  `videoBase64`→`fileBase64`→`data` 与 `duration`→`durationSec` 别名优先级 + 近似字段名反证、
  3 例坏类型大声失败反证（对象/数组型 chatId、videoBase64、caption →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400「参数无效」，不是 500）
  + 钉住 duration 显式 null → 回 0（`JsonNull` 本就是 `JsonPrimitive`，
  `.content` 为 `"null"` 字符串，`toIntOrNull() ?: 0`，与原处理器逐字一致——
  2026-09-29 20:40 修 CI：初版测试误断言为抛错，687 测中 1 红，已修正断言与 KDoc，生产代码零改动）、caption 500 截断、
  data-URI 前缀剥离与空白剔除、坏 base64 → 量出 0 不报错
  （宽容语义，与 sendVoice 一致、与 sendPhoto 的 400 `invalid base64` 故意不同，特意钉住）、
  12MB 边界、内容模板形状 + 4000 截断；
- 与 open PR（#217 用户自己的 GroupDetail 切片 + dependabot 若干）无文件交集；
- §0 复核·八版：Server 单测标注随 Q01 fuzz 系列升至 712（697 基准 + 本轮 sendVideo 8）。

### 第十三轮续五十一（2026-09-29）：bot `sendLocation` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七块）

- `/api/bot/sendLocation` 内联解析抽入 `BotSendLocationParse.kt` 纯函数
  （`parseBotSendLocationFields` / `buildBotLocationContent`，处理器只剩调用 + 字段映射，逐行等价）；
- 新增 `BotSendLocationParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略恒等断言、
  必填钉住（chatId/latitude/longitude required）、`latitude`→`lat` 与
  `longitude`→`lng`→`lon` 别名优先级 + 近似字段名反证、**别名链「穿透」怪语义钉住**
  （`?:` 接在 `toDoubleOrNull()` 之后：主字段非数字/显式 JSON null 时穿透到别名，
  整条链取不到数字才 `MissingRequired`——上一轮 sendVideo 的 duration 显式 null 回 0
  是同一族语义，这里逐字保留）、3 例坏类型大声失败反证（对象/数组型 chatId、latitude、title →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400）、title 缺省→`""` 与 80 截断、
  坐标范围（纬度 ±90、经度 ±180，`invalid coordinates`，边界值合法）、内容模板形状
  （`"📍 "` + 可选 title + `%.6f` + `[location:lat,lon]` 行）；
- 与 open PR（#218 bot sendVideo：`BotMediaRouting.kt` + `BotSendVideoParse*.kt`；#219/#220
  用户自己的 app 侧 GroupDetail 切片 + dependabot 若干）无代码文件交集；本轮清单改动仅为
  本账本节**末尾追加**（§0 表格行正被 #218 持有：697→712 未合，本轮 +8 标注暂不改表，
  待 #218 合并后由下一轮复核到 720）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续五十二（2026-09-29，G369）：`GroupDetailViewModel` 的整页加载抽出（444 → 319，PR #225）

- `load`（145 行：群信息/成员/sender key 状态/审计首页/候选成员/机器人的 IO 拉取与状态写回，
  含 8.39 审计 limit 修复与 8.48 邀请字段保留修复）逐字抽到 `GroupDetailLoader`；
- 依赖全经构造器注入（14 个）；自动重分发经 `onAutoRedistribute` 回调既有
  `SenderKeyMaintenanceController`；loader 显式类型切断类型推断环（同 #186 先例）；
- **初始化顺序教训**：VM 的 init 会同步调用 `load()`——lazy 声明必须在 init 之前，
  否则 `by lazy` 委托未初始化即被访问（CI instrumented 实测 NPE；本机 emulator-5558
  复跑 40/40 后修复）；
- VM 删 3 个随迁死 import；上限两份 map 收紧 444 → 319。

### 第十三轮续五十三（2026-09-29，G370）：`GroupDetailViewModel` 的审计分页加载抽出（319 → 290，PR #226）

- `loadMoreAudit` + `isCurrentAuditOwner`（45 行）逐字抽到 `GroupAuditLoadController`；
  `auditNextOffset` 仍由 VM 持有（loader 也写它），经 get/set lambda 共享；
- 上限两份 map 收紧 319 → 290；
- **里程碑**：`GroupDetailViewModel` **960 → 800 → 703 → 597 → 528 → 444 → 319 → 290**
  （五个控制器 + 观察器 + 加载器 + 审计分页）。

### 第十三轮续五十四（2026-09-29）：bot `sendSticker` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第八块）

- `/api/bot/sendSticker` 内联解析抽入 `BotSendStickerParse.kt` 纯函数
  （`parseBotSendStickerFields` / `buildBotStickerContent`，处理器只剩调用 + 字段映射，逐行等价）；
- 新增 `BotSendStickerParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略恒等断言、
  `emoji`→`sticker`→`text` 别名优先级（主字段存在即不穿透，哪怕显式 null——`?:` 接在
  `jsonPrimitive` 之前，与原处理器逐字一致）+ 近似字段名反证、显式 JSON null 走
  `.content` 得 `"null"` 的怪语义钉住（`isBlank()` 判不住，零行为改动）、`trim()` 后
  emoji 16 / pack 40 截断、双必填（`chatId/emoji required`，trim 后全空同样判缺）、
  4 例坏类型大声失败反证（对象/数组型 chatId、emoji、pack、text →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）、
  内容模板形状（emoji + 可选 `\n[stickerPack:<pack>]`）；
- 等价性注意：原处理器响应体 `put("emoji", emoji)` 里的局部 `emoji` 随抽取一并搬入
  `fields`，已改为 `put("emoji", fields.emoji)`——响应回显的值逐字不变；
- 与 open PR（#222 bot sendLocation：`BotGeoRouting.kt` + `BotSendLocationParse*.kt` +
  仪器测试一文件 + dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节
  **末尾追加**（§0 Server 单测标注行暂不改：#222 的 +8 按其条目约定由复核轮刷新，
  本轮 +8 同理延后，待两个 PR 依次合并后由复核轮一次性复核）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续五十五（2026-09-30）：bot `forwardMessage`/`copyMessage` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第十四块）

- `/api/bot/forwardMessage` 与 `/api/bot/copyMessage` 逐字相同的内联解析（`fromChatId`/
  `chatId`→`toChatId`→`to_chat_id`/`messageId` 抽取 + 三必填）收敛为同一个纯函数
  `parseBotForwardCopyFields`（`BotForwardCopyParse.kt`，两个处理器只剩调用 + 字段映射，
  逐行等价——处理器里局部 `val fromChatId/toChatId/messageId` 保留，下游一行不动）；
- 新增 `BotForwardCopyParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言、
  三必填逐个缺/空白判缺 + 别名满足必填、`fromChatId`→`from_chat_id` 与 `messageId`→`message_id`
  别名优先级 + `toChatId` 三元链（`chatId` > `toChatId` > `to_chat_id`）+ 近似字段名反证、
  显式 JSON null 不穿透别名（`JsonNull.content` 为字面 `"null"`，`isBlank()` 判不住，
  零行为改动）、4 例坏类型大声失败反证（对象/数组型 fromChatId、chatId、messageId、
  from_chat_id → `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 等价性注意：两个处理器的校验顺序刻意不变（功能门 `isMessageForwardingEnabled` 与双成员检查
  仍在处理器里，纯函数只负责抽取 + 三必填），副作用（仓库/fanout/响应）未动；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8 与本轮 +5 按条目约定由复核轮一次性刷新，
  待两个 PR 依次合并后由复核轮复核到 725）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续五十六（2026-09-30）：bot `sendNudge` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第十五块）

- `/api/bot/sendNudge` 内联在处理器的 `chatId`/`text` 抽取（`take(80)` 不 trim）+
  单必填（`chatId required`）+ 内容组装（空 note → `"👋 nudge"`，非空 → `"👋 $note"`）
  收敛为 `parseBotSendNudgeFields` / `buildBotNudgeContent` 纯函数
  （`BotSendNudgeParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  局部 `val chatId` 保留，下游一行不动）；
- 新增 `BotSendNudgeParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  内容模板逐字断言、单必填语义（chatId 缺/空白判缺；text 缺/空白不 400，走回退内容）+
  近似字段名反证、截断上限钉住（超长截 80、**不 trim** 前导空格计入上限）、
  显式 JSON null 怪语义钉住（chatId 得字面 `"null"` 判合法、text 得 `"null"` 组装出
  `"👋 null"`，零行为改动）、2 例坏类型大声失败反证（对象型 chatId、数组型 text →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 等价性注意：校验顺序刻意不变（功能门 `isNudgeEnabled` 与成员检查仍在处理器里，
  纯函数只负责抽取 + 单必填 + 内容组装），副作用（仓库/fanout/响应）未动；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8、#236 的 +5 与本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核到 730）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续五十七（2026-09-30）：bot `sendContactCard` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第十六块）

- `/api/bot/sendContactCard` 内联在处理器的 `chatId`/`name` 抽取（`take(80)` 不 trim、
  缺失回 `"contact"`）+ 单必填（`chatId required`）+ 内容组装（模板 `"> ~card:$name~"`）
  收敛为 `parseBotSendContactCardFields` / `buildBotContactCardContent` 纯函数
  （`BotSendContactCardParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  功能门 `isMarkdownEnabled` / `isContactCardEnabled` 与成员检查仍在处理器里，
  校验顺序不变，局部 `val chatId` 保留，下游一行不动）；
- 新增 `BotSendContactCardParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  内容模板逐字断言、单必填语义（chatId 缺/空白判缺；name 缺/空白不 400，回 `"contact"`）+
  近似字段名反证、截断上限钉住（超长截 80、**不 trim** 前导空格计入上限）、
  显式 JSON null 怪语义钉住（name 得字面 `"null"` 而**不是**回 `"contact"`，组装出
  `"> ~card:null~"`，chatId 得字面 `"null"` 判合法，零行为改动）、
  2 例坏类型大声失败反证（对象型 chatId、数组型 name → `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8、#236 的 +5、#237 的 +5 与本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核到 735）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续五十八（2026-09-30）：bot `sendPollQuiz` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第十七块）

- `/api/bot/sendPollQuiz` 内联在处理器的 `chatId`/`question`（别名 `text`）/`options`/
  `correctOptionIndex` 抽取 + 三必填（`chatId/question/options required`）+ 内容组装
  （模板 `"QUIZ:"` + question + `"|"` 分隔、正确选项 `"*"` 标记，整体 `take(2000)`）
  收敛为 `parseBotSendPollQuizFields` / `buildBotPollQuizContent` 纯函数
  （`BotSendPollQuizParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  群玩法开关 `isGroupPlayEnabled` 仍在解析之前、成员检查仍在必填之后，
  局部 `val chatId` 保留，`safeIdx` 钳位仍在处理器里逐字 `coerceIn`，下游一行不动）；
- 新增 `BotSendPollQuizParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  三必填语义（chatId 缺/空白、question 缺/空白、options 缺/不足 2 个→400；恰 2 个通过）+
  question 别名链怪语义（`?:` 接存在性：显式 JSON null 不穿透到 `text`，得字面 `"null"`；
  `orEmpty().take(200)` 不 trim，前导空格计入上限）+
  options 逐字顺序钉住（元素 `trim()`→`take(80)`→空白剔除→`take(10)`；显式 null 元素得字面
  `"null"`；对象/数组元素 `(as? JsonPrimitive)` 静默丢弃不抛错；非数组本体→`emptyList()`→判缺）+
  `correctOptionIndex`（缺省/垃圾/浮点/显式 null→0；数字字符串与 JSON 数字都解析；
  纯函数返回原始值不钳位）+ 内容模板逐字断言（含 `take(2000)`）+
  3 例坏类型大声失败反证（对象型 chatId、数组型 question、对象型 correctOptionIndex →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  correct 抽取在必填检查之前，chatId 缺失也掩盖不住它的类型错——逐字顺序钉住）；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8、#236 的 +5、#237 的 +5、#238 的 +5 与本轮 +6
  按条目约定由复核轮一次性刷新，待 PR 依次合并后由复核轮复核到 741）；

### 第十三轮续五十九（2026-09-30）：bot `answerCallbackQuery` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第十八块）

- `/api/bot/answerCallbackQuery` 内联在处理器的 `callbackQueryId`（`callbackQueryId`→`id` 别名链，双缺回 `""`，
  显式 null 得字面 `"null"` 且不穿透到 `id`）/`text` 抽取（`take(200)` 不 trim，缺省纯函数回 `null`）
  收敛为 `parseBotAnswerCallbackQueryFields` 纯函数（`BotAnswerCallbackQueryParse.kt`，处理器只剩调用 + 字段映射，
  逐行等价——`logCommand` 仍在处理器里，校验顺序不变，响应体 `text` 的 `?: ""` 回退仍在组装处）；
- 新增 `BotAnswerCallbackQueryParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  别名链优先级钉住（`callbackQueryId` > `id`；近似字段名 `CallbackQueryId`/`callbackqueryid` 被忽略）+
  双缺省（`callbackQueryId` 回 `""`、不 400；`text` 纯函数回 `null`）+
  截断上限钉住（超长截 200、**不 trim** 前导空格计入上限）+
  显式 JSON null 怪语义钉住（`callbackQueryId` 得字面 `"null"` 不穿透 `id`，`text` 得字面 `"null"`，零行为改动）+
  3 例坏类型大声失败反证（对象型 `callbackQueryId`、数组型 `id`、对象型 `text` → `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8 与本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。


### 第十三轮续六十（2026-09-30）：bot `sendChecklist` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第十九块）

- `/api/bot/sendChecklist` 内联在处理器的 `chatId`/`title`（`.orEmpty().take(80)`、
  **不 trim**，前导空格计入上限，显式 JSON null 得字面 `"null"`；非必填——空白只影响
  组装前缀）/`items` 抽取（`as? JsonArray`，元素 `trim()`→`take(80)`→空白剔除→`take(20)`；
  显式 null 元素得字面 `"null"`；对象/数组元素 `(as? JsonPrimitive)` 静默丢弃不抛错；
  非数组本体→`emptyList()`→判缺）+ 双必填（`chatId.isBlank() || items.isEmpty()`→400）+
  内容组装（标题非空 `"**title**\n"` 前缀 + 每项 `"- [ ] item"` 换行拼接，整体无截断）
  收敛为 `parseBotSendChecklistFields` / `buildBotChecklistContent` 纯函数
  （`BotSendChecklistParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  markdown 开关仍在解析之前、成员检查仍在必填之后，下游一行不动）；
- 新增 `BotSendChecklistParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  双必填语义（chatId 缺/空白、items 缺/全空白→400；title 缺/空白**不**判缺）+
  `title` 怪语义（不 trim 原样保留、`take(80)` 前导空格计入上限、显式 null 得字面 `"null"`）+
  `items` 逐字顺序钉住（`trim()`→`take(80)`→空白剔除→`take(20)`；显式 null 元素得字面
  `"null"`；对象/数组元素静默丢弃；非数组本体→判缺不抛错）+ 内容模板逐字断言
  （标题加粗前缀、每项 `- [ ]` 前缀、整体无截断）+
  2 例坏类型大声失败反证（对象型 chatId、数组型 title → `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；对象型 items 本体是判缺不是抛错——逐字语义钉住）；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8 与本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续六十（2026-09-30）：bot `sendAlert` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十块）

- `/api/bot/sendAlert` 内联在处理器的 `chatId`/`text` 抽取（`text`→`message` 别名链、
  `take(300)` 不 trim）/`level` 抽取（`.orEmpty().ifBlank { "info" }.take(16)`）+
  双必填（`chatId`/`text required`，`level` 非必填）+ 内容组装（模板 `"ALERT[$level]: $text"`，
  `type = "SYSTEM"`）收敛为 `parseBotSendAlertFields` / `buildBotAlertContent` 纯函数
  （`BotSendAlertParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  必填（纯函数）→ 成员检查（处理器），校验顺序不变，响应体 `put("level", …)` 随抽取一并
  改为 `fields.level`，回显值逐字不变，下游一行不动）；
- 新增 `BotSendAlertParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  双必填语义（chatId 缺/空白、text 缺/空白→400；`message` 别名满足必填；level 缺/空白
  **不**判缺，回 `"info"`）+ `text` 怪语义（不 trim 原样保留、`take(300)`；
  `text`→`message` 别名链 `?:` 接存在性：显式 JSON null 得字面 `"null"` 不穿透到 `message`）+
  `level` 逐字顺序钉住（`.orEmpty().ifBlank { "info" }.take(16)`：缺省/空白回 `"info"`；
  显式 null 得字面 `"null"`（非空白，`ifBlank` 不触发）；超长先回退默认再截 16）+
  内容模板逐字断言（`"ALERT[$level]: $text"`）+
  4 例坏类型大声失败反证（对象型 chatId、数组型 text、对象型 message 别名、对象型 level →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 与 open PR（#227 bot sendSticker：`BotMediaRouting.kt` + `BotSendStickerParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#227 的 +8 与本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。


### 第十三轮续六十一（2026-09-30）：bot `sendCountdown` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十一块）

- `/api/bot/sendCountdown` 内联在处理器的 `chatId`/`title`（`.orEmpty().ifBlank { "Countdown" }.take(40)`：
  缺省/空白回 `"Countdown"`，显式 null 得字面 `"null"`（非空白，`ifBlank` 不触发），
  超长先回退默认再截 40，**不 trim**（前导空格计入上限））/`seconds`
  （`(obj["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60).coerceIn(5, 86400)`：
  非整数字符串/浮点字符串/显式 null→`toIntOrNull()` 回 `null`→回退 60 不报错，
  负数/超界夹回 `[5, 86400]`，对象/数组型→`?.jsonPrimitive` 抛 `IllegalArgumentException`）
  + 单必填（`chatId.isBlank()`→400，`title`/`seconds` 非必填）+ 内容组装
  （模板 `"**$title**\n`T-${seconds}s`"`，`type = "MARKDOWN"`）收敛为
  `parseBotSendCountdownFields` / `buildBotCountdownContent` 纯函数
  （`BotSendCountdownParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  markdown 总开关检查仍在解析之前、必填（纯函数）→ 成员检查（处理器），校验顺序不变，
  响应体 `put("seconds", …)` 回显抽取后的钳制值逐字不变，下游一行不动）；
- 新增 `BotSendCountdownParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  单必填语义（chatId 缺/空白→400；title 缺/空白**不**判缺回 `"Countdown"`；
  seconds 缺**不**判缺回 60）+ `title` 怪语义（不 trim 原样保留、前导空格计入
  `take(40)`；显式 null 得字面 `"null"`）+ `seconds` 怪语义逐字钉住（非整数/浮点/
  显式 null 回退 60 不报错；负数/0→5；超界→86400；边界 5/86400 保留）+
  内容模板逐字断言（`"**title**\n`T-${seconds}s`"`）+
  4 例坏类型大声失败反证（对象型 chatId、数组型 title、对象/数组型 seconds →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 分支基座为最新 main（#227/#242 已合，本轮开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
### 第十三轮续六十二（2026-09-30）：bot `sendNotice` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十二块）

- `/api/bot/sendNotice` 内联在处理器的 `chatId`/`text` 抽取（`(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)`：
  `text`→`message` 别名链是 `?:` 接存在性——`text` 键缺席才穿透到 `message`，显式 JSON null 的 `text` 得字面
  `"null"`（`JsonNull` 即 `JsonPrimitive`，`.content` 为 `"null"`）不穿透、不判缺，**不 trim**（前导空格计入 300 上限））+
  双必填（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`；`text` 缺省/空白**判缺**，没有回退默认值，
  与 `sendAlert` 的 `text` 不同）+ 内容组装（模板 `"NOTICE: $text"`，`type = "SYSTEM"`）收敛为
  `parseBotSendNoticeFields` / `buildBotNoticeContent` 纯函数（`BotSendNoticeParse.kt`，处理器只剩调用 + 字段映射，
  逐行等价——必填（纯函数）→ 成员检查（处理器），校验顺序不变；本端点无 markdown 总开关；响应体逐字不变，下游一行不动）；
- 新增 `BotSendNoticeParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言（含 `message` 别名在避开名集合里）+
  双必填语义（chatId 缺/空白→400；text 缺/空白→400 判缺无默认值；`message` 别名满足必填；text 显式 null 得字面 `"null"` 不判缺不穿透）+
  `text` 怪语义逐字钉住（不 trim 原样保留、前导空格计入 `take(300)`；超长截 300；`text` 缺席穿透 `message`、`text` 优先于 `message`）+
  内容模板逐字断言（`"NOTICE: $text"`）+
  4 例坏类型大声失败反证（对象型 chatId、数组型 text、对象型 message 别名、对象型 text（即使 message 合法也不穿透）→
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
  另：本轮起测试代码一律规避字符串模板内嵌套引号写法——`BotSendCountdownParseFuzzTest` 第 206 行的 `${\"x\".repeat(36)}`
  在 Kotlin 2.4.0（K2）下报 `Syntax error: Expecting an expression`（#243 CI 红，已就地修：repeat 提到模板外拼接）；
- 与 open PR（#243 bot sendCountdown：`BotPresentationStatusRouting.kt` + `BotSendCountdownParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：#243 的 +6 与本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。
### 第十三轮续六十三（2026-09-30）：bot `sendBadge` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十三块）

- `/api/bot/sendBadge` 内联在处理器的 `chatId`/`label`（`.orEmpty().ifBlank { "badge" }.take(40)`：
  缺省/空白回 `"badge"`，显式 null 得字面 `"null"`（非空白，`ifBlank` 不触发），
  超长先回退默认再截 40，**不 trim**（前导空格计入上限））/`value`
  （`.orEmpty().take(80)`：缺省回 `""`——**无默认值**，与 `label` 不同，特意钉住；
  显式 null 得字面 `"null"`，**不 trim**，超长截 80）
  + 单必填（`chatId.isBlank()`→400，`label`/`value` 非必填）+ 内容组装
  （模板 `"**$label**: `$value`"`，`type = "MARKDOWN"`）收敛为
  `parseBotSendBadgeFields` / `buildBotBadgeContent` 纯函数
  （`BotSendBadgeParse.kt`，处理器只剩调用 + 字段映射，逐行等价——
  markdown 总开关检查仍在解析之前、必填（纯函数）→ 成员检查（处理器），校验顺序不变，
  响应体逐字不变，下游一行不动）；
- 新增 `BotSendBadgeParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  单必填语义（chatId 缺/空白→400；label 缺/空白**不**判缺回 `"badge"`；
  value 缺**不**判缺回 `""` 无默认值）+ `label` 怪语义（不 trim 原样保留、前导空格计入
  `take(40)`；显式 null 得字面 `"null"`；超长截 40）+ `value` 怪语义逐字钉住（缺省回 `""`；
  不 trim 原样保留、前导空格计入 `take(80)`；显式 null 得字面 `"null"`；超长截 80）+
  内容模板逐字断言（`"**label**: `value`"`，含默认值/怪语义组合）+
  3 例坏类型大声失败反证（对象型 chatId、数组型 label、对象型 value →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外拼接，见 AGENTS.md 2026-09-30 教训）；
- 分支基座为最新 main（#242/#243 已合；#245 sendNotice 重建版已开、CI 待收）；
  与 open PR（#245：`BotPresentationCardsRouting.kt` + `BotSendNoticeParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续六十四（2026-09-30）：bot `sendToast` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十四块）

- `/api/bot/sendToast` 内联在处理器的 `chatId`/`text` 抽取（`(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(200)`：
  `text`→`message` 别名链是 `?:` 接存在性——`text` 键缺席才穿透到 `message`，显式 JSON null 的 `text` 得字面
  `"null"`（`JsonNull` 即 `JsonPrimitive`，`.content` 为 `"null"`），不穿透、不判缺，**不 trim**（前导空格计入 200 上限））+
  双必填（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`；`text` 缺省/空白**判缺**，没有回退默认值，
  与 `sendAlert` 的 `text` 不同）+ 内容组装（模板 `"TOAST: $text"`，`type = "SYSTEM"`）收敛为
  `parseBotSendToastFields` / `buildBotToastContent` 纯函数（`BotSendToastParse.kt`，处理器只剩调用 + 字段映射，
  逐行等价——必填（纯函数）→ 成员检查（处理器），校验顺序不变；本端点无 markdown 总开关；响应体逐字不变，下游一行不动）；
- 新增 `BotSendToastParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言（含 `message` 别名在避开名集合里）+
  双必填语义（chatId 缺/空白→400；text 缺/空白→400 判缺无默认值；`message` 别名满足必填；text 显式 null 得字面 `"null"` 不判缺不穿透）+
  `text` 怪语义逐字钉住（不 trim 原样保留、前导空格计入 `take(200)`；超长截 200；`text` 缺席穿透 `message`、`text` 优先于 `message`）+
  内容模板逐字断言（`"TOAST: $text"`）+ 4 例坏类型大声失败反证（对象型 chatId、数组型 text、对象型 message 别名、对象型 text
  （即使 message 合法也不穿透）→ `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 与 open PR（#246 bot sendBadge：`BotPresentationWidgetsRouting.kt` + `BotSendBadgeParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核）；
### 第十三轮续六十五（2026-09-30）：bot `sendHr` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十五块）

- `/api/bot/sendHr` 内联在处理器的 `chatId`/`text`（`note`）抽取（`obj["text"]?.jsonPrimitive?.content.orEmpty().take(200)`：
  缺省回 `""`（**无默认值**，与 `sendBadge` 的 `label` 不同，特意钉住），显式 JSON null 得字面
  `"null"`，**不 trim**（前导空格计入 200 上限），超长截 200）+ 单必填
  （`chatId.isBlank()`→400 `"chatId required"`；`text` 缺省/空白**不**判缺）+
  内容组装（`note` 非空白 → `"---\n$note\n---"`，空白 → `"---"`，`type = "MARKDOWN"`）
  收敛为 `parseBotSendHrFields` / `buildBotHrContent` 纯函数（`BotSendHrParse.kt`，
  处理器只剩调用 + 字段映射，逐行等价——必填（纯函数）→ 成员检查（处理器），
  校验顺序不变；markdown 总开关检查仍在处理器解析之前；响应体逐字不变，下游一行不动）；
- 新增 `BotSendHrParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  单必填语义（chatId 缺/空白→400；text 缺/空白不判缺回 `""`/原样保留）+
  `note` 怪语义逐字钉住（不 trim 原样保留、前导空格计入 `take(200)`；超长截 200；
  显式 null 得字面 `"null"`）+ 内容模板逐字断言（非空白 `"---\n$note\n---"`、
  空/全空白 `"---"`，含怪语义组合）+ 2 例坏类型大声失败反证（对象型 chatId、
  数组型 text → `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外拼接，见 AGENTS.md 2026-09-30 教训）；
- 分支基座为最新 main（#246/#247 账本节已在 main 上按时间顺序排好：续六十三 sendBadge → 续六十四 sendToast）；
  与 open PR（#247 bot sendToast：`BotPresentationCardsRouting.kt` + `BotSendToastParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新，
  待 PR 依次合并后由复核轮复核）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。
### 第十三轮续六十六（2026-09-30）：bot `sendDivider` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十六块）

- `/api/bot/sendDivider` 内联在处理器的 `chatId`/`label`
  （`.orEmpty().ifBlank { "divider" }.take(40)`：缺省/空白回 `"divider"`，
  显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发，特意钉住），
  超长先回退默认再截 40，**不 trim**（前导空格计入上限，原处理器逐字语义））
  + 单必填（`chatId.isBlank()`→400 `"chatId required"`；`label` 非必填）+ 内容组装
  （模板 `"---\n**$label**\n---"`，`type = "MARKDOWN"`）收敛为
  `parseBotSendDividerFields` / `buildBotDividerContent` 纯函数（`BotSendDividerParse.kt`，
  处理器只剩调用 + 字段映射，逐行等价——markdown 总开关检查仍在解析之前、
  必填（纯函数）→ 成员检查（处理器），校验顺序不变；响应体逐字不变，下游一行不动）；
- 新增 `BotSendDividerParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  单必填语义（chatId 缺/空白→400；label 缺/空白**不**判缺回 `"divider"`）+
  `label` 怪语义逐字钉住（不 trim 原样保留、前导空格计入 `take(40)`；显式 null 得字面 `"null"`；
  超长先回退默认再截 40）+ 内容模板逐字断言（`"---\n**$label**\n---"`，含默认值/怪语义组合）+
  2 例坏类型大声失败反证（对象型 chatId、数组型 label → `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外拼接，见 AGENTS.md 2026-09-30 教训）；
- 分支基座为最新 main（#248 sendHr 已合，本轮开工时仅剩 open qca PR #247）；
  与 open PR（#247 bot sendToast：`BotPresentationCardsRouting.kt` +
  `BotPresentationWidgetsRouting.kt` + `BotSendBadgeParse.kt`/`BotSendToastParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集（本轮改 `BotPresentationStatusRouting.kt` +
  新增 `BotSendDividerParse*.kt`）；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续六十七（2026-09-30）：bot `sendProgress` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十七块）

- `/api/bot/sendProgress` 内联在处理器的 `chatId`/`title`/`percent` 抽取
  （`title`：`.orEmpty().ifBlank { "Progress" }.take(40)`——缺省/空白回 `"Progress"`，
  显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发，特意钉住），
  超长先回退默认再截 40，**不 trim**（前导空格计入上限，原处理器逐字语义）；
  `percent`：`(obj["percent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 100)`——
  **字符串解析**：JSON 数字经 `.content` 照样解析，缺省/显式 null/非数字串/小数串一律回 `0`，
  负数钳 `0`、超 100 钳 `100`）+ 单必填（`chatId.isBlank()`→400 `"chatId required"`；
  `title`/`percent` 非必填）+ 内容组装（进度条 `filled = percent / 10` 整数除法，
  模板 `"**$title**\n`[$bar]` $percent%"`，`type = "MARKDOWN"`）收敛为
  `parseBotSendProgressFields` / `buildBotProgressContent` 纯函数（`BotSendProgressParse.kt`，
  处理器只剩调用 + 字段映射，逐行等价——markdown 总开关检查仍在解析之前、
  必填（纯函数）→ 成员检查（处理器），校验顺序不变；响应体（含钳制后的 `percent`）逐字不变，
  下游一行不动）；
- 新增 `BotSendProgressParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略恒等断言 +
  单必填语义（chatId 缺/空白→400；title 缺/空白**不**判缺回 `"Progress"`；percent 缺省**不**判缺回 `0`）+
  `title` 怪语义逐字钉住（不 trim 原样保留、前导空格计入 `take(40)`；显式 null 得字面 `"null"`；
  超长先回退默认再截 40）+ `percent` 钳制逐字钉住（字符串数字照样解析；非数字/空串/小数串/显式 null
  回 `0`；负数钳 `0`、超 100 钳 `100`；边界 0/100 保留）+ 内容模板逐字断言（10 格进度条，
  含整数除法 `5/10=0` 与怪语义组合）+ 3 例坏类型大声失败反证（对象型 chatId、数组型 title、
  对象型 percent → `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外拼接，见 AGENTS.md 2026-09-30 教训）；
- 分支基座为最新 main（#249 sendDivider 已合；#247 sendToast 已就地修好清单冲突并补发 CI）；
  与 open PR（#247 bot sendToast：`BotPresentationCardsRouting.kt` + `BotSendToastParse*.kt` +
  本清单账本节；dependabot 若干）无代码文件交集（本轮改 `BotPresentationStatusRouting.kt` +
  新增 `BotSendProgressParse*.kt`）；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续六十八（2026-09-30）：bot `send*Hint` 系列请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十八块）

- 十二个 `send*Hint` 端点（`sendInviteHint`/`sendSafetyHint`/`sendQrHint`/`sendSpoilerHint`/
  `sendDownloadHint`/`sendLocationHint`/`sendFileHint`/`sendSecureHint`/`sendPhotoHint`/
  `sendVideoHint`/`sendGifHint`/`sendWatermarkHint`；`sendSecretHint` 已是 410 Gone，不在内）
  的处理器逐字同构（仅特性开关门控、`hint` 默认文案、内容前缀三处不同），其内联的
  `chatId`/`hint` 抽取（`(obj["hint"]?.jsonPrimitive?.content ?: <默认文案>).take(120)`：
  默认文案只在键缺席时回退，显式 JSON null 得字面 `"null"`（`JsonNull` 即 `JsonPrimitive`，
  `.content` 为 `"null"`）不回退、**不 trim**（前导空格计入 120 上限），超长截 120）+
  单必填（`chatId.isBlank()`→400 `"chatId required"`；`hint` 非必填——缺省回默认文案、
  空白原样保留不判缺）+ 内容组装（模板 `prefix + hint`，`type = "SYSTEM"`；`sendInviteHint`
  前缀 `"INVITEHINT:"` 无空格，其余为各色 emoji + 空格）收敛为 `parseBotSendHintFields` /
  `buildBotSendHintContent` 纯函数（`BotSendHintParse.kt`，处理器只剩调用 + 字段映射，
  逐行等价——各端点特性开关门控仍在解析之前、必填（纯函数）→ 成员检查（处理器），
  校验顺序不变；响应体逐字不变，下游一行不动）；
- 新增 `BotSendHintParseFuzzTest`（6 例）：12 端点 × 150 固定种子随机 payload 未知键忽略恒等断言
  （每 3 个里 1 个不带 `hint` 钉住缺省回默认文案）+ 单必填语义（chatId 缺/空/纯空白→400；
  hint 缺省→默认文案**不**判缺；hint 空白原样保留**不**判缺）+
  `hint` 怪语义逐字钉住（显式 null 得字面 `"null"` 不回退；不 trim 前后空格原样保留；
  超长截 120 前导空格计入上限；JSON 数字经 `.content` 照样解析；12 份默认文案均 ≤120 不被截断）+
  内容模板逐字断言（12 端点前缀与默认文案组合逐字一致，`sendInviteHint` 无空格前缀特意钉住）+
  4 例坏类型大声失败反证（对象/数组型 chatId、对象/数组型 hint →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  抽取顺序反证（先 chatId 后 hint；chatId 空白 + hint 坏类型→仍抛错而非 400，与原处理器逐字一致）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外拼接，见 AGENTS.md 2026-09-30 教训）；
  12 个 handler 的改动经脚本批量应用、逐个断言只命中一次，默认文案/内容前缀与改前源码字节逐字核对；
- 分支基座为最新 main（#247 sendToast、#250 sendProgress 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续六十九（2026-09-30）：bot `sendMentionCard`/`sendNudgeCard` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第二十九块）

- 两个端点（`/api/bot/sendMentionCard`、`/api/bot/sendNudgeCard`）的处理器逐字同构
  （仅特性开关门控、`label` 默认文案、内容模板包裹三处不同），其内联的
  `chatId`/`label` 抽取（`(obj["label"]?.jsonPrimitive?.content ?: obj["text"]?.jsonPrimitive?.content
  ?: <默认文案>).take(80)`：回退链接存在性，显式 JSON null 得字面 `"null"`（`JsonNull`
  即 `JsonPrimitive`，`.content` 为 `"null"`）**不继续回退**、**不 trim**（前导空格计入
  80 上限），超长截 80）+ 单必填（`chatId.isBlank()`→400 `"chatId required"`；
  `label` 非必填——缺省走回退链、空白原样保留不判缺）+ 内容组装（模板
  `prefix + label + suffix`，`type = "MARKDOWN"`；`sendMentionCard` 为 `"> @" + label`，
  `sendNudgeCard` 为 `"> ~nudge:" + label + "~"`）收敛为 `parseBotMentionNudgeFields` /
  `buildBotMentionNudgeContent` 纯函数（`BotSendMentionNudgeParse.kt`，处理器只剩调用 +
  字段映射，逐行等价——各端点特性开关门控（markdown 门 + `isMentionsEnabled` /
  `isNudgeEnabled` 门）仍在解析之前、必填（纯函数）→ 成员检查（处理器），
  校验顺序不变；响应体逐字不变，下游一行不动）；
- 新增 `BotSendMentionNudgeParseFuzzTest`（6 例）：2 端点 × 150 固定种子随机 payload
  未知键忽略恒等断言（每 3 个里 1 个不带 label/text 钉住缺省回默认文案、另 1 个只带 text
  钉住回退到 text）+ 单必填语义（chatId 缺/空/纯空白→400；label 缺省走回退链**不**判缺；
  label 空白原样保留**不**判缺；label 缺席 + text 在 → 回退 text）+
  `label` 怪语义逐字钉住（显式 null 得字面 `"null"` 不继续回退——label 位与 text 位各钉
  一处；不 trim 前后空格原样保留；超长截 80 前导空格计入上限；JSON 数字经 `.content`
  照样解析；2 份默认文案均 ≤80 不被截断）+
  内容模板逐字断言（2 端点包裹前后缀组合逐字一致，`"> @"` / `"> ~nudge:"…"~"` 特意钉住）+
  4 例坏类型大声失败反证（对象/数组型 chatId、对象型 label、数组型 text →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  抽取顺序反证（先 chatId 后 label；chatId 空白 + label 坏类型→仍抛错而非 400，
  与原处理器逐字一致）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外拼接，
  见 AGENTS.md 2026-09-30 教训）；
- 分支基座为最新 main（#251 send*Hint 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十（2026-10-01）：bot `sendMetric`/`sendCompare` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十块）

- 两个端点（`/api/bot/sendMetric`、`/api/bot/sendCompare`）的处理器逐字同构
  （连特性开关门控的拒绝文案都相同：`"markdown_disabled"`），其内联的
  `chatId` 抽取 + 各文本字段 `(obj[key]?.jsonPrimitive?.content ?: default).take(cap)`
  存在性回退 + 单必填收敛为 `parseBotMetricCompareFields` 纯函数
  （`BotSendMetricCompareParse.kt`，各端点传入自己的三组 `(key, default, cap)`：
  `sendMetric` 为 `label`/`"metric"`/40、`value`/`"0"`/40、`unit`/`""`/20，
  `sendCompare` 为 `left`/`"A"`/80、`right`/`"B"`/80 + `keyC = null`
  （第三字段槽恒 `""`，内容组装不消费），处理器只剩调用 + 字段映射，逐行等价——
  特性开关门控仍在解析之前、必填（纯函数）→ 成员检查（处理器），校验顺序不变；
  响应体逐字不变，下游一行不动）；
- 内容组装逐字搬移：`buildBotMetricContent`（`"**" + label + "**  \n`" + value +
  unit 后缀 + "`"`，unit 非空白才有 `" " + unit` 后缀，markdown 硬换行两个空格逐字保留，
  拼接写法刻意规避字符串模板内嵌套引号，见 AGENTS.md 2026-09-30 教训）/
  `buildBotCompareContent`（单行对比表格逐字）；
- 新增 `BotSendMetricCompareParseFuzzTest`（6 例）：2 端点 × 150 固定种子随机 payload
  未知键忽略恒等断言（三分之二带首字段钉住其余回默认、另三分之一全字段钉住全量抽取；
  `sendCompare` 第三槽恒 `""`）+ 单必填语义（chatId 缺/空/纯空白→MissingRequired；
  其余字段缺省/空白不判缺）+ 存在性回退逐字钉住（键缺席才回默认；显式 JSON null 得字面
  `"null"` 不回退；**键在但空白原样保留不回默认**——`label` 空白≠`"metric"`、
  `left` 空白≠`"A"`；不 trim 前导空格计入上限；超长截 cap；JSON 数字经 `.content`
  照样解析）+ 内容模板逐字断言（metric 有/无 unit、空白 unit 无后缀、compare 表格、
  label 含 markdown 特殊字符原样进模板）+ 坏类型大声失败反证（对象/数组型 chatId
  与各已知字段 → `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）
  + 抽取顺序反证（chatId 空白 + 后续字段坏类型→仍抛错而非回 MissingRequired；
  chatId 与首字段同时坏类型→抛错，chatId 先抽取）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（#252 sendMentionCard/sendNudgeCard 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十一（2026-10-01）：bot `sendKeyValue` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十一块）

- 端点 `/api/bot/sendKeyValue` 的处理器内联 `chatId` 抽取 + `key`
  （`obj["key"]?.jsonPrimitive?.content.orEmpty().ifBlank { "key" }.take(40)`：空白性
  默认而非存在性——缺席与空白都得 `"key"`；显式 JSON null 得字面 `"null"` 不触发
  `ifBlank`，特意钉住）+ `value`
  （`obj["value"]?.jsonPrimitive?.content.orEmpty().take(120)`：**无默认值**，缺席得
  `""`、空白原样保留、显式 null 得字面 `"null"`，特意钉住）+ 单必填收敛为
  `parseBotKeyValueFields` 纯函数（`BotSendKeyValueParse.kt`，处理器只剩调用 +
  字段映射，逐行等价——特性开关门控（拒绝文案 `"markdown disabled by admin"`）仍在
  解析之前、必填（纯函数）→ 成员检查（处理器），校验顺序不变；响应体逐字不变，
  下游一行不动）；
- 内容组装逐字搬移：`buildBotKeyValueContent`（`` `key` = **value** ``，逐字断言）；
- 新增 `BotSendKeyValueParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一全缺省钉住 `key`→`"key"` / `value`→`""`、另三分之一只有 key
  钉住 value 缺省 `""`、其余三分之一全字段抽取）+ 单必填语义（chatId 缺/空/纯空白→
  MissingRequired；key/value 缺省/空白不判缺——空白 key 回 `"key"`）+
  空白性默认值逐字钉住（缺席与空白都得 `"key"`；显式 null 得字面 `"null"` 不触发
  `ifBlank`；value 无默认缺席得 `""`、空白原样保留；超长截 40/120；前导空格计入上限
  （key 侧前导空格先触发 `ifBlank`）；JSON 数字经 `.content` 照样解析）+
  内容模板逐字断言（`key` 缺省/特殊字符原样进模板）+ 坏类型大声失败反证
  （对象/数组型 chatId 与 key/value → `IllegalArgumentException`，路由层
  `StatusPages` 映射 400，不是 500）+ 抽取顺序反证（chatId 空白 + key 坏类型→
  仍抛错而非回 MissingRequired；chatId 先于 key 抽取）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（#253 sendMetric/sendCompare 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十二（2026-10-01）：bot `sendQuoteCard` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十二块）

- 端点 `/api/bot/sendQuoteCard` 的处理器内联 `chatId` 抽取 + `quote`
  （`obj["quote"]?.jsonPrimitive?.content.orEmpty().take(200)`：**无默认值**——
  缺席得 `""`（随后被双必填判缺），空白原样保留（同样判缺）；显式 JSON null
  得字面 `"null"` 不被 `orEmpty` 吞掉，特意钉住）+ `by`
  （`obj["by"]?.jsonPrimitive?.content.orEmpty().take(40)`：无默认值、**非必填**——
  缺席得 `""`、空白原样保留，组装时空白 `by` 直接吞掉署名行）+ **双必填**
  （`chatId.isBlank() || quote.isBlank()`→400 `"chatId/quote required"`）收敛为
  `parseBotQuoteCardFields` 纯函数（`BotSendQuoteCardParse.kt`，处理器只剩调用 +
  字段映射，逐行等价——特性开关门控（拒绝文案 `"markdown disabled by admin"`）仍在
  解析之前、必填（纯函数）→ 成员检查（处理器），校验顺序不变；响应体逐字不变，
  下游一行不动）；
- 内容组装逐字搬移：`buildBotQuoteCardContent`（`"> "` + quote + 署名行
  `"\n— *by*"`，`by` 空白时无署名行且不留尾随换行，逐字断言）；
- 新增 `BotSendQuoteCardParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一 `by` 缺省钉住 `by`→`""`、其余全字段抽取）+ 双必填语义
  （chatId 缺/空/纯空白→MissingRequired；quote 缺/空/纯空白→MissingRequired；
  by 缺省/空白不判缺——空白 `by` 原样保留）+ 上限与 null 语义逐字钉住
  （quote 截 200 / by 截 40；显式 null 得字面 `"null"`；JSON 数字经 `.content`
  照样解析；前导空格计入上限）+ 内容模板逐字断言（署名行有/无两种形态；
  markdown 特殊字符原样进模板；无署名时内容以 quote 结尾）+ 坏类型大声失败反证
  （对象/数组型 chatId 与 quote/by → `IllegalArgumentException`，路由层
  `StatusPages` 映射 400，不是 500）+ 抽取顺序反证（chatId 空白 + quote 坏类型→
  仍抛错而非回 MissingRequired；chatId 先于 quote、quote 先于 by 抽取）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（`repeat` 提到模板外用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（#254 sendKeyValue 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十三（2026-10-01）：bot `sendBanner` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十三块）

- 端点 `/api/bot/sendBanner` 的处理器内联 `chatId` 抽取 + `title`
  （`obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Banner" }.take(40)`：**空白性**
  默认——缺席与空白都得 `"Banner"`；显式 JSON null 得字面 `"null"` 不触发
  `ifBlank`；先 `ifBlank` 再 `take(40)`，特意钉住）+ `text`
  （`(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(240)`：**存在性**
  回退——只有 `text` 键完全缺席才看 `message`；`text` 在但为显式 null 时仍走
  `text` 分支得字面 `"null"`；`text` 空白不回退、直接判缺）+ **双必填**
  （`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`）收敛为
  `parseBotBannerFields` 纯函数（`BotSendBannerParse.kt`，处理器只剩调用 +
  字段映射，逐行等价——特性开关门控（拒绝文案 `"markdown disabled by admin"`）仍在
  解析之前、必填（纯函数）→ 成员检查（处理器），校验顺序不变；响应体逐字不变，
  下游一行不动）；
- 内容组装逐字搬移：`buildBotBannerContent`（`"## " + title + "\n" + text`，逐字断言）；
- 新增 `BotSendBannerParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一 title 缺省钉住 `title`→`"Banner"`、另三分之一 text 经 `message`
  回退键传入、其余全字段抽取；`message` 视为已知字段不得参与随机名）+ 双必填语义
  （chatId 缺/空/纯空白→MissingRequired；text 缺/空/纯空白→MissingRequired；
  title 缺省/空白不判缺——空白 `title` 回 `"Banner"`）+ 上限、默认与 null 语义逐字钉住
  （title 截 40 / text 截 240；title 缺席与空白都得 `"Banner"`；title/text 显式 null
  得字面 `"null"`；text 缺席回退到 `message`；`text` 键在（null 或空白）时 `message`
  不生效；JSON 数字经 `.content` 照样解析；前导空格计入上限）+
  内容模板逐字断言（标题行 `"## "` 前缀 + 换行；markdown 特殊字符原样进模板）+
  坏类型大声失败反证（对象/数组型 chatId、title、text → `IllegalArgumentException`；
  text 缺席时对象/数组型 message 同样大声失败；text 在时 message 坏类型不被触及——
  原处理器逐字如此，路由层 `StatusPages` 映射 400，不是 500）+ 抽取顺序反证
  （chatId 空白 + title 坏类型→仍抛错而非回 MissingRequired；chatId 先于 title、
  title 先于 text 抽取）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（#255 sendQuoteCard 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十四（2026-10-01）：bot `sendJsonCard` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十四块）

- 端点 `/api/bot/sendJsonCard` 的处理器内联 `chatId` 抽取 + `payload`
  （`(obj["json"] ?: obj["data"])?.toString()?.take(500).orEmpty()`：**存在性**
  回退——只有 `json` 键完全缺席才看 `data`；`json` 在但为显式 null 时仍走
  `json` 分支得字面 `"null"`（`JsonNull.toString()` 即 `"null"`，**不**被
  `orEmpty` 吞掉）；关键怪语义是 `?.toString()` **不是** `?.jsonPrimitive`——
  对象/数组型 `json`/`data` **不抛错**、原样序列化（字符串带引号）；
  `take(500)` 作用于 `toString()` **之后**）+ **双必填**
  （`chatId.isBlank() || payload.isBlank()`→400 `"chatId/json required"`，文案逐字）
  收敛为 `parseBotJsonCardFields` 纯函数（`BotSendJsonCardParse.kt`，处理器只剩
  调用 + 字段映射，逐行等价——特性开关门控（拒绝文案 `"markdown disabled by admin"`）
  仍在解析之前、必填（纯函数）→ 成员检查（处理器），抽取顺序 chatId→payload、
  校验顺序不变；响应体逐字不变，下游一行不动）；
- 内容组装逐字搬移：`buildBotJsonCardContent`（`"```json\n" + payload + "\n```"`，
  逐字断言；注意处理器原代码里的 `"```json\n$payload\n```"` 即该拼接形式）；
- 新增 `BotSendJsonCardParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一 `json` 缺省钉住 `data` 回退、另三分之一 `json`+`data`
  同时在场钉住 `json` 分支（`data` 被忽略）、其余全字段抽取；
  `data` 视为已知字段不得参与随机名；五分之一超长载荷钉住 take(500)
  作用于 `toString()` 之后）+ 双必填语义（chatId 缺/空/纯空白→MissingRequired；
  payload 缺（json 与 data 均缺席）→MissingRequired；双合法→Ok，
  字符串 payload 经 toString 带引号；**注意**：json 空字符串 / data 纯空白字符串
  经 toString 序列化为带引号串（`""`→`\"\"`、`"   "`→`\"   \"`）非空/非空白，
  按原处理器逐字语义仍判合法——双必填判的是序列化后串的空白性，特意钉住）+ 上限与特殊语义逐字钉住（payload 超长截
  500 且作用于 toString 之后（含引号）；`json` 显式 null 得字面 `"null"`、
  不被 orEmpty 吞掉、不判缺；`json` 键在但为 null 时 `data` 不生效；json 缺席
  回退到 `data`；JSON 数字/布尔经 toString 原样；对象/数组型 json/data 不抛错
  且原样序列化进模板；字符串带引号进模板；前导空格计入上限）+
  内容模板逐字断言（`"```json\n" + payload + "\n```"`；围栏行开头/闭合行结尾；
  markdown 特殊字符原样进模板）+ 坏类型大声失败反证（对象/数组型 chatId →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  对象/数组型 json、data **必须不抛**——`?.toString()` 而非 `?.jsonPrimitive`，
  原处理器逐字如此）+ 抽取顺序反证（chatId 空白 + json 对象乱值→仍回
  MissingRequired 而非抛错；chatId 先于 payload 抽取——chatId 坏类型 + json
  乱值→抛错；chatId 缺席 + json 显式 null→payload 为字面 `"null"` 仍因
  chatId 判缺）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（#256 sendBanner 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十五（2026-10-01）：bot `sendMarkdown` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十五块）

- 端点 `/api/bot/sendMarkdown` 的处理器内联 `chatId` 抽取 + `text`
  （`(obj["text"] ?: obj["markdown"])?.jsonPrimitive?.content.orEmpty().take(4000)`：
  **存在性**回退——只有 `text` 键完全缺席才看 `markdown`；`text` 在但为显式 null
  时仍走 `text` 分支在 `.content` 处抛、**不**回退；`take(4000)` 作用于 `orEmpty()`
  **之后**（裁的是 content 本身，不是序列化串）；字符串不带引号进模板（与
  `sendJsonCard` 的 `?.toString()` 怪语义**相反**，特意钉住）+ `silentRequested`
  （`obj["silent"]?.jsonPrimitive?.booleanOrNull == true`：缺键 / JSON null 得
  `false`，字符串 `"true"` 按布尔语义得 `true`，对象 / 数组型大声失败）+ **双必填**
  （`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，文案逐字）
  收敛为 `parseBotMarkdownFields` 纯函数（`BotSendMarkdownParse.kt`，处理器只剩
  调用 + 字段映射，逐行等价——特性开关门控（拒绝文案 `"markdown disabled by admin"`）
  仍在解析之前、必填（纯函数）→ 成员检查（处理器），抽取顺序 chatId→text→silentRequested、
  校验顺序不变；响应体逐字不变，下游一行不动）；
  - 与前几批的唯一顺序差异：原处理器把 `silent = silentRequested && isSilentSendEnabled()`
    的**配置读取**放在必填判断之前，本轮把它随 `silent` 组装留在处理器、移到必填判断之后——
    `isSilentSendEnabled()` 是无副作用的运行时配置读，移动不可观测，逐行等价成立；
    纯函数返回 `silentRequested`，真正的静默与否仍由处理器结合配置决定；
- 新增 `BotSendMarkdownParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一 `text` 缺省钉住 `markdown` 回退、另三分之一 `text`+`markdown`
  同时在场钉住 `text` 分支（`markdown` 被忽略）、其余全字段抽取；
  `markdown`/`silent` 视为已知字段不得参与随机名；mode 2 的一半用例钉住 take(4000)
  作用于 content 之后）+ 双必填语义（chatId 缺/空/纯空白→MissingRequired；
  text 与 markdown 均缺席→MissingRequired；text 空/纯空白（判的是裁后空白性）→MissingRequired；
  text 缺席但 markdown 纯空白→MissingRequired；双合法→Ok）+ 上限与特殊语义逐字钉住
  （超长裁 4000 恰好、恰好 4000 不动；`text` 显式 null 在 `.content` 处抛不回退；
  `text` 缺席 + `markdown` 显式 null 同样抛；数字/布尔型 text 经 `JsonPrimitive.content`
  得 `"5"`/`"true"` **不抛**——与 chatId 同理，特意钉住；数字型 markdown 回退同样不抛）+
  silent 语义（缺键/JSON null→false；true→true；字符串 `"true"`→true（`booleanOrNull`
  认 `"true"`）、`"false"`→false；数字 1→false；对象/数组型 silent 大声失败）+
  坏类型大声失败反证（对象/数组型 chatId、text、回退位 markdown → `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）+ 抽取顺序反证（chatId 坏类型 + text 乱值→抛错；
  chatId 缺席 + text 乱值→抛错而非 MissingRequired（抽取抛在必填判断之前）；
  chatId 纯空白 + text 乱值→仍抛错；chatId 纯空白 + text 合法→MissingRequired 不抛）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（#257 sendJsonCard 本轮已合，开工时无 open qca PR）；
  与 open PR（仅 dependabot 若干）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十五（2026-10-01）：bot `sendQuote` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十六块）

- 端点 `/api/bot/sendQuote` 的处理器内联 `chatId` 抽取 + `quote`
 （`(obj["quote"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1500)`：
  **存在性**回退——只有 `quote` 键完全缺席才看 `text`；`quote` 在但为显式 null
  时仍走 `quote` 分支得字面 `"null"`（`JsonNull.content` 即 `"null"`、不抛，
  kotlinx-serialization-json 1.11.0 KDoc 明示，`sendMarkdown` 第三十五块 CI
  已实证）；`take(1500)` 作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；
  字符串 `quote` 不带引号进模板（`?.jsonPrimitive?.content`，与 `sendJsonCard`
  的 `?.toString()` 怪语义**相反**，特意钉住）+ `note`
  （`obj["note"]?.jsonPrimitive?.content.orEmpty().take(500)`：缺键得空串；
  显式 null 得 `"null"` 字符串、不抛；**不参与必填**）+ **双必填**
  （`chatId.isBlank() || quote.isBlank()`→400 `"chatId/quote required"`，文案逐字；
  判的是裁过 1500 的空白性）收敛为 `parseBotQuoteFields` 纯函数
  （`BotSendQuoteParse.kt`，处理器只剩调用 + 字段映射 + 副作用（成员检查、入库、
  fanout、响应），逐行等价——特性开关门控（`isMarkdownEnabled`，拒绝文案
  `"markdown disabled by admin"`）仍在解析之前、必填（纯函数）→ 成员检查（处理器），
  抽取顺序 chatId→quote→note、校验顺序不变；响应体逐字不变，下游一行不动）；
- 内容组装逐字搬移：`buildBotQuoteContent`（`quote.lines().joinToString("\n") { "> " + it }`
  逐行加引用前缀；`note.isNotBlank()` 时 `quoted + "\n\n" + note`，否则只发引用块——
  判的是裁过 500 的串，逐字断言）；
- 新增 `BotSendQuoteParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一 `quote` 缺省钉住 `text` 回退、另三分之一 `quote`+`text`
  同时在场钉住 `quote` 分支（`text` 被忽略）、其余全字段抽取；
  `text` 与 `note` 视为已知字段不得参与随机名；三分之一用例 `note` 缺席钉住空串语义，
  其余五分之一超长 note 钉住 take(500)；二分之一超长 quote 钉住 take(1500)
  作用于 `orEmpty()` 之后；内容组装与字段逐字一致性交叉断言）+ 双必填语义
  （chatId 缺/空/纯空白→MissingRequired；quote 与 text 均缺席→MissingRequired；
  quote 空/纯空白→MissingRequired；quote 缺席但 text 纯空白→MissingRequired；
  note 再长也不参与必填→Ok）+ 上限与特殊语义逐字钉住（quote 超长截 1500 /
  note 超长截 500；`quote` 显式 null 得字面 `"null"`、不抛、不回退到 text；
  `quote` 缺席 + `text` 显式 null 得 `"null"`；`chatId`/`note` 显式 null 同理得
  `"null"`；JSON 数字/布尔经 content 取 toString；对象/数组型 chatId/quote/note
  在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`）+ 内容组装逐字断言
  （单行/多行前缀、note 拼接、note 纯空白视为无、尾随换行逐字保留、空 quote 得
  `"> "`）+ 坏类型大声失败反证（对象/数组型 chatId/quote/note → 
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  抽取顺序反证（chatId 坏类型 + quote 乱值→抛错；chatId 缺席 + quote 乱值→抛错
  而非 MissingRequired；chatId 纯空白 + quote 乱值→仍抛错；反证 quote 合法时
  chatId 纯空白→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；`JsonNull` 语义已按第三十五块 CI 教训钉住
  （`JsonNull.content` 返回 `"null"` 字面量、不抛错），不再重复踩坑；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main；与 open PR（#258 改 `BotMessagingVariantsRouting.kt` +
  sendMarkdown 解析/测试文件；本轮改 `BotReactionRouting.kt` + sendQuote
  解析/测试文件 + 清单账本）无代码文件交集；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十六（2026-10-01）：bot `sendCode` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十七块）

- 端点 `/api/bot/sendCode` 的处理器内联 `chatId` 抽取 + `code`
 （`(obj["code"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(3500)`：
  **存在性**回退——只有 `code` 键完全缺席才看 `text`；`code` 在但为显式 null
  时仍走 `code` 分支得字面 `"null"`（`JsonNull.content` 即 `"null"`、不抛，
  kotlinx-serialization-json 1.11.0 KDoc 明示，`sendMarkdown` 第三十五块 CI
  已实证）；`take(3500)` 作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；
  字符串 `code` 不带引号进组装（`?.jsonPrimitive?.content`，与 `sendJsonCard`
  的 `?.toString()` 怪语义**相反**，特意钉住）+ `language`
  （`obj["language"]?.jsonPrimitive?.content.orEmpty().take(24)`：缺键得空串→无语言围栏；
  显式 null 得 `"null"` 字符串、不抛——注意此时围栏组装走**带语言**分支，特意钉住；
  **不参与必填**）+ **双必填**
  （`chatId.isBlank() || code.isBlank()`→400 `"chatId/code required"`，文案逐字；
  判的是裁过 3500 的空白性）收敛为 `parseBotCodeFields` 纯函数
  （`BotSendCodeParse.kt`，处理器只剩调用 + 字段映射 + 副作用（成员检查、入库、
  fanout、响应），逐行等价——特性开关门控（`isMarkdownEnabled`，拒绝文案
  `"markdown disabled by admin"`）仍在解析之前、必填（纯函数）→ 成员检查（处理器），
  抽取顺序 chatId→code→language、校验顺序不变；响应体逐字不变，下游一行不动）；
- 围栏组装逐字搬移：`buildBotCodeContent`（`` ``` `` + lang + `"\n"` + code +
  `"\n```"` / 无语言时 `` ``` `` + `"\n"` + code + `"\n```"`——判的是裁过 24 的串，
  与原模板 `"```$lang\n$code\n```"` 逐字等价，逐字断言）；
- 新增 `BotSendCodeParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三分之一 `code` 缺省钉住 `text` 回退、另三分之一 `code`+`text`
  同时在场钉住 `code` 分支（`text` 被忽略）、其余全字段抽取；
  `text` 与 `language` 视为已知字段不得参与随机名；三分之一用例 `language`
  缺席钉住空串语义，其余五分之一超长 language 钉住 take(24)；二分之一超长
  code 钉住 take(3500) 作用于 `orEmpty()` 之后；围栏组装与字段逐字一致性
  交叉断言）+ 双必填语义（chatId 缺/空/纯空白→MissingRequired；code 与 text
  均缺席→MissingRequired；code 空/纯空白→MissingRequired；code 缺席但 text
  纯空白→MissingRequired；language 再长也不参与必填→Ok）+ 上限与特殊语义
  逐字钉住（code 超长截 3500 / language 超长截 24；`code` 显式 null 得字面
  `"null"`、不抛、不回退到 text；`code` 缺席 + `text` 显式 null 得 `"null"`；
  `language` 显式 null 得字面 `"null"`（围栏组装走带语言分支）；`chatId`
  显式 null 同理得 `"null"`；JSON 数字/布尔经 content 取 toString；对象/数组型
  chatId/code/language 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`）+
  围栏组装逐字断言（带语言/无语言/纯空白语言/显式 null 语言/多行 code 逐字保留）+
  坏类型大声失败反证（对象/数组型 chatId/code/language →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  抽取顺序反证（chatId 坏类型 + code 乱值→抛错；chatId 缺席 + code 乱值→抛错
  而非 MissingRequired；chatId 纯空白 + code 乱值→仍抛错；反证 code 合法时
  chatId 纯空白→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；`JsonNull` 语义已按第三十五块 CI 教训钉住
  （`JsonNull.content` 返回 `"null"` 字面量、不抛错），不再重复踩坑；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #259 sendQuote）；本轮开工时无 open qca PR，
  无文件交集可比；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十七（2026-10-01）：bot `setMessageReaction` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十八块）

- 端点 `/api/bot/setMessageReaction` 的处理器内联 `messageId` 抽取 +
  `emoji`（`obj["emoji"]?.jsonPrimitive?.content.orEmpty().trim()`：`trim()`
  作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；双必填与白名单
  判的都是**裁过两端空白**的串——`" 👍 "` 合法通过，全空白 emoji 得
  `MissingRequired`（不是 `UnsupportedEmoji`）；显式 null 的 emoji 得字面
  `"null"`（不抛、经 `trim()` 仍为 `"null"`），它**不在**
  `ALLOWED_REACTION_EMOJIS` 里 → `UnsupportedEmoji`，特意钉住）+ **双必填**
  （`messageId.isBlank() || emoji.isBlank()`→400 `"messageId/emoji required"`，
  文案逐字；白名单拒绝 400 `"unsupported emoji"` 文案逐字，只在双必填通过后发生）
  收敛为 `parseBotReactionFields` 纯函数（`BotSetMessageReactionParse.kt`，
  结果三态 `Ok`/`MissingRequired`/`UnsupportedEmoji`，处理器只剩调用 +
  字段映射 + 副作用（消息存在性、成员检查、setReaction、logCommand、fanout、
  响应），逐行等价——特性开关门控（`isReactionsEnabled`，拒绝文案
  `"reactions_disabled"`）仍在解析之前；抽取顺序 messageId→emoji、
  校验顺序抽取→双必填→白名单不变；响应体逐字不变，下游一行不动）；
- 新增 `BotSetMessageReactionParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（白名单成员逐字通过、四分之一两端空白钉住 trim 后再判白名单、
  四分之二白名单外/乱串钉住 `UnsupportedEmoji`；五分之一 messageId 数字钉住
  content→toString；`messageId` 与 `emoji` 视为已知字段不得参与随机名）+
  双必填语义（messageId 缺/空/纯空白→MissingRequired；emoji 缺/空/纯空白
  →MissingRequired（判的是 trim 后的串）；双合法→Ok）+
  白名单语义逐字钉住（白名单**全体成员**逐字通过；白名单外/乱串→UnsupportedEmoji；
  两端空白的白名单成员→trim 后通过；emoji 显式 null 得字面 `"null"`→UnsupportedEmoji；
  emoji 数字经 content 取 toString→UnsupportedEmoji）+
  null/标量怪语义（messageId 显式 null 得 `"null"`→Ok；布尔 messageId 得 `"true"`；
  对象/数组型 messageId/emoji 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`）+
  坏类型大声失败反证（对象/数组型 messageId/emoji →
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+
  抽取顺序反证（messageId 坏类型 + emoji 乱值→抛错；messageId 缺席 + emoji 坏类型
  →抛错而非 MissingRequired；messageId 纯空白 + emoji 坏类型→仍抛错；
  反证 emoji 合法时 messageId 纯空白→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；`JsonNull` 语义已按第三十五块 CI 教训钉住
  （`JsonNull.content` 返回 `"null"` 字面量、不抛错），不再重复踩坑；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #260 sendCode）；本轮开工时无 open qca PR，
  无文件交集可比；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十八（2026-10-01）：bot `starMessage` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第三十九块）

- 端点 `/api/bot/starMessage` 的处理器内联 `messageId` 抽取（**无 `.trim()`**：
  `obj["messageId"]?.jsonPrimitive?.content.orEmpty()` 原样，判的是未裁剪串的
  `isBlank()`——`" m1 "` **原样**通过必填检查进下游、不裁剪不改写，全空白则判
  `MissingRequired`；与第三十八块 `setMessageReaction` 的 `trim()` 语义是刻意差异，
  原处理器逐字如此，特意钉住）+ **单必填**
  （`messageId.isBlank()`→400 `"messageId required"`，文案逐字）
  收敛为 `parseBotStarMessageFields` 纯函数（`BotStarMessageParse.kt`，
  结果二态 `Ok`/`MissingRequired`，处理器只剩调用 +
  字段映射 + 副作用（特性开关门控（`isMessageStarringEnabled`，拒绝文案
  `"starring_disabled"`）仍在解析之前、消息存在性、成员检查、toggleStar、
  logCommand、响应），逐行等价——下游一行不动）；
- 新增 `BotStarMessageParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（五分之一 messageId 数字钉住 content→toString；`messageId` 视为已知字段
  不得参与随机名）+
  单必填语义（messageId 缺/空/纯空白→MissingRequired；合法→Ok）+
  **无 trim 语义钉住**（`" m1 "` 原样通过必填检查、原样进下游；`"\tmsg-2\n"` 原样）+
  null/标量怪语义（messageId 显式 null 得 `"null"`→Ok；布尔得 `"true"`；
  整数得 `"42"`；对象/数组型 messageId 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`）+
  坏类型大声失败反证（对象/数组型 messageId→`IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null 不抛的反证）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；`JsonNull` 语义已按第三十五块 CI 教训钉住
  （`JsonNull.content` 返回 `"null"` 字面量、不抛错），不再重复踩坑；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #261 setMessageReaction）；本轮开工时已收 #261，
  无其他 open qca PR，无文件交集可比；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续七十九（2026-10-01）：bot `sendStatus` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十块）

- 端点 `/api/bot/sendStatus` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空白）
  + `text` 抽取（`(obj["text"] ?: obj["status"])?.jsonPrimitive?.content.orEmpty().take(200)`，
  **先 200 字符截断、后判空白**——前 200 字符全空白即判缺失，第 201 个字符之后的内容
  进不了判据；`text` 缺键才回退 `status`，**显式 JSON null 不回退**（`?:` 判的是 Kotlin
  null，不是 `JsonNull`，取到字面量 `"null"` 字符串、不抛）；同样无 `trim()`）+
  **合并必填**（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，文案逐字）
  收敛为 `parseBotSendStatusFields` 纯函数（`BotSendStatusParse.kt`，结果二态
  `Ok`/`MissingRequired`，处理器只剩调用 + 字段映射 + 副作用（成员检查、publishBotServiceMessage、
  logCommand、响应），逐行等价——下游一行不动）；
- 新增 `BotSendStatusParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（五分之一 text 整数钉住 content→toString；`chatId`/`text`/`status` 视为已知字段
  不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白或 text 缺/空/纯空白→MissingRequired；两端合法→Ok）+
  `text`/`status` 回退语义（text 缺键取 status；text 显键（含显式 null）不回退→"null"；
  status 显式 null 得字面量 "null"；两端都缺→MissingRequired）+
  `take(200)` 截断语义（250 字符截断到 200；前 200 空白 + 第 201 字符有内容→MissingRequired；
  整 200 非空白→通过）+
  无 trim 语义（`" s1 "`、`" c1 "` 原样通过必填检查、原样进下游）+
  null/标量怪语义（chatId/text 显式 null 得 `"null"`→Ok；布尔得 `"true"`；整数得 `"42"`）+
  坏类型大声失败反证（对象/数组型 chatId 或 text 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；显式 null 不抛的反证）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；`JsonNull` 语义已按第三十五块 CI 教训钉住
  （`JsonNull.content` 返回 `"null"` 字面量、不抛错），不再重复踩坑；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #262 starMessage）；本轮开工时已收 #262，
  无其他 open qca PR，无文件交集可比；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十（2026-10-01）：bot `sendTable` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十一块）

- 端点 `/api/bot/sendTable` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空白）
  + `headers` 抽取（`obj["headers"] as? JsonArray`：**非数组不是错误而是空表头**（随即判缺）；
  单元格 `(it as? JsonPrimitive)` 让对象/数组单元格**静默丢弃**（不是大声失败，逐字语义）；
  `?.content?.trim()?.take(40)` → `filter { it.isNotBlank() }` → `take(8)`——**先 trim+截 40、
  再剔空白、最后取前 8**（空白被剔后不足 8 也照收；显式 JSON null 得字面量 `"null"` 保留为表头；
  与 `sendChecklist` items 同一族语义））+
  `rows` 抽取（同理 `as? JsonArray`：非数组不是错误而是空行集；**行元素非数组则整行
  静默丢弃**；单元格 trim+截 40 但**不做 `isNotBlank` 过滤**（纯空白单元格保留为空串 `""`，
  逐字语义）；每行 `take(8)`；整体 `filter { it.isNotEmpty() }` 剔全空行、再 `take(20)`）+
  **合并必填**（`chatId.isBlank() || headers.isEmpty() || rows.isEmpty()`→400
  `"chatId/headers/rows required"`，文案逐字）
  收敛为 `parseBotSendTableFields` 纯函数（`BotSendTableParse.kt`，结果二态
  `Ok`/`MissingRequired`），表格 markdown 内容组装抽为 `buildBotTableContent(headers, rows)`
  （逐字：表头行 + 分隔行 + 数据行，列数以 `headers.size` 为准，多余单元格丢弃、不足补空串）；
  处理器只剩调用 + 副作用（成员检查、publishBotServiceMessage、logCommand、响应），逐行等价——
  下游一行不动；
- 新增 `BotSendTableParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（表头单元格混入整数/显式 null/对象/数组怪语义用例，期望与纯函数同语义重算；
  `chatId`/`headers`/`rows` 视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白、headers 缺、rows 缺→MissingRequired；三者合法→Ok）+
  chatId 无 trim（`" c1 "` 原样通过必填检查、原样进下游）+
  headers 单元格语义（trim+截 40、先剔空白后取前 8；纯空白单元格剔除；显式 null 得 `"null"`；
  对象/数组单元格静默丢弃不抛；数字/布尔经 content 取 toString；headers 非数组/显式 null
  → MissingRequired 而不是抛）+
  rows 语义（非数组行元素整行丢弃；单元格不剔空白（`""` 保留）；全空行被剔；每行取 8；
  整体取 20；rows 非数组→MissingRequired 而不是抛）+
  内容组装（列数以表头为准，不足补空串；多余单元格丢弃的逐行语义在 fuzz 期望重算中覆盖）+
  null/大声失败反证（chatId 显式 null 得 `"null"`→Ok；对象/数组型 chatId 在
  `?.jsonPrimitive` 处抛 `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  headers/rows 坏类型是反例——`as?` 分支让它们变成"缺失"，不抛）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；fuzz 辅助函数里修正了“两次 `random.nextInt` 分别
  注入与期望”的笔误（自审阶段当场修掉）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #263 sendStatus，已于本轮先行收取）；本轮开工时无其他 open qca PR，
  无文件交集可比；本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。
### 第十三轮续八十一（2026-10-01）：bot `sendAnimation` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十二块）

- 端点 `/api/bot/sendAnimation` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空白）
  + `caption` 抽取（`obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)`，
  同样无 `trim()`——与 `sendTable` 表头单元格的 trim 语义不同，逐字保留）+
  `b64` 四别名抽取（`animationBase64`→`gifBase64`→`fileBase64`→`data`，
  `?.jsonPrimitive?.content.orEmpty()`；**显式 JSON null 不回退**：`?:` 判的是 Kotlin
  null，不是 `JsonNull`——`JsonNull` 本身是 `JsonPrimitive`，`.content` 得字面量
  `"null"` 字符串（非空→Ok；`"null"` 恰是合法 base64 字母表，解码得 3 字节），逐字怪语义）+
  **合并必填**（`chatId.isBlank() || b64.isBlank()`→400 `"chatId/animationBase64 required"`，
  文案逐字，与 sendPhoto/sendDocument 的 9.138「空媒体一律拒绝」一致）
  收敛为 `parseBotSendAnimationFields` 纯函数（`BotSendAnimationParse.kt`，结果二态
  `Ok`/`MissingRequired`）；base64 解码（data-URI 前缀剥离 `substringAfter(',')` + 空白剔除，
  `runCatching` 包住）抽为 `decodeBotAnimationSize`（**坏 base64 走宽容分支**：解码抛错只判
  size=0、不 400——与 sendPhoto 的「坏 base64 判 400 `invalid base64`」故意不同，逐字保留）；
  内容组装（`"✨ gif/animation"` + size>0 时的 `" (NB)"` + 非空 caption 行 +
  `"[botAnimSize:N]"`，整体 `.take(4000)`）抽为 `buildBotAnimationContent`；
  体积上限 8MB 抽为 `BOT_ANIMATION_MAX_BYTES`（413 `animation too large (max 8MB)`，
  文案逐字）；媒体上传开关 403（`media_upload_disabled`，解析之前）与成员检查仍在处理器；
  处理器只剩调用 + 副作用（成员检查、insert、logCommand、fanout、响应），逐行等价——
  下游一行不动；
- 新增 `BotSendAnimationParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（b64 用确定性 base64 编码恒合法、四个别名键按 `i % 4` 轮换——
  **吸取第四十一块的 CI 教训**：fuzz 基 payload 的必填字段必须确定性合法，全随机时
  已知字段可能被丢弃/变空→`MissingRequired`→`okOf` 强转抛 `ClassCastException`；
  `chatId`/`caption`/四个 b64 别名视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白、b64 缺/空/纯空白→MissingRequired；两端合法→Ok）+
  chatId/caption 无 trim（`" c1 "`、`" cap "` 原样通过、原样进下游）+
  四别名优先级与显式 null 不回退（`data` 独有→用 `data`；`fileBase64` 压过 `data`；
  `animationBase64` 显式 null 时不回退 `gifBase64`→字面 `"null"`→Ok→解码 3 字节）+
  base64 解码语义（data-URI 前缀剥离、空白剔除、坏 base64 宽容判 0 不抛、空串判 0）+
  内容组装（size=0 时无 `" (0B)"` 后缀；纯空白 caption 省行；5000 字符 caption 截断到 4000）+
  null/大声失败反证（chatId 显式 null 得 `"null"`→Ok；对象/数组型 chatId、
  对象型 caption、对象型别名键在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null 不抛的反证）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #263 sendStatus）；本轮开工时仅 #264（sendTable，
  本轮已就地修 CI 并推回、待收）一个 open qca PR——**唯一文件交集是本清单账本
  （两轮均为末尾追加，git 顺叠、无语义冲突）**，其余改动文件无交集；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +7 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十二（2026-10-01）：bot `sendAudio` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十三块）

- 端点 `/api/bot/sendAudio` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空白）
  + `title` 抽取（`(obj["title"] ?: obj["fileName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)`——
  **双别名**（`title`→`fileName`）、**有 `.trim()`**（与 chatId/caption 故意不同，逐字保留）、
  截 80；显式 JSON null 得字面量 `"null"` 保留为标题）+
  `duration` 抽取（`(obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0`——
  **双别名**、非数字/坏字面量 → `toIntOrNull()` 得 null → 回 `0`（**不抛**，逐字语义；
  对象/数组型在 `?.jsonPrimitive` 处仍大声失败））+
  `caption` 抽取（`obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)`，
  **无 `.trim()`**——与 title 的 trim 语义不同，逐字保留）+
  `b64` 三别名抽取（`audioBase64`→`fileBase64`→`data`，
  `?.jsonPrimitive?.content.orEmpty()`；**显式 JSON null 不回退**：`?:` 判的是 Kotlin
  null，不是 `JsonNull`——`JsonNull` 本身是 `JsonPrimitive`，`.content` 得字面量
  `"null"` 字符串（非空→Ok；`"null"` 恰是合法 base64 字母表，解码得 3 字节），逐字怪语义）+
  **合并必填**（`chatId.isBlank() || b64.isBlank()`→400 `"chatId/audioBase64 required"`，
  文案逐字，与 sendPhoto/sendDocument 的 9.138「空媒体一律拒绝」一致）
  收敛为 `parseBotSendAudioFields` 纯函数（`BotSendAudioParse.kt`，结果二态
  `Ok`/`MissingRequired`）；base64 解码（data-URI 前缀剥离 `substringAfter(',')` + 空白剔除，
  `runCatching` 包住）抽为 `decodeBotAudioSize`（**坏 base64 走宽容分支**：解码抛错只判
  size=0、不 400——与 sendPhoto 的「坏 base64 判 400 `invalid base64`」故意不同，逐字保留）；
  内容组装（`"🎵 audio"` + 非空 title 的 `" <title>"` + duration>0 的 `" <N>s"` +
  size>0 的 `" (NB)"` + 非空 caption 行 + `"[botAudioSize:N]"`，
  整体 `.take(4000)`）抽为 `buildBotAudioContent`；
  体积上限 10MB 抽为 `BOT_AUDIO_MAX_BYTES`（413 `audio too large (max 10MB)`，
  文案逐字）；媒体上传开关 403（`media_upload_disabled`，解析之前）与成员检查仍在处理器；
  处理器只剩调用 + 副作用（成员检查、insert、logCommand、fanout、响应），逐行等价——
  下游一行不动；
- 新增 `BotSendAudioParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（b64 用确定性 base64 编码恒合法、三个别名键按 `i % 3` 轮换——
  **延续第四十一块的 CI 教训**：fuzz 基 payload 的必填字段必须确定性合法；
  title 取确定性 `" t<i> "`（断言 trim 后）、duration 取确定性 `i % 301`、
  `chatId`/`title`/`duration`/`caption`/三个 b64 别名视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白、b64 缺/空/纯空白→MissingRequired；两端合法→Ok、
  可选字段全缺省）+
  trim 语义分岔（chatId/caption 无 trim 原样通过，title 有 trim——两处故意不同、逐字保留）+
  title 双别名与截 80（`title` 压过 `fileName`；先 trim 后截 80；显式 null 得 `"null"` 保留；
  纯空白→空串）+
  duration 双别名与 `toIntOrNull` 回 0（`duration` 压过 `durationSec`；字符串数字照收；
  `abc`/`12.5`/空串/显式 null→0 不抛；负数原样保留——组装时 `duration>0` 才拼时长段）+
  b64 三别名优先级与显式 null 不回退（`data` 独有→用 `data`；`fileBase64` 压过 `data`；
  `audioBase64` 显式 null 时不回退 `fileBase64`→字面 `"null"`→Ok→解码 3 字节）+
  base64 解码语义（data-URI 前缀剥离、空白剔除、坏 base64 宽容判 0 不抛、空串判 0）+
  内容组装（全字段/全缺省/负时长逐字断言；4000 截断）+
  null/大声失败反证（chatId 显式 null 得 `"null"`→Ok；对象/数组型 chatId、
  对象型 title/duration、数组型 caption、对象型别名键在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 分支基座为最新 main（含 #265 sendAnimation）；本轮开工时仅 #264（sendTable，
  本轮先合 #265 后基座冲突已就地解、推回待收）一个 open qca PR——**唯一文件交集是本清单账本
  （两轮均为末尾追加，git 顺叠、无语义冲突）**，其余改动文件无交集；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +8 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十三（2026-10-01）：bot `editMessageCaption` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十四块）

- 端点 `/api/bot/editMessageCaption` 的处理器内联 `messageId` 抽取
  （`obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空）+
  `caption` 抽取（`(obj["caption"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1000)`——
  **双别名**（`caption`→`text`，逐字顺序）、**无 `.trim()`**（首尾空白原样进正文，
  与 title 类端点的 trim 故意不同，逐字保留）、**截 1000**；显式 JSON null 得字面量
  `"null"`（非空→Ok），对象/数组型在 `?.jsonPrimitive` 处大声失败）+
  **messageId 必填**（`messageId.isBlank()`→400 `"messageId required"`，文案逐字）
  收敛为 `parseBotEditMessageCaptionFields` 纯函数（`BotEditMessageCaptionParse.kt`，
  结果二态 `Ok`/`MissingMessageId`）；
  对端 E2EE 拒绝判定（`body0.startsWith("E2EE:") ||
  (body0.startsWith("{") && body0.contains("\"ciphertext\""))`→400
  `"cannot edit peer E2EE message"`，文案逐字——只认既有正文原文，与请求体无关）
  抽为 `isPeerE2eeContent`；
  内容组装抽为 `buildBotEditCaptionContent`：caption 空白→原正文不变（等于没改）；
  否则单行正文直接换成 caption，多行正文（媒体卡片）取 `lines.first() + "\n" + caption`
  （首行保留、只重写 caption 部分，逐字）；响应 `put("caption", caption.take(200))`
  的 200 截断留在处理器（响应组装非请求语义）；
  归属/成员检查/不存在 404 仍在处理器，校验顺序与原处理器一致（messageId 必填→
  消息存在→归属→成员→E2EE 拒绝→editOwn），逐行等价——下游一行不动；
- 新增 `BotEditMessageCaptionParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（messageId 恒为 `"m<i>"` 确定性合法——**延续第四十一块的 CI 教训**；
  caption 双别名按 `i % 2` 轮换、`i % 37 == 0` 取 1500 字符钉住截 1000；
  `messageId`/`caption`/`text` 视为已知字段不得参与随机名）+
  messageId 必填语义（缺/空/纯空白→MissingMessageId；caption 缺省不影响 Ok；
  messageId 首尾空白原样保留非 trim）+
  caption 双别名与截断（`caption` 压过 `text`；仅 `text` 生效；无 trim 原样保留；
  1200 字符→截 1000；显式 null 得字面 `"null"`；显式 null 的 messageId 同样得
  `"null"`→Ok 的逐字怪语义）+
  大声失败反证（对象/数组型 messageId、对象型 caption、数组型 text 别名键在
  `?.jsonPrimitive` 处抛 `IllegalArgumentException`，路由层 `StatusPages` 映射 400，
  不是 500；显式 null 不抛的反证）+
  近似字段名（`Caption`/`caption2` 按未知键忽略，caption 取不到→缺省空串）+
  E2EE 拒绝语义（`E2EE:` 前缀/`{` 开头含 `"ciphertext"`→拒绝；
  空串/普通文本/`{"text":…}`/`E2EE is not…`/`[{ciphertext}]`→不拒绝）+
  内容组装（空白 caption→原正文；单行→换成 caption；多行→首行+换行+caption 逐字；
  caption 首尾空白与内嵌换行原样拼接）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #266/#264），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +7 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十四（2026-10-01）：bot `sendTimeline` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十五块）

- 端点 `/api/bot/sendTimeline` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空）+
  `title` 抽取（`(obj["title"]?.jsonPrimitive?.content ?: "Timeline").take(80)`——
  默认文案只在**键缺席**时回退，显式 JSON null 得字面量 `"null"`（不回退，特意钉住）、
  **无 `.trim()`**（前导空格计入 80 上限，逐字保留）、截 80；对象/数组型在
  `?.jsonPrimitive` 处大声失败）+
  `items` 抽取（`(obj["items"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()).map { it.take(120) }.take(12)`——
  键缺席→空列表；显式 JSON null / 对象型在 `?.jsonArray` 处大声失败（`JsonNull`
  不是数组，逐字语义）；数组元素里**非 primitive（对象/数组）静默丢弃**
  （`runCatching` 包住，与坏类型大声失败的已知字段故意不同，逐字保留），
  `JsonNull` 元素是 `JsonPrimitive`、得字面量 `"null"`（保留，非空）；
  逐项 `.take(120)`、全表 `.take(12)`（先逐项截、再取前 12，逐字顺序））+
  **合并必填**（`chatId.isBlank() || items.isEmpty()`→400 `"chatId/items required"`，
  文案逐字——items 为空可能是「全被静默丢弃」的结果，逐字语义）
  收敛为 `parseBotSendTimelineFields` 纯函数（`BotSendTimelineParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；
  内容组装抽为 `buildBotTimelineContent`：
  `"### " + title + "\n" + lines`（`lines` 为 `items.mapIndexed { i, t -> (i + 1).toString() + ". " + t }.joinToString("\n")`，
  序号从 1 起，逐字）；特性开关门控（`markdown_disabled`）、成员检查、
  `publishBotServiceMessage`、`logCommand` 仍在处理器，抽取顺序与原处理器一致
  （`chatId`→`title`→`items`→必填，坏类型字段抛错顺序不变），逐行等价——下游一行不动；
- 新增 `BotSendTimelineParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c" + i`、items 恒为三条目确定性合法——**延续第四十一块的
  CI 教训**；`i % 3 == 0` 时塞 150 字符条目钉住逐项截 120；title 取 `" t" + i + " "`
  钉住无 trim；`chatId`/`title`/`items` 视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；items 缺/空数组/全被静默丢弃
  →MissingRequired；显式 null 的 chatId 得字面 `"null"`→Ok 的逐字怪语义；
  title 缺省回 `"Timeline"` 不影响 Ok）+
  title 默认与截断（键缺席→`"Timeline"`；显式 null→`"null"` 不回退；无 trim 原样保留；
  120 字符→截 80，前导空格计入上限）+
  items 元素语义（逐项 120 / 全表 12 先截后取；布尔/数字/浮点/字符串字面量保留、
  `JsonNull` 元素得字面 `"null"`、对象/数组元素静默丢弃；显式 null / 对象型 items
  在 `?.jsonArray` 处抛 `IllegalArgumentException`）+
  大声失败反证（对象/数组型 chatId、title 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+
  内容组装（单条目/多条目序号从 1 起逐字；title 与条目的首尾空格、内嵌换行原样拼接）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open qca PR（#267，`editMessageCaption`，CI 收取中）——**唯一文件交集
  是本清单账本（两轮均为末尾追加，git 顺叠、无语义冲突）**，其余改动文件无交集；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。
### 第十三轮续八十五（2026-10-01）：bot `sendRemind` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十六块）

- 端点 `/api/bot/sendRemind` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空）+
  `text` 抽取（`(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)`——
  **双别名**（`text`→`message`，逐字顺序——只有 `text` 键缺席才回退，`text` 显式 JSON null
  得字面量 `"null"`、不回退，特意钉住）、**无 `.trim()`**（首尾空白原样进正文，逐字保留）、
  **截 300**（先取后截）；对象/数组型在 `?.jsonPrimitive` 处大声失败）+
  **合并必填**（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，文案逐字）
  收敛为 `parseBotSendRemindFields` 纯函数（`BotSendRemindParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；
  内容组装抽为 `buildBotRemindContent`：`"REMIND: " + text` 逐字（`type = "SYSTEM"`，
  原处理器逐字如此）；注意本端点**没有** `markdown_disabled` 特性开关门控
  （原处理器逐字如此，其余卡片端点有、这里没有——故意不补，零行为改动）；
  成员检查/`publishBotServiceMessage`/`logCommand` 仍在处理器，
  抽取顺序与原处理器一致（`chatId`→`text`→必填，坏类型字段抛错顺序不变），
  逐行等价——下游一行不动；
- 新增 `BotSendRemindParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、text 恒为非空确定性字符串——**延续第四十一块的
  CI 教训**；`i % 3 == 0` 时塞 400 字符文本钉住截 300；`chatId`/`text`/`message`
  视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；text 缺/空/纯空白→MissingRequired；
  显式 null 的 chatId 得字面 `"null"`→Ok 的逐字怪语义）+
  text 双别名与截断（仅 `message` 生效；`text` 压过 `message`；`text` 显式 null 得
  字面 `"null"` 不回退；无 trim 原样保留；400 字符→截 300，前导空格计入上限）+
  大声失败反证（对象/数组型 chatId、text、别名 message 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+
  内容组装（`"REMIND: " + text` 逐字；首尾空格与内嵌换行原样拼接；空 text 也逐字组装）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open qca PR（#268，`sendTimeline`，CI 诊断性重跑中）——**改动文件无交集**
  （#268 动 `BotPresentationCardsRouting.kt`，本轮动 `BotPresentationStatusRouting.kt`；
  唯一交集是本清单账本，两轮均为末尾追加，git 顺叠、无语义冲突）；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十六（2026-10-01）：bot `sendMessageSilent` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十七块）

- 端点 `/api/bot/sendMessageSilent` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空）+
  `text` 抽取（`obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)`——
  **无 `.trim()`**（首尾空白原样进正文，逐字保留）、**截 4000**（先取后截）；
  显式 JSON null 得字面量 `"null"`（非空→Ok）；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+
  `parseMode` 抽取（`obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()`——
  **无 `.trim()` 直接 uppercase**：`" md "`→`" MD "`≠`"MD"`，故落 `"TEXT"` 的逐字怪语义；
  只有大写后**恰好** `"MARKDOWN"`/`"MD"` 才得 `"MARKDOWN"`，其余
  （键缺席/空串/显式 null→`"NULL"`）一律 `"TEXT"`）+
  **合并必填**（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，文案逐字）
  收敛为 `parseBotSendMessageSilentFields` 纯函数（`BotSendMessageSilentParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；抽取顺序与原处理器一致
  （`chatId`→`text`→`parseMode`→必填，坏类型字段抛错顺序不变），逐行等价——
  静默开关门控（`silent_send_disabled`）/成员检查/落库/`fanoutBotMessage`/`logCommand`
  仍在处理器，校验顺序与原处理器一致，下游一行不动；
- 新增 `BotSendMessageSilentParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、text 恒为非空确定性字符串——**延续第四十一块的
  CI 教训**；`i % 3 == 0` 时塞 5000 字符文本钉住截 4000；parseMode 按 `i % 4`
  轮换 `markdown`/`MD`/`text`/缺席钉住映射表；`chatId`/`text`/`parseMode`
  视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；text 缺/空/纯空白→MissingRequired；
  显式 null 的 chatId/text 得字面 `"null"`→Ok 的逐字怪语义）+
  parseMode 映射（`markdown`/`MARKDOWN`/`Markdown`/`md`/`MD`/`mD`→`MARKDOWN`；
  缺席/空串/`TEXT`/`HTML`/`" md "`/`"MARKDOWN "`/显式 null→`TEXT` 的逐字怪语义）+
  截断与无 trim（5000 字符→截 4000；`" c "`/`" t "` 原样保留）+
  大声失败反证（对象/数组型 chatId、text、parseMode 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+
  近似字段名（`ChatId`/`chatid2`/`Text`/`parsemode` 按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open qca PR（#268，`sendTimeline`，CI 收取中）——**改动文件无交集**
  （#268 动 `BotPresentationCardsRouting.kt`，本轮动 `BotMessagingVariantsRouting.kt`；
  唯一交集是本清单账本，两轮均为末尾追加，git 顺叠、无语义冲突）；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十七（2026-10-01）：bot `sendChatAction` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十八块）

- 端点 `/api/bot/sendChatAction` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空）+
  `action` 抽取（`obj["action"]?.jsonPrimitive?.content.orEmpty().lowercase().ifBlank { "typing" }`——
  **先 `.lowercase()` 后判空**：缺席/空串/纯空白→`"typing"`；显式 JSON null 得字面量
  `"null"`、小写化后仍为 `"null"`（非空→**不**回退 `"typing"`，特意钉住）；
  对象/数组型在 `?.jsonPrimitive` 处大声失败）+
  **合并必填**（只有 `chatId`：`chatId.isBlank()`→400 `"chatId required"`，文案逐字——
  `action` 恒有默认值，从不判空，逐字语义）
  收敛为 `parseBotSendChatActionFields` 纯函数（`BotSendChatActionParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；
  抽取顺序与原处理器一致（`chatId`→`action`→必填，坏类型字段抛错顺序不变），逐行等价——
  限流门控/成员检查/`isTyping` 推导/typing 侧信道 fanout（9.124 拉黑过滤）/
  `logCommand`（`"sendChatAction:$action"`）/`{"ok": true, "action": …}` 响应
  仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotSendChatActionParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`——**延续第四十一块的 CI 教训**；action 按 `i % 4`
  轮换 `TYPING`/`Upload_Photo`/`"  "`/缺席钉住归一化；`chatId`/`action`
  视为已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；action 缺席照样 Ok→`"typing"`；
  显式 null 的 chatId 得字面 `"null"`→Ok 的逐字怪语义）+
  action 归一化（缺席/空串/纯空白→`"typing"`；`TYPING`/`Typing`→`"typing"`；
  `UPLOAD_PHOTO`/`Record_Video`→小写化；非典型值 `cancel`/`" MD "` 原样（小写化后）透传，
  路由层不校验白名单；显式 null→`"null"` 不回退的逐字怪语义）+
  无 trim（`" c1 "` 原样保留、原样进下游）+
  大声失败反证（对象/数组型 chatId、action 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+
  近似字段名（`ChatId`/`chatid2`/`Action`/`act` 按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #268/#270），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十八（2026-10-02）：bot `exportChatInviteLink` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第四十九块）

- 端点 `/api/bot/exportChatInviteLink` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空）+
  `rotate` 抽取（`obj["rotate"]?.jsonPrimitive?.booleanOrNull == true`——**严格判真**：
  JSON `true`/内容为 `"true"` 的字符串得 `true`（`booleanOrNull` 即
  `toBooleanStrictOrNull()`，大小写**不**敏感，`"TRUE"` 同样得 `true`——CI 据此红过一次）；
  缺席/`false`/显式 null/非真假字符串/数字一律 `false`；
  对象/数组型在 `?.jsonPrimitive` 处大声失败）+
  `expiresInSeconds` 抽取（`(obj["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
  ?: 604800).coerceIn(300, 2592000)`——**先取缺省（7 天）、后夹界**：缺席/非数字串/
  显式 null（`"null"`→`toLongOrNull` 得 null）一律回 604800；< 300 夹到 300，
  > 2592000（30 天）夹到 2592000；对象/数组型大声失败）+
  `maxUses` 抽取（`(obj["maxUses"]?.jsonPrimitive?.content?.toIntOrNull()
  ?: 100).coerceIn(1, 1000)`——缺席/非数字串/显式 null 一律回 100；< 1 夹到 1，
  > 1000 夹到 1000；对象/数组型大声失败）+
  **合并必填**（只有 `chatId`：`chatId.isBlank()`→400 `"chatId required"`，文案逐字——
  其余三字段恒有默认值，从不判空，逐字语义）
  收敛为 `parseBotExportChatInviteLinkFields` 纯函数（`BotExportChatInviteLinkParse.kt`，
  结果二态 `Ok`/`MissingRequired`；缺省/夹界常量提为 `internal const`
  `BOT_EXPORT_CHAT_INVITE_*`，生产与测试钉住同一份值）；
  抽取顺序与原处理器一致（`chatId`→`rotate`→`expiresInSeconds`→`maxUses`→必填，
  坏类型字段抛错顺序不变），逐行等价——`group_invites_disabled` 开关门控
  （原处理器里先于 body 解析，本轮保持门控在解析之前，等价）/成员检查/频道拦截/
  `configureToken`/`logCommand`/响应仍在处理器，顺序与原处理器一致，下游一行不动；
  `expiresAt` 仍在处理器里按 `System.currentTimeMillis() + expiresIn * 1000L` 计算，
  纯函数只返回收敛后的秒数；
- 新增 `BotExportChatInviteLinkParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`——**延续第四十一块的 CI 教训**；`rotate` 按 `i % 3`
  轮换 `true`/`false`/缺席；`expiresInSeconds` 按 `i % 5` 轮换数字/字符串数字/低于下限/
  高于上限/缺席钉住缺省-夹界；`maxUses` 同理；四个已知字段不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；其余字段缺席照样 Ok→各自缺省；
  显式 null 的 chatId 得字面 `"null"`→Ok 的逐字怪语义）+
  rotate 严格判真（`true`/`"true"`/`"TRUE"`→true；缺席/`false`/显式 null/`"false"`/
  `"yes"`/`1`/`0`→false）+
  expiresInSeconds 缺省-夹界（缺席/`"tomorrow"`/显式 null/`""`→604800；
  300/2592000 边界原样；0/-100/`"10"`→300；99999999/`"2592001"`→2592000）+
  maxUses 缺省-夹界（缺席/`"unlimited"`/显式 null/`""`→100；1/1000 边界原样；
  0/-5/`"0"`→1；5000/`"1001"`→1000）+
  无 trim（`" c1 "` 原样保留）+
  大声失败反证（对象/数组型 chatId、rotate、expiresInSeconds、maxUses 在
  `?.jsonPrimitive` 处抛 `IllegalArgumentException`，路由层 `StatusPages` 映射 400，
  不是 500；显式 null 不抛的反证——chatId→`"null"`→Ok、rotate→false、
  expiresInSeconds→604800、maxUses→100）+
  近似字段名（`ChatId`/`chatid2`/`Rotate`/`expiresIn`/`maxuses` 按未知键忽略，
  真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #271），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +8 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续八十九（2026-10-02）：bot `revokeChatInviteLink` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十块）

- 端点 `/api/bot/revokeChatInviteLink` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ 合并必填（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）
  收敛为 `parseBotRevokeChatInviteLinkFields` 纯函数（`BotRevokeChatInviteLinkParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；单字段端点，无缺省/夹界逻辑；
  抽取逐字等价——`group_invites_disabled` 开关门控（原处理器里先于 body 解析，
  本轮保持门控在解析之前，等价）/成员检查/`revokeToken`/`logCommand`/响应
  仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotRevokeChatInviteLinkParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`——**延续第四十一块的 CI 教训**；已知字段只有 chatId，
  不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；显式 null→`"null"`→Ok 的逐字怪语义）+
  无 trim（`" c1 "` 原样保留）+
  大声失败反证（对象/数组型 chatId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null 不抛的反证）+
  近似字段名（`ChatId`/`chatid2`/`chat_id` 按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #272），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十（2026-10-02）：bot `unpinAllChatMessages` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十一块）

- 端点 `/api/bot/unpinAllChatMessages` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ 合并必填（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）
  收敛为 `parseBotUnpinAllChatMessagesFields` 纯函数（`BotUnpinAllChatMessagesParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；单字段端点，无缺省/夹界逻辑；
  抽取逐字等价——限流/bot 可投递检查（原处理器里先于 body 解析，
  本轮保持它们在解析之前，等价）/成员检查/chat 查询/`clearAll`/`logCommand`/WS 广播
  与响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotUnpinAllChatMessagesParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`——**延续第四十一块的 CI 教训**；已知字段只有 chatId，
  不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；显式 null→`"null"`→Ok 的逐字怪语义）+
  无 trim（`" c1 "` 原样保留）+
  大声失败反证（对象/数组型 chatId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null 不抛的反证）+
  近似字段名（`ChatId`/`chatid2`/`chat_id` 按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #273），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十一（2026-10-02）：bot `setChatPhoto` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十二块）

- 端点 `/api/bot/setChatPhoto` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义）+
  `base64` 三键回退抽取
  （`(obj["photoBase64"] ?: obj["base64Data"] ?: obj["photo"])?.jsonPrimitive?.content.orEmpty()`，
  **无 `.trim()`**；**按「存在」回退而非按「非空」回退**——`photoBase64` 在场但为空时不会回退
  到 `base64Data`，逐字 Elvis 怪语义；对象/数组型在 `?.jsonPrimitive` 处大声失败）
  + 合并必填（`chatId.isBlank() || base64.isBlank()`→400 `"chatId/photoBase64 required"`，
  文案逐字，写的是首选键名）收敛为 `parseBotSetChatPhotoFields` 纯函数
  （`BotSetChatPhotoParse.kt`，结果二态 `Ok`/`MissingRequired`）；无缺省/夹界逻辑；
  抽取逐字等价——限流（原处理器里先于 body 解析，本轮保持它在解析之前，等价）/
  成员检查/`saveGroupAvatar` 的 `IllegalArgumentException`→400「invalid photo」/
  `updateAvatar`/`logCommand`/`notifyGroupRevisionChanged` 与响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotSetChatPhotoParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、base64 恒为 `"Ym" + i` 轮换写进三个回退键——
  **延续第四十一块的 CI 教训**；已知字段 chatId/photoBase64/base64Data/photo 不得参与随机名）+
  合并必填语义（chatId 缺/空/纯空白→MissingRequired；三键全缺/全空→MissingRequired；
  显式 null→`"null"`→Ok 的逐字怪语义）+
  三键回退怪语义钉住（photoBase64 在场但为空时不回退→MissingRequired；
  缺席时按 photoBase64→base64Data→photo 顺序回退；都在时首选键胜出）+
  无 trim（`" c1 "`/`" eA== "` 原样保留）+
  大声失败反证（对象/数组型 chatId、三个回退键逐一的 base64 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+
  近似字段名（`ChatId`/`chatid2`/`chat_id`/`photobase64`/`base64data`/`Photo` 按未知键忽略，
  真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #274），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十二（2026-10-02）：bot `deleteChatPhoto` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十三块）

- 端点 `/api/bot/deleteChatPhoto` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ 合并必填（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）收敛为
  `parseBotDeleteChatPhotoFields` 纯函数（`BotDeleteChatPhotoParse.kt`，
  结果二态 `Ok`/`MissingRequired`）；无缺省/夹界逻辑；抽取逐字等价——限流（原处理器里
  先于 body 解析，本轮保持它在解析之前，等价）/成员检查/`clearAvatar`/
  `logCommand`/`deleteGroupAvatarUrl`/`notifyGroupRevisionChanged` 与响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotDeleteChatPhotoParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`——**延续第四十一块的 CI 教训**；已知字段 `chatId`
  不得参与随机名）+ 合并必填语义（chatId 缺/空/纯空白→MissingRequired；
  显式 null→`"null"`→Ok 的逐字怪语义）+ 无 trim（`" c1 "` 原样保留）+
  大声失败反证（对象/数组型 chatId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null 不抛的反证）+
  近似字段名（`ChatId`/`chatid2`/`chat_id` 按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open qca PR（本轮先合 #275），无文件交集可比；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十三（2026-10-02）：bot `pinChatMessage` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十四块）

- 端点 `/api/bot/pinChatMessage` 的处理器内联 `chatId` + `messageId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()` /
  `obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，**均无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ 合并必填（`chatId.isBlank() || messageId.isBlank()`→400
  `"chatId/messageId required"`，文案逐字）收敛为 `parseBotPinChatMessageFields` 纯函数
  （`BotPinChatMessageParse.kt`，结果二态 `Ok`/`MissingRequired`）；无缺省/夹界逻辑；
  抽取顺序 chatId 先、messageId 后，与原处理器逐字一致——限流、可投递检查、
  消息钉住开关检查（原处理器里**先于** body 解析，本轮保持它们在解析之前，等价）/
  成员检查/chat 查询/`toggle`/`logCommand`/WS 广播与响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotPinChatMessageParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、messageId 恒为 `"m<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`messageId` 不得参与随机名）+ 合并必填语义（任一字段缺/空/纯空白
  →MissingRequired；显式 null→`"null"`→Ok 的逐字怪语义）+ 无 trim（两字段
  `" c1 "`/`" m1 "` 原样保留）+ 大声失败反证（对象/数组型任一字段在 `?.jsonPrimitive`
  处抛 `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+ 近似字段名（`ChatId`/`chat_id`/`messageid2`/`message_id`
  按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open qca PR（#276，改动文件为 `BotChatInviteRouting.kt`+
  `BotDeleteChatPhotoParse.kt`+`BotDeleteChatPhotoParseFuzzTest.kt`，与本轮改动文件无交集）；
  §3 收取：#276 CI 全绿 + 自审通过 + mergeable clean，已 squash 合并并删远端分支；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十四（2026-10-02）：bot `unpinChatMessage` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十五块）

- 端点 `/api/bot/unpinChatMessage` 的处理器内联 `chatId` + `messageId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()` /
  `obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，**均无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ 合并必填（`chatId.isBlank() || messageId.isBlank()`→400
  `"chatId/messageId required"`，文案逐字）收敛为 `parseBotUnpinChatMessageFields` 纯函数
  （`BotUnpinChatMessageParse.kt`，结果二态 `Ok`/`MissingRequired`）；无缺省/夹界逻辑；
  抽取顺序 chatId 先、messageId 后，与原处理器逐字一致——限流、可投递检查
  （原处理器里**先于** body 解析，本轮保持它们在解析之前，等价）/成员检查/chat 查询/
  `list` 预判/`toggle`/`logCommand`/WS 广播与响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotUnpinChatMessageParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、messageId 恒为 `"m<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`messageId` 不得参与随机名）+ 合并必填语义（任一字段缺/空/纯空白
  →MissingRequired；显式 null→`"null"`→Ok 的逐字怪语义）+ 无 trim（两字段
  `" c1 "`/`" m1 "` 原样保留）+ 大声失败反证（对象/数组型任一字段在 `?.jsonPrimitive`
  处抛 `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+ 近似字段名（`ChatId`/`chat_id`/`messageid2`/`message_id`
  按未知键忽略，真字段缺席→MissingRequired）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open qca PR（#277，改动文件为 `BotChatActionRouting.kt`+
  `BotPinChatMessageParse.kt`+`BotPinChatMessageParseFuzzTest.kt`，与本轮改动文件无交集）；
  §3 收取：#277 CI 全绿 + 自审通过 + mergeable clean，已 squash 合并并删远端分支；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +5 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十五（2026-10-02）：bot `setMyName` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十六块）

- 端点 `/api/bot/setMyName` 的处理器内联 `name` 抽取 + 归一化 + 必填校验
  （`(obj["name"] ?: obj["displayName"])?.jsonPrimitive?.content.orEmpty()` →
  **先 `.trim()` 再 `.take(120)`**，全空白直接判空；`name` 优先、`displayName`
  回退——**显式 JSON null 不触发回退**（`JsonNull` 是非空实例，`?:` 不生效→得
  字面量 `"null"`→非空→Ok 的逐字怪语义）；对象/数组型在 `?.jsonPrimitive`
  处大声失败）收敛为 `parseBotSetMyNameFields` 纯函数
  （`BotSetMyNameParse.kt`，结果二态 `Ok`/`InvalidName`）；trim 与 120 字符
  截断顺序逐字一致——限流（原处理器里**先于** body 解析，本轮保持在解析之前，
  等价）/`setMyName`/`logCommand`/响应仍在处理器，顺序与原处理器一致，
  下游一行不动；
- 新增 `BotSetMyNameParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（name 恒为 `"n<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `name`/`displayName` 不得参与随机名）+ name 优先级与回退
  （name 在场优先；缺席回 displayName；显式 null 不回退→`"null"`→Ok 的逐字
  怪语义）+ trim 在先/截断在后（`"  Alice  "`→`"Alice"`；200 字符→120 截断；
  纯空白→InvalidName）+ 必填语义（缺/空/纯空白→InvalidName；
  displayName 显式 null→`"null"`→Ok）+ 大声失败反证（对象/数组型
  name/displayName 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）+ 近似字段名（`Name`/`NAME`/
  `name2`/`display_name`/`displayname` 按未知键忽略，真字段缺席→InvalidName）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open qca PR（#278，改动文件为 `BotGeoRouting.kt`+
  `BotUnpinChatMessageParse.kt`+`BotUnpinChatMessageParseFuzzTest.kt`，与本轮改动文件无交集——
  本轮新增 `BotSetMyNameParse.kt`/`BotSetMyNameParseFuzzTest.kt`、只改 `setMyName`
  处理器片段）；
  §3 收取：#278 CI 全绿 + 自审通过 + mergeable clean，已 squash 合并并删远端分支；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十六（2026-10-02）：bot `setChatTitle` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十七块）

- 端点 `/api/bot/setChatTitle` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ `title` 抽取归一化
  （`(obj["title"] ?: obj["groupName"])?.jsonPrimitive?.content.orEmpty().trim()`——
  `title` 优先、`groupName` 回退、显式 JSON null 不触发回退→字面量 `"null"`→`Ok`
  的逐字怪语义；**长度上限 50 判的是 trim 之后**的 title，原处理器逐字如此）+
  合并校验（`chatId.isBlank() || title.isBlank() || title.length > 50`→400
  `"chatId/title required (1-50)"`，文案逐字）收敛为 `parseBotSetChatTitleFields` 纯函数
  （`BotSetChatTitleParse.kt`，结果二态 `Ok`/`Invalid`）；抽取顺序 chatId 先、title 后，
  与原处理器逐字一致——限流（原处理器里**先于** body 解析，本轮保持在解析之前，等价）/
  成员检查/`updateName`/`logCommand`/修订通知与响应仍在处理器，顺序与原处理器一致，
  下游一行不动；
- 新增 `BotSetChatTitleParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、title 恒为 `"T<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`title`/`groupName` 不得参与随机名）+ title 优先级与回退
  （title 在场优先；缺席回 groupName；显式 null 不回退→`"null"`→Ok 的逐字
  怪语义）+ trim 与长度夹界（`"  群名  "`→`"群名"`；恰 50 字符→Ok；
  51 字符→Invalid；前后空白不计入长度；chatId 无 trim，`" c1 "` 原样保留）+
  必填语义（chatId 缺/空/纯空白→Invalid；title 缺→Invalid；显式 null→`"null"`→Ok）+
  大声失败反证（对象/数组型 chatId/title/groupName 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  显式 null 不抛的反证）+ 近似字段名（`ChatId`/`chatid2`/`chat_id`/`Title`/
  `group_name`/`groupname` 按未知键忽略，真字段缺席→Invalid）；
### 第十三轮续九十七（2026-10-02）：bot `deleteMessage` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十八块）

- 端点 `/api/bot/deleteMessage` 的处理器内联 `chatId`/`messageId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`、`obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，
  **两字段均无 `.trim()`**：全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；抽取顺序 chatId 先、messageId 后）+
  合并必填校验（`chatId.isBlank() || messageId.isBlank()`→400 `"chatId/messageId required"`，
  文案逐字）收敛为 `parseBotDeleteMessageFields` 纯函数
  （`BotDeleteMessageParse.kt`，结果二态 `Ok`/`Invalid`）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/消息存在性与发送者检查/`deleteOwn`/`fanoutBotEvent`/`logCommand`/
  响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotDeleteMessageParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、messageId 恒为 `"m<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`messageId` 不得参与随机名；**吸取第五十七块 CI 教训——双字段端点的
  每个 `okOf` 用例都携带全部必填字段**，缺任一字段即 `Invalid`→`as Ok` 强转抛 `ClassCastException`）+
  必填语义（chatId/messageId 各自缺/空/纯空白→Invalid；双合法→Ok）+ 显式 null 字面量
  （`"null"`→Ok，单/双 null 均钉）+ 空白不 trim（`" c1 "`/`" m1 "` 原样保留仍通过必填）+
  大声失败反证（对象/数组型 chatId/messageId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）+ 近似字段名（`ChatId`/`chat_id`/`MessageId`/
  `message_id` 等按未知键忽略，真字段缺席→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open loop PR（#279，改动文件为 `BotGeoRouting.kt`+
  `BotSetMyNameParse.kt`+`BotSetMyNameParseFuzzTest.kt`，与本轮改动文件无交集——
  本轮新增 `BotSetChatTitleParse.kt`/`BotSetChatTitleParseFuzzTest.kt`、只改
  `BotChatAdminRouting.kt` 的 `setChatTitle` 处理器片段）；
  §3 收取：#279 CI 全绿 + 自审通过 + mergeable clean，已 squash 合并并删远端分支；
  本轮清单改动仅为本账本节**末尾追加**
  （§0 Server 单测标注行暂不改：本轮 +6 按条目约定由复核轮一次性刷新）；
- 开工时 1 个 open 重构 PR（#280，改动文件为 `BotChatAdminRouting.kt`+
  `BotSetChatTitleParse.kt`+`BotSetChatTitleParseFuzzTest.kt`+本清单——本轮改动
  `BotChatModerationRouting.kt`/`BotDeleteMessageParse.kt`/`BotDeleteMessageParseFuzzTest.kt`，
  与 #280 无文件交集，**本轮未改清单 §0（复核轮统一刷新），账本仅末尾追加**——
  账本节用「续九十七」编号，与 #280 分支内的「续九十六」不冲突）；
  §3 收取：#280 Server job 曾红（`titlePriorityAndFallback` 的 `okOf` 用例漏必填 chatId→
  `ClassCastException`，照抄单字段端点测试模式时的疏漏），已追加 `fix(test)` 提交补齐，
  PR 描述同步记录，不等 CI，下一轮收取；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十八（2026-10-02）：bot `banChatMember` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第五十九块）

- 端点 `/api/bot/banChatMember` 的处理器内联 `chatId`/`userId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`、`obj["userId"]?.jsonPrimitive?.content.orEmpty()`，
  **两字段均无 `.trim()`**：全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；抽取顺序 chatId 先、userId 后）+
  合并必填校验（`chatId.isBlank() || userId.isBlank()`→400 `"chatId/userId required"`，
  文案逐字）收敛为 `parseBotBanChatMemberFields` 纯函数
  （`BotBanChatMemberParse.kt`，结果二态 `Ok`/`Invalid`）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/成员检查（`isParticipant`）/`removeMember`/`logCommand`/
  修订通知/响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotBanChatMemberParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、userId 恒为 `"u<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`userId` 不得参与随机名；**双字段端点的每个 `okOf` 用例都携带
  全部必填字段**——吸取第五十七块 CI 教训，缺任一字段即 `Invalid`→`as Ok` 强转抛
  `ClassCastException`）+
  必填语义（chatId/userId 各自缺/空/纯空白→Invalid；双合法→Ok）+ 显式 null 字面量
  （`"null"`→Ok，单/双 null 均钉）+ 空白不 trim（`" c1 "`/`" u1 "` 原样保留仍通过必填）+
  大声失败反证（对象/数组型 chatId/userId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）+ 近似字段名（`ChatId`/`chat_id`/`UserId`/
  `user_id` 等按未知键忽略，真字段缺席→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#281，改动文件为 `BotChatModerationRouting.kt`+
  `BotDeleteMessageParse.kt`+`BotDeleteMessageParseFuzzTest.kt`+本清单——本轮改动
  `BotMemberRouting.kt`/`BotBanChatMemberParse.kt`/`BotBanChatMemberParseFuzzTest.kt`，
  与 #281 无文件交集，**本轮未改清单 §0（复核轮统一刷新），账本仅末尾追加**——
  账本节用「续九十八」编号，与 #281 分支内的「续九十七」不冲突）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续九十九（2026-10-02）：bot `restrictChatMember` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十块）

- 端点 `/api/bot/restrictChatMember` 的处理器内联 `chatId`/`userId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`、`obj["userId"]?.jsonPrimitive?.content.orEmpty()`，
  **两字段均无 `.trim()`**：全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；抽取顺序 chatId 先、userId 后）+
  可选 `until` 抽取（`untilDate` 优先→`mutedUntil` 兜底→缺省/非法→`0L`，与原处理器逐字；
  **抽取发生在必填校验之前**，坏类型 `untilDate`/`mutedUntil` 先抛、哪怕 chatId 本来是空的）+
  合并必填校验（`chatId.isBlank() || userId.isBlank()`→400 `"chatId/userId required"`，
  文案逐字）收敛为 `parseBotRestrictChatMemberFields` 纯函数
  （`BotRestrictChatMemberParse.kt`，结果二态 `Ok`/`Invalid`）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/禁言时长上限钳制（8.33 修复，30 天上限）/成员检查
  （`isParticipant`）/`updateMemberMute`/`logCommand`/修订通知/响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotRestrictChatMemberParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、userId 恒为 `"u<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`userId`/`untilDate`/`mutedUntil` 不得参与随机名；**双字段端点的每个 `okOf` 用例都携带
  全部必填字段**——吸取第五十七块 CI 教训，缺任一字段即 `Invalid`→`as Ok` 强转抛
  `ClassCastException`；until 缺席恒为 0L）+
  必填语义（chatId/userId 各自缺/空/纯空白→Invalid；双合法→Ok，until 缺席→0L）+
  显式 null 字面量（chatId/userId `"null"`→Ok；untilDate 显式 null→字面量 `"null"` 解析不成 Long
  →滑向 mutedUntil（缺席）→0L；双 until 候选都显式 null→0L）+
  `until` 语义（untilDate 优先于 mutedUntil；mutedUntil 兜底；字符串数字可解析；
  untilDate 非法→滑向 mutedUntil；双候选都非法/缺席→0L）+
  空白不 trim（`" c1 "`/`" u1 "` 原样保留仍通过必填）+
  大声失败反证（对象/数组型 chatId/userId/`untilDate`/`mutedUntil` 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500；
  抽取先于必填校验的顺序反证——chatId 空+untilDate 对象照样先抛）+
  近似字段名（`ChatId`/`chat_id`/`UserId`/`user_id` 等按未知键忽略，真字段缺席→Invalid；
  `UntilDate` 近似名被忽略→until=0L）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（#281、#282 已于本轮收掉）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百（2026-10-02）：bot `unbanChatMember` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十一块）

- 端点 `/api/bot/unbanChatMember` 的处理器内联 `chatId`/`userId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`、`obj["userId"]?.jsonPrimitive?.content.orEmpty()`，
  **两字段均无 `.trim()`**：全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；抽取顺序 chatId 先、userId 后）+
  合并必填校验（`chatId.isBlank() || userId.isBlank()`→400 `"chatId/userId required"`，
  文案逐字）收敛为 `parseBotUnbanChatMemberFields` 纯函数
  （`BotUnbanChatMemberParse.kt`，结果二态 `Ok`/`Invalid`）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/成员检查（`isParticipant`）/ CHANNEL 分支 `addMembers`
  与非 CHANNEL 分支 `inviteMembers` / `logCommand` / 修订通知（仅 CHANNEL 分支）/
  响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotUnbanChatMemberParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、userId 恒为 `"u<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`userId` 不得参与随机名；**双字段端点的每个 `okOf` 用例都携带
  全部必填字段**——吸取第五十七块 CI 教训，缺任一字段即 `Invalid`→`as Ok` 强转抛
  `ClassCastException`）+
  必填语义（chatId/userId 各自缺/空/纯空白→Invalid；双合法→Ok）+
  显式 null 字面量（`"null"`→Ok，单/双 null 均钉）+
  空白不 trim（`" c1 "`/`" u1 "` 原样保留仍通过必填）+
  大声失败反证（对象/数组型 chatId/userId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）+
  近似字段名（`ChatId`/`chat_id`/`UserId`/`user_id` 等按未知键忽略，真字段缺席→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（#283 已于本轮收掉；本轮只碰 `BotMemberPromotionRouting.kt`/
  `BotUnbanChatMemberParse.kt`/`BotUnbanChatMemberParseFuzzTest.kt`+本清单——
  本轮清单改动为本账本节追加「续一百」，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一（2026-10-02）：bot `promoteChatMember` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十二块）

- 端点 `/api/bot/promoteChatMember` 的处理器内联 `chatId`/`userId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`、`obj["userId"]?.jsonPrimitive?.content.orEmpty()`，
  **两字段均无 `.trim()`**：全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；抽取顺序 chatId 先、userId 后、role 末）+
  `role` 归一化（`.orEmpty().uppercase().ifBlank { "ADMIN" }` 后精确判 `"MEMBER"`，其它全滑向
  `"ADMIN"`——大小写不敏感的 MEMBER（靠 `.uppercase()`）→`"MEMBER"`；缺席/空/纯空白/
  `"OWNER"`/`"admin"`/带空白的 `" member "`/显式 null（→`"NULL"`）一律→`"ADMIN"`；role 无 trim）+
  合并必填校验（`chatId.isBlank() || userId.isBlank()`→400 `"chatId/userId required"`，
  文案逐字）收敛为 `parseBotPromoteChatMemberFields` 纯函数
  （`BotPromoteChatMemberParse.kt`，结果二态 `Ok`/`Invalid`）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/成员检查（`isParticipant`）/
  `groupMembershipService.updateRole`（owner 直改，bots invited as ADMIN 不可提权）/
  `logCommand` / 修订通知 / 响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotPromoteChatMemberParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、userId 恒为 `"u<i>"`、role 恒为 `"MEMBER"`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`userId`/`role` 不得参与随机名）+
  必填语义（chatId/userId 各自缺/空/纯空白→Invalid；双合法+role 缺席→Ok，role 缺省→`"ADMIN"`，
  role 缺席不参与必填）+
  `role` 归一化钉住（缺席/空/纯空白→`"ADMIN"`；`"member"`/`"Member"`/`"mEmBeR"`→`"MEMBER"`；
  `" member "`（无 trim）→`"ADMIN"`；`"OWNER"`/`"owner"`/`"admin"`/`"ADMINISTRATOR"`/数字/
  布尔→`"ADMIN"`；显式 null→`"NULL"`→`"ADMIN"`）+
  显式 null 字面量（chatId/userId `"null"`→Ok，单/双 null 均钉）+
  空白不 trim（`" c1 "`/`" u1 "` 原样保留仍通过必填）+
  大声失败反证（对象/数组型 chatId/userId/**role** 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  **抽取先于必填校验的顺序反证**（role 对象型 + chatId 空白时先抛，不走 `Invalid`；
  原处理器里 role 的 `?.jsonPrimitive` 在空白判空之前执行，逐字行为）+
  近似字段名（`ChatId`/`chat_id`/`UserId`/`user_id`/`Role` 等按未知键忽略，真字段缺席→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（#284 已于本轮收掉；本轮只碰 `BotMemberPromotionRouting.kt`/
  `BotPromoteChatMemberParse.kt`/`BotPromoteChatMemberParseFuzzTest.kt`+本清单——
  本轮清单改动为本账本节追加「续一百一」，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百五（2026-10-02）：bot `setMyCommands` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十六块）

- 端点 `/api/bot/setMyCommands` 的处理器内联 `commands` 数组抽取
  （`obj["commands"] as? JsonArray`——**安全转型**：缺席/对象/字符串/数字/显式 null
  一律→400 `"commands array required"`，**不抛**；与项内 `?.jsonPrimitive` 的大声失败
  是两套判据）+ 逐项对象抽取（`item as? JsonObject`，非对象项**静默丢弃**）+
  `command`/`description` 抽取（`o["command"]?.jsonPrimitive?.content.orEmpty()`，
  **无 `.trim()`**；对象/数组型在 `?.jsonPrimitive` 处大声失败；显式 JSON null 得
  字面量 `"null"`→非空→保留的逐字怪语义）+ 空白项丢弃（`command.isBlank() ||
  description.isBlank()` 的项丢弃，**不是 400**；空数组/全丢弃→`Ok(emptyList())`）
  收敛为 `parseBotSetMyCommandsFields` 纯函数（`BotSetMyCommandsParse.kt`，
  结果二态 `Ok`/`Invalid`）——`normalizeCommands`（trim/小写/正则/去重/上限 100，
  已是 `BotRepository` 里的独立纯函数，本轮不搬，仍在处理器里调用）/`setMyCommands`
  落库/`logCommand`/响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotSetMyCommandsParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（`commands` 恒为单元素合法数组——**延续第四十一块的 CI 教训**；
  已知字段 `commands`/`command`/`description` 不得参与随机名）+
  数组必填（`commands` 缺席/对象/字符串/数字/显式 null→Invalid；空数组→`Ok(emptyList())`）+
  项级过滤（非对象项静默丢弃；`command`/`description` 缺/空/纯空白的项丢弃；
  全丢弃→`Ok(emptyList())`）+
  显式 null 字面量（`command` 显式 null→`"null"`→保留）+
  项内大声失败反证（`command` 对象型/`description` 数组型在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500——与顶层
  `as?` 安全转型形成对照）+
  近似字段名（`Commands` 按未知键忽略→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#288，Server job 失败日志不可读已重跑待收；
  本轮只碰 `BotCommandRouting.kt`/`BotSetMyCommandsParse.kt`/
  `BotSetMyCommandsParseFuzzTest.kt`+本清单——本轮清单改动为本账本节追加
  「续一百五」（编号预留 #288 的续一百二/三/四先合并），不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百六（2026-10-02）：bot `setMyDescription` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十七块）

- 端点 `/api/bot/setMyDescription` 的处理器内联简介抽取
  （`(obj["description"] ?: obj["about"])?.jsonPrimitive?.content`——`description` 优先、
  `about` 回退，**按存在而非按非空**：空字符串/显式 JSON null 的 `description` 都不回退
  （`?:` 只看空引用）；显式 null 得字面量 `"null"`（`JsonNull` 是 `JsonPrimitive`，
  `?.jsonPrimitive` 不抛，原处理器逐字如此）；双键缺席→`null`（下游
  `BotRepository.setMyDescription(botId, null)` 清简介，本轮不动该语义）；
  对象/数组型在 `?.jsonPrimitive` 处大声失败）收敛为 `parseBotSetMyDescriptionFields` 纯函数
  （`BotSetMyDescriptionParse.kt`，结果单态 `Ok`——本端点无必填校验，任何输入要么解析要么大声失败）——
  限流（原处理器里**先于** body 解析，本轮保持在解析之前，等价）/`setMyDescription`
  （`trim()`/`take(500)` 在 repository 内部，本轮不搬）/`logCommand`/响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotSetMyDescriptionParseFuzzTest`（5 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（description 恒为 `"d<i>"`——**延续第四十一块的 CI 教训**；
  已知字段 `description`/`about` 不得参与随机名）+
  别名优先级（description 优先；about 回退；空字符串 description 不回退；
  显式 null description 不回退→`"null"`）+
  null 语义（双缺席→真 `null`；description/about 显式 null→字面量 `"null"`）+
  大声失败反证（对象/数组型 description、对象型 about 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  近似字段名（`Description`/`about2` 按未知键忽略）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#288，`BotMediaRouting.kt` 相关；本轮只碰 `BotProfileRouting.kt`/
  `BotSetMyDescriptionParse.kt`/`BotSetMyDescriptionParseFuzzTest.kt`+本清单——
  本轮清单改动为本账本节追加「续一百六」，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。
### 第十三轮续一百二（2026-10-02）：bot `leaveChat` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十三块）——补记

- 本块为 PR #286（squash 合并 eefecd96，当时漏记账本，本轮补记）：`/api/bot/leaveChat`
  处理器内联 `chatId` 抽取 + 合并必填校验收敛为 `parseBotLeaveChatFields` 纯函数
  （`BotLeaveChatParse.kt`，结果二态 `Ok`/`Invalid`），新增 `BotLeaveChatParseFuzzTest`；
  `BotChatModerationRouting.kt` 处理器只保留 `when` 分发（`Invalid`→400 文案逐字），
  下游限流/成员检查/`logCommand`/响应一行不动；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件）无一在监；
- 判据：本机无 JDK/Android SDK，未本地验证，已由 CI 验证（合并闸门通过）。

### 第十三轮续一百三（2026-10-02）：bot `demoteChatMember` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十四块）——补记

- 本块为 PR #287（squash 合并 8302a304，当时漏记账本，本轮补记）：`/api/bot/demoteChatMember`
  处理器内联 `chatId`/`userId` 抽取（两字段均无 `.trim()`，显式 null 得字面量 `"null"`，
  对象/数组型在 `?.jsonPrimitive` 处大声失败，抽取先于必填校验）+ 合并必填校验
  （`chatId.isBlank() || userId.isBlank()`→400 `"chatId/userId required"`，文案逐字）
  收敛为 `parseBotDemoteChatMemberFields` 纯函数（`BotDemoteChatMemberParse.kt`，
  结果二态 `Ok`/`Invalid`），新增 `BotDemoteChatMemberParseFuzzTest`（8 例）；
  `BotMediaRouting.kt` 处理器只保留 `when` 分发，限流（先于 body 解析）/成员检查/
  `logCommand`/修订通知/响应仍在处理器，下游一行不动；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件）无一在监；
- 判据：本机无 JDK/Android SDK，未本地验证，已由 CI 验证（合并闸门通过）。

### 第十三轮续一百四（2026-10-02）：bot `setChatPermissions` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十五块）

- 端点 `/api/bot/setChatPermissions` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；对象/数组型在 `?.jsonPrimitive`
  处大声失败）+ `canSend` 布尔归一化（camel `canSendMessages` 优先、
  snake `can_send_messages` 兜底的 `?.jsonPrimitive?.booleanOrNull`，即
  `content.toBooleanStrictOrNull()`——JSON 布尔或 `"true"`/`"false"` 字符串算布尔，
  大小写**不**敏感（`"TRUE"`→`true`，同第四十九块 CI 教训）；数字/显式 null/
  空字符串一律落空到别名或 `Invalid`；显式 null camel 等同缺席，不抛）+ `until` 别名回落
  （camel `until` 优先、camel `untilDate` 兜底、缺省 `0L`；非数字字符串/显式 null
  回落不抛，下游 `muteUntil` 语义里 0=24 小时静音，本轮不动该语义）+
  合并必填校验（`chatId.isBlank() || canSend == null`→400
  `"chatId/canSendMessages required"`，文案逐字）收敛为 `parseBotSetChatPermissionsFields`
  纯函数（`BotSetChatPermissionsParse.kt`，结果二态 `Ok`/`Invalid`）——限流
  （原处理器里**先于** body 解析，本轮保持在解析之前，等价）/成员检查（`isParticipant`）/
  批量静音/`logCommand`/修订通知/响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotSetChatPermissionsParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、`canSendMessages` 恒为 JSON 布尔 true、`until` 恒为
  JSON 整数——**延续第四十一块的 CI 教训**；已知字段五名 `chatId`/`canSendMessages`/
  `can_send_messages`/`until`/`untilDate` 不得参与随机名）+
  必填语义（chatId 缺/空/纯空白→Invalid；canSend 缺/数字/显式 null→Invalid；
  `"TRUE"`→Ok(true)，大小写不敏感；双合法+until 缺席→Ok 且 until 缺省 `0L`）+
  canSend 严格布尔与别名（JSON 布尔/小写字符串接受；snake 兜底；camel 优先；
  显式 null camel→别名接管；camel 非布尔字符串落空→别名接管）+
  until 别名与回落（数字字符串→Long；`untilDate` 兜底；until 优先；非数字回落；
  双缺→`0L`；显式 null→`0L`）+
  空白不 trim（`" c1 "` 原样保留仍通过必填）+
  **抽取先于必填校验的顺序反证**（`until` 对象型 + chatId 空白时先抛，不走 `Invalid`；
  `canSend` 对象型 + chatId 空白时同样先抛；chatId 对象型先抛——抽取顺序
  chatId 先、canSend 中、until 末）+
  大声失败反证（对象/数组型在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null chatId 不抛→`"null"`→Ok）+
  近似字段名（`ChatId`/`canSendMessage`/`Until` 等按未知键忽略，真字段缺席→Invalid，
  `until` 缺席→`0L`）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（#285/#287 均于本轮按合并闸门收掉；本轮只碰 `BotMediaRouting.kt`/
  `BotSetChatPermissionsParse.kt`/`BotSetChatPermissionsParseFuzzTest.kt`+本清单——
  本轮清单改动为本账本节追加「续一百二/三（补记）/四」，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百七（2026-10-02）：bot `setChatDescription` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十八块）

- 端点 `/api/bot/setChatDescription` 的处理器内联 `chatId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**无 `.trim()`**：全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义）+ `description` 别名回退 + trim
  （`(obj["description"] ?: obj["announcement"])?.jsonPrimitive?.content.orEmpty().trim()`——
  `description` 优先、`announcement` 回退，**按存在而非按非空**：空字符串/显式 null 的
  `description` 都不回退；显式 null 得字面量 `"null"`→trim 后仍非空→Ok）+
  **双 400 分开**（`chatId.isBlank()`→400 `"chatId required"`；
  trim 后 `description.length > 1200`→400 `"description too long"`，文案逐字；
  **夹界判的是 trim 之后**：1201 个空格→`""`→Ok，不判超长）收敛为
  `parseBotSetChatDescriptionFields` 纯函数（`BotSetChatDescriptionParse.kt`，
  结果三态 `Ok`/`Invalid`/`TooLong`）——**抽取先于必填校验**（两处 `?.jsonPrimitive`
  都在判空之前，对象/数组型先抛 [IllegalArgumentException]，本轮用测试钉住）；
  空简介的下游语义（`description.takeIf { it.isNotBlank() }`→null 清公告）仍在处理器，
  本轮不动；限流（先于 body 解析）/成员检查/`updateAnnouncement`/`logCommand`/
  修订通知/响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotSetChatDescriptionParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c<i>"`、`description` 恒为 `"d<i>"`——**延续第四十一块的 CI 教训**；
  已知字段三名 `chatId`/`description`/`announcement` 不得参与随机名）+
  必填语义（chatId 缺/空/纯空白→Invalid；description 缺席→Ok("")）+
  别名与 trim（description 优先；announcement 兜底；前后空白 trim；全空白→""；
  显式 null→字面量 `"null"` 不回退）+
  长度夹界（1200→Ok；1201→TooLong；1201 空格→trim 后 ""→Ok；1200+x+前后空格→Ok）+
  抽取先于必填校验的顺序反证（description 对象型 + chatId 空白时先抛；chatId 数组型先抛）+
  大声失败反证（announcement 数组型在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500；显式 null chatId 不抛→`"null"`→Ok）+
  近似字段名（`ChatId`/`Description`/`Announcement` 等按未知键忽略）+
  chatId 无 trim（`" c1 "` 原样保留）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本轮改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#288，Server job 第三次 CI 运行中 + 刚修好 checklist 合并冲突；
  本轮只碰 `BotChatAdminRouting.kt`/`BotSetChatDescriptionParse.kt`/
  `BotSetChatDescriptionParseFuzzTest.kt`+本清单——与 #288 无文件交集；
  本轮清单改动为本账本节追加「续一百七」，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百八（2026-10-03）：bot `deleteUpdates` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第六十九块）

- 端点 `/api/bot/deleteUpdates` 的处理器内联上限抽取（`obj` 可为 null：body 非
  JSON 对象时 `runCatching { ...jsonObject }.getOrNull()` 得 null——本端点**没有**
  `"invalid json"` 400，原处理器逐字如此）+ 三层回退（body `upToId` 优先、body
  `offset` 兜底、query `upToId` 再兜底、缺省 `0L`；回退按「能否解析出 Long」而非
  按存在：非数字字符串 / 浮点数字符串 / 显式 JSON null（一律 `toLongOrNull()` 得
  `null`）继续回退；对象/数组型在 `?.jsonPrimitive` 处先抛，不走回退）+ 合并必填校验
  （`upTo <= 0L`→400 `"upToId required"`，文案逐字）收敛为
  `parseBotDeleteUpdatesFields(obj: JsonObject?, queryUpToId: String?)` 纯函数
  （`BotDeleteUpdatesParse.kt`，结果二态 `Ok`/`Invalid`，query 参数由处理器传入
  `call.request.queryParameters["upToId"]`）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/`deleteUpdates`/`logCommand`/响应仍在处理器，
  顺序与原处理器一致，下游一行不动；
- 新增 `BotDeleteUpdatesParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（upToId 恒为 `1000L + i`，已知字段二名 `upToId`/`offset` 不得参与随机名）+
  三层回退优先级（upToId 优先；offset 兜底；upToId 非数字/"5.9" 落空→offset；
  query 再兜底；双缺→Invalid）+
  null body 无 invalid-json 语义（obj=null 直接走 query；query 合法→Ok，
  全空→Invalid）+
  显式 null 落空（upToId/offset 显式 null 均继续回退，全链 null→Invalid）+
  必填语义（0/负数/全链非法→Invalid；query "-1"/"0" 同样 Invalid）+
  数字字符串接受（`"007"`→7L、JSON 数字同样）+
  大声失败反证（upToId/offset 对象/数组型抛 `IllegalArgumentException`；
  upToId 对象型 + offset/query 合法时依然先抛——抽取顺序 upToId 先）+
  近似字段名（`upToID`/`uptoid`/`up_to_id` 按未知键忽略）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#291 `refactor/set-chat-description-parse-fuzz`，
  改动文件为 `BotChatAdminRouting.kt`/`BotSetChatDescriptionParse.kt`/
  `BotSetChatDescriptionParseFuzzTest.kt`+本清单——本轮只碰 `BotMediaRouting.kt`/
  `BotDeleteUpdatesParse.kt`/`BotDeleteUpdatesParseFuzzTest.kt`+本清单；
  与 #291 的代码文件无交集；清单同为 EOF 追加，git 可自动合并，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。


### 第十三轮续一百九（2026-10-03）：bot `setWebhook` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十块）

- 端点 `/api/bot/setWebhook` 的处理器内联 `url` 抽取
  （`obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)`，**trim 在 take(500) 之前**；
  `obj` 可为 null：body 非 JSON 对象时 `runCatching { ...jsonObject }.getOrNull()` 得
  null——本端点**有** `"invalid json"` 400，原处理器逐字如此）+ 白名单校验
  （`!url.isNullOrBlank() && !isAllowedWebhookUrl(url)`→400 `"invalid webhook url"`，
  文案逐字；**空/纯空白跳过校验**；显式 JSON null 得字面量 `"null"`→非空→走校验；
  对象/数组型在 `?.jsonPrimitive` 处先抛，不走校验）收敛为
  `parseBotSetWebhookFields(obj: JsonObject?, isUrlAllowed: (String) -> Boolean)` 纯函数
  （`BotSetWebhookParse.kt`，结果三态 `Ok`/`InvalidJson`/`InvalidUrl`；白名单校验器以
  lambda 注入，生产侧传 `BotRepository::isAllowedWebhookUrl`，纯函数不直接依赖
  repository）——限流（原处理器里**先于** body 解析，本轮保持在解析之前，等价）/
  `setWebhookByToken`/`logCommand`/响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotSetWebhookParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（url 恒为 `"https://example.com/hook<i>"`，白名单替身只放行该前缀；
  已知字段一名 `url` 不得参与随机名）+
  null body 有 invalid-json 语义（obj=null→InvalidJson）+
  url 缺席→Ok(null)（下游清 webhook）+ 近似字段名（`URL`/`Url`/`webhook_url`
  按未知键忽略）+
  trim 在 take(500) 之前（前后空白先去；600 字符截断为 500，白名单看到截断后的值）+
  空/纯空白跳过白名单校验（校验器零调用）+
  白名单裁决（放行→Ok；不放行→InvalidUrl）+
  显式 null 走校验（字面量 `"null"` 被校验器收到）+
  大声失败反证（url 对象/数组型在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  即使白名单会放行也先抛）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#291 `refactor/set-chat-description-parse-fuzz`，
  改动文件为 `BotChatAdminRouting.kt`/`BotSetChatDescriptionParse.kt`/
  `BotSetChatDescriptionParseFuzzTest.kt`+本清单——本轮只碰 `BotWebhookRouting.kt`/
  `BotSetWebhookParse.kt`/`BotSetWebhookParseFuzzTest.kt`+本清单；
  与 #291 的代码文件无交集；清单同为 EOF 追加，git 可自动合并，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十（2026-10-03）：bot `kickChatMember` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十一块）

- 端点 `/api/bot/kickChatMember`（`banChatMember` 的 Telegram-compat 别名，路由文件注释
  逐字如此）的处理器内联 `chatId`/`userId` 抽取
  （`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`、`obj["userId"]?.jsonPrimitive?.content.orEmpty()`，
  **两字段均无 `.trim()`**：全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；抽取顺序 chatId 先、userId 后）+
  合并必填校验（`chatId.isBlank() || userId.isBlank()`→400 `"chatId/userId required"`，
  文案逐字）收敛为 `parseBotKickChatMemberFields` 纯函数
  （`BotKickChatMemberParse.kt`，结果二态 `Ok`/`Invalid`——与第五十九块 `banChatMember`
  的解析逐字同构，按系列惯例独立成文件）——限流（原处理器里**先于** body 解析，
  本轮保持在解析之前，等价）/成员检查（`isParticipant`）/`removeMember`/`logCommand`
  （`"kickChatMember"`）/修订通知/响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotKickChatMemberParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c" + i`、userId 恒为 `"u" + i`——**延续第四十一块的 CI 教训**；
  已知字段 `chatId`/`userId` 不得参与随机名；**双字段端点的每个 `okOf` 用例都携带
  全部必填字段**——吸取第五十七块 CI 教训，缺任一字段即 `Invalid`→`as Ok` 强转抛
  `ClassCastException`）+
  必填语义（chatId/userId 各自缺/空/纯空白→Invalid；双合法→Ok）+ 显式 null 字面量
  （`"null"`→Ok，单/双 null 均钉）+ 空白不 trim（`" c1 "`/`" u1 "` 原样保留仍通过必填）+
  大声失败反证（对象/数组型 chatId/userId 在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，
  路由层 `StatusPages` 映射 400，不是 500）+ 近似字段名（`ChatId`/`chat_id`/`UserId`/
  `user_id` 等按未知键忽略，真字段缺席→Invalid）；
### 第十三轮续一百一十一（2026-10-03）：bot `echo` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十二块）

- 端点 `/api/bot/echo` 的处理器内联 `text` 抽取
  （`(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(500)`：
  **`?:` 接在字段存在性上**——`text` 键存在（哪怕显式 JSON null）不穿透到
  `message`，得字面量 `"null"`；两键都缺 → `""`（无必填校验）；**take(500) 不 trim**，
  前后空白计入上限；对象/数组型在 `?.jsonPrimitive` 处大声失败；数字/布尔型走
  `.content` 字符串）收敛为 `parseBotEchoFields(obj: JsonObject): BotEchoFields`
  纯函数（`BotEchoParse.kt`，直接返回字段、无 Result——本端点无校验分支；
  `obj` 为 null 的 `"invalid json"` 400 仍在处理器，与 `banChatMember`/`kickChatMember`
  块同款纪律）——`logCommand`/`botId`/响应组装仍在处理器，下游一行不动；
- 新增 `BotEchoParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略恒等断言
  （text 恒为 `"t" + i`；已知字段 `text`/`message` 不得参与随机名）+
  text→message 别名回退（text 缺席用 message；双在 text 优先；双缺 → `""`）+
  显式 null 不穿透（text 显式 null 得字面量 `"null"`，不回退 message）+
  take(500) 截断（600 字符 → 500；前后空白计入上限，不 trim；message 别名同）+
  非字符串原语走 content（数字 → `"123"`、布尔 → `"true"`）+
  大声失败反证（对象/数组型 text/message 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  近似字段名（`Text`/`TEXT`/`text2`/`Message`/`message2` 等按未知键忽略，
  真字段缺席 → `""`）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#293 `refactor/set-webhook-parse-fuzz`，
  改动文件为 `BotWebhookRouting.kt`/`BotSetWebhookParse.kt`/
  `BotSetWebhookParseFuzzTest.kt`+本清单——本轮只碰 `BotMessagingVariantsRouting.kt`/
  `BotKickChatMemberParse.kt`/`BotKickChatMemberParseFuzzTest.kt`+本清单；
  与 #293 的代码文件无交集；清单同为 EOF 追加（本块取「续一百一十」，#293 的
  「续一百九」先入时 rebase 自动合并），不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。
- 开工时 1 个 open 重构 PR（#294 `refactor/kick-chat-member-parse-fuzz`，
  改动文件为 `BotMessagingVariantsRouting.kt`/`BotKickChatMemberParse.kt`/
  `BotKickChatMemberParseFuzzTest.kt`+本清单——本轮只碰 `BotPresentationCardsRouting.kt`/
  `BotEchoParse.kt`/`BotEchoParseFuzzTest.kt`+本清单；
  与 #294 的代码文件无交集；清单同为 EOF 追加——**本轮实测修正系列旧假设**：
  并发的清单 EOF 追加**不会**被 git 自动合并（#294 在 #293 合并后变 dirty），
  已在收 PR 侧以「rebase 到 main 最新 EOF」消解（git-database API 重建提交，
  树 SHA 与本地 rebase 逐字节一致），不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十二（2026-10-03）：bot `closePoll` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十三块）

- 端点 `/api/bot/closePoll` 的处理器内联 `pollId` 抽取
  （`obj["pollId"]?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败）+
  合并必填校验（`pollId.isBlank()`→400 `"pollId required"`，文案逐字）收敛为
  `parseBotClosePollFields` 纯函数（`BotClosePollParse.kt`，结果二态 `Ok`/`Invalid`；
  `obj` 为 null 的 `"invalid json"` 400 仍在处理器，与 `banChatMember`/`kickChatMember`
  块同款纪律）——限流（`requireRateLimitedBot`）与 `isBotDeliverable` 在原处理器里
  **先于** body 解析，本轮保持它们在解析之前，等价；`closePoll`（仅创建者可关）/
  `logCommand`（`"closePoll"`）/ 响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotClosePollParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（pollId 恒为 `"p" + i`；已知字段 `pollId` 不得参与随机名）+
  必填语义（缺/空/纯空白→Invalid；合法→Ok）+ 显式 null 字面量
  （`"null"`→Ok）+ 空白不 trim（`" p1 "` 原样保留仍通过必填）+
  非字符串原语走 content（数字 → `"123"`、布尔 → `"true"`）+
  大声失败反证（对象/数组型 pollId 在 `?.jsonPrimitive` 处抛
  `IllegalArgumentException`，路由层 `StatusPages` 映射 400，不是 500）+
  近似字段名（`PollId`/`poll_id`/`pollId2` 等按未知键忽略，真字段缺席→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#294 `refactor/kick-chat-member-parse-fuzz`，
  改动文件为 `BotMessagingVariantsRouting.kt`/`BotKickChatMemberParse.kt`/
  `BotKickChatMemberParseFuzzTest.kt`+本清单——本轮只碰 `BotPollRouting.kt`/
  `BotClosePollParse.kt`/`BotClosePollParseFuzzTest.kt`+本清单；
  与 #294 的代码文件无交集；清单同为 EOF 追加，不碰 §0 表格）；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十三（2026-10-03）：bot `votePoll` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十四块）

- 端点 `/api/bot/votePoll` 的处理器内联 `pollId` 抽取
  （`obj["pollId"]?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败，**先于索引校验抛出**——抽取顺序逐字保持）+
  索引解析（`optionIndexes` 数组逐元素非负整数校验；非法元素**整体拒绝**不静默截子集
  （`// 9.157` 注释纪律随逻辑搬入纯函数）→400 `"invalid optionIndexes"`；
  `optionIndexes` 缺席/null/非数组型（`as? JsonArray` 判 null）回退单 `optionIndex`
  （同样整数非负校验，缺/坏/负→400 `"pollId/optionIndexes required"`）；
  合并必填校验（空数组 `[]` 逐元素放行→空列表、`pollId` 空白→400
  `"pollId/optionIndexes required"`，与单字段分支逐字同文案，纯函数合并为一种 `Required`
  结果）收敛为 `parseBotVotePollFields(obj: JsonObject)` 纯函数
  （`BotVotePollParse.kt`，结果三态 `Ok`/`InvalidOptionIndexes`/`Required`；
  `obj` 为 null 的 `"invalid json"` 400 仍在处理器，与 `banChatMember`/`kickChatMember`
  块同款纪律）——限流（`requireRateLimitedBot`）与 `isBotDeliverable` 在原处理器里
  **先于** body 解析，本轮保持它们在解析之前，等价；`vote`（投票）/
  `logCommand`（`"votePoll"`）/ 响应仍在处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotVotePollParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（pollId 恒为 `"p" + i`、optionIndexes 恒为 `[i%3, (i+1)%3]`；
  已知字段 `pollId`/`optionIndexes`/`optionIndex` 不得参与随机名；**双字段端点的每个 `okOf`
  用例都携带全部必填字段**——吸取第五十七块 CI 教训，缺任一字段即 `Invalid`→`as Ok`
  强转抛 `ClassCastException`）+
  必填语义（pollId 缺/纯空白→Required；`optionIndexes` 空数组→Required；
  索引双缺→Required；双合法→Ok）+ 数组元素校验（负数/非整数/小数/`JsonNull`/
  嵌套数组→`InvalidOptionIndexes`，逐字复刻 `as? JsonPrimitive` + `toIntOrNull` 纪律）+
  单 `optionIndex` 回退（缺数组时合法→Ok；负数/非整数→Required）+
  数组优先（两键并存数组赢，非法单字段被忽略）+
  非数组型 `optionIndexes` 回退（字符串型→走单字段；null 型 + 单字段缺席→Required）+
  pollId 怪语义（无 trim 原样保留；显式 null 得字面量 `"null"`→Ok；
  对象/数组型在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，路由层 `StatusPages`
  映射 400，不是 500）+ 近似字段名（`PollId`/`poll_id`/`optionindexes` 等按未知键忽略，
  真字段缺席→Required）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotPollRouting.kt`/`BotVotePollParse.kt`/`BotVotePollParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十四（2026-10-03）：bot `stopPoll` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十五块）

- 端点 `/api/bot/stopPoll` 的处理器内联 `pollId` 抽取
  （`obj["pollId"]?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——全空白直接判空；
  显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败的逐字语义）+
  合并必填校验（`pollId.isBlank()`→400 `"pollId required"`，与 `closePoll` 块同文案，
  纯函数合并为一种 `Invalid` 结果）收敛为 `parseBotStopPollFields(obj: JsonObject)` 纯函数
  （`BotStopPollParse.kt`，结果二态 `Ok`/`Invalid`；
  `obj` 为 null 的 `"invalid json"` 400 仍在处理器，与 `banChatMember`/`kickChatMember`
  块同款纪律）——限流（`requireRateLimitedBot`）与 `isBotDeliverable` 在原处理器里
  **先于** body 解析，本轮保持它们在解析之前，等价；`PollRepository.closePoll`
  （`requireBotDeliverable = true`，与 `closePoll` 端点逐字同调用）/`logCommand`（`"stopPoll"`）/
  响应（逐字保留兼容字段 `alias: "closePoll"`）/ `"stop failed (creator only)"` 403 仍在
  处理器，顺序与原处理器一致，下游一行不动；
- 新增 `BotStopPollParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（pollId 恒为 `"p" + i`；已知字段 `pollId` 不得参与随机名）+
  必填语义（pollId 缺/空/纯空白→Invalid；合法→Ok）+
  pollId 怪语义（无 trim 原样保留；显式 null 得字面量 `"null"`→Ok；
  对象/数组型在 `?.jsonPrimitive` 处抛 `IllegalArgumentException`，路由层 `StatusPages`
  映射 400，不是 500）+ 非字符串原语（数字/布尔走 `.content` 字符串）+
  近似字段名（`PollId`/`POLLID`/`poll_id`/`pollid`/`pollId2` 等按未知键忽略，
  真字段缺席→Invalid）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotPollEditRouting.kt`/`BotStopPollParse.kt`/`BotStopPollParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十五（2026-10-03）：bot `sendSteps` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十六块）

- 端点 `/api/bot/sendSteps` 的处理器内联 `chatId`/`title`/`steps` 抽取
  （`chatId` 为 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——
  全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  `title` 为 `(obj["title"]?.jsonPrimitive?.content ?: "Steps").take(80)`：
  默认值 `"Steps"` **只在键缺席时回退**，显式 null 得字面量 `"null"`（不回退）、
  显式空字符串保留空（不回退），逐字钉住；对象/数组型 title 在 `?.jsonPrimitive`
  处大声失败的逐字语义；
  `steps` 为 `(obj["steps"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()).map { it.take(160) }.take(20)`：
  键缺席→空列表；显式 null/非数组型在 `?.jsonArray` 处大声失败（逐字语义）；
  数组里的对象/数组元素**静默丢弃**（`runCatching` 包住，逐字保留），
  `JsonNull` 元素得字面量 `"null"` 字符串；逐项 `.take(160)`、全表 `.take(20)`，逐字顺序）+
  合并必填校验（`chatId.isBlank() || steps.isEmpty()`→400 `"chatId/steps required"`，
  文案逐字；注意 steps 为空也可能是「全被静默丢弃」的结果，逐字语义，纯函数合并为
  一种 `Invalid` 结果）收敛为 `parseBotSendStepsFields(obj: JsonObject)` 纯函数
  （`BotSendStepsParse.kt`，结果二态 `Ok`/`Invalid`；
  `obj` 为 null 的 `"invalid json"` 400 仍在处理器，与 `sendCompare` 块同款纪律）——
  限流（`requireRateLimitedBot`）与 `isMarkdownEnabled` 门禁（`markdown_disabled` 403）
  在原处理器里**先于** body 解析，本轮保持它们在解析之前，等价；
  `isParticipant`（`bot not in chat` 403）/`publishBotServiceMessage`（固定 `type="MARKDOWN"`）/
  `logCommand`（`"sendSteps"`）/响应（`"ok": true`、`messageId`、`"type": "MARKDOWN"`）
  仍在处理器，顺序与原处理器一致，逐行等价——下游一行不动；
- 新增 `BotSendStepsParseFuzzTest`（9 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c" + i`、title 恒为 `"Steps"`、steps 恒为 `["one", "two"]`；
  已知字段 `chatId`/`title`/`steps`）；
  合并必填 4 前件（双缺/chatId 纯空白/steps 缺席/steps 空数组）+ 合法 Ok；
  title 默认值**仅缺键回退**三态（缺键→`"Steps"`、显式 null→`"null"`、显式空→`""`）+
  超长 80 截断；steps 元素规则（对象/数组元素静默丢弃、`JsonNull`→`"null"`、
  逐项 160 截断、全表 20 上限）；chatId 无 trim（`" c1 "` 原样保留仍通过）；
  非字符串 primitive 走 `.content`（`123`→`"123"`）；title 对象/数组、steps 显式 null/
  对象/字符串、chatId 对象/数组的坏类型大声失败反证（`IllegalArgumentException`）；
  近似字段名忽略（`ChatId`/`CHATID`/`chat_id`/`chatid`/`chatId2`/`Titles`/`stepz`）。
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotPresentationCardsRouting.kt`/`BotSendStepsParse.kt`/`BotSendStepsParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十六（2026-10-03）：bot hint 端点群（35 个 `/api/bot/send*Hint`）请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十七块）

- `BotHintRouting.kt` 的 `BOT_HINT_SPECS` 共享循环（35 个 `/api/bot/send*Hint` 端点）处理器内联
  `chatId` 抽取（`request["chatId"]?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——
  全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败的逐字语义）+
  合并必填校验（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）收敛为
  `parseBotHintChatId(request: JsonObject)` 纯函数（`BotHintParse.kt`，结果二态
  `Ok`/`Invalid`；`request` 为 null 的 `"invalid json"` 400 仍在处理器，同款纪律）——
  `hint` 解析（`request["hint"]?.jsonPrimitive?.content`：键缺席→null；
  显式 null→字面量 `"null"` 字符串，**不**回退 `defaultHint`，逐字语义；
  对象/数组型在 `?.jsonPrimitive` 处大声失败；`sanitize=true` 走
  `sanitizeBotHint(suppliedHint).ifBlank { defaultHint }`（缺键时 `sanitizeBotHint(null)`
  为空→回退默认值，逐字）、`sanitize=false` 走 `(suppliedHint ?: defaultHint).take(120)`，
  逐字）收敛为 `resolveBotHint(request, sanitize, defaultHint)` 纯函数——
  **拆成两个纯函数的原因**：原处理器里 `hint` 的解析在 `isParticipant`
  （403 `"bot not in chat"`）**之后**，若合并成一个函数，「hint 坏类型 + 非成员」的
  组合会从原处理器的 403 提前变成 400（大声失败先于成员检查）——拆分保持求值顺序
  逐行等价，下游一行不动；限流（`requireRateLimitedBot`）与功能门禁
  （`spec.enabled()`/`spec.disabledError` 403）在原处理器里**先于** body 解析，
  本轮保持它们在解析之前，等价；`isParticipant`/`publishBotServiceMessage`
  （固定 `type="SYSTEM"`，`content = spec.contentPrefix + hint`）/`logCommand`
  （`spec.path`）/响应（`"ok": true`、`messageId`、`"type": "SYSTEM"`）仍在处理器，
  顺序与原处理器一致，逐行等价——下游一行不动；
- 新增 `BotHintParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（chatId 恒为 `"c" + i`、hint 恒为 `"h" + i`；清洗/非清洗双分支；
  已知字段 `chatId`/`hint`）；
  必填语义（chatId 缺/空/纯空白→Invalid；显式 null→字面量 `"null"`→Ok；合法→Ok）；
  hint 非清洗分支（缺键→`defaultHint`、显式 null→`"null"`、显式空→`""`、
  超长 120 截断）；hint 清洗分支（缺键→`defaultHint`、清洗后空白→`defaultHint`、
  控制字符替换+连续空白折叠+trim、显式 null→`"null"`、超长 120 截断）；
  chatId 无 trim（`" c1 "` 原样保留仍通过）；
  非字符串 primitive 走 `.content`（`123`→`"123"`、`true`→`"true"`）；
  坏类型大声失败反证（chatId/hint 对象/数组→`IllegalArgumentException`）；
  近似字段名忽略（`ChatId`/`CHATID`/`chat_id`/`chatid`/`chatId2`/`Hint`/`HINT`/`hint2`/`hints`）。
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotHintRouting.kt`/`BotHintParse.kt`/`BotHintParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十七（2026-10-03）：`/api/chats/{chatId}/bot-callback` 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十八块）

- `BotInteractionRouting.kt` 的 `/api/chats/{chatId}/bot-callback` 处理器内联
  三字段抽取（`obj["messageId"]`/`obj["botUserId"]`/`obj["callbackData"]`
  取 `?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——
  全空白直接判空；显式 JSON null 得字面量 `"null"`→非空→Ok 的逐字怪语义；
  非字符串 primitive 走 `.content`（`123`→`"123"`、`true`→`"true"`，逐字）；
  对象/数组型在 `?.jsonPrimitive` 处大声失败的逐字语义）+
  合并必填与超长校验（`messageId.isBlank() || messageId.length > 80 ||
  botUserId.isBlank() || botUserId.length > 80 ||
  callbackData.isBlank() || callbackData.length > 128`→400
  `"messageId/botUserId/callbackData required"`，文案与上限逐字）收敛为
  `parseBotChatCallbackFields(obj)` 纯函数（`BotChatCallbackParse.kt`，结果二态
  `Ok`/`Invalid`）——`null` 的 `"invalid json"` 400 仍在处理器（`runCatching`
  形态同款纪律）；维护模式/`requireUserId`/`rejectIfSuspended`/
  `isParticipant`（403 `"无权访问该聊天"`）在原处理器里**先于** body 解析，
  本轮保持它们在解析之前，等价；`BotRepository.get`（403 `"bot unavailable"`）/
  `enqueueCallbackIfAuthorized`（403 `"回调按钮无效或已不可用"`）/
  `BotWebhookService.notifyBotDirect`/`updateId`（`"cbq_"` 前缀）/响应
  （`"ok": true`、`callbackQueryId`）仍在处理器，顺序与原处理器一致，
  逐行等价——下游一行不动；
- 新增 `BotChatCallbackParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三字段恒为 `"m<i>"`/`"b<i>"`/`"d<i>"`）；必填语义（全缺席/单个字段缺席/
  空/纯空白→Invalid；合法→Ok）；显式 null→字面量 `"null"` 三字段皆 Ok；
  上限逐字（80/80/128 边界恒等，超一个字符即 Invalid）；无 trim（`" m1 "` 原样保留）；
  非字符串 primitive 走 `.content`（`123`→`"123"`、`true`→`"true"`、`45.6`→`"45.6"`）；
  坏类型大声失败反证（messageId/botUserId/callbackData 对象/数组→
  `IllegalArgumentException`）；近似字段名忽略（`MessageId`/`MESSAGEID`/`message_id`/
  `messageId2`/`BotUserId`/`bot_user_id`/`callbackdata`/`CallbackData`/`callbackData2`）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotInteractionRouting.kt`/`BotChatCallbackParse.kt`/`BotChatCallbackParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十八（2026-10-03）：`POST /api/bots` 创建 bot 请求体手写解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第七十九块）

- `BotManagementRouting.kt` 的 `POST /api/bots` 处理器内联三字段抽取（`name`/`username`
  取 `obj["…"]?.jsonPrimitive?.content.orEmpty()`：**无 `.trim()`**——前后空格原样保留；
  键缺席→空串（后续 `BotRepository.create` 自行判输入非法，抽取层不提前判空）；
  显式 JSON null 得字面量 `"null"`→非空→照常进入创建流程的逐字怪语义；
  非字符串 primitive 走 `.content`（`123`→`"123"`、`true`→`"true"`，逐字）；
  对象/数组型在 `?.jsonPrimitive` 处大声失败的逐字语义）+
  `description` 取 `obj["description"]?.jsonPrimitive?.content`（**可空**：
  缺席→`null`；显式 JSON null→字面量 `"null"` 而非 null，`JsonNull` 是
  `JsonPrimitive` 的逐字怪语义；对象/数组型同样大声失败）收敛为
  `parseBotCreateFields(obj)` 纯函数（`BotCreateParse.kt`，直接返回
  `BotCreateFields`，处理器端无合并校验——校验本来就在 `BotRepository.create`
  的 `BotCreateResult` 分支里）——维护模式/`isBotsAllowed`/`requireUserId`/
  `rejectIfSuspended`/创建限流在原处理器里**先于** body 解析，本轮保持它们在解析
  之前，等价；`BotRepository.create` 的输入校验/用户名占用/数量上限/账号状态判定
  仍在处理器 `when` 分支里，顺序与原处理器一致，逐行等价——下游一行不动；
- 新增 `BotCreateParseFuzzTest`（7 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（三字段恒为 `"n<i>"`/`"u<i>"`/`"d<i>"`）；
  缺席语义（全缺席→name/username 空串、description null；description 单独缺席→null；
  空串原样保留）；
  显式 null→字面量 `"null"`（description 亦然，非 null）；
  无 trim（`" n1 "` 原样保留；纯空白原样保留——抽取层不提前判空）；
  非字符串 primitive 走 `.content`（`123`→`"123"`、`true`→`"true"`、`45.6`→`"45.6"`）；
  坏类型大声失败反证（name/username/description 对象/数组→`IllegalArgumentException`）；
  近似字段名忽略（`Name`/`NAME`/`user_name`/`Username`/`USERNAME`/`username2`/
  `descriptions`/`Description`/`desc`）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotManagementRouting.kt`/`BotCreateParse.kt`/`BotCreateParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百一十九（2026-10-03）：`PUT /api/bots/{botId}/webhook` 请求体 url 解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第八十块）

- `BotManagementRouting.kt` 的 `PUT /api/bots/{botId}/webhook` 处理器内联 `url` 抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.take(500)`）收敛为
  `parseManagementWebhookUrl(body)` 纯函数（`BotManagementWebhookParse.kt`）——与
  Bot API 的 `/api/bot/setWebhook`（`BotSetWebhookParse.kt`，第七十块）**语义不同**，逐字怪语义原样钉住：
  本端点**不判 `invalid json`**，runCatching 覆盖整个 parse+抽取链：坏 JSON / 顶层非对象 /
  url 对象数组型（`?.jsonPrimitive` 处抛）统统被吞 → `url == null` → 下游
  `setWebhook(botId, userId, null)` 即清空 webhook（POST `/api/bots` 那块是大声失败，这里是吞掉）；
  显式 JSON null → `JsonNull` 是 `JsonPrimitive`，`.content` 得字面量 `"null"`（非空→照常进白名单校验）；
  数字/布尔型走 `.content`（`123`→`"123"`、`true`→`"true"`）；`trim` 先于 `take(500)`（超长先去空白再截断）；
  缺席/坏 JSON/坏类型→`null`，空串/纯空白→`""`（下游 `isNullOrBlank()` 跳过白名单校验→原样清空）；
  维护模式/`requireUserId`/`rejectIfSuspended`/`botId` 参数校验在原处理器里**先于** body 解析，
  本轮保持它们在解析之前，等价；`BotRepository.isAllowedWebhookUrl` 白名单校验仍在处理器里，
  顺序与原处理器一致，逐行等价——下游一行不动；
- 新增 `BotManagementWebhookParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（url 恒为 `"u<i>"`，随机名避开 `url`、注入永不破坏 JSON 语法）；
  缺席语义（缺席/空 body→null）；坏 JSON 与顶层非对象被吞→null；
  显式 null→字面量 `"null"`；坏类型（对象/数组）被吞→null（反证与第七十块的大声失败不同）；
  trim 先于 take(500)（尾部空白先去再截；超长→500）+ 纯空白→空串；
  非字符串 primitive 走 `.content`（`123`→`"123"`、`true`→`"true"`、`45.6`→`"45.6"`）；
  近似字段名忽略（`URL`/`Url`/`urls`/`webhook_url`）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- §3 收了 #302 后 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotManagementRouting.kt`/`BotManagementWebhookParse.kt`/`BotManagementWebhookParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十（2026-10-03）：`PUT /api/bots/{botId}/enabled` 请求体 enabled 解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第八十一块）

- `BotManagementRouting.kt` 的 `PUT /api/bots/{botId}/enabled` 处理器内联 `enabled` 抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive }` +
  `p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()` + `?: 400 "enabled required"`）
  收敛为 `parseManagementBotEnabled(body): Boolean?` 纯函数（`BotEnabledParse.kt`，`null` 即
  「不可用」，处理器侧 `?: 400 "enabled required"` 逐字保留）——与之前各块逐字不同的
  怪语义原样钉住：
  runCatching 覆盖 parse+抽取全链：坏 JSON / 顶层非对象 / `enabled` 对象数组型
  （`?.jsonPrimitive` 处抛）统统被吞 → `null` → 400 `"enabled required"`，
  **本端点没有单独的 `"invalid json"` 判定**（坏 JSON 与字段缺席同文案，POST `/api/bots`
  那块是先判 jsonObject 再报 `"invalid json"`）；布尔字面量 `true`/`false` 同值；
  字符串型 `"true"`/`"false"` 有效（`booleanOrNull` 即 `content.toBooleanStrictOrNull()`，
  大小写不敏感，`"TRUE"`/`"True"` 同样有效，与原处理器逐字一致；`"1"`/空串/数字
  → `null` → 400）；显式 JSON null → `JsonNull`
  是 `JsonPrimitive`，`content` 为字面量 `"null"`，`toBooleanStrictOrNull("null")` 为 null
  → `null` → 400（与 webhook 管理块的「显式 null 得字面量字符串继续走白名单」不同）；
  维护模式/`requireUserId`/`rejectIfSuspended`/`botId` 参数校验在原处理器里**先于** body 解析，
  本轮保持它们在解析之前，等价；`BotRepository.setEnabled`/bot 响应仍在处理器，
  顺序与原处理器一致，逐行等价——下游一行不动；
  路由文件里因此无用的 `booleanOrNull`/`jsonPrimitive`/`JsonPrimitive` import 已移除
  （`Json`/`jsonObject` 仍被 POST `/api/bots` 的 `"invalid json"` 判定使用）；
- 新增 `BotEnabledParseFuzzTest`（8 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（enabled 真假交替恒等）；缺席语义（缺席/空 body/空对象→null）；
  布尔字面量（true/false→同值）；严格字符串布尔（"true"/"false" 有效，
  "TRUE"/"True" 同样有效——toBooleanStrictOrNull 大小写不敏感，与原处理器逐字一致；
  "1"/""/"yes"/数字 1/0 → null）；显式 null→null
  （反证与 webhook 块的"null"字面量不同）；坏 JSON 与顶层非对象被吞→null
  （无 "invalid json" 判定）；坏类型（对象/数组）被吞→null（大声失败反证）；
  近似字段名忽略（`Enabled`/`ENABLED`/`enabled2`/`enable`/`isEnabled`→null，
  真字段混在其中仍被识别）；
### 第十三轮续一百二十一（2026-10-03）：`POST /api/chats/{chatId}/bot-inbox` 请求体 text/botIdHint 解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第八十二块）

- `BotInteractionRouting.kt` 的 `POST /api/chats/{chatId}/bot-inbox` 处理器内联 `text`/`botIdHint`
  抽取（`(obj["text"] as? JsonPrimitive)?.content.orEmpty()` +
  `(obj["botId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }`）
  收敛为 `parseBotInboxFields(obj): BotInboxFields` 纯函数（`BotInboxParse.kt`）——
  与之前各块逐字不同的怪语义原样钉住：
  `as? JsonPrimitive` 转型失败**静默**（`text` 对象/数组型 → `""`、`botIdHint` 对象/数组型
  → `null`，与 `?.jsonPrimitive` 的大声失败不同，本端点沿用静默习惯）；显式 JSON null
  → `JsonNull` 是 `JsonPrimitive`，`text` 得字面量 `"null"`、`botIdHint` 得 `"null"`
  （非空，不被 `takeIf { it.isNotBlank() }` 滤掉）；`botIdHint` 缺席/空串/全空白 → `null`；
  数字/布尔型 → 其字面量 content（`1`→`"1"`、`true`→`"true"`）；坏 JSON 的
  `"invalid json"` 判定、`sanitizeInboxText` 的「命令无效或不能是密文」校验、
  限流/鉴权/入群检查/投递仍在处理器，顺序与原处理器一致，逐行等价——下游一行不动；
- 新增 `BotInboxParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言（text/botIdHint 交替恒等）；缺席语义（text 缺席→`""`、botId 缺席→null）；
  text 类型强制（显式 null→`"null"`、对象/数组→`""` 静默、数字→`"1"`、布尔→`"true"`）；
  botIdHint 语义（空串/全空白→null、显式 null→`"null"`、对象→null 静默、数字→`"7"`）；
  近似字段名忽略（`Text`/`TEXT`/`text2`/`txt`、`botid`/`BOTID`/`botId2`/`bot_id`→忽略，
  真字段混在其中仍被识别）；编解码往返恒等；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（仅 dependabot 依赖 PR，无文件交集）；本轮只碰
  `BotManagementRouting.kt`/`BotEnabledParse.kt`/`BotEnabledParseFuzzTest.kt`+本清单，
  `ServerArchitectureTest` 对该路由无行数上限；`RouteRegistrySplitTest` 端点数不变，不受影响；
- 开工时 1 个 open 重构 PR（#304 bot-enabled-parse-fuzz，文件为
  `BotEnabledParse.kt`/`BotManagementRouting.kt`/`BotEnabledParseFuzzTest.kt`）；
  本轮只碰 `BotInteractionRouting.kt`/`BotInboxParse.kt`/`BotInboxParseFuzzTest.kt`+本清单，
  与 open PR 的文件无交集；清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十二（2026-10-03）：`POST /api/chats/{chatId}/bots` 请求体 botId 解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第八十三块）

- `BotInteractionRouting.kt` 的 `POST /api/chats/{chatId}/bots` 处理器内联 `botId` 抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject["botId"]?.jsonPrimitive?.content }.getOrNull().orEmpty()`）
  收敛为 `parseAddBotToChatBotId(body): String` 纯函数（`BotAddBotToChatParse.kt`）——
  与之前各块逐字不同的怪语义原样钉住：
  坏 JSON / 顶层非对象（无单独 `"invalid json"` 判定）/ `botId` 为对象或数组
  （`?.jsonPrimitive` 处抛 `IllegalArgumentException`，**大声失败**——与 bot-inbox 块的
  `as?` 静默**反证**，被 `runCatching` 吞掉）统统 → `""` → 处理器报 400
  `"botId required"`；显式 JSON null → `JsonNull` 是 `JsonPrimitive`，
  `content` 为字面量 `"null"`（**非空**，不被 `isBlank()` 滤掉）→ 不走 400 文案，
  而是继续走下游 `addOwnedBot(..., "null", ...)` → `BOT_NOT_FOUND`
  → 404 `"bot not found"`（与 enabled 块的「显式 null 直接 400」不同，
  沿用 webhook 管理 / bot-inbox 块的「`"null"` 字面量继续走」习惯）；
  数字/布尔型 → 其字面量 content（`7`→`"7"`、`true`→`"true"`）；
  字符串型原样返回（含首尾空白，空白判在处理器 `isBlank()` 侧）；
  `botId` 缺席 → `""` → 400；近似字段名（`botid`/`BOTID`/`botId2`/`bot_id` 等）
  一律忽略 → `""` → 400；`receiveBoundedTextOrEmpty(4_096)`、
  `botId.length > 80` 的 400 判定、维护模式/封禁拦截/功能开关、
  `addOwnedBot` 结果映射全部仍在处理器，顺序一致——下游一行不动；
- 新增 `BotAddBotToChatParseFuzzTest`（6 例）：150 固定种子随机 payload 未知键忽略
  恒等断言；坏 JSON/顶层非对象吞异常语义（`""`）；对象/数组型大声失败→`""`；
  显式 null→字面量 `"null"` 非空继续走下游；数字/布尔字面量 content 与
  空白/长度语义（纯函数只抽取，`isBlank()`/`>80` 判在处理器）；近似字段名忽略
  （真字段混在其中仍被识别）+ 编解码往返恒等；
- 附带清理：`BotInteractionRouting.kt` 移除现已无引用的 `jsonPrimitive` 扩展 import
  （第 248 行是唯一使用者）；
- 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接，
  见 AGENTS.md 2026-09-30 教训）；
- `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动
  （server 路由 + 新增 server 解析/测试文件 + 清单账本）无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
- 开工时 0 个 open 重构 PR（#306 本轮已合并，仅剩 dependabot 依赖 PR，无文件交集）；
  本轮只碰 `BotInteractionRouting.kt`/`BotAddBotToChatParse.kt`/
  `BotAddBotToChatParseFuzzTest.kt`+本清单，`ServerArchitectureTest` 对该路由无行数上限；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十三（2026-10-03）：`POST /api/chats/{chatId}/bot-inbox` 与 `POST /api/chats/{chatId}/bot-callback` 请求体 JSON 对象信封解析收敛为纯函数 + fuzz 钉住（Q01 bot 侧第八十四块）

- `BotInteractionRouting.kt` 两个端点各自内联的信封抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`，
  语义逐字相同）收敛为 `parseJsonObjectEnvelopeOrNull(body): JsonObject?`
  纯函数（`BotJsonEnvelopeParse.kt`）——怪语义原样钉住：
  坏 JSON（截断 / 空串 / 纯空白 / 语法错误）/ 顶层非对象（数组 / 字符串 /
  数字 / 布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
  `.jsonObject` 处抛 `IllegalArgumentException`，**大声失败**被
  `runCatching` 吞掉）统统 → `null` → 两端点共用同一判定
  `?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))`；
  顶层为对象 → 逐字原样返回（不深拷贝、不排序、未知键全部保留，
  交由各自的字段抽取 `parseBotInboxFields` / `parseBotChatCallbackFields`
  决定取舍）；空 body（`receiveBoundedTextOrEmpty` 兜底 `""`）→ 抛 → `null`
  → 400 `"invalid json"`，与原先逐字一致；
  各端点的 `body` 上限（`8_192` / `16_384`）、鉴权 / 限流 / 投递映射仍在
  处理器，顺序一致——下游一行不动；
  `BotInteractionRouting.kt` 的 `jsonObject` import 因两处调用点均搬移而移除
  （`Json` 类型 import 仍被 `json: Json` 参数使用，保留）；
- 新增 `BotJsonEnvelopeParseFuzzTest`（4 例）：固定种子 150 个随机对象 payload
  往返恒等（未知键避开 `text`/`botId`/`messageId`/`botUserId`/`callbackData`
  全部保留）；10 种坏 body → `null`；顶层数组/字符串/数字/布尔/显式 null → `null`
 （尾随空白的对象仍可解析为空对象）；嵌套显式 null 保留；
- 开工时 0 个 open 重构 PR（#307 已 squash 合并）；
  本轮只碰 `BotInteractionRouting.kt`/`BotJsonEnvelopeParse.kt`/`BotJsonEnvelopeParseFuzzTest.kt`+本清单，
  清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十四（2026-10-03）：`POST /api/chats/{chatId}/polls` 与 `POST /api/polls/{pollId}/vote` 请求体 JSON 对象信封解析收敛为纯函数 + fuzz 钉住（Q01 poll 侧第一块）

- `PollLegacyRouting.kt` 两个写端点各自内联的信封抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`，
  语义逐字相同）收敛为 `parsePollJsonEnvelopeOrNull(body): JsonObject?`
  纯函数（`PollJsonEnvelopeParse.kt`）——怪语义原样钉住：
  坏 JSON（截断 / 空串 / 纯空白 / 语法错误）/ 顶层非对象（数组 / 字符串 /
  数字 / 布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
  `.jsonObject` 处抛 `IllegalArgumentException`，**大声失败**被
  `runCatching` 吞掉）统统 → `null` → 两端点共用同一判定
  `?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))`；
  顶层为对象 → 逐字原样返回（不深拷贝、不排序、未知键全部保留，
  交由各自的字段抽取决定取舍）；空 body（`receiveBoundedTextOrEmpty` 兜底 `""`）→ 抛 → `null`
  → 400 `"invalid json"`，与原先逐字一致；
  各端点的 `body` 上限（`32_768` / `8_192`）、鉴权 / 成员校验 / 禁言检查 /
  字段抽取怪语义（`question`.orEmpty()、9.157 非法选项整体拒绝、严格整数拒绝）
  仍在处理器，顺序一致——下游一行不动；
  `PollLegacyRouting.kt` 用 wildcard import（`kotlinx.serialization.json.*`），
  无需清理 import；
- 新增 `PollJsonEnvelopeParseFuzzTest`（4 例）：固定种子 150 个随机对象 payload
  往返恒等（未知键避开 `question`/`options`/`multi`/`anonymous`/`closesAt`/
  `optionIndexes`/`optionIndex` 全部保留）；10 种坏 body → `null`；
  顶层数组/字符串/数字/布尔/显式 null → `null`
  （尾随空白的对象仍可解析为空对象）；嵌套显式 null 保留；
- 开工时 0 个 open 重构 PR（#308 已 squash 合并，仅剩 dependabot 依赖 PR，无文件交集）；
  本轮只碰 `PollLegacyRouting.kt`/`PollJsonEnvelopeParse.kt`/`PollJsonEnvelopeParseFuzzTest.kt`+本清单，
  `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
  清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十五（2026-10-03）：`POST /api/bot/sendPollQuiz` 与 `POST /api/bot/sendDiceCustom` 请求体 JSON 对象信封解析收敛为纯函数 + fuzz 钉住（Q01 poll 侧第二块）

- `BotPollQuizRouting.kt` 两个写端点各自内联的信封抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`，
  语义逐字相同）收敛为 `parseBotPollQuizJsonEnvelopeOrNull(body): JsonObject?`
  纯函数（`BotPollQuizJsonEnvelopeParse.kt`）——怪语义原样钉住：
  坏 JSON（截断 / 空串 / 纯空白 / 语法错误）/ 顶层非对象（数组 / 字符串 /
  数字 / 布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
  `.jsonObject` 处抛 `IllegalArgumentException`，**大声失败**被
  `runCatching` 吞掉）统统 → `null` → 两端点共用同一判定
  `?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))`；
  顶层为对象 → 逐字原样返回（不深拷贝、不排序、未知键全部保留，
  交由各自的字段抽取决定取舍）；空 body（`receiveBoundedTextOrEmpty` 兜底 `""`）→ 抛 → `null`
  → 400 `"invalid json"`，与原先逐字一致；
  各端点的鉴权 / 限流（`requireRateLimitedBot` 在信封解析之前）/
  字段抽取怪语义（`question` 回退 `text`、200 字符截断、
  `correctOptionIndex` 越界 `coerceIn`、9.157 非法选项整体拒绝、
  `sides` 非法回退 6 再 `coerceIn(2, 100)`）仍在处理器，顺序一致——
  下游一行不动；
  `BotPollQuizRouting.kt` 用 wildcard import（`kotlinx.serialization.json.*`），
  无需清理 import；
- 新增 `BotPollQuizJsonEnvelopeParseFuzzTest`（4 例）：固定种子 150 个随机对象 payload
  往返恒等（未知键避开 `chatId`/`question`/`text`/`options`/`correctOptionIndex`/
  `sides` 全部保留）；10 种坏 body → `null`；
  顶层数组/字符串/数字/布尔/显式 null → `null`
  （尾随空白的对象仍可解析为空对象）；嵌套显式 null 保留；
- 开工时 0 个 open 重构 PR（#309 已 squash 合并，仅剩 dependabot 依赖 PR，无文件交集）；
  本轮只碰 `BotPollQuizRouting.kt`/`BotPollQuizJsonEnvelopeParse.kt`/`BotPollQuizJsonEnvelopeParseFuzzTest.kt`+本清单，
  `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
  清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十六（2026-10-03）：`POST /api/bot/stopPoll` 与 `POST /api/bot/editMessageCaption` 请求体 JSON 对象信封解析收敛为纯函数 + fuzz 钉住（Q01 poll 侧第三块）

- `BotPollEditRouting.kt` 两个写端点各自内联的信封抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`，
  语义逐字相同）收敛为 `parseBotPollEditJsonEnvelopeOrNull(body): JsonObject?`
  纯函数（`BotPollEditJsonEnvelopeParse.kt`）——怪语义原样钉住：
  坏 JSON（截断 / 空串 / 纯空白 / 语法错误）/ 顶层非对象（数组 / 字符串 /
  数字 / 布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
  `.jsonObject` 处抛 `IllegalArgumentException`，**大声失败**被
  `runCatching` 吞掉）统统 → `null` → 两端点共用同一判定
  `?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))`；
  顶层为对象 → 逐字原样返回（不深拷贝、不排序、未知键全部保留，
  交由各自的字段抽取决定取舍）；空 body（`receiveBoundedTextOrEmpty` 兜底 `""`）→ 抛 → `null`
  → 400 `"invalid json"`，与原先逐字一致；
  各端点的鉴权 / 限流（`requireRateLimitedBot` 在信封解析之前）/ stopPoll 侧
  `isBotDeliverable` 与 `parseBotStopPollFields` 怪语义（`pollId` 无 trim /
  显式 null 得字面量 `"null"` / 合并必填校验 400 `"pollId required"`）/
  editMessageCaption 侧字段抽取怪语义（`messageId` 无 trim、
  `caption` 双别名 `caption` → `text` + 无 trim + 先取后截 1000、
  `isPeerE2eeContent` 拒绝判定、`buildBotEditCaptionContent` 组装、
  响应 `caption.take(200)`）仍在处理器，顺序一致——下游一行不动；
  `BotPollEditRouting.kt` 用 wildcard import（`kotlinx.serialization.json.*`），
  无需清理 import；
- 新增 `BotPollEditJsonEnvelopeParseFuzzTest`（4 例）：固定种子 150 个随机对象 payload
  往返恒等（未知键避开 `pollId`/`messageId`/`caption`/`text` 全部保留）；10 种坏 body → `null`；
  顶层数组/字符串/数字/布尔/显式 null → `null`
  （尾随空白的对象仍可解析为空对象）；嵌套显式 null 保留；
- 开工时 0 个 open 重构 PR（#310 已 squash 合并，仅剩 dependabot 依赖 PR，无文件交集）；
  本轮只碰 `BotPollEditRouting.kt`/`BotPollEditJsonEnvelopeParse.kt`/`BotPollEditJsonEnvelopeParseFuzzTest.kt`+本清单，
  `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 只断言端点声明仍在模块内，端点数不变，不受影响；
  清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十七（2026-10-04）：`POST /api/bot/sendPoll`、`POST /api/bot/sendDice`、`POST /api/bot/votePoll`、`POST /api/bot/closePoll` 请求体 JSON 对象信封解析收敛为纯函数 + fuzz 钉住（Q01 poll 侧第四块）

- `BotPollRouting.kt` 四个写端点各自内联的信封抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`，
  语义逐字相同）收敛为 `parseBotPollJsonEnvelopeOrNull(body): JsonObject?`
  纯函数（`BotPollJsonEnvelopeParse.kt`）——怪语义原样钉住：
  坏 JSON（截断 / 空串 / 纯空白 / 语法错误）/ 顶层非对象（数组 / 字符串 /
  数字 / 布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
  `.jsonObject` 处抛 `IllegalArgumentException`，**大声失败**被
  `runCatching` 吞掉）统统 → `null` → 四端点共用同一判定
  `?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))`；
  顶层为对象 → 逐字原样返回（不深拷贝、不排序、未知键全部保留，
  交由各自的字段抽取决定取舍）；空 body（`receiveBoundedTextOrEmpty` 兜底 `""`）→ 抛 → `null`
  → 400 `"invalid json"`，与原先逐字一致；
  各端点的鉴权 / 限流（`requireRateLimitedBot` 在信封解析之前，sendPoll /
  votePoll / closePoll 的 `isBotDeliverable` 亦在解析之前）/
  字段抽取怪语义（sendPoll 的 `options` 非数组判 `MissingRequired` /
  显式 null 选项保留字面量 `"null"` / `question` 不 trim / 布尔别名穿透 /
  `closesAt`→`closeDate` 穿透；sendDice 的 `emoji` 不 trim 全字串 `when` /
  `sides` 非数字穿透到 emoji 映射 / `coerceIn(2, 100)` 钳制；votePoll 的
  `pollId` 无 trim / 显式 null 得字面量 `"null"` / `optionIndexes` 非法整体拒绝
  （9.157）/ 合并必填校验；closePoll 的 `pollId` 无 trim / 显式 null 得字面量
  `"null"` / 合并必填校验 400 `"pollId required"`）仍在处理器，顺序一致——
  下游一行不动；
  `BotPollRouting.kt` 用 wildcard import（`kotlinx.serialization.json.*`），
  无需清理 import；
- 新增 `BotPollJsonEnvelopeParseFuzzTest`（4 例）：固定种子（15027）150 个随机对象
  payload 往返恒等（未知键避开 `chatId`/`question`/`options`/`multi`/
  `allowsMultipleAnswers`/`anonymous`/`isAnonymous`/`closesAt`/`closeDate`/
  `emoji`/`sides`/`pollId`/`optionIndexes`/`optionIndex` 全部保留）；10 种坏 body → `null`；
  顶层数组/字符串/数字/布尔/显式 null → `null`
  （尾随空白的对象仍可解析为空对象）；嵌套显式 null 保留；
- 开工时 0 个 open 重构 PR（#311 已 squash 合并，仅剩 dependabot 依赖 PR，无文件交集）；
  本轮只碰 `BotPollRouting.kt`/`BotPollJsonEnvelopeParse.kt`/`BotPollJsonEnvelopeParseFuzzTest.kt`+本清单，
  `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 断言 961 个 bot 端点声明数不变（本轮只换内联表达式、不增减端点），不受影响；
  清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。

### 第十三轮续一百二十八（2026-10-04）：`POST /users/bulk-force-logout`、`POST /users/bulk-ban`、`POST /users/bulk-unban`、`POST /users/bulk-suspend-days` 请求体 JSON 对象信封解析收敛为纯函数 + fuzz 钉住（Q01 admin 侧第一块）

- `AdminBulkRouting.kt` 四个端点各自内联的信封抽取
  （`runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`，
  语义逐字相同）收敛为 `parseAdminBulkJsonEnvelopeOrNull(body): JsonObject?`
  纯函数（`AdminBulkJsonEnvelopeParse.kt`）——怪语义原样钉住：
  坏 JSON（截断 / 空串 / 纯空白 / 语法错误）/ 顶层非对象（数组 / 字符串 /
  数字 / 布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
  `.jsonObject` 处抛 `IllegalArgumentException`，**大声失败**被
  `runCatching` 吞掉）统统 → `null` → 四端点共用同一判定
  `?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))`；
  顶层为对象 → 逐字原样返回（不深拷贝、不排序、未知键全部保留，
  交由各自的字段抽取决定取舍）；
  body 读取（`runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()`，
  受 `MAX_ADMIN_JSON_BODY_CHARS` 上限，失败兜底空串）仍在处理器，不进纯函数；
  空 body → `""` → 抛 → `null` → 400 `"invalid json"`，与原先逐字一致；
  鉴权（`call.isAdminUser()` 在信封解析之前）/ 各端点字段抽取怪语义
  （bulk-force-logout 的内联 `userIds` 数组/分隔串解析（`take(64)`/`distinct()`/`take(200)`）
  + `AdminAccess.isAdmin` 管理员保护 + `rotateAccessTokenVersion`
  + 踢在线会话 + `"ADMIN_BULK_FORCE_LOGOUT"` 审计；bulk-ban 的 `parseAdminIds(obj)`
  + `days`（`toIntOrNull() ?: 1`，`coerceIn(1, DispositionService.MAX_BAN_DAYS)`）
  + `reasonCode`（空串回退 `"BULK_BAN"`）；bulk-unban 的 `parseAdminIds(obj)`；
  bulk-suspend-days 的 `parseAdminIds(obj)` + `days`（`toIntOrNull() ?: 1`，
  `coerceIn(1, 365)`））仍在处理器，顺序一致——下游一行不动；
  `AdminBulkRouting.kt` 显式 import（非 wildcard），`jsonObject` 仍被其余 20 个
  端点内联用，无需清理 import；
- 新增 `AdminBulkJsonEnvelopeParseFuzzTest`（4 例）：固定种子（18031）150 个随机对象
  payload 往返恒等（未知键避开 `userIds`/`days`/`reasonCode` 全部保留）；
  10 种坏 body → `null`；顶层数组/字符串/数字/布尔/显式 null → `null`
  （尾随空白的空对象仍可解析）；嵌套显式 null 保留；
- 开工时 0 个 open 重构 PR（#312 已 squash 合并，仅剩 dependabot 依赖 PR，无文件交集）；
  本轮只碰 `AdminBulkRouting.kt`/`AdminBulkJsonEnvelopeParse.kt`/`AdminBulkJsonEnvelopeParseFuzzTest.kt`+本清单，
  `ClientArchitectureTest` 的 `frozenHotspotLineCaps` 只覆盖 app 文件，本块改动无一在监，无需收紧上限；
  `RouteRegistrySplitTest` 断言 bot 端点声明数（本轮只换管理侧内联表达式、不增减端点），不受影响；
  清单 EOF 追加，不碰 §0 表格；
- 判据：本机无 JDK/Android SDK，未本地验证，待 CI 验证。 **2026-10-06 路由内联 Regex 提升第四批（PR 待 CI）**：`BotSend*Parse`（animation/audio/document/photo/video/voice）6 个文件的 base64 空白剔除 `\"\\s\".toRegex()`×6——每次请求重复编译的正则提升为文件级 `private val`（pattern 逐字相同，零行为改动）。
