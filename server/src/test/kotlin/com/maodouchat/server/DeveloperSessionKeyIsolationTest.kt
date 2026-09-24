package com.maodouchat.server

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * dev_session 与 access token 的密钥隔离（G328c）。
 *
 * 分离前：dev_session 用**同一把** JWT_SECRET、同一个 issuer 签发，于是 `JwtConfig.verifier`
 * 接受它——隔离完全依赖一个 `token_use` 字符串，且换密钥会同时作废两类令牌。
 * 分离后：dev_session 有自己的密钥与验证器，主验证器不再接受它。
 *
 * 单独一个类：`JwtConfig` 把密钥缓存在 val 里（进程内首次访问即固定），
 * 而 `forkEvery = 1` 是按**类**分 JVM，所以本类全程用同一个密钥，不与别的测试串味。
 */
class DeveloperSessionKeyIsolationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:dev-key-${AtomicInteger().incrementAndGet()}-" +
                "${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("DEVELOPER_USER_IDS", "u1")
        Database.connect(System.getProperty("DATABASE_URL"), driver = "org.h2.Driver")
        initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = SignalingRepository()
        val limiter = CallInviteRateLimiter()
        configureSockets(userRepo, signalingRepo = signalingRepo, callInviteRateLimiter = limiter)
        configureRouting(
            userRepo,
            PostRepository(),
            object : AiGateway {
                override val model: String = "test-model"
            },
            signalingRepo = signalingRepo,
            callInviteRateLimiter = limiter,
        )
        configureDeveloperRouting()
    }

    private suspend fun io.ktor.client.HttpClient.tokenFromLogin(path: String): String {
        val response = post(path) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (json.parseToJsonElement(response.bodyAsText()) as JsonObject)["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `dev session token is not accepted by the access token verifier`() = testApplication {
        application { moduleUnderTest() }
        val devToken = client.tokenFromLogin("/api/developer-account/login")

        // 关键性质：dev_session 用独立密钥签名，主验证器不再接受它。
        assertNull(
            JwtConfig.verifyToken(devToken),
            "dev_session 令牌不得被主 JWT 验证器接受——那正是分离要消除的耦合",
        )

        // 但开发者接口自己认它，否则就是把人锁在门外。
        val ok = client.get("/api/developer-account/me") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
    }

    @Test
    fun `access token is not accepted as a dev session`() = testApplication {
        application { moduleUnderTest() }
        val accessToken = client.tokenFromLogin("/api/auth/login")

        val denied = client.get("/api/developer-account/me") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            denied.status,
            "登录令牌不得当开发者会话使用：${denied.bodyAsText()}",
        )
    }
}
