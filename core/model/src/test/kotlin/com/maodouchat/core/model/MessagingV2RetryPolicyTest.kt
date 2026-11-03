package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingV2RetryPolicyTest {
    @Test
    fun `delay starts at one second and doubles`() {
        assertEquals(1_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 1))
        assertEquals(2_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 2))
        assertEquals(256_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 9))
    }

    @Test
    fun `delay caps at five minutes`() {
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 10))
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 100))
    }
}
