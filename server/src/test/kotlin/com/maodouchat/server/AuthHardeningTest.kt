package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.plugins.isValidPassword
import com.maodouchat.server.plugins.maskEmail
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * 审计「登录/注册/搜索」三条加固的回归测试（G328c）。
 *
 * 对应审计里三处**未处理**的条目，本轮补上：
 * 1. 口令下限 6 → 8（并拒绝全同一个字符）；
 * 2. 登录审计日志不再落明文邮箱（改为脱敏）；
 * 3. 用户搜索的 email 列加了准入条件——原先 2 字符查询就能确认「某个地址是否注册过」。
 */
class AuthHardeningTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── 纯函数部分 ───

    @Test
    fun `password floor is eight characters`() {
        assertFalse(isValidPassword("1234567"), "7 位应被拒（旧下限是 6）")
        assertFalse(isValidPassword("123456"), "6 位应被拒")
        assertTrue(isValidPassword("12345678"), "8 位应通过")
        assertTrue(isValidPassword("correct horse battery staple"))
    }

    @Test
    fun `password is still capped at the bcrypt boundary`() {
        assertTrue(isValidPassword("a".repeat(71) + "b"))
        assertFalse(isValidPassword("a".repeat(73)), "超过 72 字节会被 BCrypt 静默截断，必须拒绝")
        // 多字节字符按 UTF-8 字节数算
        assertFalse(isValidPassword("密".repeat(25)), "25 个汉字 = 75 字节，应被拒")
    }

    @Test
    fun `single repeated character passwords are rejected`() {
        assertFalse(isValidPassword("aaaaaaaa"), "全同一个字符是最常见的弱口令形态")
        assertFalse(isValidPassword("!!!!!!!!"))
        assertTrue(isValidPassword("aaaaaaab"), "只有一个字符不同就应通过——不做组合规则")
    }

    @Test
    fun `maskEmail keeps enough for troubleshooting but drops the plaintext`() {
        val masked = maskEmail("alex@example.com")
        assertFalse(masked.contains("alex@"), "不得保留原地址：$masked")
        assertTrue(masked.endsWith("@example.com"), "应保留域名：$masked")
        assertTrue(masked.startsWith("a"), "应保留首字符：$masked")
        // 极短本地部分也不能泄露
        assertEquals("a***@x.com", maskEmail("a@x.com"))
        assertFalse(maskEmail("ab@x.com").contains("ab@"))
        // 非邮箱输入不炸
        assertEquals("", maskEmail(""))
        assertFalse(maskEmail("not-an-email").contains("not-an-email"))
    }

    // ─── 路由部分 ───

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:auth-hardening-${AtomicInteger().incrementAndGet()}-" +
                "${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        Database.connect(System.getProperty("DATABASE_URL"), driver = "org.h2.Driver")
        initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        configureRouting(
            userRepo,
            PostRepository(),
            object : AiGateway {
                override val model: String = "test-model"
            },
        )
    }

    /** 搜索接口返回的是用户数组（顶层 JsonArray），取其中的 name。 */
    private fun userNames(body: String): List<String> =
        (json.parseToJsonElement(body) as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            ?: emptyList()

    private suspend fun ApplicationTestBuilder.accessToken(): String {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (json.parseToJsonElement(response.bodyAsText()) as JsonObject)["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.register(email: String, password: String) =
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"probe","email":"$email","password":"$password"}""")
        }

    @Test
    fun `registration rejects a seven character password`() = testApplication {
        application { moduleUnderTest() }
        val response = register("shortpw@example.com", "1234567")
        assertTrue(
            response.status == HttpStatusCode.BadRequest,
            "7 位口令应被注册接口拒绝，实际 ${response.status}：${response.bodyAsText()}",
        )
    }

    @Test
    fun `registration accepts an eight character password`() = testApplication {
        application { moduleUnderTest() }
        val response = register("okpw@example.com", "abcd1234")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    /**
     * 用**专属探针用户**来隔离判据：`qx7z` 只出现在它的邮箱里，名字与用户名都不含，
     * 所以「命中/不命中」只可能来自 email 列。
     * （第一版测试用 `q=al` 断言，而 `al` 本来就按名字命中 Alice——前提就是错的。）
     */
    private suspend fun ApplicationTestBuilder.seedEmailProbe() {
        val response = register("qx7z@example.com", "abcd1234")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `short fragment does not match the email column`() = testApplication {
        application { moduleUnderTest() }
        seedEmailProbe()
        val token = accessToken()

        // 旧行为：`lower(email) like '%qx%'` 会命中 qx7z@example.com，
        // 于是 2 字符查询就成了「这个地址是否注册过」的探测器。
        val short = client.get("/api/users/search?q=qx") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, short.status, short.bodyAsText())
        val names = userNames(short.bodyAsText())
        assertTrue(names.isEmpty(), "2 字符片段不应经 email 命中任何人：$names")
    }

    @Test
    fun `full address still matches so add-by-email keeps working`() = testApplication {
        application { moduleUnderTest() }
        seedEmailProbe()
        val token = accessToken()

        val full = client.get("/api/users/search?q=qx7z@example.com") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, full.status, full.bodyAsText())
        assertEquals(listOf("probe"), userNames(full.bodyAsText()), "完整邮箱仍应命中（按邮箱加好友要能用）")
    }

    @Test
    fun `search still matches name and username`() = testApplication {
        application { moduleUnderTest() }
        val token = accessToken()
        val response = client.get("/api/users/search?q=alic") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val names = userNames(response.bodyAsText())
        assertTrue(
            names.any { it.startsWith("Alice", ignoreCase = true) },
            "按名字前缀搜索必须仍然可用：$names",
        )
    }
}
