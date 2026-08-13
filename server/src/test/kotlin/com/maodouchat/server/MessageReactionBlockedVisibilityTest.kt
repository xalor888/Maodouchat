package com.maodouchat.server

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.MessageRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageReactionBlockedVisibilityTest {

    @Test
    fun `blocked user reactions are hidden from message history`() {
        val dbUrl =
            "jdbc:h2:mem:reaction-block-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2", "u3").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "Group"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
            }
            listOf("u1", "u2", "u3").forEach { id ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "g1"
                    it[ChatParticipants.userId] = id
                    it[ChatParticipants.role] = "MEMBER"
                    it[ChatParticipants.joinedAt] = now
                }
            }
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = "u1"
                it[BlockedUsers.blockedId] = "u2"
            }
            Messages.insert {
                it[Messages.id] = "m1"
                it[Messages.chatId] = "g1"
                it[Messages.senderId] = "u3"
                it[Messages.content] = "hello"
                it[Messages.type] = "TEXT"
                it[Messages.timestamp] = now
                it[Messages.status] = "SENT"
            }
            Messages.insert {
                it[Messages.id] = "m2"
                it[Messages.chatId] = "g1"
                it[Messages.senderId] = "u2"
                it[Messages.content] = "blocked"
                it[Messages.type] = "TEXT"
                it[Messages.timestamp] = now
                it[Messages.status] = "SENT"
            }
            Messages.insert {
                it[Messages.id] = "m3"
                it[Messages.chatId] = "g1"
                it[Messages.senderId] = "u3"
                it[Messages.content] = "visible"
                it[Messages.type] = "TEXT"
                it[Messages.timestamp] = now
                it[Messages.status] = "SENT"
            }
            MessageReactions.insert {
                it[MessageReactions.messageId] = "m1"
                it[MessageReactions.userId] = "u2"
                it[MessageReactions.emoji] = "👍"
                it[MessageReactions.reactedAt] = now
            }
            MessageReactions.insert {
                it[MessageReactions.messageId] = "m1"
                it[MessageReactions.userId] = "u3"
                it[MessageReactions.emoji] = "❤️"
                it[MessageReactions.reactedAt] = now
            }
            ReadReceipts.insert {
                it[ReadReceipts.messageId] = "m1"
                it[ReadReceipts.userId] = "u2"
                it[ReadReceipts.readAt] = now
            }
            ReadReceipts.insert {
                it[ReadReceipts.messageId] = "m1"
                it[ReadReceipts.userId] = "u3"
                it[ReadReceipts.readAt] = now
            }
        }

        val messages = MessageRepository().getMessages("g1", limit = 50, viewerId = "u1")
        assertTrue(messages.none { it.id == "m2" })
        assertEquals(listOf("u3"), messages.first { it.id == "m1" }.reactions.map { it.userId })
        assertEquals(listOf("u3"), MessageRepository().getReactionsForViewer("m1", "u1").map { it.userId })
        assertEquals(listOf("u3"), MessageRepository().getReadReceipts("m1", "u1").map { it.userId })

        MessageRepository().markAllAsRead("g1", "u1")
        val u1Receipts = transaction {
            com.maodouchat.server.db.ReadReceipts
                .selectAll()
                .where { com.maodouchat.server.db.ReadReceipts.userId eq "u1" }
                .map { it[com.maodouchat.server.db.ReadReceipts.messageId] }
                .toSet()
        }
        assertTrue("m2" !in u1Receipts)
        assertTrue("m3" in u1Receipts)
    }
}
