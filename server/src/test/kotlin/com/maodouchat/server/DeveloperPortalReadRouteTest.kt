package com.maodouchat.server

import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G57 安全网：开发者门户四个**只读**端点的行为契约。
 *
 * 这四个端点的查询目前写在 `plugins/DeveloperRouting.kt` 的裸 `transaction {` 里
 * （dashboard 聚合 / analytics 按天聚合 / logs 分页 / health 探针），下沉前先把行为钉住：
 * 1. **数字必须与原始表一致**（dashboard 的 totalCommands / 24h 命令数 / 去重用户数）；
 * 2. **logs 的 total 是「过滤后的总行数」**，不是本页条数（否则客户端翻页判断失效）；
 * 3. **bot token / dev_session 只能看自己的 bot**（越权 403）；
 * 4. analytics 的 dailyStats 必须覆盖请求的天数、且按天归集正确。
 */
class DeveloperPortalReadRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:dev-portal-${AtomicInteger().incrementAndGet()}-${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("DEVELOPER_USER_IDS", "u1")
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
            object : com.maodouchat.server.service.AiGateway {
                override val model: String = "test-model"
            },
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter,
        )
        configureDeveloperRouting()
    }

    private suspend fun io.ktor.client.HttpClient.devLogin(email: String): String {
        val response = post("/api/developer-account/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (json.parseToJsonElement(response.bodyAsText()) as JsonObject)["token"]!!.jsonPrimitive.content
    }

    private suspend fun io.ktor.client.HttpClient.createBot(devToken: String, name: String): String {
        val response = post("/api/developer-account/bots") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","username":"$name","description":"g57 probe"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** 直接往 BotCommandLogs 里种命令日志（绕过机器人协议，只测读路径聚合）。 */
    private fun seedCommandLogs(botId: String, entries: List<Triple<String, String?, Long>>) {
        transaction {
            entries.forEach { (command, userId, createdAt) ->
                com.maodouchat.server.db.BotCommandLogs.insert {
                    it[id] = java.util.UUID.randomUUID().toString()
                    it[com.maodouchat.server.db.BotCommandLogs.botId] = botId
                    it[com.maodouchat.server.db.BotCommandLogs.command] = command
                    it[com.maodouchat.server.db.BotCommandLogs.userId] = userId
                    it[com.maodouchat.server.db.BotCommandLogs.createdAt] = createdAt
                }
            }
        }
    }

    private fun rawLogCount(botId: String): Long = transaction {
        com.maodouchat.server.db.BotCommandLogs.selectAll()
            .where { com.maodouchat.server.db.BotCommandLogs.botId eq botId }
            .count()
    }

    @Test
    fun `dashboard counts match the raw command log table`() = testApplication {
        application { moduleUnderTest() }
        val devToken = client.devLogin("alex@example.com")
        val botId = client.createBot(devToken, "g57dash")

        val now = System.currentTimeMillis()
        // 5 条命令：3 条 /start（其中 2 条在 24h 内）、1 条 /help（24h 内）、1 条 /old（7 天前）
        seedCommandLogs(
            botId,
            listOf(
                Triple("/start", "user-a", now - 3_600_000L),
                Triple("/start", "user-b", now - 7_200_000L),
                Triple("/start", "user-a", now - 8 * 86_400_000L),
                Triple("/help", "user-c", now - 1_800_000L),
                Triple("/old", "user-d", now - 9 * 86_400_000L),
            ),
        )
        assertEquals(5L, rawLogCount(botId), "正对照：种子日志必须真的在库里")

        val response = client.get("/api/developer/dashboard?bot_id=$botId") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(5L, body["totalCommands"]!!.jsonPrimitive.content.toLong(), "totalCommands 必须等于全表行数")
        assertEquals(3L, body["commandsLast24h"]!!.jsonPrimitive.content.toLong(), "24h 窗口只应算 3 条")
        assertEquals(3L, body["uniqueUsersLast24h"]!!.jsonPrimitive.content.toLong(), "24h 去重用户应为 a/b/c 三人")
        val topCommands = body["topCommands"]!!.jsonArray
        assertEquals("/start", topCommands.first().jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(3L, topCommands.first().jsonObject["count"]!!.jsonPrimitive.content.toLong(), "topCommands 计数应按全表归集")
        assertEquals(botId, body["botId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `logs total is the filtered row count not the page size`() = testApplication {
        application { moduleUnderTest() }
        val devToken = client.devLogin("alex@example.com")
        val botId = client.createBot(devToken, "g57logs")

        val now = System.currentTimeMillis()
        seedCommandLogs(
            botId,
            listOf(
                Triple("/start", "user-a", now - 1_000L),
                Triple("/start", "user-b", now - 2_000L),
                Triple("/help", "user-c", now - 3_000L),
                Triple("/help", "user-d", now - 4_000L),
                Triple("/old", "user-e", now - 30 * 86_400_000L),
            ),
        )
        assertEquals(5L, rawLogCount(botId), "正对照：种子日志必须真的在库里")

        // 第一页只取 2 条，但 total 必须是全量（未过滤时 5）
        val page1 = client.get("/api/developer/bots/$botId/logs?limit=2&offset=0") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertEquals(HttpStatusCode.OK, page1.status, page1.bodyAsText())
        val page1Body = json.parseToJsonElement(page1.bodyAsText()).jsonObject
        assertEquals(2, page1Body["logs"]!!.jsonArray.size, "limit=2 就应只回 2 条")
        assertEquals(5L, page1Body["total"]!!.jsonPrimitive.content.toLong(), "total 必须是过滤后总行数，不是本页条数")

        // 第二页不重叠
        val page2 = client.get("/api/developer/bots/$botId/logs?limit=2&offset=2") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        val page2Ids = json.parseToJsonElement(page2.bodyAsText()).jsonObject["logs"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val page1Ids = page1Body["logs"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(page1Ids.intersect(page2Ids.toSet()).isEmpty(), "分页重叠：$page1Ids vs $page2Ids")

        // 按 command 过滤后 total 必须变成 2（不是 5，也不是本页条数）
        val filtered = client.get("/api/developer/bots/$botId/logs?command=%2Fhelp&limit=50") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        val filteredBody = json.parseToJsonElement(filtered.bodyAsText()).jsonObject
        assertEquals(2L, filteredBody["total"]!!.jsonPrimitive.content.toLong(), "过滤后的 total 必须只算匹配行")
        assertEquals(2, filteredBody["logs"]!!.jsonArray.size)
        assertTrue(
            filteredBody["logs"]!!.jsonArray.all { it.jsonObject["command"]!!.jsonPrimitive.content == "/help" },
            "过滤必须真的生效",
        )
    }

    @Test
    fun `analytics covers the requested window and buckets per day`() = testApplication {
        application { moduleUnderTest() }
        val devToken = client.devLogin("alex@example.com")
        val botId = client.createBot(devToken, "g57analytics")

        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        seedCommandLogs(
            botId,
            listOf(
                Triple("/start", "user-a", now - 1_000L),
                Triple("/start", "user-b", now - 2_000L),
                Triple("/help", "user-c", now - 1 * dayMs - 1_000L),
                Triple("/old", "user-d", now - 40 * dayMs),
            ),
        )
        assertEquals(4L, rawLogCount(botId), "正对照：种子日志必须真的在库里")

        val response = client.get("/api/developer/bots/$botId/analytics?days=7") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(7, body["periodDays"]!!.jsonPrimitive.content.toInt())
        val daily = body["dailyStats"]!!.jsonArray
        assertEquals(7, daily.size, "请求 7 天就必须回 7 个桶（含空桶）")
        // 窗口内 3 条（今天 2 + 昨天 1），40 天前那条不计入
        assertEquals(3L, body["totalCommands"]!!.jsonPrimitive.content.toLong(), "窗口外日志不得计入")
        assertEquals(
            3L,
            daily.sumOf { it.jsonObject["commandCount"]!!.jsonPrimitive.content.toLong() },
            "各天命令数之和必须等于窗口内总数",
        )
        val breakdown = body["commandBreakdown"]!!.jsonArray.associate {
            it.jsonObject["command"]!!.jsonPrimitive.content to it.jsonObject["count"]!!.jsonPrimitive.content.toLong()
        }
        assertEquals(2L, breakdown["/start"], "命令归集必须按全窗口统计")
        assertEquals(1L, breakdown["/help"])
        assertFalse(breakdown.containsKey("/old"), "窗口外命令不得出现在 breakdown")
    }

    @Test
    fun `developer session cannot read another owner's bot`() = testApplication {
        application { moduleUnderTest() }
        // u1（alex）是开发者；u2（alice）不在 DEVELOPER_USER_IDS 白名单里
        val devToken = client.devLogin("alex@example.com")
        val botId = client.createBot(devToken, "g57own")

        // 越权：用同一个 dev token 访问别人的 bot id
        val foreign = client.get("/api/developer/bots/bot-not-mine/analytics") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertTrue(
            foreign.status == HttpStatusCode.Forbidden || foreign.status == HttpStatusCode.NotFound,
            "越权访问他人 bot 必须被拒，实际 ${foreign.status}：${foreign.bodyAsText()}",
        )
        val foreignLogs = client.get("/api/developer/bots/bot-not-mine/logs") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertTrue(
            foreignLogs.status == HttpStatusCode.Forbidden || foreignLogs.status == HttpStatusCode.NotFound,
            "越权读取他人日志必须被拒，实际 ${foreignLogs.status}",
        )

        // 未带 token 一律 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/developer/dashboard?bot_id=$botId").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/developer/bots/$botId/logs").status)

        // 自己的 bot 正常可读（正对照）
        val own = client.get("/api/developer/bots/$botId/analytics") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertEquals(HttpStatusCode.OK, own.status, own.bodyAsText())
    }

    @Test
    fun `developer health reports bot and server state`() = testApplication {
        application { moduleUnderTest() }
        val devToken = client.devLogin("alex@example.com")
        val botId = client.createBot(devToken, "g57health")
        seedCommandLogs(botId, listOf(Triple("/start", "user-a", System.currentTimeMillis())))
        assertEquals(1L, rawLogCount(botId), "正对照：种子日志必须真的在库里")

        val response = client.get("/api/developer/health?bot_id=$botId") {
            header(HttpHeaders.Authorization, "Bearer $devToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

        val bot = body["bot"]!!.jsonObject
        assertEquals(botId, bot["botId"]!!.jsonPrimitive.content)
        assertEquals(1L, bot["totalCommands"]!!.jsonPrimitive.content.toLong(), "health 的命令数必须与表一致")
        assertTrue(body["status"]!!.jsonPrimitive.content in setOf("healthy", "degraded"))
        assertTrue(body["server"]!!.jsonObject["serverTime"]!!.jsonPrimitive.content.toLong() > 0L)
    }
}
