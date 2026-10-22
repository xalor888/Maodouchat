package com.maodouchat.chatdetail

import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.ChatMediaStateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaStateControllerTest {
    private val controller = ChatMediaStateController()

    @Test
    fun `download lifecycle clears transient state and exposes ready file`() {
        val started = controller.beginDownload(ChatDetailUiState(), "file-1")
        val completed = controller.finishDownload(started, "file-1", localUri = "content://local/file")

        assertTrue(started.downloadingFileMessageIds.contains("file-1"))
        assertEquals(0f, started.fileTransferProgress["file-1"])
        assertFalse(completed.downloadingFileMessageIds.contains("file-1"))
        assertFalse(completed.fileTransferProgress.containsKey("file-1"))
        assertEquals("content://local/file", completed.fileReadyToOpenUri)
    }

    @Test
    fun `media download failure is kept on message while file ready uri remains`() {
        val state = ChatDetailUiState(fileReadyToOpenUri = "content://previous")
        val updated = controller.finishDownload(
            controller.beginDownload(state, "media-1"),
            messageId = "media-1",
            failureMessage = "download failed",
            markMediaFailure = true,
        )

        assertTrue(updated.mediaDownloadErrorMessageIds.contains("media-1"))
        assertEquals("download failed", updated.groupEncryptionWarning)
        assertEquals("content://previous", updated.fileReadyToOpenUri)
    }

    @Test
    fun `segment progress suppresses sub-percent updates`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("file-1" to 0.5f))
        val updated = controller.updateSegmentProgress(
            state,
            messageId = "file-1",
            completed = 501L,
            total = 1_000L,
            start = 0f,
            end = 1f,
        )

        assertEquals(0.5f, updated.fileTransferProgress["file-1"])
    }

    @Test
    fun `consumeReadyFile clears ready uri only`() {
        val state = ChatDetailUiState(
            fileReadyToOpenUri = "content://x",
            downloadingFileMessageIds = setOf("a"),
        )
        val cleared = controller.consumeReadyFile(state)

        assertEquals(null, cleared.fileReadyToOpenUri)
        assertTrue(cleared.downloadingFileMessageIds.contains("a"))
    }

    @Test
    fun `beginDownload clears stale failure markers`() {
        val state = ChatDetailUiState(
            mediaDownloadErrorMessageIds = setOf("media-1"),
            groupEncryptionWarning = "stale warning",
        )
        val started = controller.beginDownload(state, "media-1")

        assertTrue(started.downloadingFileMessageIds.contains("media-1"))
        assertFalse(started.mediaDownloadErrorMessageIds.contains("media-1"))
        assertEquals(null, started.groupEncryptionWarning)
    }

    @Test
    fun `finishDownload without localUri keeps previous ready uri and does not mark failure`() {
        val state = controller.beginDownload(ChatDetailUiState(fileReadyToOpenUri = "content://prev"), "m")
        val done = controller.finishDownload(state, "m")

        assertEquals("content://prev", done.fileReadyToOpenUri)
        assertFalse(done.mediaDownloadErrorMessageIds.contains("m"))
    }

    @Test
    fun `applyTransfers maps failed and queued transfers to message statuses with error records`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
            Message(id = "m2", chatId = "c1", senderId = "u1", content = "b", type = MessageType.FILE),
        )
        val transfers = listOf(
            transfer("m1", AttachmentTransferState.FAILED, lastErrorCode = "ERR_NETWORK"),
            transfer("m2", AttachmentTransferState.QUEUED),
        )
        val updated = controller.applyTransfers(ChatDetailUiState(messages = messages), transfers)

        val byId = updated.messages.associateBy { it.id }
        assertEquals(MessageStatus.FAILED, byId.getValue("m1").status)
        assertEquals(MessageStatus.SENDING, byId.getValue("m2").status)
        assertEquals(AttachmentTransferState.FAILED, updated.fileTransferStates["m1"])
        assertEquals(AttachmentTransferState.QUEUED, updated.fileTransferStates["m2"])
        assertEquals("ERR_NETWORK", updated.fileTransferErrors["m1"])
        assertEquals(0.35f, updated.fileTransferProgress["m1"])
    }

    @Test
    fun `updateSegmentProgress publishes significant jumps and ignores invalid totals`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("jump" to 0.5f, "bad" to 0.2f))

        val jumped = controller.updateSegmentProgress(
            state,
            messageId = "jump",
            completed = 950L,
            total = 1_000L,
            start = 0f,
            end = 1f,
        )
        assertEquals(0.95f, jumped.fileTransferProgress["jump"])

        val ignored = controller.updateSegmentProgress(
            state,
            messageId = "bad",
            completed = 5L,
            total = 0L,
            start = 0f,
            end = 1f,
        )
        assertEquals(0.2f, ignored.fileTransferProgress["bad"])
    }

    @Test
    fun `beginDownload twice keeps the id in the downloading set once`() {
        val once = controller.beginDownload(ChatDetailUiState(), "file-1")
        val twice = controller.beginDownload(once, "file-1")

        assertEquals(setOf("file-1"), twice.downloadingFileMessageIds)
        assertEquals(0f, twice.fileTransferProgress["file-1"])
    }

    @Test
    fun `finishDownload clears only its own download`() {
        var state = controller.beginDownload(ChatDetailUiState(), "a")
        state = controller.beginDownload(state, "b")
        val done = controller.finishDownload(state, "a", localUri = "content://a")

        assertFalse(done.downloadingFileMessageIds.contains("a"))
        assertTrue(done.downloadingFileMessageIds.contains("b"))
        assertEquals("content://a", done.fileReadyToOpenUri)
    }

    @Test
    fun `applyTransfers drops stale progress of finished transfers`() {
        val state = ChatDetailUiState(
            fileTransferProgress = mapOf("stale" to 0.8f),
            fileTransferStates = mapOf("stale" to AttachmentTransferState.SENDING),
        )
        val updated = controller.applyTransfers(state, listOf(transfer("fresh", AttachmentTransferState.QUEUED)))

        assertFalse(updated.fileTransferProgress.containsKey("stale"))
        assertEquals(0.35f, updated.fileTransferProgress["fresh"])
    }

    @Test
    fun `applyTransfers maps uploading progress from uploaded bytes`() {
        val entity = transfer("m1", AttachmentTransferState.UPLOADING).copy(uploadedBytes = 50L, cipherSize = 100L)
        val updated = controller.applyTransfers(ChatDetailUiState(), listOf(entity))

        assertEquals(0.625f, updated.fileTransferProgress["m1"], 0.0001f)
    }

    @Test
    fun `finishDownload with failure and new uri replaces ready uri and warning`() {
        val state = ChatDetailUiState(fileReadyToOpenUri = "content://prev")
        val done = controller.finishDownload(state, "m", localUri = "content://new", failureMessage = "boom")

        assertEquals("content://new", done.fileReadyToOpenUri)
        assertEquals("boom", done.groupEncryptionWarning)
        assertFalse(done.mediaDownloadErrorMessageIds.contains("m"))
    }

    private fun transfer(
        messageId: String,
        state: String,
        lastErrorCode: String? = null,
    ) = AttachmentTransferEntity(
        messageId = messageId,
        ownerUserId = "u1",
        chatId = "c1",
        sourceUri = "uri",
        encryptedPath = "enc",
        fileName = "file",
        mimeType = "application/octet-stream",
        plainSize = 1L,
        keyBase64 = "k",
        ivBase64 = "i",
        cipherSha256 = "c",
        plainSha256 = "p",
        cipherSize = 100L,
        state = state,
        lastErrorCode = lastErrorCode,
    )
}
