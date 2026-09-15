package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ServiceMessageRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 服务端**存储边界**证据（G11 原结论在 G32/G33 被审计推翻并收窄）。
 *
 * 这个文件能证明什么：人类 V2 提交的那串 opaque 载荷，在**这条 repository 路径**上
 * 只落进它自己的每设备信封列；metadata 行存在；发送不会改写会话里**已经存在**的
 * `chats.last_message` 预览；也不会生成同 messageId 的 `service_messages` 正文。
 *
 * 这个文件**不能**证明什么（不要引用它去支撑这些结论）：
 * - 真实客户端 Signal 加密是否正确（载荷是测试直接构造的，没经过任何加密器）；
 * - 「服务端全库不含人类明文」——本用例只扫一个进程内 H2 库的当前表/列，
 *   不含日志、导出、备份、崩溃报告、代理或生产 PostgreSQL 的其它表；
 * - 预览列永远为空（`ServiceMessageRepository` 的 bot/service 路径**会**写它）；
 * - 真实双设备 E2EE / 离线投递（见 `MessagingV2TwoDeviceDeliveryTest`）。
 *
 * 正对照的必要性：没有正对照的「找不到」是自证——一个永远返回空的扫描器也能「通过」。
 * 所以 bot/service 用例先证明扫描器确实能发现服务端**按设计保存**的内容。
 */
class ServerPlaintextSweepTest {

    private var database: Database? = null
    private var dbUrl: String = ""

    @AfterEach
    fun closeDatabase() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `human v2 payload stays inside its own envelope and leaves existing preview alone`() {
        seedGroup()
        // 这个标记**真的**进了发送命令，所以才值得追它到底落在哪。
        // 旧版本这里另取一个从未进入任何加密/发送路径的随机「明文哨兵」，
        // 再断言扫不到它——那不是证据，只是一个永远为真的自证（G32 审计结论）。
        val payload = "OPAQUE-PAYLOAD-" + java.util.UUID.randomUUID()
        val existingPreview = "pre-existing-service-preview"
        transaction {
            Chats.update({ Chats.id eq "group-1" }) {
                it[Chats.lastMessage] = existingPreview
            }
        }

        val command = humanCommand(payload)
        val result = MessagingV2Repository { 10_000L }.send(command)
        assertEquals(command.id, result.messageId, "发送必须真的成功，否则下面的结论没有意义")
        assertEquals(1, result.envelopeCount)

        transaction {
            assertEquals(
                1L,
                MessagingV2Messages.selectAll().count(),
                "人类消息的元数据应当落库，否则本用例的负结论没有意义",
            )
            val stored = MessagingV2Envelopes.selectAll().single()
            assertEquals(command.id, stored[MessagingV2Envelopes.messageId])
            assertEquals("bob", stored[MessagingV2Envelopes.recipientUserId])
            assertEquals(1, stored[MessagingV2Envelopes.recipientDeviceId])
            assertEquals(payload, stored[MessagingV2Envelopes.ciphertext], "落库的应当是提交的载荷本身")
            assertEquals(
                existingPreview,
                Chats.selectAll().where { Chats.id eq "group-1" }.single()[Chats.lastMessage],
                "人类 V2 发送不得改写已经存在的聊天预览",
            )
            assertEquals(
                0L,
                ServiceMessages.selectAll().where { ServiceMessages.id eq command.id }.count(),
                "人类 V2 发送不得生成同 messageId 的服务消息正文",
            )
        }

        val hits = sweep(payload)
        assertEquals(
            1,
            hits.size,
            "人类 V2 提交的载荷只允许出现在它自己的信封列；这些位置出现了额外副本：$hits",
        )
        assertTrue(
            hits.single().endsWith("MESSAGING_V2_ENVELOPES.CIPHERTEXT"),
            "载荷应当落在信封密文列，实际是：$hits",
        )
    }

    @Test
    fun `the sweep really can find plaintext that the server does store`() {
        seedGroup()
        seedBotParticipant()
        val botPlaintextSentinel = "BOT-PLAINTEXT-" + java.util.UUID.randomUUID()

        transaction {
            com.maodouchat.server.db.BotApps.insert {
                it[id] = "bot_helper"
                it[ownerUserId] = "alice"
                it[name] = "helper"
                it[username] = "helper_bot"
                it[tokenHash] = "x"
                it[tokenPrefix] = "x"
                it[enabled] = true
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
        }

        // 正对照：bot/service 消息按设计是服务端可见的（服务端要能替 bot 生成与审计），
        // 所以这里**应当**能在库里找到这段内容。
        //
        // 它证明的只有「扫描器不是恒空」这一件事。它**不能**被用来论证人类消息的
        // Signal 加密正确性、整库无明文、或日志/备份安全——那些是另外的证据面。
        val result = ServiceMessageRepository().publish(
            id = "sweep-service-message",
            chatId = "group-1",
            botUserId = "bot_helper",
            content = botPlaintextSentinel,
            timestamp = 9_000L,
            recipientUserIds = setOf("alice", "bob"),
        )
        assertTrue(result is ServiceMessageRepository.PublishResult.Published, "bot 发布应当成功：$result")

        val hits = sweep(botPlaintextSentinel)
        assertTrue(
            hits.any { it.endsWith("SERVICE_MESSAGES.CONTENT") },
            "扫描器连服务端**确实保存**的服务端内容都定位不到，那它在人类消息载荷上的结论毫无意义；实际命中：$hits",
        )
        assertTrue(
            hits.any { it.endsWith("CHATS.LAST_MESSAGE") },
            "服务端 bot 内容按设计也会更新聊天预览，扫描器应当能看见这一列；实际命中：$hits",
        )
    }

    /**
     * 枚举当前 schema 的全部表与全部列，逐行搜索 [needle]（按字符串形式比较，
     * 因此不依赖列类型，也不会漏掉非 VARCHAR 列里的值）。返回 `表.列` 命中清单。
     *
     * 刻意用一条**独立的 JDBC 连接**（而不是 Exposed 的 transaction 上下文）来枚举：
     * 扫描器要能看见「库里实际存了什么」，不该受 ORM 映射层的影响。
     */
    private fun sweep(needle: String): List<String> {
        val hits = mutableListOf<String>()
        java.sql.DriverManager.getConnection(dbUrl, "sa", "").use { connection ->
            val tables = mutableListOf<String>()
            connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    val tableSchema = rs.getString("TABLE_SCHEM")
                    val tableName = rs.getString("TABLE_NAME")
                    // 跳过 H2 自己的元数据 schema：那里出现的哨兵不来自我们的数据。
                    if (tableSchema != null && tableSchema.equals("INFORMATION_SCHEMA", ignoreCase = true)) continue
                    tables += if (tableSchema != null) "$tableSchema.$tableName" else tableName
                }
            }

            tables.forEach { table ->
                connection.createStatement().use { statement ->
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
        }
        return hits
    }

    private fun humanCommand(ciphertext: String) = SendMessageV2Command(
        id = "human-message-1",
        conversationId = "group-1",
        senderUserId = "alice",
        senderDeviceId = 1,
        kind = "DATA",
        clientTimestamp = 9_000L,
        groupRevision = 7L,
        envelopes = listOf(
            OutboundEnvelope(DeviceTarget("bob", 1), "TEXT", ciphertext),
        ),
    )

    private fun seedGroup() {
        val suffix = AtomicInteger().incrementAndGet()
        dbUrl = "jdbc:h2:mem:plaintext-sweep-$suffix-${kotlin.random.Random.nextInt()};DB_CLOSE_DELAY=-1"
        database = Database.connect(
            dbUrl,
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        initDatabase()
        transaction {
            listOf("alice", "bob").forEach { userId ->
                Users.insert {
                    it[id] = userId
                    it[name] = userId
                    it[email] = "$userId-$suffix@test.local"
                    it[passwordHash] = "x"
                    it[isOnline] = false
                }
                SignalDevices.insert {
                    it[SignalDevices.userId] = userId
                    it[deviceId] = 1
                    it[deviceName] = "$userId phone"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = 1
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        SignalKeys.insert {
                            it[id] = "$userId-1-$keyType"
                            it[SignalKeys.userId] = userId
                            it[SignalKeys.deviceId] = 1
                            it[SignalKeys.keyType] = keyType
                            it[keyData] = "x"
                            it[createdAt] = 1L
                        }
                    }
            }
            Chats.insert {
                it[id] = "group-1"
                it[isGroup] = true
                it[chatType] = "GROUP"
                it[groupName] = "sweep"
                it[memberRevision] = 7L
            }
            listOf("alice", "bob").forEach { userId ->
                ChatParticipants.insert {
                    it[chatId] = "group-1"
                    it[ChatParticipants.userId] = userId
                    it[role] = if (userId == "alice") "OWNER" else "MEMBER"
                    it[joinedAt] = 1L
                }
            }
        }
    }

    private fun seedBotParticipant() {
        transaction {
            Users.insert {
                it[id] = "bot_helper"
                it[name] = "helper"
                it[email] = "bot-helper@test.local"
                it[passwordHash] = "x"
                it[isOnline] = false
            }
            ChatParticipants.insert {
                it[chatId] = "group-1"
                it[userId] = "bot_helper"
                it[role] = "MEMBER"
                it[joinedAt] = 1L
            }
        }
    }
}
