package com.maodouchat.data

import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.MessageSearchDao
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.MessageSearchRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSearchSecretChatTest {

    @Test
    fun `secret chat message deletes stale index and is never upserted`() = runTest {
        val database = mockk<AppDatabase>()
        val messageDao = mockk<MessageDao>()
        val searchDao = mockk<MessageSearchDao>()
        val chatDao = mockk<ChatDao>()
        every { database.messageDao() } returns messageDao
        every { database.messageSearchDao() } returns searchDao
        every { database.chatDao() } returns chatDao
        coEvery { chatDao.isSecretChat("secret-chat") } returns true
        coEvery { searchDao.deleteDocument("m1") } returns Unit

        val repo = MessageSearchRepository(database)
        val changed = repo.indexMessage(
            Message(id = "m1", chatId = "secret-chat", senderId = "u", content = "top secret", type = MessageType.TEXT)
        )

        assertFalse(changed)
        coVerify(exactly = 1) { searchDao.deleteDocument("m1") }
        coVerify(exactly = 0) { searchDao.upsertDocument(any()) }
        coVerify(exactly = 0) { searchDao.replaceDocument(any(), any()) }
    }

    @Test
    fun `preloaded secret chat ids skip per message dao lookup`() = runTest {
        val database = mockk<AppDatabase>()
        val messageDao = mockk<MessageDao>()
        val searchDao = mockk<MessageSearchDao>()
        val chatDao = mockk<ChatDao>()
        every { database.messageDao() } returns messageDao
        every { database.messageSearchDao() } returns searchDao
        every { database.chatDao() } returns chatDao
        coEvery { searchDao.deleteDocument("m1") } returns Unit

        val repo = MessageSearchRepository(database)
        repo.indexMessage(
            Message(id = "m1", chatId = "secret-chat", senderId = "u", content = "top secret", type = MessageType.TEXT),
            secretChatIds = setOf("secret-chat")
        )

        coVerify(exactly = 0) { chatDao.isSecretChat(any()) }
        coVerify(exactly = 1) { searchDao.deleteDocument("m1") }
    }

    @Test
    fun `normal chat message still enters index`() = runTest {
        val database = mockk<AppDatabase>()
        val messageDao = mockk<MessageDao>()
        val searchDao = mockk<MessageSearchDao>()
        val chatDao = mockk<ChatDao>()
        every { database.messageDao() } returns messageDao
        every { database.messageSearchDao() } returns searchDao
        every { database.chatDao() } returns chatDao
        coEvery { chatDao.isSecretChat("normal-chat") } returns false
        coEvery { searchDao.getFingerprint("m2") } returns null
        coEvery { searchDao.replaceDocument(any(), any()) } returns Unit

        val repo = MessageSearchRepository(database)
        val changed = repo.indexMessage(
            Message(id = "m2", chatId = "normal-chat", senderId = "u", content = "hello", type = MessageType.TEXT)
        )

        assertTrue(changed)
        coVerify(exactly = 0) { searchDao.deleteDocument("m2") }
        coVerify(exactly = 1) { searchDao.replaceDocument(any(), any()) }
    }
}
