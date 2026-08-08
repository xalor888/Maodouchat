package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRetryPolicyTest {
    @Test
    fun connectionNotEstablishedRetriesWithBackoff() {
        val firstFailure = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 1)
        val secondFailure = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 2)

        assertTrue(firstFailure.shouldRetry)
        assertEquals(800L, firstFailure.delayMs)
        assertTrue(secondFailure.shouldRetry)
        assertEquals(1_600L, secondFailure.delayMs)
    }

    @Test
    fun uncertainFailuresNeverRetryAutomatically() {
        listOf("TIMEOUT", "OUTCOME_UNKNOWN", "UNKNOWN", "INVALID_RESPONSE", "INTERRUPTED")
            .forEach { errorCode ->
                assertFalse(errorCode, AiRetryPolicy.decide(errorCode, attempts = 1).shouldRetry)
            }
    }

    @Test
    fun policyFailuresAndAttemptCapNeverRetryAutomatically() {
        listOf("AUTH_REQUIRED", "QUOTA_EXCEEDED", "RATE_LIMITED", "BUDGET_EXCEEDED")
            .forEach { errorCode ->
                assertFalse(errorCode, AiRetryPolicy.decide(errorCode, attempts = 1).shouldRetry)
            }
        assertFalse(AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 3).shouldRetry)
    }

    @Test
    fun clearSessionDropsPerChatAndGlobalWindows() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicy.Category.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicy.Category.LIGHT))
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicy.Category.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicy.Category.LIGHT))
    }
}
