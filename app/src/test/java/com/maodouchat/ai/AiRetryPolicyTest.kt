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
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun remainingDelayIncludesGlobalWindowWhenFull() {
        AiRetryPolicy.clearSession()
        repeat(240) { index ->
            AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT)
        }
        val wait = AiRetryPolicy.remainingDelayMs("fresh-chat", AiRetryPolicyCategory.LIGHT)
        assertTrue("global window should surface a wait, was $wait", wait > 0L)
        assertFalse(AiRetryPolicy.canCallNow("fresh-chat", AiRetryPolicyCategory.LIGHT))
        AiRetryPolicy.clearSession()
    }

    @Test
    fun decideExposesVisibleErrorCodes() {
        val rate = AiRetryPolicy.decide("RATE_LIMITED:45", attempts = 1)
        assertFalse(rate.shouldRetry)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, rate.visibleErrorCode)
        assertTrue(rate.delayMs > 0L)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)

        val payment = AiRetryPolicy.decide("PAYMENT_REQUIRED", attempts = 1)
        assertFalse(payment.shouldRetry)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, payment.visibleErrorCode)

        val unknown = AiRetryPolicy.decide("TIMEOUT", attempts = 1)
        assertFalse(unknown.shouldRetry)
        assertEquals("TIMEOUT", unknown.visibleErrorCode)
    }
}
