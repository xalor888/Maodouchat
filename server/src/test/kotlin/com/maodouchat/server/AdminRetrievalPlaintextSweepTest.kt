package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.DeviceTarget
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.messaging.v2.OutboundEnvelope
import com.maodouchat.server.messaging.v2.SendMessageV2Command
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.AdminManagementRepository
import com.maodouchat.server.repository.AdminMessageSearchFilter
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G61：把「服务端不得把人类聊天明文返回给管理端」从**一个入口**扩展到**全部管理端入口**。
 *
 * G38/G40 只扫了 `/api/admin/messages/search`。但管理后台有二十多个能返回
 * 会话/消息相邻数据的端点（chats 列表、会话详情、用户详情、bot 列表、看板、排行、导出 CSV、
 * AI 用量……）。任何**新加**的端点只要顺手 `selectAll()` 一遍、或者把 `ciphertext` 塞进响应，
 * 就会在没有任何用例看守的情况下把人类明文送回管理端——这正是本条要堵的洞。
 *
 * 做法：用**真实发送**（`MessagingV2Repository.send`）提交一个带唯一标记的人类载荷，
 * 然后**逐一请求全部管理端点**，断言响应里绝不出现该标记。
 *
 * 正对照（关键）：同一套扫描必须能在**服务端按设计保存**的内容上生效，
 * 否则「扫不到」可能只是扫描器坏了——所以这里用 bot 公告（服务端可见的明文）证明扫描面真的覆盖到了。
 */
class AdminRetrievalPlaintextSweepTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-sweep-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        System.setProperty("MASTER_ADMINS", "u1")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = SignalingRepository()
        val callInviteRateLimiter = CallInviteRateLimiter()
        configureSockets(userRepo, signalingRepo = signalingRepo, callInviteRateLimiter = callInviteRateLimiter)
        configureRouting(
            userRepo,
            PostRepository(),
            SweepFakeAiGateway(),
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter
        )
        configurePollRouting()
        configureDeveloperRouting()
        configureAdminEnhanceRouting(
            announcementRepo = AnnouncementRepository(),
            userTagRepo = UserTagRepository(),
            rateLimitStatsRepo = RateLimitStatsRepository(),
        )
        configureSecretSurfaceRouting(userRepo = userRepo)
    }

    private class SweepFakeAiGateway : AiGateway {
        override val model: String = "sweep-model"
    }

    private fun extractToken(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    /** `testApplication` 的 `application {}` 是惰性的：不发请求就不会启动应用，
     *  于是 `Database.connect` 也不会执行。先发一个无害请求把应用拉起来。 */
    private suspend fun io.ktor.client.HttpClient.warmUp() {
        val response = get("/health/live")
        assertTrue("预热请求应当成功，实际 ${response.status}") { response.status.value in 200..499 }
    }

    /** 造一个带人类标记消息的群会话（标记**真的**进了发送命令）。 */
    private fun seedHumanMessage(marker: String) {
        transaction {
            val existingUsers = Users.selectAll().map { it[Users.id] }.toSet()
            listOf("sweep_a", "sweep_b").filter { it !in existingUsers }.forEach { userId ->
                Users.insert {
                    it[Users.id] = userId
                    it[Users.name] = userId
                    it[Users.email] = "$userId@sweep.local"
                    it[Users.passwordHash] = "x"
                    it[Users.isOnline] = false
                }
                SignalDevices.insert {
                    it[SignalDevices.userId] = userId
                    it[SignalDevices.deviceId] = 1
                    it[SignalDevices.deviceName] = "$userId phone"
                    it[SignalDevices.status] = "CONFIRMED"
                    it[SignalDevices.confirmedAt] = 1L
                    it[SignalDevices.confirmedByDeviceId] = 1
                    it[SignalDevices.createdAt] = 1L
                    it[SignalDevices.lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        SignalKeys.insert {
                            it[SignalKeys.id] = "$userId-1-$keyType"
                            it[SignalKeys.userId] = userId
                            it[SignalKeys.deviceId] = 1
                            it[SignalKeys.keyType] = keyType
                            it[SignalKeys.keyData] = "x"
                            it[SignalKeys.createdAt] = 1L
                        }
                    }
            }
            val existingChats = Chats.selectAll().map { it[Chats.id] }.toSet()
            if ("sweep-group" !in existingChats) {
                Chats.insert {
                    it[Chats.id] = "sweep-group"
                    it[Chats.isGroup] = true
                    it[Chats.chatType] = "GROUP"
                    it[Chats.groupName] = "sweep group"
                    it[Chats.memberRevision] = 3L
                }
            }
            val existingMembers = ChatParticipants.selectAll()
                .filter { it[ChatParticipants.chatId] == "sweep-group" }
                .map { it[ChatParticipants.userId] }.toSet()
            listOf("sweep_a", "sweep_b").filter { it !in existingMembers }.forEach { userId ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "sweep-group"
                    it[ChatParticipants.userId] = userId
                    it[ChatParticipants.role] = if (userId == "sweep_a") "OWNER" else "MEMBER"
                    it[ChatParticipants.joinedAt] = 1L
                }
            }
        }

        val alreadySent = transaction {
            MessagingV2Messages.selectAll().any { it[MessagingV2Messages.id] == "sweep-human-msg" }
        }
        if (alreadySent) return

        val command = SendMessageV2Command(
            id = "sweep-human-msg",
            conversationId = "sweep-group",
            senderUserId = "sweep_a",
            senderDeviceId = 1,
            kind = "DATA",
            clientTimestamp = 1_000L,
            groupRevision = 3L,
            envelopes = listOf(
                OutboundEnvelope(DeviceTarget("sweep_b", 1), "TEXT", marker),
            ),
        )
        val result = MessagingV2Repository { 2_000L }.send(command)
        assertEquals("sweep-human-msg", result.messageId, "人类消息必须真的发送成功，否则后面的结论没有意义")
        assertEquals(1, result.envelopeCount)
    }

    /** 发一条 bot 公告（服务端按设计保存的明文），返回是否真的发布成功。 */
    private fun publishBotAnnouncement(marker: String): Boolean {
        transaction {
            if (Users.selectAll().none { it[Users.id] == "bot_sweep" }) {
                Users.insert {
                    it[Users.id] = "bot_sweep"
                    it[Users.name] = "sweep bot"
                    it[Users.email] = "bot-sweep@test.local"
                    it[Users.passwordHash] = "x"
                    it[Users.isOnline] = false
                }
            }
            if (BotApps.selectAll().none { it[BotApps.id] == "bot_sweep" }) {
                BotApps.insert {
                    it[id] = "bot_sweep"
                    it[ownerUserId] = "sweep_a"
                    it[name] = "sweep bot"
                    it[username] = "sweep_bot"
                    it[tokenHash] = "x"
                    it[tokenPrefix] = "x"
                    it[enabled] = true
                    it[createdAt] = 1L
                    it[updatedAt] = 1L
                }
            }
            val members = ChatParticipants.selectAll()
                .filter { it[ChatParticipants.chatId] == "sweep-group" }
                .map { it[ChatParticipants.userId] }.toSet()
            if ("bot_sweep" !in members) {
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "sweep-group"
                    it[ChatParticipants.userId] = "bot_sweep"
                    it[ChatParticipants.role] = "MEMBER"
                    it[ChatParticipants.joinedAt] = 1L
                }
            }
        }
        val published = ServiceMessageRepository().publish(
            id = "svc-sweep-1",
            chatId = "sweep-group",
            botUserId = "bot_sweep",
            content = marker,
            timestamp = 3_000L,
            type = "TEXT",
            recipientUserIds = setOf("sweep_a", "sweep_b"),
        )
        return published is ServiceMessageRepository.PublishResult.Published
    }

    private suspend fun io.ktor.client.HttpClient.adminToken(): String {
        val login = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        return extractToken(
            post("/api/admin/session") {
                header(HttpHeaders.Authorization, "Bearer ${extractToken(login.bodyAsText())}")
                contentType(ContentType.Application.Json)
                setBody("""{"password":"password123"}""")
            }.bodyAsText()
        )
    }

    @Test
    fun `no admin retrieval endpoint ever returns the human payload marker`() = testApplication {
        application { moduleUnderTest() }
        client.warmUp()
        val marker = "HUMAN-MARKER-${java.util.UUID.randomUUID()}"
        seedHumanMessage(marker)
        val adminToken = client.adminToken()

        // 全部管理端检索 / 导出入口。参数尽量贴近真实调用（缺参数会 400，
        // 但 400 也说明该入口**没有返回**标记，仍然是有效证据）。
        val endpoints = listOf(
            "/api/admin/messages/search?q=HUMAN&limit=50",
            "/api/admin/chats?limit=50",
            "/api/admin/chats/sweep-group",
            "/api/admin/users?limit=50",
            "/api/admin/users/u2",
            "/api/admin/users/u2/detail",
            "/api/admin/bots?limit=50",
            "/api/admin/dashboard",
            "/api/admin/ranking",
            "/api/admin/trends",
            "/api/admin/storage",
            "/api/admin/online",
            "/api/admin/reports?limit=50",
            "/api/admin/posts?limit=50",
            "/api/admin/comments?limit=50",
            "/api/admin/rate-limit/dashboard",
            "/api/admin/ai-usage?limit=50",
            "/api/admin/ai-usage-export?limit=50",
            "/api/admin/message-stats-export",
            "/api/admin/users-export?limit=50",
            "/api/admin/bots-export?limit=50",
            "/api/admin/push-tokens-export?limit=50",
            "/api/admin/reports-export?limit=50",
            "/api/admin/risk-events-export?limit=50",
            "/api/admin/ai-feature-flags-export",
            "/api/admin/settings",
        )

        val leaks = mutableListOf<String>()
        var responded = 0
        for (endpoint in endpoints) {
            val response = client.get(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
            }
            val body = response.bodyAsText()
            // 只把「真的给出了 200 响应」的入口算作被检查过；400/403 说明入口存在但拒绝返回数据。
            if (response.status == HttpStatusCode.OK) responded++
            if (body.contains(marker)) leaks += "$endpoint -> ${body.take(200)}"
        }

        assertTrue(
            "至少要有一部分管理端点真实返回 200，否则本用例没有检查到任何东西（responded=$responded / ${endpoints.size}）"
        ) { responded >= endpoints.size / 2 }
        assertEquals(emptyList(), leaks, "这些管理端点把人类消息标记返回给了管理员")
    }

    @Test
    fun `the sweep really can find plaintext that the server does store`() = testApplication {
        application { moduleUnderTest() }
        client.warmUp()
        val marker = "HUMAN-MARKER-${java.util.UUID.randomUUID()}"
        seedHumanMessage(marker)

        // 正对照：bot/service 公告是服务端**按设计**保存的明文。
        assertTrue(
            publishBotAnnouncement(marker),
            "正对照失败：bot 公告必须真的发布成功，否则扫描器扫不到东西",
        )
        val stored = transaction {
            ServiceMessages.selectAll()
                .where { ServiceMessages.id eq "svc-sweep-1" }
                .single()[ServiceMessages.content]
        }
        assertEquals(marker, stored, "正对照失败：服务端应当真的保存了这条 bot 明文")

        val adminToken = client.adminToken()

        // ① 元数据检索能列出该会话的消息（证明扫描面真的覆盖到了它）。
        val byChat = client.get("/api/admin/messages/search?chatId=sweep-group&limit=50") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, byChat.status, byChat.bodyAsText())
        assertTrue(
            byChat.bodyAsText().contains("svc-sweep-1"),
            "正对照：必须能检索到该会话里的 bot 消息，否则扫描面根本没覆盖它：${byChat.bodyAsText().take(300)}",
        )
        // ② 按**明文内容**检索不到它（管理端不投影正文）。
        val byContent = client.get("/api/admin/messages/search?q=$marker&limit=50") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, byContent.status, byContent.bodyAsText())
        assertFalse(
            byContent.bodyAsText().contains(marker),
            "管理检索不得按正文内容命中：${byContent.bodyAsText().take(300)}",
        )
        // ③ 仓储层直接查也确认：搜索只命中元数据列，不会碰正文列。
        val repoRows = AdminManagementRepository().searchMessageMetadata(
            AdminMessageSearchFilter(q = marker, chatId = "", userId = "", limit = 50, offset = 0),
        )
        assertTrue(
            repoRows.none { it.id == "svc-sweep-1" },
            "按正文内容不得命中 bot 消息：${repoRows.map { it.id }}",
        )
        // ④ 人类信封的载荷只以密文形态存在于信封列。
        val envelopeCipher = transaction {
            MessagingV2Envelopes.selectAll()
                .single { it[MessagingV2Envelopes.messageId] == "sweep-human-msg" }[MessagingV2Envelopes.ciphertext]
        }
        assertEquals(marker, envelopeCipher, "人类载荷必须只存在于信封列")
        for (body in listOf(byChat.bodyAsText(), byContent.bodyAsText())) {
            assertFalse(body.contains("ciphertext"), "管理检索不得回信封密文字段：${body.take(300)}")
        }
    }
}
