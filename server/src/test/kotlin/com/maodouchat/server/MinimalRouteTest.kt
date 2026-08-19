package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.plugins.bearerTokenOrNull
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAiEnhanceRouting
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.plugins.isAllowedWebhookAddress
import com.maodouchat.server.plugins.postPinnedWebhookJson
import com.maodouchat.server.plugins.readPinnedWebhookResponse
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.signal.libsignal.protocol.ecc.Curve
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 服务端路由骨架测试。
 *
 * 架构要点：
 * - 每个测试类只放 1-2 个最小化的断言；配合 build.gradle.kts 中 `forkEvery = 1`，
 *   每个测试类跑在独立 JVM 进程 → 彻底避免 Ktor 2.3.7 + H2 in-memory
 *   的 Exposed TransactionManager 跨 testApplication 同进程串台。
 *
 * 覆盖链：
 * - 1. 健康检查
 * - 2. 注册：空 body → 400；合法 body → 200 + token
 * - 3. 用户列表：Bearer token 登录 + 不泄漏 email
 * - 4. 发帖：认证 + 返回 JSON + feed 可见
 * - 5. 消息：合法类型 + 非法类型校验
 */

private fun freshDbUrl(): String =
    "jdbc:h2:mem:test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

private fun Application.moduleUnderTest(seedDemoUsers: Boolean = false, aiGateway: AiGateway = FakeAiGateway()) {
    System.setProperty("DATABASE_URL", freshDbUrl())
    System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
    System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
    System.setProperty("SEED_DEMO_USERS", seedDemoUsers.toString())
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
    // B1-B8 新增路由（与 Application.kt 生产注册一致，供测试覆盖）
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
    configureSecretSurfaceRouting(chatRepo = chatRepo, messageRepo = messageRepo, userRepo = userRepo)
}

class RefreshSessionIsolationRouteTest {
    @Test
    fun `refresh reuse revokes only its login session`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(): JsonObject {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alex@example.com","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }

        suspend fun refresh(token: String): HttpResponse = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$token"}""")
        }

        val firstLogin = login()
        val secondLogin = login()
        val firstRefresh = firstLogin["refreshToken"]!!.jsonPrimitive.content
        val secondRefresh = secondLogin["refreshToken"]!!.jsonPrimitive.content

        val firstRotation = refresh(firstRefresh)
        assertEquals(HttpStatusCode.OK, firstRotation.status, firstRotation.bodyAsText())
        val firstRotatedAccess = extractToken(firstRotation.bodyAsText())

        val replay = refresh(firstRefresh)
        assertEquals(HttpStatusCode.Unauthorized, replay.status, replay.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $firstRotatedAccess")
        }.status)

        val unaffectedRotation = refresh(secondRefresh)
        assertEquals(HttpStatusCode.OK, unaffectedRotation.status, unaffectedRotation.bodyAsText())
    }
}

class BotTokenRouteIsolationTest {
    @Test
    fun `bot API accepts bot token without user JWT`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val userToken = extractToken(login.bodyAsText())
        val created = client.post("/api/bots") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Route Bot","username":"route_isolation_bot"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val botToken = Json.parseToJsonElement(created.bodyAsText()).jsonObject["tokenOnce"]!!.jsonPrimitive.content

        val me = client.get("/api/bot/me") {
            header("X-Bot-Token", botToken)
        }
        assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
        assertTrue(me.bodyAsText().contains("route_isolation_bot"), me.bodyAsText())
        val malformedBearer = client.get("/api/bot/me") {
            header(HttpHeaders.Authorization, "Bearer$botToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, malformedBearer.status)
        val malformedLogEvent = client.post("/api/bot/logEvent") {
            header("X-Bot-Token", botToken)
            contentType(ContentType.Application.Json)
            setBody("""{"event":"test","chatId":{"nested":"invalid"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformedLogEvent.status, malformedLogEvent.bodyAsText())
    }
}

class RoutingSecurityHelperTest {
    @Test
    fun `bearer parsing and webhook address policy fail closed`() {
        assertEquals("token", "Bearer token".bearerTokenOrNull())
        assertEquals("token", "bearer   token".bearerTokenOrNull())
        assertEquals(null, "BearerToken".bearerTokenOrNull())
        assertEquals(null, "Basic token".bearerTokenOrNull())
        assertFalse(java.net.InetAddress.getByName("100.64.0.1").isAllowedWebhookAddress(allowLoopback = false))
        assertFalse(java.net.InetAddress.getByName("169.254.169.254").isAllowedWebhookAddress(allowLoopback = false))
        assertFalse(java.net.InetAddress.getByName("168.63.129.16").isAllowedWebhookAddress(allowLoopback = false))
        assertFalse(java.net.InetAddress.getByName("192.88.99.1").isAllowedWebhookAddress(allowLoopback = false))
        assertFalse(java.net.InetAddress.getByName("64:ff9b::7f00:1").isAllowedWebhookAddress(allowLoopback = false))
        assertFalse(java.net.InetAddress.getByName("2001:db8::1").isAllowedWebhookAddress(allowLoopback = false))
        assertTrue(java.net.InetAddress.getByName("8.8.8.8").isAllowedWebhookAddress(allowLoopback = false))
        assertTrue(java.net.InetAddress.getByName("2606:4700:4700::1111").isAllowedWebhookAddress(allowLoopback = false))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://[::]/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://[::127.0.0.1]/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://[::ffff:127.0.0.1]/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://[0:0:0:0:0:0:0:1]/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://[64:ff9b::7f00:1]/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://100.64.0.1/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://2130706433/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://0x7f000001/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://0177.0.0.1/hook"))
        assertFalse(BotRepository.isAllowedWebhookUrl("https://127.1/hook"))
        assertTrue(BotRepository.isAllowedWebhookUrl("https://example.com/hook"))
        assertTrue(BotRepository.isAllowedWebhookUrl("https://1.2.3.4/hook"))
        assertTrue(BotRepository.isAllowedWebhookUrl("https://[2606:4700:4700::1111]/hook"))
        assertFailsWith<IllegalArgumentException> {
            postPinnedWebhookJson(
                url = "https://example.com:0/hook",
                body = "{}",
                headers = emptyMap(),
                connectTimeoutMs = 1,
                readTimeoutMs = 1
            )
        }

        val informationalThenOk = readPinnedWebhookResponse(
            "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                .byteInputStream(Charsets.US_ASCII),
            maxBodyBytes = 16
        )
        assertEquals(200, informationalThenOk.statusCode)
        assertEquals("ok", informationalThenOk.body)
        assertFailsWith<IllegalStateException> {
            readPinnedWebhookResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx"
                    .byteInputStream(Charsets.US_ASCII),
                maxBodyBytes = 16
            )
        }
    }
}

private val testJson = Json { ignoreUnknownKeys = true }

private fun extractToken(body: String): String =
    (testJson.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

private class FakeAiGateway : AiGateway {
    override val model: String = "test-model"

    override suspend fun rewrite(
        text: String,
        mode: String,
        targetLanguage: String?,
        styleHint: String?
    ): AiGatewayResult<String> {
        return AiGatewayResult.Success("改写：${text.trim()}", model)
    }

    override suspend fun suggestReplies(
        messages: List<com.maodouchat.server.model.AiContextMessage>,
        tone: String,
        count: Int
    ): AiGatewayResult<List<String>> {
        return AiGatewayResult.Success(listOf("好的", "我看看", "稍后回复").take(count), model)
    }

    override suspend fun summarize(
        messages: List<com.maodouchat.server.model.AiContextMessage>,
        style: String
    ): AiGatewayResult<String> {
        return AiGatewayResult.Success("总结：${messages.size} 条消息", model)
    }

    override suspend fun groupAssistant(
        query: String,
        messages: List<com.maodouchat.server.model.AiContextMessage>,
        mode: String
    ): AiGatewayResult<com.maodouchat.server.model.AiGroupAssistantResult> {
        val tasks = if (mode == "tasks") {
            listOf(com.maodouchat.server.model.AiGroupTask("发布新版本", "Alice", "周六十点", 1_700_000_000_000))
        } else {
            emptyList()
        }
        return AiGatewayResult.Success(com.maodouchat.server.model.AiGroupAssistantResult("群助手：${query.trim()}", tasks), model)
    }

    override suspend fun translate(text: String, targetLanguage: String): AiGatewayResult<String> {
        return AiGatewayResult.Success("翻译：${text.trim()}", model)
    }

    override suspend fun semanticSearch(
        query: String,
        candidates: List<com.maodouchat.server.model.AiSemanticSearchCandidate>,
        limit: Int
    ): AiGatewayResult<List<com.maodouchat.server.model.AiSemanticSearchMatch>> {
        return AiGatewayResult.Success(
            candidates.take(limit).mapIndexed { index, candidate ->
                com.maodouchat.server.model.AiSemanticSearchMatch(candidate.messageId, 1.0 - index * 0.05)
            },
            model
        )
    }

    override suspend fun transcribe(audioBytes: ByteArray, mimeType: String, language: String?): AiGatewayResult<String> {
        return AiGatewayResult.Success("test transcript", model)
    }

    override suspend fun analyzeImage(imageBase64: String, mimeType: String, mode: String): AiGatewayResult<String> {
        return AiGatewayResult.Success("图片分析：$mode", model)
    }

    override suspend fun analyzeFile(
        fileBase64: String,
        fileName: String,
        mimeType: String,
        mode: String,
        question: String?
    ): AiGatewayResult<String> {
        return AiGatewayResult.Success("文件分析：$fileName / $mode", model)
    }
}

class HealthCheckRouteTest {
    @Test
    fun `public liveness and readiness distinguish process from dependencies`() = testApplication {
        val healthStorage = java.nio.file.Files.createTempDirectory("maodouchat-health-")
        System.setProperty("STORAGE_DIR", healthStorage.toString())
        application { moduleUnderTest(seedDemoUsers = true) }
        val r = client.get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("ok"))
        val live = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, live.status)
        assertTrue(live.bodyAsText().contains("\"status\":\"ok\""), live.bodyAsText())
        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status, ready.bodyAsText())
        assertTrue(ready.bodyAsText().contains("\"status\":\"ready\""), ready.bodyAsText())
        assertTrue(ready.bodyAsText().contains("\"database\":\"ok\""), ready.bodyAsText())
        assertTrue(ready.bodyAsText().contains("\"storage\":\"ok\""), ready.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }

    @Test
    fun `server info endpoint exposes branding without auth`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = false) }
        val info = client.get("/api/server/info")
        assertEquals(HttpStatusCode.OK, info.status)
        val body = info.bodyAsText()
        // 第三方服务器模式：客户端靠此端点展示服务器名称/公告，字段缺失会破坏身份卡
        assertTrue(body.contains("\"name\""), body)
        assertTrue(body.contains("\"announcement\""), body)
        assertTrue(body.contains("\"registrationOpen\""), body)
        assertTrue(body.contains("\"version\""), body)
    }
}

class PublicWebsiteRouteTest {
    @Test
    fun `website serves clean urls and redirects legacy html links`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val cleanPages = listOf("/", "/privacy", "/terms", "/security", "/developer")
        cleanPages.forEach { path ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, "$path -> ${response.status}")
            assertTrue(response.bodyAsText().isNotBlank(), "$path body should not be blank")
        }

        val legacyPages = mapOf(
            "/faq" to "/#faq",
            "/faq.html" to "/#faq",
            "/help" to "/#faq",
            "/help.html" to "/#faq",
            "/privacy.html" to "/privacy",
            "/terms.html" to "/terms",
            "/security.html" to "/security",
            "/developer.html" to "/developer"
        )
        val noRedirectClient = createClient { followRedirects = false }
        legacyPages.forEach { (legacy, clean) ->
            val response = noRedirectClient.get(legacy)
            assertEquals(HttpStatusCode.MovedPermanently, response.status, "$legacy should redirect")
            assertTrue(
                response.headers[HttpHeaders.Location].orEmpty().endsWith(clean),
                "$legacy should point to $clean, got ${response.headers[HttpHeaders.Location]}"
            )
        }
        val home = client.get("/").bodyAsText()
        assertFalse(home.contains(".html\""), "Homepage should not contain visible .html links")
        assertTrue(home.contains("毛豆聊天"), "Homepage should render brand copy")

        val sitemap = client.get("/sitemap.xml")
        assertEquals(HttpStatusCode.OK, sitemap.status, sitemap.bodyAsText())
        assertTrue(sitemap.bodyAsText().contains("/security</loc>"), sitemap.bodyAsText())
        assertFalse(sitemap.bodyAsText().contains(".html"), "Sitemap should only use clean URLs")

        val robots = client.get("/robots.txt")
        assertEquals(HttpStatusCode.OK, robots.status, robots.bodyAsText())
        assertTrue(robots.bodyAsText().contains("Sitemap: "), robots.bodyAsText())
        assertFalse(robots.bodyAsText().contains("/faq.html"), robots.bodyAsText())

        val securityTxt = client.get("/.well-known/security.txt")
        assertEquals(HttpStatusCode.OK, securityTxt.status, securityTxt.bodyAsText())
        assertTrue(securityTxt.bodyAsText().contains("mailto:security@maodouchat.com"), securityTxt.bodyAsText())
        assertTrue(securityTxt.bodyAsText().contains("Policy: "), securityTxt.bodyAsText())

        val legacySecurityTxt = noRedirectClient.get("/security.txt")
        assertEquals(HttpStatusCode.MovedPermanently, legacySecurityTxt.status, legacySecurityTxt.bodyAsText())
        assertTrue(legacySecurityTxt.headers[HttpHeaders.Location].orEmpty().endsWith("/.well-known/security.txt"))
        noRedirectClient.close()
    }
}

class IceConfigRouteTest {
    @Test
    fun `ICE config requires auth and returns safe STUN fallback`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = false) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/calls/ice-config").status)

        val register = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Caller","email":"caller@example.com","password":"password123"}""")
        }
        val token = extractToken(register.bodyAsText())
        val response = client.get("/api/calls/ice-config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("stun:"))
        assertTrue(body.contains("turnEnabled"))
        assertFalse(body.contains("TURN_SHARED_SECRET"))
    }
}

class SignalingCallIdRouteTest {
    @Test
    fun `call ids survive polling and hangup only clears its own session`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = false) }
        suspend fun register(name: String, email: String): Pair<String, String> {
            val response = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$name","email":"$email","password":"password123"}""")
            }
            val body = Json.parseToJsonElement(response.bodyAsText()) as JsonObject
            return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
        }
        val (aliceToken, _) = register("Alice", "call-alice@example.com")
        val (bobToken, bobId) = register("Bob", "call-bob@example.com")

        // 1:1 signaling requires an existing shared chat
        val chatResp = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["$bobId"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResp.status, chatResp.bodyAsText())

        suspend fun send(
            callId: String,
            type: String,
            payload: String = if (type == "offer") "offer-sdp" else "",
            path: String = "/api/signaling/send"
        ): HttpResponse =
            client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
                contentType(ContentType.Application.Json)
                setBody("""{"toUserId":"$bobId","type":"$type","payload":"$payload","callId":"$callId"}""")
            }

        assertEquals(HttpStatusCode.OK, send("call_old", "offer").status)
        assertEquals(HttpStatusCode.OK, send("call_new", "offer").status)
        assertEquals(HttpStatusCode.OK, send("call_new", "hang-up", path = "/api/signaling/hangup").status)
        assertEquals(HttpStatusCode.BadRequest, send("bad call id", "offer").status)

        val pending = client.get("/api/signaling/pending") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, pending.status)
        val messages = Json.parseToJsonElement(pending.bodyAsText()).jsonArray.map { it.jsonObject }
        assertTrue(messages.any { it["callId"]?.jsonPrimitive?.content == "call_old" && it["type"]?.jsonPrimitive?.content == "offer" })
        assertTrue(messages.any { it["callId"]?.jsonPrimitive?.content == "call_new" && it["type"]?.jsonPrimitive?.content == "hang-up" })
        assertFalse(messages.any { it["callId"]?.jsonPrimitive?.content == "call_new" && it["type"]?.jsonPrimitive?.content == "offer" })

        assertEquals(HttpStatusCode.OK, send("call_filter", "offer").status)
        assertEquals(HttpStatusCode.OK, send("call_filter", "ice-candidate", "audio|0|candidate-data").status)
        val offersOnly = client.get("/api/signaling/pending?offersOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        val offers = Json.parseToJsonElement(offersOnly.bodyAsText()).jsonArray.map { it.jsonObject }
        assertTrue(offers.all { it["type"]?.jsonPrimitive?.content == "offer" })
        val candidates = client.get("/api/signaling/pending") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertTrue(candidates.bodyAsText().contains("candidate-data"))
    }
}

class GroupMeshSignalingRouteTest {
    @Test
    fun `group mesh metadata is member scoped bounded and survives polling`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            return extractToken(response.bodyAsText())
        }
        val owner = login("alex@example.com")
        val alice = login("alice@example.com")
        val created = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2","u3"],"isGroup":true,"groupName":"Mesh Test"}""")
        }
        val groupId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        suspend fun send(memberIds: String, groupInvite: Boolean = true): HttpResponse =
            client.post("/api/signaling/send") {
                header(HttpHeaders.Authorization, "Bearer $owner")
                contentType(ContentType.Application.Json)
                setBody("""{"toUserId":"u2","type":"offer","payload":"offer-sdp","callId":"call_mesh","groupId":"$groupId","groupMemberIds":$memberIds,"groupInvite":$groupInvite}""")
            }

        assertEquals(HttpStatusCode.OK, send("[\"u1\",\"u2\",\"u3\"]").status)
        assertEquals(HttpStatusCode.BadRequest, send("[\"u1\",\"u2\",\"not-a-member\"]").status)
        assertEquals(HttpStatusCode.BadRequest, send("[\"u1\",\"u2\",\"u3\",\"u4\",\"u5\",\"u6\",\"u7\"]").status)

        val pending = client.get("/api/signaling/pending?offersOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $alice")
        }
        val body = pending.bodyAsText()
        assertTrue(body.contains("\"groupId\":\"$groupId\""))
        assertTrue(body.contains("\"groupMemberIds\":[\"u1\",\"u2\",\"u3\"]"))
        assertTrue(body.contains("\"groupInvite\":true"))

        assertEquals(HttpStatusCode.OK, send("[\"u1\",\"u2\",\"u3\"]", groupInvite = false).status)
        val incomingOnly = client.get("/api/signaling/pending?offersOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $alice")
        }
        assertEquals("[]", incomingOnly.bodyAsText())
        val meshPending = client.get("/api/signaling/pending") {
            header(HttpHeaders.Authorization, "Bearer $alice")
        }
        val meshBody = meshPending.bodyAsText()
        assertTrue(meshBody.contains("\"callId\":\"call_mesh\""))
        assertTrue(meshBody.contains("\"groupId\":\"$groupId\""))
    }
}

class CallInviteRateLimitRouteTest {
    @Test
    fun `new call sessions are rate limited with retry after`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())

        // 1:1 signaling requires an existing shared chat with u2 (alice)
        val chatResp = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResp.status, chatResp.bodyAsText())

        suspend fun offer(index: Int): HttpResponse = client.post("/api/signaling/send") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"toUserId":"u2","type":"offer","payload":"offer-$index","callId":"call_rate_$index"}""")
        }

        repeat(5) { index -> assertEquals(HttpStatusCode.OK, offer(index).status) }
        val limited = offer(6)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue((limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 0L) > 0L)
    }
}

class RegisterRouteTest {
    @Test
    fun `POST register rejects empty body with 400`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = false) }
        val bad = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `POST register with valid body returns token`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = false) }
        val good = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"T","email":"r-${kotlin.random.Random.nextInt(1_000_000)}@x.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, good.status)
        assertTrue(extractToken(good.bodyAsText()).isNotBlank())
    }
}

class UsersRouteTest {
    @Test
    fun `GET users with bearer token does not leak emails`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = extractToken(login.bodyAsText())

        val users = client.get("/api/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, users.status)
        assertTrue(!users.bodyAsText().contains("@"), "email leaked")
    }

    @Test
    fun `GET users supports offset pagination without overlap`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = extractToken(login.bodyAsText())

        val first = client.get("/api/users?limit=5&offset=0") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val second = client.get("/api/users?limit=5&offset=5") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)

        fun ids(body: String): Set<String> = Json.parseToJsonElement(body).jsonArray
            .map { element -> (element as JsonObject)["id"]?.jsonPrimitive?.content.orEmpty() }
            .filter(String::isNotBlank)
            .toSet()

        val firstIds = ids(first.bodyAsText())
        val secondIds = ids(second.bodyAsText())
        assertEquals(5, firstIds.size)
        assertEquals(5, secondIds.size)
        assertTrue(firstIds.intersect(secondIds).isEmpty(), "offset pages must not overlap")
    }

    @Test
    fun `GET users pagination does not let blocked users consume page capacity`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = extractToken(login.bodyAsText())

        val block = client.post("/api/users/block/u2") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, block.status)

        val users = client.get("/api/users?limit=5&offset=0") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, users.status)
        val ids = Json.parseToJsonElement(users.bodyAsText()).jsonArray
            .map { element -> (element as JsonObject)["id"]?.jsonPrimitive?.content.orEmpty() }
            .filter(String::isNotBlank)
        assertEquals(5, ids.size)
        assertTrue("u2" !in ids, "blocked user must not consume page capacity")
    }

    @Test
    fun `POST create post requires auth and feed shows it`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val unauthorized = client.post("/api/posts") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"no auth"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())

        val createResp = client.post("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hello world"}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        assertTrue(createResp.bodyAsText().contains("hello world"))

        val feed = client.get("/api/posts?limit=20") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, feed.status)
        assertTrue(feed.bodyAsText().contains("hello world"))
    }

    @Test
    fun `POST messages require valid type and chat id`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())

        // 群聊：当前登录用户为 u1（alex），所以选 u2 作为对方
        val chatResp = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResp.status, chatResp.bodyAsText())
        val chatId = (Json.parseToJsonElement(chatResp.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val bad = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"x","type":"UNSUPPORTED_TYPE"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val good = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"hi","type":"TEXT"}""")
        }
        assertEquals(HttpStatusCode.Created, good.status, good.bodyAsText())
    }
}

class PerUserUnreadRouteTest {
    @Test
    fun `group unread windows remain independent after another member reads`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }

        val ownerToken = login("alex@example.com")
        val aliceToken = login("alice@example.com")
        val bobToken = login("bob@example.com")
        val groupResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2","u3"],"isGroup":true,"groupName":"Unread Test"}""")
        }
        assertEquals(HttpStatusCode.Created, groupResponse.status, groupResponse.bodyAsText())
        val chatId = (Json.parseToJsonElement(groupResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val sent = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"ciphertext","type":"TEXT"}""")
        }
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())

        val aliceBefore = client.get("/api/chats/$chatId/unread-window") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, aliceBefore.status, aliceBefore.bodyAsText())
        assertTrue(aliceBefore.bodyAsText().contains("\"totalCount\":1"))

        val aliceRead = client.post("/api/chats/$chatId/mark-read") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, aliceRead.status, aliceRead.bodyAsText())

        val aliceAfter = client.get("/api/chats/$chatId/unread-window") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertTrue(aliceAfter.bodyAsText().contains("\"totalCount\":0"), aliceAfter.bodyAsText())

        val bobAfterAlice = client.get("/api/chats/$chatId/unread-window") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, bobAfterAlice.status, bobAfterAlice.bodyAsText())
        assertTrue(bobAfterAlice.bodyAsText().contains("\"totalCount\":1"), bobAfterAlice.bodyAsText())

        val bobChats = client.get("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertTrue(bobChats.bodyAsText().contains("\"unreadCount\":1"), bobChats.bodyAsText())
    }
}

class ChatUserSettingsRouteTest {
    @Test
    fun `conversation settings are isolated per user`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }

        val alexToken = login("alex@example.com")
        val aliceToken = login("alice@example.com")
        val bobToken = login("bob@example.com")
        val created = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val updated = client.put("/api/chats/$chatId/settings") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pinned":true,"notificationsMuted":true,"archived":true,"markedUnread":true}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        assertTrue(updated.bodyAsText().contains("\"notificationsMuted\":true"), updated.bodyAsText())

        val alexChats = client.get("/api/chats") { header(HttpHeaders.Authorization, "Bearer $alexToken") }
        assertTrue(alexChats.bodyAsText().contains("\"archived\":true"), alexChats.bodyAsText())
        assertTrue(alexChats.bodyAsText().contains("\"markedUnread\":true"), alexChats.bodyAsText())

        val aliceChats = client.get("/api/chats") { header(HttpHeaders.Authorization, "Bearer $aliceToken") }
        assertTrue(!aliceChats.bodyAsText().contains("\"archived\":true"), aliceChats.bodyAsText())
        assertTrue(!aliceChats.bodyAsText().contains("\"notificationsMuted\":true"), aliceChats.bodyAsText())

        val outsider = client.put("/api/chats/$chatId/settings") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pinned":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, outsider.status, outsider.bodyAsText())
    }
}

class GroupInviteAndAvatarRouteTest {
    @Test
    fun `group invite limits audit and avatar authorization are enforced`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }
        suspend fun register(index: Int): String {
            val response = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Invite $index","email":"invite-$index-${kotlin.random.Random.nextInt(1_000_000)}@x.com","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }

        val owner = login("alex@example.com")
        val member = login("alice@example.com")
        val firstGuest = register(1)
        val secondGuest = register(2)
        val created = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Invite Guard"}""")
        }
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val invite = client.post("/api/chats/$chatId/invite-token") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"rotate":true,"expiresInSeconds":300,"maxUses":1}""")
        }
        assertEquals(HttpStatusCode.OK, invite.status, invite.bodyAsText())
        val inviteJson = Json.parseToJsonElement(invite.bodyAsText()) as JsonObject
        val inviteToken = inviteJson["token"]!!.jsonPrimitive.content
        assertTrue(invite.bodyAsText().contains("\"remainingUses\":1"), invite.bodyAsText())

        val firstJoin = client.post("/api/chats/join-by-invite") {
            header(HttpHeaders.Authorization, "Bearer $firstGuest")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$inviteToken"}""")
        }
        assertEquals(HttpStatusCode.OK, firstJoin.status, firstJoin.bodyAsText())
        val secondJoin = client.post("/api/chats/join-by-invite") {
            header(HttpHeaders.Authorization, "Bearer $secondGuest")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$inviteToken"}""")
        }
        assertEquals(HttpStatusCode.NotFound, secondJoin.status, secondJoin.bodyAsText())

        val forbiddenAvatar = client.post("/api/chats/$chatId/avatar") {
            header(HttpHeaders.Authorization, "Bearer $member")
            contentType(ContentType.Application.Json)
            setBody("""{"base64Data":"invalid"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenAvatar.status, forbiddenAvatar.bodyAsText())

        val uploadedAvatar = client.post("/api/chats/$chatId/avatar") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"base64Data":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="}""")
        }
        assertEquals(HttpStatusCode.OK, uploadedAvatar.status, uploadedAvatar.bodyAsText())
        val avatarUrl = (Json.parseToJsonElement(uploadedAvatar.bodyAsText()) as JsonObject)["avatarUrl"]!!.jsonPrimitive.content
        val avatarPath = java.net.URI(avatarUrl).path
        val avatarFile = client.get(avatarPath) { header(HttpHeaders.Authorization, "Bearer $owner") }
        assertEquals(HttpStatusCode.OK, avatarFile.status)

        val audit = client.get("/api/chats/$chatId/audit") { header(HttpHeaders.Authorization, "Bearer $owner") }
        assertEquals(HttpStatusCode.OK, audit.status, audit.bodyAsText())
        assertTrue(audit.bodyAsText().contains("INVITE_ROTATED"), audit.bodyAsText())
        assertTrue(audit.bodyAsText().contains("MEMBER_JOINED"), audit.bodyAsText())
        assertTrue(audit.bodyAsText().contains("AVATAR_UPDATED"), audit.bodyAsText())
        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(avatarUrl)
    }
}

class GroupOwnershipRouteTest {
    @Test
    fun `owner must transfer atomically before leaving and members cannot transfer`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return extractToken(response.bodyAsText())
        }

        val ownerToken = login("alex@example.com")
        val targetToken = login("alice@example.com")
        val memberToken = login("bob@example.com")
        val group = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2","u3"],"isGroup":true,"groupName":"Ownership"}""")
        }
        assertEquals(HttpStatusCode.Created, group.status)
        val chatId = (Json.parseToJsonElement(group.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val memberRename = client.put("/api/chats/$chatId/name") {
            header(HttpHeaders.Authorization, "Bearer $memberToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":[],"isGroup":true,"groupName":"Hijacked"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, memberRename.status)

        val memberTitle = client.put("/api/chats/$chatId/members/u3/title") {
            header(HttpHeaders.Authorization, "Bearer $memberToken")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Self assigned"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, memberTitle.status)

        val oversizedNickname = client.put("/api/chats/$chatId/members/me/nickname") {
            header(HttpHeaders.Authorization, "Bearer $memberToken")
            contentType(ContentType.Application.Json)
            setBody("""{"groupNickname":"${"x".repeat(101)}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, oversizedNickname.status)

        val oversizedTitle = client.put("/api/chats/$chatId/members/u3/title") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"${"x".repeat(51)}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, oversizedTitle.status)

        val missingAdd = client.post("/api/chats/$chatId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["missing-user"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, missingAdd.status)

        val missingRemove = client.delete("/api/chats/$chatId/members/missing-user") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.NotFound, missingRemove.status)

        val ownerLeaveBeforeTransfer = client.delete("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.Conflict, ownerLeaveBeforeTransfer.status)
        assertTrue(ownerLeaveBeforeTransfer.bodyAsText().contains("GROUP_OWNER_TRANSFER_REQUIRED"))

        val promoteTarget = client.put("/api/chats/$chatId/members/u2/role") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"ADMIN"}""")
        }
        assertEquals(HttpStatusCode.OK, promoteTarget.status)

        val promoteSecondAdmin = client.put("/api/chats/$chatId/members/u3/role") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"ADMIN"}""")
        }
        assertEquals(HttpStatusCode.OK, promoteSecondAdmin.status)

        val adminTransfer = client.put("/api/chats/$chatId/members/u3/ownership") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }
        assertEquals(HttpStatusCode.Forbidden, adminTransfer.status)

        val adminRemoveOwner = client.delete("/api/chats/$chatId/members/u1") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }
        assertEquals(HttpStatusCode.Forbidden, adminRemoveOwner.status)

        val adminRemoveAdmin = client.delete("/api/chats/$chatId/members/u3") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }
        assertEquals(HttpStatusCode.Forbidden, adminRemoveAdmin.status)

        val adminMuteOwner = client.put("/api/chats/$chatId/members/u1/mute") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
            contentType(ContentType.Application.Json)
            setBody("""{"mutedUntil":${System.currentTimeMillis() + 60_000}}""")
        }
        assertEquals(HttpStatusCode.Forbidden, adminMuteOwner.status)

        val outsiderTransfer = client.put("/api/chats/$chatId/members/u4/ownership") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.NotFound, outsiderTransfer.status)

        val transfer = client.put("/api/chats/$chatId/members/u2/ownership") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, transfer.status, transfer.bodyAsText())

        val members = client.get("/api/chats/$chatId/members") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }
        assertEquals(HttpStatusCode.OK, members.status)
        val roles = Json.parseToJsonElement(members.bodyAsText()).jsonArray.associate {
            val member = it.jsonObject
            val userId = listOf("userId", "user_id", "id")
                .firstNotNullOfOrNull { key -> member[key]?.jsonPrimitive?.content }
            assertNotNull(userId, members.bodyAsText())
            val role = member["role"]?.jsonPrimitive?.content ?: "MEMBER"
            userId to role
        }
        assertEquals("ADMIN", roles["u1"])
        assertEquals("OWNER", roles["u2"])
        assertEquals(1, roles.values.count { it == "OWNER" })

        val formerOwnerTransfer = client.put("/api/chats/$chatId/members/u3/ownership") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.Forbidden, formerOwnerTransfer.status)

        val formerOwnerLeave = client.delete("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, formerOwnerLeave.status)

        val audit = client.get("/api/chats/$chatId/audit") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }
        assertEquals(HttpStatusCode.OK, audit.status)
        val auditBody = audit.bodyAsText()
        assertTrue(auditBody.contains("OWNERSHIP_TRANSFERRED"))
        assertEquals(1, Regex("OWNERSHIP_TRANSFERRED").findAll(auditBody).count())
    }
}

class GroupMemberConcurrencyRouteTest {
    @Test
    fun `promotion racing admin removal is serialized and audited once`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            return extractToken(response.bodyAsText())
        }

        val ownerToken = login("alex@example.com")
        val adminToken = login("alice@example.com")
        val group = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2","u3"],"isGroup":true,"groupName":"Concurrent"}""")
        }
        val chatId = Json.parseToJsonElement(group.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.put("/api/chats/$chatId/members/u2/role") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"ADMIN"}""")
        }.status)

        val (promote, remove) = coroutineScope {
            val promoteRequest = async {
                client.put("/api/chats/$chatId/members/u3/role") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"ADMIN"}""")
                }
            }
            val removeRequest = async {
                client.delete("/api/chats/$chatId/members/u3") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
            }
            promoteRequest.await() to removeRequest.await()
        }

        assertFalse(
            promote.status == HttpStatusCode.OK && remove.status == HttpStatusCode.OK,
            "promotion and admin removal cannot both commit: promote=${promote.status}, remove=${remove.status}"
        )
        assertTrue(promote.status == HttpStatusCode.OK || remove.status == HttpStatusCode.OK)

        val members = client.get("/api/chats/$chatId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        val target = Json.parseToJsonElement(members.bodyAsText()).jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["userId"]?.jsonPrimitive?.content == "u3" }
        if (target != null) assertEquals("ADMIN", target["role"]!!.jsonPrimitive.content)

        val audit = client.get("/api/chats/$chatId/audit") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.bodyAsText()
        val promoteCount = Regex("MEMBER_PROMOTED").findAll(audit).count()
        val removeCount = Regex("MEMBER_REMOVED").findAll(audit).count()
        // u2 promotion is the setup audit; exactly one of the racing u3 mutations is committed.
        assertEquals(2, promoteCount + removeCount)
    }
}

class GroupProfilePermissionRouteTest {
    @Test
    fun `demoted admin cannot mutate group profile and removed member cannot rename self`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            return extractToken(response.bodyAsText())
        }

        val owner = login("alex@example.com")
        val member = login("alice@example.com")
        val created = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Original"}""")
        }
        val chatId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.put("/api/chats/$chatId/members/u2/role") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"ADMIN"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/chats/$chatId/members/u2/role") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"MEMBER"}""")
        }.status)

        val rename = client.put("/api/chats/$chatId/name") {
            header(HttpHeaders.Authorization, "Bearer $member")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":[],"isGroup":true,"groupName":"Hijacked"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, rename.status)
        assertTrue(rename.bodyAsText().contains("GROUP_PERMISSION_DENIED"))

        val announcement = client.put("/api/chats/$chatId/announcement") {
            header(HttpHeaders.Authorization, "Bearer $member")
            contentType(ContentType.Application.Json)
            setBody("""{"announcement":"Hijacked"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, announcement.status)
        assertTrue(announcement.bodyAsText().contains("GROUP_PERMISSION_DENIED"))

        val invite = client.post("/api/chats/$chatId/invite-token") {
            header(HttpHeaders.Authorization, "Bearer $member")
            contentType(ContentType.Application.Json)
            setBody("""{"rotate":true,"expiresInSeconds":300,"maxUses":1}""")
        }
        assertEquals(HttpStatusCode.Forbidden, invite.status)
        assertTrue(invite.bodyAsText().contains("GROUP_PERMISSION_DENIED"))

        val ownNickname = client.put("/api/chats/$chatId/members/me/nickname") {
            header(HttpHeaders.Authorization, "Bearer $member")
            contentType(ContentType.Application.Json)
            setBody("""{"groupNickname":"Still a member"}""")
        }
        assertEquals(HttpStatusCode.OK, ownNickname.status)

        assertEquals(HttpStatusCode.OK, client.delete("/api/chats/$chatId/members/u2") {
            header(HttpHeaders.Authorization, "Bearer $owner")
        }.status)
        val removedNickname = client.put("/api/chats/$chatId/members/me/nickname") {
            header(HttpHeaders.Authorization, "Bearer $member")
            contentType(ContentType.Application.Json)
            setBody("""{"groupNickname":"No longer a member"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, removedNickname.status)
        assertTrue(removedNickname.bodyAsText().contains("GROUP_ACTOR_NOT_MEMBER"))

        val audit = client.get("/api/chats/$chatId/audit") {
            header(HttpHeaders.Authorization, "Bearer $owner")
        }.bodyAsText()
        assertFalse(audit.contains("GROUP_RENAMED"))
        assertFalse(audit.contains("ANNOUNCEMENT_UPDATED"))
        assertFalse(audit.contains("INVITE_ROTATED"))
        assertEquals(1, Regex("NICKNAME_UPDATED").findAll(audit).count())
    }
}

class AiRouteTest {
    @Test
    @org.junit.jupiter.api.Disabled("Known rate-limit side effect: test makes >20 AI calls/min; re-enable with delayed retries in CI")
    fun `AI rewrite and suggested replies require auth and return gateway results`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val unauthorized = client.post("/api/ai/rewrite") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","mode":"polish"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val unauthorizedStream = client.post("/api/ai/rewrite/stream") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","mode":"polish"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorizedStream.status)

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())

        val invalidSummarySync = client.post("/api/ai/summary-sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"syncId":"bad","senderDeviceId":1,"targetDeviceIds":[],"envelope":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidSummarySync.status, invalidSummarySync.bodyAsText())

        val defaultSettings = client.get("/api/ai/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, defaultSettings.status, defaultSettings.bodyAsText())

        val disabled = client.put("/api/ai/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, disabled.status, disabled.bodyAsText())

        val blockedRewrite = client.post("/api/ai/rewrite") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","mode":"polish"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, blockedRewrite.status, blockedRewrite.bodyAsText())

        val blockedRewriteStream = client.post("/api/ai/rewrite/stream") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","mode":"polish"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, blockedRewriteStream.status, blockedRewriteStream.bodyAsText())

        val enabled = client.put("/api/ai/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, enabled.status, enabled.bodyAsText())

        val rewrite = client.post("/api/ai/rewrite") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","mode":"polish"}""")
        }
        assertEquals(HttpStatusCode.OK, rewrite.status, rewrite.bodyAsText())
        assertTrue(rewrite.bodyAsText().contains("改写"))
        assertTrue(rewrite.bodyAsText().contains("test-model"))

        val suggestions = client.post("/api/ai/suggest-replies") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"sender":"u2","text":"今晚一起吃饭吗？"}],"tone":"friendly","count":2}""")
        }
        assertEquals(HttpStatusCode.OK, suggestions.status, suggestions.bodyAsText())
        assertTrue(suggestions.bodyAsText().contains("好的"))
        assertTrue(suggestions.bodyAsText().contains("test-model"))

        val rewriteStream = client.post("/api/ai/rewrite/stream") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","mode":"polish"}""")
        }
        assertEquals(HttpStatusCode.OK, rewriteStream.status, rewriteStream.bodyAsText())
        assertTrue(rewriteStream.bodyAsText().contains("\"type\":\"start\""))
        assertTrue(rewriteStream.bodyAsText().contains("\"type\":\"delta\""))
        assertTrue(rewriteStream.bodyAsText().contains("改写"))
        assertTrue(rewriteStream.bodyAsText().contains("\"type\":\"done\""))

        val suggestionStream = client.post("/api/ai/suggest-replies/stream") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"sender":"u2","text":"今晚一起吃饭吗？"}],"tone":"friendly","count":2}""")
        }
        assertEquals(HttpStatusCode.OK, suggestionStream.status, suggestionStream.bodyAsText())
        assertTrue(suggestionStream.bodyAsText().contains("\"type\":\"reply\""))
        assertTrue(suggestionStream.bodyAsText().contains("好的"))
        assertTrue(suggestionStream.bodyAsText().contains("\"type\":\"done\""))

        val summary = client.post("/api/ai/summarize") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"sender":"u2","text":"今晚一起吃饭吗？"},{"sender":"me","text":"可以，七点见"}],"style":"brief"}""")
        }
        assertEquals(HttpStatusCode.OK, summary.status, summary.bodyAsText())
        assertTrue(summary.bodyAsText().contains("总结"))
        assertTrue(summary.bodyAsText().contains("test-model"))

        val chatResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResponse.status, chatResponse.bodyAsText())
        val chatId = (Json.parseToJsonElement(chatResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val imageAnalysis = client.post("/api/ai/analyze-image") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"imageBase64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=","mimeType":"image/png","mode":"describe","chatId":"$chatId"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, imageAnalysis.status, imageAnalysis.bodyAsText())
        assertTrue(imageAnalysis.bodyAsText().contains("图片分析"), imageAnalysis.bodyAsText())

        val fileAnalysis = client.post("/api/ai/analyze-file") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"fileBase64":"5Lya6K6u57qq6KaB77ya5ZGo5YWt5Y+R5biD44CC","fileName":"meeting.txt","mimeType":"text/plain","mode":"summarize","chatId":"$chatId"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, fileAnalysis.status, fileAnalysis.bodyAsText())
        assertTrue(fileAnalysis.bodyAsText().contains("meeting.txt"), fileAnalysis.bodyAsText())

        val invalidPdf = client.post("/api/ai/analyze-file") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"fileBase64":"AAECAwQ=","fileName":"fake.pdf","mimeType":"application/pdf","mode":"summarize","chatId":"$chatId"}"""
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPdf.status, invalidPdf.bodyAsText())

        val semanticSearch = client.post("/api/ai/semantic-search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"query":"上次说的见面地点","chatId":"$chatId","limit":5,"candidates":[{"messageId":"m_semantic_1","sender":"Alice","text":"我们周六在人民广场地铁站见","timestamp":1700000000000}]}"""
            )
        }
        assertEquals(HttpStatusCode.OK, semanticSearch.status, semanticSearch.bodyAsText())
        assertTrue(semanticSearch.bodyAsText().contains("m_semantic_1"))
        assertTrue(semanticSearch.bodyAsText().contains("test-model"))

        val groupResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2","u3"],"isGroup":true,"groupName":"AI Test Group"}""")
        }
        assertEquals(HttpStatusCode.Created, groupResponse.status, groupResponse.bodyAsText())
        val groupId = (Json.parseToJsonElement(groupResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val groupAssistant = client.post("/api/ai/group-assistant") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"query":"提取待办","chatId":"$groupId","mode":"tasks","messages":[{"sender":"Alice","text":"周六十点发布新版本"}]}"""
            )
        }
        assertEquals(HttpStatusCode.OK, groupAssistant.status, groupAssistant.bodyAsText())
        assertTrue(groupAssistant.bodyAsText().contains("群助手"))
        assertTrue(groupAssistant.bodyAsText().contains("tasks"))
        assertTrue(groupAssistant.bodyAsText().contains("发布新版本"))

        val globalSemanticSearch = client.post("/api/ai/global-semantic-search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"query":"发布安排","limit":10,"candidates":[{"chatId":"$chatId","messageId":"m_global_1","sender":"Alice","text":"周六发布客户端","timestamp":1700000000000},{"chatId":"$groupId","messageId":"m_global_2","sender":"Bob","text":"周日发布服务端","timestamp":1700000001000}]}"""
            )
        }
        assertEquals(HttpStatusCode.OK, globalSemanticSearch.status, globalSemanticSearch.bodyAsText())
        assertTrue(globalSemanticSearch.bodyAsText().contains("m_global_1"))
        assertTrue(globalSemanticSearch.bodyAsText().contains("$chatId"))

        val audit = client.get("/api/ai/audit") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, audit.status, audit.bodyAsText())
        assertTrue(audit.bodyAsText().contains("rewrite"))
        assertTrue(audit.bodyAsText().contains("suggest_replies"))
        assertTrue(audit.bodyAsText().contains("summarize"))
        assertTrue(audit.bodyAsText().contains("semantic_search"))
        assertTrue(audit.bodyAsText().contains("global_semantic_search"))
        assertTrue(audit.bodyAsText().contains("group_assistant"))
    }
}

class AiSummarySyncSessionIsolationRouteTest {
    @Test
    fun `summary sync is isolated to the signal device bound to the jwt session`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alex@example.com","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }

        fun authSessionId(token: String): String {
            val jwt = checkNotNull(com.maodouchat.server.auth.JwtConfig.verifyToken(token))
            return checkNotNull(com.maodouchat.server.auth.JwtConfig.authSessionId(jwt))
        }

        val deviceOneToken = login()
        val deviceTwoToken = login()
        val deviceOneSession = authSessionId(deviceOneToken)
        val deviceTwoSession = authSessionId(deviceTwoToken)
        val signalKeyRepo = SignalKeyRepository()
        val deviceOneKeyPair = Curve.generateKeyPair()
        val deviceOneIdentity = Base64.getEncoder().encodeToString(deviceOneKeyPair.publicKey.serialize())
        val deviceTwoIdentity = "summary-sync-device-two-identity"

        fun uploadDevice(sessionId: String, deviceId: Int, identityKey: String) {
            assertEquals(
                SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
                signalKeyRepo.uploadKeyPackage(
                    userId = "u1",
                    authSessionId = sessionId,
                    deviceId = deviceId,
                    identityKey = identityKey,
                    registrationId = 20_000 + deviceId,
                    signedPreKeyId = deviceId,
                    signedPreKey = "summary-sync-signed-pre-key-$deviceId",
                    signedPreKeySignature = "summary-sync-signature-$deviceId",
                    preKeys = emptyList()
                )
            )
        }

        uploadDevice(deviceOneSession, 1, deviceOneIdentity)
        uploadDevice(deviceTwoSession, 2, deviceTwoIdentity)
        val confirmationPayload = "maodouchat-device-confirm:v1\nu1\n1\n2\n$deviceTwoIdentity".toByteArray()
        val confirmationProof = Base64.getEncoder().encodeToString(
            Curve.calculateSignature(deviceOneKeyPair.privateKey, confirmationPayload)
        )
        assertEquals(
            SignalKeyRepository.ConfirmDeviceResult.CONFIRMED,
            signalKeyRepo.confirmDevice("u1", 2, 1, confirmationProof)
        )
        assertTrue(signalKeyRepo.isDeviceConfirmed("u1", 1))
        assertTrue(signalKeyRepo.isDeviceConfirmed("u1", 2))
        assertTrue(signalKeyRepo.isAuthSessionBoundToDevice("u1", deviceOneSession, 1))
        assertTrue(signalKeyRepo.isAuthSessionBoundToDevice("u1", deviceTwoSession, 2))
        assertFalse(signalKeyRepo.isAuthSessionBoundToDevice("u1", deviceOneSession, 2))
        assertFalse(signalKeyRepo.isAuthSessionBoundToDevice("u1", deviceTwoSession, 1))

        val initialUpload = client.post("/api/ai/summary-sync") {
            header(HttpHeaders.Authorization, "Bearer $deviceOneToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"syncId":"sync_session_1","senderDeviceId":1,"targetDeviceIds":[2],"envelope":"original-envelope"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, initialUpload.status, initialUpload.bodyAsText())

        val crossDeviceGet = client.get("/api/ai/summary-sync?deviceId=2") {
            header(HttpHeaders.Authorization, "Bearer $deviceOneToken")
        }
        assertEquals(HttpStatusCode.Forbidden, crossDeviceGet.status, crossDeviceGet.bodyAsText())

        val legitimateGet = client.get("/api/ai/summary-sync?deviceId=2") {
            header(HttpHeaders.Authorization, "Bearer $deviceTwoToken")
        }
        assertEquals(HttpStatusCode.OK, legitimateGet.status, legitimateGet.bodyAsText())
        val initialPending = Json.parseToJsonElement(legitimateGet.bodyAsText()).jsonArray
        assertEquals(1, initialPending.size)
        val envelopeId = initialPending.single().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("original-envelope", initialPending.single().jsonObject["envelope"]!!.jsonPrimitive.content)

        val forgedSender = client.post("/api/ai/summary-sync") {
            header(HttpHeaders.Authorization, "Bearer $deviceTwoToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"syncId":"sync_session_1","senderDeviceId":1,"targetDeviceIds":[2],"envelope":"forged-envelope"}"""
            )
        }
        assertEquals(HttpStatusCode.Forbidden, forgedSender.status, forgedSender.bodyAsText())

        val crossDeviceAck = client.post("/api/ai/summary-sync/ack") {
            header(HttpHeaders.Authorization, "Bearer $deviceOneToken")
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":2,"envelopeIds":["$envelopeId"]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossDeviceAck.status, crossDeviceAck.bodyAsText())

        val stillPendingResponse = client.get("/api/ai/summary-sync?deviceId=2") {
            header(HttpHeaders.Authorization, "Bearer $deviceTwoToken")
        }
        assertEquals(HttpStatusCode.OK, stillPendingResponse.status, stillPendingResponse.bodyAsText())
        val stillPending = Json.parseToJsonElement(stillPendingResponse.bodyAsText()).jsonArray
        assertEquals(1, stillPending.size)
        assertEquals(envelopeId, stillPending.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("original-envelope", stillPending.single().jsonObject["envelope"]!!.jsonPrimitive.content)
    }
}

class NewFeaturesRouteTest {
    @Test
    fun `test message editing and read receipts and starring`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        
        // 1. Login
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        
        // 2. Create Chat
        val chatResp = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResp.status)
        val chatId = (Json.parseToJsonElement(chatResp.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        
        // 3. Send Message
        val msgResp = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"hello","type":"TEXT"}""")
        }
        assertEquals(HttpStatusCode.Created, msgResp.status)
        val msgId = (Json.parseToJsonElement(msgResp.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        
        // 4. Edit Message
        val editResp = client.put("/api/messages/$msgId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"hello edited","type":"TEXT"}""")
        }
        assertEquals(HttpStatusCode.OK, editResp.status)
        
        // 5. Star Message
        val starResp = client.post("/api/messages/$msgId/star") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, starResp.status)
        
        // 6. Get Starred Messages
        val starredList = client.get("/api/messages/starred") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, starredList.status)
        assertTrue(starredList.bodyAsText().contains(msgId))
        
        // 7. Mark Read
        val markReadResp = client.post("/api/chats/$chatId/mark-read") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, markReadResp.status)
        
        // 8. Get Read Receipts
        val receiptsResp = client.get("/api/messages/$msgId/read-receipts") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, receiptsResp.status)
    }

    @Test
    fun `test group management role title and nicknames`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        
        // 1. Login
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        
        // 2. Create Group Chat
        val groupResp = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Test Group"}""")
        }
        assertEquals(HttpStatusCode.Created, groupResp.status)
        val chatId = (Json.parseToJsonElement(groupResp.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        
        // 3. Get Members
        val membersResp = client.get("/api/chats/$chatId/members") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, membersResp.status)
        
        // 4. Change member role
        val roleResp = client.put("/api/chats/$chatId/members/u2/role") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"ADMIN"}""")
        }
        assertEquals(HttpStatusCode.OK, roleResp.status)
        
        // 5. Update self group nickname
        val nicknameResp = client.put("/api/chats/$chatId/members/me/nickname") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"groupNickname":"AlexTheBoss"}""")
        }
        assertEquals(HttpStatusCode.OK, nicknameResp.status)
        
        // 6. Set title
        val titleResp = client.put("/api/chats/$chatId/members/u2/title") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Co-Host"}""")
        }
        assertEquals(HttpStatusCode.OK, titleResp.status)
    }

    @Test
    fun `encrypted attachment upload commit download authorization and cleanup`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return extractToken(response.bodyAsText())
        }

        val ownerToken = login("alex@example.com")
        val participantToken = login("alice@example.com")
        val outsiderToken = login("bob@example.com")
        val chatResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResponse.status)
        val chatId = (Json.parseToJsonElement(chatResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val ciphertext = "opaque-ciphertext-with-gcm-tag-for-route-test"
        val cipherHash = MessageDigest.getInstance("SHA-256")
            .digest(ciphertext.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val messageId = "m_attachment_route_test"
        val uploadResponse = client.post("/api/attachments?chatId=$chatId&messageId=$messageId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header("X-Content-SHA256", cipherHash)
            contentType(ContentType.Application.OctetStream)
            setBody(ciphertext.toByteArray())
        }
        assertEquals(HttpStatusCode.Created, uploadResponse.status)
        val attachmentId = (Json.parseToJsonElement(uploadResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val pendingDownload = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.NotFound, pendingDownload.status)

        val badHashUpload = client.post("/api/attachments?chatId=$chatId&messageId=m_attachment_bad_hash") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header("X-Content-SHA256", "0".repeat(64))
            contentType(ContentType.Application.OctetStream)
            setBody(ciphertext.toByteArray())
        }
        assertEquals(HttpStatusCode.BadRequest, badHashUpload.status)

        val messageResponse = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"opaque-reference-envelope","type":"FILE","id":"$messageId"}""")
        }
        assertEquals(HttpStatusCode.Created, messageResponse.status)

        val explicitCommit = client.post("/api/attachments/$attachmentId/commit") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"messageId":"$messageId"}""")
        }
        assertEquals(HttpStatusCode.OK, explicitCommit.status)

        val ownerDownload = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, ownerDownload.status)
        assertEquals(cipherHash, ownerDownload.headers["X-Content-SHA256"])
        assertEquals(ciphertext, ownerDownload.bodyAsText())

        val participantDownload = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $participantToken")
        }
        assertEquals(HttpStatusCode.OK, participantDownload.status)
        assertEquals(ciphertext, participantDownload.bodyAsText())

        val outsiderDownload = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
        }
        assertEquals(HttpStatusCode.Forbidden, outsiderDownload.status)

        val deleteMessage = client.delete("/api/messages/$messageId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, deleteMessage.status)
        val deletedDownload = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.NotFound, deletedDownload.status)
    }

    @Test
    fun `resumable attachment chunks are idempotent and downloads support range`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return extractToken(response.bodyAsText())
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val ownerToken = login("alex@example.com")
        val chatResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResponse.status)
        val chatId = (Json.parseToJsonElement(chatResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        val messageId = "m_resumable_attachment_test"
        val ciphertext = "resumable-ciphertext-with-a-gcm-tag".toByteArray()
        val fullHash = sha256(ciphertext)

        val sessionResponse = client.post("/api/attachment-uploads") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"chatId":"$chatId","messageId":"$messageId","cipherSha256":"$fullHash","cipherSize":${ciphertext.size}}"""
            )
        }
        assertEquals(HttpStatusCode.Created, sessionResponse.status)
        val sessionJson = Json.parseToJsonElement(sessionResponse.bodyAsText()) as JsonObject
        val attachmentId = sessionJson["id"]!!.jsonPrimitive.content
        assertEquals("0", sessionJson["uploadedBytes"]!!.jsonPrimitive.content)

        val firstChunk = ciphertext.copyOfRange(0, 11)
        suspend fun putChunk(offset: Int, bytes: ByteArray) = client.put("/api/attachment-uploads/$attachmentId?offset=$offset") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header("X-Chunk-SHA256", sha256(bytes))
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }

        val firstUpload = putChunk(0, firstChunk)
        assertEquals(HttpStatusCode.OK, firstUpload.status)
        assertTrue(firstUpload.bodyAsText().contains("\"uploadedBytes\":11"))

        val replay = putChunk(0, firstChunk)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertTrue(replay.bodyAsText().contains("\"uploadedBytes\":11"))

        val wrongOffset = putChunk(12, ciphertext.copyOfRange(11, 15))
        assertEquals(HttpStatusCode.Conflict, wrongOffset.status)
        assertTrue(wrongOffset.bodyAsText().contains("\"uploadedBytes\":11"))

        val statusResponse = client.get("/api/attachment-uploads/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, statusResponse.status)
        assertTrue(statusResponse.bodyAsText().contains("\"uploadedBytes\":11"))

        val finalUpload = putChunk(11, ciphertext.copyOfRange(11, ciphertext.size))
        assertEquals(HttpStatusCode.OK, finalUpload.status)
        val finalJson = Json.parseToJsonElement(finalUpload.bodyAsText()) as JsonObject
        assertEquals("true", finalJson["complete"]!!.jsonPrimitive.content)
        assertEquals(ciphertext.size.toString(), finalJson["uploadedBytes"]!!.jsonPrimitive.content)

        val messageResponse = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"opaque-reference-envelope","type":"FILE","id":"$messageId"}""")
        }
        assertEquals(HttpStatusCode.Created, messageResponse.status)

        val committedStatus = client.get("/api/attachment-uploads/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, committedStatus.status)
        assertTrue(committedStatus.bodyAsText().contains("\"status\":\"COMMITTED\""))

        val idempotentMessageRetry = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"opaque-reference-envelope","type":"FILE","id":"$messageId"}""")
        }
        assertEquals(HttpStatusCode.Created, idempotentMessageRetry.status)

        val rangeStart = 7
        val rangeResponse = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header("Range", "bytes=$rangeStart-")
        }
        assertEquals(HttpStatusCode.PartialContent, rangeResponse.status)
        assertEquals(
            "bytes $rangeStart-${ciphertext.size - 1}/${ciphertext.size}",
            rangeResponse.headers["Content-Range"]
        )
        assertEquals(ciphertext.copyOfRange(rangeStart, ciphertext.size).decodeToString(), rangeResponse.bodyAsText())

        val invalidRange = client.get("/api/attachments/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header("Range", "bytes=${ciphertext.size}-")
        }
        assertEquals(416, invalidRange.status.value)

        listOf("IMAGE", "GIF", "VIDEO", "VOICE").forEachIndexed { index, attachmentType ->
            val objectMessageId = "m_${attachmentType.lowercase()}_attachment_test_$index"
            val objectCiphertext = "$attachmentType-object-ciphertext-with-tag".toByteArray()
            val objectHash = sha256(objectCiphertext)
            val objectSession = client.post("/api/attachment-uploads") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"chatId":"$chatId","messageId":"$objectMessageId","cipherSha256":"$objectHash","cipherSize":${objectCiphertext.size}}"""
                )
            }
            assertEquals(HttpStatusCode.Created, objectSession.status)
            val objectId = (Json.parseToJsonElement(objectSession.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
            val objectUpload = client.put("/api/attachment-uploads/$objectId?offset=0") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
                header("X-Chunk-SHA256", objectHash)
                contentType(ContentType.Application.OctetStream)
                setBody(objectCiphertext)
            }
            assertEquals(HttpStatusCode.OK, objectUpload.status)

            val objectMessage = client.post("/api/chats/$chatId/messages") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"chatId":"$chatId","content":"opaque-$attachmentType-reference-envelope","type":"$attachmentType","id":"$objectMessageId"}"""
                )
            }
            assertEquals(HttpStatusCode.Created, objectMessage.status)

            val committedObjectStatus = client.get("/api/attachment-uploads/$objectId") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
            }
            assertEquals(HttpStatusCode.OK, committedObjectStatus.status)
            assertTrue(committedObjectStatus.bodyAsText().contains("\"status\":\"COMMITTED\""))
        }
    }
}

class MessageEditRealtimeRouteTest {
    @Test
    fun `edited event includes chat id for client isolation`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        val chatResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        val chatId = Json.parseToJsonElement(chatResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val sent = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"cipher-v1","type":"TEXT"}""")
        }
        val messageId = Json.parseToJsonElement(sent.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val websocketClient = createClient { install(WebSockets) }

        websocketClient.webSocket(
            request = {
                url("/ws")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        ) {
            val edited = client.put("/api/messages/$messageId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"chatId":"$chatId","content":"cipher-v2","type":"TEXT"}""")
            }
            assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())

            val payload = withTimeout(5_000L) {
                while (true) {
                    val text = (incoming.receive() as? Frame.Text)?.readText() ?: continue
                    val outer = Json.parseToJsonElement(text).jsonObject
                    if (outer["type"]?.jsonPrimitive?.content == "MESSAGE_EDITED") {
                        return@withTimeout Json.parseToJsonElement(
                            outer["payload"]!!.jsonPrimitive.content
                        ).jsonObject
                    }
                }
                error("unreachable")
            }
            assertEquals(messageId, payload["messageId"]!!.jsonPrimitive.content)
            assertEquals(chatId, payload["chatId"]!!.jsonPrimitive.content)
            assertEquals("cipher-v2", payload["content"]!!.jsonPrimitive.content)
        }
    }
}

class ChatCleanupRouteTest {
    @Test
    fun `last participant can delete chat with message dependencies`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return extractToken(response.bodyAsText())
        }

        val ownerToken = login("alex@example.com")
        val participantToken = login("alice@example.com")
        val chatResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResponse.status)
        val chatId = (Json.parseToJsonElement(chatResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val messageResponse = client.post("/api/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","content":"opaque-envelope","type":"TEXT"}""")
        }
        assertEquals(HttpStatusCode.Created, messageResponse.status)
        val messageId = (Json.parseToJsonElement(messageResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, client.post("/api/messages/$messageId/star") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/chats/$chatId/mark-read") {
            header(HttpHeaders.Authorization, "Bearer $participantToken")
        }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/messages/$messageId/reaction") {
            header(HttpHeaders.Authorization, "Bearer $participantToken")
            contentType(ContentType.Application.Json)
            setBody("""{"emoji":"👍"}""")
        }.status)

        assertEquals(HttpStatusCode.OK, client.delete("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.status)
        // 1:1 一方删除后整会话清除（避免对方残留 1 人幽灵会话与重复私聊）：
        // 另一方已非成员，再删返回 403
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $participantToken")
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $participantToken")
        }.status)
    }
}

class ChatLookupPrivacyRouteTest {
    @Test
    fun `existing and missing chats are indistinguishable to non members`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return extractToken(response.bodyAsText())
        }

        val ownerToken = login("alex@example.com")
        val outsiderToken = login("bob@example.com")
        val chatResponse = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResponse.status)
        val chatId = (Json.parseToJsonElement(chatResponse.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        val existing = client.get("/api/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
        }
        val missing = client.get("/api/chats/c_does_not_exist") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
        }

        assertEquals(HttpStatusCode.NotFound, existing.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(existing.bodyAsText(), missing.bodyAsText())
    }
}

class SenderKeyDistributionRouteTest {
    @Test
    fun `sender key coverage is isolated per sender and exposes newly confirmed devices`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }

        val alexToken = login("alex@example.com")
        val aliceToken = login("alice@example.com")
        val created = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Sender Key Test"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content

        SignalKeyRepository().apply {
            fun uploadBundle(userId: String, deviceId: Int, label: String, identityKey: String = "$label-identity") {
                val sessionId = "test-session-$userId-$deviceId"
                org.jetbrains.exposed.sql.transactions.transaction {
                    com.maodouchat.server.db.AuthSessions.insert {
                        it[com.maodouchat.server.db.AuthSessions.id] = sessionId
                        it[com.maodouchat.server.db.AuthSessions.userId] = userId
                        it[com.maodouchat.server.db.AuthSessions.signalDeviceId] = deviceId
                        it[com.maodouchat.server.db.AuthSessions.createdAt] = System.currentTimeMillis()
                        it[com.maodouchat.server.db.AuthSessions.updatedAt] = System.currentTimeMillis()
                    }
                }
                val uploadResult = uploadKeyPackage(
                    userId = userId,
                    authSessionId = sessionId,
                    deviceId = deviceId,
                    identityKey = identityKey,
                    registrationId = 10_000 + deviceId,
                    signedPreKeyId = deviceId,
                    signedPreKey = "$label-signed-pre-key",
                    signedPreKeySignature = "$label-signature",
                    preKeys = emptyList()
                )
                check(uploadResult == SignalKeyRepository.UploadKeyPackageResult.UPLOADED) { "uploadKeyPackage failed: $uploadResult" }
            }
            val approverKeyPair = Curve.generateKeyPair()
            val approverIdentity = Base64.getEncoder().encodeToString(approverKeyPair.publicKey.serialize())
            val targetIdentity = "alice-device-2-identity"
            uploadBundle("u1", 1, "alex-device-1")
            touchDevice("u1", 1)
            uploadBundle("u2", 1, "alice-device-1", approverIdentity)
            touchDevice("u2", 1)
            uploadBundle("u2", 2, "alice-device-2", targetIdentity)
            touchDevice("u2", 2)
            val proofPayload = "maodouchat-device-confirm:v1\nu2\n1\n2\n$targetIdentity".toByteArray()
            val proof = Base64.getEncoder().encodeToString(Curve.calculateSignature(approverKeyPair.privateKey, proofPayload))
            val forgedProof = Base64.getEncoder().encodeToString(ByteArray(64))
            assertEquals(SignalKeyRepository.ConfirmDeviceResult.INVALID_PROOF, confirmDevice("u2", 2, 1, forgedProof))
            assertEquals(SignalKeyRepository.ConfirmDeviceResult.CONFIRMED, confirmDevice("u2", 2, 1, proof))
        }

        val alexFirstReport = client.post("/api/chats/$chatId/sender-key-distributions") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"epoch":1,"messageId":"sk_alex_1","targets":[{"userId":"u2","deviceId":1,"status":"SENT"}]}""")
        }
        assertEquals(HttpStatusCode.OK, alexFirstReport.status, alexFirstReport.bodyAsText())

        val alexCoverage = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, alexCoverage.status, alexCoverage.bodyAsText())
        assertTrue(alexCoverage.bodyAsText().contains("\"pending\":1"), alexCoverage.bodyAsText())
        assertTrue(alexCoverage.bodyAsText().contains("device_not_covered"), alexCoverage.bodyAsText())

        val aliceReport = client.post("/api/chats/$chatId/sender-key-distributions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"epoch":1,"messageId":"sk_alice_1","targets":[{"userId":"u1","deviceId":1,"status":"SENT"},{"userId":"u2","deviceId":2,"status":"SENT"}]}""")
        }
        assertEquals(HttpStatusCode.OK, aliceReport.status, aliceReport.bodyAsText())

        val alexStillPending = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertTrue(alexStillPending.bodyAsText().contains("\"pending\":1"), alexStillPending.bodyAsText())

        val alexSecondReport = client.post("/api/chats/$chatId/sender-key-distributions") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"epoch":1,"messageId":"sk_alex_2","targets":[{"userId":"u2","deviceId":2,"status":"SENT"}]}""")
        }
        assertEquals(HttpStatusCode.OK, alexSecondReport.status, alexSecondReport.bodyAsText())
        val complete = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertTrue(complete.bodyAsText().contains("\"sent\":2"), complete.bodyAsText())
        assertTrue(complete.bodyAsText().contains("\"pending\":0"), complete.bodyAsText())

        assertEquals(HttpStatusCode.OK, client.delete("/api/keys/devices/2") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }.status)
        val afterDeviceRemoval = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertTrue(afterDeviceRemoval.bodyAsText().contains("\"total\":1"), afterDeviceRemoval.bodyAsText())
        assertTrue(afterDeviceRemoval.bodyAsText().contains("\"sent\":1"), afterDeviceRemoval.bodyAsText())
    }
}

class AdminSessionAttemptLimiterTest {
    @Test
    fun `admin password step up is rate limited and resettable`() {
        val limiter = com.maodouchat.server.plugins.AdminSessionAttemptLimiter()
        repeat(5) { assertTrue(limiter.acquire("u1", now = 1_000L)) }
        assertFalse(limiter.acquire("u1", now = 1_000L))
        assertTrue(limiter.acquire("u1", now = 301_001L))
        limiter.reset("u1")
        assertTrue(limiter.acquire("u1", now = 301_001L))
    }
}

class ProxyAddressPolicyTest {
    @Test
    fun `proxy client address is trusted only when explicitly enabled and sanitized`() {
        assertEquals(
            "10.0.0.8",
            com.maodouchat.server.plugins.resolveClientAddress(false, "203.0.113.7", "198.51.100.2", "10.0.0.8")
        )
        assertEquals(
            "203.0.113.7",
            com.maodouchat.server.plugins.resolveClientAddress(true, "203.0.113.7", "198.51.100.2", "10.0.0.8")
        )
        assertEquals(
            "198.51.100.9",
            com.maodouchat.server.plugins.resolveClientAddress(true, "spoofed.example", "198.51.100.2, 198.51.100.9", "10.0.0.8")
        )
        assertEquals(
            "10.0.0.8",
            com.maodouchat.server.plugins.resolveClientAddress(true, "bad value", "also bad", "10.0.0.8")
        )
    }
}

class AdminRouteAuthenticationTest {
    @Test
    fun `admin dashboard rejects moderator and protects master account operations`() = testApplication {
        System.setProperty("MASTER_ADMINS", "")
        application { moduleUnderTest(seedDemoUsers = true) }

        val shell = client.get("/admin")
        assertEquals(HttpStatusCode.OK, shell.status)
        assertTrue(shell.bodyAsText().contains("login-form"))
        assertTrue(shell.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
        // 管理后台 CSP 有意放开 unsafe-inline（admin.js 大量内联样式/onclick，代码已注释说明无用户可控注入）。
        // 关键防护仍需具备：frame-ancestors 'none'（防点击劫持）、base-uri 'none'（防 base 标签注入）。
        val shellCsp = shell.headers["Content-Security-Policy"].orEmpty()
        assertTrue(shellCsp.contains("frame-ancestors 'none'"), shellCsp)
        assertTrue(shellCsp.contains("base-uri 'none'"), shellCsp)
        val adminCss = client.get("/admin/assets/admin.css")
        assertEquals(HttpStatusCode.OK, adminCss.status)
        assertTrue(adminCss.headers[HttpHeaders.ContentType].orEmpty().contains("text/css"))
        assertTrue(adminCss.headers[HttpHeaders.CacheControl].orEmpty().contains("must-revalidate"))
        val adminJs = client.get("/admin/assets/admin.js")
        assertEquals(HttpStatusCode.OK, adminJs.status)
        assertTrue(adminJs.headers[HttpHeaders.ContentType].orEmpty().contains("javascript"))
        assertTrue(adminJs.bodyAsText().contains("startSessionClock"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/dashboard").status)

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = extractToken(login.bodyAsText())
        val moderatorDashboard = client.get("/api/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, moderatorDashboard.status)
        val moderatorSession = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, moderatorSession.status)

        val aliceLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, aliceLogin.status)
        val aliceToken = extractToken(aliceLogin.bodyAsText())
        val post = client.post("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"admin pagination post","visibility":"PUBLIC"}""")
        }
        assertEquals(HttpStatusCode.Created, post.status, post.bodyAsText())
        val postId = Json.parseToJsonElement(post.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val comment = client.post("/api/posts/$postId/comments") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"admin comment listing"}""")
        }
        assertEquals(HttpStatusCode.Created, comment.status, comment.bodyAsText())
        val commentId = Json.parseToJsonElement(comment.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        System.setProperty("MASTER_ADMINS", "u2")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }.status)
        val wrongPasswordSession = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"wrong-password"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongPasswordSession.status)
        val aliceSession = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, aliceSession.status, aliceSession.bodyAsText())
        val aliceAdminToken = extractToken(aliceSession.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $aliceAdminToken")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/reports") {
            header(HttpHeaders.Authorization, "Bearer $aliceAdminToken")
        }.status)

        System.setProperty("MASTER_ADMINS", "u1")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $aliceAdminToken")
        }.status)
        val adminSession = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, adminSession.status, adminSession.bodyAsText())
        val adminToken = extractToken(adminSession.bodyAsText())
        val decodedAdminToken = com.maodouchat.server.auth.JwtConfig.verifyToken(adminToken)
        assertNotNull(decodedAdminToken)
        assertTrue(com.maodouchat.server.auth.JwtConfig.isAdminSession(decodedAdminToken))
        assertTrue(decodedAdminToken.expiresAt.time - decodedAdminToken.issuedAt.time <= com.maodouchat.server.auth.JwtConfig.ADMIN_VALIDITY_MS)
        val selfRenew = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, selfRenew.status, selfRenew.bodyAsText())
        val dashboard = client.get("/api/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, dashboard.status, dashboard.bodyAsText())
        assertTrue(dashboard.bodyAsText().contains("totalUsers"))
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/posts") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/moderation-rules") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.status)
        val comments = client.get("/api/admin/comments?limit=1&offset=0") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, comments.status, comments.bodyAsText())
        assertTrue(comments.bodyAsText().contains("admin comment listing"), comments.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.delete("/api/admin/comments/$commentId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.status)

        val selfDelete = client.delete("/api/admin/users/u1") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.BadRequest, selfDelete.status)

        val invalidRule = client.post("/api/admin/moderation-rules") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"","pattern":"","scope":"INVALID"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidRule.status, invalidRule.bodyAsText())

        val createdRule = client.post("/api/admin/moderation-rules") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"editable rule","scope":"POST","matchType":"KEYWORD","pattern":"spam","action":"WARN_MOD","priority":100}""")
        }
        assertEquals(HttpStatusCode.OK, createdRule.status, createdRule.bodyAsText())
        val ruleId = Json.parseToJsonElement(createdRule.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val updatedRule = client.put("/api/admin/moderation-rules/$ruleId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"edited rule","pattern":"scam","enabled":false,"priority":2}""")
        }
        assertEquals(HttpStatusCode.OK, updatedRule.status, updatedRule.bodyAsText())
        assertTrue(updatedRule.bodyAsText().contains("\"pattern\":\"scam\""), updatedRule.bodyAsText())
        assertTrue(updatedRule.bodyAsText().contains("\"enabled\":false"), updatedRule.bodyAsText())

        val report = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"targetType":"USER","targetId":"u2","reason":"test"}""")
        }
        assertEquals(HttpStatusCode.Created, report.status, report.bodyAsText())
        val reportId = Json.parseToJsonElement(report.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val reportAction = client.post("/api/admin/reports/$reportId/action") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"NO_ACTION","resolutionNote":"reviewed"}""")
        }
        assertEquals(HttpStatusCode.OK, reportAction.status, reportAction.bodyAsText())

        val malformedSessionRevoke = client.post("/api/admin/users/u2/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, malformedSessionRevoke.status, malformedSessionRevoke.bodyAsText())
        val mistypedSessionRevoke = client.post("/api/admin/users/u2/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"all":"true"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, mistypedSessionRevoke.status, mistypedSessionRevoke.bodyAsText())
        val implicitRevokeAll = client.post("/api/admin/users/u2/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, implicitRevokeAll.status, implicitRevokeAll.bodyAsText())
        val shortSessionPrefix = client.post("/api/admin/users/u2/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"tokenHashPrefix":"abcd"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, shortSessionPrefix.status, shortSessionPrefix.bodyAsText())
        val ambiguousSessionRevoke = client.post("/api/admin/users/u2/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"all":true,"tokenHashPrefix":"abcdef12"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, ambiguousSessionRevoke.status, ambiguousSessionRevoke.bodyAsText())

        val missingBotToggle = client.put("/api/admin/bots/missing-bot/enabled") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.NotFound, missingBotToggle.status, missingBotToggle.bodyAsText())

        System.setProperty("MASTER_ADMINS", "u1,u2")
        val protectedMaster = client.delete("/api/admin/users/u2") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.Forbidden, protectedMaster.status)
        val protectedMasterTotp = client.post("/api/admin/users/u2/disable-totp") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.Forbidden, protectedMasterTotp.status)
        val protectedBulkUnban = client.post("/api/admin/users/bulk-unban") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["u1","u2"]}""")
        }
        assertEquals(HttpStatusCode.OK, protectedBulkUnban.status, protectedBulkUnban.bodyAsText())
        val skippedUnbanIds = Json.parseToJsonElement(protectedBulkUnban.bodyAsText())
            .jsonObject["skipped"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("u1", "u2"), skippedUnbanIds)
        System.setProperty("MASTER_ADMINS", "u1")

        val deactivate = client.delete("/api/admin/users/u2") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, deactivate.status, deactivate.bodyAsText())
        assertTrue(deactivate.bodyAsText().contains("deactivated"))

        val logs = client.get("/api/admin/audit-logs") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, logs.status)
        assertTrue(logs.bodyAsText().contains("ADMIN_ACCOUNT_DEACTIVATED"))
        assertTrue(logs.bodyAsText().contains("REPORT_ACTION_APPLIED"))
        assertTrue(logs.bodyAsText().contains("\"actorId\":\"u1\""))
        assertTrue(logs.bodyAsText().contains("\"targetUserId\":\"u2\""))

        val users = client.get("/api/admin/users?q=deleted_") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, users.status)
        assertTrue(users.bodyAsText().contains("\"deletedAt\":"), users.bodyAsText())
        val export = client.get("/api/admin/audit-logs/export?limit=100") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, export.status, export.bodyAsText())
        assertTrue(export.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
        assertTrue(export.bodyAsText().startsWith("\uFEFFid,actorId,targetUserId"), export.bodyAsText().take(100))
        assertTrue(export.bodyAsText().contains("ADMIN_ACCOUNT_DEACTIVATED"))
        System.setProperty("MASTER_ADMINS", "")
    }
}


class AdminEnhanceRoutesTest {
    @Test
    fun `announcement CRUD, tag CRUD and rate-limit dashboard`() = testApplication {
        System.setProperty("MASTER_ADMINS", "u1") // alex（demo u1）为主管理员
        application { moduleUnderTest(seedDemoUsers = true) }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val userToken = extractToken(login.bodyAsText())

        // 普通用户可访问用户端公告端点（active 列表）
        assertEquals(HttpStatusCode.OK, client.get("/api/announcements/active") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }.status)

        // 管理员二次验证换 admin session
        val session = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        val adminToken = extractToken(session.bodyAsText())

        // 创建公告（ALL 受众）
        val now = System.currentTimeMillis()
        val created = client.post("/api/admin/announcements") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"System maintenance","content":"Down for upgrade tonight","audience":"ALL","level":"INFO","startsAt":$now,"expiresAt":${now + 86400000}}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val announcementId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // 发布
        val published = client.post("/api/admin/announcements/$announcementId/publish") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())

        // 用户拉取 active 公告
        val active = client.get("/api/announcements/active") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertEquals(HttpStatusCode.OK, active.status, active.bodyAsText())
        assertTrue(active.bodyAsText().contains("System maintenance"), active.bodyAsText())

        // 用户已读确认
        val ack = client.post("/api/announcements/$announcementId/ack") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertEquals(HttpStatusCode.OK, ack.status, ack.bodyAsText())

        // 撤回
        val cancelled = client.post("/api/admin/announcements/$announcementId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())

        // 用户标签 CRUD
        val tag = client.post("/api/admin/user-tags") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"test-tag","color":"#112233","riskLevel":"LOW"}""")
        }
        assertEquals(HttpStatusCode.Created, tag.status, tag.bodyAsText())
        val tagId = Json.parseToJsonElement(tag.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val tagList = client.get("/api/admin/user-tags") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, tagList.status, tagList.bodyAsText())
        assertTrue(tagList.bodyAsText().contains("test-tag"), tagList.bodyAsText())
        val tagDeleted = client.delete("/api/admin/user-tags/$tagId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, tagDeleted.status, tagDeleted.bodyAsText())

        // 限流仪表盘
        val dashboard = client.get("/api/admin/rate-limit/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, dashboard.status, dashboard.bodyAsText())

        // 用户会话/设备/推送列表（混合 mapOf + encodeToString 运行时序列化回归防护）
        val sessionsList = client.get("/api/admin/users/u2/sessions") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, sessionsList.status, sessionsList.bodyAsText())
        val sessionsBody = sessionsList.bodyAsText()
        assertTrue(sessionsBody.contains("\"refreshSessions\""), sessionsBody)
        assertTrue(sessionsBody.contains("\"signalDevices\""), sessionsBody)
        assertTrue(sessionsBody.contains("\"pushTokens\""), sessionsBody)

        // 推送校验密钥认证通道（dev 配置下 key 应为 null；匿名必须 401）
        val verifyKey = client.get("/api/push/verify-key") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertEquals(HttpStatusCode.OK, verifyKey.status, verifyKey.bodyAsText())
        assertTrue(verifyKey.bodyAsText().contains("\"key\""), verifyKey.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/push/verify-key").status)
    }
}


class GroupPlayRoutesTest {
    @Test
    fun `checkin chain and pk flows`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val loginA = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, loginA.status, loginA.bodyAsText())
        val alexToken = extractToken(loginA.bodyAsText())

        // 建群（alex + bob=u3）
        val chat = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2","u3"],"isGroup":true,"name":"play test group"}""")
        }
        assertEquals(HttpStatusCode.Created, chat.status, chat.bodyAsText())
        val chatId = Json.parseToJsonElement(chat.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // 群签到
        val checkin = client.post("/api/chats/$chatId/checkins") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, checkin.status, checkin.bodyAsText())
        val me = client.get("/api/chats/$chatId/checkins/me") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())

        // 群接龙
        val chain = client.post("/api/chats/$chatId/chains") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"chain title","topic":"topic","maxEntries":10}""")
        }
        assertEquals(HttpStatusCode.OK, chain.status, chain.bodyAsText())
        val chainId = Json.parseToJsonElement(chain.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val entry = client.post("/api/chains/$chainId/entries") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"first entry"}""")
        }
        assertEquals(HttpStatusCode.OK, entry.status, entry.bodyAsText())

        // 满员接龙：第二人加入应失败，而不是返回 200 + myJoined=false
        val fullChain = client.post("/api/chats/$chatId/chains") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"full chain","topic":"topic","maxEntries":2}""")
        }
        assertEquals(HttpStatusCode.OK, fullChain.status, fullChain.bodyAsText())
        val fullChainId = Json.parseToJsonElement(fullChain.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val fullChainFirst = client.post("/api/chains/$fullChainId/entries") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"only slot"}""")
        }
        assertEquals(HttpStatusCode.OK, fullChainFirst.status, fullChainFirst.bodyAsText())
        val loginAlice = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, loginAlice.status, loginAlice.bodyAsText())
        val aliceToken = extractToken(loginAlice.bodyAsText())
        val fullChainSecond = client.post("/api/chains/$fullChainId/entries") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"second slot"}""")
        }
        assertEquals(HttpStatusCode.OK, fullChainSecond.status, fullChainSecond.bodyAsText())
        val loginB = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, loginB.status, loginB.bodyAsText())
        val bobToken = extractToken(loginB.bodyAsText())
        val fullChainThird = client.post("/api/chains/$fullChainId/entries") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"late entry"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, fullChainThird.status, fullChainThird.bodyAsText())

        // 群 PK
        val pk = client.post("/api/chats/$chatId/pk") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"leftTitle":"Left","rightTitle":"Right"}""")
        }
        assertEquals(HttpStatusCode.OK, pk.status, pk.bodyAsText())
        val pkId = Json.parseToJsonElement(pk.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val vote = client.post("/api/pk/$pkId/vote") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"LEFT"}""")
        }
        assertEquals(HttpStatusCode.OK, vote.status, vote.bodyAsText())

        // 常规投票也属于群玩法写路径
        val poll = client.post("/api/chats/$chatId/polls") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"question":"poll question","options":["A","B"]}""")
        }
        assertEquals(HttpStatusCode.OK, poll.status, poll.bodyAsText())
        val pollId = Json.parseToJsonElement(poll.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // 已关闭的投票/PK 再投票必须 400，不能返回 200 + 旧状态
        val closePoll = client.post("/api/polls/$pollId/close") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, closePoll.status, closePoll.bodyAsText())
        val closedPollVote = client.post("/api/polls/$pollId/vote") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"optionIndexes":[0]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, closedPollVote.status, closedPollVote.bodyAsText())

        val closePk = client.post("/api/pk/$pkId/close") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, closePk.status, closePk.bodyAsText())
        val closedPkVote = client.post("/api/pk/$pkId/vote") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"LEFT"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, closedPkVote.status, closedPkVote.bodyAsText())

        // 禁言成员不得参与群玩法写入
        val mute = client.put("/api/chats/$chatId/members/u3/mute") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"mutedUntil":${System.currentTimeMillis() + 60_000L}}""")
        }
        assertEquals(HttpStatusCode.OK, mute.status, mute.bodyAsText())
        val mutedCheckin = client.post("/api/chats/$chatId/checkins") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.Forbidden, mutedCheckin.status, mutedCheckin.bodyAsText())
        val mutedChainEntry = client.post("/api/chains/$chainId/entries") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"muted entry"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, mutedChainEntry.status, mutedChainEntry.bodyAsText())
        val mutedPollCreate = client.post("/api/chats/$chatId/polls") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"question":"muted poll","options":["A","B"]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, mutedPollCreate.status, mutedPollCreate.bodyAsText())
        val mutedPollVote = client.post("/api/polls/$pollId/vote") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"optionIndexes":[0]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, mutedPollVote.status, mutedPollVote.bodyAsText())
    }
}


class SecretSurfaceHealthzTest {
    @Test
    fun `bot healthz routes respond for surface 71-78`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val userToken = extractToken(login.bodyAsText())

        val bot = client.post("/api/bots") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Healthz Bot","username":"healthz_bot"}""")
        }
        assertEquals(HttpStatusCode.OK, bot.status, bot.bodyAsText())
        val botToken = Json.parseToJsonElement(bot.bodyAsText()).jsonObject["tokenOnce"]!!.jsonPrimitive.content

        for (name in listOf("burnz", "ttlz", "fwlz", "simz", "2faz", "ndz", "dvz", "sntz")) {
            val resp = client.get("/api/bot/$name") {
                header("X-Bot-Token", botToken)
            }
            assertEquals(HttpStatusCode.OK, resp.status, "$name: " + resp.bodyAsText())
        }
    }
}


class AiEnhanceRoutesTest {
    @Test
    fun `conversation profile and emotion reply endpoints`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val userToken = extractToken(login.bodyAsText())

        // 建 1:1 聊天（alex + bob=u3）
        val chat = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u3"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chat.status, chat.bodyAsText())
        val chatId = Json.parseToJsonElement(chat.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // 会话画像（FakeAiGateway 返回固定摘要）
        val profile = client.post("/api/ai/enhance/conversation-profile") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"sender":"u1","text":"hello there"}],"chatId":"$chatId"}""")
        }
        assertEquals(HttpStatusCode.OK, profile.status, profile.bodyAsText())
        assertTrue(profile.bodyAsText().contains("summary"), profile.bodyAsText())

        // 情绪感知回复
        val emotion = client.post("/api/ai/enhance/emotion-reply") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"sender":"u1","text":"I am so happy today"}],"emotion":"happy","chatId":"$chatId"}""")
        }
        assertEquals(HttpStatusCode.OK, emotion.status, emotion.bodyAsText())
        assertTrue(emotion.bodyAsText().contains("reply"), emotion.bodyAsText())
    }
}

class CommentEditRouteTest {
    @Test
    fun `author can edit own comment`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = extractToken(login.bodyAsText())

        val createPost = client.post("/api/posts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hello world"}""")
        }
        assertEquals(HttpStatusCode.Created, createPost.status, createPost.bodyAsText())
        val postId = Json.parseToJsonElement(createPost.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val createComment = client.post("/api/posts/$postId/comments") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"first version"}""")
        }
        assertEquals(HttpStatusCode.Created, createComment.status, createComment.bodyAsText())
        val commentId = Json.parseToJsonElement(createComment.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val editComment = client.put("/api/posts/$postId/comments/$commentId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"edited version"}""")
        }
        assertEquals(HttpStatusCode.OK, editComment.status, editComment.bodyAsText())
        assertTrue(editComment.bodyAsText().contains("edited version"))
        assertTrue(!editComment.bodyAsText().contains("first version"))
    }
}
