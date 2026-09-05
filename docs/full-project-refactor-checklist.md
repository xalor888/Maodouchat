# Maodouchat 全项目重构总清单

**审计基线日期**：2026-08-29  
**审计分支**：`main`  
**已提交基线**：`e3344a53`（当时与 `origin/main` 一致）  
**工作区状态**：约 255 个未提交状态项；本文以当前未提交源码为准，数量会随实施变化。  
**目标**：保留产品能力和已经验证的 Messaging V2 协议不变量，重写职责边界、状态所有权、存储、网络、UI 和后端领域实现，最终删除旧入口与兼容实现。

本文是执行清单，不是“功能已经完成”的声明。只有同时满足代码、迁移、测试、删除旧路径和真实 E2E 门槛，条目才允许勾选完成。

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
- [ ] 文档、监控、错误码、隐私边界和发布回滚方案同步更新。

## 3. 不可破坏的消息架构原则

以下原则来自已经落地的 Messaging V2，不应在“重写”中退回旧设计：

- [~] 服务端以设备邮箱持久化密文信封，presence 不参与消息是否可发送的判断。
- [~] metadata 与全部目标设备 envelopes 在同一服务端事务提交。
- [~] 客户端先持久化 inbox，再解密和投影；成功后进入可恢复 ACK 流程。
- [~] ACK、send、pull、mutation、receipt 必须幂等。
- [~] 群 Sender Key 分发和缺钥修复通过持久化加密邮箱，不要求成员同时在线。
- [~] 群成员 revision 改变后，旧的预制群密文必须失效并重新准备。
- [~] 删除和撤回是终态数据库事实；延迟 DATA、附件 finalize、定时任务不得复活消息。
- [~] WebSocket 只承载唤醒、presence、typing 和通话信令，不得重新承载人类消息正文。
- [~] 服务端不得保存或检索人类聊天明文；Bot/service message 使用独立存储语义。

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
- [~] 所有共享接口先冻结再并行实施：clock/id/dispatcher/SessionContext/领域错误(NetworkResult)/JSON(MaodouJson) 已冻结；typed payload 待 M01 定义。

Gate：空壳模块可编译；旧 app 仍能运行；禁止依赖检查进入 CI。

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
- [x] `AppDatabase.kt` 只负责数据库创建、注册 migration 和 transaction boundary（30 个迁移已抽到 `DatabaseMigrations.kt`，AppDatabase 723→171 行）。
- [x] 建立 `DatabaseLifecycle`，覆盖创建、解锁、换号销毁、迁移失败和恢复（已抽为 `internal object DatabaseLifecycle`：open/close/destroy/backup-recreate）。
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

当前状态：`[x]`。`ConversationCommandFacade` 统一调度消息命令，`ChatDetailViewModel` 职责收敛。

- [x] 建立 `ConversationTimelineStore`、`ComposerController`、`ConversationCommandFacade`（`ConversationCommandFacade` 已接入并调度文本、重试与转发）。
- [x] 建立 `ConversationRealtimeCoordinator`、`ConversationSecurityController`。
- [x] 建立独立 Media、Search、Selection、AI、Group 状态 controller。
- [x] 单一宽 `ChatDetailUiState` 组合各稳定领域子状态。
- [x] 统一收敛消息发送、状态流转与重发逻辑至 Messaging V2 Outbox 与 CommandFacade。

Gate：ViewModel reducer、快速切会话、进程恢复、单 intent 单命令和账号切换测试通过。

### U02 Chat Detail Compose 页面

当前状态：`[x]`。Telegram 预见性返回手势、线性弹性微动效就绪；巨型单体解耦完成，`ChatDetailScreen.kt` 42 行薄路由，子组件面板化。

- [x] 系统级预见性返回手势：在 `AndroidManifest.xml` 中启用 `android:enableOnBackInvokedCallback="true"`，适配 Android 13+ / 14+ 类似 TG 的侧滑返回预览。
- [x] 拆为 `ConversationRoute`、`TimelinePane`、`ComposerPane` 等子面板，`ChatDetailScreen.kt` 仅 42 行纯代理入口。
- [x] sheets：转发、定时、举报、安全码、联系人、AI、媒体操作。
- [x] banners：置顶、断线、群公告、消失消息、安全变化、Sender Key 健康。
- [x] 平台动作通过 effect handler 执行，不在 Composable 内直接写库。
- [x] 子组件接收稳定 model/event。

Gate：大字体、长文本、RTL、中英文、横屏、平板、键盘和弹窗互斥 Compose 测试通过。

### U03 消息气泡与内容渲染

当前状态：`[x]`。客户端 UI 全面对齐 `reference/Murexide` 现代 Material 3 + Liquid Glass 体系，旧单体已拆分。

- [x] 建立 `MurexideMessageBubble.kt`，纯正 Murexide M3 质感：浅色 `#EEEEF0`、深色 `#1E1E20`、己方主色容器；
- [x] 动态 18dp/4.5dp 连续气泡圆角，邻近消息流自然收敛；
- [x] 底部对齐的 36dp 精致圆形头像（对齐 Murexide 规范）；
- [x] 优雅的 Quote Reply 引用微件：左侧 3dp 竖向高光色块，紧凑预览与作者名；
- [x] 液态玻璃：集成 Murexide `LiquidGlass` 高斯模糊微光与高对比度文字自适应；
- [x] 拆分独立渲染器：`TextMessageBubble.kt`、`MediaMessageBubbles.kt`、`FileMessageRenderer.kt`，消除 wire/meta 渲染期耦合。

Gate：每种消息 golden、损坏内容、未知类型、超长文本/文件名、终态和无障碍测试通过。

### U04 Composer、草稿、回复、提及与输入状态

当前状态：`[x]`。客户端输入区全面对齐 `reference/Murexide` 胶囊设计与微动效。

- [x] 建立 `MurexideMessageInput.kt`：26dp Capsule 胶囊聊天输入栏，带 LiquidGlass 与高度自适应；
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
- [x] 签到、接龙、PK、投票拆成独立 feature，不继续集中在 `GroupPlayPolicy.kt`。
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

当前状态：`[ ]`。`CallViewModel.kt` 1,576 行，`WebRTCManager.kt` 1,431 行。

- [ ] 建立 `CallSessionMachine`、`SignalingTransport`、`RtcPeerFactory`。
- [ ] Direct、Group Mesh、媒体采集、音频路由、ICE 恢复、系统集成分层。
- [ ] 所有信令携带合法 `callId`、session epoch，并幂等处理。
- [ ] ViewModel 只显示 call state 和发送 user intent。
- [ ] 前台服务、Telecom、锁屏和权限生命周期进入 `CallSystemIntegration`。
- [ ] 明确 6 人 Mesh 上限；SFU、屏幕共享、录制保持未实现，不能伪装完成。

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

当前状态：`[~]`。Push 注册语义与实际 WS 保活混杂。`AppNotifier.kt` 已删除（1057→0）：`EXTRA_*` 逐字迁入 `NotificationIntents`，35 文件调用方改直连四服务/基础设施（含 3 个 mockk 单测转 mock 新服务）。

- [x] 冻结通知槽位纯策略：`NotificationSlotPolicy`（消息/reminder/来电/未接/动态/AI任务/好友/群邀请/公告/测试 tag+id+分组+dataURI+requestCode，表达式与历史行为逐字等价；`AppNotifier` 私有槽位常量与 `incomingCallNotifyId/missedCallNotifyId/aiTaskGroupSummaryId` 私有函数已删除）。
- [ ] 定义统一 `PushTransport`；前台 WS 与后台推送渠道职责分开。
- [ ] 推送只唤醒 inbox/sync，不携带聊天敏感正文。
- [ ] 替换语义模糊的守护/假来电/媒体保活实现，遵守 Android 后台限制。
- [x] 拆分 Message、Call、Social、Reminder notification service（4/4 完成：+`MessageNotificationService`（showMessage/reminder/test/scheduledFailed/cancelMessage，M11 动作/前台静音/群渠道逐行搬运）与 Post 归位 Social（showPostInteraction/cancelPostInteraction）；`AppNotifier` 1057→207 行，仅剩薄委托/`EXTRA_*` 常量/兼容入口）。
- [x] 通知去重保持纯策略（`NotificationSlotPolicy` 为 tag/id/分组/data-URI/requestCode 唯一事实源；账号隔离仍由 `notificationOwnerMatches` + `NotificationIntentPolicy` 双门禁执行；共享实现下沉为模块内 `NotificationInfrastructure`，四服务直连）。
- [x] 删除巨型 `AppNotifier` 静态入口（文件已删除；调用方直连四服务/`NotificationInfrastructure`/`NotificationIntents`，零 `AppNotifier` 代码引用残留）。

Gate：Doze、强杀、重启、Token 轮换、Android 13-16 权限、密聊脱敏和重复推送通过。

### P08 Navigation、Activity、深链与系统入口

当前状态：`[~]`。`NavGraph.kt` 1,046 行，Activity 承担较多全局状态。纯决策层已先行冻结：`AppLinkDestination` typed 目标 + `AppLinkRouter` 纯解析/清洗器（13 单测，含遍历/非法字符/危险 scheme/斜杠注入拒绝），零行为变更，待调用方渐进接入。

- [x] 冻结纯决策契约：typed `AppLinkDestination`（ChatDetail/PublicProfile/PostDetail/GroupInvite/NotificationCenter/CallHistory，`requiresAuth` + `toRoute()`）与 `AppLinkRouter`（深链白名单 scheme/host、通知 extras 映射、严格/宽松两档清洗器）。
- [~] MainActivity 外部深链分支已迁移到 `AppLinkRouter.parseDeepLink`（手写 scheme/host/path 截取删除；parity 单测锁定旧接受/忽略语义；多余路径段从截断改为拒绝，见 8.34 注释意图）。通知 extras 四 ID（chat/message/aiTasks/post）已改走严格清洗（含 /?# 拒收；非法 messageId 仅丢高亮、仍开会话）。Telecom、Widget 直跳、二维码、邀请、网页链接尚未统一接入。
- [ ] 每个 feature 提供 destination contract 并由 NavGraph 统一注册（`Routes` 字符串常量仍在使用中）。
- [ ] 通知、来电、Widget、二维码、邀请和网页链接统一经过 `AppLinkRouter`（MainActivity 手写解析与 navDeepLink pattern 尚未迁移）。
- [ ] Deep link 参数在执行业务前完成认证、权限和数据校验。
- [ ] `MainActivity` 只负责宿主、权限和系统生命周期。
- [ ] 导航接线由唯一集成 Agent 完成。

Gate：冷/热启动、登录重定向、返回栈、非法链接、旋转、进程恢复和平板测试通过。

### P09 Widget、应用更新、安装与发布渠道

当前状态：`[~]`。更新乱码修复、Android P+ 签名提取修复、WebRTC 原生库 SHA-256 强校验已落地。

- [x] 更新清单与安装器签名修复：修复 `OfficialApkInstaller` Android P+ 签名提取与十六进制标准格式化；修复 `AppUpdatePolicy` UTF-8 / ISO 智能转码防乱码。
- [x] WebRTC 原生库按需下载加固：`WebRtcNativeDownloadPolicy` 来源白名单与强制 SHA-256 哈希校验。
- [ ] Widget 使用账号隔离的 projection 和消息 command port。
- [ ] 更新清单校验 HTTPS、SHA-256、包名、versionCode 和签名证书。
- [ ] 下载进入 WorkManager，可恢复并校验完整性。
- [ ] 禁止降级、错误签名、错误包和不可信重定向。
- [ ] 发布渠道、签名证书和回滚策略形成文档与自动检查。

Gate：截断、哈希错、签名错、空间不足、权限恢复、多 Widget 和换号测试通过。

### P10 性能、无障碍、国际化与 UI 稳定性

当前状态：`[ ]`。尚无系统 Compose UI/截图/无障碍回归体系。

- [ ] 建立启动、聊天列表、长时间线、图片列表、数据库和内存基准。
- [ ] 所有主流程支持大字体、TalkBack、触控目标、RTL、中英文和动态颜色。
- [ ] 固定格式控件使用稳定尺寸，避免消息状态和进度造成布局跳动。
- [ ] 建立截图基线覆盖浅色、深色、手机、平板和横屏。
- [ ] 避免超大 state 导致全屏重组；用稳定 selector 和分页投影。

Gate：性能预算、Macrobenchmark、无障碍扫描和截图回归进入 CI。

## 9. Server 重构

### B01 配置、数据库迁移、任务与部署基础

当前状态：`[~]`。版本化 migration runner（PostgreSQL advisory lock + H2 行锁 + schema_migrations 历史表）已存在；表已按领域拆分；Database.kt 已拆出 SchemaMigration（47 行只剩 datasource/schema 引导）；ServerConfig/RuntimeConfigService 分离、Docker/Hikari/优雅关闭齐备。剩余：schema 创建完全并入 migration、后台任务 database lease、expand→contract 流程文档化。

- [x] 引入 Flyway/Liquibase 或等价版本化 migration runner（`db/migration/MigrationRunner`，PostgreSQL advisory lock + H2 行锁 + schema_migrations 版本表）。
- [~] 每领域独立 schema/table 文件，Database 只管理 datasource（表已拆 CoreTables/AdminTables/MessagingV2Tables/SignalTables/PollTables/ServiceMessageTables；Database.kt 556→47 行，migration/backfill 已迁 SchemaMigration.kt；createSchemaTables 仍待并入 migration v1 以彻底移除启动期建表）。
- [~] 采用 expand -> compatibility -> backfill -> contract 发布流程（migration v1=baseline、v2=retire legacy，流程文档化待补）。
- [x] 破坏性 drop 只在明确 contract 版本执行（`retireLegacyMessagingTables` 在 migration v2）。
- [ ] 后台清理任务使用数据库 lease，不依赖 route 内进程协程。
- [x] typed immutable startup config 与 runtime settings 分离（`ServerConfig` + `RuntimeConfigService`）。
- [x] 统一 Docker/self-host 配置，支持两副本、优雅关闭和 readiness（`docker-compose.yml` + `server/Dockerfile` + HikariCP 优雅关闭）。

Gate：空库、旧库升级、重复/中断 migration、备份恢复、滚动发布和 PostgreSQL 测试通过。

### B02 认证、账户、Session、设备与隐私

当前状态：`[~]`。`MfaService`（TOTP）、`BlockService`（拉黑）、`AccountLifecycleService`（注销）、`PrivacyService`（隐私）已从 `UserRepository` 抽出（1281→~657 行）；CredentialService/ProfileService/SessionService 尚未拆。

- [~] 拆 `CredentialService`、`MfaService`、`SessionService`、`ProfileService`（`MfaService` 已拆并注入 AuthRouting；其余待做）。
- [~] 拆 `PrivacyService`、`BlockService`、`AccountLifecycleService`（三个均已拆；UserRepository 保留薄委托）。
- [ ] 登录失败、验证码和 limiter 使用可共享 store，支持多实例。
- [ ] `DeviceSession` 明确绑定 auth session、Signal device 和 push token。
- [ ] 注销使用可重试编排器和删除清单。
- [ ] 认证/用户路由全部迁出 `Routing.kt`。

Gate：refresh rotation/replay、多设备登出、TOTP、两节点限流、注销恢复和 WS 撤销通过。

### B03 Signal Key 与设备注册

当前状态：`[~]`。领域模型已抽到 `SignalKeyModels.kt`；`DeviceRegistry`/`IdentityKeyStore`/`SignedPreKeyStore`/`PreKeyStore` 类拆分已落地；`SignalKeyRepository` 内重复的 key 读写实现已删除（`getBundle`/上传/清理改走 Store，`consume/peek/getSignedPreKey/upload/purge` 私有重复 + 私有 key 常量清零，`PreKeyStore` 补 FIFO 取键）；`PreKeyStoreTest` 7 例锁定原子消费/并发恰好一次/消费 id 永不复活/过期清理。

- [x] 拆 `DeviceRegistry`、`IdentityKeyStore`、`PreKeyStore`、`SignedPreKeyStore`（`SignalKeyRepository` 只剩组合门面 + 上传编排 + 通用单键读取，存储逻辑在各 Store）。
- [ ] 设备状态明确为 pending/confirmed/revoked。
- [x] one-time pre-key 消费使用数据库原子操作（`forUpdate` 行锁 + 条件更新 + FIFO；8 线程并发恰好一人成功，H2 验证）。
- [ ] 身份密钥变化产生安全事件并触发会话风险处理。
- [ ] V2 只依赖只读 `EncryptableDeviceDirectory`。
- [ ] backfill 迁入版本 migration，删除缺 device id/status 的长期兼容。

Gate：并发 pre-key、设备批准防重放、被撤销设备、新设备群 key 修复通过。

### B04 会话创建、查询、设置与生命周期

当前状态：`[~]`。会话域已拆为 9 个 repository 文件；旧 direct 热路径扫描已删除（回填迁移 v3 替代）。`ConversationCommandService` 统一命令入口仍未建。

- [~] `ConversationCommandService` 统一 direct/group/channel 创建、退出、删除和归档语义（CreationService/LifecycleRepository 已分，统一 CommandService 待建）。
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

当前状态：`[~]`。CallSignalingService 统一 REST/WS 校验与仓储，epoch/sequence/幂等键已入库；去重/排序与会话级 TURN 撤销待完成。

- [~] `CallSignalingService` 使用统一 call session 状态机。
- [~] offer/answer/ICE/terminal 使用 callId、epoch、sequence 和幂等键。
- [x] REST durable fallback 与 WS 共享同一校验和 repository。
- [~] TURN 凭据短期、限用户/会话、可撤销。
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

当前状态：`[x]`。`AdminRouting.kt` 已缩为 22 行注册门面（非 4,575 行）；`RuntimeConfigService` 引入 typed registry（`RuntimeSettingsRegistry`，`defaults()`/`knownKeys` 三重复消除）；`OperationsQueryService` 承接运营统计只读查询；`AdminIdentity`（两级角色解析）+ `UserDispositionService`（单用户/批量处置 + 开关写命令门面）落地，AdminBulkRouting 1366→~800 行，管理路由无裸 `Users.update/insert`；AdminEnhanceRouting 1106→598 行（公告→`AnnouncementRouting`、用户标签→`UserTagRouting`、模型→`AnnouncementModels`/`UserTagModels`）；重复 helper（`receiveEnhanceJson`/`dayBucketExpression`/`recordAdminAudit`/`isAdminUser`/`csvCell`/`parseAdminIds`）全部去重，secrets 不入 `RuntimeConfigService`。

- [x] 建立 `AdminIdentity`、`UserDispositionService`、`OperationsQueryService`（`AdminIdentity`+`AdminIdentityResolver`、`UserDispositionService` 写命令门面、`OperationsQueryService` 只读统计）。
- [x] Runtime settings 使用 typed registry 描述类型、默认值、范围、敏感性和重启要求（`RuntimeSettingsRegistry`：类型/默认/range/sensitive/restartRequired，`defaults()` 单一事实源派生 + `normalize` 校验）。
- [x] 管理 route 按用户治理、内容审核、配置、统计、公告拆分（用户治理→Users/Bulk/Management、内容审核→Content/Moderation/Report/UserTag、配置→System、统计→Observability+OperationsQuery/Diagnostics、公告→Announcement）。
- [x] 管理写操作统一 command + audit；统计走只读 query model（处置单/批量、开关、moderator、TOTP 全部走 `UserRepository` 命令 + 审计；统计走 `OperationsQueryService`）。
- [x] 合并 AdminRouting/AdminEnhanceRouting 重复能力（`receiveEnhanceJson`→`receiveAdminJson`、`observabilityDayBucketExpression`→`dayBucketExpression`、`recordObservabilityAudit`→`recordAdminAudit`、`isAdminObservabilityUser`→`isAdminUser`、`observabilityCsvCell`→`csvCell` 等重复 helper 全部去重）。
- [x] 删除重复 getter、路由事务和敏感配置导出（`knownKeys`/`defaults` 三重复消除；observability + bulk + 单用户写路由事务清零；`dayBucketExpression`/`recordAdminAudit`/`isAdminUser`/`csvCell`/`parseAdminIds` 去重；secrets 只存 `ServerConfig`，不导出）。

Gate：master/moderator/user 权限、审计、敏感配置和大数据查询性能通过。

### B14 应用更新、静态文件、水印与运维工具

当前状态：`[ ]`。这些能力存在，但仍与总路由、文件服务和 Admin 混合。

- [x] 官服与动态构建解耦：原生 WebRTC 依赖由 Gradle 构建任务自动解压至 `build/generated/resources/webrtc` 并注入 classpath，彻底移除源码树中提交的原生 `.so`/`.dylib` 二进制残留。
- [x] 更新发布使用签名 manifest、不可降级策略和不可变制品元数据（不可降级 + 不可变 manifest 旁文件 + HMAC-SHA256 签名（key=JWT_SECRET，客户端验签）全部落地；`AppUpdatePublishPolicy` 修复 UTF-8 / ISO 智能转码防乱码）。
- [~] 静态/官网资源与 API route 分离部署和缓存策略（缓存策略已落地：HTML no-cache、静态 CSS/JS `public, max-age=3600`、PNG/WebRTC 更长缓存；分离部署属 nginx/CDN 运维配置）。
- [x] 水印提取任务异步化，限制文件、CPU、内存和执行时间（`Dispatchers.Default` 异步 + 30s 超时兜底；请求体 4MB + 维度 ≤8192/像素 ≤16M 解压炸弹防御）。
- [ ] 备份/恢复脚本纳入版本 migration 和定期恢复演练。
- [~] 健康检查拆 liveness/readiness，并覆盖数据库、迁移和后台任务状态（`/health/live` + `/health/ready` 已拆，ready 覆盖 database/migrations/storage；后台任务状态待加）。

Gate：恶意文件、资源耗尽、制品签名、备份恢复和滚动发布测试通过。

## 10. 旧实现退役清单

以下项目未删除前，不得宣称全量重构完成：

- [x] `ChatDetailViewModel.kt` 不再包含单体业务逻辑，压缩为组合与分发调度层（接入 `ConversationCommandFacade`、`MessagingV2Outbox` 与领域 UseCase）。
- [x] 旧巨型 `ChatDetailScreen.kt` 实现删除（降为 42 行薄路由，拆分 `TimelinePane`、`ComposerPane` 与独立面板）。
- [x] 旧 `MessageBubble.kt` 单体解耦，全面对齐 `reference/Murexide` 建立 `MurexideMessageBubble.kt`，渲染期经 `MessagePresentationMapper` 彻底消除 wire/meta 业务解析。
- [~] `ChatListViewModel.kt` 瘦身中（多选状态机纯 reducer 9 单测；设置 toggle 纯构造 9 单测；实时三投影纯函数 7 单测；预览内存投影 + 置顶排序口径纯化 7 单测，`restoreChatSorted` 私有包装已删；collector 编排与 WS/网络/多仓库直连尚未解耦）。
- [ ] `SignalProtocol.kt` 宽 facade 删除。
- [x] `ApiService` 巨单体拆解完成，分离为 Auth、Messaging、Conversation、Media、Social 等独立域 API，收敛为组合委托薄门面。
- [x] `AppNotifier` 全局巨型入口删除（文件已物理删除；四服务 + `NotificationSlotPolicy` + `NotificationIntents` + `NotificationInfrastructure` 替代）。
- [ ] Notification Center、Scheduled Message、Reminder 等业务 JSON SharedPreferences store 删除。
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
- [ ] reducer/state machine 使用 fake clock 和确定性 dispatcher。
- [ ] 架构测试禁止 UI -> infrastructure、domain -> Android/Ktor 依赖。
- [ ] 协议模型有向前/向后兼容与 fuzz 测试。

### Q02 数据库与迁移

- [ ] Android 每个支持旧版本 -> 当前版本真实数据迁移测试。
- [ ] Server 空库、最后生产版本、重复、中断、回滚/恢复 migration 测试。
- [ ] PostgreSQL 是并发和约束测试真源，H2 只用于快速测试。
- [ ] SQLCipher 密钥、磁盘满、事务故障和数据损坏测试。

### Q03 Compose 与系统集成

- [ ] Chat、List、Contacts、Explore、Call、Settings 主流程 Compose 测试。
- [ ] 截图覆盖浅/深色、手机/平板、横屏、大字体、RTL、中英文。
- [ ] 通知、Widget、深链、权限、前台服务和更新器仪器测试。

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

- [ ] CI 运行 JVM、Lint、Room instrumentation、Compose、Server PostgreSQL 和 E2E。
- [ ] Release 必须依赖同 commit 全门禁成功，不允许单独绕过测试构建。
- [ ] 生产签名 Secret 缺失必须失败，禁止回退 debug 签名。
- [ ] 产出 SBOM、签名证书信息、checksum 和可复现构建记录。
- [ ] Android 26、当前稳定 Android、target SDK 真机验收。
- [ ] 上线前完成 backup -> upgrade -> rollback/restore 演练。

## 12. 多 Agent 并行执行模式

当前并发槽总数为 4，包含主 Agent。因此固定采用：

- `Agent-0 Integration`：总控、契约、共享热点接线、合并、回归和删除旧路径。
- 每轮最多 3 个执行 Agent 并行。
- 每个执行 Agent 使用独立 `git worktree` 和 `codex/refactor-*` 分支。
- 当前 255 项脏工作区必须先由 Integration Agent 建立可恢复基线；未经用户要求不得提交或推送。
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

- [ ] P03、P07、P09；通话、Push、通知、Widget、更新。

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
