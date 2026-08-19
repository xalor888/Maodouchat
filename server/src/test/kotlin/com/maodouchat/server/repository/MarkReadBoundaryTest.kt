package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MarkReadBoundaryTest {

    @Test
    fun `mark read respects throughId watermark`() {
        val dbUrl =
            "jdbc:h2:mem:mark-read-boundary-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
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
            fun insertMessage(id: String, timestamp: Long) {
                Messages.insert {
                    it[Messages.id] = id
                    it[Messages.chatId] = "c1"
                    it[Messages.senderId] = "u2"
                    it[Messages.content] = "opaque"
                    it[Messages.type] = "TEXT"
                    it[Messages.timestamp] = timestamp
                    it[Messages.status] = "SENT"
                }
            }
            insertMessage("m1", now)
            insertMessage("m2", now + 1_000L)
            insertMessage("m3", now + 2_000L)
        }

        val updated = MessageRepository().markAllAsRead("c1", "u1", throughId = "m2")
        assertEquals(setOf("m1", "m2"), updated.map { it.first }.toSet())

        transaction {
            val receiptIds = ReadReceipts.selectAll()
                .where { ReadReceipts.userId eq "u1" }
                .map { it[ReadReceipts.messageId] }
                .toSet()
            assertEquals(setOf("m1", "m2"), receiptIds)
            assertFalse("m3" in receiptIds)

            val m3Status = Messages.select(Messages.status)
                .where { Messages.id eq "m3" }
                .single()[Messages.status]
            assertEquals("SENT", m3Status)
        }
    }
}
