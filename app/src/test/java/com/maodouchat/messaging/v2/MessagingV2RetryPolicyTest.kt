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

    @Test
    fun `now offsets the whole schedule unchanged`() {
        assertEquals(6_000L, MessagingV2RetryPolicy.nextAttemptAt(5_000L, 1))
        assertEquals(9_000L, MessagingV2RetryPolicy.nextAttemptAt(5_000L, 2))
    }

    @Test
    fun `backoff doubles each attempt before the cap`() {
        assertEquals(1_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 1))
        assertEquals(2_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 2))
        assertEquals(4_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 3))
        assertEquals(16_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 5))
        assertEquals(32_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 6))
    }

    @Test
    fun `cap starts at attempt ten, not nine`() {
        assertEquals(256_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 9))
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 10))
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 11))
    }

    @Test
    fun `non-positive attempt counts fall back to the base delay`() {
        assertEquals(1_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 0))
        assertEquals(1_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, -3))
    }

    @Test
    fun `huge attempt counts stay capped without overflow`() {
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, Int.MAX_VALUE))
    }
}
