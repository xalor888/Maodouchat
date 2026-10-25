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

    @Test
    fun decideNegativeAttemptsTreatedAsFirstRetry() {
        // attempts 为负时 (attempts - 1).coerceAtLeast(0) 归零：按首次重试计 800ms。
        val decision = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = -1)
        assertTrue(decision.shouldRetry)
        assertEquals(800L, decision.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun decideRateLimitBranchWinsOverAuthBranch() {
        // 分支顺序限流在前：同时含限流与认证特征的码走限流分支。
        listOf("RATE_LIMITED_AUTH", "TOO_MANY_AUTH").forEach { errorCode ->
            val decision = AiRetryPolicy.decide(errorCode, attempts = 1)
            assertFalse(errorCode, decision.shouldRetry)
            assertEquals(errorCode, 0L, decision.delayMs)
            assertEquals(errorCode, AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
        }
    }

    @Test
    fun decideBudgetFlavorWithSuffixFlowsIntoQuotaWait() {
        // BUDGET 走配额分支：裸码等待为 0，内嵌秒数按后缀计费等待。
        val bare = AiRetryPolicy.decide("BUDGET_EXCEEDED", attempts = 1)
        assertFalse(bare.shouldRetry)
        assertEquals(0L, bare.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, bare.visibleErrorCode)

        val withSuffix = AiRetryPolicy.decide("BUDGET_EXCEEDED:60", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(60_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, withSuffix.visibleErrorCode)
    }

    @Test
    fun decideExplanationTextsAreStablePerBranch() {
        assertEquals("rate-limited; ask user to wait",
            AiRetryPolicy.decide("RATE_LIMITED", attempts = 1).explanation)
        assertEquals("auth required; do not auto-retry",
            AiRetryPolicy.decide("UNAUTHORIZED", attempts = 1).explanation)
        assertEquals("quota exceeded; do not auto-retry",
            AiRetryPolicy.decide("QUOTA_EXCEEDED", attempts = 1).explanation)
        assertEquals("connection was not established; safe to retry",
            AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 1).explanation)
        assertEquals("reached max auto retries; user must retry",
            AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 3).explanation)
        assertEquals("non-transient error",
            AiRetryPolicy.decide("TIMEOUT", attempts = 1).explanation)
    }

    @Test
    fun decideWhitespaceAroundCodeIsTrimmedBeforeBranchMatching() {
        // 基码解析先 trim：首尾空白 + 小写不影响分支与内嵌秒数。
        val limited = AiRetryPolicy.decide("  RATE_LIMITED:45  ", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(45_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide(" quota_exceeded ", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(300_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun decideRateLimitBranchWinsOverQuotaBranch() {
        // 限流分支在前：同时含限流与配额特征的码走限流分支，等待按基码查表（无内嵌秒数、无默认码，等待为 0）。
        listOf("RATE_LIMITED_QUOTA", "TOO_MANY_QUOTA").forEach { errorCode ->
            val decision = AiRetryPolicy.decide(errorCode, attempts = 1)
            assertFalse(errorCode, decision.shouldRetry)
            assertEquals(errorCode, 0L, decision.delayMs)
            assertEquals(errorCode, AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
        }
    }

    @Test
    fun decidePaymentFlavorWithSuffixFlowsIntoQuotaWait() {
        val withSuffix = AiRetryPolicy.decide("PAYMENT_REQUIRED:120", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(120_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, withSuffix.visibleErrorCode)

        val bare = AiRetryPolicy.decide("PAYMENT_REQUIRED", attempts = 1)
        assertFalse(bare.shouldRetry)
        assertEquals(0L, bare.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, bare.visibleErrorCode)
    }

    @Test
    fun decideCapCaseKeepsNormalizedCodeAsVisibleError() {
        val nullCode = AiRetryPolicy.decide(null, attempts = 3)
        assertFalse(nullCode.shouldRetry)
        assertEquals(0L, nullCode.delayMs)
        assertEquals("UNKNOWN", nullCode.visibleErrorCode)
        assertEquals("reached max auto retries; user must retry", nullCode.explanation)

        val timeout = AiRetryPolicy.decide("TIMEOUT", attempts = 3)
        assertFalse(timeout.shouldRetry)
        assertEquals(0L, timeout.delayMs)
        assertEquals("TIMEOUT", timeout.visibleErrorCode)
    }

    @Test
    fun decideEmptyCodeResolvesToUnknown() {
        val decision = AiRetryPolicy.decide("", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("UNKNOWN", decision.visibleErrorCode)
        assertEquals("non-transient error", decision.explanation)
    }

    @Test
    fun heavyPerChatWindowBlocksAndReportsWait() {
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-h", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-h", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.remainingDelayMs("chat-h", AiRetryPolicyCategory.HEAVY) > 0L)
        assertTrue(AiRetryPolicy.canCallNow("chat-h", AiRetryPolicyCategory.LIGHT))
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("chat-h", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-h", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun decideNonPositiveSuffixFallsBackToBranchDefault() {
        // 内嵌秒数只取正数：非数字、0 与负数后缀都落回分支默认等待。
        val badNumber = AiRetryPolicy.decide("RATE_LIMITED:abc", attempts = 1)
        assertFalse(badNumber.shouldRetry)
        assertEquals(30_000L, badNumber.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, badNumber.visibleErrorCode)

        val zero = AiRetryPolicy.decide("RATE_LIMITED:0", attempts = 1)
        assertEquals(30_000L, zero.delayMs)

        val negative = AiRetryPolicy.decide("RATE_LIMITED:-5", attempts = 1)
        assertEquals(30_000L, negative.delayMs)

        val quotaBad = AiRetryPolicy.decide("QUOTA_EXCEEDED:abc", attempts = 1)
        assertFalse(quotaBad.shouldRetry)
        assertEquals(300_000L, quotaBad.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quotaBad.visibleErrorCode)
    }

    @Test
    fun decideOversizedSuffixIsCappedAtOneDay() {
        // 内嵌秒数上限 86400 秒：超大后缀按一天计等待。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:999999", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(86_400_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:100000", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(86_400_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun decideConnectionFailureWithSuffixStillRetries() {
        // 基码解析先截掉冒号后缀：带后缀的连接失败仍是可重试分支，后缀秒数不生效。
        val suffixed = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED:30", attempts = 1)
        assertTrue(suffixed.shouldRetry)
        assertEquals(800L, suffixed.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", suffixed.visibleErrorCode)

        val lower = AiRetryPolicy.decide("connection_not_established:30", attempts = 2)
        assertTrue(lower.shouldRetry)
        assertEquals(1_600L, lower.delayMs)
    }

    @Test
    fun decideUnknownFamilyPinsFullDecision() {
        // 非瞬态分支此前只断言不重试：把等待、说明与可见码一并钉住。
        listOf("UNKNOWN", "INTERRUPTED", "INVALID_RESPONSE").forEach { errorCode ->
            val decision = AiRetryPolicy.decide(errorCode, attempts = 1)
            assertFalse(errorCode, decision.shouldRetry)
            assertEquals(errorCode, 0L, decision.delayMs)
            assertEquals(errorCode, "non-transient error", decision.explanation)
            assertEquals(errorCode, errorCode, decision.visibleErrorCode)
        }
    }

    @Test
    fun decideAuthBranchKeepsNormalizedCodeAndIgnoresSuffix() {
        // 认证分支不查等待秒数：后缀与空白/小写都不影响，可见码恒为归一化后的基码。
        val required = AiRetryPolicy.decide("AUTH_REQUIRED", attempts = 1)
        assertFalse(required.shouldRetry)
        assertEquals(0L, required.delayMs)
        assertEquals("auth required; do not auto-retry", required.explanation)
        assertEquals("AUTH_REQUIRED", required.visibleErrorCode)

        val suffixed = AiRetryPolicy.decide("UNAUTHORIZED:60", attempts = 1)
        assertFalse(suffixed.shouldRetry)
        assertEquals(0L, suffixed.delayMs)
        assertEquals("UNAUTHORIZED", suffixed.visibleErrorCode)

        val folded = AiRetryPolicy.decide("  auth_required ", attempts = 1)
        assertEquals("AUTH_REQUIRED", folded.visibleErrorCode)
        assertEquals(0L, folded.delayMs)
    }

}
