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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XAL-31：管理后台 system-stats / trends（8 标签页中此前弱覆盖的读接口）。
 * dashboard 鉴权已在 AdminRouteAuthenticationTest，这里不重复规则 CRUD。
 */
class AdminSystemStatsRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest(
        seedDemoUsers: Boolean = false,
        aiGateway: AiGateway = AdminStatsFakeAiGateway()
    ) {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-stats-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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
    fun `system-stats and trends require admin session not user jwt`() = testApplication {
        System.setProperty("MASTER_ADMINS", "u1")
        application { moduleUnderTest(seedDemoUsers = true) }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val userToken = extractToken(login.bodyAsText())

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/system-stats") {
                header(HttpHeaders.Authorization, "Bearer $userToken")
            }.status
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/trends") {
                header(HttpHeaders.Authorization, "Bearer $userToken")
            }.status
        )

        val session = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        val adminToken = extractToken(session.bodyAsText())

        val stats = client.get("/api/admin/system-stats") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, stats.status, stats.bodyAsText())
        val statsJson = json.parseToJsonElement(stats.bodyAsText()).jsonObject
        assertTrue(statsJson.containsKey("totalMessages"), stats.bodyAsText())
        assertTrue(statsJson.containsKey("totalChats"), stats.bodyAsText())
        assertTrue(statsJson.containsKey("jvmMaxMemoryBytes"), stats.bodyAsText())
        assertTrue(statsJson.containsKey("onlineUsers"), stats.bodyAsText())
        assertTrue(statsJson["jvmMaxMemoryBytes"]!!.jsonPrimitive.content.toLong() > 0L, stats.bodyAsText())

        val trends = client.get("/api/admin/trends") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, trends.status, trends.bodyAsText())
        val trendsJson = json.parseToJsonElement(trends.bodyAsText()).jsonObject
        assertEquals(7, trendsJson["newUsers"]!!.jsonArray.size, trends.bodyAsText())
        assertEquals(7, trendsJson["newMessages"]!!.jsonArray.size, trends.bodyAsText())
        assertEquals(7, trendsJson["newPosts"]!!.jsonArray.size, trends.bodyAsText())
    }
}

private class AdminStatsFakeAiGateway : AiGateway {
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
