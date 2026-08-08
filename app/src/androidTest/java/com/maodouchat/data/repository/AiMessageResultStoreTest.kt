package com.maodouchat.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiMessageResultStoreTest {
    private lateinit var database: AppDatabase
    private lateinit var store: AiMessageResultStore

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        store = AiMessageResultStore(database)
        database.chatDao().insertChats(listOf(ChatEntity(id = CHAT_ID)))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runningOperationAndMessageResultCommitTogether() = runBlocking {
        database.aiOperationDao().upsert(operation("accepted"))
        assertEquals(1, database.aiOperationDao().markRunning("accepted", 2L))

        assertTrue(store.commit("accepted", message("message-accepted")))

        assertEquals(AiOperationState.SUCCEEDED, database.aiOperationDao().get("accepted")?.state)
        assertNotNull(database.messageDao().getMessageById("message-accepted"))
    }

    @Test
    fun cancelledOperationCannotPersistLateResult() = runBlocking {
        database.aiOperationDao().upsert(operation("cancelled"))
        assertEquals(1, database.aiOperationDao().markRunning("cancelled", 2L))
        assertEquals(1, database.aiOperationDao().markCancelled("cancelled", 3L))

        assertFalse(store.commit("cancelled", message("message-cancelled")))

        assertEquals(AiOperationState.CANCELLED, database.aiOperationDao().get("cancelled")?.state)
        assertNull(database.messageDao().getMessageById("message-cancelled"))
    }

    @Test
    fun aiResultPreservesConcurrentDeliveryAndInteractionState() = runBlocking {
        val reactions = listOf(MessageReaction(userId = "reader", emoji = "ok", reactedAt = 4L))
        val existing = message("message-merged").copy(
            content = "original",
            status = MessageStatus.READ,
            starred = true,
            reactions = reactions
        )
        database.messageDao().insertMessage(existing.toEntity())

        val aiResult = existing.copy(
            content = "original<meta>{\"translations\":{\"en\":\"result\"}}</meta>",
            status = MessageStatus.SENT,
            starred = false,
            reactions = emptyList()
        )
        assertTrue(store.commit(null, aiResult))

        val persisted = database.messageDao().getMessageById(existing.id)?.toDomain()
        assertEquals(MessageStatus.READ, persisted?.status)
        assertEquals(true, persisted?.starred)
        assertEquals(reactions, persisted?.reactions)
        assertEquals(aiResult.content, persisted?.content)
    }

    private fun operation(id: String) = AiOperationEntity(
        id = id,
        ownerUserId = "owner",
        chatId = CHAT_ID,
        type = "TRANSLATE_MESSAGE",
        parametersJson = "{}",
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun message(id: String) = Message(
        id = id,
        chatId = CHAT_ID,
        senderId = "sender",
        content = "cipher<meta>{\"translations\":{\"中文\":\"结果\"}}</meta>"
    )

    private companion object {
        const val CHAT_ID = "chat-ai-result"
    }
}
