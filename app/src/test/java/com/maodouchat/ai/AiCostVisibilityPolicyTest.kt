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
            BillingHint.IN_FLIGHT_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(null, isStreaming = true)
        )
        assertEquals(
            BillingHint.CANCELLED_PARTIAL_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_CANCELLED)
        )
        assertEquals(
            BillingHint.OUTCOME_UNKNOWN_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("OUTCOME_UNKNOWN")
        )
        assertEquals(
            BillingHint.RATE_LIMITED_WAIT,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_RATE_LIMIT)
        )
        assertEquals(
            BillingHint.SAFE_AUTO_RETRY,
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

    @Test
    fun `http 402 and billing phrases are quota not generic failure`() {
        val payment = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 402,
            serverCode = "payment_required",
            serverMessage = "insufficient credit"
        )
        assertTrue(payment.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(payment))
        assertEquals(
            BillingHint.QUOTA_EXCEEDED,
            AiCostVisibilityPolicy.billingHintFor("PAYMENT_REQUIRED")
        )
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("PAYMENT_REQUIRED"))
        val display = AiCostVisibilityPolicy.displaySignalFor("QUOTA_EXCEEDED", isFailed = true)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, display.errorCode)
        assertEquals(AiCostVisibilityPolicy.DEFAULT_QUOTA_WAIT_SECONDS, display.waitSeconds)
        assertEquals(BillingHint.QUOTA_EXCEEDED, display.hint)
        assertTrue(display.warnRetryBills)
    }

    @Test
    fun `non positive retry after is dropped and server value clamped`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 429,
            serverCode = null,
            serverMessage = null,
            serverRetryAfterSeconds = 0
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.retryAfterSeconds == null)
        assertEquals(
            86_400L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                serverRetryAfterSeconds = 100_000
            )
        )
        assertEquals(
            5L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                serverRetryAfterSeconds = -5,
                localRemainingMs = 5000
            )
        )
    }

    @Test
    fun `encode error code only suffixes rate limit family codes`() {
        assertEquals(
            "CANCELLED",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_CANCELLED, 45)
        )
        assertEquals(
            "QUOTA_EXCEEDED:45",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_QUOTA, 45)
        )
        assertEquals(
            "RATE_LIMITED:45",
            AiCostVisibilityPolicy.encodeErrorCode("rate_limited", 45)
        )
        assertEquals(
            "RATE_LIMITED",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, 0)
        )
        assertTrue(AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:0") == null)
        assertEquals(
            86_400L,
            AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:999999")
        )
        assertTrue(AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:") == null)
    }

    @Test
    fun `base error code normalizes case and blank`() {
        assertEquals(
            "RATE_LIMITED",
            AiCostVisibilityPolicy.baseErrorCode("  rate_limited:45 ")
        )
        assertEquals("", AiCostVisibilityPolicy.baseErrorCode(null))
        assertEquals("", AiCostVisibilityPolicy.baseErrorCode("   "))
    }

    @Test
    fun `billing hint priority streaming over cancel and timeout over failed`() {
        assertEquals(
            BillingHint.IN_FLIGHT_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_CANCELLED, isStreaming = true)
        )
        assertEquals(
            BillingHint.OUTCOME_UNKNOWN_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("TIMEOUT", isFailed = true)
        )
        assertEquals(
            BillingHint.RETRY_MAY_BILL_AGAIN,
            AiCostVisibilityPolicy.billingHintFor("SOME_ERROR", isFailed = true)
        )
        assertEquals(BillingHint.NONE, AiCostVisibilityPolicy.billingHintFor("SOME_ERROR"))
    }

    @Test
    fun `display signal binds code wait hint and warn flag`() {
        val signal = AiCostVisibilityPolicy.displaySignalFor("RATE_LIMITED:45", isFailed = true)
        assertEquals("RATE_LIMITED", signal.errorCode)
        assertEquals(45L, signal.waitSeconds)
        assertEquals(BillingHint.RATE_LIMITED_WAIT, signal.hint)
        assertTrue(signal.warnRetryBills)
        val blank = AiCostVisibilityPolicy.displaySignalFor(null)
        assertEquals("", blank.errorCode)
        assertEquals(0L, blank.waitSeconds)
        assertEquals(BillingHint.NONE, blank.hint)
        assertFalse(blank.warnRetryBills)
    }
}
