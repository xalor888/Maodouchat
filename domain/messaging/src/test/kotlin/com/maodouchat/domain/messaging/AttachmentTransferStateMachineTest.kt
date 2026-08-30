package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachmentTransferStateMachineTest {

    @Test
    fun `uploading can pause and resume`() {
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.UPLOADING, TransferStatus.PAUSED))
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.PAUSED, TransferStatus.UPLOADING))
    }

    @Test
    fun `committed and cancelled are terminal`() {
        TransferStatus.entries.forEach { next ->
            assertFalse(AttachmentTransferStateMachine.canTransition(TransferStatus.COMMITTED, next))
            assertFalse(AttachmentTransferStateMachine.canTransition(TransferStatus.CANCELLED, next))
        }
    }

    @Test
    fun `failure can retry from preparing`() {
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.FAILED, TransferStatus.PREPARING))
        assertFalse(AttachmentTransferStateMachine.canTransition(TransferStatus.FAILED, TransferStatus.UPLOADING))
    }

    @Test
    fun `normal flow is allowed`() {
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.PREPARING, TransferStatus.UPLOADING))
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.UPLOADING, TransferStatus.FINALIZING))
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.FINALIZING, TransferStatus.COMMITTED))
    }
}
