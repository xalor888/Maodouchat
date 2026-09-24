# Messaging V2 Architecture

Messaging v2 replaces screen-owned delivery with a durable, device-addressed pipeline. WebSocket
message send/status/receive commands have been removed; it now carries only wake-up and ephemeral
control traffic. New code must not add dependencies from `messaging/v2` back into chat UI classes.

## Invariants

1. A send is accepted only when the encrypted target set exactly matches every confirmed device
   with a complete Signal bundle in the conversation membership snapshot. A partially registered
   device cannot block an otherwise healthy group; direct conversations still require a peer target.
   → 验证：`MessagingV2RepositoryTest#send requires every current group device without checking online presence`；`MessagingV2RepositoryTest#snapshot includes offline confirmed devices and excludes current sender device`；`MessagingV2RepositoryTest#group send ignores confirmed device without complete key bundle`

2. The server atomically commits immutable message metadata and all per-device envelopes.
   → 验证：`MessagingV2RepositoryTest#v2 send commits uploaded attachment atomically`
   → 验证：`MessagingV2RepositoryTest#a failed attachment commit rolls back metadata and every envelope`

3. User presence is never consulted for delivery. WebSocket only emits `INBOX_AVAILABLE_V2`.
   → 验证：`MessagingV2RepositoryTest#send requires every current group device without checking online presence`
   → 验证：`MessagingV2DeliveryWakeupTest#a committed v2 send wakes the recipient with nothing but INBOX_AVAILABLE_V2`；`WsLegacyMessageProtocolRetiredTest#legacy websocket message commands are rejected`（反证：WS 不再接受发送命令）

4. A device keeps pulling an envelope until it explicitly acknowledges it.
   → 验证：`MessagingV2RepositoryTest#group message is durable for offline devices and disappears only after ack`；`MailboxRetentionServiceTest#purge batch applies acknowledged unacknowledged and retired device policies`

5. The client stores an envelope before decrypting it and processes envelopes through one ordered
   coordinator, never from a screen or WebSocket collector.
   → 验证：`MessagingV2InboxSynchronizerTest#an envelope is persisted before it is decrypted, by the single coordinator`

6. Decrypted domain data is committed before the inbox row becomes `ACK_PENDING`.
   → 验证：`MessagingV2InboxSynchronizerTest#an envelope is persisted before it is decrypted, by the single coordinator`；`MessagingV2InboxSynchronizerTest#a failed decryption never marks the row ACK_PENDING`

7. `ACK_PENDING` survives process death. Server ACK is idempotent, so a crash between remote ACK
   and local deletion converges on the next run.
   → 验证：`MessagingV2RepositoryTest#same encrypted request is idempotent but changed ciphertext conflicts`
   → 验证：`MessagingV2InboxSynchronizerTest#an acknowledgement that stays pending is re-sent idempotently`

8. A recoverable failed envelope stops the current processing pass until retry. Permanent poison
   envelopes and exhausted retries are server-ACKed into a local `DEAD_LETTER` record so unrelated
   mailbox traffic continues without losing diagnostic state.
   → 验证：`MessagingV2InboxFailurePolicyTest#missing sessions remain recoverable until bounded retry budget is exhausted`；`MessagingV2InboxFailurePolicyTest#wrong-device and unsupported envelopes are terminal immediately`

9. Outbox plaintext exists only in the local SQLCipher database. Network requests contain only
   per-device ciphertext.
   → 验证：`MessagingV2OutboxPlaintextBoundaryTest#the wire request carries per-device ciphertext and never the local plaintext`；`MessagingV2OutboxPlaintextBoundaryTest#what gets staged for the wire is the prepared envelopes, not the plaintext`
   → 验证：`SignalMessagingV2EnvelopePreparerTest#the plaintext goes into the cipher and never into the envelope`（加密准备器这一层的边界：明文进加密层、密文出信封）
   → 验证：`SignalMessagingV2EnvelopeProcessorTest#every decrypt failure is reported and nothing is committed`（解密侧的对应边界：解不开的信封必须抛错，绝不能变成「已入库」）
   → 验证：`SignalE2eeRoundTripTest#realX3dhSessionCarriesExactPlaintextBothDirections`（**真机/模拟器上的真实加密往返**：真 X3DH 建会话 → 对方解回逐字节相同原文 → 棘轮双向。这一层 JVM 单测覆盖不了，因为 libsignal 的 AAR 只带 Android JNI、没有 host 动态库；同文件另有四条反证：篡改一字节 / 第三方无会话 / 身份与签名不匹配 / 未建会话就加密）
   → 验证：`SignalE2eeRoundTripTest#wireEnvelopeNeverContainsPlaintext`（**生产** `SignalEnvelopeCodec` 产出的上线信封里既不含原文、也不含原文标记，但仍承载密文并被认成加密信封）
   → 验证：`PersistentSignalStoreRoundTripTest#realX3dhRoundTripThroughProductionStore`（**本仓库生产 store**：两个账号都用 `PersistentSignalProtocolStore`（Room 支撑）跑真 X3DH，并把「写穿是真的」钉进 DAO——identity/session 行确实落库、被消费的一次性 PreKey 行已删除）
   → 验证：`PersistentSignalStoreRoundTripTest#ratchetStateSurvivesStoreReloadAfterLoadPersistedState`（**跨重启存活**：新 store 实例 + `loadPersistedState()` 后仍能解开对端后续消息。反证 `#aReloadedStoreWithoutHydrationCannotDecrypt` 证明这条结论真的来自持久化回填——不回填的新实例必须解不出来，回填后同一实例必须解得出来）
   → 验证：`PersistentSignalStoreRoundTripTest#aCorruptSessionRowIsDroppedAndDecryptionFailsLoudly`
   → 验证：`SignalGroupSenderKeyRoundTripTest#groupSenderKeyRoundTripThroughProductionCipher`（**群消息 SenderKey 真往返**：生产 `SignalGroupSenderKeyManager`/`SignalGroupCipher` 建分发 → 安装 → 加密 → 解回逐字节相同原文，连发两条；并用生产 `SignalEnvelopeCodec` 断言群信封与分发信封都**不含原文**、且都被认成对应信封类型）
   → 验证：`SignalGroupSenderKeyRoundTripTest#aThirdPartyThatNeverInstalledTheDistributionCannotDecrypt`
   → 验证：`SignalDecryptInputMatrixTest#directEnvelopeEntryPointNeverLetsAThrowableEscape`
   → 验证：`SignalMessagingV2EnvelopeProcessorAssemblyTest#directEnvelopeIsDecryptedAndCommittedExactlyOnce`；`SignalMessagingV2EnvelopeProcessorAssemblyTest#aPolicyRejectedPayloadIsNotCommittedAndDoesNotThrow`；`SignalMessagingV2EnvelopeProcessorAssemblyTest#undecryptableEnvelopesCommitNothingAndFailWithTheirOwnName`；`SignalMessagingV2EnvelopeProcessorAssemblyTest#duplicateWithoutJournalIsNotCommittedTwiceAndFailsNamed`；`SignalMessagingV2EnvelopeProcessorAssemblyTest#duplicateWithJournalRecoversTheProjectionFromTheJournal`（**装配层**：驱动生产 `SignalMessagingV2EnvelopeProcessor.process(envelope)` 的完整路径——解密 → 内容策略 → 落库 sink。钉住「失败一行都不提交」以及**具名失败**（调用方据此分重试/死信），并覆盖策略拒绝不提交不抛错、重复信封不二次提交、以及有 journal 时从 journal 恢复投影的路径）
；`SignalDecryptInputMatrixTest#deviceCiphertextEntryPointNeverLetsAThrowableEscape`；`SignalDecryptInputMatrixTest#multiDeviceAndCrossTypeInputsNeverLetAThrowableEscape`（**解密契约的畸形输入矩阵**：每个返回 `DecryptResult` 的入口都只能用返回 `DecryptResult` 的方式结束，任何 `Throwable` 逃逸都算红；同一密文在**五个不同偏移**各翻一个 bit，并额外覆盖截断/空/非 base64/错误 version 与 algorithm/跨类型互喂/多设备信封指向不存在的本机设备。G20 用它复现并修掉了「多设备入口整条分类链缺失」以及统一了三个入口的分类——`AssertionError`（libsignal 把意外的 checked exception 包成它，`extends Error`）从此有唯一收口）
；`SignalGroupSenderKeyRoundTripTest#aGroupEnvelopeReplayedIntoAnotherGroupIsRejected`；`SignalGroupSenderKeyRoundTripTest#aTamperedGroupEnvelopeFailsAndNeverReturnsPlaintext`（未安装分发者解不出、跨群重放被拒、篡改必须收敛成 `DecryptResult.Failed`）
；`PersistentSignalStoreRoundTripTest#aPersistenceFailureIsRecordedOnWriteAndThrownOnRead`；`PersistentSignalStoreRoundTripTest#anotherAccountCannotDecryptTheSameConversation`（损坏行被丢弃且随后**大声失败**、持久化失败写路径记录/读路径抛出、账号作用域隔离）

10. Group membership changes invalidate prepared outbox ciphertext and require encryption against
    the new member revision.
   → 验证：`MessagingV2GroupControlPolicyTest#user data is re-prepared rather than discarded`；`GroupMessagingCoordinatorTest#epoch invalidation orders durable rows before key and attachment reconciliation`
   → 验证：`SignalGroupSenderKeyRoundTripTest#staleEpochEncryptionIsRefusedAfterInvalidation`（**真机/模拟器上的真群密码学**：`invalidateGroupSenderKey` 之后**不能再按旧 epoch 加密**，且失败原因必须是 `group_sender_key_not_distributed`——只断言「失败」不够，因为另一条机制也会让那条路径失败，探针实测过这一点）
   → 验证：`SignalGroupSenderKeyRoundTripTest#aFutureEpochEnvelopeIsRejected`（未来 epoch 的信封必须是 `DecryptResult.FutureEpoch`，不是被当成正常消息解掉）

11. Account restrictions, group mute, channel ownership, and bilateral blocks are enforced inside
    the same server transaction that validates device coverage and inserts envelopes.
   → 验证：`MessagingV2RepositoryTest#group snapshot and send exclude members blocked by sender`；`MessagingV2RepositoryTest#message restricted account cannot submit user mutations`；`MessagingV2RepositoryTest#muted member may still send receipts but not user mutations`；`MessagingV2RepositoryTest#channel member cannot submit data but may submit encrypted events`

12. Idempotent retries bypass new-message admission limits; only a newly accepted mutation consumes
   the per-user rate bucket.
   → 验证：`MessagingV2RepositoryTest#idempotent replay bypasses new message admission limit`

13. Group Sender Key coverage is considered complete only after the v2 server transaction has
    committed the Sender Key mailbox envelopes. Client-side coverage reports are telemetry, never
    delivery authority.
   → 验证：`SenderKeyCoveragePolicyTest#unknown status is not treated as covered`；`SenderKeyCoveragePolicyTest#all expected devices covered can reuse current sender key`

14. Missing group Sender Keys are repaired through durable encrypted `KEY_REQUEST` mailbox items.
    Recovery never requires both devices to share a live WebSocket window.
   → 验证：`MessagingV2RepositoryTest#durable sender key request is accepted for groups and rejected for direct chats`

15. A membership revision invalidates all prepared group data ciphertext. Old `SENDER_KEY` and
    `KEY_REQUEST` commands are discarded rather than relabeled with the new epoch.
   → 验证：`MessagingV2GroupControlPolicyTest#sender key controls are stale when membership revision changes`；`GroupMessagingCoordinatorTest#coverage retry accepts only current valid group commands`

16. Scheduled sends are always owned by an explicit account id. Workers, recurring reschedules,
    chat cleanup, and logout cleanup never infer ownership from whichever account is live later.
   → 验证：`TextOutboxSessionPolicyTest#account switch aborts`；`TextOutboxSessionPolicyTest#logout clears token aborts`；`ConversationScheduledMessageDispatcherTest#dispatcher uses deterministic scheduled id through facade`

17. "Send now" keeps its scheduled row until the text is durably staged in the v2 outbox. Once
    the human message is durably staged, later worker dispatch or UI cleanup failures cannot undo
    the staging or turn it into a failed send.
   → 验证：`ConversationScheduledMessageDispatcherTest#dispatcher uses deterministic scheduled id through facade`；`ConversationScheduledMessageDispatcherTest#missing chat is rejected without staging`；`ConversationScheduleCoordinatorTest#two-phase immediate send maintains row until completion or restoration`

18. A zero-target Sender Key status is complete only when this device already owns the current local
    Sender Key. This allows a single-device, single-member group to converge without an infinite
    redistribution loop while still minting its key before future members are added.
   → 验证：`GroupSenderKeyMaintenanceCoordinatorTest#single member zero-target coverage is complete when local key exists`；`GroupSenderKeyMaintenanceCoordinatorTest#zero target group mints local key once and becomes ready`

19. Automatic Sender Key maintenance deduplicates only work that is currently in flight. A completed
    epoch is never permanently suppressed because a newly confirmed device can require fresh coverage
    without changing group membership revision.
   → 验证：`GroupSenderKeyMaintenanceCoordinatorTest#same epoch automatic maintenance is single flight only while running`；`GroupSenderKeyMaintenanceCoordinatorTest#incomplete automatic coverage queues retry and may run again for same epoch`

20. An optimistic edit, revoke, delete, or reaction may roll back only if durable v2 outbox staging
    failed. Once the encrypted event is durable, cancellation or local SQLCipher/search/media
    projection failure is a convergence warning and must never resurrect the previous UI state.
   → 验证：`MessagingV2MutationFacadeTest#local projection failure after durable event is reported without undoing commit`；`MessagingV2MutationFacadeTest#postcommit cancellation is a projection warning not a precommit rollback signal`；`MessagingV2MutationFacadeTest#edit is durable in outbox before local projection changes`

21. Destructive local conversation cleanup is account-generation scoped and step-isolated. A
    failed cache, notification, or scheduler operation cannot skip later privacy cleanup, while an
    account switch stops the old request before it can touch the new session's state.
   → 验证：`MessagingV2MutationFacadeTest#terminal cleanup continues after an earlier local projection failure`
   → 验证：`ConversationLocalStateCoordinatorTest#account switch stops cleanup before touching later state`；`ConversationLocalStateCoordinatorTest#full deletion continues after isolated cache failure`

22. `DATA` and `EVENT` commands preserve their real SQLite enqueue order within one conversation.
    Sender Key distribution, key repair, and receipts may bypass blocked data commands so protocol
    repair cannot deadlock behind the message that needs it. Stale process-death claims return to a
    retry state before the next flush.
   → 验证：`MessagingV2OutboxOrderingPolicyTest#data and user events preserve conversation causality`；`MessagingV2OutboxOrderingPolicyTest#protocol controls and receipts may bypass blocked user data`

23. Delete and revoke are terminal database facts, not UI flags. Their encrypted event and local
    `message_mutation_tombstones` row commit in one Room transaction. A failed outbox insert cannot
    leave a false tombstone, and a committed terminal event cannot be followed by a recreated DATA
    row with the same message id.
   → 验证：`MessageTerminalStoreTest#delete removes media before room row`；`MessageTerminalStoreTest#revoked placeholder destroys content metadata and persists`

24. Normal send, retry, received DATA projection, scheduled send, quick reply, agent send, forwarding,
    and attachment finalization all consult the same terminal tombstone inside their message + outbox
    transaction. History clearing tombstones every current message before cancelling workers. Delete
    and revoke also converge media cache, search documents, attachment transfer state, notification
    center references, and the matching system notification.
   → 验证：`MessageTerminalStoreTest#delete removes media before room row`
   → 验证：`ConversationLocalStateCoordinatorTest#history tombstones are durable before attachment cancellation starts`；`MessageTerminalStoreTest#delete removes media before room row`

25. **A human V2 send keeps its submitted payload inside its own per-device envelope.** The server
    stores transport metadata (`messaging_v2_messages`: id/conversation/sender/kind/timestamps/
    request_digest) plus one opaque ciphertext per destination device (`messaging_v2_envelopes.
    ciphertext`). A human send must not copy that payload anywhere else — not into the chat-list
    preview, not into a `service_messages` body, not into any other column. Bot/service messages are
    the deliberate server-visible exception: the server generates and audits them, so their text is
    stored server-side on purpose.
   → 验证：`ServerPlaintextSweepTest#human v2 payload stays inside its own envelope and leaves existing preview alone`
   → 验证：`ServerPlaintextSweepTest#the sweep really can find plaintext that the server does store`（扫描器正对照）
   → 证据边界（G32/G33 审计收窄了 G11 的原始表述）：该用例只覆盖**一个进程内 H2 库、这条
   repository 路径**。它**不**证明「服务端全库不含人类明文」——日志、导出、备份、崩溃报告、
   反向代理、生产 PostgreSQL 的其它表都不在其扫描范围内。
   → 另一层证据（G17 补上）：**真实 Signal 加密往返**现在有 on-device 证据了，见第 9 条的
   `SignalE2eeRoundTripTest`（真 X3DH 建会话 → 对方解回逐字节相同原文 → 棘轮双向，另有四条反证）。
   所以「人类载荷在客户端确实被加密成只有目标设备能解的东西」不再是缺口。
   → 仍然没有证据（不得用上面几条冒充）：真实**双设备跨进程/跨网络**投递（第 26 条只覆盖服务端
   边界的 mailbox 语义）、日志/导出/备份/崩溃报告是否含明文、生产 PostgreSQL 的其它表、
   群 SenderKey 分发，以及 `SignalDirectCipher`/`SignalProtocol.initialize` 的完整装配路径
   （它依赖 `MaodouchatApp` 单例与真实 SQLCipher 库；G17 覆盖「真实 libsignal 原语 + 真实生产
   信封编码器」，G18 再覆盖「真实生产 store + 跨重启回填」，但两者都还没有把整条产品装配串起来）。
   → 备注：人类 V2 发送路径**完全不写** `chats.last_message`（实测
   `grep -rn lastMessage server/src/main/kotlin/com/maodouchat/server/messaging/v2/` 为 0 命中）；
   该列目前**只**由 `repository/ServiceMessageRepository.kt` 的 bot/service 发布路径写入。
   但这**不等于**该列对某个会话始终为空：群里只要有 bot 发过消息，预览里就是服务端可见的明文。

26. **A device's mailbox is per-device, survives being offline, and is acknowledged per device.** A
    message that was sent while the recipient had no live socket is still delivered: the recipient's
    device pulls it over `GET /api/v2/inbox` and receives the **byte-identical** ciphertext that the
    sender submitted — the server relays, it does not rewrite. Acknowledging one device's copy clears
    only that device's mailbox: both another account's device and the **same account's** other device
    keep their own copies. An acknowledgement is authorization-scoped: knowing another device's
    `envelopeId` (which is not secret) must not let a caller clear a mailbox it does not own.
   → 验证：`MessagingV2TwoDeviceDeliveryTest#an offline device pulls the exact ciphertext and its ack keeps the sibling copy`
   → 验证：`SignalMessagingV2EnvelopeProcessorAssemblyTest#senderKeyDistributionInstallsWithoutCommitting`；`SignalMessagingV2EnvelopeProcessorAssemblyTest#aStaleDistributionIsSkippedAndTheGroupMessageStaysUndecryptable`（装配层安装 SenderKey：装成功后才解得出群消息；**epoch 比当前 revision 旧的分发被跳过**，其群消息因此保持不可解并触发修复回调——对应第 10 条的陈旧性保护在装配层的落地）

## Ownership Boundaries

- `server/messaging/v2`: validates membership/device coverage and owns durable device mailboxes.
- `MessagingV2Runtime`: process-scoped lifecycle and Inbox/Outbox convergence only. Screens cannot
  start it or trigger transport synchronization.
- `MessagingV2Outbox`: the only application API that may create or retry durable outbound commands.
  Terminal mutation events atomically persist their target tombstone with the outbox command.
- `MessagingV2MessageGateway`: atomically stages a user-visible local message and its DATA command,
  after rejecting terminal message ids. Search indexing and transport wake-up are post-commit work.
- `MessagingV2MutationFacade`: owns the durable commit boundary for encrypted edits, revokes,
  deletes, and reactions. Local timeline/search/media projection is post-commit convergence work.
- `ConversationMessageMutationCoordinator`: owns optimistic mutation single-flight ordering and
  rollback. It drops duplicate commands and lets authoritative terminal observations prevent stale
  failures from resurrecting a deleted or revoked message.
- `ConversationLocalStateCoordinator`: owns local history clearing, forgotten-lock cleanup, and
  post-leave conversation deletion. Chat detail, chat list, and stale-server-snapshot convergence
  share the same cleanup modes instead of maintaining independent database/media/notification lists.
- `OutgoingConversationResolver`: owns local-first conversation identity resolution, remote metadata
  hydration only when required, first direct-conversation creation, crypto readiness, and account
  session cancellation. Existing cached direct and group conversations remain enqueueable offline.
- `OutgoingMessageCoordinator`: owns conversation rebinding and the ordering of durable staging,
  failure persistence, retry, and post-commit callbacks. Composer, inline content, nudge, attachment
  and retry entry points must not reproduce this ordering.
- `MessagingV2TimelineProjector`: owns decrypted projection into local chat/message domain tables.
  It treats tombstones as authoritative against delayed or replayed DATA and performs terminal
  privacy cleanup before an envelope is acknowledged.
- `attachment/AttachmentSendWorkflow` + `AttachmentSendCoordinator`: own attachment intent
  normalization, session checks, optimistic local message persistence, and the atomic message +
  transfer handoff before WorkManager upload/finalization. Chat screens only provide intent and
  render progress; they do not construct attachment metadata or encrypt files.
- `scheduling/ConversationScheduleCoordinator`: owns scheduled-message/reminder storage and
  WorkManager ordering, account isolation, reschedule rollback, and durable send-now completion.
- `GroupMessagingCoordinator`: a platform-independent protocol coordinator that owns group Sender
  Key epoch invalidation, attachment ciphertext reconciliation after membership changes, account
  switch guards, and mailbox-backed coverage checks. It accepts capabilities through constructor
  injection and must not depend on Android, Room, WorkManager, API singletons, or `MaodouchatApp`.
- `AndroidGroupMessagingWiring`: the only adapter allowed to connect the pure group coordinator to
  Room, SignalProtocol, SenderKeyRetryManager, attachment WorkManager scheduling, and HTTP status
  fetches. Chat and group detail screens receive the same coordinator construction instead of
  rebuilding protocol ordering themselves.
- `GroupSenderKeyMaintenanceCoordinator`: owns manual/automatic coverage completeness, in-flight
  deduplication, durable retry enqueueing, and the single-member zero-target rule. Group detail UI
  projects its `Ready`, `Pending`, `Failed`, or `Skipped` outcome and keeps no epoch retry set.
- `GroupLifecycleCoordinator`: owns the group mutation commit boundary. Once the mutation request
  succeeds, chat refresh and Sender Key reconciliation are post-commit work and cannot convert the
  committed operation into a retryable mutation failure.
- `server/repository/GroupMembershipRepository`: owns member removal, role changes, ownership
  transfer, member-scoped v2 mailbox cleanup, audit insertion, and member revision changes under
  one chat-row lock. HTTP and bot routes consume `GroupLifecycleService` commit snapshots instead
  of coordinating these writes themselves.
- Other `app/messaging/v2` coordinators own polling, ordered processing, retry, ACK, encryption and
  receiver-side mutation authorization.
- `SignalProtocol`: cryptographic primitive provider; it must not perform HTTP, Room writes, UI
  updates, or WebSocket collection.
- `LocalMessageStore`: SQLCipher-backed decrypted timeline projection only. It must not perform
  network sends, remote history fetches, WebSocket handling, or retry scheduling.
- Chat UI: observes local domain tables and enqueues commands only through `MessagingV2Outbox`. It
  does not start/synchronize transport, send, decrypt, scan local `SENDING` rows, retry network work,
  or mark transport delivery directly.
- Server route modules are transport adapters. Conversation, group lifecycle/invitation/admin,
  Signal key, call signaling, friend graph, client preference, bot, and messaging-v2 routes are
  registered by domain modules; `Routing.kt` must not regain ownership of their transactions.

## Migration Order

1. Durable server inbox and authenticated send/pull/ACK API.
2. Local inbox/outbox tables and the single receive coordinator.
3. Direct-message envelope processor and sender.
4. Group sender-key distribution and group data messages through the same mailbox.
5. Receipts, edits, revokes, reactions, and disappearing-message events as encrypted v2 kinds.
6. Switch chat screens to the new domain facade.
7. Separate bot/service storage and moderation metadata from human E2EE content.
8. Remove legacy REST/WebSocket message commands and the server `MessageRepository`.
9. Migrate legacy bot rows into `ServiceMessages`, then physically drop `messages`,
   `read_receipts`, `message_reactions`, and `message_mutations`.

## Retired Surfaces

- Human message bodies are never stored or searched on the server.
- Online presence is not a delivery prerequisite for direct or group conversations.
- Server-authored disappearing-message expiry and attachment commit endpoints are removed.
- Reads, edits, revokes, reactions, and nudges are durable encrypted v2 events.
- Chat list previews and unread counts are derived from the local SQLCipher timeline.
- New server databases do not create any v1 message tables. Existing databases perform a one-way
  service-message migration and drop those tables during initialization.
- The chat detail screen no longer contains an independent history decryptor or deferred Signal
  session-repair loop. Receiver-side decryption is centralized in the v2 envelope processor;
  screens only observe the projected timeline.
- The former inactive-chat Sender Key ingest helper has been retired. Background/list processing
  must consume the durable v2 inbox instead of decrypting directly from a message preview.
- The WebSocket `REQUEST_SENDER_KEY` fan-out has been retired. Missing-key repair uses encrypted,
  device-addressed `KEY_REQUEST` mailbox items and therefore does not require simultaneous presence.

## 服务端明文面（非 E2EE 通道）

「服务端只存密文」是**不准确**的笼统说法。准确的说法是：**人类聊天消息正文**在服务端
只有密文；下列通道按设计就是明文，理解它们的存在与否是判断风险的前提：

| 通道 | 落库位置 | 说明 |
|------|----------|------|
| 系统/服务消息 | `service_messages.content`（TEXT） | 服务端自己生成的文本（入群提示、通话记录等），由 `ServiceMessagePublisher` 写入 |
| 动态与评论 | `posts` / `post_comments` | 公开内容，审核需要明文（AI 审帖只接这两处） |
| 投票/接龙/打卡条目 | `group_chain_entries`、`group_chains`、`group_checkins`、`group_pk_*` | 群内公开数据，非 E2EE 承载 |
| 举报备注 | `reports.description` / `reports.resolution_note`（各 varchar(800)） | 用户可粘贴任意文本（上限 800 字）；管理员可 CSV 导出 |
| 群昵称 / 群名 / 会话标题等元数据 | 多方 | 元数据不加密，见上表「元数据」相关不变量 |

与之对照，**人类消息正文**路径的可验证性质：入站 v2 正文只落
`messaging_v2_envelopes.ciphertext`；幂等键哈希只覆盖路由元数据与密文；管理后台的
「消息搜索」按 id/chatId/senderId/kind 过滤且**回显 contentPreview 为空**；导出不含消息列；
AI 审核只接动态/评论，聊天密文永不送模型。

新增落库字段前请对照本表：任何把用户自由输入的文本放进明文列的改动，
都必须在这里显式登记，否则「只存密文」的说法会再次悄悄失真。
