package com.maodouchat.server

import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ChatRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListExpiredPreviewTest {

    @Test
    fun `expired disappearing message is not used as chat preview`() {
        val dbUrl =
            "jdbc:h2:mem:chat-exp-preview-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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

        val created = ChatRepository().createChat(participantIds = listOf("u1", "u2"))
        val chatId = created.id
        val visibleTimestamp = now - 60_000L
        transaction {
            Messages.insert {
                it[Messages.id] = "m_visible"
                it[Messages.chatId] = chatId
                it[Messages.senderId] = "u2"
                it[Messages.content] = "visible"
                it[Messages.type] = "VOICE"
                it[Messages.timestamp] = visibleTimestamp
                it[Messages.status] = "SENT"
                it[Messages.expiresAt] = 0L
            }
            Messages.insert {
                it[Messages.id] = "m_expired"
                it[Messages.chatId] = chatId
                it[Messages.senderId] = "u2"
                it[Messages.content] = "expired"
                it[Messages.type] = "IMAGE"
                it[Messages.timestamp] = now
                it[Messages.status] = "SENT"
                it[Messages.expiresAt] = now - 1_000L
            }
        }

        val byId = ChatRepository().getChatById(chatId, viewerId = "u1")!!
        assertEquals("VOICE", byId.lastMessageType)
        assertEquals(visibleTimestamp, byId.lastMessageTime)

        val chatList = ChatRepository().getChatsForUser("u1").single()
        assertEquals("VOICE", chatList.lastMessageType)
        assertEquals(visibleTimestamp, chatList.lastMessageTime)
    }
}
