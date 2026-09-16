package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureMessagingV2Routing
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.plugins.bearerTokenOrNull
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
import com.maodouchat.server.repository.ConfirmDeviceResult

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
 * - 5. 旧消息 REST：必须保持物理下线
 */

private fun freshDbUrl(): String =
    "jdbc:h2:mem:test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

/**
 * 9.3xx：群邀请同意流程——测试辅助：以 token 用户身份接受全部待处理群邀请。
 * 建群后参与者不再自动入群，必须先本人接受邀请。
 */
private suspend fun io.ktor.server.testing.ApplicationTestBuilder.acceptAllGroupInvites(token: String) {
    val list = client.get("/api/group-invitations") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    val invites = runCatching {
        Json.parseToJsonElement(list.bodyAsText()) as kotlinx.serialization.json.JsonArray
    }.getOrNull() ?: return
    invites.forEach { element ->
        val id = (element as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: return@forEach
        client.post("/api/group-invitations/$id/accept") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

private fun Application.moduleUnderTest(seedDemoUsers: Boolean = false, aiGateway: AiGateway = FakeAiGateway()) {
    System.setProperty("DATABASE_URL", freshDbUrl())
    System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
    System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
    System.setProperty("SEED_DEMO_USERS", seedDemoUsers.toString())
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
    // B1-B8 新增路由（与 Application.kt 生产注册一致，供测试覆盖）
    configurePollRouting()
    configureDeveloperRouting()
    configureAdminEnhanceRouting(
        announcementRepo = com.maodouchat.server.repository.AnnouncementRepository(),
        userTagRepo = com.maodouchat.server.repository.UserTagRepository(),
        rateLimitStatsRepo = com.maodouchat.server.repository.RateLimitStatsRepository()
    )
    configureSecretSurfaceRouting(userRepo = userRepo)
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
        // B14：readiness 覆盖后台周期任务状态。
        assertTrue(ready.bodyAsText().contains("\"backgroundTasks\""), ready.bodyAsText())
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
        assertTrue(offers.any { it["type"]?.jsonPrimitive?.content == "offer" && it["callId"]?.jsonPrimitive?.content == "call_filter" })
        assertTrue(offers.any { it["type"]?.jsonPrimitive?.content == "ice-candidate" && it["payload"]?.jsonPrimitive?.content == "audio|0|candidate-data" })
        val leftover = client.get("/api/signaling/pending") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals("[]", leftover.bodyAsText())
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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(owner)
        acceptAllGroupInvites(alice)
        acceptAllGroupInvites(login("bob@example.com"))

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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(member)

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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(targetToken)
        acceptAllGroupInvites(memberToken)

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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(adminToken)
        acceptAllGroupInvites(login("bob@example.com"))
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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(member)
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
    fun `cloud chat AI endpoints including settings and summary-sync are gone`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())

        for (path in listOf(
            "/api/ai/settings",
            "/api/ai/summary-sync",
            "/api/ai/summary-sync/ack",
            "/api/ai/rewrite",
            "/api/ai/rewrite/stream",
            "/api/ai/suggest-replies",
            "/api/ai/summarize",
            "/api/ai/translate",
            "/api/ai/analyze-file",
            "/api/ai/analyze-image",
            "/api/ai/transcribe",
            "/api/ai/group-assistant",
            "/api/ai/semantic-search",
            "/api/ai/global-semantic-search",
            "/api/ai/audit",
            "/api/ai/enhance/conversation-profile"
        )) {
            val response = client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"text":"hello"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status, "$path ${response.bodyAsText()}")
        }
        val settingsGet = client.get("/api/ai/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, settingsGet.status, settingsGet.bodyAsText())
    }
}

class RetiredAiSummarySyncGoneTest {
    @Test
    fun `summary-sync queue is gone`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        val post = client.post("/api/ai/summary-sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"syncId":"x","senderDeviceId":1,"targetDeviceIds":[2],"envelope":"e"}""")
        }
        assertEquals(HttpStatusCode.NotFound, post.status, post.bodyAsText())
        val get = client.get("/api/ai/summary-sync?deviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, get.status, get.bodyAsText())
    }
}

class NewFeaturesRouteTest {
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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alice@example.com","password":"password123"}""")
            }.bodyAsText()
        ))
        
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

        transaction {
            insertMessagingV2MessageFixture(
                messageId = messageId,
                conversationId = chatId,
                senderUserId = "u1",
                timestamp = System.currentTimeMillis(),
            )
            com.maodouchat.server.db.EncryptedAttachments.update({
                com.maodouchat.server.db.EncryptedAttachments.id eq attachmentId
            }) {
                it[com.maodouchat.server.db.EncryptedAttachments.status] = "COMMITTED"
                it[com.maodouchat.server.db.EncryptedAttachments.expiresAt] = null
            }
        }

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

        val deleteResult = com.maodouchat.server.messaging.v2.MessagingV2Repository()
            .deleteMessageForModeration(messageId)
        assertNotNull(deleteResult)
        deleteResult.deletedAttachmentIds.forEach(
            com.maodouchat.server.service.BlobStore::delete,
        )
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

        fun commitV2Fixture(targetMessageId: String, targetAttachmentId: String) {
            transaction {
                insertMessagingV2MessageFixture(
                    messageId = targetMessageId,
                    conversationId = chatId,
                    senderUserId = "u1",
                    timestamp = System.currentTimeMillis(),
                )
                com.maodouchat.server.db.EncryptedAttachments.update({
                    com.maodouchat.server.db.EncryptedAttachments.id eq targetAttachmentId
                }) {
                    it[com.maodouchat.server.db.EncryptedAttachments.status] = "COMMITTED"
                    it[com.maodouchat.server.db.EncryptedAttachments.expiresAt] = null
                }
            }
        }

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

        commitV2Fixture(messageId, attachmentId)

        val committedStatus = client.get("/api/attachment-uploads/$attachmentId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, committedStatus.status)
        assertTrue(committedStatus.bodyAsText().contains("\"status\":\"COMMITTED\""))

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

            commitV2Fixture(objectMessageId, objectId)

            val committedObjectStatus = client.get("/api/attachment-uploads/$objectId") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
            }
            assertEquals(HttpStatusCode.OK, committedObjectStatus.status)
            assertTrue(committedObjectStatus.bodyAsText().contains("\"status\":\"COMMITTED\""))
        }
    }
}

class CorsSameOriginRouteTest {
    @Test
    fun `same origin browser requests with Origin header are not rejected`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        // 模拟浏览器：携带与 BASE_URL 同源的 Origin 头做 POST——此前被 CORS 空白名单 403
        val origin = "http://localhost:8080"
        val login = client.post("/api/auth/login") {
            header(io.ktor.http.HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        // 注：跨域拒绝在开发模式（anyHost）下不可断言——生产白名单路径由
        // ServerConfig.corsOrigins 分支与上述 same-origin allowHost 共同约束
    }
}

class WsHeartbeatRouteTest {
    @Test
    fun `app level ping is answered with pong`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        val websocketClient = createClient { install(WebSockets) }
        websocketClient.webSocket(
            request = {
                url("/ws")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        ) {
            send(Frame.Text("""{"type":"PING","payload":"1787000000000"}"""))
            val text = withTimeout(5_000L) {
                while (true) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) return@withTimeout frame.readText()
                }
                error("unreachable")
            }
            val outer = Json.parseToJsonElement(text).jsonObject
            assertEquals("PONG", outer["type"]?.jsonPrimitive?.content)
            assertEquals("1787000000000", outer["payload"]?.jsonPrimitive?.content)
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

        val messageId = "m_chat_cleanup_v2"
        transaction {
            insertMessagingV2MessageFixture(
                messageId = messageId,
                conversationId = chatId,
                senderUserId = "u1",
                timestamp = System.currentTimeMillis(),
            )
        }

        assertEquals(HttpStatusCode.OK, client.post("/api/messages/$messageId/star") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        acceptAllGroupInvites(aliceToken)

        SignalKeyRepository().apply {
            fun uploadBundle(
                userId: String,
                deviceId: Int,
                label: String,
                identityKey: String? = null,
                identityPrivateKey: org.signal.libsignal.protocol.ecc.ECPrivateKey? = null
            ) {
                // 9.298：服务端上传验签后，测试必须用真实密钥包（identity 签 SPK）
                val generatedPair = if (identityKey == null) Curve.generateKeyPair() else null
                val actualIdentityKey = identityKey
                    ?: Base64.getEncoder().encodeToString(generatedPair!!.publicKey.serialize())
                val signingKey = identityPrivateKey ?: generatedPair!!.privateKey
                val spkPair = Curve.generateKeyPair()
                val spkSignature = Curve.calculateSignature(signingKey, spkPair.publicKey.serialize())
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
                    identityKey = actualIdentityKey,
                    registrationId = 10_000 + deviceId,
                    signedPreKeyId = deviceId,
                    signedPreKey = Base64.getEncoder().encodeToString(spkPair.publicKey.serialize()),
                    signedPreKeySignature = Base64.getEncoder().encodeToString(spkSignature),
                    preKeys = emptyList()
                )
                check(uploadResult == SignalKeyRepository.UploadKeyPackageResult.UPLOADED) { "uploadKeyPackage failed: $uploadResult" }
            }
            val approverKeyPair = Curve.generateKeyPair()
            val approverIdentity = Base64.getEncoder().encodeToString(approverKeyPair.publicKey.serialize())
            val targetKeyPair = Curve.generateKeyPair()
            val targetIdentity = Base64.getEncoder().encodeToString(targetKeyPair.publicKey.serialize())
            uploadBundle("u1", 1, "alex-device-1")
            touchDevice("u1", 1)
            uploadBundle("u2", 1, "alice-device-1", approverIdentity, approverKeyPair.privateKey)
            touchDevice("u2", 1)
            uploadBundle("u2", 2, "alice-device-2", targetIdentity, targetKeyPair.privateKey)
            touchDevice("u2", 2)
            val proofPayload = "maodouchat-device-confirm:v1\nu2\n1\n2\n$targetIdentity".toByteArray()
            val proof = Base64.getEncoder().encodeToString(Curve.calculateSignature(approverKeyPair.privateKey, proofPayload))
            val forgedProof = Base64.getEncoder().encodeToString(ByteArray(64))
            assertEquals(ConfirmDeviceResult.INVALID_PROOF, confirmDevice("u2", 2, 1, forgedProof))
            assertEquals(ConfirmDeviceResult.CONFIRMED, confirmDevice("u2", 2, 1, proof))
        }

        // Coverage is no longer client-reported. Only a committed v2 Sender Key mailbox
        // message can make a target SENT.
        val retiredReport = client.post("/api/chats/$chatId/sender-key-distributions") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"epoch":1,"messageId":"sk_alex_1","targets":[{"userId":"u2","deviceId":1,"status":"SENT"}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, retiredReport.status, retiredReport.bodyAsText())

        val alexCoverage = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, alexCoverage.status, alexCoverage.bodyAsText())
        assertTrue(alexCoverage.bodyAsText().contains("\"pending\":2"), alexCoverage.bodyAsText())
        assertTrue(alexCoverage.bodyAsText().contains("device_not_covered"), alexCoverage.bodyAsText())

        val aliceRetiredReport = client.post("/api/chats/$chatId/sender-key-distributions") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"epoch":1,"messageId":"sk_alice_1","targets":[{"userId":"u1","deviceId":1,"status":"SENT"},{"userId":"u2","deviceId":2,"status":"SENT"}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, aliceRetiredReport.status, aliceRetiredReport.bodyAsText())

        val alexStillPending = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertTrue(alexStillPending.bodyAsText().contains("\"pending\":2"), alexStillPending.bodyAsText())

        assertEquals(HttpStatusCode.OK, client.delete("/api/keys/devices/2") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }.status)
        val afterDeviceRemoval = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1&currentDeviceId=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertTrue(afterDeviceRemoval.bodyAsText().contains("\"total\":1"), afterDeviceRemoval.bodyAsText())
        assertTrue(afterDeviceRemoval.bodyAsText().contains("\"pending\":1"), afterDeviceRemoval.bodyAsText())
    }

    @Test
    fun `omitted currentDeviceId without bound session still lists own other devices`() = testApplication {
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
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"SKD unbound session"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        acceptAllGroupInvites(aliceToken)

        SignalKeyRepository().apply {
            fun uploadBundle(userId: String, deviceId: Int, identityKey: String? = null, identityPrivateKey: org.signal.libsignal.protocol.ecc.ECPrivateKey? = null) {
                val generatedPair = if (identityKey == null) Curve.generateKeyPair() else null
                val actualIdentityKey = identityKey
                    ?: Base64.getEncoder().encodeToString(generatedPair!!.publicKey.serialize())
                val signingKey = identityPrivateKey ?: generatedPair!!.privateKey
                val spkPair = Curve.generateKeyPair()
                val spkSignature = Curve.calculateSignature(signingKey, spkPair.publicKey.serialize())
                val sessionId = "test-session-unbound-$userId-$deviceId"
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
                    identityKey = actualIdentityKey,
                    registrationId = 20_000 + deviceId,
                    signedPreKeyId = deviceId,
                    signedPreKey = Base64.getEncoder().encodeToString(spkPair.publicKey.serialize()),
                    signedPreKeySignature = Base64.getEncoder().encodeToString(spkSignature),
                    preKeys = emptyList()
                )
                check(uploadResult == SignalKeyRepository.UploadKeyPackageResult.UPLOADED) { "uploadKeyPackage failed: $uploadResult" }
            }
            val firstKeyPair = Curve.generateKeyPair()
            val firstIdentity = Base64.getEncoder().encodeToString(firstKeyPair.publicKey.serialize())
            val secondKeyPair = Curve.generateKeyPair()
            val secondIdentity = Base64.getEncoder().encodeToString(secondKeyPair.publicKey.serialize())
            uploadBundle("u1", 1, firstIdentity, firstKeyPair.privateKey)
            touchDevice("u1", 1)
            uploadBundle("u1", 2, secondIdentity, secondKeyPair.privateKey)
            touchDevice("u1", 2)
            val proofPayload = "maodouchat-device-confirm:v1\nu1\n1\n2\n$secondIdentity".toByteArray()
            val proof = Base64.getEncoder().encodeToString(Curve.calculateSignature(firstKeyPair.privateKey, proofPayload))
            assertEquals(ConfirmDeviceResult.CONFIRMED, confirmDevice("u1", 2, 1, proof))
            uploadBundle("u2", 1)
            touchDevice("u2", 1)
        }

        val coverage = client.get("/api/chats/$chatId/sender-key-distributions?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, coverage.status, coverage.bodyAsText())
        val body = coverage.bodyAsText()
        // 登录会话尚未绑定 signalDeviceId，省略 currentDeviceId 时不得把自身全部设备从覆盖清单抹掉。
        assertTrue(body.contains("\"userId\":\"u1\""), body)
        assertTrue(body.contains("\"deviceId\":2"), body)
        assertTrue(body.contains("\"userId\":\"u2\""), body)
        assertTrue(body.contains("device_not_covered"), body)
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals(3, parsed["total"]!!.jsonPrimitive.content.toInt(), body)
        assertEquals(3, parsed["pending"]!!.jsonPrimitive.content.toInt(), body)
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
        // 9.3xx：参与者先接受群邀请才成为正式成员
        val aliceInviteToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alice@example.com","password":"password123"}""")
            }.bodyAsText()
        )
        val bobInviteToken = extractToken(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"bob@example.com","password":"password123"}""")
            }.bodyAsText()
        )
        acceptAllGroupInvites(aliceInviteToken)
        acceptAllGroupInvites(bobInviteToken)

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
    fun `enhance endpoints are gone`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        val gone = client.post("/api/ai/enhance/emotion-reply") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"sender":"u1","text":"hi"}],"emotion":"happy"}""")
        }
        assertEquals(HttpStatusCode.NotFound, gone.status, gone.bodyAsText())
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

class WsLegacyMessageProtocolRetiredTest {
    @Test
    fun `legacy websocket message commands are rejected`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        val websocketClient = createClient { install(WebSockets) }

        websocketClient.webSocket(
            request = {
                url("/ws")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        ) {
            send(Frame.Text("""{"type":"SEND_MESSAGE","payload":"{}"}"""))
            val outer = withTimeout(5_000L) {
                Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            }
            assertEquals("ERROR", outer["type"]!!.jsonPrimitive.content)
            val error = Json.parseToJsonElement(outer["payload"]!!.jsonPrimitive.content).jsonObject
            assertEquals("UNSUPPORTED_WS_COMMAND", error["code"]!!.jsonPrimitive.content)
        }
    }
}

class RestLegacyMessageProtocolRetiredTest {
    @Test
    fun `legacy human message routes stay physically removed`() = testApplication {
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
        val auth: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
        }

        val responses = listOf(
            client.get("/api/chats/$chatId/messages", auth),
            client.post("/api/chats/$chatId/messages") { auth(); setBody("{}") },
            client.get("/api/chats/$chatId/unread-window", auth),
            client.post("/api/chats/$chatId/mark-read", auth),
            client.put("/api/messages/legacy-message/status") { auth(); setBody("{}") },
            client.post("/api/messages/legacy-message/revoke", auth),
            client.put("/api/messages/legacy-message") { auth(); setBody("{}") },
            client.put("/api/messages/legacy-message/reaction") { auth(); setBody("{}") },
            client.get("/api/messages/legacy-message/read-receipts", auth),
            client.post("/api/messages/batch-read") { auth(); setBody("{}") },
            client.delete("/api/messages/legacy-message", auth),
            client.get("/api/chats/$chatId/message-mutations", auth),
            client.post("/api/search") { auth(); setBody("{\"query\":\"hello\"}") },
            client.post("/api/chats/$chatId/arm-disappearing", auth),
            client.post("/api/attachments/legacy-attachment/commit") { auth(); setBody("{}") },
        )

        responses.forEach { response ->
            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        }
    }
}

class PreKeyBundleStabilityRouteTest {
    @Test
    fun `self compatible prekey bundle is allowed after upload`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val token = extractToken(login.bodyAsText())
        val jwt = checkNotNull(com.maodouchat.server.auth.JwtConfig.verifyToken(token))
        val sessionId = checkNotNull(com.maodouchat.server.auth.JwtConfig.authSessionId(jwt))
        val identityPair = Curve.generateKeyPair()
        val identityKey = Base64.getEncoder().encodeToString(identityPair.publicKey.serialize())
        val spkPair = Curve.generateKeyPair()
        val spkSignature = Curve.calculateSignature(identityPair.privateKey, spkPair.publicKey.serialize())
        assertEquals(
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED,
            SignalKeyRepository().uploadKeyPackage(
                userId = "u1",
                authSessionId = sessionId,
                deviceId = 1,
                identityKey = identityKey,
                registrationId = 12_345,
                signedPreKeyId = 1,
                signedPreKey = Base64.getEncoder().encodeToString(spkPair.publicKey.serialize()),
                signedPreKeySignature = Base64.getEncoder().encodeToString(spkSignature),
                preKeys = emptyList()
            )
        )

        val selfBundle = client.get("/api/keys/u1/prekey-bundle") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, selfBundle.status, selfBundle.bodyAsText())
        assertFalse(selfBundle.bodyAsText().contains("不能获取自己的密钥包"), selfBundle.bodyAsText())
        val body = Json.parseToJsonElement(selfBundle.bodyAsText()).jsonObject
        assertEquals(identityKey, body["identityKey"]!!.jsonPrimitive.content)
        assertEquals(1, body["deviceId"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `missing keys return 404 not 500`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())
        val chatResp = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, chatResp.status, chatResp.bodyAsText())

        val peerMissing = client.get("/api/keys/u2/prekey-bundle") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, peerMissing.status, peerMissing.bodyAsText())
        assertTrue(
            peerMissing.bodyAsText().contains("用户密钥未上传") ||
                peerMissing.bodyAsText().contains("设备未确认"),
            peerMissing.bodyAsText()
        )
        assertFalse(peerMissing.status.value in 500..599)

        val unknown = client.get("/api/keys/no-such-user/prekey-bundle") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, unknown.status, unknown.bodyAsText())
        assertFalse(unknown.status.value in 500..599)
    }
}

class SoloGroupChannelRouteTest {
    @Test
    fun `creator alone can open a group and a channel`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val token = extractToken(login.bodyAsText())

        val emptyDirect = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":[],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyDirect.status, emptyDirect.bodyAsText())

        val soloGroup = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":[],"isGroup":true,"groupName":"Solo"}""")
        }
        assertEquals(HttpStatusCode.Created, soloGroup.status, soloGroup.bodyAsText())
        val groupBody = Json.parseToJsonElement(soloGroup.bodyAsText()).jsonObject
        assertEquals("GROUP", groupBody["chatType"]!!.jsonPrimitive.content)
        assertEquals(1, groupBody["participants"]!!.jsonArray.size)
        assertEquals("u1", groupBody["participants"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        val soloChannel = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":[],"isGroup":true,"groupName":"SoloChannel","chatType":"CHANNEL"}""")
        }
        assertEquals(HttpStatusCode.Created, soloChannel.status, soloChannel.bodyAsText())
        val channelBody = Json.parseToJsonElement(soloChannel.bodyAsText()).jsonObject
        assertEquals("CHANNEL", channelBody["chatType"]!!.jsonPrimitive.content)
        assertEquals(1, channelBody["participants"]!!.jsonArray.size)
    }
}

class BotInboxRouteTest {
    @Test
    fun `bot inbox rejects ciphertext and delivers slash without dumping group text`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }

        suspend fun login(email: String): String {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return extractToken(response.bodyAsText())
        }

        val alex = login("alex@example.com")
        val alice = login("alice@example.com")

        val createdBot = client.post("/api/bots") {
            header(HttpHeaders.Authorization, "Bearer $alex")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Inbox Bot","username":"inbox_route_bot"}""")
        }
        assertEquals(HttpStatusCode.OK, createdBot.status, createdBot.bodyAsText())
        val botJson = Json.parseToJsonElement(createdBot.bodyAsText()).jsonObject
        val botId = botJson["id"]!!.jsonPrimitive.content
        val botToken = botJson["tokenOnce"]!!.jsonPrimitive.content

        val group = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $alex")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Bot Inbox Group"}""")
        }
        assertEquals(HttpStatusCode.Created, group.status, group.bodyAsText())
        val chatId = Json.parseToJsonElement(group.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        acceptAllGroupInvites(alice)

        val invite = client.post("/api/chats/$chatId/bots") {
            header(HttpHeaders.Authorization, "Bearer $alex")
            contentType(ContentType.Application.Json)
            setBody("""{"botId":"$botId"}""")
        }
        assertEquals(HttpStatusCode.OK, invite.status, invite.bodyAsText())

        val commands = client.get("/api/chats/$chatId/bot-commands") {
            header(HttpHeaders.Authorization, "Bearer $alice")
        }
        assertEquals(HttpStatusCode.OK, commands.status, commands.bodyAsText())
        val commandBody = Json.parseToJsonElement(commands.bodyAsText()).jsonObject
        assertTrue(commandBody["commands"]!!.jsonArray.size >= 2, commands.bodyAsText())

        val ciphertext = client.post("/api/chats/$chatId/bot-inbox") {
            header(HttpHeaders.Authorization, "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"{\"ciphertext\":\"abc\"}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, ciphertext.status, ciphertext.bodyAsText())

        val chatter = client.post("/api/chats/$chatId/bot-inbox") {
            header(HttpHeaders.Authorization, "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello everyone"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, chatter.status, chatter.bodyAsText())

        val help = client.post("/api/chats/$chatId/bot-inbox") {
            header(HttpHeaders.Authorization, "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"/help tomorrow"}""")
        }
        assertEquals(HttpStatusCode.OK, help.status, help.bodyAsText())

        val updates = client.get("/api/bot/getUpdates") {
            header("X-Bot-Token", botToken)
        }
        assertEquals(HttpStatusCode.OK, updates.status, updates.bodyAsText())
        val updateText = updates.bodyAsText()
        assertTrue(updateText.contains("user_command"), updateText)
        assertTrue(updateText.contains("/help tomorrow") || updateText.contains("\"command\":\"help\""), updateText)
        assertFalse(updateText.contains("hello everyone"), updateText)

        val dm = client.post("/api/bots/$botId/dm") {
            header(HttpHeaders.Authorization, "Bearer $alice")
        }
        assertEquals(HttpStatusCode.Created, dm.status, dm.bodyAsText())
        val dmId = Json.parseToJsonElement(dm.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val dmInbox = client.post("/api/chats/$dmId/bot-inbox") {
            header(HttpHeaders.Authorization, "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hi bot"}""")
        }
        assertEquals(HttpStatusCode.OK, dmInbox.status, dmInbox.bodyAsText())
    }
}

class BotProbeRoutingTest {
    @Test
    fun `table driven bot probes preserve every compatibility response`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val userToken = Json.parseToJsonElement(login.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val created = client.post("/api/bots") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Probe Bot","username":"probe_table_bot"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val botToken = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["tokenOnce"]!!.jsonPrimitive.content

        val expected = mapOf(
            "buzzz" to (60 to "buzz"),
            "chimez" to (60 to "chime"),
            "ringz" to (60 to "ring"),
            "beepz" to (60 to "beep"),
            "pushz" to (60 to "push"),
            "quietz" to (60 to "quiet"),
            "fealz" to (60 to "feel"),
            "slidez" to (60 to "slide"),
            "leakz" to (60 to "leak"),
            "vaultz" to (61 to "vault"),
            "sealz" to (62 to "seal"),
            "markz" to (63 to "mark"),
            "linkz" to (64 to "link"),
            "privz" to (65 to "priv"),
            "metaz" to (66 to "meta"),
            "typtz" to (67 to "typing"),
            "redz" to (68 to "read"),
            "presz" to (69 to "presence"),
            "lastsz" to (70 to "lastseen"),
        )
        expected.forEach { (path, expectation) ->
            val response = client.get("/api/bot/$path") {
                header("X-Bot-Token", botToken)
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(expectation.first, body["surface"]!!.jsonPrimitive.content.toInt(), path)
            assertEquals(expectation.second, body["ping"]!!.jsonPrimitive.content, path)
        }
        val booleanSignals = mapOf(
            "readyz" to "ready",
            "alivez" to "alive",
            "heartbeatz" to "heartbeat",
            "pulsez" to "pulse",
            "tickz" to "tick",
            "tockz" to "tock",
            "clangz" to "clang",
            "dingz" to "ding",
        )
        booleanSignals.forEach { (path, signal) ->
            val response = client.get("/api/bot/$path") {
                header("X-Bot-Token", botToken)
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("true", body[signal]!!.jsonPrimitive.content, path)
            assertEquals(60, body["surface"]!!.jsonPrimitive.content.toInt(), path)
            assertTrue(body["serverTime"]!!.jsonPrimitive.content.toLong() > 0L, path)
        }
        val flagProbes = mapOf(
            "getCallMediaFlags" to (60 to setOf("callsEnabled", "voiceCallEnabled", "videoCallEnabled", "gifSendEnabled")),
            "getAppearanceFlags" to (60 to setOf("chatWallpaperEnabled", "chatFontScaleEnabled", "voiceCallEnabled", "videoCallEnabled")),
            "getNotifyFlags" to (60 to setOf("unreadPriorityEnabled", "ringtoneEnabled", "chatWallpaperEnabled", "chatFontScaleEnabled")),
            "getAlertMediaFlags" to (60 to setOf("notificationSoundEnabled", "notificationPreviewEnabled", "unreadPriorityEnabled", "ringtoneEnabled")),
            "getPushFlags" to (60 to setOf("pushNotificationsEnabled", "taskRemindersEnabled", "notificationSoundEnabled", "notificationPreviewEnabled")),
            "getQuietFlags" to (60 to setOf("dndEnabled", "pushNotificationsEnabled", "taskRemindersEnabled")),
            "getFeelFlags" to (60 to setOf("inAppSoundsEnabled", "hapticsEnabled", "dndEnabled")),
            "getMotionFlags" to (60 to setOf("chatAnimationsEnabled", "navTransitionsEnabled", "inAppSoundsEnabled", "hapticsEnabled")),
            "getCaptureShieldFlags" to (60 to setOf("screenshotDetectEnabled", "recentsExclusionEnabled", "screenSecureRuntimeEnabled", "captureAlertEnabled")),
            "getSecretLeakFlags" to (60 to setOf("secretCopyBlockEnabled", "secretMediaExportBlockEnabled", "screenshotDetectEnabled", "recentsExclusionEnabled")),
            "getSecretVaultFlags" to (61 to setOf("secretForwardBlockEnabled", "secretChatExportBlockEnabled", "secretCopyBlockEnabled", "secretMediaExportBlockEnabled")),
            "getSealedCryptoFlags" to (62 to setOf("sealedSenderEnabled", "pqxdhPreview", "secretChatEnabled")),
            "getMarkPrivacyFlags" to (63 to setOf("secretAutoDisappearEnabled", "blindWatermarkEnabled")),
            "getLinkPrivacyFlags" to (64 to setOf("secretLinkPreviewBlockEnabled", "secretExternalLinkBlockEnabled", "linkPreviewEnabled")),
            "getNotifyPrivacyFlags" to (65 to setOf("secretNotifPreviewBlockEnabled", "secretListPreviewBlockEnabled", "secretReactionBlockEnabled", "secretStarBlockEnabled", "notificationPreviewEnabled")),
            "getSecretMetaFlags" to (66 to setOf("secretReactionBlockEnabled", "secretStarBlockEnabled", "reactionsEnabled", "messageStarringEnabled")),
            "getSecretTypingFlags" to (67 to setOf("secretTypingBlockEnabled", "typingIndicatorsEnabled")),
            "getSecretReadReceiptFlags" to (68 to setOf("secretReadReceiptBlockEnabled", "readReceiptsEnabled")),
            "getSecretPresenceFlags" to (69 to setOf("secretPresenceBlockEnabled", "presenceEnabled")),
            "getSecretLastSeenFlags" to (70 to setOf("secretLastSeenBlockEnabled", "presenceEnabled")),
        )
        flagProbes.forEach { (path, expectation) ->
            val response = client.get("/api/bot/$path") {
                header("X-Bot-Token", botToken)
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(expectation.first, body["surface"]!!.jsonPrimitive.content.toInt(), path)
            expectation.second.forEach { key -> assertTrue(key in body, "$path missing $key") }
        }
        val runtimeProjectionSizes = mapOf(
            "getRuntimeFlags" to 63,
            "getMuteArchiveFlags" to 58,
            "getPrivacyFlags" to 57,
            "getMessagePolicyFlags" to 54,
            "getEngagementFlags" to 52,
            "getComposerFlags" to 50,
            "getSocialFlags" to 49,
            "getIdentityFlags" to 44,
            "getMediaFlags" to 42,
            "getLocationFlags" to 6,
            "getPrivacySecureFlags" to 6,
            "getMediaSendFlags" to 6,
            "getMediaPrivacyFlags" to 8,
        )
        runtimeProjectionSizes.forEach { (path, expectedSize) ->
            val response = client.get("/api/bot/$path") {
                header("X-Bot-Token", botToken)
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(expectedSize, body.size, path)
            assertTrue(body["serverTime"]!!.jsonPrimitive.content.toLong() > 0L, path)
        }
    }
}

class BotHintRoutingTest {
    @Test
    fun `table driven hints persist policy prefix and sanitize secret metadata`() = testApplication {
        application { moduleUnderTest(seedDemoUsers = true) }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        val userToken = extractToken(login.bodyAsText())
        val createdBot = client.post("/api/bots") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Hint Bot","username":"hint_table_bot"}""")
        }
        assertEquals(HttpStatusCode.OK, createdBot.status, createdBot.bodyAsText())
        val botBody = Json.parseToJsonElement(createdBot.bodyAsText()).jsonObject
        val botId = botBody["id"]!!.jsonPrimitive.content
        val botToken = botBody["tokenOnce"]!!.jsonPrimitive.content
        val createdChat = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":[],"isGroup":true,"groupName":"Hint Test"}""")
        }
        assertEquals(HttpStatusCode.Created, createdChat.status, createdChat.bodyAsText())
        val chatId = Json.parseToJsonElement(createdChat.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val invited = client.post("/api/chats/$chatId/bots") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"botId":"$botId"}""")
        }
        assertEquals(HttpStatusCode.OK, invited.status, invited.bodyAsText())

        suspend fun send(path: String, hintJson: String): String {
            val response = client.post("/api/bot/$path") {
                header("X-Bot-Token", botToken)
                contentType(ContentType.Application.Json)
                setBody("""{"chatId":"$chatId","hint":$hintJson}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject["messageId"]!!.jsonPrimitive.content
        }

        val voiceId = send("sendVoiceCallHint", "\"Use voice\"")
        val secretId = send("sendSecretReactionHint", "\"Line\\nBreak\\u0001\"")
        val repository = ServiceMessageRepository()
        assertEquals("CALL:VOICE Use voice", repository.getById(voiceId)?.content)
        assertEquals("META:REACT Line Break", repository.getById(secretId)?.content)
        assertEquals("SYSTEM", repository.getById(secretId)?.type)
    }
}

/**
 * 不变量 3 的后半句：「WebSocket only emits `INBOX_AVAILABLE_V2`」。
 *
 * 此前只有一个反向用例（`legacy websocket message commands are rejected`，证明 WS **不再接受**
 * 发送命令），但没有任何用例断言**投递时真正发到 socket 上的帧**是什么形状——也就是
 * 「wake 帧里会不会被塞进消息内容」这条没人守；而 `/api/v2/messages` 至今没有 HTTP 级测试。
 *
 * 这里把发送端点真跑一遍，用**真实 WebSocket 连接**接收收件人实际收到的帧
 * （不用 mock：mock 只能证明「我以为会发什么」）。
 */
class MessagingV2DeliveryWakeupTest {

    private val cipherForU1Device2 = "CIPHERTEXT-FOR-U1-DEVICE2"
    private val cipherForU2Device1 = "CIPHERTEXT-FOR-U2-DEVICE1"

    @Test
    fun `a committed v2 send wakes the recipient with nothing but INBOX_AVAILABLE_V2`() = testApplication {
        application {
            moduleUnderTest(seedDemoUsers = true)
            // V2 路由由 Application.kt 单独装配，不在 configureRouting 里。
            configureMessagingV2Routing(
                com.maodouchat.server.messaging.v2.MessagingV2Repository(),
            )
        }

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
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Wakeup Test"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        acceptAllGroupInvites(aliceToken)

        // 直接种「已确认且有 identity key」的设备：服务端只需要设备地址与密钥存在，
        // 密文对它是不透明的，因此不必跑真实 Signal 加密。
        org.jetbrains.exposed.sql.transactions.transaction {
            listOf("u1" to 1, "u1" to 2, "u2" to 1).forEach { (userId, deviceId) ->
                com.maodouchat.server.db.SignalDevices.insert {
                    it[com.maodouchat.server.db.SignalDevices.userId] = userId
                    it[com.maodouchat.server.db.SignalDevices.deviceId] = deviceId
                    it[deviceName] = "$userId-$deviceId"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = deviceId
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        com.maodouchat.server.db.SignalKeys.insert {
                            it[id] = "$userId-$deviceId-$keyType"
                            it[com.maodouchat.server.db.SignalKeys.userId] = userId
                            it[com.maodouchat.server.db.SignalKeys.deviceId] = deviceId
                            it[com.maodouchat.server.db.SignalKeys.keyType] = keyType
                            it[keyData] = "x"
                            it[createdAt] = 1L
                        }
                    }
            }
            // 登录会话默认未绑定设备（V2 路由会回 409 DEVICE_NOT_READY），把它绑到 u1 的设备 1。
            com.maodouchat.server.db.AuthSessions.update(
                { com.maodouchat.server.db.AuthSessions.userId eq "u1" },
            ) {
                it[com.maodouchat.server.db.AuthSessions.signalDeviceId] = 1
            }
        }

        val memberRevision = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.Chats.selectAll()
                .where { com.maodouchat.server.db.Chats.id eq chatId }
                .single()[com.maodouchat.server.db.Chats.memberRevision]
        }
        val http = client
        val wsClient = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }
        val frames = mutableListOf<String>()

        // 在同一个 suspend 作用域里：先连上并等它注册，再发消息，再收帧。
        wsClient.webSocket(
            request = {
                url("/ws")
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
            },
        ) {
            val registered = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                while (com.maodouchat.server.plugins.ConnectionRegistry.onlineUsers["u2"].isNullOrEmpty()) {
                    kotlinx.coroutines.delay(20)
                }
                true
            }
            assertTrue(registered == true, "u2 的 WebSocket 未在 5s 内注册")

            val sent = http.post("/api/v2/messages") {
                header(HttpHeaders.Authorization, "Bearer $alexToken")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"id":"wake_1","conversationId":"$chatId","kind":"DATA","clientTimestamp":1000,"groupRevision":$memberRevision,
                     "attachmentIds":[],
                     "envelopes":[
                       {"recipientUserId":"u1","recipientDeviceId":2,"ciphertextType":"TEXT","ciphertext":"$cipherForU1Device2"},
                       {"recipientUserId":"u2","recipientDeviceId":1,"ciphertextType":"TEXT","ciphertext":"$cipherForU2Device1"}
                     ]}
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.Accepted, sent.status, sent.bodyAsText())

            // 收接下来的几帧（wake 之后不应再有别的）；每帧最多等 2s。
            repeat(2) {
                val next = kotlinx.coroutines.withTimeoutOrNull(2_000L) { incoming.receive() }
                if (next is io.ktor.websocket.Frame.Text) frames += next.readText()
            }
        }

        assertTrue(frames.isNotEmpty(), "收件人应至少收到一个 wake 帧")
        frames.forEach { frame ->
            assertEquals(
                """{"type":"INBOX_AVAILABLE_V2","payload":"{}"}""",
                frame,
                "投递 wake 帧必须只有信号类型、没有任何消息数据",
            )
            assertFalse(frame.contains(cipherForU2Device1), "wake 帧里出现了密文：$frame")
            assertFalse(frame.contains(cipherForU1Device2), "wake 帧里出现了密文：$frame")
            assertFalse(frame.contains("wake_1"), "wake 帧里出现了消息 id：$frame")
        }
    }
}

/**
 * M5 第一片（G12）：**双设备端到端投递 + 离线可取 + ACK 按设备**。
 *
 * 与 `MessagingV2DeliveryWakeupTest` 的分工：那个用例只证明「投递时推的帧是纯信号」，
 * 本用例证明**消息本身真的跨设备送达**——走真实 HTTP：A 发送 → B（从未建立任何 WebSocket，
 * 即离线）拉取收件箱拿到**逐字节相同**的密文 → B 确认后自己清空 → 但发给同一会话里
 * 另一个设备的副本必须还在。
 *
 * 最后一条是关键：如果不查这一条，「ACK 生效」完全可能是靠把**所有**设备的副本一起删掉
 * 来实现的——那在真实使用里就是「一个人读了，另一个人的消息也没了」。
 */
class MessagingV2TwoDeviceDeliveryTest {

    @Test
    fun `an offline device pulls the exact ciphertext and its ack keeps the sibling copy`() = testApplication {
        application {
            moduleUnderTest(seedDemoUsers = true)
            configureMessagingV2Routing(
                com.maodouchat.server.messaging.v2.MessagingV2Repository(),
            )
        }

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
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"Two Device Test"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        acceptAllGroupInvites(aliceToken)

        val cipherForU1Device2 = "CIPHER-U1D2-" + java.util.UUID.randomUUID()
        val cipherForU2Device1 = "CIPHER-U2D1-" + java.util.UUID.randomUUID()
        val cipherForU2Device2 = "CIPHER-U2D2-" + java.util.UUID.randomUUID()

        org.jetbrains.exposed.sql.transactions.transaction {
            // u2 刻意给**两台**设备：只有这样才钉得住「收件箱是按设备隔离的」。
            // 若 u2 只有一台设备，「按 deviceId 过滤」与「按 userId 过滤」结果相同，
            // 去掉设备过滤的回归就测不出来（本轮反证时实测到这一点）。
            listOf("u1" to 1, "u1" to 2, "u2" to 1, "u2" to 2).forEach { (userId, deviceId) ->
                com.maodouchat.server.db.SignalDevices.insert {
                    it[com.maodouchat.server.db.SignalDevices.userId] = userId
                    it[com.maodouchat.server.db.SignalDevices.deviceId] = deviceId
                    it[deviceName] = "$userId-$deviceId"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = deviceId
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        com.maodouchat.server.db.SignalKeys.insert {
                            it[id] = "$userId-$deviceId-$keyType"
                            it[com.maodouchat.server.db.SignalKeys.userId] = userId
                            it[com.maodouchat.server.db.SignalKeys.deviceId] = deviceId
                            it[com.maodouchat.server.db.SignalKeys.keyType] = keyType
                            it[keyData] = "x"
                            it[createdAt] = 1L
                        }
                    }
            }
            // 两个账号的登录会话都绑到各自设备 1，这样 u2 的 token 才能拉自己的收件箱。
            com.maodouchat.server.db.AuthSessions.update(
                { com.maodouchat.server.db.AuthSessions.userId eq "u1" },
            ) {
                it[com.maodouchat.server.db.AuthSessions.signalDeviceId] = 1
            }
            com.maodouchat.server.db.AuthSessions.update(
                { com.maodouchat.server.db.AuthSessions.userId eq "u2" },
            ) {
                it[com.maodouchat.server.db.AuthSessions.signalDeviceId] = 1
            }
        }

        val memberRevision = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.Chats.selectAll()
                .where { com.maodouchat.server.db.Chats.id eq chatId }
                .single()[com.maodouchat.server.db.Chats.memberRevision]
        }

        // A = u1/d1 发送。收件人只有 d1 的 u2 与同账号的另一台设备 u1/d2。
        val sent = client.post("/api/v2/messages") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"id":"two-device-1","conversationId":"$chatId","kind":"DATA","clientTimestamp":1000,
                 "groupRevision":$memberRevision,"attachmentIds":[],
                 "envelopes":[
                   {"recipientUserId":"u1","recipientDeviceId":2,"ciphertextType":"TEXT","ciphertext":"$cipherForU1Device2"},
                   {"recipientUserId":"u2","recipientDeviceId":1,"ciphertextType":"TEXT","ciphertext":"$cipherForU2Device1"},
                   {"recipientUserId":"u2","recipientDeviceId":2,"ciphertextType":"TEXT","ciphertext":"$cipherForU2Device2"}
                 ]}
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, sent.status, sent.bodyAsText())

        // B 是**离线**的：本用例从未为 u2 建立任何 WebSocket 连接。把这一点变成可核对的事实，
        // 而不是叙述。
        assertTrue(
            com.maodouchat.server.plugins.ConnectionRegistry.onlineUsers["u2"].isNullOrEmpty(),
            "本用例刻意让 u2 保持离线；若这里有活跃 session，就不算离线投递",
        )

        val inbox = client.get("/api/v2/inbox?limit=100") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, inbox.status, inbox.bodyAsText())
        val inboxJson = Json.parseToJsonElement(inbox.bodyAsText()).jsonObject
        val envelopes = inboxJson["envelopes"]!!.jsonArray
        assertEquals(
            1,
            envelopes.size,
            "离线设备应当恰好取到发给**它这一台**的那一份；取到 2 份说明是按账号而不是按设备过滤：${inbox.bodyAsText()}",
        )
        val only = envelopes[0].jsonObject
        assertEquals(
            cipherForU2Device1,
            only["ciphertext"]!!.jsonPrimitive.content,
            "投递穿过服务端后，密文必须逐字节不变",
        )
        val envelopeId = only["envelopeId"]!!.jsonPrimitive.content

        val ack = client.post("/api/v2/inbox/ack") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"envelopeIds":["$envelopeId"]}""")
        }
        assertEquals(HttpStatusCode.OK, ack.status, ack.bodyAsText())
        assertEquals(1, Json.parseToJsonElement(ack.bodyAsText()).jsonObject["acknowledged"]!!.jsonPrimitive.content.toInt())

        val afterAck = client.get("/api/v2/inbox?limit=100") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(
            0,
            Json.parseToJsonElement(afterAck.bodyAsText()).jsonObject["envelopes"]!!.jsonArray.size,
            "确认之后自己的收件箱应当清空：${afterAck.bodyAsText()}",
        )

        // 关键：ACK 必须是**按设备**的。同一会话里 u1/d2 那一份不能因为别人确认而消失。
        val repository = com.maodouchat.server.messaging.v2.MessagingV2Repository()
        val sibling = repository.pending("u1", 2, 100)
        assertEquals(1, sibling.envelopes.size, "另一个账号设备的副本被误删了——ACK 变成了全局删除")
        assertEquals(cipherForU1Device2, sibling.envelopes.single().ciphertext)

        // 同一账号的另一台设备也必须保留自己那份：这才钉住「ACK 按设备」而不是「按账号」。
        val sameAccountSibling = repository.pending("u2", 2, 100)
        assertEquals(1, sameAccountSibling.envelopes.size, "同账号另一台设备的副本被误删了——ACK 是按账号生效了")
        assertEquals(cipherForU2Device2, sameAccountSibling.envelopes.single().ciphertext)

        // ACK 的**授权作用域**：拿着别人的 envelopeId 不能替别人确认。
        // envelopeId 在客户端之间并非秘密（密文信封本来就带 id），所以「知道 id 就能删」
        // 是一条真实的越权路径——那会让攻击者静默清掉别人尚未解密的消息。
        val foreignEnvelopeId = sibling.envelopes.single().envelopeId
        val foreignAck = client.post("/api/v2/inbox/ack") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"envelopeIds":["$foreignEnvelopeId"]}""")
        }
        assertEquals(HttpStatusCode.OK, foreignAck.status, foreignAck.bodyAsText())
        assertEquals(
            0,
            Json.parseToJsonElement(foreignAck.bodyAsText()).jsonObject["acknowledged"]!!.jsonPrimitive.content.toInt(),
            "u2 的会话不该能确认属于 u1/d2 的信封",
        )
        assertEquals(
            1,
            repository.pending("u1", 2, 100).envelopes.size,
            "越权 ACK 竟然真的删掉了别人的信封",
        )
    }

    /**
     * G38：人类消息经 **HTTP 路由** `/api/v2/messages` 发出后，用**枚举全 schema**的方式扫它的明文标记——
     * 必须只出现在信封密文列，并且**检索面取不回**。
     *
     * 之所以要有这一条：仓库里现有的最强证据（`ServerPlaintextSweepTest`）走的是 repository，
     * 而**路由层**才是真正的攻击面。
     */
    @Test
    fun `a human v2 payload sent over http lives in exactly one column and is not searchable`() = testApplication {
        // alex(u1) 需要在管理检索面里是管理员，才能验证「不得检索」。
        System.setProperty("MASTER_ADMINS", "u1")
        application {
            moduleUnderTest(seedDemoUsers = true)
            configureMessagingV2Routing(
                com.maodouchat.server.messaging.v2.MessagingV2Repository(),
            )
        }

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
            setBody("""{"participantIds":["u2"],"isGroup":true,"groupName":"HTTP Sweep"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val chatId = (Json.parseToJsonElement(created.bodyAsText()) as JsonObject)["id"]!!.jsonPrimitive.content
        acceptAllGroupInvites(aliceToken)

        org.jetbrains.exposed.sql.transactions.transaction {
            listOf("u1" to 1, "u1" to 2, "u2" to 1).forEach { (userId, deviceId) ->
                com.maodouchat.server.db.SignalDevices.insert {
                    it[com.maodouchat.server.db.SignalDevices.userId] = userId
                    it[com.maodouchat.server.db.SignalDevices.deviceId] = deviceId
                    it[deviceName] = "$userId-$deviceId"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = deviceId
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        com.maodouchat.server.db.SignalKeys.insert {
                            it[id] = "$userId-$deviceId-$keyType"
                            it[com.maodouchat.server.db.SignalKeys.userId] = userId
                            it[com.maodouchat.server.db.SignalKeys.deviceId] = deviceId
                            it[com.maodouchat.server.db.SignalKeys.keyType] = keyType
                            it[keyData] = "x"
                            it[createdAt] = 1L
                        }
                    }
            }
            com.maodouchat.server.db.AuthSessions.update(
                { com.maodouchat.server.db.AuthSessions.userId eq "u1" },
            ) {
                it[com.maodouchat.server.db.AuthSessions.signalDeviceId] = 1
            }
        }

        val memberRevision = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.Chats.selectAll()
                .where { com.maodouchat.server.db.Chats.id eq chatId }
                .single()[com.maodouchat.server.db.Chats.memberRevision]
        }

        // 载荷是**不透明**的：服务端只需把它当字节搬，不需要、也不允许理解它。
        // 每个收件设备用**各自不同**的标记：这样「每个标记恰好出现 1 次」才是精确断言
        // （同一个密文发给两台设备会落两行信封，用同一个标记就只能断言「2 次」，说明不了位置）。
        val markerForU1Device2 = "HTTP-HUMAN-OPAQUE-U1D2-" + java.util.UUID.randomUUID()
        val markerForU2Device1 = "HTTP-HUMAN-OPAQUE-U2D1-" + java.util.UUID.randomUUID()
        val sent = client.post("/api/v2/messages") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"http_sweep_1","conversationId":"$chatId","kind":"DATA","clientTimestamp":1000,"groupRevision":$memberRevision,
                 "attachmentIds":[],
                 "envelopes":[
                   {"recipientUserId":"u1","recipientDeviceId":2,"ciphertextType":"TEXT","ciphertext":"$markerForU1Device2"},
                   {"recipientUserId":"u2","recipientDeviceId":1,"ciphertextType":"TEXT","ciphertext":"$markerForU2Device1"}
                 ]}""",
            )
        }
        // 控制组：HTTP 发送必须真的成功，否则后面的负结论毫无意义。
        assertTrue(sent.status.value in 200..299, "HTTP 发送必须成功：${sent.status} ${sent.bodyAsText()}")

        // ① 枚举**全部用户表**的所有列，对**每个**标记断言「恰好出现在它自己的信封行里」。
        listOf(markerForU1Device2, markerForU2Device1).forEach { marker ->
            val hits = sweepAllTables(marker)
            assertEquals(
                1,
                hits.size,
                "人类载荷只允许出现在它自己的信封列（marker=$marker）；这些位置出现了额外副本：$hits",
            )
            assertTrue(
                hits.single().endsWith("MESSAGING_V2_ENVELOPES.CIPHERTEXT"),
                "载荷应当落在信封密文列（marker=$marker），实际是：$hits",
            )
        }

        // ② 正对照：服务端**确实**保存明文的地方（bot/service 消息正文）必须被**同一套**扫描扫到，
        //    否则「没扫到」可能只是扫描本身坏了，而不是服务端真的没存。
        val serviceMarker = "SERVICE-PLAINTEXT-" + java.util.UUID.randomUUID()
        org.jetbrains.exposed.sql.transactions.transaction {
            // service 消息的插入条件（读 `ServiceMessageRepository.insert` 得到）：
            // bot 存在且 enabled、其 owner 可投递、会话非密聊、且 **bot 是该会话的参与者**。
            com.maodouchat.server.db.Users.insert {
                it[id] = "bot_sweep"
                it[name] = "sweep"
                it[email] = "bot-sweep@test.local"
                it[passwordHash] = "x"
                it[isOnline] = false
            }
            com.maodouchat.server.db.BotApps.insert {
                it[id] = "bot_sweep"
                it[ownerUserId] = "u1"
                it[name] = "sweep"
                it[username] = "sweep_bot"
                it[tokenHash] = "x"
                it[tokenPrefix] = "x"
                it[enabled] = true
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
            com.maodouchat.server.db.ChatParticipants.insert {
                // 注意：必须写全限定列名——本作用域里有同名局部变量 `chatId`，
                // 直接 `it[chatId]` 会被解析成那个 String 变量而不是列（实测编译报错）。
                it[com.maodouchat.server.db.ChatParticipants.chatId] = chatId
                it[com.maodouchat.server.db.ChatParticipants.userId] = "bot_sweep"
                it[com.maodouchat.server.db.ChatParticipants.role] = "MEMBER"
                it[com.maodouchat.server.db.ChatParticipants.joinedAt] = 1L
            }
        }
        val inserted = com.maodouchat.server.repository.ServiceMessageRepository().insert(
            id = "svc_sweep_1",
            chatId = chatId,
            botUserId = "bot_sweep",
            content = serviceMarker,
            timestamp = 1L,
        )
        assertTrue(inserted, "正对照的 service 消息必须插入成功，否则这一条对照无效")
        val controlHits = sweepAllTables(serviceMarker)
        assertTrue(
            controlHits.isNotEmpty(),
            "扫描必须能找到服务端**确实**保存的明文（bot/service 正文），否则扫描本身不可信",
        )

        // ③ 不得检索：管理面的消息检索**只允许元数据**，不得回出人类载荷。
        // 管理面走的是**独立的管理员会话**（实测：直接用用户 token 会 401「管理员会话无效或已过期」）。
        val adminSession = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(
            HttpStatusCode.OK,
            adminSession.status,
            "管理员会话必须建立成功：${adminSession.status} ${adminSession.bodyAsText()}",
        )
        val adminToken = extractToken(adminSession.bodyAsText())
        val search = client.get("/api/admin/messages/search?chatId=$chatId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertTrue(
            search.status.value in 200..299,
            "管理检索接口应当可用：${search.status} ${search.bodyAsText()}",
        )
        // 控制组：检索**必须真的返回了这条消息的元数据**——否则「没回出明文」可能只是接口空转。
        assertTrue(
            search.bodyAsText().contains("http_sweep_1"),
            "检索应当返回该消息的元数据（否则下面的负结论是空转）：${search.bodyAsText()}",
        )
        // **两个**信封的标记都要断言：只查其中一个会漏掉「回出了另一个信封」的泄漏
        // （这正是 P3 探针第一次没能变红的原因——它回出的其实是另一个信封的载荷）。
        listOf(markerForU1Device2, markerForU2Device1).forEach { leaked ->
            assertFalse(
                search.bodyAsText().contains(leaked),
                "管理检索面不得回出人类消息明文（marker=$leaked）：${search.bodyAsText()}",
            )
        }
    }

    /**
     * 枚举**全部用户表的所有列**扫一个标记。
     *
     * 刻意**不**维护人工表清单：新增的表或列会被自动纳入——「不保存明文」是全称断言，
     * 用人工清单扫就等于把结论建立在一份会过期的名单上。
     */
    private fun sweepAllTables(needle: String): List<String> =
        org.jetbrains.exposed.sql.transactions.transaction {
            val hits = mutableListOf<String>()
            // 关键：**复用应用自己的连接**（Exposed 当前事务的底层 JDBC 连接），不另开连接。
            // 实测教训：另开 DriverManager 连接去打 H2 内存库会 28000 认证失败——即便
            // url/user/password 全都取自 ServerConfig 也一样；复用同一连接才是可靠做法。
            @Suppress("UNCHECKED_CAST")
            val jdbc = (
                org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
                    as org.jetbrains.exposed.sql.statements.api.ExposedConnection<java.sql.Connection>
                ).connection

            // 扫描面 = **数据库里的全部用户表**（用 JDBC 元数据枚举，不维护人工表清单：
            // 以后新增的表/列会自动进入扫描面，而不是把结论建立在一份会过期的名单上）。
            val tables = mutableListOf<String>()
            jdbc.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    val schema = rs.getString("TABLE_SCHEM")
                    // 跳过 H2 自己的元数据 schema：那里出现的标记不来自我们的数据。
                    if (schema != null && schema.equals("INFORMATION_SCHEMA", ignoreCase = true)) continue
                    val table = rs.getString("TABLE_NAME")
                    tables += if (schema != null) "$schema.$table" else table
                }
            }

            tables.forEach { table ->
                jdbc.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $table").use { rs ->
                        val columnCount = rs.metaData.columnCount
                        while (rs.next()) {
                            for (index in 1..columnCount) {
                                val value = rs.getString(index) ?: continue
                                if (value.contains(needle)) {
                                    hits += "$table.${rs.metaData.getColumnName(index)}"
                                }
                            }
                        }
                    }
                }
            }
            hits
        }
}
