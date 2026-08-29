package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagingV2RetryPolicyTest {
    @Test
    fun `retry delay grows exponentially and is capped`() {
        assertEquals(1_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 1))
        assertEquals(8_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 4))
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 20))
    }
}
