package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.StarredMessageReference
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.ConversationCreationRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.StarMessageRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨账号隔离（per-user 状态）。
 *
 * 客户端那边我给账号隔离立了源码闸门（G205b `DaoOwnerScopingTest`），但**服务端不行**——
 * 实测 414 个 Exposed 查询点里 314 个的调用片段不含 `userId`，绝大多数是合法的
 * （登出清理、`selectAll` 后另行过滤）。所以服务端只能靠**行为测试**。
 *
 * 这里验证的是同一个性质的另一侧：u1 的私有状态（星标、会话设置）不能被 u2 看见。
 * 每个用例都按同一个套路——**先把两个账号的数据都造出来，再分别用各自的 token 读**。
 * 这样「读不到别人的」和「自己的还能读到」两件事同时被验证，避免测试因为
 * 「谁都读不到」而假绿。
 */
class PerUserStateIsolationRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:isolation-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
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
            PerUserIsolationFakeAiGateway(),
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter
        )
        configurePollRouting()
        configureDeveloperRouting()
        configureAdminEnhanceRouting(
            announcementRepo = AnnouncementRepository(),
            userTagRepo = UserTagRepository(),
            rateLimitStatsRepo = RateLimitStatsRepository()
        )
        configureSecretSurfaceRouting(userRepo = userRepo)
    }

    private suspend fun io.ktor.client.HttpClient.login(email: String): String {
        val resp = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "登录失败: ${resp.bodyAsText()}")
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun bearer(token: String) = "Bearer $token"

    /** 造一个 u1/u2 都在的群，并给双方各插一条消息（u1 的标成 A，u2 的标成 B）。 */
    private fun seedChatWithTwoMessages(): Pair<String, Pair<String, String>> {
        val repo = ConversationCreationRepository()
        val chat = repo.create(listOf("u1", "u2"), isGroup = true, groupName = "iso-group", creatorId = "u1")
        transaction {
            insertMessagingV2MessageFixture(
                messageId = "iso_msg_u1",
                conversationId = chat.id,
                senderUserId = "u1",
                timestamp = 1_000L,
            )
            insertMessagingV2MessageFixture(
                messageId = "iso_msg_u2",
                conversationId = chat.id,
                senderUserId = "u2",
                timestamp = 2_000L,
            )
        }
        return chat.id to ("iso_msg_u1" to "iso_msg_u2")
    }

    @Test
    fun `starred messages are isolated between accounts`() = testApplication {
        application { moduleUnderTest() }
        // ⚠️ 必须先发一次请求（登录）把 app 起起来：`application { }` 只是配置，
        // Database.connect() 在里面，app 未启动时直接造数据会
        // 报 "Please call Database.connect() before using this code"。
        val u1 = client.login("alex@example.com")
        val u2 = client.login("alice@example.com")

        val (chatId, ids) = seedChatWithTwoMessages()

        // 各自星标自己的那条
        listOf(u1 to ids.first, u2 to ids.second).forEach { (token, mid) ->
            val r = client.post("/api/messages/$mid/star") {
                header(HttpHeaders.Authorization, bearer(token))
            }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        }

        // 分别读自己的星标
        val u1List = client.get("/api/messages/starred") {
            header(HttpHeaders.Authorization, bearer(u1))
        }
        val u2List = client.get("/api/messages/starred") {
            header(HttpHeaders.Authorization, bearer(u2))
        }
        assertEquals(HttpStatusCode.OK, u1List.status, u1List.bodyAsText())
        assertEquals(HttpStatusCode.OK, u2List.status, u2List.bodyAsText())

        val u1Ids = json.parseToJsonElement(u1List.bodyAsText()).jsonArray
            .map { it.jsonObject["messageId"]?.jsonPrimitive?.content }.toSet()
        val u2Ids = json.parseToJsonElement(u2List.bodyAsText()).jsonArray
            .map { it.jsonObject["messageId"]?.jsonPrimitive?.content }.toSet()

        // 自己的要读得到（否则下面两条会因为「谁都读不到」而假绿）
        assertTrue(ids.first in u1Ids, "u1 应读到自己星标的消息，实际 $u1Ids")
        assertTrue(ids.second in u2Ids, "u2 应读到自己星标的消息，实际 $u2Ids")
        // 别人的读不到
        assertTrue(ids.second !in u1Ids, "u1 不应看到 u2 的星标，实际 $u1Ids")
        assertTrue(ids.first !in u2Ids, "u2 不应看到 u1 的星标，实际 $u2Ids")
    }

}

/** 与 AdminChatIsolationRouteTest 里那个同形，但那个是 private in file，跨文件不可见。 */
private class PerUserIsolationFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}

/**
 * repo 层的跨账号检查。
 *
 * 上面那个用例走 HTTP；这里直接打 repository。之所以还要这一层：
 * 路由 404 说明「接口没接」，而 repo 层的检查才能说明**数据本身**没串账号——
 * 将来若有人给 `/api/chats` 之类接上线，路由层的断言也会跟着补。
 */
private class PerUserStateRepoIsolationTest {

    private fun db() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:repo-iso-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("SEED_DEMO_USERS", "true")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        UserRepository().createDefaultUsers()
    }

    @Test
    fun `star rows never cross accounts at the repository layer`() {
        db()
        val repo = StarMessageRepository()
        val creation = ConversationCreationRepository()
        val chat = creation.create(listOf("u1", "u2"), isGroup = true, groupName = "repo-iso", creatorId = "u1")
        transaction {
            insertMessagingV2MessageFixture(
                messageId = "repo_iso_m1",
                conversationId = chat.id,
                senderUserId = "u1",
                timestamp = 10L,
            )
            insertMessagingV2MessageFixture(
                messageId = "repo_iso_m2",
                conversationId = chat.id,
                senderUserId = "u1",
                timestamp = 20L,
            )
        }

        // u1 星标 m1，u2 星标 m2
        assertEquals(true, repo.toggleStar("u1", "repo_iso_m1"))
        assertEquals(true, repo.toggleStar("u2", "repo_iso_m2"))

        val u1 = repo.getStarredMessages("u1").map { it.messageId }.toSet()
        val u2 = repo.getStarredMessages("u2").map { it.messageId }.toSet()
        assertEquals(setOf("repo_iso_m1"), u1, "u1 只应看到自己星标的")
        assertEquals(setOf("repo_iso_m2"), u2, "u2 只应看到自己星标的")

        // u3 从未星标 → 空（不是「所有人的」）
        assertEquals(emptySet<String>(), repo.getStarredMessages("u3").map { it.messageId }.toSet())

        // u2 撤销自己的，不影响 u1
        repo.toggleStar("u2", "repo_iso_m2")
        assertEquals(setOf("repo_iso_m1"), repo.getStarredMessages("u1").map { it.messageId }.toSet())
    }

    @Test
    fun `chat user settings written for one account are not visible to another`() {
        db()
        val creation = ConversationCreationRepository()
        val chat = creation.create(listOf("u1", "u2"), isGroup = true, groupName = "repo-iso-settings", creatorId = "u1")
        transaction {
            ChatUserSettings.insert {
                it[chatId] = chat.id
                it[userId] = "u1"
                it[pinnedAt] = 7L
                it[notificationsMuted] = true
                it[archived] = false
                it[markedUnread] = false
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        // 只有 u1 那一行存在
        val rows = transaction {
            ChatUserSettings.selectAll().where { ChatUserSettings.chatId eq chat.id }.toList()
        }
        assertEquals(1, rows.size, "应只有 u1 一行设置")
        assertEquals("u1", rows.single()[ChatUserSettings.userId])
        // u2 查不到任何自己的设置
        val u2Rows = transaction {
            ChatUserSettings.selectAll().where {
                (ChatUserSettings.chatId eq chat.id) and (ChatUserSettings.userId eq "u2")
            }.toList()
        }
        assertTrue(u2Rows.isEmpty(), "u2 不应继承 u1 的置顶/静音设置")
    }
}
