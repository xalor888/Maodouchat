package com.maodouchat.server

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.MessageRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class RevokedMessageUnreadFilterTest {

    @Test
    fun `revoked messages never leave a permanent unread badge`() {
        val dbUrl =
            "jdbc:h2:mem:revoked-unread-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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
        }

        val chat = ChatRepository().createChat(participantIds = listOf("u1", "u2"))
        transaction {
            Messages.insert {
                it[Messages.id] = "m_revoked"
                it[Messages.chatId] = chat.id
                it[Messages.senderId] = "u2"
                it[Messages.content] = "revoked"
                it[Messages.type] = "REVOKED"
                it[Messages.timestamp] = now
                it[Messages.status] = "SENT"
            }
        }

        val unreadWindow = MessageRepository().getUnreadWindow(chat.id, "u1")
        assertEquals(0, unreadWindow.totalCount)
        assertEquals(emptyList(), unreadWindow.messageIds)

        val chatList = ChatRepository().getChatsForUser("u1")
        assertEquals(0, chatList.single().unreadCount)
    }
}
