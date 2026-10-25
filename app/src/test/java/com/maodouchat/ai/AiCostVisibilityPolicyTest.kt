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

    @Test
    fun `classify english too many phrase without status and clamps retry after`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "too_many_requests",
            serverMessage = "Too many requests",
            serverRetryAfterSeconds = 200_000
        )
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertEquals(86_400L, signal.retryAfterSeconds)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `classify insufficient funds message is quota not generic rate limit`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 200,
            serverCode = "PAYMENT_FAILED",
            serverMessage = "insufficient funds"
        )
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
        assertEquals(
            BillingHint.QUOTA_EXCEEDED,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_QUOTA)
        )
    }

    @Test
    fun `classify generic server error maps to null code`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 500,
            serverCode = "SERVER_ERROR",
            serverMessage = "boom"
        )
        assertFalse(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertTrue(signal.retryAfterSeconds == null)
        assertTrue(AiCostVisibilityPolicy.mapToErrorCode(signal) == null)
    }

    @Test
    fun `billing hint and warn cover rate limit family spellings`() {
        assertEquals(
            BillingHint.RATE_LIMITED_WAIT,
            AiCostVisibilityPolicy.billingHintFor("RATE_LIMIT_SOFT")
        )
        assertEquals(
            BillingHint.RATE_LIMITED_WAIT,
            AiCostVisibilityPolicy.billingHintFor("TOO_MANY_REQUESTS")
        )
        assertEquals(
            BillingHint.QUOTA_EXCEEDED,
            AiCostVisibilityPolicy.billingHintFor("MONTHLY_BUDGET")
        )
        assertEquals(
            BillingHint.OUTCOME_UNKNOWN_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("UNKNOWN")
        )
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("rate_limit_soft"))
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("MONTHLY_BUDGET"))
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("SERVER"))
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("EMPTY_RESULT"))
        assertFalse(AiCostVisibilityPolicy.shouldWarnRetryBills("SOME_ERROR"))
    }

    @Test
    fun `wait seconds local remaining rounds up and clamps`() {
        assertEquals(
            1L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                localRemainingMs = 1
            )
        )
        assertEquals(
            86_400L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                localRemainingMs = 90_000_000
            )
        )
        assertEquals(45L, AiCostVisibilityPolicy.waitSecondsFor("QUOTA_EXCEEDED:45"))
        assertEquals(
            86_400L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                serverRetryAfterSeconds = 86_400
            )
        )
    }

    @Test
    fun `classify quota wins over rate limit when both signals present`() {
        // QUOTA 分支先于限流分支返回：两者都命中时信号按配额算。
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "RATE_LIMITED",
            serverMessage = "quota exceeded"
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.isQuota)
        assertTrue(signal.retryAfterSeconds == null)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_QUOTA,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `classify chinese too frequent message alone is rate limited`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "AI 请求过于频繁，请稍后再试"
        )
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertTrue(signal.retryAfterSeconds == null)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `classify insufficient without fund credit or balance is generic`() {
        // INSUFFICIENT 只有配 FUND/CREDIT/BALANCE 任一词才算配额：纯权限不足是通用失败。
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 403,
            serverCode = "PERMISSION_DENIED",
            serverMessage = "insufficient permissions"
        )
        assertFalse(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertTrue(signal.retryAfterSeconds == null)
        assertTrue(AiCostVisibilityPolicy.mapToErrorCode(signal) == null)
    }

    @Test
    fun `wait seconds priority server over embedded over local over default`() {
        assertEquals(
            10L,
            AiCostVisibilityPolicy.waitSecondsFor(
                "RATE_LIMITED:45",
                serverRetryAfterSeconds = 10,
                localRemainingMs = 90_000
            )
        )
        assertEquals(
            45L,
            AiCostVisibilityPolicy.waitSecondsFor("RATE_LIMITED:45", localRemainingMs = 90_000)
        )
        assertEquals(5L, AiCostVisibilityPolicy.waitSecondsFor("CANCELLED", localRemainingMs = 5000))
        assertEquals(0L, AiCostVisibilityPolicy.waitSecondsFor("SOME_UNKNOWN"))
        assertEquals(0L, AiCostVisibilityPolicy.waitSecondsFor(null))
    }

    @Test
    fun `display signal streaming quota binds server wait and flight hint`() {
        val streaming = AiCostVisibilityPolicy.displaySignalFor(
            "QUOTA_EXCEEDED",
            isStreaming = true,
            serverRetryAfterSeconds = 120
        )
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, streaming.errorCode)
        assertEquals(120L, streaming.waitSeconds)
        assertEquals(BillingHint.IN_FLIGHT_MAY_BILL, streaming.hint)
        assertTrue(streaming.warnRetryBills)
        val embedded = AiCostVisibilityPolicy.displaySignalFor("QUOTA_EXCEEDED:90")
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, embedded.errorCode)
        assertEquals(90L, embedded.waitSeconds)
        assertEquals(BillingHint.QUOTA_EXCEEDED, embedded.hint)
        assertTrue(embedded.warnRetryBills)
    }
}
