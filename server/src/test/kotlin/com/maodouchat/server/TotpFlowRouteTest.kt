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
import com.maodouchat.server.plugins.configureAiEnhanceRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.TotpService
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 8.33 补测 P0：TOTP 双因素（此前零覆盖）。
 * 纯逻辑层（TotpService）+ 完整 API 流程（setup→confirm→requiresTotp→disable）。
 * 独立 JVM（forkEvery=1），TOTP 计算与服务端同算法（RFC 6238 / SHA-1 / 30s / 6 位）。
 */
private const val TOTP_PERIOD_SEC = 30L
private const val TOTP_DIGITS = 6

/** 测试侧 RFC 6238 生成器：与服务端 TotpService.generateCode 一致 */
private fun testTotpCode(secretBase32: String, nowMs: Long = System.currentTimeMillis()): String {
    val cleaned = secretBase32.trim().uppercase().replace("=", "").replace(" ", "")
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var buffer = 0
    var bitsLeft = 0
    val out = ArrayList<Byte>()
    for (ch in cleaned) {
        val idx = alphabet.indexOf(ch)
        check(idx >= 0) { "invalid base32 char: $ch" }
        buffer = (buffer shl 5) or idx
        bitsLeft += 5
        if (bitsLeft >= 8) {
            out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
            bitsLeft -= 8
        }
    }
    val counter = nowMs / 1000L / TOTP_PERIOD_SEC
    val data = ByteBuffer.allocate(8).putLong(counter).array()
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(out.toByteArray(), "HmacSHA1"))
    val hash = mac.doFinal(data)
    val offset = hash.last().toInt() and 0x0f
    val binary =
        ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
    val otp = binary % 10.0.pow(TOTP_DIGITS).toInt()
    return otp.toString().padStart(TOTP_DIGITS, '0')
}

class TotpServiceLogicTest {
    @Test
    fun `generated secret is valid base32 and verify accepts current code`() {
        val secret = TotpService.generateSecret()
        assertTrue(secret.length >= 32, "secret=$secret")
        val now = System.currentTimeMillis()
        val code = testTotpCode(secret, now)
        assertTrue(TotpService.verify(secret, code, nowMs = now), "code=$code secret=$secret")
    }

    @Test
    fun `verify rejects wrong code and accepts adjacent window`() {
        val secret = TotpService.generateSecret()
        val counter = 10_000L
        val now = counter * TOTP_PERIOD_SEC * 1000L + 15_000L
        val code = testTotpCode(secret, now)
        assertFalse(TotpService.verify(secret, "000000", nowMs = now))
        assertFalse(TotpService.verify(secret, "12345", nowMs = now), "short code rejected")
        assertFalse(TotpService.verify(secret, "", nowMs = now))
        // 上一个窗口（counter-1）在 window=1 容差内仍通过
        val prevNow = (counter - 1) * TOTP_PERIOD_SEC * 1000L + 15_000L
        assertTrue(TotpService.verify(secret, code, nowMs = prevNow))
    }

    @Test
    fun `verify rejects replay of the same window code`() {
        val secret = TotpService.generateSecret()
        val now = System.currentTimeMillis()
        val code = testTotpCode(secret, now)
        assertTrue(TotpService.verify(secret, code, nowMs = now))
        assertFalse(TotpService.verify(secret, code, nowMs = now), "same counter must not be accepted twice")
    }

    @Test
    fun `provisioning uri embeds secret and standard params`() {
        val secret = TotpService.generateSecret()
        val uri = TotpService.provisioningUri(secret, "alex@example.com")
        assertTrue(uri.startsWith("otpauth://totp/"), uri)
        assertTrue(uri.contains("secret=$secret"), uri)
        assertTrue(uri.contains("algorithm=SHA1"), uri)
        assertTrue(uri.contains("digits=6"), uri)
        assertTrue(uri.contains("period=30"), uri)
        assertTrue(uri.contains("issuer="), uri)
    }

    @Test
    fun `verify handles invalid secrets safely`() {
        assertFalse(TotpService.verify("", "123456", nowMs = 0L))
        assertFalse(TotpService.verify("!!!!not-base32!!!!", "123456", nowMs = 0L))
        assertFalse(TotpService.verify("A".repeat(100), "123456", nowMs = 0L))
    }
}

class TotpFlowRouteTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest(seedDemoUsers: Boolean = false, aiGateway: AiGateway = TotpFlowFakeAiGateway()) {
        System.setProperty("DATABASE_URL",
            "jdbc:h2:mem:totp-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1")
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
            announcementRepo = com.maodouchat.server.repository.AnnouncementRepository(),
            userTagRepo = com.maodouchat.server.repository.UserTagRepository(),
            rateLimitStatsRepo = com.maodouchat.server.repository.RateLimitStatsRepository()
        )
        configureSecretSurfaceRouting(chatRepo = chatRepo, messageRepo = messageRepo)
    }

    private fun extractToken(body: String): String =
        (Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    @Test
    fun `totp setup confirm login requires code and disable restores plain login`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(payload: String): HttpResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        val alexToken = extractToken(login("""{"email":"alex@example.com","password":"password123"}""").bodyAsText())

        // 初始未启用
        val status0 = client.get("/api/auth/totp/status") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, status0.status, status0.bodyAsText())
        assertTrue(status0.bodyAsText().contains("\"enabled\":false"), status0.bodyAsText())

        // setup 返回 secret 与 otpauth uri
        val setup = client.post("/api/auth/totp/setup") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, setup.status, setup.bodyAsText())
        val setupJson = json.parseToJsonElement(setup.bodyAsText()).jsonObject
        val secret = setupJson["secret"]!!.jsonPrimitive.content
        assertTrue(secret.length >= 32, secret)
        assertTrue(setupJson["otpauthUrl"]!!.jsonPrimitive.content.contains("secret=$secret"))

        // 错误验证码 → 400 TOTP_INVALID（确认未生效）
        val badConfirm = client.post("/api/auth/totp/confirm") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"000000"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badConfirm.status, badConfirm.bodyAsText())
        assertTrue(badConfirm.bodyAsText().contains("TOTP_INVALID"), badConfirm.bodyAsText())

        // 正确验证码 → 启用
        val code = testTotpCode(secret)
        val confirm = client.post("/api/auth/totp/confirm") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status, confirm.bodyAsText())
        assertTrue(confirm.bodyAsText().contains("\"enabled\":true"), confirm.bodyAsText())

        val status1 = client.get("/api/auth/totp/status") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertTrue(status1.bodyAsText().contains("\"enabled\":true"), status1.bodyAsText())

        // 登录：纯密码 → 200 requiresTotp=true 且 token 为空
        val noCode = login("""{"email":"alex@example.com","password":"password123"}""")
        assertEquals(HttpStatusCode.OK, noCode.status, noCode.bodyAsText())
        assertTrue(noCode.bodyAsText().contains("\"requiresTotp\":true"), noCode.bodyAsText())

        // 登录：错误 TOTP → 仍 requiresTotp（不泄露，且计入失败计数）
        val badCode = login("""{"email":"alex@example.com","password":"password123","totpCode":"000000"}""")
        assertEquals(HttpStatusCode.OK, badCode.status, badCode.bodyAsText())
        assertTrue(badCode.bodyAsText().contains("\"requiresTotp\":true"), badCode.bodyAsText())

        // 登录：正确 TOTP → 完整 token。confirm 已消费当前窗口验证码并推进 totpLastCounter，
        // 防重放会拒绝同窗口的重复码——用下一 30s 窗口生成登录码（counter 递增，可被接受）。
        val loginCode = testTotpCode(secret, System.currentTimeMillis() + 30_000L)
        val withCode = login("""{"email":"alex@example.com","password":"password123","totpCode":"$loginCode"}""")
        assertEquals(HttpStatusCode.OK, withCode.status, withCode.bodyAsText())
        assertNotNull(extractToken(withCode.bodyAsText()))

        // disable：错误验证码被拒
        val badDisable = client.post("/api/auth/totp/disable") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"000000"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badDisable.status, badDisable.bodyAsText())

        // disable：正确验证码 → 关闭。disable 只校验时限窗口内有效，不受登录防重放计数器约束；
        // 但需用 ≥ 上次已验证计数器的窗口码（+30s），否则被 in-memory 防重放守卫拒绝。
        val disableCode = testTotpCode(secret, System.currentTimeMillis() + 30_000L)
        val disable = client.post("/api/auth/totp/disable") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$disableCode"}""")
        }
        assertEquals(HttpStatusCode.OK, disable.status, disable.bodyAsText())
        assertTrue(disable.bodyAsText().contains("\"enabled\":false"), disable.bodyAsText())

        // 关闭后：纯密码直接登录成功
        val plain = login("""{"email":"alex@example.com","password":"password123"}""")
        assertEquals(HttpStatusCode.OK, plain.status, plain.bodyAsText())
        assertTrue(extractToken(plain.bodyAsText()).isNotBlank(), plain.bodyAsText())
    }
}

private class TotpFlowFakeAiGateway : AiGateway {
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
    ): AiGatewayResult<List<String>> = AiGatewayResult.Success(listOf("好的", "我看看").take(count), model)

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
