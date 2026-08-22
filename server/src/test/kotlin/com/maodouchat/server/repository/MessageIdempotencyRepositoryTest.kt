package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repository-level send idempotency: same requestedId + same payload is a no-op
 * (`wasExisting=true`); a conflicting payload must throw instead of rewriting.
 */
class MessageIdempotencyRepositoryTest {

    private var database: Database? = null

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:msg-idempotency-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
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
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `same id and payload is idempotent and does not rewrite the original row`() {
        setupDb()
        val repo = MessageRepository()
        val first = repo.sendMessage(
            chatId = "c1",
            senderId = "u1",
            content = "cipher-v1",
            type = "TEXT",
            requestedId = "m_repo_idempotent"
        )
        assertFalse(first.wasExisting)
        assertEquals("m_repo_idempotent", first.message.id)
        assertEquals("cipher-v1", first.message.content)

        val retry = repo.sendMessage(
            chatId = "c1",
            senderId = "u1",
            content = "cipher-v1",
            type = "TEXT",
            requestedId = "m_repo_idempotent"
        )
        assertTrue(retry.wasExisting)
        assertEquals(first.message.id, retry.message.id)
        assertEquals(first.message.timestamp, retry.message.timestamp)
        assertEquals("cipher-v1", retry.message.content)

        transaction {
            val rows = Messages.selectAll().where { Messages.chatId eq "c1" }.toList()
            assertEquals(1, rows.size)
            assertEquals("cipher-v1", rows.single()[Messages.content])
        }
    }

    @Test
    fun `same id with conflicting payload throws DuplicateMessageIdException`() {
        setupDb()
        val repo = MessageRepository()
        repo.sendMessage(
            chatId = "c1",
            senderId = "u1",
            content = "cipher-v1",
            type = "TEXT",
            requestedId = "m_repo_conflict"
        )

        assertFailsWith<DuplicateMessageIdException> {
            repo.sendMessage(
                chatId = "c1",
                senderId = "u1",
                content = "cipher-other",
                type = "TEXT",
                requestedId = "m_repo_conflict"
            )
        }

        transaction {
            val rows = Messages.selectAll().where { Messages.id eq "m_repo_conflict" }.toList()
            assertEquals(1, rows.size)
            assertEquals("cipher-v1", rows.single()[Messages.content])
        }
    }
}
