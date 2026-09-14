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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 特征测试：把 `AdminExportsRouting.kt` 里 27 个 CSV 导出 + `runtime-export` + `watermark/extract`
 * 的**对外契约**先钉死，再动重构。
 *
 * 这个文件存在的理由：`AdminExportsRouting.kt` 有 1110 行、26 处 `transaction {`、零行为测试。
 * 没有安全网就直接把 SQL 搬进 repository，是拿管理后台的导出功能赌运气。所以先把
 * 「状态码 / Content-Type / Content-Disposition / CSV 表头」锁住——重构只许让内部结构变化，
 * 不许让任何一个导出的对外形状变化。
 *
 * 表头字符串是从源码里逐字取出后写死在这里的，**故意不复用生产常量**：如果生产代码改了表头，
 * 这里必须跟着红，否则这个测试就失去意义。
 */
class AdminExportsRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-exports-${kotlin.random.Random.nextInt(1_000_000)}-" +
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
        // configureRouting 是整站安装器（带 RoutingInstalledKey 幂等守卫），
        // 它会经 configureAdminRouting → configureAdminManagementRouting 装上
        // /api/admin/*（含导出）与 /api/auth/login，因此这里不要再单独装一遍管理路由。
        configureRouting(
            userRepo = userRepo,
            postRepo = PostRepository(),
            aiGateway = ExportsFakeAiGateway(),
        )
    }

    private fun token(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

    /** 用普通用户 JWT 换一个 5 分钟用途限定的 admin session token（导出路由只认它）。 */
    private suspend fun ApplicationTestBuilder.adminToken(): String {
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alex@example.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())

        val session = client.post("/api/admin/session") {
            header(HttpHeaders.Authorization, "Bearer ${token(login.bodyAsText())}")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return token(session.bodyAsText())
    }

    @Test
    fun `csv exports reject anonymous callers`() = testApplication {
        application { moduleUnderTest() }
        CSV_EXPORTS.forEach { (path, _) ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/admin$path").status,
                "匿名访问 $path 必须被拒",
            )
        }
    }

    @Test
    fun `csv exports keep their exact header and response shape`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        CSV_EXPORTS.forEach { (path, expectedHeader) ->
            val response = client.get("/api/admin$path") {
                header(HttpHeaders.Authorization, "Bearer $admin")
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path → ${response.bodyAsText().take(200)}")

            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            assertTrue(contentType.startsWith("text/csv"), "$path Content-Type=$contentType")

            val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
            assertTrue(
                disposition.startsWith("attachment;") && disposition.contains("filename="),
                "$path Content-Disposition=$disposition",
            )

            val actualHeader = response.bodyAsText().lineSequence().first()
            assertEquals(expectedHeader, actualHeader, "$path 的 CSV 表头变了")
        }
    }

    /**
     * 行级断言（目标里的「关键行数」）。
     *
     * 只锁形状是不够的：把 SQL 搬进 repository 时，如果接错表、写错过滤条件，
     * 状态码和表头依然全绿。所以这里对**有种子数据的 Users 系导出**断言真实行数与内容，
     * 对**必然为空**的导出断言「只有表头、没有幽灵行」。
     */
    @Test
    fun `exports report real rows and never invent phantom rows`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        suspend fun csv(path: String): List<String> {
            val response = client.get("/api/admin$path") {
                header(HttpHeaders.Authorization, "Bearer $admin")
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path → ${response.bodyAsText().take(200)}")
            return response.bodyAsText().trim().lines().filter { it.isNotBlank() }
        }

        // createDefaultUsers() 固定种下 u1..u13，共 13 个用户。
        listOf(
            "/users-export",
            "/online-presence-export",
            "/privacy-flags-export",
            "/identity-users-export",
            "/sessions-summary-export",
        ).forEach { path ->
            val lines = csv(path)
            assertEquals(14, lines.size, "$path 期望 1 行表头 + 13 个种子用户，实际 ${lines.size} 行")
            assertTrue(lines.any { it.startsWith("\"u1\"") }, "$path 缺少 u1 行")
        }

        val users = csv("/users-export")
        assertTrue(users.any { it.contains("\"alex@example.com\"") }, "users-export 缺少 alex 的邮箱")

        // 邮箱脱敏：alex@example.com → "ale***"
        assertTrue(
            csv("/identity-users-export").any { it.contains("\"ale***\"") },
            "identity-users-export 的邮箱脱敏列变了",
        )

        // 这些表在测试库里没有任何行：只允许出现表头，出现数据行就是查询写错了。
        listOf("/totp-users-export", "/restricted-users-export", "/blocks-export", "/friends-export")
            .forEach { path ->
                assertEquals(1, csv(path).size, "$path 应为「只有表头」，实际出现了数据行")
            }
    }

    @Test
    fun `runtime-export keeps its json contract`() = testApplication {
        application { moduleUnderTest() }
        val response = client.get("/api/admin/runtime-export") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        listOf("generatedAt", "settings", "defaults", "security").forEach { key ->
            assertTrue(body.containsKey(key), "runtime-export 缺少 $key：${response.bodyAsText().take(200)}")
        }
        val security = body["security"]!!.jsonObject
        listOf(
            "sealedSenderEnabled", "aiEnabled", "botsAllowed", "captureAlertEnabled",
            "pqxdhPreview", "minAppVersion", "maxBotsPerUser", "ipBlocklistCount", "maxMessagePerMin",
        ).forEach { key ->
            assertTrue(security.containsKey(key), "runtime-export.security 缺少 $key")
        }
    }

    @Test
    fun `watermark extract validates input instead of crashing`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        val empty = client.post("/api/admin/watermark/extract") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status, empty.bodyAsText())

        val authorized = client.post("/api/admin/watermark/extract") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody("""{"imageBase64":"not-an-image"}""")
        }
        assertTrue(
            authorized.status == HttpStatusCode.OK || authorized.status == HttpStatusCode.GatewayTimeout,
            "非图片输入应被安全处理，实际 ${authorized.status}",
        )
    }

    private companion object {
        /**
         * 逐字取自 `AdminExportsRouting.kt` 的 `appendLine("<header>")`。
         * 生产代码改表头而这里没改 → 测试必须红。
         */
        val CSV_EXPORTS: List<Pair<String, String>> = listOf(
            "/push-tokens-export" to "userId,deviceId,platform,tokenPrefix,timezoneOffsetMinutes,updatedAt",
            "/users-export" to "id,name,email,status,isOnline,isModerator,suspendedUntil,lastSeen",
            "/bots-export" to "id,name,username,ownerUserId,tokenPrefix,webhookUrl,enabled,createdAt,updatedAt",
            "/message-stats-export" to "type,count",
            "/reports-export" to "id,reporterId,targetType,targetId,reason,status,createdAt",
            "/risk-events-export" to "id,userId,source,action,matched,needsReview,createdAt",
            "/online-export" to "userId,online",
            "/sessions-summary-export" to "userId,name,isOnline,activeRefreshSessions,lastSeen",
            "/polls-export" to "id,chatId,creatorId,question,multi,anonymous,closed,voteRows,createdAt,closesAt",
            "/moderation-audit-export" to "id,actorId,userId,action,detail,createdAt",
            "/bot-command-stats-export" to "id,botId,chatId,userId,command,createdAt",
            "/friends-export" to "userLowId,userHighId,createdAt",
            "/reports-meta-export" to "id,reporterId,targetType,targetId,chatId,reason,status,actionTaken,createdAt",
            "/blocks-export" to "blockerId,blockedId",
            "/chat-settings-export" to "userId,chatId,pinnedAt,notificationsMuted,archived,markedUnread,updatedAt",
            "/disappearing-chats-export" to "chatId,isGroup,groupName,disappearingSeconds",
            "/muted-chats-export" to "userId,chatId,notificationsMuted,updatedAt",
            "/ai-feature-flags-export" to "key,value",
            "/online-presence-export" to "userId,isOnline,lastSeen,showOnline",
            "/privacy-flags-export" to "userId,showOnline,showStatus,searchable",
            "/identity-users-export" to "userId,searchable,showOnline,totpEnabled,emailHint",
            "/totp-users-export" to "userId,totpEnabled,emailHint",
            "/group-invites-export" to "chatId,tokenPrefix,expiresAt,maxUses,useCount",
            "/restricted-users-export" to "userId,messageRestrictedUntil,postRestrictedUntil,suspendedUntil",
            "/poll-votes-export" to "pollId,userId,optionIndex,votedAt",
            "/pinned-messages-export" to "chatId,messageId,pinnedBy,pinnedAt",
            "/chats-export" to "id,type,title,memberCount,memberRevision,disappearingSeconds",
        )
    }
}

private class ExportsFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}
