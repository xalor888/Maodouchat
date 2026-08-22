package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XAL-20：聊天重复发送。同 requestedId + 同载荷幂等（wasExisting），冲突载荷抛 DuplicateMessageIdException。
 * 独立文件，避免与未合并的 XAL-16 MessageIdempotencyRepositoryTest 撞名。
 */
class MessageSendDuplicateIdRegressionTest {

    @Test
    fun `same requested id and payload is a no-op resend`() {
        val repo = seededRepo("dup-same")
        val first = repo.sendMessage("c1", "u1", "hello", requestedId = "m_dup")
        assertFalse(first.wasExisting)

        val second = repo.sendMessage("c1", "u1", "hello", requestedId = "m_dup")
        assertTrue(second.wasExisting)
        assertEquals(first.message.id, second.message.id)
        assertEquals(1, messageCount("c1"))
    }

    @Test
    fun `same requested id with different payload is rejected`() {
        val repo = seededRepo("dup-conflict")
        repo.sendMessage("c1", "u1", "hello", requestedId = "m_dup")

        assertFailsWith<DuplicateMessageIdException> {
            repo.sendMessage("c1", "u1", "other", requestedId = "m_dup")
        }
        assertEquals(1, messageCount("c1"))
    }

    @Test
    fun `sk dist with same requested id stays a single row`() {
        val repo = seededRepo("dup-sk")
        val first = repo.sendMessage(
            chatId = "c1",
            senderId = "u1",
            content = """{"algorithm":"signal-sender-key-distribution-v1","distributionMessage":"skdm"}""",
            type = "SK_DIST",
            requestedId = "sk_1"
        )
        val second = repo.sendMessage(
            chatId = "c1",
            senderId = "u1",
            content = """{"algorithm":"signal-sender-key-distribution-v1","distributionMessage":"skdm"}""",
            type = "SK_DIST",
            requestedId = "sk_1"
        )
        assertFalse(first.wasExisting)
        assertTrue(second.wasExisting)
        assertEquals(1, messageCount("c1"))
    }

    private fun seededRepo(suffix: String): MessageRepository {
        val dbUrl =
            "jdbc:h2:mem:msg-send-dup-$suffix-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id+$suffix@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            Chats.insert {
                it[Chats.id] = "c1"
                it[Chats.isGroup] = false
                it[Chats.chatType] = "DIRECT"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
            }
            listOf("u1", "u2").forEach { id ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "c1"
                    it[ChatParticipants.userId] = id
                    it[ChatParticipants.role] = "MEMBER"
                    it[ChatParticipants.joinedAt] = now
                }
            }
        }
        return MessageRepository()
    }

    private fun messageCount(chatId: String): Long = transaction {
        Messages.selectAll().where { Messages.chatId eq chatId }.count()
    }
}
