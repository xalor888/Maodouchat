package com.maodouchat.server

import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G56 安全网：管理后台**批量**操作端点的行为契约。
 *
 * 这些端点在 `AdminBulkRouting` 里裸写 `ModerationAuditLog` / 查 `AiAuditLogs`，
 * 下沉前先把行为钉住——尤其三条容易被「顺手简化」改坏的语义：
 * 1. **不碰其他管理员、不碰自己**（否则一次批量请求就能把管理员集体踢下线）；
 * 2. **缺失用户必须进 skipped**（不能静默当成成功，也不能 500）；
 * 3. **会话真的失效**（token version 被顶掉后旧 token 立刻 401）。
 * 另加 AI 用量导出的「纯元数据」边界（CSV 不得含 chatId / prompt / 正文）。
 */
class AdminBulkOperationRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-bulk-${AtomicInteger().incrementAndGet()}-${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("MASTER_ADMINS", "u1,u2")
        Database.connect(System.getProperty("DATABASE_URL"), driver = "org.h2.Driver")
        com.maodouchat.server.db.initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = com.maodouchat.server.repository.SignalingRepository()
        val callInviteRateLimiter = CallInviteRateLimiter()
        configureSockets(userRepo, signalingRepo = signalingRepo, callInviteRateLimiter = callInviteRateLimiter)
        configureRouting(
            userRepo,
            PostRepository(),
            object : AiGateway {
                override val model: String = "test-model"
            },
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter,
        )
        configureAdminEnhanceRouting(
            announcementRepo = com.maodouchat.server.repository.AnnouncementRepository(),
            userTagRepo = com.maodouchat.server.repository.UserTagRepository(),
            rateLimitStatsRepo = RateLimitStatsRepository(),
        )
    }

    private suspend fun io.ktor.client.HttpClient.login(email: String): String {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (json.parseToJsonElement(response.bodyAsText()) as JsonObject)["token"]!!.jsonPrimitive.content
    }

    private suspend fun io.ktor.client.HttpClient.adminSession(userToken: String): String {
        val response = post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (json.parseToJsonElement(response.bodyAsText()) as JsonObject)["token"]!!.jsonPrimitive.content
    }

    private fun stringArray(body: String, key: String): List<String> =
        json.parseToJsonElement(body).jsonObject[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /**
     * 用旧 token 打一个**普通用户**接口，确认它是否仍然有效。
     * 注意：admin session token 只在 `admin-jwt` 方案下有效，不能拿它探 `/api/users/me`。
     */
    private suspend fun io.ktor.client.HttpClient.stillAuthorized(token: String): Boolean {
        val response = get("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return response.status == HttpStatusCode.OK
    }

    /** admin session 是否仍然有效（用 admin-jwt 方案下的接口探）。 */
    private suspend fun io.ktor.client.HttpClient.adminSessionStillAuthorized(token: String): Boolean {
        val response = get("/api/admin/user-tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return response.status == HttpStatusCode.OK
    }

    @Test
    fun `bulk force logout skips other admins and self but really invalidates the rest`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))
        // bob 是第三个 demo 用户：既不是管理员，也不是操作者本人
        val bobToken = client.login("bob@example.com")

        val response = client.post("/api/admin/users/bulk-force-logout") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            // 故意混入：另一个管理员 u2、操作者自己 u1、不存在的用户、真实目标 u3
            setBody("""{"userIds":["u1","u2","u3","no-such-user"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val body = response.bodyAsText()
        val loggedOut = stringArray(body, "loggedOut")
        val skippedAdmins = stringArray(body, "skippedAdmins")
        val skippedMissing = stringArray(body, "skippedMissing")

        assertTrue("u3" in loggedOut, "真实目标应当被登出：$body")
        // 契约：bulk-force-logout 只跳过「其他管理员」，允许把操作者自己一并踢下线
        assertTrue("u2" in skippedAdmins, "其他管理员必须被跳过：$body")
        assertTrue("u1" in loggedOut, "操作者本人在 bulk-force-logout 里是允许下线的：$body")
        assertTrue("no-such-user" in skippedMissing, "缺失用户必须进 skipped：$body")
        assertFalse(loggedOut.contains("u2"), "其他管理员不该出现在成功列表：$body")

        // 真失效：bob 的旧 token 必须打不动了
        assertTrue(client.stillAuthorized(bobToken).not(), "bob的旧 token 必须失效")
        // 契约：操作者本人在 bulk-force-logout 里允许下线，所以 admin token 自己也会失效
        assertTrue(client.adminSessionStillAuthorized(adminToken).not(), "self 也在批量范围内，admin token 应一并失效")
        // 被跳过的其他管理员（u2）会话不受影响
        val otherAdminToken = client.adminSession(client.login("alice@example.com"))
        assertTrue(client.adminSessionStillAuthorized(otherAdminToken), "被跳过的其他管理员会话不应受影响")
    }

    @Test
    fun `bulk set suspend until skips admins and self and invalidates only the suspended`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))
        val bobToken = client.login("bob@example.com")
        val until = System.currentTimeMillis() + 3_600_000L

        val response = client.post("/api/admin/users/bulk-set-suspend-until") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["u1","u2","u3"],"suspendedUntil":$until}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue("u3" in stringArray(body, "updated"), "真实目标应被更新：$body")
        assertTrue("u1" in stringArray(body, "skipped"), "操作者自己必须跳过：$body")
        assertTrue("u2" in stringArray(body, "skipped"), "其他管理员必须跳过：$body")

        // 封禁生效 → bob 旧 token 失效；管理员不受影响
        assertTrue(client.stillAuthorized(bobToken).not(), "bob的旧 token 必须失效")
        assertTrue(client.adminSessionStillAuthorized(adminToken), "被跳过的管理员自己的会话不应被牵连")

        // 封禁期间 bob 登不进来（证明 suspend 真的生效）
        val blocked = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, blocked.status, "封禁期间不得放行登录：${blocked.bodyAsText()}")

        // 解除封禁后 bob 立刻能重新登录（证明 suspend 是可逆的真状态，不是把账号写坏）
        val cleared = client.post("/api/admin/users/bulk-clear-suspend") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["u3"]}""")
        }
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        val relogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, relogin.status, relogin.bodyAsText())
    }

    @Test
    fun `bulk force token bump skips admins and self and rotates the rest`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))
        val bobToken = client.login("bob@example.com")

        val response = client.post("/api/admin/users/bulk-force-token-bump") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["u1","u2","u3","ghost"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue("u3" in stringArray(body, "updated"), "真实目标应被顶号：$body")
        assertTrue("u1" in stringArray(body, "skipped"), "操作者自己必须跳过：$body")
        assertTrue("u2" in stringArray(body, "skipped"), "其他管理员必须跳过：$body")
        assertTrue("ghost" in stringArray(body, "skipped"), "缺失用户必须跳过：$body")

        assertTrue(client.stillAuthorized(bobToken).not(), "bob的旧 token 必须失效")
        assertTrue(client.adminSessionStillAuthorized(adminToken), "被跳过的管理员自己的会话不应被牵连")
    }

    @Test
    fun `ai usage export is metadata only csv with the documented header`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))

        // 先造一条 AI 审计行（带 chatId，用来证明它不会出现在导出里）
        val aiRepo = com.maodouchat.server.repository.AiRepository()
        aiRepo.recordAudit(
            userId = "u2",
            chatId = "chat-secret",
            feature = "summary",
            model = "test-model",
            status = "ok",
            inputChars = 120,
            contextMessages = 3,
            durationMs = 42L,
            error = null,
            inputTokens = 11L,
            outputTokens = 5L,
        )

        val response = client.get("/api/admin/ai-usage-export?limit=50") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val csv = response.bodyAsText()

        assertTrue(
            csv.startsWith("id,userId,feature,status,inputChars,contextMessages,durationMs,error,createdAt,inputTokens,outputTokens"),
            "CSV 表头必须保持契约：${csv.take(120)}",
        )
        assertTrue(csv.contains("summary"), "应导出 feature 元数据：${csv.take(200)}")
        assertTrue(csv.contains("120"), "应导出 inputChars 元数据：${csv.take(200)}")
        assertTrue(csv.contains("11") && csv.contains("5"), "应导出真实 token 数：${csv.take(200)}")
        // 元数据边界：不得泄漏 chatId / prompt / 正文
        assertFalse(csv.contains("chat-secret"), "AI 用量导出泄漏了 chatId：${csv.take(300)}")
        assertFalse(csv.lowercase().contains("prompt"), "AI 用量导出泄漏了 prompt")
        assertFalse(csv.lowercase().contains("\"body\""), "AI 用量导出泄漏了 body 字段")
    }
}
