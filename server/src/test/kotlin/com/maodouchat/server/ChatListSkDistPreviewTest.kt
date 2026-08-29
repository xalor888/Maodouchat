package com.maodouchat.server

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.ConversationCreationRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Internal sender-key records never become user-visible chat activity.
 * 独立文件，不追加 ChatListExpiredPreviewTest。
 */
class ChatListSkDistPreviewTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:chat-skdist-preview-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
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
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `sk dist as only tail is empty preview unread 0 and keeps sort time`() {
        setupDb()
        val created = ConversationCreationRepository().create(participantIds = listOf("u1", "u2"))
        val chatId = created.id
        val skDistTs = System.currentTimeMillis()
        transaction {
            insertMessagingV2MessageFixture(
                messageId = "m_sk",
                conversationId = chatId,
                senderUserId = "u2",
                timestamp = skDistTs,
                kind = "INTERNAL",
                recordClass = MessagingV2RecordClass.INTERNAL,
            )
        }

        val listed = ConversationQueryRepository().listForUser("u1").single()
        assertEquals("", listed.lastMessage)
        assertEquals("TEXT", listed.lastMessageType)
        assertEquals(0L, listed.lastMessageTime)
        assertEquals(0, listed.unreadCount)

        val byId = ConversationQueryRepository().getById(chatId, viewerId = "u1")!!
        assertEquals("", byId.lastMessage)
        assertEquals("TEXT", byId.lastMessageType)
        assertEquals(0L, byId.lastMessageTime)
    }

    @Test
    fun `sk dist after a real message is excluded from unread and list preview`() {
        setupDb()
        val created = ConversationCreationRepository().create(participantIds = listOf("u1", "u2"))
        val chatId = created.id
        val textTs = System.currentTimeMillis() - 5_000L
        val skDistTs = System.currentTimeMillis()
        transaction {
            insertMessagingV2MessageFixture("m_text", chatId, "u2", textTs)
            insertMessagingV2MessageFixture(
                messageId = "m_sk",
                conversationId = chatId,
                senderUserId = "u2",
                timestamp = skDistTs,
                kind = "INTERNAL",
                recordClass = MessagingV2RecordClass.INTERNAL,
            )
        }

        val listed = ConversationQueryRepository().listForUser("u1").single()
        assertEquals("", listed.lastMessage)
        assertEquals("TEXT", listed.lastMessageType)
        assertEquals(textTs, listed.lastMessageTime)
        assertEquals(0, listed.unreadCount)

        val byId = ConversationQueryRepository().getById(chatId, viewerId = "u1")!!
        assertEquals("", byId.lastMessage)
        assertEquals("TEXT", byId.lastMessageType)
        assertEquals(textTs, byId.lastMessageTime)
    }
}
