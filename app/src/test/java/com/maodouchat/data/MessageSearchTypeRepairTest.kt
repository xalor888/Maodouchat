package com.maodouchat.data

import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.MessageSearchDao
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.entity.MessageEntity
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchFingerprint
import com.maodouchat.data.repository.MessageSearchRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSearchTypeRepairTest {

    @Test
    fun `matching counts still repair stale migrated message types`() = runTest {
        val database = mockk<AppDatabase>()
        val messageDao = mockk<MessageDao>()
        val searchDao = mockk<MessageSearchDao>()
        val chatDao = mockk<ChatDao>()
        every { database.messageDao() } returns messageDao
        every { database.messageSearchDao() } returns searchDao
        every { database.chatDao() } returns chatDao

        val message = MessageEntity(
            id = "markdown-1",
            chatId = "chat-1",
            senderId = "sender",
            content = "sunset photo",
            type = "MARKDOWN",
            timestamp = 7L
        )
        coEvery { searchDao.countDocuments() } returns 1
        coEvery { messageDao.countSearchableWithContent() } returns 1
        coEvery { searchDao.hasMessageTypeMismatch() } returns true
        coEvery { searchDao.deleteDocumentsNotInSearchableTypes(any()) } returns Unit
        coEvery { chatDao.listSecretChatIds() } returns emptyList()
        coEvery {
            messageDao.getSearchableMessagesAfterCursor(-1L, "", any())
        } returns listOf(message)
        coEvery {
            messageDao.getSearchableMessagesAfterCursor(7L, "markdown-1", any())
        } returns emptyList()
        coEvery { searchDao.getFingerprintsForIds(listOf("markdown-1")) } returns listOf(
            MessageSearchFingerprint(
                messageId = "markdown-1",
                contentHash = "legacy-hash",
                messageType = "TEXT"
            )
        )
        val repairedDocument = slot<MessageSearchDocumentEntity>()
        coEvery { searchDao.replaceDocument(capture(repairedDocument), any()) } returns Unit

        val changed = MessageSearchRepository(database).refreshIndexIfStale()

        assertEquals(1, changed)
        assertEquals("MARKDOWN", repairedDocument.captured.messageType)
        coVerify(exactly = 1) { searchDao.hasMessageTypeMismatch() }
        coVerify(exactly = 1) { searchDao.replaceDocument(any(), any()) }
    }
}
