package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureStatusPages
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2/G15 特征测试：先把 `AdminManagementRouting.kt` 的**对外契约**钉死，再动重构。
 *
 * 该文件有 525 行、**6 处 `transaction {`**、且没有任何行为测试。没有安全网就把 SQL
 * 搬进 service/repository，是拿管理后台赌运气。所以这里锁住：
 * 状态码、响应字段、权限边界、参数校验，以及**三处审计写入（`ModerationAuditLog`）**——
 * 恰好就是本轮要搬走的那三处事务；它们一旦搬错，这里必须红。
 */
class AdminManagementRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-mgmt-${kotlin.random.Random.nextInt(1_000_000)}-" +
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
        configureRouting(userRepo = userRepo, postRepo = PostRepository(), aiGateway = ManagementFakeAiGateway())
    }

    private fun token(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    /** alex 即 u1，在 MASTER_ADMINS 里；alice 即 u2，不是管理员。 */
    private suspend fun ApplicationTestBuilder.login(email: String): String {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return token(response.bodyAsText())
    }

    /**
     * 管理路由只认**管理员会话 token**：普通登录 token 换发一次（与 `AdminExportsRouteTest` 同流程）。
     * 实测：直接用普通登录 token 打这些路由会得到 401 `管理员会话无效或已过期`。
     */
    private suspend fun ApplicationTestBuilder.adminToken(): String {
        val plain = login("alex@example.com")
        val session = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer $plain")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return token(session.bodyAsText())
    }

    /** 普通用户（非 MASTER_ADMINS）拿不到管理员会话。 */
    private suspend fun ApplicationTestBuilder.nonAdminToken(): String = login("alice@example.com")

    private fun seedFixtures() {
        val now = System.currentTimeMillis()
        transaction {
            SignalDevices.insert {
                it[SignalDevices.userId] = "u2"
                it[deviceId] = 7
                it[deviceName] = "alice phone"
                it[status] = "CONFIRMED"
                it[confirmedAt] = now
                it[confirmedByDeviceId] = 7
                it[createdAt] = now
                it[lastSeenAt] = now
            }
            PushTokens.insert {
                it[PushTokens.userId] = "u2"
                it[deviceId] = "7"
                it[platform] = "android"
                it[token] = "push-token-7"
                it[updatedAt] = now
            }
            // 一个普通会话 + 一个密聊会话：搜索必须只看到前者。
            Chats.insert {
                it[id] = "c_normal"
                it[isGroup] = false
                it[chatType] = "DIRECT"
            }
            Chats.insert {
                it[id] = "c_secret"
                it[isGroup] = false
                it[chatType] = "SECRET"
            }
            listOf("msg_normal" to "c_normal", "msg_secret" to "c_secret").forEach { (msgId, chatId) ->
                MessagingV2Messages.insert {
                    it[id] = msgId
                    it[conversationId] = chatId
                    it[senderUserId] = "u2"
                    it[senderDeviceId] = 7
                    it[kind] = "DATA"
                    it[recordClass] = "MESSAGE"
                    it[clientTimestamp] = now
                    it[serverTimestamp] = now
                    it[requestDigest] = "d".repeat(64)
                }
            }
            AuthSessions.insert {
                it[id] = "sess-u2"
                it[userId] = "u2"
                it[createdAt] = now
                it[updatedAt] = now
            }
            RefreshTokens.insert {
                it[tokenHash] = "abcdef1234567890abcdef1234567890"
                it[userId] = "u2"
                it[sessionId] = "sess-u2"
                it[createdAt] = now
                it[expiresAt] = now + 3_600_000L
            }
        }
    }

    private fun auditRows(action: String): List<Pair<String?, String?>> = transaction {
        ModerationAuditLog.selectAll()
            .where { ModerationAuditLog.action eq action }
            .map { it[ModerationAuditLog.actorId] to it[ModerationAuditLog.userId] }
    }

    @Test
    fun `anonymous callers are rejected`() = testApplication {
        application { moduleUnderTest() }
        listOf(
            "/api/admin/users/u2/sessions",
            "/api/admin/messages/search?q=msg",
        ).forEach { path ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(path).status,
                "匿名访问 $path 必须被拒",
            )
        }
        listOf(
            "/api/admin/users/u2/sessions/revoke",
            "/api/admin/broadcast",
            "/api/admin/users/u2/force-logout",
        ).forEach { path ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.status,
                "匿名访问 $path 必须被拒",
            )
        }
    }

    @Test
    fun `non-admin callers are forbidden`() = testApplication {
        application { moduleUnderTest() }
        val alice = nonAdminToken()

        // 实测：非管理员**拿不到管理员会话**，所以在这一层就被拒为 401，
        // 走不到 handler 里那句 `isAdminUser()` 的 403。403 分支只有在
        // 「先发了 admin session、之后 MASTER_ADMINS 被改掉并重启」时才可达。
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/users/u2/sessions") {
                header(HttpHeaders.Authorization, "Bearer $alice")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/messages/search?q=msg") {
                header(HttpHeaders.Authorization, "Bearer $alice")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/admin/broadcast") {
                header(HttpHeaders.Authorization, "Bearer $alice")
                contentType(ContentType.Application.Json)
                setBody("""{"text":"hi"}""")
            }.status,
        )
        // 而非管理员连换发管理员会话这一步都过不去。
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/admin/session") {
                header(HttpHeaders.Authorization, "Bearer $alice")
                contentType(ContentType.Application.Json)
                setBody("""{"password":"password123"}""")
            }.status,
            "非 MASTER_ADMINS 账号不得换发管理员会话",
        )
    }

    @Test
    fun `session overview exposes sessions devices and push tokens`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()
        seedFixtures()

        val response = client.get("/api/admin/users/u2/sessions") {
            header(HttpHeaders.Authorization, "Bearer $admin")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("u2", body["userId"]!!.jsonPrimitive.content)
        val devices = body["signalDevices"]!!.jsonArray
        assertEquals(1, devices.size, "应当报告该用户的 signal 设备：$body")
        assertEquals("alice phone", devices[0].jsonObject["deviceName"]!!.jsonPrimitive.content)
        assertEquals("CONFIRMED", devices[0].jsonObject["status"]!!.jsonPrimitive.content)
        val push = body["pushTokens"]!!.jsonArray
        assertEquals(1, push.size, "应当报告 push token：$body")
        assertEquals("android", push[0].jsonObject["platform"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("refreshSessions"), "响应形状必须保留 refreshSessions：$body")
        assertTrue(body.containsKey("activeRefreshCount"), "响应形状必须保留 activeRefreshCount：$body")
    }

    @Test
    fun `session revoke validates its inputs`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        suspend fun revoke(body: String, user: String = "u2") = client.post("/api/admin/users/$user/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.BadRequest, revoke("{}").status, "既没有 prefix 也没有 all=true")
        assertEquals(
            HttpStatusCode.BadRequest,
            revoke("""{"tokenHashPrefix":"abcdef123456","all":true}""").status,
            "两者互斥",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            revoke("""{"tokenHashPrefix":"xyz"}""").status,
            "prefix 不是 12-64 位十六进制",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            revoke("""{"all":"true"}""").status,
            "all 必须是严格的 JSON boolean",
        )
        assertEquals(
            HttpStatusCode.NotFound,
            revoke("""{"all":true}""", user = "u_does_not_exist").status,
            "未知用户",
        )
    }

    @Test
    fun `session revoke by prefix revokes the session and writes an audit row`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()
        seedFixtures()

        // `revoked` 的实际语义（读实现得到，不是猜的）：`sessionChanged + tokenChanged`——
        // **会话数与被一并吊销的 token 数相加**。命中一个 session 时，该 session 会被置撤销，
        // 它名下的活跃 refresh token 全部置撤销，两笔各算 1。这里按这个真实语义算期望值。
        val sessionsBefore = transaction {
            AuthSessions.selectAll()
                .where {
                    (AuthSessions.id eq "sess-u2") and
                        (AuthSessions.userId eq "u2") and
                        AuthSessions.revokedAt.isNull()
                }
                .count()
        }
        val tokensBefore = transaction {
            RefreshTokens.selectAll()
                .where {
                    (RefreshTokens.userId eq "u2") and
                        (RefreshTokens.sessionId eq "sess-u2") and
                        RefreshTokens.revokedAt.isNull()
                }
                .count()
        }

        val response = client.post("/api/admin/users/u2/sessions/revoke") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody("""{"tokenHashPrefix":"abcdef123456"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals(
            (sessionsBefore + tokensBefore).toInt(),
            body["revoked"]!!.jsonPrimitive.content.toInt(),
            "revoked 应当等于「撤销的会话数 + 一并吊销的 token 数」：$body",
        )
        assertEquals("u2", body["userId"]!!.jsonPrimitive.content)

        assertEquals(
            listOf("u1" to "u2"),
            auditRows("ADMIN_SESSION_REVOKE"),
            "吊销必须留下审计行（actor→target）",
        )
    }

    @Test
    fun `message search requires a filter and never returns content`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()
        seedFixtures()

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/admin/messages/search") {
                header(HttpHeaders.Authorization, "Bearer $admin")
            }.status,
            "没有任何过滤条件时必须拒绝",
        )

        val response = client.get("/api/admin/messages/search?q=msg") {
            header(HttpHeaders.Authorization, "Bearer $admin")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray.map { it.jsonObject }
        val ids = items.map { it["id"]!!.jsonPrimitive.content }
        assertTrue("msg_normal" in ids, "普通会话的元数据应当可搜到：$ids")
        assertFalse("msg_secret" in ids, "**密聊会话的消息绝不能出现在管理搜索里**：$ids")

        items.forEach { item ->
            assertEquals("", item["contentPreview"]!!.jsonPrimitive.content, "管理搜索只给元数据，不给内容")
            assertEquals("DURABLE", item["status"]!!.jsonPrimitive.content)
            assertTrue(item["sealedSender"]!!.jsonPrimitive.content.toBoolean(), "sealedSender 必须为 true")
        }
    }

    @Test
    fun `broadcast requires text and writes an audit row`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/admin/broadcast") {
                header(HttpHeaders.Authorization, "Bearer $admin")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"t"}""")
            }.status,
            "缺少 text 必须拒绝",
        )

        val ok = client.post("/api/admin/broadcast") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"t","text":"maintenance soon"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val body = json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("onlineTargets") && body.containsKey("delivered"), "响应形状：$body")

        assertEquals(listOf("u1" to null), auditRows("ADMIN_BROADCAST"), "广播必须留下审计行")
    }

    @Test
    fun `force logout reports unknown users and writes an audit row`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/api/admin/users/u_does_not_exist/force-logout") {
                header(HttpHeaders.Authorization, "Bearer $admin")
            }.status,
            "未知用户必须 404",
        )

        val ok = client.post("/api/admin/users/u2/force-logout") {
            header(HttpHeaders.Authorization, "Bearer $admin")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val body = json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals("u2", body["userId"]!!.jsonPrimitive.content)

        assertEquals(listOf("u1" to "u2"), auditRows("ADMIN_FORCE_LOGOUT"), "强制下线必须留下审计行")
    }

    private class ManagementFakeAiGateway : AiGateway {
        override val model: String = "test-model"
    }
}
