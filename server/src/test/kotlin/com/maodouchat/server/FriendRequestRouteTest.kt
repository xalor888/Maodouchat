package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAiEnhanceRouting
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.MessageRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XAL-31：好友申请/列表此前零路由覆盖。独立 JVM（forkEvery=1）。
 */
class FriendRequestRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest(
        seedDemoUsers: Boolean = false,
        aiGateway: AiGateway = FriendReqFakeAiGateway()
    ) {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:friend-req-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", seedDemoUsers.toString())
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        val chatRepo = ChatRepository()
        val messageRepo = MessageRepository()
        val postRepo = PostRepository()
        if (seedDemoUsers) userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = SignalingRepository()
        val callInviteRateLimiter = CallInviteRateLimiter()
        configureSockets(userRepo, messageRepo, chatRepo, signalingRepo = signalingRepo, callInviteRateLimiter = callInviteRateLimiter)
        configureRouting(
            userRepo,
            chatRepo,
            messageRepo,
            postRepo,
            aiGateway,
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter
        )
        configurePollRouting()
        configureDeveloperRouting()
        configureAiEnhanceRouting(
            aiGateway = aiGateway,
            chatRepo = chatRepo,
            aiRepo = AiRepository(),
            aiRateLimiter = com.maodouchat.server.plugins.BoundedRateLimiter()
        )
        configureAdminEnhanceRouting(
            announcementRepo = AnnouncementRepository(),
            userTagRepo = UserTagRepository(),
            rateLimitStatsRepo = RateLimitStatsRepository()
        )
        configureSecretSurfaceRouting(chatRepo = chatRepo, messageRepo = messageRepo, userRepo = userRepo)
    }

    private fun extractToken(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    @Test
    fun `send accept list friends and reject unauthenticated`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val anon = client.post("/api/friends/requests") {
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u3"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)

        val alexToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alex@example.com","password":"password123"}""")
            }.bodyAsText()
        )
        val bobToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"bob@example.com","password":"password123"}""")
            }.bodyAsText()
        )

        val self = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u1"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, self.status, self.bodyAsText())
        assertTrue(self.bodyAsText().contains("SELF"), self.bodyAsText())

        val missing = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"no-such-user"}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status, missing.bodyAsText())
        assertTrue(missing.bodyAsText().contains("USER_NOT_FOUND"), missing.bodyAsText())

        val created = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u3","message":"hi"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val requestId = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(created.bodyAsText().contains("PENDING"), created.bodyAsText())

        val dup = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u3"}""")
        }
        assertEquals(HttpStatusCode.Conflict, dup.status, dup.bodyAsText())
        assertTrue(dup.bodyAsText().contains("ALREADY_PENDING"), dup.bodyAsText())

        val incoming = client.get("/api/friends/requests/incoming") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, incoming.status, incoming.bodyAsText())
        assertTrue(incoming.bodyAsText().contains(requestId), incoming.bodyAsText())

        val outgoing = client.get("/api/friends/requests/outgoing") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, outgoing.status, outgoing.bodyAsText())
        assertTrue(outgoing.bodyAsText().contains(requestId), outgoing.bodyAsText())

        val accept = client.post("/api/friends/requests/$requestId/accept") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, accept.status, accept.bodyAsText())
        assertTrue(accept.bodyAsText().contains("ACCEPTED"), accept.bodyAsText())

        val friends = client.get("/api/friends") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, friends.status, friends.bodyAsText())
        val friendIds = (json.parseToJsonElement(friends.bodyAsText()) as JsonArray)
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("u3" in friendIds, friends.bodyAsText())

        val removed = client.delete("/api/friends/u3") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())

        val again = client.delete("/api/friends/u3") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.NotFound, again.status, again.bodyAsText())
        assertTrue(again.bodyAsText().contains("NOT_FRIENDS"), again.bodyAsText())
    }

    @Test
    fun `reject and cancel friend requests`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val alexToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alex@example.com","password":"password123"}""")
            }.bodyAsText()
        )
        val aliceToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alice@example.com","password":"password123"}""")
            }.bodyAsText()
        )
        val bobToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"bob@example.com","password":"password123"}""")
            }.bodyAsText()
        )

        val toAlice = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u2"}""")
        }
        assertEquals(HttpStatusCode.Created, toAlice.status, toAlice.bodyAsText())
        val rejectId = json.parseToJsonElement(toAlice.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val rejected = client.post("/api/friends/requests/$rejectId/reject") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        assertTrue(rejected.bodyAsText().contains("REJECTED"), rejected.bodyAsText())

        val toBob = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u3"}""")
        }
        assertEquals(HttpStatusCode.Created, toBob.status, toBob.bodyAsText())
        val cancelId = json.parseToJsonElement(toBob.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val cancelled = client.post("/api/friends/requests/$cancelId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertTrue(cancelled.bodyAsText().contains("CANCELLED"), cancelled.bodyAsText())

        val bobIncoming = client.get("/api/friends/requests/incoming") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, bobIncoming.status, bobIncoming.bodyAsText())
        assertTrue(!bobIncoming.bodyAsText().contains(cancelId), bobIncoming.bodyAsText())
    }
}

private class FriendReqFakeAiGateway : AiGateway {
    override val model: String = "test-model"

    override suspend fun rewrite(
        text: String,
        mode: String,
        targetLanguage: String?,
        styleHint: String?
    ): AiGatewayResult<String> = AiGatewayResult.Success("改写：${text.trim()}", model)

    override suspend fun suggestReplies(
        messages: List<com.maodouchat.server.model.AiContextMessage>,
        tone: String,
        count: Int
    ): AiGatewayResult<List<String>> = AiGatewayResult.Success(listOf("好的").take(count), model)

    override suspend fun summarize(
        messages: List<com.maodouchat.server.model.AiContextMessage>,
        style: String
    ): AiGatewayResult<String> = AiGatewayResult.Success("总结：${messages.size} 条消息", model)

    override suspend fun groupAssistant(
        query: String,
        messages: List<com.maodouchat.server.model.AiContextMessage>,
        mode: String
    ): AiGatewayResult<com.maodouchat.server.model.AiGroupAssistantResult> =
        AiGatewayResult.Success(com.maodouchat.server.model.AiGroupAssistantResult("群助手：${query.trim()}", emptyList()), model)

    override suspend fun translate(text: String, targetLanguage: String): AiGatewayResult<String> =
        AiGatewayResult.Success("翻译：${text.trim()}", model)

    override suspend fun semanticSearch(
        query: String,
        candidates: List<com.maodouchat.server.model.AiSemanticSearchCandidate>,
        limit: Int
    ): AiGatewayResult<List<com.maodouchat.server.model.AiSemanticSearchMatch>> =
        AiGatewayResult.Success(emptyList(), model)

    override suspend fun transcribe(audioBytes: ByteArray, mimeType: String, language: String?): AiGatewayResult<String> =
        AiGatewayResult.Success("test transcript", model)

    override suspend fun analyzeImage(imageBase64: String, mimeType: String, mode: String): AiGatewayResult<String> =
        AiGatewayResult.Success("图片分析：$mode", model)

    override suspend fun analyzeFile(
        fileBase64: String,
        fileName: String,
        mimeType: String,
        mode: String,
        question: String?
    ): AiGatewayResult<String> = AiGatewayResult.Success("文件分析：$fileName / $mode", model)
}
