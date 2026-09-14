package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserTagRepository
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 第二步特征测试：`AdminEnhanceRouting.kt`（599 行 / 12 处 `transaction {`）的 5 个端点。
 *
 * 与 `AdminExportsRouteTest` 同一套路：先把对外契约钉死，再动重构。这个文件里的 SQL
 * 分布在 4 个 handler、`buildAuditExportCsv`（5 个 scope 分支）、`DeviceEventConsistencyGuard`
 * 和 `purgeAdminOperationalData` 里，搬动面比导出那次更散——没有安全网就是拿管理后台赌运气。
 *
 * 注意这里刻意**不复用**生产常量：CSV 表头与 JSON 字段名都是逐字写死的，
 * 生产改了而这里没改就必须红。
 */
class AdminEnhanceRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:admin-enhance-${kotlin.random.Random.nextInt(1_000_000)}-" +
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
        configureRouting(userRepo = userRepo, postRepo = PostRepository(), aiGateway = EnhanceFakeAiGateway())
        // 注意：enhance 路由**不在** configureRouting 里，生产由 Application.kt 单独装配。
        configureAdminEnhanceRouting(
            announcementRepo = AnnouncementRepository(),
            userTagRepo = UserTagRepository(),
            rateLimitStatsRepo = RateLimitStatsRepository(),
        )
    }

    private fun token(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["token"]!!.jsonPrimitive.content

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
    fun `enhance endpoints reject anonymous callers`() = testApplication {
        application { moduleUnderTest() }
        listOf(
            "/api/admin/audit/time-range-export?scope=ADMIN_AUDIT&fromMs=0&toMs=1",
            "/api/admin/rate-limit/dashboard",
            "/api/admin/device-consistency/summary",
            "/api/admin/device-consistency/events",
        ).forEach { path ->
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, "匿名访问 $path 必须被拒")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/admin/rate-limit/sample").status,
        )
    }

    @Test
    fun `audit time range export validates its parameters`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()
        val base = "/api/admin/audit/time-range-export"

        suspend fun statusOf(query: String): HttpStatusCode =
            client.get("$base?$query") { header(HttpHeaders.Authorization, "Bearer $admin") }.status

        assertEquals(HttpStatusCode.BadRequest, statusOf("fromMs=0&toMs=1"), "缺 scope 必须 400")
        assertEquals(HttpStatusCode.BadRequest, statusOf("scope=NOPE&fromMs=0&toMs=1"), "非法 scope 必须 400")
        assertEquals(HttpStatusCode.BadRequest, statusOf("scope=ADMIN_AUDIT&toMs=1"), "缺 fromMs 必须 400")
        assertEquals(HttpStatusCode.BadRequest, statusOf("scope=ADMIN_AUDIT&fromMs=0"), "缺 toMs 必须 400")
        assertEquals(
            HttpStatusCode.BadRequest,
            statusOf("scope=ADMIN_AUDIT&fromMs=100&toMs=100"),
            "fromMs >= toMs 必须 400",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            statusOf("scope=ADMIN_AUDIT&fromMs=0&toMs=${91L * 24 * 3_600_000L}"),
            "超过 90 天必须 400",
        )
    }

    @Test
    fun `audit time range export keeps per-scope headers and bom`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        // 窗口必须落在 90 天上限内：用最近 1 小时。
        val toMs = System.currentTimeMillis()
        val fromMs = toMs - 3_600_000L
        AUDIT_SCOPES.forEach { (scope, expectedHeader) ->
            val response = client.get(
                "/api/admin/audit/time-range-export?scope=$scope&fromMs=$fromMs&toMs=$toMs",
            ) { header(HttpHeaders.Authorization, "Bearer $admin") }
            assertEquals(HttpStatusCode.OK, response.status, "$scope → ${response.bodyAsText().take(200)}")

            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            assertTrue(contentType.startsWith("text/csv"), "$scope Content-Type=$contentType")

            val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
            assertTrue(
                disposition.contains("attachment") && disposition.contains("maodouchat-${scope.lowercase()}-"),
                "$scope Content-Disposition=$disposition",
            )

            val body = response.bodyAsText()
            assertTrue(body.startsWith("\uFEFF"), "$scope 丢了 UTF-8 BOM（Excel 会乱码）")
            val header = body.removePrefix("\uFEFF").lineSequence().first()
            assertEquals(expectedHeader, header, "$scope 的 CSV 表头变了")

            if (scope == "ADMIN_AUDIT") {
                // 这个 scope 必然非空：换取 admin session 本身就会写一条
                // ADMIN_SESSION_ISSUED 的 ModerationAuditLog（第一次跑时这里红过，
                // 查清是测试假设错、不是产品缺陷）。用它来证明「查询确实按时间窗取到了真行」。
                assertTrue(
                    body.contains("ADMIN_SESSION_ISSUED"),
                    "ADMIN_AUDIT 应当取到本次 admin session 的审计行",
                )
            } else {
                // 其余 4 张表在测试库里没有落在时间窗内的行 → 只允许 BOM + 表头 + 空行，
                // 出现数据行就说明查询写错了（防幽灵行）。
                assertEquals(
                    "\uFEFF$expectedHeader\r\n\r\n",
                    body,
                    "$scope 在没有数据时应当只有表头",
                )
            }
        }
    }

    @Test
    fun `rate limit dashboard keeps its json contract`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        listOf("1h", "24h", "7d").forEach { range ->
            val response = client.get("/api/admin/rate-limit/dashboard?range=$range") {
                header(HttpHeaders.Authorization, "Bearer $admin")
            }
            assertEquals(HttpStatusCode.OK, response.status, "range=$range → ${response.bodyAsText().take(200)}")
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(range, body["range"]!!.jsonPrimitive.content)
            listOf(
                "range", "points", "totalAllowed", "totalRejected",
                "peakRejectionsPerMinute", "live", "lastSnapshotAt", "retentionDays",
            ).forEach { key ->
                assertTrue(body.containsKey(key), "range=$range 缺少 $key")
            }
            listOf("allowed", "rejected", "totalBuckets", "maxBuckets", "maxPerMinute").forEach { key ->
                assertTrue(body["live"]!!.jsonObject.containsKey(key), "range=$range live 缺少 $key")
            }
        }

        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/admin/rate-limit/dashboard") { header(HttpHeaders.Authorization, "Bearer $admin") }.status,
            "range 缺省应为 24h",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/admin/rate-limit/dashboard?range=30d") {
                header(HttpHeaders.Authorization, "Bearer $admin")
            }.status,
            "非法 range 必须 400",
        )
    }

    @Test
    fun `rate limit sample and device consistency keep their contracts`() = testApplication {
        application { moduleUnderTest() }
        val admin = adminToken()

        val sample = client.post("/api/admin/rate-limit/sample") {
            header(HttpHeaders.Authorization, "Bearer $admin")
        }
        assertEquals(HttpStatusCode.OK, sample.status, sample.bodyAsText())
        assertTrue(
            json.parseToJsonElement(sample.bodyAsText()).jsonObject["ok"]!!.jsonPrimitive.content == "true",
            "rate-limit/sample 应回 {ok:true}",
        )

        val summary = client.get("/api/admin/device-consistency/summary") {
            header(HttpHeaders.Authorization, "Bearer $admin")
        }
        assertEquals(HttpStatusCode.OK, summary.status, summary.bodyAsText())
        val summaryBody = json.parseToJsonElement(summary.bodyAsText()).jsonObject
        assertTrue(summaryBody.containsKey("sequences"), "summary 缺少 sequences")
        assertTrue(summaryBody.containsKey("anomalyCount"), "summary 缺少 anomalyCount")
        assertEquals(0, summaryBody["sequences"]!!.jsonArray.size, "空库下 sequences 应为 []")
        assertEquals("0", summaryBody["anomalyCount"]!!.jsonPrimitive.content, "空库下 anomalyCount 应为 0")

        val events = client.get("/api/admin/device-consistency/events") {
            header(HttpHeaders.Authorization, "Bearer $admin")
        }
        assertEquals(HttpStatusCode.OK, events.status, events.bodyAsText())
        assertEquals(0, json.parseToJsonElement(events.bodyAsText()).jsonArray.size, "空库下 events 应为 []")
    }

    private companion object {
        /** 逐字取自 `buildAuditExportCsv` 的 `header` when 分支。 */
        val AUDIT_SCOPES: List<Pair<String, String>> = listOf(
            "ADMIN_AUDIT" to "id,actorId,targetUserId,action,detail,createdAt",
            "RISK_EVENTS" to "id,userId,source,ruleId,action,matched,referenceId,needsReview,createdAt",
            "ANNOUNCEMENTS" to "id,title,content,level,audience,tagId,startsAt,expiresAt,status,createdBy,createdAt,publishedAt,cancelledAt",
            "USER_TAGS" to "tagId,tagName,userId,source,assignedBy,createdAt",
            "RATE_LIMIT" to "bucketStartMs,allowed,rejected,totalBuckets,maxBuckets,maxPerMinute,sampledAt",
        )
    }
}

private class EnhanceFakeAiGateway : AiGateway {
    override val model: String = "test-model"
}
