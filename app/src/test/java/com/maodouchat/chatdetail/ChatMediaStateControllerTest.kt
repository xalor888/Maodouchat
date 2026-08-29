package com.maodouchat.chatdetail

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
}
