package com.maodouchat.attachment

import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.canRetryWithoutUpload
import com.maodouchat.data.local.entity.hasCompletedUpload
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AttachmentTransferEntityTest {
    @Test
    fun `transfer persists attachment message type`() {
        assertEquals("IMAGE", transfer(AttachmentTransferState.QUEUED, null, 0L).copy(messageType = "IMAGE").messageType)
        assertEquals("GIF", transfer(AttachmentTransferState.QUEUED, null, 0L).copy(messageType = "GIF").messageType)
        assertEquals("VIDEO", transfer(AttachmentTransferState.QUEUED, null, 0L).copy(messageType = "VIDEO").messageType)
        val voice = transfer(AttachmentTransferState.QUEUED, null, 0L).copy(
            messageType = "VOICE",
            durationMs = 12_340L
        )
        assertEquals("VOICE", voice.messageType)
        assertEquals(12_340L, voice.durationMs)
    }

    @Test
    fun `completed failed upload retries only encrypted message send`() {
        val transfer = transfer(
            state = AttachmentTransferState.FAILED,
            attachmentId = "att_complete",
            uploadedBytes = 1_024L
        )

        assertTrue(transfer.canRetryWithoutUpload())
    }

    @Test
    fun `partial upload must return to worker`() {
        assertFalse(
            transfer(
                state = AttachmentTransferState.FAILED,
                attachmentId = "att_partial",
                uploadedBytes = 512L
            ).canRetryWithoutUpload()
        )
    }

    @Test
    fun `pause after final checkpoint can continue directly to send`() {
        assertTrue(
            transfer(
                state = AttachmentTransferState.PAUSED,
                attachmentId = "att_complete",
                uploadedBytes = 1_024L
            ).canRetryWithoutUpload()
        )
    }

    @Test
    fun `ready transfer is finalized after process restart without reopening chat`() {
        assertEquals(
            AttachmentWorkerAction.FINALIZE,
            transfer(AttachmentTransferState.READY, "att_complete", 1_024L).nextWorkerAction()
        )
        assertEquals(
            AttachmentWorkerAction.PROMOTE_AND_FINALIZE,
            transfer(AttachmentTransferState.FAILED, "att_complete", 1_024L).nextWorkerAction()
        )
    }

    @Test
    fun `completed failed transfer is automatically reconciled without another upload`() {
        val transfer = transfer(AttachmentTransferState.FAILED, "att_complete", 1_024L)

        assertTrue(transfer.shouldScheduleAfterProcessDeath())
        assertEquals(AttachmentWorkerAction.PROMOTE_AND_FINALIZE, transfer.nextWorkerAction())
    }

    @Test
    fun `partial failed transfer waits for explicit resume after retries are exhausted`() {
        val transfer = transfer(AttachmentTransferState.FAILED, "att_partial", 512L)

        assertFalse(transfer.shouldScheduleAfterProcessDeath())
        assertEquals(AttachmentWorkerAction.UPLOAD, transfer.nextWorkerAction())
    }

    @Test
    fun `preparing paused and claimed states are not scheduled after restart`() {
        listOf(
            AttachmentTransferState.PREPARING,
            AttachmentTransferState.PAUSED,
            AttachmentTransferState.SENDING
        ).forEach { state ->
            val transfer = transfer(state, "att_complete", 1_024L)
            assertFalse(transfer.shouldScheduleAfterProcessDeath())
            assertEquals(AttachmentWorkerAction.STOP, transfer.nextWorkerAction())
        }
    }

    @Test
    fun `malformed completed checkpoint is rejected`() {
        val blankId = transfer(AttachmentTransferState.READY, "", 1_024L)
        val zeroSize = transfer(AttachmentTransferState.READY, "att", 0L).copy(cipherSize = 0L)

        assertFalse(blankId.hasCompletedUpload())
        assertFalse(blankId.shouldScheduleAfterProcessDeath())
        assertEquals(AttachmentWorkerAction.FAIL, blankId.nextWorkerAction())
        assertFalse(zeroSize.hasCompletedUpload())
        assertEquals(AttachmentWorkerAction.FAIL, zeroSize.nextWorkerAction())
    }

    @Test
    fun `unknown persisted state fails closed`() {
        val transfer = transfer("CORRUPTED", "att_complete", 1_024L)

        assertFalse(transfer.shouldScheduleAfterProcessDeath())
        assertEquals(AttachmentWorkerAction.FAIL, transfer.nextWorkerAction())
    }

    @Test
    fun `paused and already claimed transfer are not duplicated`() {
        assertEquals(
            AttachmentWorkerAction.STOP,
            transfer(AttachmentTransferState.PAUSED, "att_complete", 1_024L).nextWorkerAction()
        )
        assertEquals(
            AttachmentWorkerAction.STOP,
            transfer(AttachmentTransferState.SENDING, "att_complete", 1_024L).nextWorkerAction()
        )
    }

    private fun transfer(state: String, attachmentId: String?, uploadedBytes: Long) = AttachmentTransferEntity(
        messageId = "message_1",
        ownerUserId = "user_1",
        chatId = "chat_1",
        sourceUri = "content://document/1",
        encryptedPath = "attachment-uploads/message_1.bin",
        fileName = "document.pdf",
        mimeType = "application/pdf",
        plainSize = 1_008L,
        keyBase64 = "key",
        ivBase64 = "iv",
        cipherSha256 = "cipher-sha",
        plainSha256 = "plain-sha",
        cipherSize = 1_024L,
        attachmentId = attachmentId,
        state = state,
        uploadedBytes = uploadedBytes
    )
}
