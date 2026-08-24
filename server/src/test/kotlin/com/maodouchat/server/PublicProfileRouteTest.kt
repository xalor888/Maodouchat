package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
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
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XAL-42：GET /u/{username} 公开名片页（独立 JVM，forkEvery=1）。
 */
class PublicProfileRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest(seedDemoUsers: Boolean = false) {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:public-profile-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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
            PublicProfileFakeAiGateway(),
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
    fun `invalid and missing usernames render escaped error cards without dialogs`() = testApplication {
        application { moduleUnderTest() }

        val invalid = client.get("/u/ab")
        assertEquals(HttpStatusCode.OK, invalid.status, invalid.bodyAsText())
        val invalidHtml = invalid.bodyAsText()
        assertTrue(invalidHtml.contains("state-error"), invalidHtml)
        assertTrue(invalidHtml.contains("用户名无效"), invalidHtml)
        assertTrue(invalidHtml.contains("/assets/profile.css"), invalidHtml)
        assertFalse(invalidHtml.contains("alert("), invalidHtml)
        assertFalse(invalidHtml.contains("<script"), invalidHtml)
        assertFalse(invalidHtml.contains("{{"), invalidHtml)
        assertEquals("no-cache", invalid.headers[HttpHeaders.CacheControl])

        val missing = client.get("/u/nouserxyz")
        assertEquals(HttpStatusCode.OK, missing.status, missing.bodyAsText())
        val missingHtml = missing.bodyAsText()
        assertTrue(missingHtml.contains("state-error"), missingHtml)
        assertTrue(missingHtml.contains("用户不存在"), missingHtml)
        assertEquals("no-cache", missing.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `public profile card escapes user fields and ships deep links`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val css = client.get("/assets/profile.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.bodyAsText().contains("prefers-color-scheme: dark"), css.bodyAsText())

        val alexToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alex@example.com","password":"password123"}""")
            }.bodyAsText()
        )

        val username = client.put("/api/users/me/username") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alex_share"}""")
        }
        assertEquals(HttpStatusCode.OK, username.status, username.bodyAsText())

        val profile = client.put("/api/users/profile") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"<img src=x onerror=alert(1)>","status":"busy & \"quote\" <script>"}""")
        }
        assertEquals(HttpStatusCode.OK, profile.status, profile.bodyAsText())

        val page = client.get("/u/alex_share")
        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        val html = page.bodyAsText()
        assertTrue(html.contains("state-profile"), html)
        assertTrue(html.contains("@alex_share"), html)
        assertTrue(html.contains("maodouchat://u/alex_share"), html)
        assertTrue(html.contains("intent://u/alex_share#Intent;scheme=maodouchat;package=com.maodouchat;end"), html)
        assertTrue(html.contains("og:type"), html)
        assertTrue(html.contains("twitter:card"), html)
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html)
        assertFalse(html.contains("<img src=x onerror=alert(1)>"), html)
        assertFalse(html.contains("<script>"), html)
        assertFalse(html.contains("window.alert"), html)
        assertFalse(html.contains("{{"), html)
        // 匿名公开主页隐藏 status（UserRepository.toResponse(anonymous)），页面不得回显个性签名
        assertFalse(html.contains("busy"), html)
        assertFalse(html.contains("has-status"), html)
        assertTrue(html.contains("不会显示在线状态"), html)
        assertEquals("public, max-age=60", page.headers[HttpHeaders.CacheControl])
    }
}

private class PublicProfileFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}
