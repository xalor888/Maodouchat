package com.maodouchat.messaging.v2

import kotlin.math.min

object MessagingV2RetryPolicy {
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 5L * 60L * 1_000L

    fun nextAttemptAt(now: Long, attemptsAfterFailure: Int): Long {
        val exponent = (attemptsAfterFailure - 1).coerceIn(0, 12)
        val delay = min(MAX_DELAY_MS, BASE_DELAY_MS shl exponent)
        return now + delay
    }
}
