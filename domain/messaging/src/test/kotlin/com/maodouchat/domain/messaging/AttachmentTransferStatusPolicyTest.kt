package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentTransferStatusPolicyTest {
    @Test
    fun forwardMapsPreparing() {
        assertEquals(
            TransferStatus.PREPARING,
            AttachmentTransferStatusPolicy.transferStatusFromState("PREPARING"),
        )
    }

    @Test
    fun forwardMapsQueuedAndUploadingToUploading() {
        assertEquals(
            TransferStatus.UPLOADING,
            AttachmentTransferStatusPolicy.transferStatusFromState("QUEUED"),
        )
        assertEquals(
            TransferStatus.UPLOADING,
            AttachmentTransferStatusPolicy.transferStatusFromState("UPLOADING"),
        )
    }

    @Test
    fun forwardMapsReadyAndSendingToFinalizing() {
        assertEquals(
            TransferStatus.FINALIZING,
            AttachmentTransferStatusPolicy.transferStatusFromState("READY"),
        )
        assertEquals(
            TransferStatus.FINALIZING,
            AttachmentTransferStatusPolicy.transferStatusFromState("SENDING"),
        )
    }

    @Test
    fun forwardMapsPausedAndFailed() {
        assertEquals(
            TransferStatus.PAUSED,
            AttachmentTransferStatusPolicy.transferStatusFromState("PAUSED"),
        )
        assertEquals(
            TransferStatus.FAILED,
            AttachmentTransferStatusPolicy.transferStatusFromState("FAILED"),
        )
    }

    @Test
    fun forwardMapsUnknownStateToUploading() {
        assertEquals(
            TransferStatus.UPLOADING,
            AttachmentTransferStatusPolicy.transferStatusFromState("BOGUS"),
        )
        assertEquals(
            TransferStatus.UPLOADING,
            AttachmentTransferStatusPolicy.transferStatusFromState(""),
        )
    }

    @Test
    fun reverseMapsFiveStatusesToStateStrings() {
        assertEquals(
            "PREPARING",
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.PREPARING),
        )
        assertEquals(
            "UPLOADING",
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.UPLOADING),
        )
        assertEquals(
            "PAUSED",
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.PAUSED),
        )
        assertEquals(
            "READY",
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.FINALIZING),
        )
        assertEquals(
            "FAILED",
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.FAILED),
        )
    }

    @Test
    fun reverseReturnsNullForCommittedAndCancelled() {
        assertNull(
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.COMMITTED),
        )
        assertNull(
            AttachmentTransferStatusPolicy.transferStateFromStatus(TransferStatus.CANCELLED),
        )
    }
}
