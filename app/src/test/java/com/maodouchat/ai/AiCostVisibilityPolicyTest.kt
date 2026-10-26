package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `classify bare 402 status is quota without code or message`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 402,
            serverCode = null,
            serverMessage = null
        )
        assertTrue(signal.isQuota)
        assertTrue(signal.isRateLimited)
        assertTrue(signal.retryAfterSeconds == null)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `classify message only too many requests is rate limited`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "Too many requests, try later"
        )
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertTrue(signal.retryAfterSeconds == null)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `classify billing code is quota`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "billing_issue",
            serverMessage = null
        )
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostVisibilityPolicy.billingHintFor("BILLING_ISSUE"))
    }

    @Test
    fun `embedded retry after rejects non numeric and leading colon`() {
        assertTrue(AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:abc") == null)
        assertTrue(AiCostVisibilityPolicy.embeddedRetryAfterSeconds(":45") == null)
        assertEquals(45L, AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED: 45"))
    }

    @Test
    fun `display signal binds auto retry and timeout outcome`() {
        val auto = AiCostVisibilityPolicy.displaySignalFor(
            "CONNECTION_NOT_ESTABLISHED",
            hasScheduledAutoRetry = true
        )
        assertEquals("CONNECTION_NOT_ESTABLISHED", auto.errorCode)
        assertEquals(0L, auto.waitSeconds)
        assertEquals(BillingHint.SAFE_AUTO_RETRY, auto.hint)
        assertFalse(auto.warnRetryBills)
        val timeout = AiCostVisibilityPolicy.displaySignalFor("TIMEOUT", isFailed = true)
        assertEquals("TIMEOUT", timeout.errorCode)
        assertEquals(0L, timeout.waitSeconds)
        assertEquals(BillingHint.OUTCOME_UNKNOWN_MAY_BILL, timeout.hint)
        assertTrue(timeout.warnRetryBills)
    }


    @Test
    fun `classify negative retry after drops and budget message is quota`() {
        val negative = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 429,
            serverCode = null,
            serverMessage = null,
            serverRetryAfterSeconds = -10
        )
        assertTrue(negative.isRateLimited)
        assertFalse(negative.isQuota)
        assertTrue(negative.retryAfterSeconds == null)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.mapToErrorCode(negative)
        )
        val budget = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "budget exhausted"
        )
        assertTrue(budget.isQuota)
        assertTrue(budget.isRateLimited)
        assertTrue(budget.retryAfterSeconds == null)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(budget))
    }

    @Test
    fun `embedded retry after rejects multi colon suffix`() {
        assertTrue(AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:45:60") == null)
        assertEquals(90L, AiCostVisibilityPolicy.embeddedRetryAfterSeconds("QUOTA_EXCEEDED:90"))
        assertEquals(45L, AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:45 "))
    }

    @Test
    fun `encode error code handles blank base and rate limit prefix family`() {
        assertEquals("", AiCostVisibilityPolicy.encodeErrorCode("", 45))
        assertEquals(
            "RATE_LIMIT_SOFT:30",
            AiCostVisibilityPolicy.encodeErrorCode("rate_limit_soft", 30)
        )
        assertEquals(
            "RATE_LIMITED",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, null)
        )
    }

    @Test
    fun `base error code multi colon and colon only edges`() {
        assertEquals(
            "RATE_LIMITED",
            AiCostVisibilityPolicy.baseErrorCode("RATE_LIMITED:45:60")
        )
        assertEquals(":", AiCostVisibilityPolicy.baseErrorCode(":"))
        assertEquals(":45", AiCostVisibilityPolicy.baseErrorCode(" :45"))
    }

    @Test
    fun `wait seconds negative local remaining falls through to default`() {
        assertEquals(
            AiCostVisibilityPolicy.DEFAULT_RATE_LIMIT_WAIT_SECONDS,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                localRemainingMs = -5000
            )
        )
        assertEquals(
            AiCostVisibilityPolicy.DEFAULT_QUOTA_WAIT_SECONDS,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_QUOTA,
                localRemainingMs = -1
            )
        )
        assertEquals(
            0L,
            AiCostVisibilityPolicy.waitSecondsFor(
                null,
                serverRetryAfterSeconds = -3,
                localRemainingMs = -1
            )
        )
    }

    @Test
    fun `encode error code clamps seconds at one day`() {
        assertEquals(
            "RATE_LIMITED:86400",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, 100_000)
        )
        assertEquals(
            "QUOTA_EXCEEDED:86400",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_QUOTA, 200_000)
        )
    }

    @Test
    fun `classify rate limited code with surrounding whitespace still maps`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "  RATE_LIMITED  ",
            serverMessage = null
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
    fun `display signal for cancelled binds partial hint and warn`() {
        val signal = AiCostVisibilityPolicy.displaySignalFor(AiCostVisibilityPolicy.ERROR_CANCELLED)
        assertEquals(AiCostVisibilityPolicy.ERROR_CANCELLED, signal.errorCode)
        assertEquals(0L, signal.waitSeconds)
        assertEquals(BillingHint.CANCELLED_PARTIAL_MAY_BILL, signal.hint)
        assertTrue(signal.warnRetryBills)
    }

    @Test
    fun `billing hint quota family beats scheduled auto retry`() {
        assertEquals(
            BillingHint.QUOTA_EXCEEDED,
            AiCostVisibilityPolicy.billingHintFor(
                AiCostVisibilityPolicy.ERROR_QUOTA,
                hasScheduledAutoRetry = true
            )
        )
    }

    @Test
    fun `retry warning covers invalid response and interrupted outcomes`() {
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("INVALID_RESPONSE"))
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("INTERRUPTED"))
        assertEquals(
            BillingHint.OUTCOME_UNKNOWN_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("INTERRUPTED")
        )
    }

    @Test
    fun `classify 429 with quota message maps to quota`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 429,
            serverCode = null,
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
    fun `classify spaced rate limited message is rate limited`() {
        val message = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "Rate limited for now"
        )
        assertTrue(message.isRateLimited)
        assertFalse(message.isQuota)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.mapToErrorCode(message)
        )
        val code = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "rate limited",
            serverMessage = null
        )
        assertTrue(code.isRateLimited)
        assertFalse(code.isQuota)
    }

    @Test
    fun `encode drops negative seconds and trims quota base`() {
        assertEquals(
            AiCostVisibilityPolicy.ERROR_QUOTA,
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_QUOTA, -5)
        )
        assertEquals(
            "QUOTA_EXCEEDED:30",
            AiCostVisibilityPolicy.encodeErrorCode("  quota_exceeded  ", 30)
        )
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, -1)
        )
    }

    @Test
    fun `wait seconds skips non-positive server and zero local values`() {
        assertEquals(
            45L,
            AiCostVisibilityPolicy.waitSecondsFor("RATE_LIMITED:45", serverRetryAfterSeconds = -5)
        )
        assertEquals(
            90L,
            AiCostVisibilityPolicy.waitSecondsFor("QUOTA_EXCEEDED:90", serverRetryAfterSeconds = 0)
        )
        assertEquals(
            AiCostVisibilityPolicy.DEFAULT_RATE_LIMIT_WAIT_SECONDS,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                localRemainingMs = 0
            )
        )
        assertEquals(
            AiCostVisibilityPolicy.DEFAULT_QUOTA_WAIT_SECONDS,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_QUOTA,
                localRemainingMs = 0
            )
        )
    }

    @Test
    fun `display signal binds server and local waits for unknown codes`() {
        val server = AiCostVisibilityPolicy.displaySignalFor("SOME_ERROR", serverRetryAfterSeconds = 20)
        assertEquals("SOME_ERROR", server.errorCode)
        assertEquals(20L, server.waitSeconds)
        assertEquals(BillingHint.NONE, server.hint)
        assertFalse(server.warnRetryBills)
        val local = AiCostVisibilityPolicy.displaySignalFor("SOME_ERROR", localRemainingMs = 2500)
        assertEquals(3L, local.waitSeconds)
        assertEquals(BillingHint.NONE, local.hint)
        val mixed = AiCostVisibilityPolicy.displaySignalFor(
            "SOME_ERROR",
            serverRetryAfterSeconds = -5,
            localRemainingMs = 1500
        )
        assertEquals(2L, mixed.waitSeconds)
    }


    @Test
    fun `classify insufficient balance message is quota`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "insufficient balance"
        )
        assertTrue(signal.isQuota)
        assertTrue(signal.isRateLimited)
        assertTrue(signal.retryAfterSeconds == null)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_QUOTA,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `classify spaced payment required message alone is generic`() {
        // 下划线形 payment_required 算配额，空格形 payment required 不算：blob 只认下划线拼写。
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "payment required"
        )
        assertFalse(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertTrue(signal.retryAfterSeconds == null)
        assertTrue(AiCostVisibilityPolicy.mapToErrorCode(signal) == null)
    }

    @Test
    fun `billing hint and warn cover payment failed code`() {
        assertEquals(
            BillingHint.QUOTA_EXCEEDED,
            AiCostVisibilityPolicy.billingHintFor("PAYMENT_FAILED")
        )
        assertEquals(
            BillingHint.QUOTA_EXCEEDED,
            AiCostVisibilityPolicy.billingHintFor("payment_failed", isFailed = true)
        )
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("PAYMENT_FAILED"))
    }

    @Test
    fun `encode drops suffix for too many code but embedded still reads it`() {
        assertEquals(
            "TOO_MANY_REQUESTS",
            AiCostVisibilityPolicy.encodeErrorCode("TOO_MANY_REQUESTS", 45)
        )
        assertEquals(
            "TOO_MANY_REQUESTS",
            AiCostVisibilityPolicy.encodeErrorCode("too_many_requests", 45)
        )
        assertEquals(45L, AiCostVisibilityPolicy.embeddedRetryAfterSeconds("TOO_MANY_REQUESTS:45"))
        assertEquals(45L, AiCostVisibilityPolicy.waitSecondsFor("TOO_MANY_REQUESTS:45"))
    }

    @Test
    fun `classify 402 with retry after keeps retry and maps quota`() {
        val first = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 402,
            serverCode = null,
            serverMessage = null,
            serverRetryAfterSeconds = 30
        )
        val second = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 402,
            serverCode = null,
            serverMessage = null,
            serverRetryAfterSeconds = 30
        )
        assertEquals(first, second)
        assertTrue(first.isQuota)
        assertTrue(first.isRateLimited)
        assertEquals(30L, first.retryAfterSeconds)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_QUOTA,
            AiCostVisibilityPolicy.mapToErrorCode(first)
        )
        assertEquals(
            30L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_QUOTA,
                serverRetryAfterSeconds = 30
            )
        )
    }

    @Test
    fun `classify generic failure drops server retry after and maps to null`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 500,
            serverCode = null,
            serverMessage = "boom",
            serverRetryAfterSeconds = 30
        )
        assertFalse(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertEquals(null, signal.retryAfterSeconds)
        assertEquals(null, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `billing hint failed server code retries with bill again warning`() {
        assertEquals(
            BillingHint.RETRY_MAY_BILL_AGAIN,
            AiCostVisibilityPolicy.billingHintFor("SERVER", isFailed = true)
        )
        assertEquals(
            BillingHint.NONE,
            AiCostVisibilityPolicy.billingHintFor(null, isFailed = true)
        )
        assertEquals(
            BillingHint.SAFE_AUTO_RETRY,
            AiCostVisibilityPolicy.billingHintFor("SERVER", hasScheduledAutoRetry = true)
        )
        assertTrue(AiCostVisibilityPolicy.shouldWarnRetryBills("SERVER"))
    }

    @Test
    fun `wait seconds embedded beats local remaining`() {
        assertEquals(
            60L,
            AiCostVisibilityPolicy.waitSecondsFor("QUOTA_EXCEEDED:60", localRemainingMs = 5000)
        )
        assertEquals(
            45L,
            AiCostVisibilityPolicy.waitSecondsFor("RATE_LIMITED:45", localRemainingMs = 99999)
        )
    }

    @Test
    fun `encode error code drops suffix for non rate quota family`() {
        assertEquals(
            "CANCELLED",
            AiCostVisibilityPolicy.encodeErrorCode(AiCostVisibilityPolicy.ERROR_CANCELLED, 45)
        )
        assertEquals(
            "OUTCOME_UNKNOWN",
            AiCostVisibilityPolicy.encodeErrorCode("OUTCOME_UNKNOWN", 45)
        )
    }

    @Test
    fun `classify chinese too frequent in code field alone is not rate limited`() {
        // 中文“过于频繁”只在文案字段里识别，code 字段里的不算。
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "过于频繁",
            serverMessage = null
        )
        assertFalse(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertEquals(null, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }


    @Test
    fun `classify billing message alone is quota`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 500,
            serverCode = null,
            serverMessage = "billing failed"
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `classify too many code field is rate limited`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 500,
            serverCode = "TOO_MANY_REQUESTS",
            serverMessage = null
        )
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `embedded retry after exactly at day cap passes through`() {
        assertEquals(86_400L, AiCostVisibilityPolicy.embeddedRetryAfterSeconds("RATE_LIMITED:86400"))
    }

    @Test
    fun `wait seconds local remaining just over one second rounds up to two`() {
        assertEquals(
            2L,
            AiCostVisibilityPolicy.waitSecondsFor(
                AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                localRemainingMs = 1001
            )
        )
    }

    @Test
    fun `classify quota code with surrounding whitespace still maps`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "  quota_exceeded  ",
            serverMessage = null
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `classify 402 with plain message is quota even without quota words`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = 402,
            serverCode = null,
            serverMessage = "plain"
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.isQuota)
        assertNull(signal.retryAfterSeconds)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `classify insufficient funds wording in message is quota`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = null,
            serverMessage = "Insufficient funds"
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `wait seconds server retry after zero falls through to embedded seconds`() {
        assertEquals(
            120L,
            AiCostVisibilityPolicy.waitSecondsFor(
                "RATE_LIMITED:120",
                serverRetryAfterSeconds = 0
            )
        )
    }

    @Test
    fun `billing hint streaming beats cancelled code`() {
        assertEquals(
            BillingHint.IN_FLIGHT_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("CANCELLED", isStreaming = true)
        )
        assertEquals(
            BillingHint.CANCELLED_PARTIAL_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor("CANCELLED")
        )
    }

    @Test
    fun `encode quota zero seconds drops suffix and unknown base keeps clean`() {
        assertEquals("QUOTA_EXCEEDED", AiCostVisibilityPolicy.encodeErrorCode("QUOTA_EXCEEDED", 0))
        assertEquals("TIMEOUT", AiCostVisibilityPolicy.encodeErrorCode("timeout", 45))
        assertEquals("RATE_LIMITED:90", AiCostVisibilityPolicy.encodeErrorCode(" rate_limited ", 90))
    }

    @Test
    fun `classify underscored payment required code is quota`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "payment_required",
            serverMessage = null
        )
        assertTrue(signal.isRateLimited)
        assertTrue(signal.isQuota)
        assertNull(signal.retryAfterSeconds)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_QUOTA,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `classify mixed case too many code folds to rate limited`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = null,
            serverCode = "Too_Many_Requests",
            serverMessage = null
        )
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertNull(signal.retryAfterSeconds)
        assertEquals(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
            AiCostVisibilityPolicy.mapToErrorCode(signal)
        )
    }

    @Test
    fun `wait seconds unknown code with zero server and zero local returns zero`() {
        assertEquals(
            0L,
            AiCostVisibilityPolicy.waitSecondsFor(
                "SOMETHING_ELSE",
                serverRetryAfterSeconds = 0,
                localRemainingMs = 0
            )
        )
        assertEquals(
            0L,
            AiCostVisibilityPolicy.waitSecondsFor(
                "SOMETHING_ELSE",
                serverRetryAfterSeconds = -10,
                localRemainingMs = -5
            )
        )
    }

    @Test
    fun `encode error code re-suffixes a suffixed rate base`() {
        assertEquals("RATE_LIMITED:90", AiCostVisibilityPolicy.encodeErrorCode("RATE_LIMITED:45", 90))
        assertEquals("QUOTA_EXCEEDED", AiCostVisibilityPolicy.encodeErrorCode("QUOTA_EXCEEDED:30", 0))
    }

    @Test
    fun `billing hint blank code with failed flag stays none`() {
        assertEquals(BillingHint.NONE, AiCostVisibilityPolicy.billingHintFor("", isFailed = true))
        assertEquals(BillingHint.NONE, AiCostVisibilityPolicy.billingHintFor(null, isFailed = true))
    }

}
