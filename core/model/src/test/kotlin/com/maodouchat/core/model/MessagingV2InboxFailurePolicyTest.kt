package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingV2InboxFailurePolicyTest {
    @Test
    fun `permanent errors dead letter immediately`() {
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_wrong_device", 1))
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_unsupported_ciphertext", 1))
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_sender_key_install_failed", 1))
    }

    @Test
    fun `recoverable errors stay recoverable below the budget`() {
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_no_session", 1))
        assertFalse(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_no_session",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun `recoverable errors dead letter at the budget`() {
        assertTrue(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_no_session",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `unknown codes dead letter exactly at the budget`() {
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_something_else", 0))
        assertTrue(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_something_else",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS,
            ),
        )
    }
}
