package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ServiceMessageRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 命门证据：**人对人消息在服务端全库不含明文**。
 *
 * 为什么需要这个文件：`MessagingV2Messages` 表只有 id/conversation/sender/kind/timestamps/request_digest，
 * **没有任何正文字段**——「服务端读不到人类消息」目前完全靠 schema 结构成立，
 * 而**没有任何测试能发现有人开始持久化正文**。既有的 messaging-v2 不变量 9 只覆盖客户端一侧
 * （网络请求只带每设备密文），服务端这一半是空的。
 *
 * 设计上刻意包含一个**正对照**：先证明扫描器真的能在库里找到明文（走按设计保存明文的
 * bot/service 路径），再断言人类消息路径找不到。没有正对照的「找不到」是自证——
 * 一个永远返回空的扫描器也能「通过」。
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
    fun `a human v2 send leaves no plaintext anywhere in the server database`() {
        seedGroup()
        val plaintextSentinel = "PLAINTEXT-SENTINEL-" + java.util.UUID.randomUUID()
        val ciphertextForBob = "CIPHERTEXT-FOR-BOB-" + java.util.UUID.randomUUID()

        // 人类消息：线路上只有每设备密文。明文哨兵**从未离开设备**，所以它出现在库里
        // 只可能意味着服务端开始持久化正文。
        MessagingV2Repository { 10_000L }.send(humanCommand(ciphertextForBob))

        // 先确认这次发送真的发生了——否则「全库找不到明文」可能只是因为什么都没写进去。
        transaction {
            assertEquals(
                1,
                MessagingV2Messages.selectAll().count(),
                "人类消息的元数据应当落库，否则本用例的负结论没有意义",
            )
            val stored = MessagingV2Envelopes.selectAll().single()[MessagingV2Envelopes.ciphertext]
            assertEquals(ciphertextForBob, stored, "落库的应当是密文本身")
        }

        assertEquals(
            emptyList(),
            sweep(plaintextSentinel),
            "人类消息的明文出现在服务端库里了——服务端不该有任何地方能放下它",
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

        // bot/service 消息按设计是服务端可见的（服务端要能替 bot 生成与审计），
        // 所以这里**应当**能在库里找到明文——这正是扫描器的正对照。
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
            hits.isNotEmpty(),
            "扫描器连服务端**确实保存**的明文都找不到，那它在人类消息上的「找不到」毫无意义",
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
