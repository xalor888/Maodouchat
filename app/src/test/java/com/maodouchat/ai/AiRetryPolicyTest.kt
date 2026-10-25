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

    @Test
    fun decideNullAndBlankCodesResolveToUnknownWithoutRetry() {
        val nullCode = AiRetryPolicy.decide(null, attempts = 1)
        val blankCode = AiRetryPolicy.decide("   ", attempts = 1)

        listOf(nullCode, blankCode).forEach { decision ->
            assertFalse(decision.shouldRetry)
            assertEquals(0L, decision.delayMs)
            assertEquals("UNKNOWN", decision.visibleErrorCode)
        }
    }

    @Test
    fun decideAuthFlavorNamesAreRejectedBeforeRetry() {
        val invalidToken = AiRetryPolicy.decide("INVALID_AUTH_TOKEN", attempts = 1)
        assertFalse(invalidToken.shouldRetry)
        assertEquals(0L, invalidToken.delayMs)
        assertEquals("INVALID_AUTH_TOKEN", invalidToken.visibleErrorCode)

        val unauthorized = AiRetryPolicy.decide("UNAUTHORIZED", attempts = 1)
        assertFalse(unauthorized.shouldRetry)
        assertEquals(0L, unauthorized.delayMs)
    }

    @Test
    fun decideBillingAndInsufficientFlavorsMapToQuotaSignal() {
        val billing = AiRetryPolicy.decide("BILLING_ERROR", attempts = 1)
        assertFalse(billing.shouldRetry)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, billing.visibleErrorCode)
        assertEquals(0L, billing.delayMs)

        val insufficient = AiRetryPolicy.decide("INSUFFICIENT_FUNDS", attempts = 1)
        assertFalse(insufficient.shouldRetry)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, insufficient.visibleErrorCode)
    }

    @Test
    fun decideRetryAfterSuffixFlowsIntoRateLimitDelay() {
        val limited = AiRetryPolicy.decide("RATE_LIMITED:120", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(120_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val tooMany = AiRetryPolicy.decide("TOO_MANY_REQUESTS:30", attempts = 1)
        assertFalse(tooMany.shouldRetry)
        assertEquals(30_000L, tooMany.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, tooMany.visibleErrorCode)
    }

    @Test
    fun decideConnectionRetryBeyondMaxAttemptsStopsRetrying() {
        // attempts≥3 已达最大自动重试次数：不再重试，delay 归零。
        listOf(7, 10).forEach { attempts ->
            val decision = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = attempts)
            assertFalse(decision.shouldRetry)
            assertEquals(0L, decision.delayMs)
        }
    }

    @Test
    fun categoriesHaveIndependentPerChatWindows() {
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-x", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-x", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-x", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-y", AiRetryPolicyCategory.HEAVY))
        AiRetryPolicy.clearSession()
    }

    @Test
    fun perChatWindowsAreIndependentAcrossChats() {
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-b", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-b", AiRetryPolicyCategory.LIGHT))
        AiRetryPolicy.clearSession()
    }

    @Test
    fun nonTransientFailuresNeverRetryAtAnyAttempt() {
        listOf("TIMEOUT", "OUTCOME_UNKNOWN", "INVALID_RESPONSE").forEach { errorCode ->
            val decision = AiRetryPolicy.decide(errorCode, attempts = 2)
            assertFalse(errorCode, decision.shouldRetry)
            assertEquals(0L, decision.delayMs)
        }
    }

    @Test
    fun rateLimitSurfacesServerWaitAndVisibleCode() {
        val withServerWait = AiRetryPolicy.decide("RATE_LIMITED:45", attempts = 1)
        assertFalse(withServerWait.shouldRetry)
        assertEquals(45_000L, withServerWait.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, withServerWait.visibleErrorCode)

        val defaultWait = AiRetryPolicy.decide("RATE_LIMITED", attempts = 1)
        assertFalse(defaultWait.shouldRetry)
        assertEquals(30_000L, defaultWait.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, defaultWait.visibleErrorCode)
    }

    @Test
    fun zeroAttemptsStartFromBaseBackoff() {
        val first = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 0)
        assertTrue(first.shouldRetry)
        assertEquals(800L, first.delayMs)
    }

    @Test
    fun lowercaseCodesNormalizeBeforeBranchMatching() {
        // 基码归一化会大写：小写输入同样走限流/配额分支。
        val limited = AiRetryPolicy.decide("rate_limited:45", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(45_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("quota_exceeded", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(300_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun tooManyWithoutSuffixReportsRateLimitSignalWithZeroWait() {
        // TOO_MANY 命中限流分支，但等待秒数按基码查表：裸 TOO_MANY_REQUESTS 不在表里，等待为 0。
        val tooMany = AiRetryPolicy.decide("TOO_MANY_REQUESTS", attempts = 1)
        assertFalse(tooMany.shouldRetry)
        assertEquals(0L, tooMany.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, tooMany.visibleErrorCode)
    }

    @Test
    fun quotaSuffixFlowsIntoQuotaWait() {
        val withSuffix = AiRetryPolicy.decide("QUOTA_EXCEEDED:90", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(90_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, withSuffix.visibleErrorCode)

        val bare = AiRetryPolicy.decide("QUOTA_EXCEEDED", attempts = 1)
        assertFalse(bare.shouldRetry)
        assertEquals(300_000L, bare.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, bare.visibleErrorCode)
    }

    @Test
    fun attemptCapBeatsBranchMapping() {
        // attempts≥3 先于分支判定返回：后缀秒数不再生效，等待归零。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:45", attempts = 3)
        assertFalse(limited.shouldRetry)
        assertEquals(0L, limited.delayMs)
        assertEquals("RATE_LIMITED", limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:90", attempts = 3)
        assertFalse(quota.shouldRetry)
        assertEquals(0L, quota.delayMs)
        assertEquals("QUOTA_EXCEEDED", quota.visibleErrorCode)
    }

    @Test
    fun authBranchWinsOverQuotaBranch() {
        // 分支顺序：限流 → 认证 → 配额，同时含 AUTH 与 QUOTA 的码走认证分支。
        listOf("AUTH_QUOTA", "QUOTA_AUTH").forEach { errorCode ->
            val decision = AiRetryPolicy.decide(errorCode, attempts = 1)
            assertFalse(errorCode, decision.shouldRetry)
            assertEquals(0L, decision.delayMs)
            assertEquals(errorCode, decision.visibleErrorCode)
        }
    }
}
