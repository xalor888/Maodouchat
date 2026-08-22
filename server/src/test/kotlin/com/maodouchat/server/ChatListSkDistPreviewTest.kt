package com.maodouchat.server

import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ChatRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XAL-41：聊天列表尾消息为 SK_DIST 时，服务端不得把密钥分发内容当预览，
 * 也不得把 SK_DIST 计入未读；时间仍参与排序。
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
        val created = ChatRepository().createChat(participantIds = listOf("u1", "u2"))
        val chatId = created.id
        val skDistTs = System.currentTimeMillis()
        val secret = "sk-payload-must-not-leak"
        transaction {
            Messages.insert {
                it[Messages.id] = "m_sk"
                it[Messages.chatId] = chatId
                it[Messages.senderId] = "u2"
                it[Messages.content] = secret
                it[Messages.type] = "SK_DIST"
                it[Messages.timestamp] = skDistTs
                it[Messages.status] = "SENT"
                it[Messages.expiresAt] = 0L
            }
        }

        val listed = ChatRepository().getChatsForUser("u1").single()
        assertEquals("", listed.lastMessage)
        assertTrue(!listed.lastMessage.contains(secret))
        assertEquals("TEXT", listed.lastMessageType)
        assertEquals(skDistTs, listed.lastMessageTime)
        assertEquals(0, listed.unreadCount)

        val byId = ChatRepository().getChatById(chatId, viewerId = "u1")!!
        assertEquals("", byId.lastMessage)
        assertEquals("TEXT", byId.lastMessageType)
        assertEquals(skDistTs, byId.lastMessageTime)
    }

    @Test
    fun `sk dist after a real message is excluded from unread and list preview`() {
        setupDb()
        val created = ChatRepository().createChat(participantIds = listOf("u1", "u2"))
        val chatId = created.id
        val textTs = System.currentTimeMillis() - 5_000L
        val skDistTs = System.currentTimeMillis()
        transaction {
            Messages.insert {
                it[Messages.id] = "m_text"
                it[Messages.chatId] = chatId
                it[Messages.senderId] = "u2"
                it[Messages.content] = "ciphertext-body"
                it[Messages.type] = "VOICE"
                it[Messages.timestamp] = textTs
                it[Messages.status] = "SENT"
                it[Messages.expiresAt] = 0L
            }
            Messages.insert {
                it[Messages.id] = "m_sk"
                it[Messages.chatId] = chatId
                it[Messages.senderId] = "u2"
                it[Messages.content] = "sk-payload-must-not-leak"
                it[Messages.type] = "SK_DIST"
                it[Messages.timestamp] = skDistTs
                it[Messages.status] = "SENT"
                it[Messages.expiresAt] = 0L
            }
        }

        val listed = ChatRepository().getChatsForUser("u1").single()
        assertEquals("", listed.lastMessage)
        assertEquals("TEXT", listed.lastMessageType)
        assertEquals(skDistTs, listed.lastMessageTime)
        assertEquals(1, listed.unreadCount)

        val byId = ChatRepository().getChatById(chatId, viewerId = "u1")!!
        assertEquals("[端到端加密语音]", byId.lastMessage)
        assertEquals("VOICE", byId.lastMessageType)
        assertEquals(skDistTs, byId.lastMessageTime)
    }
}
