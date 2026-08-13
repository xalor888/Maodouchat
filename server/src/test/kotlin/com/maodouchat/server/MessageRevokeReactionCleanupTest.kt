package com.maodouchat.server

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.Messages
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

class MessageRevokeReactionCleanupTest {

    @Test
    fun `revoke removes reactions from the revoked message`() {
        val dbUrl =
            "jdbc:h2:mem:revoke-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "c1"
                it[ChatParticipants.userId] = "u1"
                it[ChatParticipants.role] = "MEMBER"
                it[ChatParticipants.joinedAt] = now
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "c1"
                it[ChatParticipants.userId] = "u2"
                it[ChatParticipants.role] = "MEMBER"
                it[ChatParticipants.joinedAt] = now
            }
            Messages.insert {
                it[Messages.id] = "m1"
                it[Messages.chatId] = "c1"
                it[Messages.senderId] = "u1"
                it[Messages.content] = "hello"
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
        }

        val result = MessageRepository().revokeMessage("m1", "u1")
        assertTrue(result is MessageRepository.RevokeResult.Applied)

        transaction {
            assertTrue(MessageReactions.selectAll().where { MessageReactions.messageId eq "m1" }.empty())
            assertEquals("REVOKED", Messages.selectAll().where { Messages.id eq "m1" }.first()[Messages.type])
        }
    }
}
