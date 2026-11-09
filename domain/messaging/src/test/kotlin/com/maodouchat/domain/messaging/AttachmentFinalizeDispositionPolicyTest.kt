package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AttachmentFinalizeOutcome
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentFinalizeDispositionPolicyTest {

    private val sentMessage = Message(
        id = "m1",
        chatId = "c1",
        senderId = "me",
        content = "x",
        type = MessageType.FILE,
    )

    @Test
    fun `sent is success`() {
        assertEquals(
            FinalizeDisposition.Success,
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.Sent(sentMessage),
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `already claimed is success`() {
        assertEquals(
            FinalizeDisposition.Success,
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.AlreadyClaimed,
                runAttemptCount = 3,
            ),
        )
    }

    @Test
    fun `discarded terminal is success`() {
        assertEquals(
            FinalizeDisposition.Success,
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.DiscardedTerminal,
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `claim invalidated retries below max`() {
        assertEquals(
            FinalizeDisposition.Retry,
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.ClaimInvalidated,
                runAttemptCount = 5,
            ),
        )
    }

    @Test
    fun `claim invalidated marks exhausted at max`() {
        assertEquals(
            FinalizeDisposition.Failure(markRetryExhausted = true),
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.ClaimInvalidated,
                runAttemptCount = 6,
            ),
        )
    }

    @Test
    fun `reupload required retries below max`() {
        assertEquals(
            FinalizeDisposition.Retry,
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.ReuploadRequired,
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `reupload required marks exhausted at max`() {
        assertEquals(
            FinalizeDisposition.Failure(markRetryExhausted = true),
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.ReuploadRequired,
                runAttemptCount = 6,
            ),
        )
    }

    @Test
    fun `transient retries below max`() {
        assertEquals(
            FinalizeDisposition.Retry,
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.Transient(RuntimeException("boom")),
                runAttemptCount = 2,
            ),
        )
    }

    @Test
    fun `transient marks exhausted at max`() {
        assertEquals(
            FinalizeDisposition.Failure(markRetryExhausted = true),
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.Transient(RuntimeException("boom")),
                runAttemptCount = 6,
            ),
        )
    }

    @Test
    fun `failed never marks exhausted`() {
        assertEquals(
            FinalizeDisposition.Failure(markRetryExhausted = false),
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.Failed(RuntimeException("boom")),
                runAttemptCount = 0,
            ),
        )
        assertEquals(
            FinalizeDisposition.Failure(markRetryExhausted = false),
            AttachmentFinalizeDispositionPolicy.finalizeDisposition(
                AttachmentFinalizeOutcome.Failed(RuntimeException("boom")),
                runAttemptCount = 6,
            ),
        )
    }

    @Test
    fun `max retries is six`() {
        assertEquals(6, AttachmentFinalizeDispositionPolicy.MAX_TRANSFER_RETRIES)
    }

    @Test
    fun `retry exhausted code is stable`() {
        assertEquals(
            "finalize_retry_exhausted",
            AttachmentFinalizeDispositionPolicy.FINALIZE_RETRY_EXHAUSTED_CODE,
        )
    }
}
