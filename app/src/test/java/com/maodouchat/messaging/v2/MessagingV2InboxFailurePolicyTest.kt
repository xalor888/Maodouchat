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
}
