package com.maodouchat.e2e

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.PersistentSignalProtocolStore
import com.maodouchat.crypto.SignalKeyExchange
import org.signal.libsignal.protocol.ecc.Curve
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.messaging.v2.MessagingV2Content
import com.maodouchat.messaging.v2.MessagingV2Event
import com.maodouchat.messaging.v2.MessagingV2EventAction
import com.maodouchat.messaging.v2.MessagingV2DomainSink
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.ApiMessagingV2ConversationSnapshotProvider
import com.maodouchat.messaging.v2.MessagingV2ArrivalNotifier
import com.maodouchat.messaging.v2.MessagingV2InboxSynchronizer
import com.maodouchat.messaging.v2.MessagingV2Outbox
import com.maodouchat.messaging.v2.MessagingV2TimelineProjector
import com.maodouchat.messaging.v2.SignalMessagingV2EnvelopePreparer
import com.maodouchat.messaging.v2.SignalMessagingV2EnvelopeProcessor
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.AuthResponse
import com.maodouchat.network.ConversationSnapshotV2Dto
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.network.PendingEnvelopeV2Dto
import com.maodouchat.network.SendMessageRequestV2
import com.maodouchat.network.TokenManager
import com.maodouchat.network.api.AuthApiClient
import com.maodouchat.network.api.ConversationApiClient
import com.maodouchat.network.api.MessagingApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 真客户端 ↔ 真服务端的端到端证据（G23 / M5 第一片）。
 *
 * 与 G22 的区别：G22 是**一个进程内**的装配（两个账号共用同一个内存库）。本文件跑在**模拟器**里，
 * 通过**真实 HTTP** 打一台**真的在宿主上运行的服务端进程**（由 `scripts/two-device-http-e2e.sh` 起停），
 * 客户端用的是本仓库自己的网络层（`AuthApiClient` / `MessagingApiClient` / `ConversationApiClient`）。
 *
 * **为什么登录/初始化只做一次（类级共享）**：服务端规则是「一个已确认设备同一时刻只能绑定一个活跃
 * 会话」。若每个用例各自重新登录并重新上传密钥，第二次上传会因 device 1 已被上一个会话占用而被拒
 * （DEVICE_ID_CONFLICT），新会话拿不到绑定，于是 v2 下的所有接口都回 409 DEVICE_NOT_READY。
 * 所以「登录 + bootstrap + 建会话 + 取快照」提升为**类级一次**的共享前置。
 *
 * 分层（避免把「脚本能起服务」说成「端到端通了」）：
 * 1. 模拟器能经真实 HTTP 摸到宿主上的服务端；
 * 2. 两个账号能用本仓库的登录客户端真登录；
 * 3. 两个账号各自完成生产 `SignalProtocol.initialize`（生成+上传密钥）并能取回对端 prekey bundle；
 * 4. 一条真加密消息走完 客户端 → 服务端 → 另一个客户端，并被生产 processor 解出原文。
 */
@RunWith(AndroidJUnit4::class)
class TwoAccountHttpRoundTripTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { encodeDefaults = true }

    private class RecordingSink : MessagingV2DomainSink {
        val commits = mutableListOf<Pair<MessagingV2InboxEntity, MessagingV2Content>>()
        val bodies get() = commits.map { it.second.body }
        override suspend fun commit(envelope: MessagingV2InboxEntity, content: MessagingV2Content) {
            commits += envelope to content
        }
    }

    /** 类级共享前置：同一批会话/设备/协议实例，避免重复绑定设备。 */
    private class Shared(
        val alex: AuthResponse,
        val alice: AuthResponse,
        val alexProtocol: SignalProtocol,
        val aliceProtocol: SignalProtocol,
        val chatId: String,
        val snapshot: ConversationSnapshotV2Dto,
        val groupId: String,
        val groupSnapshot: ConversationSnapshotV2Dto,
        val groupEpoch: Long,
        /** 同账号的**第二台已确认设备**（各自独立的 store 与登录会话）。 */
        val alexDeviceId: Int,
        val alex2: AuthResponse,
        val alex2Protocol: SignalProtocol,
        val aliceDeviceId: Int,
        val alice2: AuthResponse,
        val alice2Protocol: SignalProtocol,
    )

    private companion object {
        private var database: AppDatabase? = null
        private var shared: Shared? = null
        private val extraDatabases = mutableListOf<AppDatabase>()

        fun freshDatabase(): AppDatabase =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).build().also { extraDatabases += it }

        fun database(): AppDatabase =
            database ?: Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).build().also { database = it }

        fun useSession(auth: AuthResponse) {
            // ApiService 还缓存了一份内存态会话；只改 TokenManager 会让它把「换了身份」
            // 判成 SESSION_CHANGED。真实 App 切换账号时也是先清内存态再存新会话。
            ApiService.clearSessionTokens()
            TokenManager.getInstance(ApplicationProvider.getApplicationContext()).saveAuthSession(
                token = auth.token,
                refreshToken = auth.refreshToken,
                userId = auth.userId,
                accessTokenExpiresAt = auth.expiresAt,
                refreshTokenExpiresAt = auth.refreshExpiresAt,
            )
        }
    }

    @Before
    fun requireE2eServer() {
        val enabled = InstrumentationRegistry.getArguments().getString("e2eHttp")
        org.junit.Assume.assumeTrue(
            "需要真服务端：请用 scripts/two-device-http-e2e.sh 运行（会注入 e2eHttp=1 与服务端地址）",
            enabled == "1",
        )
    }

    private fun baseUrl(): String {
        val url = ApiConfig.BASE_URL
        assertTrue(
            "这个用例必须跑在注入的 E2E 服务端地址上，实际 BASE_URL=$url",
            url.contains("10.0.2.2") || url.contains("127.0.0.1") || url.contains("localhost"),
        )
        return url
    }

    /** 登录两个账号 → 各自 bootstrap → 建会话 → 取快照；**整个过程只做一次**。 */
    private fun ensureShared(): Shared = runBlocking {
        shared?.let { return@runBlocking it }
        val db = database()

        val alex = AuthApiClient.login("alex@example.com", "password123", "").getOrThrow()
        val alice = AuthApiClient.login("alice@example.com", "password123", "").getOrThrow()
        check(alex.userId == "u1" && alice.userId == "u2") {
            "种子账号应当是 u1/u2，实际 ${alex.userId}/${alice.userId}"
        }

        val alexProtocol = SignalProtocol(db.signalKeyDao(), db.identityTrustDao())
        val aliceProtocol = SignalProtocol(db.signalKeyDao(), db.identityTrustDao())
        useSession(alex)
        check(alexProtocol.initialize(alex.token, alex.userId)) { "alex Signal bootstrap 失败" }
        useSession(alice)
        check(aliceProtocol.initialize(alice.token, alice.userId)) { "alice Signal bootstrap 失败" }

        // 两个账号各注册**第二台已确认设备**。**必须放在取快照之前**：设备集合一变，
        // 之前取的快照就失效，服务端会以「设备列表已变化，请刷新密钥后重试」拒绝发送
        // （本轮实测撞到——这是产品的真实保护，不是测试问题）。
        val alexDeviceId = alexProtocol.context.localDeviceId
        val (alex2, alex2Protocol, _) = registerConfirmedSecondDevice(
            "alex@example.com", alex.userId, alexProtocol, alexDeviceId,
        )
        val aliceDeviceId = aliceProtocol.context.localDeviceId
        val (alice2, alice2Protocol, _) = registerConfirmedSecondDevice(
            "alice@example.com", alice.userId, aliceProtocol, aliceDeviceId,
        )

        // 服务端只允许**会话参与者**之间取 prekey bundle（反枚举规则），所以必须先建会话。
        useSession(alex)
        val chat = ConversationApiClient
            .createChat(alex.token, participantIds = listOf(alice.userId), isGroup = false)
            .getOrThrow()
        val snapshot = MessagingApiClient.getConversationSnapshotV2(alex.token, chat.id).getOrThrow()

        // 群会话：服务端只给会话参与者发 prekey bundle，所以群也必须先建好并让 B 接受邀请。
        val group = ConversationApiClient.createChat(
            alex.token,
            participantIds = listOf(alice.userId),
            isGroup = true,
            groupName = "E2E group",
        ).getOrThrow()
        useSession(alice)
        val invites = ApiService.getGroupInvitations(alice.token).getOrThrow()
        val invite = invites.firstOrNull { it.chatId == group.id }
            ?: error("alice 必须收到群邀请，实际 ${invites.map { it.chatId }}")
        ApiService.acceptGroupInvitation(alice.token, invite.id).getOrThrow()
        useSession(alex)
        val groupSnapshot = MessagingApiClient.getConversationSnapshotV2(alex.token, group.id).getOrThrow()

        Shared(
            alex, alice, alexProtocol, aliceProtocol, chat.id, snapshot,
            groupId = group.id,
            groupSnapshot = groupSnapshot,
            // 生产里 sender key 的 epoch 就是群 memberRevision（见 SignalMessagingV2Adapter）。
            groupEpoch = groupSnapshot.memberRevision,
            alexDeviceId = alexDeviceId,
            alex2 = alex2,
            alex2Protocol = alex2Protocol,
            aliceDeviceId = aliceDeviceId,
            alice2 = alice2,
            alice2Protocol = alice2Protocol,
        ).also { shared = it }
    }

    /**
     * 给同一账号注册**第二台设备**并走完**审批**。
     *
     * 实测到的形状：新会话 + 新 store 上调 `initialize` 会向服务端注册一台**新设备**（deviceId 是
     * 随机分配的，不是 1/2），此时它的状态是 `PENDING`；`initialize` 仍返回 true。
     * 审批 = 用一台**已确认**设备的身份私钥对固定载荷签名：
     * `"maodouchat-device-confirm:v1\n<userId>\n<approverDeviceId>\n<targetDeviceId>\n<targetIdentityKeyBase64>"`，
     * 再 POST `/api/keys/devices/{target}/confirm`（服务端 `DeviceRegistry.verifyDeviceConfirmationProof`）。
     */
    private suspend fun registerConfirmedSecondDevice(
        email: String,
        userId: String,
        approver: SignalProtocol,
        approverDeviceId: Int,
    ): Triple<AuthResponse, SignalProtocol, Int> {
        val session = AuthApiClient.login(email, "password123", "").getOrThrow()
        val db2 = freshDatabase()
        val protocol = SignalProtocol(db2.signalKeyDao(), db2.identityTrustDao())
        useSession(session)
        check(protocol.initialize(session.token, userId)) { "$email 第二台设备的 bootstrap 失败" }
        val deviceId = protocol.context.localDeviceId
        check(deviceId != approverDeviceId) {
            "第二台设备必须拿到不同的 deviceId，实际 $deviceId == $approverDeviceId"
        }

        val target = SignalKeyExchange.fetchDevices(session.token, userId, deviceId).getOrThrow()
            .firstOrNull { it.deviceId == deviceId }
            ?: error("服务端上找不到刚注册的设备 $deviceId")
        if (target.status != "CONFIRMED") {
            val payload = buildString {
                append("maodouchat-device-confirm:v1\n")
                append(userId); append('\n')
                append(approverDeviceId); append('\n')
                append(deviceId); append('\n')
                append(target.identityKey)
            }
            val signature = Base64.encodeToString(
                Curve.calculateSignature(
                    approver.context.identityKeyPair.privateKey,
                    payload.toByteArray(Charsets.UTF_8),
                ),
                Base64.NO_WRAP,
            )
            ApiService.confirmMyDevice(session.token, deviceId, approverDeviceId, signature).getOrThrow()
        }
        val confirmed = SignalKeyExchange.fetchDevices(session.token, userId, deviceId).getOrThrow()
            .first { it.deviceId == deviceId }
        check(confirmed.status == "CONFIRMED") { "设备 $deviceId 审批后仍不是 CONFIRMED：${confirmed.status}" }

        // **审批之后客户端还必须重新初始化一次**：服务端已 CONFIRMED，但本机 `devicePendingApproval`
        // 仍是 true，而 `isLocalCryptoReadyFor` = `!devicePendingApproval && storeReady`，
        // 于是这台设备**能收到密文却解不开**（实测表现为解密返回 Failed，底层 libsignal 其实成功了）。
        // 重新 `initialize` 会走 `verifyCurrentDevicePublication` 把状态刷新回来——这正是真机上的时序。
        check(protocol.initialize(session.token, userId)) { "审批后重新初始化失败" }
        check(!protocol.isDevicePendingApproval()) {
            "审批后重新初始化仍处于待审批状态，isLocalCryptoReadyFor=${protocol.isLocalCryptoReadyFor(userId)}"
        }
        return Triple(session, protocol, deviceId)
    }

    @Test
    fun theEmulatorCanReachTheHostedServerOverRealHttp() {
        val url = baseUrl()
        http.newCall(Request.Builder().url("$url/health/ready").get().build()).execute().use {
            assertEquals("服务端健康检查必须 200，实际 ${it.code}", 200, it.code)
            val body = it.body?.string().orEmpty()
            assertTrue("健康检查应当报告 ready：$body", body.contains("ready"))
        }
    }

    @Test
    fun twoDemoAccountsLogInThroughTheRealClientOverRealHttp() {
        baseUrl()
        val s = ensureShared()
        assertTrue("alex 必须登录成功（服务端种子用户）", s.alex.token.isNotBlank())
        assertTrue("alice 必须登录成功（服务端种子用户）", s.alice.token.isNotBlank())
        assertTrue("两个账号必须是不同用户", s.alex.userId != s.alice.userId)
    }

    @Test
    fun bothAccountsExchangeKeysOverRealHttp() = runBlocking {
        baseUrl()
        val s = ensureShared()
        // 服务端对 prekey bundle 有**产品自身的限流**：`allowPreKeyFetch` = 每 (requester,target)
        // 每分钟 10 次（`RoutingHelpers.allowPreKeyFetch`）。这个 E2E 类会发 20+ 条消息，很容易撞到。
        // 这里**遵守**它（等待后重试），而不是绕过或放宽服务端配置。
        suspend fun fetchRespectingRateLimit(token: String, target: String) =
            withBoundedRateLimitRetry { SignalKeyExchange.fetchPreKeyBundle(token, target) }

        useSession(s.alex)
        val aliceFetched = fetchRespectingRateLimit(s.alex.token, s.alice.userId)
        useSession(s.alice)
        val alexFetched = fetchRespectingRateLimit(s.alice.token, s.alex.userId)

        assertTrue(
            "必须能从服务端取到 alice 的 prekey bundle，实际失败：${aliceFetched.exceptionOrNull()}",
            aliceFetched.isSuccess,
        )
        assertTrue(
            "必须能从服务端取到 alex 的 prekey bundle，实际失败：${alexFetched.exceptionOrNull()}",
            alexFetched.isSuccess,
        )
        assertTrue("alice 的 identityKey 不能为空", !aliceFetched.getOrThrow().identityKey.isNullOrBlank())
        assertTrue("alice 的 signedPreKey 不能为空", !aliceFetched.getOrThrow().signedPreKey.isNullOrBlank())
        assertTrue("alex 的 identityKey 不能为空", !alexFetched.getOrThrow().identityKey.isNullOrBlank())
    }

    /**
     * 第 4 步（本目标核心）：一条真加密消息走完 客户端 → 服务端 → 另一个客户端。
     * A 用生产多接收者加密产出每设备密文，经**真实 POST** 交给服务端；B 经**真实 GET** 拉自己的
     * 收件箱，交给**生产 `SignalMessagingV2EnvelopeProcessor`** 处理，断言解出的正是原文；
     * 同时断言**服务端中转的那份载荷里不含原文**。
     */
    @Test
    fun anEncryptedMessageTravelsClientToServerToClient() {
        baseUrl()
        val s = ensureShared()

        // A 侧：生产多接收者加密（每个目标设备一份密文）。
        useSession(s.alex)
        val plaintext = "e2e-http-plaintext-${UUID.randomUUID()}"
        val contentJson = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(version = 2, type = "TEXT", body = plaintext),
        )
        val targetUserIds = s.snapshot.targets.map { it.userId }.distinct()
        assertTrue("快照里必须有目标设备，实际 ${s.snapshot.targets}", targetUserIds.isNotEmpty())

        val encrypted = runBlocking {
            s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = targetUserIds,
                plaintext = contentJson,
                payloadType = "DATA",
                includeCurrentUserDevices = true,
                requiredRecipientIds = targetUserIds.filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
        }
        // **线上约定**：`ciphertextType` 必须匹配 `^[A-Z0-9_-]{1,32}$`（服务端校验），而直发密码学
        // 常量是 `prekey`/`signal`（小写）。生产路径用 `SignalMessagingV2EnvelopePreparer` 的
        // `wireCiphertextType` 归一化成大写（这正是本轮 E2E 逼出来的修复），这里**直接调用那个
        // 生产函数**来构造线上字段——而不是在测试里自己 uppercase，否则这个修复就没有守卫。
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        assertEquals("prekey", encrypted.ciphertexts.first().ciphertextType)
        assertEquals("PREKEY", preparer.wireCiphertextType(encrypted.ciphertexts.first().ciphertextType))
        assertEquals("SIGNAL", preparer.wireCiphertextType("signal"))

        val envelopes = encrypted.ciphertexts.map {
            EncryptedDeviceEnvelopeRequestV2(
                recipientUserId = it.userId,
                recipientDeviceId = it.deviceId,
                ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                ciphertext = it.ciphertext,
            )
        }
        assertTrue("必须至少有一个收件人设备密文", envelopes.isNotEmpty())
        assertTrue("密文本体不得包含原文", envelopes.none { it.ciphertext.contains(plaintext) })

        val messageId = "e2e-http-${UUID.randomUUID()}"
        val sent = runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alex.token,
                SendMessageRequestV2(
                    id = messageId,
                    conversationId = s.chatId,
                    kind = "DATA",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = s.snapshot.memberRevision,
                    attachmentIds = emptyList(),
                    envelopes = envelopes,
                ),
            ).getOrThrow()
        }
        assertTrue("服务端必须接受这次发送，实际 envelopeCount=${sent.envelopeCount}", sent.envelopeCount > 0)

        // 反面：小写 `ciphertextType` 必须被服务端拒绝——这就是修复前生产客户端一直会撞到的 400。
        // 把这条钉住，才不会有人把归一化删掉而没被发现。
        val lowercaseRejected = runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alex.token,
                SendMessageRequestV2(
                    id = "e2e-http-lower-${UUID.randomUUID()}",
                    conversationId = s.chatId,
                    kind = "DATA",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = s.snapshot.memberRevision,
                    attachmentIds = emptyList(),
                    envelopes = encrypted.ciphertexts.map {
                        EncryptedDeviceEnvelopeRequestV2(
                            recipientUserId = it.userId,
                            recipientDeviceId = it.deviceId,
                            ciphertextType = it.ciphertextType,
                            ciphertext = it.ciphertext,
                        )
                    },
                ),
            )
        }
        assertTrue(
            "小写 ciphertextType 必须被服务端拒绝（这正是要归一化的原因），实际：${lowercaseRejected.getOrNull()}",
            lowercaseRejected.isFailure,
        )

        // B 侧：真实拉收件箱。
        useSession(s.alice)
        val inbox = runBlocking { MessagingApiClient.getPendingInboxV2(s.alice.token, 100).getOrThrow() }
        val mine = inbox.envelopes.filter { it.conversationId == s.chatId && it.messageId == messageId }
        assertTrue("alice 的收件箱里必须有这条消息（服务端按设备投递）", mine.isNotEmpty())
        assertTrue("服务端中转的载荷里不得出现原文", mine.none { it.ciphertext.contains(plaintext) })

        // B 侧：交给**生产 processor** 解密并落库。
        val sink = RecordingSink()
        val processor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = sink,
            groupRevisionProvider = { s.snapshot.memberRevision },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        runBlocking {
            for (e in mine) {
                processor.process(
                    MessagingV2InboxEntity(
                        envelopeId = e.envelopeId,
                        ownerUserId = s.alice.userId,
                        deviceId = s.aliceProtocol.context.localDeviceId,
                        sequence = e.sequence,
                        messageId = e.messageId,
                        conversationId = e.conversationId,
                        senderUserId = e.senderUserId,
                        senderDeviceId = e.senderDeviceId,
                        kind = e.kind,
                        groupRevision = e.groupRevision,
                        clientTimestamp = e.clientTimestamp,
                        serverTimestamp = e.serverTimestamp,
                        ciphertextType = e.ciphertextType,
                        ciphertext = e.ciphertext,
                    ),
                )
            }
        }
        assertTrue(
            "生产 processor 必须解出原文并落到 sink，实际提交=${sink.bodies}",
            sink.bodies.contains(plaintext),
        )
    }

    /** 这条消息（含密文）经过服务端后，B 的收件箱里属于这条消息的信封。 */
    private fun inboxFor(s: Shared, token: String, conversationId: String, messageId: String? = null) = runBlocking {
        MessagingApiClient.getPendingInboxV2(token, 100).getOrThrow().envelopes
            .filter { it.conversationId == conversationId && (messageId == null || it.messageId == messageId) }
    }

    private fun send(
        s: Shared,
        conversationId: String,
        kind: String,
        messageId: String,
        envelopes: List<EncryptedDeviceEnvelopeRequestV2>,
        revision: Long,
    ) = runBlocking {
        MessagingApiClient.sendMessageV2(
            s.alex.token,
            SendMessageRequestV2(
                id = messageId,
                conversationId = conversationId,
                kind = kind,
                clientTimestamp = System.currentTimeMillis(),
                groupRevision = revision,
                attachmentIds = emptyList(),
                envelopes = envelopes,
            ),
        ).getOrThrow()
    }

    private fun processorFor(s: Shared, sink: RecordingSink) = SignalMessagingV2EnvelopeProcessor(
        signalProtocol = s.aliceProtocol,
        domainSink = sink,
        groupRevisionProvider = { s.groupEpoch },
        onSenderKeyMissing = { _, _ -> },
        inboxDao = null,
    )

    private fun PendingEnvelopeV2Dto.forDevice(s: Shared, protocol: SignalProtocol) =
        forUser(s.alice.userId, protocol)

    private fun PendingEnvelopeV2Dto.forUser(ownerUserId: String, protocol: SignalProtocol) = MessagingV2InboxEntity(
        envelopeId = envelopeId,
        ownerUserId = ownerUserId,
        deviceId = protocol.context.localDeviceId,
        sequence = sequence,
        messageId = messageId,
        conversationId = conversationId,
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        kind = kind,
        groupRevision = groupRevision,
        clientTimestamp = clientTimestamp,
        serverTimestamp = serverTimestamp,
        ciphertextType = ciphertextType,
        ciphertext = ciphertext,
    )

    /** 收件箱给的是 DTO；processor 要的是收件箱实体（owner/device 由本地会话决定，不是服务端给的）。 */
    private fun PendingEnvelopeV2Dto.forAlice(s: Shared) = MessagingV2InboxEntity(
        envelopeId = envelopeId,
        ownerUserId = s.alice.userId,
        deviceId = s.aliceProtocol.context.localDeviceId,
        sequence = sequence,
        messageId = messageId,
        conversationId = conversationId,
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        kind = kind,
        groupRevision = groupRevision,
        clientTimestamp = clientTimestamp,
        serverTimestamp = serverTimestamp,
        ciphertextType = ciphertextType,
        ciphertext = ciphertext,
    )

    /**
     * ① **群消息 SenderKey**：A 先发 sender key 分发信封（`kind=SENDER_KEY`），再发群文本；
     * B 按 **sequence 顺序**处理——分发只安装不提交，群文本必须解出原文并提交；两个 wire 载荷都不含原文。
     */
    @Test
    fun aGroupSenderKeyDistributionAndGroupTextTravelThroughTheServer() {
        baseUrl()
        val s = ensureShared()
        val plaintext = "e2e-group-${UUID.randomUUID()}"

        // A 先本地建 sender key 分发，并把它**加密成直发信封**发给群成员（生产路径：群控类 kind 走多接收者加密）。
        useSession(s.alex)
        val distribution = s.alexProtocol.createGroupSenderKeyDistribution(s.groupId, s.groupEpoch)
        val distributionJson = s.alexProtocol.envelopeCodec.buildSenderKeyDistributionEnvelope(
            groupId = s.groupId,
            distributionId = distribution.distributionId,
            message = distribution.message,
            epoch = s.groupEpoch,
            senderDeviceId = s.alexProtocol.context.localDeviceId,
        )
        val targetUserIds = s.groupSnapshot.targets.map { it.userId }.distinct()
        val encryptedDistribution = runBlocking {
            s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = targetUserIds,
                plaintext = distributionJson,
                payloadType = "SENDER_KEY",
                includeCurrentUserDevices = true,
                requiredRecipientIds = targetUserIds.filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
        }
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        val distributionMessageId = "e2e-dist-${UUID.randomUUID()}"
        val distributionEnvelopes = encryptedDistribution.ciphertexts.map {
            EncryptedDeviceEnvelopeRequestV2(
                recipientUserId = it.userId,
                recipientDeviceId = it.deviceId,
                ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                ciphertext = it.ciphertext,
            )
        }
        send(s, s.groupId, "SENDER_KEY", distributionMessageId, distributionEnvelopes, s.groupEpoch)

        // 再发群文本（群消息用 sender key 信封：ciphertextType = SENDER_KEY）。
        // 生产路径加密的是 `message.localPayload`，也就是 `MessagingV2Content` 的 JSON；
        // 直接加密裸字符串会让 processor 解析失败后**静默 return**（本轮踩过）。
        val groupContentJson = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(version = 2, type = "TEXT", body = plaintext),
        )
        val groupEnvelope = s.alexProtocol
            .encryptGroupTextEnvelope(s.groupId, groupContentJson, "TEXT", s.groupEpoch)
            .getOrThrow()
        val groupMessageId = "e2e-group-${UUID.randomUUID()}"
        val groupEnvelopes = s.groupSnapshot.targets.map {
            EncryptedDeviceEnvelopeRequestV2(
                recipientUserId = it.userId,
                recipientDeviceId = it.deviceId,
                ciphertextType = preparer.wireCiphertextType("SENDER_KEY"),
                ciphertext = groupEnvelope,
            )
        }
        send(s, s.groupId, "DATA", groupMessageId, groupEnvelopes, s.groupEpoch)

        // B 按 sequence 顺序拉取并处理。
        useSession(s.alice)
        val mine = inboxFor(s, s.alice.token, s.groupId)
        assertTrue("B 的群里应当有分发与群文本两个信封，实际 ${mine.map { it.kind }}", mine.size >= 2)
        assertEquals(
            "投递顺序必须按 sequence 升序稳定",
            mine.map { it.sequence }.sorted(),
            mine.map { it.sequence },
        )
        assertTrue("群 wire 载荷里不得出现原文", mine.none { it.ciphertext.contains(plaintext) })

        val sink = RecordingSink()
        val processor = processorFor(s, sink)
        runBlocking { mine.forEach { processor.process(it.forAlice(s)) } }

        assertTrue(
            "群文本必须解出原文并提交，实际提交=${sink.bodies}",
            sink.bodies.contains(plaintext),
        )
        assertEquals(
            "分发信封只安装 sender key，不产生提交",
            1,
            sink.commits.size,
        )
    }

    /**
     * ② **EVENT 分支**：合规事件提交且 action 正确；把事件载荷伪装成 `kind=DATA` 必须**不提交**
     * （内容策略把 EVENT 视为保留控制类型）。
     */
    @Test
    fun anEventCommitsButASmuggledControlPayloadDoesNot() {
        baseUrl()
        val s = ensureShared()
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )

        fun sendDirect(kind: String, contentJson: String, messageId: String) {
            useSession(s.alex)
            val encrypted = runBlocking {
                s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                    token = s.alex.token,
                    recipientIds = s.snapshot.targets.map { it.userId }.distinct(),
                    plaintext = contentJson,
                    payloadType = "TEXT",
                    includeCurrentUserDevices = true,
                    requiredRecipientIds = s.snapshot.targets.map { it.userId }
                        .filterNot { it == s.alex.userId }.toSet(),
                ).getOrThrow()
            }
            val envelopes = encrypted.ciphertexts.map {
                EncryptedDeviceEnvelopeRequestV2(
                    recipientUserId = it.userId,
                    recipientDeviceId = it.deviceId,
                    ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                    ciphertext = it.ciphertext,
                )
            }
            runBlocking {
                MessagingApiClient.sendMessageV2(
                    s.alex.token,
                    SendMessageRequestV2(
                        id = messageId,
                        conversationId = s.chatId,
                        kind = kind,
                        clientTimestamp = System.currentTimeMillis(),
                        groupRevision = s.snapshot.memberRevision,
                        attachmentIds = emptyList(),
                        envelopes = envelopes,
                    ),
                ).getOrThrow()
            }
        }

        // 合规事件：kind=EVENT + 合法 EVENT 载荷 → 必须提交，且 action 正确。
        val eventJson = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(
                version = 1,
                type = "EVENT",
                event = MessagingV2Event(action = MessagingV2EventAction.DELETE, targetMessageId = "target-1"),
            ),
        )
        val eventMessageId = "e2e-event-${UUID.randomUUID()}"
        sendDirect("EVENT", eventJson, eventMessageId)

        useSession(s.alice)
        val sink = RecordingSink()
        val processor = processorFor(s, sink)
        val eventEnvelopes = inboxFor(s, s.alice.token, s.chatId, eventMessageId)
        assertTrue("B 必须收到事件信封", eventEnvelopes.isNotEmpty())
        runBlocking { eventEnvelopes.forEach { processor.process(it.forAlice(s)) } }
        assertEquals("合规事件必须提交一次", 1, sink.commits.size)
        assertEquals(
            "提交的事件 action 必须是 DELETE",
            MessagingV2EventAction.DELETE,
            sink.commits.single().second.event?.action,
        )

        // 伪装：同样的事件载荷，但 kind=DATA → 内容策略必须拒绝（EVENT 是保留控制类型），不提交。
        val smuggleMessageId = "e2e-smuggle-${UUID.randomUUID()}"
        sendDirect("DATA", eventJson, smuggleMessageId)
        useSession(s.alice)
        val sink2 = RecordingSink()
        val processor2 = processorFor(s, sink2)
        val smuggleEnvelopes = inboxFor(s, s.alice.token, s.chatId, smuggleMessageId)
        assertTrue("B 必须收到伪装信封", smuggleEnvelopes.isNotEmpty())
        runBlocking { smuggleEnvelopes.forEach { processor2.process(it.forAlice(s)) } }
        assertEquals("把控制载荷伪装成 DATA 不得提交", 0, sink2.commits.size)
    }

    /**
     * ③ **ACK 走真 HTTP**：返回计数必须等于请求里属于自己的 id 数（客户端同步器依赖它），
     * 重复确认幂等，混入**别人设备的 id** 不得计入、也不得清掉对方的行。
     */
    @Test
    fun acknowledgingOverRealHttpIsIdempotentAndDeviceScoped() {
        baseUrl()
        val s = ensureShared()
        useSession(s.alex)
        val encrypted = runBlocking {
            s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = s.snapshot.targets.map { it.userId }.distinct(),
                plaintext = json.encodeToString(
                    MessagingV2Content.serializer(),
                    MessagingV2Content(version = 2, type = "TEXT", body = "ack-${UUID.randomUUID()}"),
                ),
                payloadType = "TEXT",
                includeCurrentUserDevices = true,
                requiredRecipientIds = s.snapshot.targets.map { it.userId }
                    .filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
        }
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        val messageId = "e2e-ack-${UUID.randomUUID()}"
        runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alex.token,
                SendMessageRequestV2(
                    id = messageId,
                    conversationId = s.chatId,
                    kind = "DATA",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = s.snapshot.memberRevision,
                    attachmentIds = emptyList(),
                    envelopes = encrypted.ciphertexts.map {
                        EncryptedDeviceEnvelopeRequestV2(
                            recipientUserId = it.userId,
                            recipientDeviceId = it.deviceId,
                            ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                            ciphertext = it.ciphertext,
                        )
                    },
                ),
            ).getOrThrow()
        }

        useSession(s.alice)
        val aliceIds = inboxFor(s, s.alice.token, s.chatId, messageId).map { it.envelopeId }
        assertTrue("B 必须收到这条消息", aliceIds.isNotEmpty())
        val first = runBlocking {
            MessagingApiClient.acknowledgeInboxV2(s.alice.token, aliceIds).getOrThrow()
        }
        assertEquals("acknowledged 必须等于请求里属于自己的 id 数", aliceIds.size, first.acknowledged)

        val second = runBlocking {
            MessagingApiClient.acknowledgeInboxV2(s.alice.token, aliceIds).getOrThrow()
        }
        assertEquals("重复确认必须幂等（后端返回「这些 id 已确认」而不是「本次改了几行」）", first.acknowledged, second.acknowledged)

        // 真正的「别的设备」id：反向再发一条（alice → alex），拿到落在 **alex** 设备上的信封。
        // 注：发件人自己的设备不在快照 targets 里（服务端会排除请求者本设备），单设备账号
        // 因此没有「自己的副本」可用——跨设备隔离只能靠另一个账号的信封来验。
        useSession(s.alice)
        val aliceSnapshot = runBlocking {
            MessagingApiClient.getConversationSnapshotV2(s.alice.token, s.chatId).getOrThrow()
        }
        val reverseMessageId = "e2e-ack-rev-${UUID.randomUUID()}"
        val reverseEncrypted = runBlocking {
            s.aliceProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alice.token,
                recipientIds = aliceSnapshot.targets.map { it.userId }.distinct(),
                plaintext = json.encodeToString(
                    MessagingV2Content.serializer(),
                    MessagingV2Content(version = 2, type = "TEXT", body = "reverse-${UUID.randomUUID()}"),
                ),
                payloadType = "TEXT",
                includeCurrentUserDevices = true,
                requiredRecipientIds = aliceSnapshot.targets.map { it.userId }
                    .filterNot { it == s.alice.userId }.toSet(),
            ).getOrThrow()
        }
        runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alice.token,
                SendMessageRequestV2(
                    id = reverseMessageId,
                    conversationId = s.chatId,
                    kind = "DATA",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = s.snapshot.memberRevision,
                    attachmentIds = emptyList(),
                    envelopes = reverseEncrypted.ciphertexts.map {
                        EncryptedDeviceEnvelopeRequestV2(
                            recipientUserId = it.userId,
                            recipientDeviceId = it.deviceId,
                            ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                            ciphertext = it.ciphertext,
                        )
                    },
                ),
            ).getOrThrow()
        }
        useSession(s.alex)
        val alexIds = inboxFor(s, s.alex.token, s.chatId, reverseMessageId).map { it.envelopeId }
        assertTrue("反向消息必须落到 alex 的设备上（这是「别的设备」的 id 来源）", alexIds.isNotEmpty())

        useSession(s.alice)
        val mixed = runBlocking {
            MessagingApiClient.acknowledgeInboxV2(s.alice.token, aliceIds + alexIds).getOrThrow()
        }
        assertTrue("别人的 id 不得计入自己的确认数", mixed.acknowledged <= aliceIds.size)

        useSession(s.alex)
        val alexStillThere = inboxFor(s, s.alex.token, s.chatId, reverseMessageId).map { it.envelopeId }
        assertTrue(
            "alice 的确认不得清掉 alex 的行，实际剩下 $alexStillThere",
            alexStillThere.containsAll(alexIds),
        )
    }

    /**
     * **同账号第二台设备**：注册 + 审批 + 多设备扇出 + 逐设备隔离。
     *
     * 断言：A 的另一台设备也收到自己那份（`includeCurrentUserDevices` 的真实语义）；B 的两台设备
     * **各自**只有自己那份；**只有对应设备解得开**（把设备 1 的密文喂给设备 2 必须失败）；
     * 设备 1 确认后设备 2 的行仍在且仍能解；每台设备对同一 messageId 只提交一次。
     */
    @Test
    fun aSecondDeviceGetsItsOwnCopyAndOnlyThatDeviceCanDecryptIt() {
        baseUrl()
        val s = ensureShared()
        val plaintext = "e2e-second-device-${UUID.randomUUID()}"
        val alex2DeviceId = s.alex2Protocol.context.localDeviceId

        // 取**当前**设备集合的快照：两台设备注册之后才取，才不会被「设备列表已变化」挡下。
        useSession(s.alex)
        val snapshot = runBlocking {
            MessagingApiClient.getConversationSnapshotV2(s.alex.token, s.chatId).getOrThrow()
        }
        assertEquals(
            "快照必须包含 alice 的两台设备",
            2,
            snapshot.targets.count { it.userId == s.alice.userId },
        )
        assertTrue(
            "快照必须包含 alex 的**另一台**设备（includeCurrentUserDevices 的真实语义）",
            snapshot.targets.any { it.userId == s.alex.userId && it.deviceId == alex2DeviceId },
        )
        assertTrue(
            "快照不得包含发件设备自己",
            snapshot.targets.none { it.userId == s.alex.userId && it.deviceId == s.alexDeviceId },
        )

        val encrypted = runBlocking {
            s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = snapshot.targets.map { it.userId }.distinct(),
                plaintext = json.encodeToString(
                    MessagingV2Content.serializer(),
                    MessagingV2Content(version = 2, type = "TEXT", body = plaintext),
                ),
                payloadType = "TEXT",
                includeCurrentUserDevices = true,
                requiredRecipientIds = snapshot.targets.map { it.userId }
                    .filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
        }
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        val messageId = "e2e-dev2-${UUID.randomUUID()}"
        runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alex.token,
                SendMessageRequestV2(
                    id = messageId,
                    conversationId = s.chatId,
                    kind = "DATA",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = snapshot.memberRevision,
                    attachmentIds = emptyList(),
                    envelopes = encrypted.ciphertexts.map {
                        EncryptedDeviceEnvelopeRequestV2(
                            recipientUserId = it.userId,
                            recipientDeviceId = it.deviceId,
                            ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                            ciphertext = it.ciphertext,
                        )
                    },
                ),
            ).getOrThrow()
        }

        // ① A 的**另一台**设备也拿到自己那份。
        useSession(s.alex2)
        val alex2Rows = inboxFor(s, s.alex2.token, s.chatId, messageId)
        assertEquals("A 的第二台设备必须收到**恰好一份**自己的副本", 1, alex2Rows.size)

        // ② B 的两台设备各自只有自己那份，而且是**不同**的信封/密文。
        useSession(s.alice)
        val alice1Rows = inboxFor(s, s.alice.token, s.chatId, messageId)
        useSession(s.alice2)
        val alice2Rows = inboxFor(s, s.alice2.token, s.chatId, messageId)
        assertEquals("B 的设备 1 只应有自己那一份", 1, alice1Rows.size)
        assertEquals("B 的设备 2 只应有自己那一份", 1, alice2Rows.size)
        assertTrue(
            "两台设备的信封必须不同（不是把同一份发给所有人）",
            alice1Rows.single().envelopeId != alice2Rows.single().envelopeId &&
                alice1Rows.single().ciphertext != alice2Rows.single().ciphertext,
        )
        assertTrue("两台设备的密文都不得含原文", (alice1Rows + alice2Rows).none { it.ciphertext.contains(plaintext) })

        // ③ 只有对应设备解得开。
        val sink1 = RecordingSink()
        val processor1 = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = sink1,
            groupRevisionProvider = { snapshot.memberRevision },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        runBlocking { alice1Rows.forEach { processor1.process(it.forAlice(s)) } }
        assertEquals("B 设备 1 必须解出原文且只提交一次", listOf(plaintext), sink1.bodies)

        val sink2 = RecordingSink()
        val processor2 = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.alice2Protocol,
            domainSink = sink2,
            groupRevisionProvider = { snapshot.memberRevision },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        runBlocking { alice2Rows.forEach { processor2.process(it.forDevice(s, s.alice2Protocol)) } }
        assertEquals("B 设备 2 必须能解开**自己那份**", listOf(plaintext), sink2.bodies)

        // 把设备 1 那份密文喂给设备 2 → 必须失败（不同 store / 不同 prekey）。
        val crossDevice = s.alice2Protocol.decryptDeviceCiphertext(
            senderId = alice1Rows.single().senderUserId,
            senderDeviceId = alice1Rows.single().senderDeviceId,
            ciphertextType = alice1Rows.single().ciphertextType,
            ciphertext = alice1Rows.single().ciphertext,
        )
        assertTrue(
            "设备 1 的密文不得被设备 2 解开，实际 $crossDevice",
            crossDevice !is com.maodouchat.core.crypto.DecryptResult.Success,
        )

        // ④ 设备 1 确认后，设备 2 的行仍在，且仍拉得到。
        useSession(s.alice)
        val acked = runBlocking {
            MessagingApiClient.acknowledgeInboxV2(s.alice.token, alice1Rows.map { it.envelopeId }).getOrThrow()
        }
        assertEquals("设备 1 只确认自己那一份", 1, acked.acknowledged)
        useSession(s.alice2)
        val alice2AfterAck = inboxFor(s, s.alice2.token, s.chatId, messageId)
        assertEquals("设备 2 的行不得被设备 1 的确认清掉", 1, alice2AfterAck.size)

        // 真正的设备隔离：用**设备 1 的会话**去确认**设备 2 的**信封——必须无效。
        // （只确认自己的 id 是测不到这条的：id 列表本身就已经限定了范围。G24 里用另一个账号验过同一件事，
        //  这里才是同账号跨设备的版本。）
        useSession(s.alice)
        val impostor = runBlocking {
            MessagingApiClient.acknowledgeInboxV2(s.alice.token, alice2Rows.map { it.envelopeId }).getOrThrow()
        }
        assertEquals("设备 1 不得确认设备 2 的信封", 0, impostor.acknowledged)
        useSession(s.alice2)
        assertEquals(
            "设备 2 的行不得被设备 1 冒充确认清掉",
            1,
            inboxFor(s, s.alice2.token, s.chatId, messageId).size,
        )
    }

    /**
     * 服务端 prekey bundle 限流（10 次/分钟/请求者-目标对）是**产品行为**；E2E 会发很多条消息，
     * 因此这里遇到 429 就等待后重试，而不是把它当失败或去改服务端配置。
     */
    private suspend fun <T> withBoundedRateLimitRetry(
        attempts: Int = 5,
        waitMs: Long = 20_000,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var last: Result<T> = block()
        repeat(attempts - 1) {
            val message = last.exceptionOrNull()?.message.orEmpty()
            if (last.isSuccess || "请求过于频繁" !in message) return last
            Thread.sleep(waitMs)
            last = block()
        }
        return last
    }

    /** 把 B 收件箱里遗留的未确认行确认掉，让「离线窗口」是干净的（测试隔离用，不是产品行为）。 */
    private fun drainInbox(s: Shared) {
        useSession(s.alice)
        val pending = runBlocking {
            MessagingApiClient.getPendingInboxV2(s.alice.token, 500).getOrThrow().envelopes
        }
        if (pending.isNotEmpty()) {
            runBlocking {
                MessagingApiClient.acknowledgeInboxV2(s.alice.token, pending.map { it.envelopeId }).getOrThrow()
            }
        }
    }

    /** A 的一条直发消息（真加密 → 真 POST）。 */
    private fun sendDirectTo(
        s: Shared,
        conversationId: String,
        body: String,
        messageId: String,
        tamperCiphertext: Boolean = false,
    ) {
        useSession(s.alex)
        val snapshot = runBlocking {
            MessagingApiClient.getConversationSnapshotV2(s.alex.token, conversationId).getOrThrow()
        }
        val encrypted = runBlocking {
            s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = snapshot.targets.map { it.userId }.distinct(),
                plaintext = json.encodeToString(
                    MessagingV2Content.serializer(),
                    MessagingV2Content(version = 2, type = "TEXT", body = body),
                ),
                payloadType = "TEXT",
                includeCurrentUserDevices = true,
                requiredRecipientIds = snapshot.targets.map { it.userId }
                    .filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
        }
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alex.token,
                SendMessageRequestV2(
                    id = messageId,
                    conversationId = conversationId,
                    kind = "DATA",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = snapshot.memberRevision,
                    attachmentIds = emptyList(),
                    envelopes = encrypted.ciphertexts.map {
                        val wire = preparer.wireCiphertextType(it.ciphertextType)
                        // 篡改：改掉密文中段一个字符（服务端只当中转，不校验内容）。
                        val ciphertext = if (tamperCiphertext) {
                            val mid = it.ciphertext.length / 2
                            val flipped = if (it.ciphertext[mid] == 'A') 'B' else 'A'
                            it.ciphertext.substring(0, mid) + flipped + it.ciphertext.substring(mid + 1)
                        } else {
                            it.ciphertext
                        }
                        EncryptedDeviceEnvelopeRequestV2(
                            recipientUserId = it.userId,
                            recipientDeviceId = it.deviceId,
                            ciphertextType = wire,
                            ciphertext = ciphertext,
                        )
                    },
                ),
            ).getOrThrow()
        }
    }

    /** 群控：sender key 分发（走多接收者加密，`kind=SENDER_KEY`）。 */
    private fun sendGroupControl(s: Shared, messageId: String) {
        useSession(s.alex)
        val distribution = s.alexProtocol.createGroupSenderKeyDistribution(s.groupId, s.groupEpoch)
        val distributionJson = s.alexProtocol.envelopeCodec.buildSenderKeyDistributionEnvelope(
            groupId = s.groupId,
            distributionId = distribution.distributionId,
            message = distribution.message,
            epoch = s.groupEpoch,
            senderDeviceId = s.alexProtocol.context.localDeviceId,
        )
        val targetUserIds = s.groupSnapshot.targets.map { it.userId }.distinct()
        val encrypted = runBlocking {
            s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = targetUserIds,
                plaintext = distributionJson,
                payloadType = "SENDER_KEY",
                includeCurrentUserDevices = true,
                requiredRecipientIds = targetUserIds.filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
        }
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        val envelopes = encrypted.ciphertexts.map {
            EncryptedDeviceEnvelopeRequestV2(
                recipientUserId = it.userId,
                recipientDeviceId = it.deviceId,
                ciphertextType = preparer.wireCiphertextType(it.ciphertextType),
                ciphertext = it.ciphertext,
            )
        }
        send(s, s.groupId, "SENDER_KEY", messageId, envelopes, s.groupEpoch)
    }

    /** 群文本（sender key 信封，`ciphertextType=SENDER_KEY`）。 */
    private fun sendGroupText(s: Shared, body: String, messageId: String) {
        useSession(s.alex)
        val contentJson = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(version = 2, type = "TEXT", body = body),
        )
        val groupEnvelope = s.alexProtocol
            .encryptGroupTextEnvelope(s.groupId, contentJson, "TEXT", s.groupEpoch)
            .getOrThrow()
        val preparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.alexProtocol,
            snapshotProvider = { _, _ -> error("E2E 不使用 snapshotProvider") },
        )
        val envelopes = s.groupSnapshot.targets.map {
            EncryptedDeviceEnvelopeRequestV2(
                recipientUserId = it.userId,
                recipientDeviceId = it.deviceId,
                ciphertextType = preparer.wireCiphertextType("SENDER_KEY"),
                ciphertext = groupEnvelope,
            )
        }
        send(s, s.groupId, "DATA", messageId, envelopes, s.groupEpoch)
    }

    /**
     * ① **离线重连与补投**（驱动**生产 `MessagingV2InboxSynchronizer`**，不是手写拉取循环）：
     * B 离线期间 A 在**两个会话**里交错发 12 条；B 重连后跑一次同步器 → 12 条**全部恰好提交一次**、
     * **每个会话内严格保序**；紧接着再同步一次 → **不产生任何新提交**。
     */
    @Test
    fun offlineCatchUpDeliversEveryMissedMessageOnceAndInOrder() {
        baseUrl()
        val s = ensureShared()
        drainInbox(s)

        // 两个会话：直发会话 + 群会话。（`createChat` 对同一对参与者会**去重**返回已有会话，
        // 所以「第二个直发会话」造不出来——实测撞到后才改用群会话。）
        val expected = linkedMapOf(s.chatId to mutableListOf<String>(), s.groupId to mutableListOf<String>())

        // 群里必须先有 sender key 分发；把它也放进离线窗口，由同步器按 sequence 先处理（只安装、不提交）。
        sendGroupControl(s, "offline-dist-${UUID.randomUUID()}")

        repeat(6) { i ->
            val body = "offline-$i-${UUID.randomUUID()}"
            if (i % 3 == 2) {
                sendGroupText(s, body, "offline-group-$i-${UUID.randomUUID()}")
                expected.getValue(s.groupId) += body
            } else {
                sendDirectTo(s, s.chatId, body, "offline-dm-$i-${UUID.randomUUID()}")
                expected.getValue(s.chatId) += body
            }
        }

        // 服务端**投递本身**必须按 sequence 升序（本地 claim 也按 sequence，两处独立；
        // 这条断言让「服务端乱序」成为可达的反证，否则本地排序会把服务端乱序掩盖掉）。
        useSession(s.alice)
        val rawOrder = runBlocking {
            MessagingApiClient.getPendingInboxV2(s.alice.token, 500).getOrThrow().envelopes
        }
        assertTrue("离线窗口里应当有 6 条消息 + 1 条分发，实际 ${rawOrder.size}", rawOrder.size >= 7)
        assertEquals(
            "服务端投递必须按 sequence 升序",
            rawOrder.map { it.sequence }.sorted(),
            rawOrder.map { it.sequence },
        )

        // B 重连：用**生产同步器**（专用 inbox 库 + 生产 processor + 真 clock）。
        // 注意 processor 与 synchronizer 必须共用**同一个** dao / sink，否则断言看的不是同一条链路。
        val syncDb = freshDatabase()
        val sink = RecordingSink()
        val processor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = sink,
            groupRevisionProvider = { s.groupEpoch },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = syncDb.messagingV2Dao(),
        )
        val syncer = MessagingV2InboxSynchronizer(syncDb.messagingV2Dao(), processor)
        useSession(s.alice)
        runBlocking { syncer.sync(s.alice.token, s.alice.userId, s.aliceDeviceId) }

        val allExpected = expected.values.flatten()
        assertEquals("6 条必须全部送达（分发信封只安装、不提交）", 6, sink.commits.size)
        assertEquals("每条必须恰好一次", allExpected.sorted(), sink.bodies.sorted())
        expected.forEach { (conversationId, bodies) ->
            val got = sink.commits.filter { it.first.conversationId == conversationId }.map { it.second.body }
            assertEquals("会话 $conversationId 内必须严格保序", bodies, got)
        }

        // ② 幂等重同步。
        val before = sink.commits.size
        runBlocking { syncer.sync(s.alice.token, s.alice.userId, s.aliceDeviceId) }
        assertEquals("重同步不得产生新提交", before, sink.commits.size)
    }

    /**
     * ④ **保序**：中间夹一条**必然解密失败**的信封时，本轮同步**不得提交它后面的行**
     * （`processAvailable` 失败即 `return false`），且失败的这条会被重试、最终**死信**，
     * 后面的行之后才能提交。用同步器**可注入的 clock** 跳过退避，不真等。
     */
    @Test
    fun aFailedEnvelopeBlocksLaterOnesUntilItDeadLetters() {
        baseUrl()
        val s = ensureShared()
        drainInbox(s)

        val body1 = "order-1-${UUID.randomUUID()}"
        val body3 = "order-3-${UUID.randomUUID()}"
        sendDirectTo(s, s.chatId, body1, "order-msg-1-${UUID.randomUUID()}")
        sendDirectTo(s, s.chatId, "tampered-${UUID.randomUUID()}", "order-msg-2-${UUID.randomUUID()}", tamperCiphertext = true)
        sendDirectTo(s, s.chatId, body3, "order-msg-3-${UUID.randomUUID()}")

        val syncDb = freshDatabase()
        val sink = RecordingSink()
        var fakeNow = System.currentTimeMillis()
        val processor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = sink,
            groupRevisionProvider = { s.groupEpoch },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = syncDb.messagingV2Dao(),
        )
        val syncer = MessagingV2InboxSynchronizer(syncDb.messagingV2Dao(), processor, clock = { fakeNow })
        useSession(s.alice)
        runBlocking { syncer.sync(s.alice.token, s.alice.userId, s.aliceDeviceId) }

        assertEquals("第一轮只应提交第 1 条（失败的不能让它后面的先上）", listOf(body1), sink.bodies)

        // 跳过退避反复重试，直到第 3 条被提交（或被判定为不可解）。
        var rounds = 0
        while (!sink.bodies.contains(body3) && rounds < 20) {
            fakeNow += 24L * 60L * 60L * 1000L
            runBlocking { syncer.sync(s.alice.token, s.alice.userId, s.aliceDeviceId) }
            rounds++
        }
        assertTrue(
            "第 3 条最终必须送达（失败的中间那条应当先死信），实际提交=${sink.bodies} rounds=$rounds",
            sink.bodies.contains(body3),
        )
        assertEquals("第 1 条不得被重复提交", 1, sink.bodies.count { it == body1 })
        assertTrue("死信需要跨过多次重试，rounds=$rounds 太少，可能没有真的走重试阶梯", rounds >= 5)
    }

    /**
     * **Sender Key repair 闭环**：分发丢失 → 触发修复请求 → 发送方重新分发 → 群消息恢复可解。
     *
     * 分层（哪段是生产代码、哪段是测试胶水）：
     * - **生产**：失败分支的 `onSenderKeyMissing` 回调；用生产 `MessagingV2Outbox.enqueueSenderKeyRequest`
     *   产出请求；用生产 `ApiMessagingV2ConversationSnapshotProvider` + 生产 preparer 准备并真 POST；
     *   接收方用生产 processor；**响应侧判定用生产 `MessagingV2TimelineProjector`**。
     * - **测试胶水**：把 `onSenderKeyRequest` 回调接到「重新分发」这个动作上（生产里接的是
     *   `SenderKeyRetryManager.redistributeNow`，它需要完整 app runtime）；动作本身仍调用生产的
     *   `createGroupSenderKeyDistribution` + 真 HTTP 发送。
     */
    @Test
    fun aMissingSenderKeyTriggersARequestAndRedistributionRestoresGroupDecryption() {
        baseUrl()
        val s = ensureShared()

        // 新群：alice 在这个群上**从来没有** alex 的 sender key。
        useSession(s.alex)
        val group = runBlocking {
            ConversationApiClient.createChat(
                s.alex.token, participantIds = listOf(s.alice.userId), isGroup = true, groupName = "repair-e2e",
            ).getOrThrow()
        }
        useSession(s.alice)
        val invite = runBlocking { ApiService.getGroupInvitations(s.alice.token).getOrThrow() }
            .first { it.chatId == group.id }
        runBlocking { ApiService.acceptGroupInvitation(s.alice.token, invite.id).getOrThrow() }
        useSession(s.alex)
        val snap = runBlocking { MessagingApiClient.getConversationSnapshotV2(s.alex.token, group.id).getOrThrow() }
        val epoch = snap.memberRevision

        // A 建好本地分发并加密一条群消息，但**故意不把分发发出去**。
        s.alexProtocol.createGroupSenderKeyDistribution(group.id, epoch)
        val failedMessageId = "repair-before-${UUID.randomUUID()}"
        val beforeContent = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(version = 2, type = "TEXT", body = "before-repair"),
        )
        val beforeEnvelope = s.alexProtocol.encryptGroupTextEnvelope(group.id, beforeContent, "TEXT", epoch).getOrThrow()
        send(
            s, group.id, "DATA", failedMessageId,
            snap.targets.map { EncryptedDeviceEnvelopeRequestV2(it.userId, it.deviceId, "SENDER_KEY", beforeEnvelope) },
            epoch,
        )

        // ① 制造缺失：alice 处理 → NoSession → 触发一次修复回调，且**一行都不提交**。
        // 回调里用**生产 writer** 把请求写进 outbox —— 这正是生产 `MessagingV2Runtime` 的接线方式，
        // 也让「没有回调就没有请求」这条链是真实的（否则 P1 反证毫无意义）。
        val repairDb = freshDatabase()
        val outbox = MessagingV2Outbox(
            database = repairDb,
            dao = repairDb.messagingV2Dao(),
            ownerUserId = { s.alice.userId },
            deviceId = { s.aliceDeviceId },
            wakeTransport = {},
        )
        useSession(s.alice)
        val beforeRows = inboxFor(s, s.alice.token, group.id, failedMessageId)
        assertEquals("alice 必须收到那条群消息", 1, beforeRows.size)
        val missing = mutableListOf<Pair<String, Long>>()
        val enqueued = mutableListOf<String>()
        val sinkBefore = RecordingSink()
        val aliceProcessor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = sinkBefore,
            groupRevisionProvider = { epoch },
            onSenderKeyMissing = { env, e ->
                missing += env.conversationId to e
                enqueued += runBlocking {
                    outbox.enqueueSenderKeyRequest(
                        conversationId = env.conversationId,
                        requestedSenderUserId = env.senderUserId,
                        groupRevision = e,
                        failedMessageId = env.messageId,
                    )
                }
            },
            inboxDao = null,
        )
        val failure = runCatching {
            runBlocking { beforeRows.forEach { aliceProcessor.process(it.forAlice(s)) } }
        }.exceptionOrNull()
        assertEquals("必须恰好触发一次修复回调（群 + epoch）", listOf(group.id to epoch), missing)
        assertEquals("拿不到 sender key 时一行都不许提交", 0, sinkBefore.commits.size)
        assertEquals("messaging_v2_no_session", failure?.message)

        // ② 把回调产出的那条请求，用**生产 preparer** 真 POST 上线。
        assertEquals("回调必须触发一次请求入队", 1, enqueued.size)
        val requestRow = runBlocking { repairDb.messagingV2Dao().getOutbox(enqueued.single(), s.alice.userId) }
            ?: error("生产 writer 必须把请求写进 outbox")
        assertEquals("KEY_REQUEST", requestRow.kind)

        useSession(s.alice)
        val alicePreparer = SignalMessagingV2EnvelopePreparer(
            signalProtocol = s.aliceProtocol,
            snapshotProvider = ApiMessagingV2ConversationSnapshotProvider { s.alice.userId },
        )
        val preparedRequest = runBlocking { alicePreparer.prepare(s.alice.token, requestRow) }
        assertTrue("请求必须真的被加密成信封", preparedRequest.envelopes.isNotEmpty())
        assertTrue(
            "请求的 wire 载荷不得含明文",
            preparedRequest.envelopes.none { it.ciphertext.contains("SENDER_KEY_REQUEST") },
        )
        runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alice.token,
                SendMessageRequestV2(
                    id = requestRow.messageId,
                    conversationId = group.id,
                    kind = requestRow.kind,
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = preparedRequest.groupRevision,
                    attachmentIds = emptyList(),
                    envelopes = preparedRequest.envelopes,
                ),
            ).getOrThrow()
        }

        // ③ 请求落到 A 的收件箱，A 用生产 processor 处理，内容里带正确的 attributes。
        useSession(s.alex)
        val requestRows = inboxFor(s, s.alex.token, group.id, requestRow.messageId)
        assertEquals("请求必须落到发送方的收件箱", 1, requestRows.size)
        assertEquals("请求的 groupRevision 必须是那个 epoch", epoch, requestRows.single().groupRevision)
        val alexSink = RecordingSink()
        val alexProcessor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.alexProtocol,
            domainSink = alexSink,
            groupRevisionProvider = { epoch },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        runBlocking { requestRows.forEach { alexProcessor.process(it.forUser(s.alex.userId, s.alexProtocol)) } }
        assertEquals("KEY_REQUEST 必须被内容策略接受并提交", 1, alexSink.commits.size)
        val (requestEnvelope, requestContent) = alexSink.commits.single()
        assertEquals("SENDER_KEY_REQUEST", requestContent.type)
        // 两个 attribute 键的权威值来自生产常量 `requestedSenderUserId` / `failedMessageId`
        // （companion 是 private，测试取不到，只能写字面量）。真正的守卫是下一步：如果键写错了，
        // 生产 TimelineProjector 的判定不会触发 → 「必须触发一次重新分发」断言就会红。
        assertEquals(
            "requestedSender 必须是发送方自己",
            s.alex.userId,
            requestContent.attributes["requestedSenderUserId"],
        )
        assertEquals(
            "failedMessageId 必须是那条失败消息",
            failedMessageId,
            requestContent.attributes["failedMessageId"],
        )

        // ④ 响应侧：用**生产 TimelineProjector** 判定，并只在判定通过时**真重新分发**。
        val app = ApplicationProvider.getApplicationContext<MaodouchatApp>()
        var redistributed = 0
        val onSenderKeyRequest: suspend (String, Long, String) -> Unit = { conversationId, requestedEpoch, requesterUserId ->
            assertEquals("触发重新分发的会话必须是那个群", group.id, conversationId)
            assertEquals("epoch 必须是请求里的那个", epoch, requestedEpoch)
            assertEquals("请求者必须是 alice", s.alice.userId, requesterUserId)
            redistributed++
            // 真重新分发：生产 API 建分发 + 真 HTTP 发送（SENDER_KEY 走群控多接收者加密）。
            useSession(s.alex)
            val distribution = s.alexProtocol.createGroupSenderKeyDistribution(group.id, requestedEpoch)
            val distributionJson = s.alexProtocol.envelopeCodec.buildSenderKeyDistributionEnvelope(
                groupId = group.id,
                distributionId = distribution.distributionId,
                message = distribution.message,
                epoch = requestedEpoch,
                senderDeviceId = s.alexProtocol.context.localDeviceId,
            )
            val targetUserIds = snap.targets.map { it.userId }.distinct()
            val encrypted = s.alexProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = s.alex.token,
                recipientIds = targetUserIds,
                plaintext = distributionJson,
                payloadType = "SENDER_KEY",
                includeCurrentUserDevices = true,
                requiredRecipientIds = targetUserIds.filterNot { it == s.alex.userId }.toSet(),
            ).getOrThrow()
            // 只需要生产函数做线上类型归一化；密文已经由上面的生产多接收者加密产出。
            val preparer = SignalMessagingV2EnvelopePreparer(
                signalProtocol = s.alexProtocol,
                snapshotProvider = ApiMessagingV2ConversationSnapshotProvider { s.alex.userId },
            )
            runBlocking {
                MessagingApiClient.sendMessageV2(
                    s.alex.token,
                    SendMessageRequestV2(
                        id = "skd_${UUID.randomUUID()}",
                        conversationId = group.id,
                        kind = "SENDER_KEY",
                        clientTimestamp = System.currentTimeMillis(),
                        groupRevision = requestedEpoch,
                        attachmentIds = emptyList(),
                        envelopes = encrypted.ciphertexts.map {
                            EncryptedDeviceEnvelopeRequestV2(
                                it.userId, it.deviceId,
                                preparer.wireCiphertextType(it.ciphertextType), it.ciphertext,
                            )
                        },
                    ),
                ).getOrThrow()
            }
        }
        val projector = MessagingV2TimelineProjector(
            app = app,
            messageStore = LocalMessageStore(repairDb.messageDao(), repairDb),
            ownerUserId = { s.alex.userId },
            notifier = MessagingV2ArrivalNotifier(app, { s.alex.userId }),
            sendDeliveryReceipt = {},
            onSenderKeyRequest = onSenderKeyRequest,
        )
        runBlocking { projector.project(requestEnvelope, requestContent) }
        assertEquals("生产判定必须触发一次重新分发", 1, redistributed)

        // ⑤（负面）生产判定必须**只在** `requestedSender == 自己` 且请求者不是自己时才触发。
        runBlocking {
            projector.project(
                requestEnvelope,
                requestContent.copy(
                    attributes = requestContent.attributes + ("requestedSenderUserId" to s.alice.userId),
                ),
            )
        }
        assertEquals("requestedSender 不是自己时不得触发重新分发", 1, redistributed)
        runBlocking {
            projector.project(
                requestEnvelope.copy(senderUserId = s.alex.userId),
                requestContent,
            )
        }
        assertEquals("自己发给自己的请求不得触发重新分发", 1, redistributed)
        runBlocking {
            projector.project(
                requestEnvelope.copy(groupRevision = 0L),
                requestContent,
            )
        }
        assertEquals("epoch 为 0 的请求不得触发重新分发", 1, redistributed)

        // ⑤ 恢复：alice 安装新分发后，随后一条群消息必须解出原文并提交。
        val afterMessageId = "repair-after-${UUID.randomUUID()}"
        val afterPlaintext = "after-repair-${UUID.randomUUID()}"
        useSession(s.alex)
        val afterEnvelope = s.alexProtocol.encryptGroupTextEnvelope(
            group.id,
            json.encodeToString(
                MessagingV2Content.serializer(),
                MessagingV2Content(version = 2, type = "TEXT", body = afterPlaintext),
            ),
            "TEXT",
            epoch,
        ).getOrThrow()
        send(
            s, group.id, "DATA", afterMessageId,
            snap.targets.map { EncryptedDeviceEnvelopeRequestV2(it.userId, it.deviceId, "SENDER_KEY", afterEnvelope) },
            epoch,
        )
        useSession(s.alice)
        val distributionRows = inboxFor(s, s.alice.token, group.id).filter { it.kind == "SENDER_KEY" }
        assertTrue("alice 必须收到重新分发的信封", distributionRows.isNotEmpty())
        val afterRows = inboxFor(s, s.alice.token, group.id, afterMessageId)
        assertEquals(1, afterRows.size)
        val sinkAfter = RecordingSink()
        val recoveringProcessor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = sinkAfter,
            groupRevisionProvider = { epoch },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        runBlocking {
            (distributionRows + afterRows).sortedBy { it.sequence }.forEach {
                recoveringProcessor.process(it.forAlice(s))
            }
        }
        assertEquals(
            "重新分发之后，群消息必须恢复可解（闭环成立的判据），实际提交=${sinkAfter.bodies}",
            listOf(afterPlaintext),
            sinkAfter.bodies,
        )
    }

    /** 用 jq 风格的原始 JSON POST（bot 凭证与 hint 路由没有客户端封装）。 */
    private fun postJson(url: String, token: String, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string().orEmpty())
        }
    }

    /**
     * **service/bot 明文分支**：这是唯一一条「服务端可见明文」的合法投递路径。
     *
     * 分层：
     * - **真 HTTP + 真凭证**：建 bot → 拿 `tokenOnce` → 拉 bot 进会话 → 用 **bot token** 调
     *   `POST /api/bot/sendSecretNewDeviceRiskHint`（该开关默认 true）→ 服务端经
     *   `ServiceMessagePublisher` 以 `SERVICE_PLAINTEXT` 写进设备邮箱。
     * - **接收侧**：生产 processor 的 `MessagingV2ServiceEnvelopePolicy` 是第二道防线，
     *   即使拿到「形状合法但来源不对」的信封也必须不提交。
     */
    @Test
    fun theBotServicePlaintextPathWorksWhileHumansCannotInjectService() {
        baseUrl()
        val s = ensureShared()
        drainInbox(s)

        // ① 专用群：**不能**用共享群——把 bot 拉进群会改群成员版本（实测 `群成员版本已变化:3`），
        // 从而让别的用例依赖的共享快照/epoch 失效。这里为服务消息另建一个群。
        useSession(s.alex)
        val botGroup = runBlocking {
            ConversationApiClient.createChat(
                s.alex.token, participantIds = listOf(s.alice.userId), isGroup = true, groupName = "service-e2e",
            ).getOrThrow()
        }
        useSession(s.alice)
        val botGroupInvite = runBlocking { ApiService.getGroupInvitations(s.alice.token).getOrThrow() }
            .first { it.chatId == botGroup.id }
        runBlocking { ApiService.acceptGroupInvitation(s.alice.token, botGroupInvite.id).getOrThrow() }

        // 建 bot（返回里带 tokenOnce）并拉进这个群。
        useSession(s.alex)
        val (createCode, createBody) = postJson(
            "${ApiConfig.BASE_URL}/api/bots",
            s.alex.token,
            """{"name":"E2E bot","username":"e2ebot${UUID.randomUUID().toString().replace("-", "").take(10)}"}""",
        )
        assertEquals("建 bot 必须成功，实际 $createCode / $createBody", 200, createCode)
        val bot = Json.parseToJsonElement(createBody).jsonObject
        val botId = bot["id"]!!.jsonPrimitive.content
        val botToken = bot["tokenOnce"]!!.jsonPrimitive.content
        assertTrue("bot id 必须形如 bot_*，实际 $botId", botId.startsWith("bot_"))
        assertTrue("必须拿到 bot 凭证", botToken.isNotBlank())

        // 服务端只允许把 bot 拉进**群聊**（实测 400「只能向群聊邀请机器人」），所以走共享群会话。
        val (inviteCode, inviteBody) = postJson(
            "${ApiConfig.BASE_URL}/api/chats/${botGroup.id}/bots",
            s.alex.token,
            """{"botId":"$botId"}""",
        )
        assertEquals("把 bot 拉进会话必须成功，实际 $inviteCode / $inviteBody", 200, inviteCode)

        // 用 **bot token** 触发服务端发布一条 SYSTEM 服务消息。
        val (hintCode, hintBody) = postJson(
            "${ApiConfig.BASE_URL}/api/bot/sendSecretNewDeviceRiskHint",
            botToken,
            """{"chatId":"${botGroup.id}"}""",
        )
        assertEquals("hint 路由必须成功，实际 $hintCode / $hintBody", 200, hintCode)
        val serviceMessageId = Json.parseToJsonElement(hintBody).jsonObject["messageId"]!!.jsonPrimitive.content

        // ② alice 真拉收件箱：形状与「载荷即明文」。
        useSession(s.alice)
        val serviceRows = inboxFor(s, s.alice.token, botGroup.id, serviceMessageId)
        assertEquals("服务消息必须落到 alice 的收件箱", 1, serviceRows.size)
        val serviceRow = serviceRows.single()
        assertEquals("kind 必须是 SERVICE", "SERVICE", serviceRow.kind)
        assertEquals("密文类型必须是 SERVICE_PLAINTEXT", "SERVICE_PLAINTEXT", serviceRow.ciphertextType)
        assertEquals("服务消息的设备号必须是 0", 0, serviceRow.senderDeviceId)
        assertTrue("发送者必须是 bot_*，实际 ${serviceRow.senderUserId}", serviceRow.senderUserId.startsWith("bot_"))
        assertTrue(
            "**这条载荷本身就是明文**（服务端可见）——这正是与端到端加密路径的区别，实际=${serviceRow.ciphertext}",
            serviceRow.ciphertext.contains("NDV:RISK"),
        )

        // 生产 processor 必须接受并提交它的明文内容。
        val serviceSink = RecordingSink()
        val serviceProcessor = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = serviceSink,
            groupRevisionProvider = { s.groupEpoch },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        runBlocking { serviceProcessor.process(serviceRow.forAlice(s)) }
        assertEquals("生产 processor 必须提交这条服务消息", 1, serviceSink.commits.size)
        assertTrue(
            "提交的内容必须含服务文案，实际=${serviceSink.commits.single().second}",
            serviceSink.commits.single().second.body.contains("NDV:RISK"),
        )

        // ③ 人类**不能**注入 SERVICE：普通 token 走 V2 发送路由。
        useSession(s.alex)
        val spoofMessageId = "spoof-service-${UUID.randomUUID()}"
        val spoof = runBlocking {
            MessagingApiClient.sendMessageV2(
                s.alex.token,
                SendMessageRequestV2(
                    id = spoofMessageId,
                    conversationId = s.chatId,
                    kind = "SERVICE",
                    clientTimestamp = System.currentTimeMillis(),
                    groupRevision = null,
                    attachmentIds = emptyList(),
                    envelopes = listOf(
                        EncryptedDeviceEnvelopeRequestV2(
                            recipientUserId = s.alice.userId,
                            recipientDeviceId = s.aliceDeviceId,
                            ciphertextType = "SERVICE_PLAINTEXT",
                            ciphertext = "spoofed",
                        ),
                    ),
                ),
            )
        }
        // 必须**因为 kind 不合法**被拒（`v2 消息参数无效`），而不是因为别的原因（例如设备覆盖不匹配）失败——
        // 否则这条断言会在「服务端已经允许 SERVICE」时仍然通过，等于没测到那道门。
        val spoofReason = spoof.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "人类用 kind=SERVICE 发送必须因 kind 不合法被拒，实际=${spoof.getOrNull()} reason=$spoofReason",
            spoof.isFailure && spoofReason.contains("消息参数无效"),
        )
        useSession(s.alice)
        assertEquals(
            "被拒的伪装不得在收件箱里留下任何行",
            0,
            inboxFor(s, s.alice.token, s.chatId, spoofMessageId).size,
        )

        // ④ 接收侧是第二道防线：形状合法但来源不对的三种信封都必须不提交。
        val defensiveSink = RecordingSink()
        val defensive = SignalMessagingV2EnvelopeProcessor(
            signalProtocol = s.aliceProtocol,
            domainSink = defensiveSink,
            groupRevisionProvider = { s.groupEpoch },
            onSenderKeyMissing = { _, _ -> },
            inboxDao = null,
        )
        val base = serviceRow.forAlice(s)
        listOf(
            "发送者不是 bot/system" to base.copy(senderUserId = s.alex.userId),
            "设备号不是 0" to base.copy(senderDeviceId = 1),
            "密文类型不是 SERVICE_PLAINTEXT" to base.copy(ciphertextType = "signal", ciphertext = "AAAA"),
        ).forEach { (label, entity) ->
            runBlocking {
                runCatching { defensive.process(entity.copy(envelopeId = "defense-${UUID.randomUUID()}")) }
            }
            assertEquals("$label 时不得提交", 0, defensiveSink.commits.size)
        }

        // ⑤ 对照：同批次里普通 V2 加密消息的 wire 载荷**不含**原文。
        val plaintext = "contrast-${UUID.randomUUID()}"
        sendDirectTo(s, s.chatId, plaintext, "contrast-${UUID.randomUUID()}")
        useSession(s.alice)
        val encryptedRows = inboxFor(s, s.alice.token, s.chatId).filter { it.ciphertext.contains(plaintext) }
        assertEquals("加密路径的 wire 载荷不得含原文", 0, encryptedRows.size)
    }
}
