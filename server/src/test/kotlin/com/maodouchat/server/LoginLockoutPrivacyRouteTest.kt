package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 8.33 补测 P0：登录锁定 / 拉黑双向语义（此前零覆盖的安全敏感路径）。
 * 独立 JVM（forkEvery=1）；锁定表为 configureRouting 局部状态，每次 testApplication 重建。
 */
class LoginLockoutPrivacyRouteTest {

    private fun Application.moduleUnderTest(seedDemoUsers: Boolean = false, aiGateway: AiGateway = LockPrivacyFakeAiGateway()) {
        System.setProperty("DATABASE_URL",
            "jdbc:h2:mem:lock-privacy-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1")
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", seedDemoUsers.toString())
        // 登录/注册 IP 与账号限流默认 10/min；本测试类大量连续登录，放宽以专注锁定逻辑本身
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
            announcementRepo = com.maodouchat.server.repository.AnnouncementRepository(),
            userTagRepo = com.maodouchat.server.repository.UserTagRepository(),
            rateLimitStatsRepo = com.maodouchat.server.repository.RateLimitStatsRepository()
        )
        configureSecretSurfaceRouting(userRepo = userRepo)
    }

    private fun extractToken(body: String): String =
        (Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    private fun extractChatId(body: String): String =
        (Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as JsonObject)["id"]!!.jsonPrimitive.content

    private fun loginBody(email: String, password: String) = """{"email":"$email","password":"$password"}"""

    @Test
    fun `login locks account after 5 failures and rejects correct password while locked`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        // 5 次错误密码 → 401
        repeat(5) {
            val resp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alex@example.com", "wrong-pass"))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, resp.bodyAsText())
        }
        // 第 6 次：已锁定 → 429 + ACCOUNT_LOCKED（即使密码正确）
        val locked = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status, locked.bodyAsText())
        assertTrue(locked.bodyAsText().contains("ACCOUNT_LOCKED"), locked.bodyAsText())
        // 锁定期间正确密码持续被拒，且不泄露密码正误（仍为 ACCOUNT_LOCKED）
        val during = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, during.status, during.bodyAsText())
        assertTrue(during.bodyAsText().contains("ACCOUNT_LOCKED"), during.bodyAsText())
        // 其他账号不受影响
        val other = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("bob@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.OK, other.status, other.bodyAsText())
    }

    @Test
    fun `successful login resets failure counter`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        // 4 次错误 → 未达阈值
        repeat(4) {
            val resp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alex@example.com", "wrong-pass"))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, resp.bodyAsText())
        }
        // 正确密码登录成功（计数清零）
        val ok = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        // 再错 5 次 → 第 5 次即触发锁定
        repeat(4) {
            val resp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alex@example.com", "wrong-pass"))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, resp.bodyAsText())
        }
        val fifth = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "wrong-pass"))
        }
        assertEquals(HttpStatusCode.Unauthorized, fifth.status, fifth.bodyAsText())
        val locked = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status, locked.bodyAsText())
        assertTrue(locked.bodyAsText().contains("ACCOUNT_LOCKED"), locked.bodyAsText())
    }

    @Test
    fun `block hides profile and rejects chat recreation from blocked party`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val alexToken = extractToken(client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "password123"))
        }.bodyAsText())
        val bobToken = extractToken(client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("bob@example.com", "password123"))
        }.bodyAsText())

        // 1) 拉黑前先建好 1:1 私聊（拉黑后无法再建，模拟「先有会话后拉黑」）
        val create = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u3"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val chatId = extractChatId(create.bodyAsText())

        // 2) A（u1=alex）拉黑 B（u3=bob）
        val block = client.post("/api/users/block/u3") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, block.status, block.bodyAsText())

        // 3) 被拉黑方 B 查 A 资料 → 404（双向拉黑过滤）
        val profile = client.get("/api/users/u1") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.NotFound, profile.status, profile.bodyAsText())

        // 4) B 重建 1:1 私聊被拒（拉黑预检 → 403）
        val recreate = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u1"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Forbidden, recreate.status, recreate.bodyAsText())

    }

    @Test
    fun `unblock restores profile visibility`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val alexToken = extractToken(client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("alex@example.com", "password123"))
        }.bodyAsText())
        val bobToken = extractToken(client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("bob@example.com", "password123"))
        }.bodyAsText())

        val block = client.post("/api/users/block/u3") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, block.status, block.bodyAsText())
        val hidden = client.get("/api/users/u1") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.NotFound, hidden.status, hidden.bodyAsText())

        val unblock = client.delete("/api/users/block/u3") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, unblock.status, unblock.bodyAsText())
        val visible = client.get("/api/users/u1") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, visible.status, visible.bodyAsText())
    }
}

private class LockPrivacyFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}
