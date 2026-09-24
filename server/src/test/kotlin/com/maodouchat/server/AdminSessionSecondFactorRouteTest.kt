package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 管理后台二次确认的**第二因子**（TOTP / 恢复码）。
 *
 * 修的是什么：`/api/admin/session` 此前只校验口令，请求体 `AdminSessionRequest` 里
 * 根本没有动态码字段——账号即使开着 TOTP，「口令 + 任意有效 access token」就能换发
 * 5 分钟全权限管理 token。2FA 在应用登录生效，却在唯一的提权入口被整条绕过。
 *
 * 为什么访问令牌在开启 TOTP **之前**取得：登录路径的重放守卫（`TotpService` 的
 * 进程内守卫 + 8.51 的 DB CAS）会拒绝「同一 30 秒窗口内第二次使用同一个码」——
 * 那是登录路径的正确行为。测试若在开启 2FA 之后再登录，就必须干等 30 秒换新码，
 * 既慢又在窗口边界上不稳定。令牌先取得既避开这一点，也正好就是被修的那个威胁模型：
 * 持有一个**合法取得**的 access token + 口令，但拿不出动态码。
 */
class AdminSessionSecondFactorRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class TestAiGateway : AiGateway {
        override val model: String = "test-model"
    }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-mfa-${kotlin.random.Random.nextInt(1_000_000)}-" +
                "${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("MASTER_ADMINS", "u1")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        configureRouting(userRepo = userRepo, postRepo = PostRepository(), aiGateway = TestAiGateway())
    }

    private suspend fun ApplicationTestBuilder.accessTokenFor(totpCode: String = ""): String {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123","totpCode":"$totpCode"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val token = (body(response.bodyAsText()))["token"]?.jsonPrimitive?.contentOrNull
        assertTrue(!token.isNullOrBlank(), "登录应返回 token：${response.bodyAsText()}")
        return token
    }

    /**
     * 走真实 API 给 alex（u1）开启 TOTP 并返回 (secret, 8 个恢复码)。
     * [accessToken] 是该账号在**未启用 2FA 时**取得的普通登录令牌。
     */
    private suspend fun ApplicationTestBuilder.enableTotpForAdmin(
        accessToken: String,
    ): Pair<String, List<String>> {
        val setup = client.post("/api/auth/totp/setup") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, setup.status, setup.bodyAsText())
        val secret = body(setup.bodyAsText())["secret"]!!.jsonPrimitive.content
        val confirm = client.post("/api/auth/totp/confirm") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"${testTotpCode(secret)}"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status, confirm.bodyAsText())
        val codes = body(confirm.bodyAsText())["backupCodes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(8, codes.size, "confirm 应返回 8 个恢复码")
        return secret to codes
    }

    private suspend fun ApplicationTestBuilder.adminSession(
        accessToken: String,
        password: String = "password123",
        totpCode: String? = null,
    ): HttpResponse =
        client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                if (totpCode == null) {
                    """{"password":"$password"}"""
                } else {
                    """{"password":"$password","totpCode":"$totpCode"}"""
                },
            )
        }

    private fun body(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    private fun errorCode(text: String): String? = body(text)["code"]?.jsonPrimitive?.contentOrNull

    /** 取一个**确定不等于**当前 ±1 窗口内任一有效码的 6 位数（避免 1e-6 级偶发通过）。 */
    private fun wrongCodeFor(secret: String): String {
        val now = System.currentTimeMillis()
        val valid = setOf(
            testTotpCode(secret, now),
            testTotpCode(secret, now - TOTP_PERIOD_SEC * 1000L),
            testTotpCode(secret, now + TOTP_PERIOD_SEC * 1000L),
        )
        return listOf("000000", "111111", "222222", "999999").first { it !in valid }
    }

    @Test
    fun `password only is refused once TOTP is enabled`() = testApplication {
        application { moduleUnderTest() }
        val token = accessTokenFor()
        enableTotpForAdmin(token)

        val response = adminSession(token)
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.Unauthorized, response.status, text)
        assertEquals("TOTP_REQUIRED", errorCode(text))
        assertNull(body(text)["token"], "缺第二因子时不能下发任何 token")
    }

    @Test
    fun `wrong code is refused`() = testApplication {
        application { moduleUnderTest() }
        val token = accessTokenFor()
        val (secret, _) = enableTotpForAdmin(token)

        val response = adminSession(token, totpCode = wrongCodeFor(secret))
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.Unauthorized, response.status, text)
        assertTrue(
            errorCode(text) in setOf("TOTP_INVALID", "TOTP_REQUIRED"),
            "错误动态码必须被拒：$text",
        )
        assertNull(body(text)["token"], "第二因子错误时不能下发 token")
    }

    @Test
    fun `correct code is accepted`() = testApplication {
        application { moduleUnderTest() }
        val token = accessTokenFor()
        val (secret, _) = enableTotpForAdmin(token)

        val response = adminSession(token, totpCode = testTotpCode(secret))
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        assertTrue(!body(text)["token"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(), "应下发管理会话 token：$text")
        assertTrue(body(text)["expiresAt"] != null, "应带过期时间")
    }

    @Test
    fun `code already consumed earlier in the same window is still accepted here`() = testApplication {
        application { moduleUnderTest() }
        val token = accessTokenFor()
        val (secret, _) = enableTotpForAdmin(token)

        // 这一条钉住刻意的语义选择：管理会话确认发生在刚完成 2FA 之后，因此接受同一
        // 时间窗内**已被前一步消费过**的验证码（trackReplay = false，与 disableTotp 同一先例）。
        // 若有人把它改成走重放计数器 CAS，这里会立刻红——那次改动必须重新评估
        // 「主管理员刚验证完还要干等 30 秒才能进后台」这个代价。
        val sameWindowCode = testTotpCode(secret)
        val response = adminSession(token, totpCode = sameWindowCode)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `backup code is accepted once and consumed`() = testApplication {
        application { moduleUnderTest() }
        val token = accessTokenFor()
        val (_, codes) = enableTotpForAdmin(token)
        val backup = codes.first()

        val first = adminSession(token, totpCode = backup)
        assertEquals(HttpStatusCode.OK, first.status, "恢复码应可作为第二因子：${first.bodyAsText()}")

        val second = adminSession(token, totpCode = backup)
        assertEquals(HttpStatusCode.Unauthorized, second.status, "同一个恢复码不得二次使用")
    }

    @Test
    fun `password only still works when TOTP is off`() = testApplication {
        application { moduleUnderTest() }
        // 负控制：没开 TOTP 的账号路径不能被这次加固顺手弄坏。
        val token = accessTokenFor()

        val response = adminSession(token)
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        assertTrue(!body(text)["token"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(), "未开 TOTP 时应照常换发 token：$text")
    }

    @Test
    fun `wrong password is still reported as password error`() = testApplication {
        application { moduleUnderTest() }
        val token = accessTokenFor()
        val (secret, _) = enableTotpForAdmin(token)

        val response = adminSession(token, password = "definitely-not-the-password", totpCode = testTotpCode(secret))
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.Unauthorized, response.status, text)
        // 口令错与动态码错必须可区分：前者不带 code，后者带 TOTP_*
        assertNull(errorCode(text), "口令错误不应报成动态码问题：$text")
    }
}
