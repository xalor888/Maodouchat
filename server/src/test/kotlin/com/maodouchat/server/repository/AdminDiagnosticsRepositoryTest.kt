package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.service.AdminAiAuditPolicy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G54：`AdminDiagnosticsRouting` 的 4 处裸事务下沉到仓储层后的**行为回归**。
 *
 * 覆盖下沉后的新仓储入口：Ops 快照计数、推送令牌列表（过滤 + 排序 + 分页）、
 * AI 审计列表（过滤 + 分页 + 元数据边界）、Bot 管理启停。
 * 每条都先断言「种子数据真的在库里」（正对照），否则「查不到」会被误判成正确。
 */
class AdminDiagnosticsRepositoryTest {

    private var database: Database? = null
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUpDb() {
        val dbUrl =
            "jdbc:h2:mem:admin-diag-${kotlin.random.Random.nextInt(1_000_000)}-${counter.incrementAndGet()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    private fun seedUser(id: String) {
        transaction {
            Users.insert {
                it[Users.id] = id
                it[Users.name] = "user-$id"
                it[Users.email] = "$id@example.com"
                it[Users.passwordHash] = "x"
            }
        }
    }

    @Test
    fun `ops snapshot counts match the tables it aggregates`() {
        seedUser("u1")
        seedUser("u2")
        transaction {
            Chats.insert { it[Chats.id] = "chat-1" }
            MessagingV2Messages.insert {
                it[MessagingV2Messages.id] = "msg-1"
                it[MessagingV2Messages.conversationId] = "chat-1"
                it[MessagingV2Messages.senderUserId] = "u1"
                it[MessagingV2Messages.senderDeviceId] = 1
                it[MessagingV2Messages.kind] = "DATA"
                it[MessagingV2Messages.clientTimestamp] = 1L
                it[MessagingV2Messages.serverTimestamp] = 10L
                it[MessagingV2Messages.recordClass] = MessagingV2RecordClass.MESSAGE
                it[MessagingV2Messages.requestDigest] = "d".repeat(64)
            }
            // 只有「启用且有 webhook」的机器人该被计入 botsWithWebhook
            insertBot("bot-on", enabled = true, webhook = "https://example.invalid/hook")
            insertBot("bot-off", enabled = false, webhook = "https://example.invalid/hook")
            insertBot("bot-nohook", enabled = true, webhook = null)
        }

        val snapshot = AdminManagementRepository().opsSnapshot(generatedAt = 1234L)

        assertEquals(2L, snapshot.users, "用户数应与 Users 表一致")
        assertEquals(1L, snapshot.messages, "消息数只应统计 MESSAGE recordClass")
        assertEquals(3L, snapshot.botsTotal)
        assertEquals(2L, snapshot.botsEnabled)
        assertEquals(1L, snapshot.botsWithWebhook, "停用/无 webhook 的机器人都不该计入")
        assertEquals(0L, snapshot.pollsTotal)
        assertEquals(0L, snapshot.pollsOpen)
        assertEquals(0L, snapshot.pollVotes)
        assertEquals(1234L, snapshot.generatedAt)
    }

    @Test
    fun `admin push token list filters by user and orders by recency`() {
        seedUser("u1")
        seedUser("u2")
        transaction {
            // PK 是 (user_id, device_id)：每个 token 用不同设备号，避免主键冲突
            listOf(
                listOf("tok-old", "u1", 100L, "device-1"),
                listOf("tok-new", "u1", 900L, "device-2"),
                listOf("tok-other", "u2", 500L, "device-1"),
            ).forEach { row ->
                val token = row[0] as String
                val userId = row[1] as String
                val updatedAt = row[2] as Long
                val deviceId = row[3] as String
                PushTokens.insert {
                    it[PushTokens.token] = token
                    it[PushTokens.userId] = userId
                    it[PushTokens.deviceId] = deviceId
                    it[PushTokens.platform] = "android"
                    it[PushTokens.timezoneOffsetMinutes] = 480
                    it[PushTokens.updatedAt] = updatedAt
                }
            }
        }
        // 正对照：三行都真的在库里
        assertEquals(3, transaction { PushTokens.selectAll().count() })

        val repo = AdminManagementRepository()
        val all = repo.listPushTokens(limit = 10, offset = 0, userIdFilter = null)
        assertEquals(
            listOf(900L, 500L, 100L),
            all.map { it.updatedAt },
            "应按 updatedAt 倒序返回",
        )
        assertTrue(all.all { it.platform == "android" }, "应透出平台元数据")
        assertEquals(480, all.first().timezoneOffsetMinutes, "应透出时区元数据")

        val onlyU1 = repo.listPushTokens(limit = 10, offset = 0, userIdFilter = "u1")
        assertEquals(listOf(900L, 100L), onlyU1.map { it.updatedAt })
        assertTrue(onlyU1.all { it.userId == "u1" }, "userId 过滤必须真的生效")
    }

    @Test
    fun `admin push token list paginates without overlap`() {
        seedUser("u1")
        transaction {
            (1..4).forEach { i ->
                PushTokens.insert {
                    it[PushTokens.token] = "page-$i"
                    it[PushTokens.userId] = "u1"
                    it[PushTokens.deviceId] = "device-$i"
                    it[PushTokens.platform] = "android"
                    it[PushTokens.timezoneOffsetMinutes] = 0
                    it[PushTokens.updatedAt] = i * 10L
                }
            }
        }
        val repo = AdminManagementRepository()
        val first = repo.listPushTokens(limit = 2, offset = 0, userIdFilter = null).map { it.deviceId }
        val second = repo.listPushTokens(limit = 2, offset = 2, userIdFilter = null).map { it.deviceId }
        assertEquals(listOf("device-4", "device-3"), first)
        assertEquals(listOf("device-2", "device-1"), second)
        assertTrue(first.intersect(second.toSet()).isEmpty(), "分页重叠")
    }

    @Test
    fun `admin ai audit list filters by feature and user and never projects chat identity`() {
        seedUser("u1")
        seedUser("u2")
        transaction {
            seedAuditRow("a1", "u1", "summary", createdAt = 1000L, inputTokens = 7L, outputTokens = 3L)
            seedAuditRow("a2", "u2", "translate", createdAt = 2000L, inputTokens = null, outputTokens = null)
            seedAuditRow("a3", "u1", "translate", createdAt = 3000L, inputTokens = 5L, outputTokens = 1L)
        }
        // 正对照：三行都在库里
        assertEquals(3, transaction { AiAuditLogs.selectAll().count() })

        val repo = AiRepository()

        val translateOnly = repo.listAuditLogsForAdmin(limit = 10, offset = 0, featureFilter = "translate", userFilter = null)
        assertEquals(2, translateOnly.size)
        assertTrue(translateOnly.all { it.feature == "translate" }, "feature 过滤必须真的生效")

        val forU2 = repo.listAuditLogsForAdmin(limit = 10, offset = 0, featureFilter = null, userFilter = "u2")
        assertEquals(1, forU2.size)
        assertEquals("u2", forU2.single().userId)

        // token 列直接读 Table 单例（不再走参数化裸 SQL 回填）：真实 token 必须体现在 estimatedTokens 上
        val u1Translate = repo.listAuditLogsForAdmin(limit = 10, offset = 0, featureFilter = null, userFilter = "u1")
            .first { it.feature == "translate" }
        assertEquals(6, u1Translate.estimatedTokens, "真实 token 之和应直接透出")

        // 元数据边界：序列化后的管理响应里绝不能出现 chatId / prompt / 正文
        val rendered = json.encodeToString(
            ListSerializer(com.maodouchat.server.model.AiUsageAdminResponse.serializer()),
            translateOnly + forU2,
        )
        assertFalse(rendered.contains("chat-secret"), "管理响应泄漏了 chatId：$rendered")
        AdminAiAuditPolicy.forbiddenPayloadKeys.forEach { key ->
            assertFalse(rendered.contains("\"$key\""), "管理响应出现了禁止字段 $key：$rendered")
        }
    }

    @Test
    fun `admin ai audit list paginates without overlap`() {
        seedUser("u1")
        transaction {
            (1..5).forEach { i ->
                seedAuditRow("p$i", "u1", "summary", createdAt = i * 100L)
            }
        }
        val repo = AiRepository()
        val firstPage = repo.listAuditLogsForAdmin(limit = 2, offset = 0, featureFilter = null, userFilter = null)
        val secondPage = repo.listAuditLogsForAdmin(limit = 2, offset = 2, featureFilter = null, userFilter = null)
        assertEquals(listOf("p5", "p4"), firstPage.map { it.id })
        assertEquals(listOf("p3", "p2"), secondPage.map { it.id })
        assertTrue(firstPage.map { it.id }.intersect(secondPage.map { it.id }.toSet()).isEmpty(), "分页重叠")
    }

    @Test
    fun `admin ai audit row without token columns falls back to estimate`() {
        seedUser("u3")
        transaction {
            seedAuditRow("legacy-1", "u3", "summary", createdAt = 50L, inputTokens = null, outputTokens = null)
        }
        val row = AiRepository()
            .listAuditLogsForAdmin(limit = 10, offset = 0, featureFilter = null, userFilter = null)
            .single()
        assertEquals("legacy-1", row.id)
        assertTrue(row.estimatedTokens >= 0, "老行没有 token 列时必须回退到估算，不能抛异常")
        assertFalse(row.toString().contains("chat-legacy"), "不得泄漏 chatId")
    }

    @Test
    fun `admin bot toggle flips enabled and returns null for unknown bot`() {
        seedUser("u1")
        transaction {
            insertBot("bot-x", enabled = true, webhook = null)
        }
        // 正对照：初始确实是启用
        assertEquals(true, transaction { BotApps.selectAll().single()[BotApps.enabled] })

        val disabled = BotRepository.setAdminEnabled("bot-x", enabled = false)
        assertNotNull(disabled)
        assertFalse(disabled.enabled, "启停后回读应为 false")
        assertEquals(false, transaction { BotApps.selectAll().single()[BotApps.enabled] }, "库里的值必须真的被改掉")

        val reEnabled = BotRepository.setAdminEnabled("bot-x", enabled = true)
        assertNotNull(reEnabled)
        assertTrue(reEnabled.enabled)
        assertEquals(true, transaction { BotApps.selectAll().single()[BotApps.enabled] })

        // 不存在的 bot：必须 null（路由据此回 404），不能抛异常
        assertNull(BotRepository.setAdminEnabled("no-such-bot", enabled = false))
    }

    private fun insertBot(id: String, enabled: Boolean, webhook: String?) {
        BotApps.insert {
            it[BotApps.id] = id
            it[BotApps.ownerUserId] = "u1"
            it[BotApps.name] = id
            it[BotApps.username] = id
            it[BotApps.tokenHash] = "hash-$id"
            it[BotApps.tokenPrefix] = "tok_$id".take(16)
            it[BotApps.enabled] = enabled
            it[BotApps.webhookUrl] = webhook
            it[BotApps.createdAt] = 1L
            it[BotApps.updatedAt] = 1L
        }
    }

    private fun seedAuditRow(
        id: String,
        userId: String,
        feature: String,
        createdAt: Long,
        inputTokens: Long? = null,
        outputTokens: Long? = null,
    ) {
        AiAuditLogs.insert {
            it[AiAuditLogs.id] = id
            it[AiAuditLogs.userId] = userId
            it[AiAuditLogs.chatId] = "chat-secret"
            it[AiAuditLogs.feature] = feature
            it[AiAuditLogs.model] = "model-$id"
            it[AiAuditLogs.status] = "ok"
            it[AiAuditLogs.inputChars] = 40
            it[AiAuditLogs.contextMessages] = 2
            it[AiAuditLogs.createdAt] = createdAt
            it[AiAuditLogs.inputTokens] = inputTokens
            it[AiAuditLogs.outputTokens] = outputTokens
        }
    }

    private companion object {
        val counter = AtomicInteger()
    }
}
