package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCostVisibilityPolicyTest {

    @Test
    fun `http 429 is rate limited with optional retry after`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 429,
            serverCode = null,
            serverMessage = "AI 请求过于频繁",
            serverRetryAfterSeconds = 45
        )
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertEquals(45L, signal.retryAfterSeconds)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `server rate_limited code maps without relying on status`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 503,
            serverCode = "rate_limited",
            serverMessage = null
        )
        assertTrue(signal.isRateLimited)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `quota codes map to quota exceeded`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 403,
            serverCode = "QUOTA_EXCEEDED",
            serverMessage = "budget exhausted"
        )
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
        assertEquals(
            AiCostVisibilityPolicy.DEFAULT_QUOTA_WAIT_SECONDS,
            AiCostVisibilityPolicy.waitSecondsFor(AiCostVisibilityPolicy.ERROR_QUOTA)
        )
    }

    @Test
    fun `wait seconds prefer server then local remaining`() {
        assertEquals(
            12L,
            AiCostVisibilityPolicy.waitSecondsFor(
                errorCode = AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                serverRetryAfterSeconds = 12,
                localRemainingMs = 90_000
            )
        )
        assertEquals(
            30L,
            AiCostVisibilityPolicy.waitSecondsFor(
                errorCode = AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                localRemainingMs = 29_100
            )
        )
        assertEquals(
            AiCostVisibilityPolicy.DEFAULT_RATE_LIMIT_WAIT_SECONDS,
            AiCostVisibilityPolicy.waitSecondsFor(AiCostVisibilityPolicy.ERROR_RATE_LIMIT)
        )
    }

    @Test
    fun `billing hints cover stream cancel and unknown outcome`() {
        assertEquals(
            AiCostVisibilityPolicy.BillingHint.IN_FLIGHT_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(null, isStreaming = true)
        )
        assertEquals(
            AiCostVisibilityPolicy.BillingHint.CANCELLED_PARTIAL_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_CANCELLED)
        )
        assertEquals(
            AiCostVisibilityPolicy.BillingHint.OUTCOME_UNKNOWN_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("OUTCOME_UNKNOWN")
        )
        assertEquals(
            AiCostVisibilityPolicy.BillingHint.RATE_LIMITED_WAIT,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_RATE_LIMIT)
        )
        assertEquals(
            AiCostVisibilityPolicy.BillingHint.SAFE_AUTO_RETRY,
            AiCostVisibilityPolicy.billingHintFor(
                "CONNECTION_NOT_ESTABLISHED",
                hasScheduledAutoRetry = true
            )
        )
    }

    @Test
    fun `manual retry billing warning is fail closed for unknown outcomes`() {
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("OUTCOME_UNKNOWN"))
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills(AiCostVisibilityPolicy.ERROR_RATE_LIMIT))
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills(AiCostVisibilityPolicy.ERROR_CANCELLED))
        assertFalse(AiCostVisibilityPolicy.shouldWarnRetryBills("CONNECTION_NOT_ESTABLISHED"))
        assertFalse(AiCostVisibilityPolicy.shouldWarnRetryBills(null))
    }

    @Test
    fun `error codes can embed retry after seconds for persistence`() {
        assertEquals(
            "RATE_LIMITED:45",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, 45)
        )
        assertEquals(45L, AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:45"))
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.baseErrorCode("RATE_LIMITED:45")
        )
        assertEquals(
            45L,
            AiCostVisibilityPolicy.waitSecondsFor("RATE_LIMITED:45")
        )
    }
}
