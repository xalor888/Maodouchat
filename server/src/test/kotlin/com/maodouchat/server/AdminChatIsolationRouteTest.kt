package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.initDatabase
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
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.MessageRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.AiGateway
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminChatIsolationRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-chat-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        System.setProperty("MASTER_ADMINS", "u1")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        val chatRepo = ChatRepository()
        val messageRepo = MessageRepository()
        val postRepo = PostRepository()
        userRepo.createDefaultUsers()
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
            IsolationFakeAiGateway(),
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
        configureSecretSurfaceRouting(chatRepo = chatRepo, messageRepo = messageRepo, userRepo = userRepo)
    }

    private fun extractToken(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    @Test
    fun `admin chats hide secret and refuse dissolving 1-1`() = testApplication {
        lateinit var chatRepo: ChatRepository
        application {
            moduleUnderTest()
            chatRepo = ChatRepository()
        }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val userToken = extractToken(login.bodyAsText())
        val session = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        val adminToken = extractToken(session.bodyAsText())

        val group = chatRepo.createChat(listOf("u1", "u2"), isGroup = true, groupName = "ops-group", creatorId = "u1")
        val direct = chatRepo.getOrCreateDirectChat("u1", "u2")
        val secret = chatRepo.getOrCreateSecretChat("u1", "u2")
        transaction {
            Messages.insert {
                it[id] = "m_secret_admin_search"
                it[chatId] = secret.id
                it[senderId] = "u1"
                it[content] = "cipherblob-should-not-leak"
                it[type] = "TEXT"
                it[timestamp] = System.currentTimeMillis()
                it[status] = "SENT"
            }
        }

        val listed = client.get("/api/admin/chats?groupOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
        val ids = json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(group.id in ids, listed.bodyAsText())
        assertFalse(direct.id in ids, listed.bodyAsText())
        assertFalse(secret.id in ids, listed.bodyAsText())

        val csv = client.get("/api/admin/chats-export") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, csv.status, csv.bodyAsText())
        assertFalse(csv.bodyAsText().contains(secret.id), csv.bodyAsText())

        val searchBySecretId = client.get("/api/admin/messages/search?chatId=${secret.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, searchBySecretId.status, searchBySecretId.bodyAsText())
        val items = json.parseToJsonElement(searchBySecretId.bodyAsText()).jsonObject["items"] as JsonArray
        assertEquals(0, items.size, searchBySecretId.bodyAsText())

        val dissolveDirect = client.delete("/api/admin/chats/${direct.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.BadRequest, dissolveDirect.status, dissolveDirect.bodyAsText())

        val dissolveSecret = client.delete("/api/admin/chats/${secret.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.BadRequest, dissolveSecret.status, dissolveSecret.bodyAsText())

        val dissolveGroup = client.delete("/api/admin/chats/${group.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, dissolveGroup.status, dissolveGroup.bodyAsText())
    }
}

private class IsolationFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}
