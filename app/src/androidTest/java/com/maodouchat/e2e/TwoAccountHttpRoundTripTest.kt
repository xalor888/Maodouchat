package com.maodouchat.e2e

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.crypto.SignalKeyExchange
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.messaging.v2.MessagingV2Content
import com.maodouchat.messaging.v2.MessagingV2DomainSink
import com.maodouchat.messaging.v2.SignalMessagingV2EnvelopePreparer
import com.maodouchat.messaging.v2.SignalMessagingV2EnvelopeProcessor
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.AuthResponse
import com.maodouchat.network.ConversationSnapshotV2Dto
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.network.SendMessageRequestV2
import com.maodouchat.network.TokenManager
import com.maodouchat.network.api.AuthApiClient
import com.maodouchat.network.api.ConversationApiClient
import com.maodouchat.network.api.MessagingApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val commits = mutableListOf<String>()
        override suspend fun commit(envelope: MessagingV2InboxEntity, content: MessagingV2Content) {
            commits += content.body
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
    )

    private companion object {
        private var database: AppDatabase? = null
        private var shared: Shared? = null

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

        // 服务端只允许**会话参与者**之间取 prekey bundle（反枚举规则），所以必须先建会话。
        useSession(alex)
        val chat = ConversationApiClient
            .createChat(alex.token, participantIds = listOf(alice.userId), isGroup = false)
            .getOrThrow()
        val snapshot = MessagingApiClient.getConversationSnapshotV2(alex.token, chat.id).getOrThrow()

        Shared(alex, alice, alexProtocol, aliceProtocol, chat.id, snapshot).also { shared = it }
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
        useSession(s.alex)
        val aliceFetched = SignalKeyExchange.fetchPreKeyBundle(s.alex.token, s.alice.userId)
        useSession(s.alice)
        val alexFetched = SignalKeyExchange.fetchPreKeyBundle(s.alice.token, s.alex.userId)

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
            "生产 processor 必须解出原文并落到 sink，实际提交=${sink.commits}",
            sink.commits.contains(plaintext),
        )
    }
}
