package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [MessagingV2RetryPolicy] (checklist line 931, 34th block).
 * Test-only, zero production changes.
 *
 * The oracle is the backoff table written out literally (1000ms doubling,
 * capped at 300_000ms), rather than the production shift expression, so a
 * wrong cap or exponent bound shows up as a mismatch. A Python mini-model
 * differentially checks the table against the shift formula on 5000 random
 * (now, attempts) pairs plus every boundary attempt count (0 diffs).
 */
class MessagingV2RetryPolicyFuzzTest {

    private val delayTable = longArrayOf(
        1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L,
        128_000L, 256_000L, 300_000L, 300_000L, 300_000L, 300_000L
    )

    private fun oracleNextAttemptAt(now: Long, attemptsAfterFailure: Int): Long {
        val exponent = (attemptsAfterFailure - 1).coerceIn(0, 12)
        return now + minOf(300_000L, delayTable[exponent])
    }

    @Test
    fun nextAttemptAtMatchesBackoffTable() {
        val r = Random(3408)
        repeat(300) {
            val now = r.nextLong(-1_000_000_000_000L, 1_000_000_000_000L)
            val attempts = r.nextInt(-100, 100)
            assertEquals(
                oracleNextAttemptAt(now, attempts),
                MessagingV2RetryPolicy.nextAttemptAt(now, attempts)
            )
        }
    }

    @Test
    fun backoffTableBoundariesArePinned() {
        // attempts <= 1 floors at exponent 0; attempts >= 10 hits the cap.
        val cases = mapOf(
            -5 to 1_000L, 0 to 1_000L, 1 to 1_000L, 2 to 2_000L,
            3 to 4_000L, 9 to 256_000L, 10 to 300_000L, 13 to 300_000L,
            100 to 300_000L
        )
        for ((attempts, delay) in cases) {
            assertEquals(delay, MessagingV2RetryPolicy.nextAttemptAt(0L, attempts) - 0L)
            assertEquals(
                1_000_000L + delay,
                MessagingV2RetryPolicy.nextAttemptAt(1_000_000L, attempts)
            )
        }
    }
}
