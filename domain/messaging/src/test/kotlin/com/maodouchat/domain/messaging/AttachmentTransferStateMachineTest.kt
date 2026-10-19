package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `terminal set pins exactly committed and cancelled`() {
        assertEquals(
            setOf(TransferStatus.COMMITTED, TransferStatus.CANCELLED),
            AttachmentTransferStateMachine.terminal
        )
        // 失败不是终态：它还能从准备阶段重试
        assertFalse(AttachmentTransferStateMachine.terminal.contains(TransferStatus.FAILED))
    }

    @Test
    fun `cancel is reachable before finalizing starts`() {
        listOf(TransferStatus.PREPARING, TransferStatus.UPLOADING, TransferStatus.PAUSED).forEach { from ->
            assertTrue(AttachmentTransferStateMachine.canTransition(from, TransferStatus.CANCELLED), from.name)
        }
        // finalize 一旦开始就只能成功或失败，不再接受取消
        assertFalse(AttachmentTransferStateMachine.canTransition(TransferStatus.FINALIZING, TransferStatus.CANCELLED))
    }

    @Test
    fun `failed retry always restarts from preparing`() {
        TransferStatus.entries
            .filter { it != TransferStatus.PREPARING }
            .forEach { next ->
                assertFalse(AttachmentTransferStateMachine.canTransition(TransferStatus.FAILED, next), next.name)
            }
        assertTrue(AttachmentTransferStateMachine.canTransition(TransferStatus.FAILED, TransferStatus.PREPARING))
    }

    @Test
    fun `self transitions are never allowed`() {
        TransferStatus.entries.forEach { state ->
            assertFalse(AttachmentTransferStateMachine.canTransition(state, state), state.name)
        }
    }

    @Test
    fun `non-terminal states always have an exit, terminal states have none`() {
        val nonTerminal = TransferStatus.entries - AttachmentTransferStateMachine.terminal
        nonTerminal.forEach { from ->
            assertTrue(
                TransferStatus.entries.any { next -> AttachmentTransferStateMachine.canTransition(from, next) },
                from.name
            )
        }
        AttachmentTransferStateMachine.terminal.forEach { from ->
            assertFalse(
                TransferStatus.entries.any { next -> AttachmentTransferStateMachine.canTransition(from, next) },
                from.name
            )
        }
    }
}
