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
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
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
import kotlin.test.assertTrue

/**
 * XAL-31：登录成功契约、logout / logout-all 吊销 refresh 与 access version。
 * 独立文件，避免追加 MinimalRouteTest；forkEvery=1。
 * 锁定/refresh 重用/TOTP 已有专用类，这里不重复。
 */
class AuthLogoutRefreshRouteTest {

    private fun Application.moduleUnderTest(
        seedDemoUsers: Boolean = false,
        aiGateway: AiGateway = AuthLogoutFakeAiGateway()
    ) {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:auth-logout-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", seedDemoUsers.toString())
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        val postRepo = PostRepository()
        if (seedDemoUsers) userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = SignalingRepository()
        val callInviteRateLimiter = CallInviteRateLimiter()
        configureSockets(userRepo, signalingRepo = signalingRepo, callInviteRateLimiter = callInviteRateLimiter)
        configureRouting(
            userRepo,
            postRepo,
            aiGateway,
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

    private fun loginJson(body: String): JsonObject =
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as JsonObject

    private fun field(obj: JsonObject, key: String): String = obj[key]!!.jsonPrimitive.content

    @Test
    fun `login returns tokens and rejects invalid credentials without leaking account existence`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val empty = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status, empty.bodyAsText())

        val bad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"wrong-pass"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status, bad.bodyAsText())
        assertTrue(bad.bodyAsText().contains("AUTH_INVALID"), bad.bodyAsText())

        val unknown = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nobody@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unknown.status, unknown.bodyAsText())
        assertTrue(unknown.bodyAsText().contains("AUTH_INVALID"), unknown.bodyAsText())

        val ok = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val json = loginJson(ok.bodyAsText())
        assertTrue(field(json, "token").isNotBlank(), ok.bodyAsText())
        assertTrue(field(json, "refreshToken").isNotBlank(), ok.bodyAsText())
        assertEquals("u1", field(json, "userId"))
        assertTrue(json["expiresAt"]!!.jsonPrimitive.content.toLong() > 0L, ok.bodyAsText())
        assertTrue(json["refreshExpiresAt"]!!.jsonPrimitive.content.toLong() > 0L, ok.bodyAsText())
    }

    @Test
    fun `logout revokes refresh and logout-all rejects old access and refresh`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): JsonObject {
            val resp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
            return loginJson(resp.bodyAsText())
        }

        val alex = login("alex@example.com")
        val bob = login("bob@example.com")
        val alexToken = field(alex, "token")
        val alexRefresh = field(alex, "refreshToken")
        val bobToken = field(bob, "token")
        val bobRefresh = field(bob, "refreshToken")

        val malformedLogout = client.post("/api/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, malformedLogout.status, malformedLogout.bodyAsText())

        val logout = client.post("/api/auth/logout") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            setBody("""{"refreshToken":"$alexRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, logout.status, logout.bodyAsText())
        assertTrue(logout.bodyAsText().contains("\"status\":\"ok\""), logout.bodyAsText())

        val reused = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$alexRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status, reused.bodyAsText())
        assertTrue(reused.bodyAsText().contains("登录已过期"), reused.bodyAsText())

        val bobStillOk = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$bobRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, bobStillOk.status, bobStillOk.bodyAsText())

        val bobMe = client.get("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, bobMe.status, bobMe.bodyAsText())

        val logoutAll = client.post("/api/auth/logout-all") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, logoutAll.status, logoutAll.bodyAsText())

        val staleMe = client.get("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, staleMe.status, staleMe.bodyAsText())

        val staleRefresh = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$bobRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, staleRefresh.status, staleRefresh.bodyAsText())
        assertTrue(staleRefresh.bodyAsText().contains("登录已过期"), staleRefresh.bodyAsText())
    }
}

private class AuthLogoutFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}
