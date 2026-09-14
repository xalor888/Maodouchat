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
   → 缺口：元数据与**全部**信封同事务、部分写入失败时整体回滚，没有直接用例

3. User presence is never consulted for delivery. WebSocket only emits `INBOX_AVAILABLE_V2`.
   → 验证：`MessagingV2RepositoryTest#send requires every current group device without checking online presence`
   → 缺口：WebSocket 只发 INBOX_AVAILABLE_V2 这一半没有用例

4. A device keeps pulling an envelope until it explicitly acknowledges it.
   → 验证：`MessagingV2RepositoryTest#group message is durable for offline devices and disappears only after ack`；`MailboxRetentionServiceTest#purge batch applies acknowledged unacknowledged and retired device policies`

5. The client stores an envelope before decrypting it and processes envelopes through one ordered
   coordinator, never from a screen or WebSocket collector.
   → 缺口：客户端「先落盘再解密」「单一有序协调器、不得从 UI/WebSocket 收集器处理」没有用例（MessagingV2InboxSynchronizer 无测试）

6. Decrypted domain data is committed before the inbox row becomes `ACK_PENDING`.
   → 缺口：「解密后的领域数据先提交、inbox 行才转 ACK_PENDING」没有用例（全仓没有任何测试提到 ACK_PENDING）

7. `ACK_PENDING` survives process death. Server ACK is idempotent, so a crash between remote ACK
   and local deletion converges on the next run.
   → 验证：`MessagingV2RepositoryTest#same encrypted request is idempotent but changed ciphertext conflicts`
   → 缺口：ACK_PENDING 跨进程存活没有用例

8. A recoverable failed envelope stops the current processing pass until retry. Permanent poison
   envelopes and exhausted retries are server-ACKed into a local `DEAD_LETTER` record so unrelated
   mailbox traffic continues without losing diagnostic state.
   → 验证：`MessagingV2InboxFailurePolicyTest#missing sessions remain recoverable until bounded retry budget is exhausted`；`MessagingV2InboxFailurePolicyTest#wrong-device and unsupported envelopes are terminal immediately`

9. Outbox plaintext exists only in the local SQLCipher database. Network requests contain only
   per-device ciphertext.
   → 验证：`MessagingV2OutboxPlaintextBoundaryTest#the wire request carries per-device ciphertext and never the local plaintext`；`MessagingV2OutboxPlaintextBoundaryTest#what gets staged for the wire is the prepared envelopes, not the plaintext`

10. Group membership changes invalidate prepared outbox ciphertext and require encryption against
    the new member revision.
   → 验证：`MessagingV2GroupControlPolicyTest#user data is re-prepared rather than discarded`；`GroupMessagingCoordinatorTest#epoch invalidation orders durable rows before key and attachment reconciliation`

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

17. "Send now" keeps its scheduled row until the text is durably staged in the v2 outbox. Optional
    bot/service follow-up failure cannot turn an already committed human message into a failed send.
   → 验证：`ConversationScheduledMessageDispatcherTest#missing chat is rejected without staging`
   → 缺口：「bot/service 后续失败不得把已提交的人类消息变成发送失败」没有用例

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
   → 缺口：账号代际（account-generation）作用域、以及切号时停掉旧请求，没有直接用例

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
   → 缺口：「清空历史先为每条消息落墓碑再取消 worker」没有用例

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
