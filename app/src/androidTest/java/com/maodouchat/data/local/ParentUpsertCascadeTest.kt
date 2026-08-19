package com.maodouchat.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.local.entity.MessageEntity
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchTokenEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParentUpsertCascadeTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()

        database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                "Test requires SQLite foreign-key enforcement"
            }
        }

        database.chatDao().insertChats(listOf(chat()))
        database.messageDao().insertMessage(message())
        database.messageSearchDao().replaceDocument(
            document = MessageSearchDocumentEntity(
                messageId = MESSAGE_ID,
                chatId = CHAT_ID,
                senderId = "sender",
                searchableText = "hello",
                contentHash = "hash",
                timestamp = 1L,
                indexedAt = 2L
            ),
            tokens = listOf(
                MessageSearchTokenEntity(
                    messageId = MESSAGE_ID,
                    token = "hello",
                    chatId = CHAT_ID,
                    timestamp = 1L
                )
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun refreshingExistingChatPreservesMessagesAndSearchIndex() = runBlocking {
        database.chatDao().insertChats(listOf(chat(lastMessage = "refreshed")))

        assertGraphIntact()
        assertEquals("refreshed", database.chatDao().getChatById(CHAT_ID)?.lastMessage)
    }

    @Test
    fun updatingExistingMessagePreservesSearchIndex() = runBlocking {
        database.messageDao().insertMessage(message(status = "READ"))

        assertGraphIntact()
        assertEquals("READ", database.messageDao().getMessageById(MESSAGE_ID)?.status)
    }

    private suspend fun assertGraphIntact() {
        assertNotNull(database.chatDao().getChatById(CHAT_ID))
        assertNotNull(database.messageDao().getMessageById(MESSAGE_ID))
        assertEquals("hash", database.messageSearchDao().getContentHash(MESSAGE_ID))
        assertEquals(
            listOf(MESSAGE_ID),
            database.messageSearchDao().search(listOf("hello"), limit = 10).map { it.messageId }
        )
    }

    private fun chat(lastMessage: String = "original") = ChatEntity(
        id = CHAT_ID,
        lastMessage = lastMessage,
        lastMessageTime = 1L
    )

    private fun message(status: String = "SENT") = MessageEntity(
        id = MESSAGE_ID,
        chatId = CHAT_ID,
        senderId = "sender",
        content = "hello",
        timestamp = 1L,
        status = status
    )

    private companion object {
        const val CHAT_ID = "chat-upsert"
        const val MESSAGE_ID = "message-upsert"
    }
}
