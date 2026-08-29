package com.maodouchat.server

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.ConversationCreationRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListExpiredPreviewTest {

    @Test
    fun `internal transport record is not used as chat activity`() {
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

        val created = ConversationCreationRepository().create(participantIds = listOf("u1", "u2"))
        val chatId = created.id
        val visibleTimestamp = now - 60_000L
        transaction {
            insertMessagingV2MessageFixture("m_visible", chatId, "u2", visibleTimestamp)
            insertMessagingV2MessageFixture(
                messageId = "m_internal",
                conversationId = chatId,
                senderUserId = "u2",
                timestamp = now,
                kind = "INTERNAL",
                recordClass = MessagingV2RecordClass.INTERNAL,
            )
        }

        val byId = ConversationQueryRepository().getById(chatId, viewerId = "u1")!!
        assertEquals("", byId.lastMessage)
        assertEquals("TEXT", byId.lastMessageType)
        assertEquals(visibleTimestamp, byId.lastMessageTime)

        val chatList = ConversationQueryRepository().listForUser("u1").single()
        assertEquals("", chatList.lastMessage)
        assertEquals("TEXT", chatList.lastMessageType)
        assertEquals(visibleTimestamp, chatList.lastMessageTime)
    }
}
