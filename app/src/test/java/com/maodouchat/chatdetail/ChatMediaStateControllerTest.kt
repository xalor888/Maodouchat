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

        assertEquals(0.625f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
    }

    @Test
    fun `finishDownload with failure and new uri replaces ready uri and warning`() {
        val state = ChatDetailUiState(fileReadyToOpenUri = "content://prev")
        val done = controller.finishDownload(state, "m", localUri = "content://new", failureMessage = "boom")

        assertEquals("content://new", done.fileReadyToOpenUri)
        assertEquals("boom", done.groupEncryptionWarning)
        assertFalse(done.mediaDownloadErrorMessageIds.contains("m"))
    }

    @Test
    fun `applyTransfers maps paused and ready to sending with their progress`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
            Message(id = "m2", chatId = "c1", senderId = "u1", content = "b", type = MessageType.FILE),
        )
        val transfers = listOf(
            transfer("m1", AttachmentTransferState.PAUSED).copy(uploadedBytes = 100L),
            transfer("m2", AttachmentTransferState.READY),
        )
        val updated = controller.applyTransfers(ChatDetailUiState(messages = messages), transfers)

        val byId = updated.messages.associateBy { it.id }
        assertEquals(MessageStatus.SENDING, byId.getValue("m1").status)
        assertEquals(MessageStatus.SENDING, byId.getValue("m2").status)
        assertEquals(0.9f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
        assertEquals(0.92f, updated.fileTransferProgress.getValue("m2"), 0.0001f)
    }

    @Test
    fun `applyTransfers leaves preparing transfers out of message statuses`() {
        val messages = listOf(
            Message(
                id = "m1", chatId = "c1", senderId = "u1", content = "a",
                type = MessageType.FILE, status = MessageStatus.SENT,
            ),
        )
        val updated = controller.applyTransfers(
            ChatDetailUiState(messages = messages),
            listOf(transfer("m1", AttachmentTransferState.PREPARING)),
        )

        assertEquals(MessageStatus.SENT, updated.messages.single().status)
        assertEquals(AttachmentTransferState.PREPARING, updated.fileTransferStates["m1"])
    }

    @Test
    fun `applyTransfers records progress for transfers without matching messages`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
        )
        val updated = controller.applyTransfers(
            ChatDetailUiState(messages = messages),
            listOf(transfer("ghost", AttachmentTransferState.UPLOADING).copy(uploadedBytes = 100L)),
        )

        assertEquals(1, updated.messages.size)
        assertEquals(MessageStatus.SENT, updated.messages.single().status)
        assertEquals(0.9f, updated.fileTransferProgress.getValue("ghost"), 0.0001f)
    }

    @Test
    fun `finishDownload keeps warning without error marker when not marked as media failure`() {
        val state = controller.beginDownload(ChatDetailUiState(), "m")
        val done = controller.finishDownload(state, "m", failureMessage = "boom")

        assertEquals("boom", done.groupEncryptionWarning)
        assertFalse(done.mediaDownloadErrorMessageIds.contains("m"))
        assertFalse(done.downloadingFileMessageIds.contains("m"))
    }

    @Test
    fun `updateSegmentProgress maps bytes into the given window`() {
        val updated = controller.updateSegmentProgress(
            ChatDetailUiState(),
            messageId = "seg",
            completed = 50L,
            total = 100L,
            start = 0.5f,
            end = 1f,
        )

        assertEquals(0.75f, updated.fileTransferProgress.getValue("seg"), 0.0001f)
    }

    @Test
    fun `applyTransfers maps sending to sending with near complete progress`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
        )
        val updated = controller.applyTransfers(
            ChatDetailUiState(messages = messages),
            listOf(transfer("m1", AttachmentTransferState.SENDING)),
        )

        assertEquals(MessageStatus.SENDING, updated.messages.single().status)
        assertEquals(0.92f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
        assertEquals(AttachmentTransferState.SENDING, updated.fileTransferStates["m1"])
    }

    @Test
    fun `applyTransfers clamps uploading progress past cipher size`() {
        val entity = transfer("m1", AttachmentTransferState.UPLOADING).copy(uploadedBytes = 200L, cipherSize = 100L)
        val updated = controller.applyTransfers(ChatDetailUiState(), listOf(entity))

        assertEquals(0.9f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
    }

    @Test
    fun `applyTransfers drops stale error codes of replaced transfers`() {
        val state = ChatDetailUiState(
            fileTransferErrors = mapOf("old" to "ERR_GONE"),
            fileTransferStates = mapOf("old" to AttachmentTransferState.FAILED),
        )
        val updated = controller.applyTransfers(state, listOf(transfer("fresh", AttachmentTransferState.QUEUED)))

        assertTrue(updated.fileTransferErrors.isEmpty())
        assertFalse(updated.fileTransferStates.containsKey("old"))
    }

    @Test
    fun `finishDownload marks failure on a never started id without inventing a download`() {
        val done = controller.finishDownload(
            ChatDetailUiState(),
            "ghost",
            failureMessage = "boom",
            markMediaFailure = true,
        )

        assertTrue(done.mediaDownloadErrorMessageIds.contains("ghost"))
        assertEquals("boom", done.groupEncryptionWarning)
        assertTrue(done.downloadingFileMessageIds.isEmpty())
        assertEquals(null, done.fileReadyToOpenUri)
    }

    @Test
    fun `updateSegmentProgress publishes first value without a previous reading`() {
        val updated = controller.updateSegmentProgress(
            ChatDetailUiState(),
            messageId = "fresh",
            completed = 50L,
            total = 100L,
            start = 0f,
            end = 1f,
        )

        assertEquals(0.5f, updated.fileTransferProgress.getValue("fresh"), 0.0001f)
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

    @Test
    fun `applyTransfers maps failed with uploaded bytes to failed status with partial progress`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
        )
        val entity = transfer("m1", AttachmentTransferState.FAILED).copy(uploadedBytes = 50L, cipherSize = 100L)
        val updated = controller.applyTransfers(ChatDetailUiState(messages = messages), listOf(entity))

        assertEquals(MessageStatus.FAILED, updated.messages.single().status)
        assertEquals(0.625f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
        assertEquals(AttachmentTransferState.FAILED, updated.fileTransferStates["m1"])
    }

    @Test
    fun `applyTransfers maps uploading from zero bytes to base progress`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
        )
        val updated = controller.applyTransfers(
            ChatDetailUiState(messages = messages),
            listOf(transfer("m1", AttachmentTransferState.UPLOADING)),
        )

        assertEquals(MessageStatus.SENDING, updated.messages.single().status)
        assertEquals(0.35f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
    }

    @Test
    fun `finishDownload with uri on never started id sets ready uri only`() {
        val done = controller.finishDownload(ChatDetailUiState(), "ghost", localUri = "content://ghost")

        assertEquals("content://ghost", done.fileReadyToOpenUri)
        assertTrue(done.downloadingFileMessageIds.isEmpty())
        assertFalse(done.mediaDownloadErrorMessageIds.contains("ghost"))
    }

    @Test
    fun `beginDownload preserves sibling download and ready uri`() {
        val state = ChatDetailUiState(
            fileReadyToOpenUri = "content://prev",
            downloadingFileMessageIds = setOf("a"),
        )
        val started = controller.beginDownload(state, "b")

        assertTrue(started.downloadingFileMessageIds.contains("a"))
        assertTrue(started.downloadingFileMessageIds.contains("b"))
        assertEquals("content://prev", started.fileReadyToOpenUri)
        assertEquals(0f, started.fileTransferProgress["b"])
    }

    @Test
    fun `consumeReadyFile keeps progress and error markers`() {
        val state = ChatDetailUiState(
            fileReadyToOpenUri = "content://x",
            fileTransferProgress = mapOf("a" to 0.5f),
            mediaDownloadErrorMessageIds = setOf("e"),
            downloadingFileMessageIds = setOf("d"),
        )
        val cleared = controller.consumeReadyFile(state)

        assertEquals(null, cleared.fileReadyToOpenUri)
        assertEquals(0.5f, cleared.fileTransferProgress["a"])
        assertTrue(cleared.mediaDownloadErrorMessageIds.contains("e"))
        assertTrue(cleared.downloadingFileMessageIds.contains("d"))
    }

    @Test
    fun `beginDownload resets existing progress to zero for that id`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("b" to 0.8f))
        val started = controller.beginDownload(state, "b")

        assertEquals(0f, started.fileTransferProgress["b"])
        assertTrue(started.downloadingFileMessageIds.contains("b"))
    }

    @Test
    fun `finishDownload without mark removes pre-existing error marker`() {
        val state = ChatDetailUiState(
            mediaDownloadErrorMessageIds = setOf("m"),
            groupEncryptionWarning = "old",
        )
        val done = controller.finishDownload(state, "m")

        assertFalse(done.mediaDownloadErrorMessageIds.contains("m"))
        assertEquals("old", done.groupEncryptionWarning)
    }

    @Test
    fun `finishDownload empty failure message replaces warning with empty string`() {
        val state = ChatDetailUiState(groupEncryptionWarning = "old")
        val done = controller.finishDownload(state, "m", failureMessage = "")

        assertEquals("", done.groupEncryptionWarning)
    }

    @Test
    fun `applyTransfers duplicate messageId lets last transfer win`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
        )
        val transfers = listOf(
            transfer("m1", AttachmentTransferState.QUEUED),
            transfer("m1", AttachmentTransferState.FAILED),
        )
        val updated = controller.applyTransfers(ChatDetailUiState(messages = messages), transfers)

        assertEquals(MessageStatus.FAILED, updated.messages.single().status)
        assertEquals(AttachmentTransferState.FAILED, updated.fileTransferStates["m1"])
        assertEquals(0.35f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
        assertTrue(updated.fileTransferErrors.isEmpty())
    }

    @Test
    fun `applyTransfers keeps progress of ids absent from the states map`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("keep" to 0.5f))
        val updated = controller.applyTransfers(state, listOf(transfer("fresh", AttachmentTransferState.QUEUED)))

        assertEquals(0.5f, updated.fileTransferProgress["keep"])
        assertEquals(0.35f, updated.fileTransferProgress["fresh"])
    }

    @Test
    fun `updateSegmentProgress publishes a two percent jump`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("jump" to 0.5f))
        val updated = controller.updateSegmentProgress(
            state,
            messageId = "jump",
            completed = 520L,
            total = 1_000L,
            start = 0f,
            end = 1f,
        )
        assertEquals(0.52f, updated.fileTransferProgress.getValue("jump"), 0.0001f)
    }

    @Test
    fun `updateSegmentProgress publishes completion despite a tiny delta`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("done" to 0.995f))
        val updated = controller.updateSegmentProgress(
            state,
            messageId = "done",
            completed = 1_000L,
            total = 1_000L,
            start = 0f,
            end = 1f,
        )
        assertEquals(1f, updated.fileTransferProgress.getValue("done"), 0.0001f)
    }

    @Test
    fun `updateSegmentProgress clamps over-completion into the window`() {
        val updated = controller.updateSegmentProgress(
            ChatDetailUiState(),
            messageId = "over",
            completed = 150L,
            total = 100L,
            start = 0.5f,
            end = 1f,
        )
        assertEquals(1f, updated.fileTransferProgress.getValue("over"), 0.0001f)
    }

    @Test
    fun `updateSegmentProgress ignores negative totals`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("bad" to 0.2f))
        val updated = controller.updateSegmentProgress(
            state,
            messageId = "bad",
            completed = 5L,
            total = -5L,
            start = 0f,
            end = 1f,
        )
        assertEquals(0.2f, updated.fileTransferProgress.getValue("bad"), 0.0001f)
    }

    @Test
    fun `updateSegmentProgress clamps negative completion to the window start`() {
        val updated = controller.updateSegmentProgress(
            ChatDetailUiState(),
            messageId = "neg",
            completed = -10L,
            total = 100L,
            start = 0.25f,
            end = 0.75f,
        )
        assertEquals(0.25f, updated.fileTransferProgress.getValue("neg"), 0.0001f)
    }

    @Test
    fun `updateSegmentProgress publishes an exactly one percent jump`() {
        val state = ChatDetailUiState(fileTransferProgress = mapOf("jump" to 0.5f))
        val updated = controller.updateSegmentProgress(
            state,
            messageId = "jump",
            completed = 510L,
            total = 1_000L,
            start = 0f,
            end = 1f,
        )
        assertEquals(0.51f, updated.fileTransferProgress.getValue("jump"), 0.0001f)
    }

    @Test
    fun `updateSegmentProgress clamps an inverted window to its start`() {
        val updated = controller.updateSegmentProgress(
            ChatDetailUiState(),
            messageId = "inv",
            completed = 50L,
            total = 100L,
            start = 0.8f,
            end = 0.2f,
        )
        assertEquals(0.8f, updated.fileTransferProgress.getValue("inv"), 0.0001f)
    }

    @Test
    fun `applyTransfers maps paused with zero bytes to base progress`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.FILE),
        )
        val updated = controller.applyTransfers(
            ChatDetailUiState(messages = messages),
            listOf(transfer("m1", AttachmentTransferState.PAUSED)),
        )

        assertEquals(MessageStatus.SENDING, updated.messages.single().status)
        assertEquals(0.35f, updated.fileTransferProgress.getValue("m1"), 0.0001f)
    }

    @Test
    fun `finishDownload drops only its own progress and keeps siblings`() {
        val state = ChatDetailUiState(
            fileTransferProgress = mapOf("a" to 0.5f, "b" to 0.7f),
            downloadingFileMessageIds = setOf("a", "b"),
        )
        val done = controller.finishDownload(state, "a", localUri = "content://a")

        assertFalse(done.fileTransferProgress.containsKey("a"))
        assertEquals(0.7f, done.fileTransferProgress.getValue("b"), 0.0001f)
        assertTrue(done.downloadingFileMessageIds.contains("b"))
    }

    @Test
    fun `beginDownload clears only its own failure marker and keeps siblings`() {
        val state = ChatDetailUiState(
            mediaDownloadErrorMessageIds = setOf("a", "b"),
            groupEncryptionWarning = "stale",
        )
        val started = controller.beginDownload(state, "a")

        assertFalse(started.mediaDownloadErrorMessageIds.contains("a"))
        assertTrue(started.mediaDownloadErrorMessageIds.contains("b"))
        assertEquals(null, started.groupEncryptionWarning)
    }

}
