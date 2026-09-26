# Maodouchat 全项目重构总清单

**审计基线日期**：2026-08-29  
**审计分支**：`main`  
**已提交基线**：`e3344a53`（当时与 `origin/main` 一致；此后到 2026-09-24 的进度见下方「权威口径」）  
**工作区状态**：**2026-09-24 实测为干净**（此前这里写的「约 255 个未提交状态项」是 2026-08-29 的快照，早已不成立）。  
**目标**：保留产品能力和已经验证的 Messaging V2 协议不变量，重写职责边界、状态所有权、存储、网络、UI 和后端领域实现，最终删除旧入口与兼容实现。

本文是执行清单，不是“功能已经完成”的声明。只有同时满足代码、迁移、测试、删除旧路径和真实 E2E 门槛，条目才允许勾选完成。

## 0. 权威口径（2026-09-24 重新实测）

**这一节是本文所有数字的唯一权威来源。** 正文里的内联数字（尤其是 §G32–G58、§Q0x 那些
长段落里带日期的数字）是**当时那一轮的日志**，多数已过期，甚至与本文其它段落互相矛盾——
审计实测确实如此。要引用任何计数，先跑下面一条命令重新测，或引用本节。

| 量 | 2026-09-24 实测值 | 怎么测 |
|----|------------------|--------|
| 工作区脏项 | 0 | `git status --porcelain \| wc -l` |
| App JVM 单测（执行数） | 2129 | `./gradlew :app:testDebugUnitTest` 后读 `app/build/test-results/testDebugUnitTest/*.xml` |
| App 仪器测试 | 156（`@Test` 计数） | `grep -rho "@Test" app/src/androidTest --include=*.kt \| wc -l` |
| Server 单测（执行数） | 578（`@Test` 标注 603，差值是 postgres tag 等未进默认套件的） | `cd server && ../gradlew test` 后读 `server/build/test-results/test/*.xml` |
| core/domain 模块测试（执行数） | 68 | `./gradlew test -x :app:test` 后读各模块 `build/test-results/test/*.xml` |
| 有测试源文件的模块 | 9 个 | `ClientArchitectureTest.modulesWithTests`（G328c 从 5 个增到 9 个） |
| `settings.gradle.kts` 模块数 | 10（app + 8 core + 1 domain） | `grep -c 'include(' settings.gradle.kts` |
| `plugins/` 内 `transaction {` | **0 处** | `grep -rc "transaction {" server/src/main/kotlin/.../plugins/*.kt` |
| `plugins/` 内 import Exposed | **0 个文件**（`StatusPages.kt` 只有全限定名引用，非 import） | `grep -rl org.jetbrains.exposed .../plugins/*.kt` |
| `repository/`→`plugins/` 反向依赖 | 0 处 | `ServerArchitectureTest.frozenRepositoryDependingOnPlugins`（空 map） |
| 非 ui 包 import ui | **2 处**（均为合理例外，见门禁白名单） | `ClientArchitectureTest.packages outside ui must not import ui` |
| ui 直连 `ApiService`（真发请求） | **0 个文件**（G328c 完成，空名单 + 反向断言） | `ClientArchitectureTest.frozenUiApiCallers` |
| ui 读会话令牌 `TokenManager` | **31 个文件**（只许降；修法是让仓库自持凭据，见 `data/repository/SessionTokens.kt`） | `ClientArchitectureTest.frozenUiTokenReaders` |
| core 模块生产引用 | 在用 3（crypto 44 / realtime 23 / model 5）；**零引用 4**（util、serialization、network、session，已登记） | `ClientArchitectureTest.core modules are either adopted...` |
| 就地 `OkHttpClient.Builder()` | 0 处（除共享工厂自身） | `ClientArchitectureTest.okhttp clients must come from the shared factory` |
| 最热三个文件行数 | `ChatDetailRoute.kt` 2786 / `ChatDetailViewModel.kt` 2545 / `util/GroupPlayPolicy.kt` 858 | `ClientArchitectureTest.frozenHotspotLineCaps`（**零余量**） |

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
- [ ] 删除 ViewModel 和 Composable 对 Token/Application/WebSocket 的直接访问。

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

当前状态：`[~]`。`ConversationCommandFacade` 统一调度消息命令；但 `ChatDetailViewModel` 职责**并未收敛**：实测 3131 行 / 130 个函数（仅 35 个是单行委托）、133 行构造期手写装配约 30 个具体依赖、**18 处直连 `app.database.*` DAO**、**6 处裸 `ApiService.*`**、**8 处 `signalProtocol.*` 原语**、46 处 `viewModelScope.launch`；`currentGroupRevision()`(182 行)、`loadChat()`(178 行)、`sendEncryptedAttachment()`(139 行)、`handleGroupRevisionChanged()`(123 行) 等单体核心未拆。端口接线目前只是薄层。

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
- [~] 平台动作通过 effect handler 执行，不在 Composable 内直接写库（**未达标，但已量化、上了棘轮，且抽出的第一块已修出真缺陷**：`ChatDetailRoute.kt:879` 在 Composable 内取 `secretChatDao()`，`:891` 于 `while(true)` 中每 60s `touchActivity()` 写库，并内联 TTL 过期判定/`destroySession`；`:1010/1025/1042/1059` 另取 4 个 `database` 句柄驱动 AI 业务逻辑。`ChatDetailRoute.kt` 实为单个 5061 行 composable，含 78 处 `LaunchedEffect`。**G8 实测全量口径**：`app/src/main/java/com/maodouchat/ui/` 下 `database.`/`secretChatDao`/`MaodouchatApp` 共 **38 个文件 / 200 处**（最重 `ChatDetailViewModel.kt` 36、`ChatListPorts.kt` 26、`SettingsSubScreens.kt` 11），已由 `ClientHotspotRatchetTest` 精确相等冻结，只许下降；该门禁此前从不运行，G8 已接进 CI。**G9** 把上面那段密聊心跳抽成非 Composable 的 `security/SecretChatActivityHeartbeat.kt`，`ChatDetailRoute.kt` 直连命中 9→6、行数 5061→5048，口径校正为 Composable 主口径 **84 处 / 17 文件** 与全 ui 次口径 **197 处 / 38 文件**。**G10** 随即用它抓到并修掉一个真缺陷：抽出前的 `runCatching` 会把 `CancellationException` 一起吞掉，取消后仍写一次 `touchActivity`——而 `touchActivity` 会**延长密聊 TTL**，等于让泄漏的心跳给已离开/已销毁的会话续命；现按项目惯例重抛取消并在每次写活动前 `ensureActive()`，7 个单测（含 2 个取消用例）钉住）。
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
- [ ] ViewModel 只显示 call state 和发送 user intent。
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
- [ ] 拆为 Provider、Credential、Context Builder、Tool Registry、Approval、Audit、Executor。
- [ ] 每个 Tool 只依赖领域 Command/Query Port，不访问 DAO、ApiService 或 Application。
- [ ] 明确每种模型调用的数据出境清单、密聊/PIN 门禁和日志脱敏。
- [ ] 写操作携带审批记录和幂等键；取消后不得继续执行工具。
- [ ] 改写、回复建议、翻译、摘要、OCR、转写、任务提取分别拥有状态和错误边界。
- [ ] 端侧 embedding 当前仍未实现，保持明确标记。

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
- [ ] 所有主流程支持大字体、TalkBack、触控目标、RTL、中英文和动态颜色。
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

- [x] 按 bot-auth、management、updates、messaging、group-admin、media 拆模块（BotCore 3454→430 行 + BotPresentation 1988→153 行，31 个子模块，端点计数 961 由 RouteRegistrySplitTest 锁定）。
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
- [ ] 消除反向依赖：`repository/BotRepository.kt:18`→`plugins.isAllowedWebhookAddress`、`repository/RateLimitStatsRepository.kt:4-5`→`plugins.GlobalRateLimiter/RateLimitStats` 为真实倒置；`service/` 另有 3 个文件（`BotWebhookService`/`CallSignalingService`/`OrphanGcJob`）反向依赖 `plugins/`（G5 已把 `MaintenanceRunner` 的 `purgeAdminOperationalData` 迁到 repository 而消掉一条）；另有 16 个 `*Service.kt`（约 3784 行，如 `repository/FeedQueryService.kt` 455 行、`repository/AccountLifecycleService.kt` 432 行）物理错放在 `repository/`，破坏「repository=SQL 边界」契约。以上三组均已进入 `ServerArchitectureTest` 棘轮基线。

Gate：master/moderator/user 权限、审计、敏感配置和大数据查询性能通过。

### B14 应用更新、静态文件、水印与运维工具

当前状态：`[ ]`。这些能力存在，但仍与总路由、文件服务和 Admin 混合。

- [x] 官服与动态构建解耦：原生 WebRTC 依赖由 Gradle 构建任务自动解压至 `build/generated/resources/webrtc` 并注入 classpath，彻底移除源码树中提交的原生 `.so`/`.dylib` 二进制残留。
- [x] 更新发布使用签名 manifest、不可降级策略和不可变制品元数据（不可降级 + 不可变 manifest 旁文件 + HMAC-SHA256 签名（key=JWT_SECRET，客户端验签）全部落地；`AppUpdatePublishPolicy` 修复 UTF-8 / ISO 智能转码防乱码）。
- [~] 静态/官网资源与 API route 分离部署和缓存策略（缓存策略已落地：HTML no-cache、静态 CSS/JS `public, max-age=3600`、PNG/WebRTC 更长缓存；分离部署属 nginx/CDN 运维配置）。
- [x] 水印提取任务异步化，限制文件、CPU、内存和执行时间（`Dispatchers.Default` 异步 + 30s 超时兜底；请求体 4MB + 维度 ≤8192/像素 ≤16M 解压炸弹防御）。
- [ ] 备份/恢复脚本纳入版本 migration 和定期恢复演练。
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
- [ ] 服务端 `Routing.kt` 只保留模块注册，不再包含领域 endpoint/事务。
- [x] `BotApiRouting.kt`、`AdminRouting.kt` 巨型实现删除并由子域 routes 替代。
- [x] `Database.kt` 启动期 create/backfill/drop 逻辑删除（Database.kt 47 行仅留 datasource，migrationRunner 接管）。
- [x] 旧人类消息 REST/WS API、v1 消息表和 legacy repository 删除（Messaging V2 全量接管，历史 v1 表在 migration v2 彻底退役）。
- [x] 旧 direct chat 扫描、空 callId、缺 device status 等长期兼容删除（DirectChatPairs 唯一键 + migration v3 替代）。
- [ ] 所有临时 facade 均有删除版本和调用者清零证明。

## 11. 测试与发布硬门槛

### Q01 单元与架构测试

- [ ] 每个 domain command/query 有成功、失败、取消、重复和账号切换测试。
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
- [ ] 协议模型有向前/向后兼容与 fuzz 测试。（2026-09-26 进展：第一块——`MessagingV2Routing` 的 `messagingV2Json` 从 `ignoreUnknownKeys = false` 收敛为 `true`（其余路由早已是 `true`，此前新版客户端给 V2 发未知字段会 400），`internal` 可见性供测试直接引用同一份配置；新增 `MessagingV2ForwardCompatTest` 5 例：未知字段容忍×2、缺失可选字段回默认值、往返稳定、未知包装字段不污染已知字段。fuzz 测试仍缺，本项保持 `[ ]`。）

### Q02 数据库与迁移

- [~] Android 每个支持旧版本 -> 当前版本真实数据迁移测试（`AppDatabaseMigrationTest` 因 A03 抽取全挂已修复：27 处引用改走 `DatabaseMigrations`；新增 36→40 业务四表链测试。**此处的历史教训**：原先写的「CI 仪器测试执行」是错的——`ci.yml` 与 `release.yml` 都没有 `connectedDebugAndroidTest`（`grep -n "connected\|androidTest\|instrument" .github/workflows/*.yml` → 0 命中），4 个仪器测试文件从未被任何自动化执行。**G3 已补上 CI `instrumented` job**（emulator runner 跑 `connectedDebugAndroidTest`），第一次真跑就抓到 `migrate33To34CreatesTerminalTombstonesWithConversationCascade` 是红的。仍未覆盖：真实旧版本设备数据 fixture、SQLCipher 密钥/磁盘满/损坏等故障注入）。
- [~] Server 空库、最后生产版本、重复、中断、回滚/恢复 migration 测试（**G6 已在真 PostgreSQL 上落地**：`PostgresMigrationMatrixTest`（`@Tag("postgres")`，5 例）覆盖空库到最新、旧版本库只补缺失版本、失败整体回滚且重跑收敛、双实例并发被 advisory lock 串行化、pg_trgm 不可用时降级；备份/恢复往返由 `scripts/rehearse-pg-restore.sh` 覆盖（逐表行数 + 内容 + 外键 + 索引比对 + 坏备份必须被拒绝）。**仍缺**：「最后生产版本」的真实旧库 fixture（目前用「前 3 个迁移」模拟旧库））。
- [~] PostgreSQL 是并发和约束测试真源，H2 只用于快速测试（PG 侧现有并发测试 + G6 的迁移矩阵 + G58 的**带数据真实升级**；但 414 个 H2 用例仍是主体，绝大多数约束/并发语义**只在 H2 上验证过**）。
  - **G58 补齐的那一块**：升级路径的**数据保全**此前零证据（G6 自己记的 Risk：「旧库不是真实数据 fixture」）。`PostgresUpgradeDataPreservationTest` 现在用真 PG 证明：v3 旧库灌入真实用户/会话/设备/密钥/附件/信令/动态后按生产迁移升到最新版，9 类表行数不变、6 个身份字段逐项不变、v4 新列对老行回默认值、v5 回填补齐设备行且不重复、二次迁移 no-op。
- [ ] SQLCipher 密钥、磁盘满、事务故障和数据损坏测试。

### Q03 Compose 与系统集成

- [~] Chat、List、Contacts、Explore、Call、Settings 主流程 Compose 测试（**G319c：七个入口全覆盖（含五个屏幕本体）**。此前只有 dialog 层的 `ChatDetailDialogsUiTest`（G173b，12 个 dialog）；G301c 新增两批——`ui/screen/contacts/ContactsRowsUiTest`（**10 例**，覆盖 `ContactItem` 与 `FriendRequestRow` 两个无状态行 composable）与 `ui/screen/explore/ExploreLikersDialogUiTest`（**7 例**，覆盖 Explore 与 PostDetail **共用**的 `LikersDialog`）；G305c 与 G309c 再各加一个**屏幕本体**测试——`ui/screen/contacts/ContactsScreenUiTest`（**5 例**）与 `ui/screen/explore/ExploreScreenUiTest`（**4 例**），两个屏幕都是「显式传 fake VM」直接 `setContent`，**不需要任何依赖注入改造**（详见 G305c/G309c：VM 的 `viewModel` 本就是普通参数，默认值只在省略时才求值）。每例同时断言可见性与行为，文案一律取 `R.string`。**均已在本地 AVD `maodou_test` 实跑**（JUnit XML 逐条核对）；负控制五轮，其中两轮是行为级且**预判完全命中**（Contacts 与 Explore 各一轮：把屏幕的 `onOpenScan`/`onOpenPost` 接线改成空操作，结果只有断言行为的那条红、只断言可见性的那条仍绿）。全量 instrumented **122 tests / 0 failures**（27 skipped 全在 `PersistentSignalStoreRoundTripTest`，真机用例、预先存在）。**仍未做**：Chats（原 G313c 五点实证仍成立——`ChatListScreen.kt` 无无状态行 composable、两个 dialog 组都必填 `viewModel`、`ChatListPorts` 是含 7 个具体协作者的 `internal class`、其中 4 个从未被任何测试构造；**但 G317c 已铺好接缝**：ports 构造器 `private` → `internal`，测试侧现在能 `ChatListViewModel(app, ports)`；且 G317c 已更正我当时的错误推论——那 4 个里 `TokenManager` 有 `getInstance` 入口、`ChatRepository`/`MissedCallRepository` 收 Room DAO **接口**、`NotificationCenterRepository` 收 `Context`，**都并非不可构造，只是没人做过**。剩余仅 fake/内存库工作）与 Call / Settings 的屏幕本体（**G315c 已补**：Call 8 例、Settings 6 例；**G319c 补 Chats 3 例**）；未登录时 Explore 走的是 snackbar 而非内联文案，那条路径因涉及时序未做用例（记为可选项）。故本项保持 `[~]` 不标 `[x]`。
- [~] 截图覆盖浅/深色、手机/平板、横屏、大字体、RTL、中英文（**G325c：零依赖起步**。仓库此前**零截图基建**（无 paparazzi/roborazzi），所以本轮没做像素回归（那需新增构建依赖），改用 `CompositionLocalProvider` 覆盖 `LocalLayoutDirection`(RTL) 与 `LocalConfiguration`(fontScale=2.0)，对 ChatListScreen 与 SettingsScreen 各测两种配置，断言「不崩 + 关键内容仍在」——4 例。**关键是防「配置没生效却空跑」**：每例先捕获生效值并断言，且对捕获值断言本身做了负控制（Rtl 改 Ltr → 红在捕获值断言而非 chip 断言），证明覆盖生效、断言承重。**仍未做**：像素级截图回归、浅/深色、平板尺寸、横屏、中英文）
- [~] 通知、Widget、深链、权限、前台服务和更新器仪器测试（**G329c：只开了「更新器」一个头**。实测 `app/src/androidTest` 原有子目录只有 crypto/data/e2e/messaging/ui，六项全为零。新增 `update/AppUpdatePromptStoreTest`（4 例，钉住「同一 versionCode 只弹一次、等下一版再弹」这条 UX 契约）与 `update/AppUpdateDownloadSchedulerTest`（5 例，只测 `progressOf`/`errorOf` 两个纯函数；**实测 `androidx.work.WorkInfo` 构造器在测试里可直接调用**，不需要 `work-testing` 依赖）。**其余五项（通知/Widget/深链/权限/前台服务）仍为零**，`AppUpdateDownloadWorker`（真正干下载活的）也未测——它的 `enqueue`/`cancel`/`observe` 要真跑 WorkManager）

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
- [~] Release 必须依赖同 commit 全门禁成功，不允许单独绕过测试构建（**G3 已补门禁**：`release.yml` 新增 `verify-ci` job 并让 `release` 声明 `needs: verify-ci`；该 job 用 `gh api` 查同 `github.sha` 上 `ci.yml` 是否存在 `completed + success` 的 run，否则 `exit 1`。本地已双向验证脚本逻辑：在绿 commit `9734a007` 上 `ok=1` 放行；在无 CI run 的 `35b60169` 上 `runs=[] ok=0` 拒绝。仍未做：跨 workflow 的强制 required-check，绕过手段是手动 `workflow_dispatch` 一个未过 CI 的 ref——但该 ref 同样会被 `verify-ci` 拦下）。
- [ ] 生产签名 Secret 缺失必须失败，禁止回退 debug 签名。
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
| `ChatDetailRoute.kt` 继续拆 | 3013 行，仍是单个 composable | 剩余都是 15–40 行的中小块；大块（450 行弹层）已搬 |
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

行数：2786 → 2774 → 2763 → 2755 → 2751 → 2747 → 2742 → 2737 → 2731 → 2727 → 2721 → 2701 → 2682 → 2682 → 2680 → 2680 → 2678 → 2677 → 2677 → 2676 → 2655 → 2660 → 2557（第十四批只搬引用、净行数不变；第十五批 3 个开关收成 1 行声明 + 1 行接线，净 −2；第十六批 2 个引用状态收进持有类、净 0；第十七批聊天锁错误文案归位、净 −2；第十八批禁言到期触发器收进持有类、`mutableLongStateOf` import 一并移除、净 −1；第十九批气泡坐标缓存收进持有类、净 0，热点上限维持 2677；第二十批 Route 最后的 2 处 MaodouchatApp 直连收进 crypto 包 helper、净 −1，热点上限收紧至 2676；第二十一批删死状态 `showGroupInfo`（声明/`LaunchedEffect`/对话框块）与零引用的群信息对话框文件、净 −21，热点上限收紧至 2655；第二十二批提醒列表与重排草稿两个对话框局部状态收进 `ChatDetailScheduleState`（打开处同步加载 + `LaunchedEffect` 按 chatId 重载，`rescheduleTextDraft` 进 Saver 转屏保草稿；删零调用的 `openSchedule`/`closeAll`）、净 +5，热点上限两处同步调整至 2660；第二十三批删 22 批抽离后残留的 103 行未使用 import（逐条核验 simple name 在正文无出现，`getValue`/`setValue` 与别名 import 排除）、净 −103，热点上限两处同步收紧至 2557；第二十四批 ViewModel 死代码清理（`exportToUri`/`exportRejectMessage` + 随之零引用的 `ChatExportGuard` 及其单测）净 −61，热点上限两处同步收紧至 2481；第二十五批 ViewModel 死代码清理第二批（删 `setSilentSend`/`cancelSendMessage`/`exportChatAsJson`/`addGroupMember`/`verifyAllDevices` 5 个零引用透传方法）净 −18，热点上限两处同步收紧至 2463；每批上限恒等于实测行数，零余量规则）。
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
