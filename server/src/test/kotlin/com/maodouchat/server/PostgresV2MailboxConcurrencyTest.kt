package com.maodouchat.server

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.DeviceTarget
import com.maodouchat.server.messaging.v2.MessagingV2DuplicateMessageException
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.messaging.v2.OutboundEnvelope
import com.maodouchat.server.messaging.v2.SendMessageV2Command
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V2 设备邮箱在**真 PostgreSQL** 上的并发/幂等证据（G21 / M4）。
 *
 * 为什么需要：既有的 PG 证据只覆盖「迁移矩阵」与「群变更行锁」。
 * 收件箱 ACK（不变量 26「按设备确认」）与「同一 messageId 重复发送」这两条**并发**路径
 * 此前从没在 PG 上验证过——而 H2 上的 `server:test` 不能替代 PG 证据（行锁/唯一索引/
 * READ COMMITTED 的重取语义都不一样）。
 *
 * 每条用例都先断言自己**确实跑在 PostgreSQL 上**，否则用例可能在 H2 上假绿。
 */
@Tag("postgres")
class PostgresV2MailboxConcurrencyTest {

    /**
     * 刻意不叫 `chatId`：`ChatParticipants` 有一个同名**列**，而在 `insert { }` 的隐式接收者里
     * 未限定名会优先解析到列，于是 `it[ChatParticipants.chatId] = testChatId` 会把列引用写进 VALUES
     * （实测 PG 报 `invalid reference to FROM-clause entry for table "chat_participants"`）。
     */
    private val testChatId = "itv2-chat"
    private val revision = 7L

    private fun postgresBaseUrl(): String {
        val baseUrl = System.getenv("POSTGRES_TEST_DATABASE_URL")
            ?.takeIf(String::isNotBlank)
            ?: error("POSTGRES_TEST_DATABASE_URL is required for postgresIntegrationTest")
        require(baseUrl.startsWith("jdbc:postgresql://")) { "PostgreSQL integration URL required" }
        return baseUrl
    }

    /** 防止有人在 H2 上把这份「PG 并发证据」跑绿。 */
    private fun assertReallyOnPostgres() {
        val product = transaction {
            exec("SELECT version()") { rs -> if (rs.next()) rs.getString(1) else "" } ?: ""
        }
        assertTrue(
            product.contains("PostgreSQL"),
            "这条并发用例必须跑在 PostgreSQL 上，实际是：$product",
        )
    }

    /** 每台设备都有 identity/registration_id/signed_pre_key(+签名)，才可能被算作可投递设备。 */
    private fun seed() {
        UserRepository().createDefaultUsers()
        transaction {
            listOf("u1" to 1, "u1" to 2, "u2" to 1, "u2" to 2).forEach { (userId, deviceId) ->
                SignalDevices.insert {
                    it[SignalDevices.userId] = userId
                    it[SignalDevices.deviceId] = deviceId
                    it[deviceName] = "$userId-$deviceId"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = deviceId
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        SignalKeys.insert {
                            it[id] = "$userId-$deviceId-$keyType"
                            it[SignalKeys.userId] = userId
                            it[SignalKeys.deviceId] = deviceId
                            it[SignalKeys.keyType] = keyType
                            it[keyData] = "x"
                            it[createdAt] = 1L
                        }
                    }
            }
            if (Chats.selectAll().where { Chats.id eq testChatId }.empty()) {
                Chats.insert {
                    it[id] = testChatId
                    it[isGroup] = true
                    it[chatType] = "GROUP"
                    it[groupName] = "V2 mailbox concurrency"
                    it[memberRevision] = revision
                }
                listOf("u1", "u2").forEach { userId ->
                    ChatParticipants.insert {
                        it[ChatParticipants.chatId] = testChatId
                        it[ChatParticipants.userId] = userId
                        it[role] = if (userId == "u1") "OWNER" else "MEMBER"
                        it[joinedAt] = 1L
                    }
                }
            }
        }
    }

    /** 发件人是 u1/d1；目标恰好覆盖其余三台已确认设备（策略要求精确覆盖）。 */
    private fun command(messageId: String, ciphertext: String) = SendMessageV2Command(
        id = messageId,
        conversationId = testChatId,
        senderUserId = "u1",
        senderDeviceId = 1,
        kind = "DATA",
        clientTimestamp = 9_000L,
        groupRevision = revision,
        envelopes = listOf(
            OutboundEnvelope(DeviceTarget("u1", 2), "TEXT", "$ciphertext-u1d2"),
            OutboundEnvelope(DeviceTarget("u2", 1), "TEXT", "$ciphertext-u2d1"),
            OutboundEnvelope(DeviceTarget("u2", 2), "TEXT", "$ciphertext-u2d2"),
        ),
    )

    private fun runInIsolatedSchema(
        baseUrl: String,
        body: suspend () -> Unit,
    ) = runBlocking {
        val schema = "maodou_it_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        require(schema.matches(Regex("^maodou_it_[a-f0-9]{12}$")))
        DriverManager.getConnection(baseUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") }
        }
        val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"
        try {
            Database.connect(scopedUrl, driver = "org.postgresql.Driver")
            initDatabase()
            seed()
            body()
        } finally {
            DriverManager.getConnection(baseUrl).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE") }
            }
        }
    }

    private fun acknowledgedAtOf(envelopeId: String): Long? = transaction {
        MessagingV2Envelopes.selectAll()
            .where { MessagingV2Envelopes.id eq envelopeId }
            .single()[MessagingV2Envelopes.acknowledgedAt]
    }

    private fun envelopeIdsFor(userId: String, deviceId: Int): List<String> = transaction {
        MessagingV2Envelopes.selectAll().where {
            (MessagingV2Envelopes.recipientUserId eq userId) and
                (MessagingV2Envelopes.recipientDeviceId eq deviceId)
        }.map { it[MessagingV2Envelopes.id] }
    }

    @Test
    fun `concurrent acks from one device acknowledge each envelope exactly once`() = runInIsolatedSchema(
        postgresBaseUrl(),
    ) {
        assertReallyOnPostgres()
        val repository = MessagingV2Repository()
        repeat(5) { index -> repository.send(command("concurrent-ack-$index", "cipher-$index")) }

        val ids = envelopeIdsFor("u2", 1)
        assertEquals(5, ids.size, "前置条件：u2/d1 应当有 5 个 envelope")
        val siblingBefore = envelopeIdsFor("u2", 2).associateWith { acknowledgedAtOf(it) }

        // 四个并发调用者同时确认**同一批** id（这是客户端重试会真实产生的形状）。
        val barrier = CyclicBarrier(4)
        val returned = withContext(Dispatchers.IO) {
            (1..4).map {
                async(Dispatchers.IO) {
                    barrier.await()
                    repository.acknowledge("u2", 1, ids.toSet())
                }
            }.awaitAll()
        }

        // 每一行恰好被置一次：非空，且再确认一次不会改写时间戳。
        val firstTimestamps = ids.associateWith { acknowledgedAtOf(it) }
        assertTrue(firstTimestamps.values.all { it != null && it > 0L }, "所有 envelope 都必须被确认：$firstTimestamps")
        // 必须跨过一个毫秒再重试：`acknowledged_at` 是毫秒精度，同一毫秒内重写会得到相同值，
        // 断言就分辨不出「没被改写」和「被改写成同一毫秒」——本轮探针实测过这个假绿。
        Thread.sleep(5)
        repository.acknowledge("u2", 1, ids.toSet())
        assertEquals(
            firstTimestamps,
            ids.associateWith { acknowledgedAtOf(it) },
            "重复确认不得改写 acknowledged_at（否则「确认时间」不再可信）",
        )

        // 其它设备/其它账号的副本一行都不许被碰。
        assertEquals(
            siblingBefore,
            envelopeIdsFor("u2", 2).associateWith { acknowledgedAtOf(it) },
            "同账号另一台设备的副本被误改——ACK 变成了按账号生效",
        )
        assertTrue(
            envelopeIdsFor("u1", 2).all { acknowledgedAtOf(it) == null },
            "另一个账号设备的副本被误改——ACK 变成了全局生效",
        )

        // `acknowledged` 的语义是**幂等**的：「这次请求的 id 里，调用返回时已被确认的有几条」，
        // 而不是「这次调用改了几行」。所以并发/重试时每个调用者都合法地看到满额。
        //
        // 这一条**看起来像 bug，其实不是**——客户端的重试路径依赖它：
        // `MessagingV2InboxSynchronizer` 里有 `check(response.acknowledged == ids.size)`。
        // 如果把它改成「实际更新行数」，客户端重试（行早已确认）就会拿到 0 而抛
        // `messaging_v2_ack_incomplete`，把一条正常路径变成失败。
        // 本轮先按这个事实把测试写对，而不是顺手把服务端改成「更严格」从而打断重试。
        assertEquals(
            List(4) { ids.size },
            returned,
            "每个并发调用者都应当看到「请求的 id 全部已确认」——这是客户端 check(acknowledged == ids.size) 依赖的语义：$returned",
        )
    }

    @Test
    fun `concurrent acks from two devices stay device scoped`() = runInIsolatedSchema(postgresBaseUrl()) {
        assertReallyOnPostgres()
        val repository = MessagingV2Repository()
        repeat(3) { index -> repository.send(command("device-scope-$index", "cipher-$index")) }

        val device1Ids = envelopeIdsFor("u2", 1)
        val device2Ids = envelopeIdsFor("u2", 2)
        assertEquals(3, device1Ids.size)
        assertEquals(3, device2Ids.size)

        val barrier = CyclicBarrier(2)
        val results = withContext(Dispatchers.IO) {
            listOf(
                async(Dispatchers.IO) {
                    barrier.await()
                    repository.acknowledge("u2", 1, device1Ids.toSet())
                },
                async(Dispatchers.IO) {
                    barrier.await()
                    repository.acknowledge("u2", 2, device2Ids.toSet())
                },
            ).awaitAll()
        }

        assertTrue(device1Ids.all { acknowledgedAtOf(it) != null }, "d1 自己的副本应当被确认")
        assertTrue(device2Ids.all { acknowledgedAtOf(it) != null }, "d2 自己的副本应当被确认")
        assertTrue(
            envelopeIdsFor("u1", 2).all { acknowledgedAtOf(it) == null },
            "u1/d2 的副本不在这两次确认的作用域内，不该被改",
        )
        assertEquals(listOf(3, 3), results.sorted(), "每台设备各自确认 3 条：$results")
    }

    @Test
    fun `an ack for another device's envelope is not counted and changes nothing`() = runInIsolatedSchema(
        postgresBaseUrl(),
    ) {
        assertReallyOnPostgres()
        val repository = MessagingV2Repository()
        repeat(2) { index -> repository.send(command("foreign-ack-$index", "cipher-$index")) }

        val mine = envelopeIdsFor("u2", 1)
        val foreign = envelopeIdsFor("u2", 2)
        assertEquals(2, mine.size)
        assertEquals(2, foreign.size)
        val foreignBefore = foreign.associateWith { acknowledgedAtOf(it) }

        // 混着别人的 id 一起提交：只能算自己的，别人的一行都不能动（不变量 26 的授权作用域）。
        val acknowledged = repository.acknowledge("u2", 1, (mine + foreign).toSet())

        assertEquals(mine.size, acknowledged, "只应计算属于本设备的 id：$acknowledged")
        assertTrue(mine.all { acknowledgedAtOf(it) != null }, "自己的副本应当被确认")
        assertEquals(
            foreignBefore,
            foreign.associateWith { acknowledgedAtOf(it) },
            "不属于本设备的 id 被改动了——ACK 的授权作用域失效",
        )
    }

    @Test
    fun `concurrent identical sends never create a second message row`() = runInIsolatedSchema(
        postgresBaseUrl(),
    ) {
        assertReallyOnPostgres()
        val repository = MessagingV2Repository()
        val sameId = "identical-send-race"
        val sameCipher = "identical-cipher"

        val barrier = CyclicBarrier(2)
        val outcomes = withContext(Dispatchers.IO) {
            listOf(1, 2).map {
                async(Dispatchers.IO) {
                    barrier.await()
                    runCatching { repository.send(command(sameId, sameCipher)) }
                }
            }.awaitAll()
        }

        // 绝不产生两行：一条 message、每个目标设备恰好一个 envelope。
        transaction {
            assertEquals(
                1L,
                MessagingV2Messages.selectAll().where { MessagingV2Messages.id eq sameId }.count(),
                "并发重复发送产生了多行 message",
            )
        }
        listOf("u1" to 2, "u2" to 1, "u2" to 2).forEach { (userId, deviceId) ->
            assertEquals(
                1,
                envelopeIdsFor(userId, deviceId).size,
                "并发重复发送给 $userId/$deviceId 造出了重复 envelope",
            )
        }

        // 输的那一次必须是**领域结果**（幂等重放或明确的重复消息异常），
        // 不能把数据库唯一索引冲突当成未知 500 漏出去。
        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        assertTrue(
            failures.all { it is MessagingV2DuplicateMessageException },
            "并发重复发送的失败方必须是领域异常，实际：${failures.map { it.javaClass.name }}",
        )
        val successes = outcomes.mapNotNull { it.getOrNull() }
        assertTrue(successes.all { it.messageId == sameId }, "成功方的 messageId 应当一致：$successes")
    }

    @Test
    fun `rate limit sampler takes the postgres upsert branch`() = runInIsolatedSchema(postgresBaseUrl()) {
        assertReallyOnPostgres()
        val repository = RateLimitStatsRepository()
        val now = 1_700_000_040_000L // 分钟对齐的任意时刻

        repository.recordMinute(now)
        repository.recordMinute(now)

        val rows = transaction {
            RateLimitStatsSnapshots.selectAll()
                .where { RateLimitStatsSnapshots.bucketStartMs eq now - now % 60_000L }
                .toList()
        }
        assertEquals(1, rows.size, "同一个分钟桶重复采样必须更新同一行，而不是插入多行")
    }
}
