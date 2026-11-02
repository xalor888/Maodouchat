package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRetryPolicyTest {

    @Test
    fun connectionFailureRetriesWithExponentialBackoff() {
        val first = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 1)
        assertTrue(first.shouldRetry)
        assertEquals(800L, first.delayMs)
        val second = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 2)
        assertTrue(second.shouldRetry)
        assertEquals(1_600L, second.delayMs)
    }

    @Test
    fun attemptCapStopsRetries() {
        val decision = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 3)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun rateLimitedFailureDoesNotRetryButCarriesWait() {
        val decision = AiRetryPolicy.decide("RATE_LIMITED", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertTrue(decision.delayMs > 0L)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun authFailureDoesNotRetry() {
        val decision = AiRetryPolicy.decide("UNAUTHORIZED", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
    }
}
