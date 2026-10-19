package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2InboxFailurePolicyTest {
    @Test
    fun `wrong-device and unsupported envelopes are terminal immediately`() {
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_wrong_device", 1))
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_unsupported_ciphertext", 1))
    }

    @Test
    fun `missing sessions remain recoverable until bounded retry budget is exhausted`() {
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_no_session", 1))
        assertFalse(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_no_session",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS - 1,
            ),
        )
        assertTrue(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_no_session",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `sender key install failure is terminal on the first sight`() {
        assertTrue(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_sender_key_install_failed",
                1,
            ),
        )
    }

    @Test
    fun `unknown error codes get the full retry budget`() {
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_something_else", 1))
        assertFalse(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_something_else",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS - 1,
            ),
        )
        assertTrue(
            MessagingV2InboxFailurePolicy.shouldDeadLetter(
                "messaging_v2_something_else",
                MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `budget boundary holds at zero and beyond`() {
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_no_session", 0))
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_no_session", 9))
        assertTrue(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_no_session", 100))
    }

    @Test
    fun `permanent error codes match exactly`() {
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_wrong_devic", 1))
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("MESSAGING_V2_WRONG_DEVICE", 1))
        assertFalse(MessagingV2InboxFailurePolicy.shouldDeadLetter("", 1))
    }

    @Test
    fun `dead-letter decision never flips back as attempts grow`() {
        val seen = (1..12).map { attempts ->
            MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_no_session", attempts)
        }
        val firstTrue = seen.indexOfFirst { it }
        assertTrue(firstTrue == MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS - 1)
        assertTrue(seen.drop(firstTrue).all { it })
    }
}
