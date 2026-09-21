package com.maodouchat.server

import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.AiGateway
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G55 安全网：公告「已读确认（ack）」的可见性边界。
 *
 * 8.46 修复的契约是：ack 必须与 `activeForUser` 同一可见性判定
 * （status=ACTIVE + 生效窗口 [startsAt, expiresAt] + 受众命中），
 * 否则任意用户都能对**不可见**的 TAGGED / 过期 / 未发布公告打已读，
 * 污染管理后台的 acked 统计。
 *
 * 这些用例在把 `AnnouncementRouting` 的 4 处裸事务下沉到
 * `AnnouncementRepository` **之前**建立：下沉只许搬代码，不许改行为。
 */
class AnnouncementAckVisibilityRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        System.setProperty("DATABASE_URL", "jdbc:h2:mem:ann-ack-${AtomicInteger().incrementAndGet()}-${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("MASTER_ADMINS", "u1")
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
            com.maodouchat.server.repository.PostRepository(),
            FakeAiGatewayImpl(),
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter,
        )
        configureAdminEnhanceRouting(
            announcementRepo = AnnouncementRepository(),
            userTagRepo = UserTagRepository(),
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

    private suspend fun io.ktor.client.HttpClient.createTag(adminToken: String, name: String): String {
        val response = post("/api/admin/user-tags") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","color":"#123456","riskLevel":"LOW"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun io.ktor.client.HttpClient.assignTag(adminToken: String, userId: String, tagId: String) {
        val response = post("/api/admin/users/$userId/tags") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":["$tagId"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun io.ktor.client.HttpClient.createAnnouncement(
        adminToken: String,
        audience: String,
        tagId: String?,
        startsAt: Long,
        expiresAt: Long,
        draft: Boolean = false,
    ): String {
        val tagPart = tagId?.let { ",\"tagId\":\"$it\"" } ?: ""
        val response = post("/api/admin/announcements") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"title":"G55 notice","content":"ack visibility probe","audience":"$audience","level":"INFO","startsAt":$startsAt,"expiresAt":$expiresAt,"draft":$draft$tagPart}"""
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun io.ktor.client.HttpClient.publish(adminToken: String, id: String) {
        val response = post("/api/admin/announcements/$id/publish") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun io.ktor.client.HttpClient.ack(token: String, id: String): HttpStatusCode {
        val response = post("/api/announcements/$id/ack") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return response.status
    }

    private suspend fun io.ktor.client.HttpClient.ackedCount(adminToken: String, id: String): Long {
        val response = get("/api/admin/announcements/$id/stats") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["ackedCount"]!!.jsonPrimitive.content.toLong()
    }

    @Test
    fun `tagged announcement is ackable only by a tagged user and the count is real`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))
        val alexToken = client.login("alex@example.com")
        val aliceToken = client.login("alice@example.com")

        val tagId = client.createTag(adminToken, "g55-tagged")
        client.assignTag(adminToken, "u2", tagId) // 只有 alice 命中该标签

        val now = System.currentTimeMillis()
        val announcementId = client.createAnnouncement(
            adminToken = adminToken,
            audience = "TAGGED",
            tagId = tagId,
            startsAt = now - 60_000L,
            expiresAt = now + 86_400_000L,
        )
        client.publish(adminToken, announcementId)

        // 未命中标签的 alex：不可见 → 不得打已读
        assertEquals(HttpStatusCode.NotFound, client.ack(alexToken, announcementId), "未命中标签的用户不得 ack")
        assertEquals(0L, client.ackedCount(adminToken, announcementId), "失败的 ack 不得污染统计")

        // 命中标签的 alice：可见 → 可以打已读
        assertEquals(HttpStatusCode.OK, client.ack(aliceToken, announcementId))
        assertEquals(1L, client.ackedCount(adminToken, announcementId), "ack 后统计必须真的 +1")

        // 重复 ack 是幂等的，不应把计数推到 2
        assertEquals(HttpStatusCode.OK, client.ack(aliceToken, announcementId))
        assertEquals(1L, client.ackedCount(adminToken, announcementId), "重复 ack 不应重复计数")
    }

    @Test
    fun `draft announcement cannot be acked before it is published`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))
        val aliceToken = client.login("alice@example.com")

        val now = System.currentTimeMillis()
        val announcementId = client.createAnnouncement(
            adminToken = adminToken,
            audience = "ALL",
            tagId = null,
            startsAt = now - 60_000L,
            expiresAt = now + 86_400_000L,
            draft = true,
        )

        assertEquals(HttpStatusCode.NotFound, client.ack(aliceToken, announcementId), "未发布草稿不得被 ack")
        assertEquals(0L, client.ackedCount(adminToken, announcementId))

        // 发布后同一用户立即可读
        client.publish(adminToken, announcementId)
        assertEquals(HttpStatusCode.OK, client.ack(aliceToken, announcementId))
        assertEquals(1L, client.ackedCount(adminToken, announcementId))
    }

    @Test
    fun `ack is reflected as acked in the active list for the same user only`() = testApplication {
        application { moduleUnderTest() }
        val adminToken = client.adminSession(client.login("alex@example.com"))
        val alexToken = client.login("alex@example.com")
        val aliceToken = client.login("alice@example.com")

        val now = System.currentTimeMillis()
        val announcementId = client.createAnnouncement(
            adminToken = adminToken,
            audience = "ALL",
            tagId = null,
            startsAt = now - 60_000L,
            expiresAt = now + 86_400_000L,
        )
        client.publish(adminToken, announcementId)

        assertEquals(HttpStatusCode.OK, client.ack(aliceToken, announcementId))

        val aliceActive = client.get("/api/announcements/active") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, aliceActive.status, aliceActive.bodyAsText())
        val aliceEntry = json.parseToJsonElement(aliceActive.bodyAsText()).jsonObject["announcements"]!!
            .let { runCatching { it.jsonObject }.getOrNull() ?: it }
        assertTrue(
            aliceActive.bodyAsText().contains(""""acked":true"""),
            "alice 的 active 列表应标记已读：${aliceActive.bodyAsText()}",
        )
        assertFalse(aliceEntry.toString().isBlank())

        val alexActive = client.get("/api/announcements/active") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
        }
        assertEquals(HttpStatusCode.OK, alexActive.status, alexActive.bodyAsText())
        assertFalse(
            alexActive.bodyAsText().contains(""""acked":true"""),
            "未打已读的 alex 不应被标记成已读：${alexActive.bodyAsText()}",
        )
    }

    private class FakeAiGatewayImpl : AiGateway {
        override val model: String = "test-model"
    }
}
