package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(7_000L, MessagingV2RetryPolicy.nextAttemptAt(5_000L, 2))
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

    @Test
    fun `attempts seven and eight fill the uncapped middle`() {
        assertEquals(64_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 7))
        assertEquals(128_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 8))
    }

    @Test
    fun `negative now offsets the whole schedule`() {
        assertEquals(-4_000L, MessagingV2RetryPolicy.nextAttemptAt(-5_000L, 1))
        assertEquals(-44_000L, MessagingV2RetryPolicy.nextAttemptAt(-300_000L, 9))
        assertEquals(0L, MessagingV2RetryPolicy.nextAttemptAt(-300_000L, 10))
    }

    @Test
    fun `first attempt always yields the base delay on top of now`() {
        listOf(0L, 5_000L, 1_000_000L).forEach { now ->
            assertEquals(now + 1_000L, MessagingV2RetryPolicy.nextAttemptAt(now, 1))
        }
    }

    @Test
    fun `delays never shrink as attempts grow`() {
        (1..20).forEach { attempt ->
            val current = MessagingV2RetryPolicy.nextAttemptAt(0L, attempt)
            val next = MessagingV2RetryPolicy.nextAttemptAt(0L, attempt + 1)
            assertTrue(next >= current, "attempt $attempt delay must not shrink")
        }
    }

    @Test
    fun `capped delay stays at the five-minute ceiling past the clamp`() {
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 14))
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 100))
    }

    @Test
    fun `capped delay offsets a negative now`() {
        assertEquals(-700_000L, MessagingV2RetryPolicy.nextAttemptAt(-1_000_000L, 20))
        assertEquals(-200_000L, MessagingV2RetryPolicy.nextAttemptAt(-500_000L, 14))
    }

    @Test
    fun `base delay applies on negative nows too`() {
        listOf(-1L, -1_000L, -999_999L).forEach { now ->
            assertEquals(now + 1_000L, MessagingV2RetryPolicy.nextAttemptAt(now, 1))
        }
    }

    @Test
    fun `delays strictly increase until the cap then stay flat`() {
        (1..8).forEach { attempt ->
            val current = MessagingV2RetryPolicy.nextAttemptAt(0L, attempt)
            val next = MessagingV2RetryPolicy.nextAttemptAt(0L, attempt + 1)
            assertTrue(next > current, "attempt $attempt delay must strictly grow")
        }
        (10..20).forEach { attempt ->
            assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, attempt))
        }
    }

    @Test
    fun `zero and negative attempts share the base delay at any now`() {
        assertEquals(8_000L, MessagingV2RetryPolicy.nextAttemptAt(7_000L, 0))
        assertEquals(8_000L, MessagingV2RetryPolicy.nextAttemptAt(7_000L, -100))
        assertEquals(-1_000L, MessagingV2RetryPolicy.nextAttemptAt(-2_000L, -1))
    }

    @Test
    fun `last doubling before the cap lands on attempt nine`() {
        assertEquals(128_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 8))
        assertEquals(256_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 9))
        assertEquals(300_000L, MessagingV2RetryPolicy.nextAttemptAt(0L, 10))
    }
}
