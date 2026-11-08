package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.InboxRetryPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InboxRetryPolicyTest {

    @Test
    fun `permanent error code dead-letters on first failure`() {
        val decision = InboxRetryPolicy.decide(
            error = IllegalStateException("messaging_v2_wrong_device"),
            attemptsAfterFailure = 1,
            now = 10_000L,
        )
        assertIs<Decision.DeadLetter>(decision)
        assertEquals("messaging_v2_wrong_device", decision.errorCode)
    }

    @Test
    fun `unsupported ciphertext dead-letters on first failure`() {
        val decision = InboxRetryPolicy.decide(
            error = IllegalStateException("messaging_v2_unsupported_ciphertext"),
            attemptsAfterFailure = 1,
            now = 10_000L,
        )
        assertIs<Decision.DeadLetter>(decision)
    }

    @Test
    fun `eighth failure dead-letters even for transient errors`() {
        val decision = InboxRetryPolicy.decide(
            error = IllegalStateException("transient_ratchet_failure"),
            attemptsAfterFailure = 8,
            now = 10_000L,
        )
        assertIs<Decision.DeadLetter>(decision)
        assertEquals("transient_ratchet_failure", decision.errorCode)
    }

    @Test
    fun `first transient failure retries after one second`() {
        val decision = InboxRetryPolicy.decide(
            error = IllegalStateException("transient_ratchet_failure"),
            attemptsAfterFailure = 1,
            now = 10_000L,
        )
        assertIs<Decision.Retry>(decision)
        assertEquals("transient_ratchet_failure", decision.errorCode)
        assertEquals(11_000L, decision.nextAttemptAt)
    }

    @Test
    fun `seventh transient failure retries with exponential backoff`() {
        val decision = InboxRetryPolicy.decide(
            error = IllegalStateException("transient_ratchet_failure"),
            attemptsAfterFailure = 7,
            now = 10_000L,
        )
        assertIs<Decision.Retry>(decision)
        assertEquals(74_000L, decision.nextAttemptAt)
    }

    @Test
    fun `blank error message falls back to the exception class name`() {
        val decision = InboxRetryPolicy.decide(
            error = IllegalStateException(),
            attemptsAfterFailure = 1,
            now = 10_000L,
        )
        assertIs<Decision.Retry>(decision)
        assertEquals("IllegalStateException", decision.errorCode)
    }
}
