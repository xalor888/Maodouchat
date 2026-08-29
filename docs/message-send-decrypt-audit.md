# 消息发送与解密失败专项审计

> 审计日期：2026-08-25  
> 审计范围：Android 客户端消息发送、outbox、Signal 会话与消息落库；Ktor 服务端消息写入、WebSocket/REST 投递、附件提交与 Signal 设备目录  
> 当前状态：根因对应的修复已落在当前未提交工作树；本文记录行为不变量、修复映射、已知验证和剩余验证缺口  
> 隐私约束：不得记录消息明文、令牌、私钥或完整密钥材料

## 结论

这不是单一网络故障，而是发送确认、幂等重放、设备寻址和加密状态持久化之间的多处契约不一致。最危险的两条旧路径分别是：服务端已经向发送者确认、但收件人 fan-out 尚未发生；以及 libsignal 已推进内存 ratchet、但对应 Room 写入失败后仍继续处理。前者造成“发送方显示成功、对方收不到”，后者造成重启后的会话分叉和持续解密失败。

修复后的核心不变量是：

1. 消息发送按“数据库提交 -> 至少一次收件人 fan-out -> 发送者 `SENT` ACK”执行。
2. 同一客户端消息 ID 的精确重试必须重新投递，但 push/webhook 等一次性副作用只在首次写入时执行。
3. 本地可读明文不得被后到的服务端 wire/ciphertext 或解密失败占位覆盖；仍未解密时则保留原始 wire 供修复后重试。
4. ratchet、session、PreKey 或 SenderKey 的持久化失败必须 fail closed，不能在仅内存成功时继续发送或确认解密成功。
5. `PENDING` 设备不得进入对端设备发现、PreKey bundle 获取或加密 fan-out。

| 审计域 | 已定位根因 | 当前结果 |
| --- | ---: | --- |
| 消息发送 | 7 | 已在当前工作树修复，仍需真实双账号/双设备 E2E |
| 消息解密 | 6 | 已在当前工作树修复，仍需进程重启与设备迁移 E2E |
| 跨端设备信任 | 1 条强制不变量 | discovery、bundle、fan-out 均按 `CONFIRMED` 收口 |

## 发送失败根因与修复

### 1. 捕获了错误的超时异常类型

- 严重程度：High
- 旧行为：Kotlin coroutine 的 `withTimeout` 抛出 `TimeoutCancellationException`，旧代码却按另一种 `TimeoutException` 处理，因此超时未进入预期的死连接清理/IO 失败路径。
- 影响：单个阻塞 session 可能拖住 fan-out，错误还可能以取消异常越过调用层，使发送结果与实际投递状态不一致。
- 修复：`runWithWsSendTimeout` 精确捕获 `kotlinx.coroutines.TimeoutCancellationException`；若父协程已取消则保留取消语义，否则转为 `IOException`，由 session 清理路径处理。
- 证据：`server/src/main/kotlin/com/maodouchat/server/plugins/Sockets.kt:915`；`server/src/test/kotlin/com/maodouchat/server/plugins/SocketsSendTimeoutTest.kt`。

### 2. 发送者 ACK 早于收件人 fan-out

- 严重程度：Critical
- 旧行为：服务端先向发送者回 `SENT`，随后才向参与者发 `NEW_MESSAGE`。如果进程在两者之间退出，发送端会停止重试，而收件端永远没有收到实时消息。
- 影响：出现稳定的“我方已发送、对方没有消息”，且仅靠发送端状态无法恢复。
- 修复：WebSocket 路径先完成收件人 fan-out，并在首次写入时安排 push，最后才发送 `SENT` ACK。发送者 socket 失败不能阻止其他参与者的投递。
- 证据：`server/src/main/kotlin/com/maodouchat/server/plugins/Sockets.kt:546`、`:581`。

### 3. 精确重试只 ACK，不重放消息

- 严重程度：Critical
- 旧行为：当服务端已经提交消息、但 ACK 或 fan-out 丢失时，客户端用相同 ID 重试；旧幂等分支只返回既有消息或 ACK，没有重新 fan-out。
- 影响：幂等性避免了重复写入，却同时固化了“已提交但未投递”的空窗。
- 修复：WebSocket 与 REST 都让精确重试重新进入仓库幂等路径，并按至少一次语义重放 `NEW_MESSAGE`。客户端按消息 ID 去重；push 和 webhook 仍由 `wasExisting == false` 限制为首次副作用。
- 历史证据：旧 WebSocket/REST/`MessageRepository` 路径已由 messaging v2 替换并删除。

### 4. 附件精确重试未推进到 `COMMITTED`

- 严重程度：High
- 旧行为：附件已上传为 `UPLOADED`，消息首次请求在附件 commit 前后中断；精确重试走快捷返回，绕过附件状态推进。消息可见但收件人下载会失败。
- 影响：附件消息永久停在不可下载状态，继续重试也只能得到 ACK。
- 修复：附件消息写入和 `UPLOADED -> COMMITTED` 在同一数据库事务内完成；精确重试也调用同一仓库路径。附件尚未就绪时明确返回冲突，而不是伪造发送成功。
- 历史证据：旧服务端消息仓库已删除；当前收敛由 durable v2 inbox/outbox 保证。

### 5. 多设备 session 覆盖与 device ID migration

- 严重程度：Critical
- 旧行为：部分路径把 `DEFAULT_DEVICE_ID` 当成真实目标，或只验证“用户至少有一个加密结果”，没有验证每个已确认设备；本地 device ID 因冲突迁移后，旧 device ID 下建立的 outbound ratchet 还可能继续复用。
- 影响：同一用户的一台设备可收到并解密，另一台设备缺 envelope；更严重时，密文被加密到错误设备/session，接收端表现为持续 bad MAC、缺 session 或无法解密。
- 修复：设备发现解析为具体 `(userId, deviceId)`；单聊要求所有必需的已确认设备均有 envelope，群聊保留 best-effort fan-out 但记录缺失目标；自己的其他设备参与同步，当前设备不对自己建会话。device ID 迁移会持久化 pending 标记、标记旧 session 需要重建，并在全部修复完成后才清除标记。
- 证据：`app/src/main/java/com/maodouchat/crypto/MultiRecipientCoveragePolicy.kt`；`app/src/main/java/com/maodouchat/crypto/SignalSessionPolicy.kt`；`app/src/main/java/com/maodouchat/crypto/SignalDeviceIdRecoveryPolicy.kt`；`app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt:838`、`:1814`、`:2018`。

### 6. sealed sender 首次发送与 outbox 重试参数不一致

- 严重程度：High
- 旧行为：首次发送可能在取得 certificate 后使用 `sealedSender=true`，但本地 outbox 快照仍保留 `false`，或重试时缺少相同 certificate。服务端把 sealed sender 标志视为消息 ID 的不可变身份字段，因此相同 ID 的重试会变成 `MESSAGE_ID_CONFLICT`。
- 影响：消息在 ACK 丢失或断线恢复后无法完成 outbox，界面长期停在发送中或转失败。
- 修复：发送内容先写入 durable v2 outbox，由唯一的 outbox coordinator 准备并重试不可变的设备密文集合；界面不再保存或重放一套独立的发送参数。证书或会话暂不可用时保留队列项并退避重试，不再切换成另一种 wire 身份。
- 证据：`app/src/main/java/com/maodouchat/messaging/v2/MessagingV2Outbox.kt`；`app/src/main/java/com/maodouchat/messaging/v2/MessagingV2OutboxCoordinator.kt`；`app/src/main/java/com/maodouchat/messaging/v2/SignalMessagingV2Adapter.kt`。

### 7. Signal store 持久化失败被静默吞掉

- 严重程度：Critical
- 旧行为：libsignal callback 先修改内存 map，Room 写入失败后只记录日志或继续返回。当前进程看似可发送，重启后磁盘状态却停在旧 ratchet。
- 影响：发送方与接收方 ratchet 永久分叉；后续消息可能发送成功但对方无法解密。该问题同时影响 session、PreKey、SenderKey 和身份信任记录。
- 修复：所有同步 DAO 写入通过统一包装记录并抛出 `SignalStorePersistenceException`；关键 libsignal 操作后再次检查 store 健康度。发生持久化错误时撤销本地“crypto ready”资格并停止发送，避免产生无法在重启后延续的密文。
- 证据：`app/src/main/java/com/maodouchat/crypto/PersistentSignalProtocolStore.kt:46`、`:56`；`app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt:92`、`:1526`、`:1533`、`:2246`。

## 解密失败根因与修复

### 1. 用关键词判断解密占位，误伤正常正文

- 严重程度：Medium
- 旧行为：只要正文包含“无法解密”“session”等关键词，就可能被当成内部失败占位。用户正常讨论加密问题时，正文会被过滤、覆盖或跳过索引。
- 修复：占位识别收口为已知历史占位字符串的完整匹配，统一 trim 和大小写归一化；含相同关键词但不是完整占位的正文保持可读。
- 证据：`app/src/main/java/com/maodouchat/crypto/DecryptPlaceholderPolicy.kt`；对应的 `DecryptPlaceholderPolicyTest.kt`、`DecryptFailureDisplayPolicyTest.kt`。

### 2. 服务端 wire/ciphertext 覆盖本地已解密明文

- 严重程度：High
- 旧行为：WebSocket、REST backlog 或状态刷新再次 upsert 同一消息时，服务端保存的 ciphertext 被当成较新快照，覆盖 Room 中已经成功解密的 plaintext。
- 影响：消息短暂可读，刷新列表、重连或同步后又变回“加密消息”或解密失败占位。
- 修复：统一 Room 合并策略按编辑版本、可读性和 wire 类型选择 content。已有可读明文优先于同版本 wire/占位；没有明文时优先保留原始 wire，而不是不可重试的占位；投递状态保持单调递增。
- 证据：本地明文只进入 SQLCipher `LocalMessageStore`，网络传输由 messaging v2 独立负责。

### 3. ratchet/session 持久化失败及 device ID migration

- 严重程度：Critical
- 旧行为：接收端完成解密后内存 ratchet 已前进，但数据库仍是旧状态；或者本机 device ID 已迁移，却复用了迁移前会话。重启、重复投递或下一条消息都会从错误状态继续。
- 修复：解密完成前检查 ratchet 写入是否持久化，失败时不把结果视为成功；迁移标记采用 fail-closed 恢复，所有迁移前 session 均需重新建立。迁移标记写入失败时宁可重复重建，也不恢复旧 ratchet。
- 证据：`app/src/main/java/com/maodouchat/crypto/PersistentSignalProtocolStore.kt`；`app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt:1466`、`:1567`、`:2018`。

### 4. consumed PreKey 被清理或复活

- 严重程度：Critical
- 旧行为：客户端初始化曾从包含已消费项的聚合 `KEY_PRE_KEYS` blob 恢复一次性 PreKey；服务端上传同 ID 时也可能覆盖或重新建立已消费项。已经在途的 PreKeyMessage 随后可能找不到原私钥，或两个会话复用同一个一次性 PreKey ID。
- 修复：客户端只把持久化 store 中仍存在的 `pre_key:*` 单行记录作为活跃真源，不再从旧聚合 blob 复活已消费项。服务端把消费操作改为事务内 `pre_key -> consumed_pre_key` tombstone，刷新消费时间，在保留期内禁止同 ID 被覆盖或复活；过期 tombstone 再由周期任务清理。
- 证据：`app/src/main/java/com/maodouchat/crypto/PersistentSignalProtocolStore.kt:122`；`app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt:201`；`server/src/main/kotlin/com/maodouchat/server/repository/SignalKeyRepository.kt:329`、`:392`、`:582`、`:745`。

### 5. `InvalidMessageException` 被错误终态化

- 严重程度：High
- 旧行为：libsignal 的 `InvalidMessageException` 被一概标记为不可恢复；但该父类也覆盖 bad MAC、ratchet 暂时不同步等可由 session 修复解决的情况，`DuplicateMessageException` 也属于其子类。
- 影响：一次乱序、重复或暂态 session 问题会永久跳过该 wire，后续补齐密钥也不再尝试。
- 修复：先单独识别 `DuplicateMessageException` 并按已处理去重；普通 `InvalidMessageException` 进入有上限的可恢复失败计数，保留原始 wire，允许 session 修复后重试。只有确定的序列化、编码或不支持 envelope 才进入终态。
- 证据：`app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt:1164`、`:1169`、`:1172`；`app/src/main/java/com/maodouchat/crypto/DecryptFailurePolicy.kt`。

### 6. 解密失败占位仍发送 `DELIVERED`

- 严重程度：High
- 旧行为：收到 wire 并展示失败占位后仍向服务端发送 `DELIVERED`。发送方看到已投递，会误以为对方已经获得可读内容，同时服务端状态推进后无法表达真实失败。
- 修复：网络 `STATUS_UPDATE(DELIVERED)` 仅在接收处理得到可读内容后发送；自己的消息、`SK_DIST`、`REVOKED` 和解密失败占位不发该回执。传输模型可继续使用本地接收状态，但不能据此提前产生服务端投递回执。
- 证据：`app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt:2339`、`:2357`。

## 客户端修复摘要

- 将设备级 envelope 覆盖作为单聊发送的硬条件，避免“一台设备成功”掩盖其他确认设备失败；群聊保持 best-effort，并保留失败目标供重试。
- 统一首次发送、附件 finalizer、定时消息和文本 outbox 的消息 ID、wire content、sealed sender 与重试决策。
- 以精确占位策略和统一 Room merge policy 保护本地明文，同时保留未解密 wire 的可重试性。
- Signal store 写失败统一 fail closed；加解密、session 建立和 SenderKey 安装均在返回成功前确认持久状态健康。
- device ID 迁移持久化标记并强制重建旧 session；当前设备发布状态不是 `CONFIRMED` 时，不把本地加密初始化视为完整可用。
- PreKey 以仍存活的逐项记录为真源，防止已消费密钥在重启或重传后复活。
- 多设备 bundle 接口成功返回空列表时保持为空；只有明确 HTTP 404 才回退旧单设备接口，网络、超时和鉴权错误继续 fail closed。
- 仅在解密成功后发送网络 `DELIVERED`，失败占位不再制造错误回执。

## 服务端修复摘要

- WebSocket 发送超时捕获 coroutine 的真实异常类型，并区分局部超时与父协程取消。
- 调整发送顺序为事务提交、收件人 fan-out、首次 push、发送者 ACK；精确重试使用至少一次 WS 重放。
- WebSocket 与 REST 共用仓库幂等判定；同 ID 不同 sender/chat/content/type/sealed 标志仍按冲突拒绝。
- 加密消息的精确重试按稳定路由元数据比较，允许正常的 PREKEY -> SIGNAL 重加密；compact 默认值会规范化，错误数值类型和非法 Base64 会被拒绝。
- 附件状态推进纳入消息仓库事务，首次发送和精确重试都保证 `COMMITTED` 后再报告成功。
- 设备 discovery 默认只返回 `CONFIRMED`；bundle 在事务和行锁内再次确认设备状态及完整关键密钥；群 SenderKey fan-out 使用确认设备与完整 bundle 的交集。
- 已消费一次性 PreKey 保留 tombstone，禁止上传重放将其覆盖或复活，并在保留期后清理。

## `PENDING` 设备不变量

`PENDING` 表示密钥包已上传、但设备尚未得到既有可信设备批准。它可以出现在设备管理界面中，但绝不能成为其他客户端的加密目标：

1. Discovery：`getDeviceIds(..., confirmedOnly = true)` 和普通设备信息查询只暴露 `CONFIRMED` 设备；缺失 `SignalDevices` 元数据的历史密钥行按 `PENDING` 处理。
2. Bundle：`getBundle` 在同一事务中锁定用户行并再次验证设备为 `CONFIRMED`，同时要求 identity、registration、signed PreKey 与签名完整；不满足时返回无 bundle。
3. Fan-out：群设备目标取“`CONFIRMED` 设备集合”和“具备完整 bundle 密钥类型的设备集合”的交集。单聊客户端也只对 discovery 返回的具体确认设备建立 session 和生成 envelope。
4. 本机状态：新设备若仍为 `PENDING`，客户端明确进入待批准状态，不把“密钥上传 HTTP 成功”误当作可收取加密消息。

主要实现位于 `server/src/main/kotlin/com/maodouchat/server/repository/SignalKeyRepository.kt:179`、`:197`、`:329`，以及 `app/src/main/java/com/maodouchat/crypto/SignalProtocol.kt:1917`。

## 验证记录

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| `:app:compileDebugKotlin` | 通过 | 最新客户端源码完整编译通过；仅有一条既有风格级冗余 safe-call 警告 |
| 服务端 `compileKotlin compileTestKotlin` | 通过 | 最新服务端源码与测试源码完整编译通过 |
| 客户端聚焦单元测试 | 通过 | 42 项，覆盖 Room 合并、sealed sender、outbox 瞬态故障和 device ID/legacy fallback 策略 |
| 服务端幂等策略测试 | 通过 | 9 项，覆盖 PREKEY -> SIGNAL、compact 默认值、路由身份、数值类型和 Base64 校验 |
| 既有定向回归 | 通过 | 已覆盖发送超时、WS/REST 幂等 fan-out、仓库幂等、Signal key 上传和最小路由 |
| `git diff --check` | 通过 | 最终文档与源码修改不存在空白错误 |
| 真实双账号/双设备 emulator E2E | 未执行 | 仍是本次修复最重要的剩余验证 |
| Git commit | 无 | 当前所有修复仍在未提交工作树 |

Lint 尚不能作为本轮回归结论，存在两项已知的既有阻塞：

- `local.properties` 中 Windows SDK 路径转义问题。
- `app/src/main/java/com/maodouchat/ui/screen/chatdetail/MediaCenterScreen.kt` 的 Compose `LocalContextGetResourceValueCall`。

## 建议的 E2E 验收矩阵

1. 两个账号、接收账号两个 `CONFIRMED` 设备：单聊文本、图片、文件在前台、后台和重连后都应在两台设备解密，且发送端状态最终一致。
2. 第三个设备保持 `PENDING`：它不得出现在对端 discovery/bundle/fan-out；批准后重新建 session，随后才能收到新消息。
3. 模拟“数据库已提交、fan-out/ACK 前进程退出”：以同一消息 ID 重试，只产生一条数据库记录，但重新向收件人投递。
4. 附件上传完成后在 commit/ACK 边界断线：相同 ID 重试后附件必须为 `COMMITTED`，收件人可下载和解密。
5. sealed sender 首次发送后丢 ACK：outbox 使用相同 sealed 参数重试，不出现 `MESSAGE_ID_CONFLICT`，也不降级为普通发送。
6. device ID 冲突并迁移后重启应用：迁移标记仍强制旧 session 重建；完成后两端新消息可连续解密。
7. 在 Signal DAO 写入处注入失败：客户端不得继续发出密文或发送 `DELIVERED`；恢复存储后通过明确重建/重试恢复。
8. 对可恢复 `InvalidMessageException` 先失败、修复 session 后重试：原始 wire 应成功解密；真正 malformed envelope 应有界停止，不形成热循环。

在上述 E2E 完成前，不能仅凭单元测试宣称多设备消息链路已经完成生产验证。
