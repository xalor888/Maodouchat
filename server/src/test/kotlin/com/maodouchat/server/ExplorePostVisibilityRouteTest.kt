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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XAL-31：探索 feed 可见性隔离 + 点赞/取消点赞。
 * PUBLIC 必须显式 useDefaultVisibility=false，否则 fail-closed 落到账号默认 PRIVATE。
 */
class ExplorePostVisibilityRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest(
        seedDemoUsers: Boolean = false,
        aiGateway: AiGateway = ExploreVisFakeAiGateway()
    ) {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:explore-vis-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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

    private fun feedIds(body: String): Set<String> =
        (json.parseToJsonElement(body) as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

    @Test
    fun `public private and contacts visibility isolate feed`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
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

        suspend fun create(visibility: String, content: String): String {
            val resp = client.post("/api/posts") {
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
                contentType(ContentType.Application.Json)
                setBody("""{"content":"$content","visibility":"$visibility","useDefaultVisibility":false}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
            return json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        }

        val publicId = create("PUBLIC", "public-feed-marker")
        val privateId = create("PRIVATE", "private-feed-marker")
        val contactsId = create("CONTACTS", "contacts-feed-marker")

        val bobFeed0 = client.get("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, bobFeed0.status, bobFeed0.bodyAsText())
        val ids0 = feedIds(bobFeed0.bodyAsText())
        assertTrue(publicId in ids0, bobFeed0.bodyAsText())
        assertFalse(privateId in ids0, bobFeed0.bodyAsText())
        assertFalse(contactsId in ids0, bobFeed0.bodyAsText())

        // CONTACTS 按完整 1:1 私聊判定，不是好友关系。
        val friendReq = client.post("/api/friends/requests") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u3"}""")
        }
        assertEquals(HttpStatusCode.Created, friendReq.status, friendReq.bodyAsText())
        val requestId = json.parseToJsonElement(friendReq.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val accept = client.post("/api/friends/requests/$requestId/accept") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, accept.status, accept.bodyAsText())

        val bobFeedFriendsOnly = client.get("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        val idsFriendsOnly = feedIds(bobFeedFriendsOnly.bodyAsText())
        assertTrue(publicId in idsFriendsOnly, bobFeedFriendsOnly.bodyAsText())
        assertFalse(contactsId in idsFriendsOnly, bobFeedFriendsOnly.bodyAsText())
        assertFalse(privateId in idsFriendsOnly, bobFeedFriendsOnly.bodyAsText())

        val chat = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u3"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chat.status, chat.bodyAsText())

        val bobFeed1 = client.get("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        val ids1 = feedIds(bobFeed1.bodyAsText())
        assertTrue(publicId in ids1, bobFeed1.bodyAsText())
        assertTrue(contactsId in ids1, bobFeed1.bodyAsText())
        assertFalse(privateId in ids1, bobFeed1.bodyAsText())
    }

    @Test
    fun `like unlike round trip and cannot like own post`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
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

        val unauth = client.post("/api/posts") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"x","visibility":"PUBLIC","useDefaultVisibility":false}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val created = client.post("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"like-me","visibility":"PUBLIC","useDefaultVisibility":false}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val postId = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val selfLike = client.post("/api/posts/$postId/like") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.BadRequest, selfLike.status, selfLike.bodyAsText())
        assertTrue(selfLike.bodyAsText().contains("不能给自己的动态点赞"), selfLike.bodyAsText())

        val liked = client.post("/api/posts/$postId/like") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, liked.status, liked.bodyAsText())
        val likedJson = json.parseToJsonElement(liked.bodyAsText()).jsonObject
        assertEquals("true", likedJson["likedByMe"]!!.jsonPrimitive.content)
        assertEquals("1", likedJson["likeCount"]!!.jsonPrimitive.content)

        val unliked = client.delete("/api/posts/$postId/like") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, unliked.status, unliked.bodyAsText())
        val unlikedJson = json.parseToJsonElement(unliked.bodyAsText()).jsonObject
        // encodeDefaults=false：likedByMe=false / likeCount=0 可能省略
        assertEquals("false", unlikedJson["likedByMe"]?.jsonPrimitive?.content ?: "false")
        assertEquals("0", unlikedJson["likeCount"]?.jsonPrimitive?.content ?: "0")
    }
}

private class ExploreVisFakeAiGateway : AiGateway {
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
