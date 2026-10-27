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
    fun decideRateLimitPrefixRequiredForRateBranch() {
        // 限流分支只认前缀：RATE_LIMITED 出现在中间不触发，落回非瞬态分支，后缀秒数也不生效。
        val decision = AiRetryPolicy.decide("PRE_RATE_LIMITED:45", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("non-transient error", decision.explanation)
        assertEquals("PRE_RATE_LIMITED", decision.visibleErrorCode)
    }

    @Test
    fun decideQuotaSubstringTriggersQuotaBranch() {
        // 配额分支是 contains：基码含 QUOTA 即走配额分支，可见码统一为 QUOTA_EXCEEDED。
        val bare = AiRetryPolicy.decide("OVER_QUOTA", attempts = 1)
        assertFalse(bare.shouldRetry)
        assertEquals(0L, bare.delayMs)
        assertEquals("quota exceeded; do not auto-retry", bare.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, bare.visibleErrorCode)

        val withSuffix = AiRetryPolicy.decide("OVER_QUOTA:120", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(120_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, withSuffix.visibleErrorCode)
    }

    @Test
    fun decideInsufficientFlavorWithSuffixFlowsIntoQuotaWait() {
        val withSuffix = AiRetryPolicy.decide("INSUFFICIENT_FUNDS:90", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(90_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, withSuffix.visibleErrorCode)
    }

    @Test
    fun decideBillingFlavorWithSuffixFlowsIntoQuotaWait() {
        val withSuffix = AiRetryPolicy.decide("BILLING_ERROR:75", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(75_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, withSuffix.visibleErrorCode)
    }

    @Test
    fun globalWindowBoundaryAt240Calls() {
        // 全局窗口上限恰好 240：239 次后仍可调用，第 240 次后新会话被拦，清空后恢复。
        AiRetryPolicy.clearSession()
        repeat(239) { index ->
            AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT)
        }
        assertTrue(AiRetryPolicy.canCallNow("fresh-chat", AiRetryPolicyCategory.LIGHT))
        AiRetryPolicy.recordCall("chat-239", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("fresh-chat", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.remainingDelayMs("fresh-chat", AiRetryPolicyCategory.LIGHT) > 0L)
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("fresh-chat", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh-chat", AiRetryPolicyCategory.LIGHT))
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

    @Test
    fun decideAuthExpiredFlavorMapsToAuthBranch() {
        // AUTH 子串即走认证分支：可见码为归一化后的基码，等待为 0。
        val expired = AiRetryPolicy.decide("AUTH_EXPIRED", attempts = 1)
        assertFalse(expired.shouldRetry)
        assertEquals(0L, expired.delayMs)
        assertEquals("auth required; do not auto-retry", expired.explanation)
        assertEquals("AUTH_EXPIRED", expired.visibleErrorCode)

        val folded = AiRetryPolicy.decide("auth_expired", attempts = 1)
        assertFalse(folded.shouldRetry)
        assertEquals("AUTH_EXPIRED", folded.visibleErrorCode)
    }

    @Test
    fun decideTooManySubstringTriggersRateBranchWithZeroWait() {
        // 限流分支是 contains：基码含 TOO_MANY 即走限流分支；该基码不在等待表里，等待为 0。
        val decision = AiRetryPolicy.decide("ERROR_TOO_MANY_REQUESTS", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideQuotaZeroSuffixFallsBackToQuotaDefault() {
        // 内嵌秒数只取正数：0 与负数后缀都落回配额默认等待 300 秒。
        val zero = AiRetryPolicy.decide("QUOTA_EXCEEDED:0", attempts = 1)
        assertFalse(zero.shouldRetry)
        assertEquals(300_000L, zero.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, zero.visibleErrorCode)

        val negative = AiRetryPolicy.decide("QUOTA_EXCEEDED:-10", attempts = 1)
        assertEquals(300_000L, negative.delayMs)
    }

    @Test
    fun decidePaymentOversizedSuffixIsCappedAtOneDay() {
        // PAYMENT 走配额分支：内嵌秒数上限 86400 秒，超大后缀按一天计等待。
        val payment = AiRetryPolicy.decide("PAYMENT_REQUIRED:999999", attempts = 1)
        assertFalse(payment.shouldRetry)
        assertEquals(86_400_000L, payment.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, payment.visibleErrorCode)
    }

    @Test
    fun decideRateAndQuotaBranchesIgnoreAttemptCountBelowCap() {
        // attempts 只在连接重试退避与 ≥3 封顶时有意义：限流/配额/认证分支在 1 次与 2 次下结果相同。
        val limitedFirst = AiRetryPolicy.decide("RATE_LIMITED:45", attempts = 1)
        val limitedSecond = AiRetryPolicy.decide("RATE_LIMITED:45", attempts = 2)
        assertEquals(limitedFirst, limitedSecond)

        val quotaFirst = AiRetryPolicy.decide("QUOTA_EXCEEDED", attempts = 1)
        val quotaSecond = AiRetryPolicy.decide("QUOTA_EXCEEDED", attempts = 2)
        assertEquals(quotaFirst, quotaSecond)

        val authFirst = AiRetryPolicy.decide("UNAUTHORIZED", attempts = 1)
        val authSecond = AiRetryPolicy.decide("UNAUTHORIZED", attempts = 2)
        assertEquals(authFirst, authSecond)
    }

    @Test
    fun decideConnectionFailureTrimsWhitespaceAndCase() {
        // 基码解析先 trim 再大写：空白与小写不影响连接重试分支。
        val padded = AiRetryPolicy.decide("  connection_not_established  ", attempts = 1)
        assertTrue(padded.shouldRetry)
        assertEquals(800L, padded.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", padded.visibleErrorCode)

        val second = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED ", attempts = 2)
        assertTrue(second.shouldRetry)
        assertEquals(1_600L, second.delayMs)
    }
    @Test
    fun decideSuffixOnlyCodeKeepsRawVisibleCode() {
        // 冒号前无基码时 baseErrorCode 回落为原串大写：后缀-only 码走非瞬态分支，可见码即原串。
        val suffixed = AiRetryPolicy.decide(":45", attempts = 1)
        assertFalse(suffixed.shouldRetry)
        assertEquals(0L, suffixed.delayMs)
        assertEquals("non-transient error", suffixed.explanation)
        assertEquals(":45", suffixed.visibleErrorCode)

        val bare = AiRetryPolicy.decide(":", attempts = 1)
        assertFalse(bare.shouldRetry)
        assertEquals(":", bare.visibleErrorCode)
    }
    @Test
    fun decideSpaceBeforeColonStillParsesSuffix() {
        // 基码取冒号前一段并 trim：冒号前空格不影响分支与内嵌秒数。
        val limited = AiRetryPolicy.decide("RATE_LIMITED :45", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(45_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED :60", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(60_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }
    @Test
    fun lightPerChatRemainingStaysWithinThirtySeconds() {
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-l", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-l", AiRetryPolicyCategory.LIGHT))
        val wait = AiRetryPolicy.remainingDelayMs("chat-l", AiRetryPolicyCategory.LIGHT)
        assertTrue("LIGHT remaining should be ~30s, was $wait", wait in 1L..30_000L)
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-l", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-l", AiRetryPolicyCategory.HEAVY))
        AiRetryPolicy.clearSession()
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-l", AiRetryPolicyCategory.LIGHT))
    }
    @Test
    fun decideAuthSubstringInCompoundCodeMapsToAuthBranch() {
        // 认证分支是 contains：复合码含 AUTH 即走认证分支，可见码为归一化后的基码。
        val session = AiRetryPolicy.decide("SESSION_AUTH_FAILED", attempts = 1)
        assertFalse(session.shouldRetry)
        assertEquals(0L, session.delayMs)
        assertEquals("auth required; do not auto-retry", session.explanation)
        assertEquals("SESSION_AUTH_FAILED", session.visibleErrorCode)

        val reauth = AiRetryPolicy.decide("REAUTH_REQUIRED", attempts = 1)
        assertFalse(reauth.shouldRetry)
        assertEquals("REAUTH_REQUIRED", reauth.visibleErrorCode)
    }

    @Test
    fun decideRateLimitAndQuotaExactDayCap() {
        // 内嵌秒数恰为 86400：不触发封顶截断，按一天计等待。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:86400", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(86_400_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:86400", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(86_400_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun decideTrailingColonFallsBackToBranchDefault() {
        // 冒号后无秒数时内嵌解析为空：落回各分支默认等待。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(30_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(300_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun decideBareTooManyCodeMapsToRateBranch() {
        // 裸 TOO_MANY 含限流子串即走限流分支；该基码不在等待表里，等待为 0。
        val decision = AiRetryPolicy.decide("TOO_MANY", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideQuotaSubstringBeatsConnectionEquality() {
        // 配额分支在连接相等判定之前：含 QUOTA 的连接码走配额分支，不重试。
        listOf("CONNECTION_QUOTA", "QUOTA_CONNECTION_NOT_ESTABLISHED").forEach { errorCode ->
            val decision = AiRetryPolicy.decide(errorCode, attempts = 1)
            assertFalse(errorCode, decision.shouldRetry)
            assertEquals(errorCode, 0L, decision.delayMs)
            assertEquals(errorCode, "quota exceeded; do not auto-retry", decision.explanation)
            assertEquals(errorCode, AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
        }
    }

    @Test
    fun decideRateLimitExtraPrefixWithSuffix() {
        // 限流分支只认前缀：RATE_LIMITED_EXTRA 同样走限流分支，等待按内嵌秒数计。
        val withSuffix = AiRetryPolicy.decide("RATE_LIMITED_EXTRA:45", attempts = 1)
        assertFalse(withSuffix.shouldRetry)
        assertEquals(45_000L, withSuffix.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, withSuffix.visibleErrorCode)

        val bare = AiRetryPolicy.decide("RATE_LIMITED_EXTRA", attempts = 1)
        assertFalse(bare.shouldRetry)
        assertEquals(0L, bare.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, bare.visibleErrorCode)
    }


    @Test
    fun decideConnectionCodeWithSuffixStillRetriesWithBackoff() {
        // 基码取冒号前一段：连接码带后缀仍走连接重试分支，后缀秒数不生效。
        val first = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED:45", attempts = 1)
        assertTrue(first.shouldRetry)
        assertEquals(800L, first.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", first.visibleErrorCode)

        val second = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED:xyz", attempts = 2)
        assertTrue(second.shouldRetry)
        assertEquals(1_600L, second.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", second.visibleErrorCode)
    }

    @Test
    fun decideNonNumericSuffixFallsBackToBranchDefault() {
        // 内嵌秒数解析失败时落回各分支默认等待：非数字后缀不影响分支。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:abc", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(30_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:abc", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(300_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun decideNegativeRateSuffixFallsBackToRateDefault() {
        // 内嵌秒数只取正数：负数后缀落回限流默认等待 30 秒。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:-10", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(30_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)
    }

    @Test
    fun decideDoubleColonSuffixIsIgnoredForWait() {
        // 内嵌秒数只认第一个冒号后整段：双冒号后缀解析失败，落回分支默认等待。
        val limited = AiRetryPolicy.decide("RATE_LIMITED:45:60", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(30_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:30:90", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(300_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }

    @Test
    fun decideSpaceAfterColonStillParsesSuffix() {
        // 冒号后空格先 trim 再解析：冒号后空格不影响内嵌秒数。
        val limited = AiRetryPolicy.decide("RATE_LIMITED: 45", attempts = 1)
        assertFalse(limited.shouldRetry)
        assertEquals(45_000L, limited.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, limited.visibleErrorCode)

        val quota = AiRetryPolicy.decide("QUOTA_EXCEEDED:  60", attempts = 1)
        assertFalse(quota.shouldRetry)
        assertEquals(60_000L, quota.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, quota.visibleErrorCode)
    }


    @Test
    fun decideEmptyCodeAtCapResolvesUnknownWithCapExplanation() {
        val decision = AiRetryPolicy.decide("", attempts = 3)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("UNKNOWN", decision.visibleErrorCode)
        assertEquals("reached max auto retries; user must retry", decision.explanation)
    }

    @Test
    fun decideLowercaseConnectionCodeWithSuffixStillRetries() {
        val decision = AiRetryPolicy.decide("connection_not_established:99", attempts = 2)
        assertTrue(decision.shouldRetry)
        assertEquals(1_600L, decision.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun decideRateLimitPrefixWithTooManySubstringUsesRateBranchWithZeroWait() {
        val decision = AiRetryPolicy.decide("RATE_LIMITED_TOO_MANY", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideAuthCodeAtCapKeepsCapExplanation() {
        // 封顶判定先于分支判定：认证码触顶时说明是封顶文案，不是认证文案。
        val decision = AiRetryPolicy.decide("UNAUTHORIZED", attempts = 3)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("UNAUTHORIZED", decision.visibleErrorCode)
        assertEquals("reached max auto retries; user must retry", decision.explanation)
    }

    @Test
    fun decideConnectionCodeWithSuffixAtZeroAttemptsRetriesAtBase() {
        val decision = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED:45", attempts = 0)
        assertTrue(decision.shouldRetry)
        assertEquals(800L, decision.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun decideConnectionCodeAtCapKeepsCapExplanation() {
        val decision = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = 3)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("reached max auto retries; user must retry", decision.explanation)
        assertEquals("CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun decideNullCodeBelowCapMapsToUnknownNonTransient() {
        val decision = AiRetryPolicy.decide(null, attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("non-transient error", decision.explanation)
        assertEquals("UNKNOWN", decision.visibleErrorCode)
    }

    @Test
    fun decideBillingZeroSuffixHasNoDefaultWait() {
        val decision = AiRetryPolicy.decide("BILLING:0", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("quota exceeded; do not auto-retry", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun decideInsufficientCodeWithSuffixUsesQuotaWait() {
        val decision = AiRetryPolicy.decide("INSUFFICIENT_FUNDS:120", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(120_000L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun decideConnectionCodeWithNegativeAttemptsRetriesAtBase() {
        val decision = AiRetryPolicy.decide("CONNECTION_NOT_ESTABLISHED", attempts = -1)
        assertTrue(decision.shouldRetry)
        assertEquals(800L, decision.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun decideTooManyRequestsSuffixUsesRateWait() {
        val decision = AiRetryPolicy.decide("TOO_MANY_REQUESTS:30", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(30_000L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decidePaymentRequiredSuffixUsesQuotaWait() {
        val decision = AiRetryPolicy.decide("PAYMENT_REQUIRED:180", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(180_000L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun decideLowercaseRateLimitSuffixUsesRateWait() {
        // 基码归一化转大写，内嵌秒数仍按原串解析。
        val decision = AiRetryPolicy.decide("rate_limited:60", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(60_000L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideBudgetExceededSuffixUsesQuotaWait() {
        val decision = AiRetryPolicy.decide("BUDGET_EXCEEDED:240", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(240_000L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun heavyCallDoesNotBlockLightCallForSameChat() {
        // 限流键按类目区分：HEAVY 记录只挡同类目。
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-hl", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-hl", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-hl", AiRetryPolicyCategory.LIGHT))
        AiRetryPolicy.clearSession()
    }

    @Test
    fun decideTooManyQuotaCodeUsesRateBranch() {
        // 分支顺序限流在前：含 TOO_MANY 的配额码走限流分支，不走配额分支。
        val decision = AiRetryPolicy.decide("TOO_MANY_QUOTA", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideBarePaymentFailedMapsToQuotaWithZeroWait() {
        // 裸 PAYMENT 码走配额分支：基码不在等待表里，等待为 0。
        val decision = AiRetryPolicy.decide("PAYMENT_FAILED", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun decideAuthSubstringBeatsConnectionEquality() {
        // 认证分支在连接相等判定之前：含 AUTH 的连接码走认证分支，不重试。
        val decision = AiRetryPolicy.decide("AUTH_CONNECTION_NOT_ESTABLISHED", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("auth required; do not auto-retry", decision.explanation)
        assertEquals("AUTH_CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun lightCallDoesNotBlockHeavyCallForSameChat() {
        // 限流键按类目区分：LIGHT 记录只挡同类目。
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-lh", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-lh", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-lh", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-lh", AiRetryPolicyCategory.HEAVY))
        AiRetryPolicy.clearSession()
    }

    @Test
    fun heavyPerChatRemainingStaysWithinSixtySeconds() {
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-h60", AiRetryPolicyCategory.HEAVY)
        val wait = AiRetryPolicy.remainingDelayMs("chat-h60", AiRetryPolicyCategory.HEAVY)
        assertTrue("HEAVY remaining should be within 60s, was $wait", wait in 1L..60_000L)
        assertFalse(AiRetryPolicy.canCallNow("chat-h60", AiRetryPolicyCategory.HEAVY))
        AiRetryPolicy.clearSession()
    }

    @Test
    fun decideAuthorizeWordMapsToAuthBranch() {
        // 认证分支是 contains：AUTHORIZE 含 AUTH 即走认证分支，不重试。
        val decision = AiRetryPolicy.decide("AUTHORIZE", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("auth required; do not auto-retry", decision.explanation)
        assertEquals("AUTHORIZE", decision.visibleErrorCode)
    }

    @Test
    fun decideCancelledPinsFullNonTransientDecision() {
        val decision = AiRetryPolicy.decide("CANCELLED", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("non-transient error", decision.explanation)
        assertEquals("CANCELLED", decision.visibleErrorCode)
    }

    @Test
    fun decideOverBudgetMapsToQuotaBranchWithZeroWait() {
        // 配额分支是 contains：OVER_BUDGET 含 BUDGET 走配额分支；基码非 QUOTA_EXCEEDED 故等待为 0。
        val decision = AiRetryPolicy.decide("OVER_BUDGET", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("quota exceeded; do not auto-retry", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun decideBareTooManyWithSuffixUsesRateWait() {
        val decision = AiRetryPolicy.decide("TOO_MANY:90", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(90_000L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decidePaddedLowercaseConnectionCodeRetriesAtSecondAttempt() {
        // trim + 大写 + 退避组合：两端空白小写连接码 2 次仍退避 1600。
        val decision = AiRetryPolicy.decide("  connection_not_established  ", attempts = 2)
        assertTrue(decision.shouldRetry)
        assertEquals(1_600L, decision.delayMs)
        assertEquals("CONNECTION_NOT_ESTABLISHED", decision.visibleErrorCode)
    }

    @Test
    fun decideQuotaSuffixOverridesDefaultWait() {
        val decision = AiRetryPolicy.decide("QUOTA_EXCEEDED:60", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(60_000L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun decideTooManyZeroSuffixReportsZeroWait() {
        // 零后缀不是正数等待：TOO_MANY 分支的等待落回 0，而不是 30 秒默认
        val decision = AiRetryPolicy.decide("TOO_MANY:0", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideTimeoutSuffixStripsToBaseCode() {
        // 非重试分支只看基码：后缀剥离后仍走 else 分支
        val decision = AiRetryPolicy.decide("TIMEOUT:30", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("TIMEOUT", decision.visibleErrorCode)
    }

    @Test
    fun decideBudgetZeroSuffixFallsBackToZeroWait() {
        // BUDGET 基码本身没有默认等待：零后缀同样落回 0
        val decision = AiRetryPolicy.decide("BUDGET_EXCEEDED:0", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun heavyRecordLeavesLightRemainingAtZero() {
        AiRetryPolicy.clearSession()
        AiRetryPolicy.recordCall("chat-heavy", AiRetryPolicyCategory.HEAVY)
        assertTrue(AiRetryPolicy.canCallNow("chat-heavy", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-heavy", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.remainingDelayMs("chat-heavy", AiRetryPolicyCategory.HEAVY) > 0L)
        AiRetryPolicy.clearSession()
    }


    @Test
    fun decideCancelledWithSuffixKeepsBaseVisibleWithoutWait() {
        val decision = AiRetryPolicy.decide("CANCELLED:30", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
        assertEquals("non-transient error", decision.explanation)
        assertEquals("CANCELLED", decision.visibleErrorCode)
    }

    @Test
    fun decideLowercaseTooManyWithSuffixUsesRateWait() {
        val decision = AiRetryPolicy.decide("too_many_requests:30", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(30_000L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

    @Test
    fun decideBillingOversizedSuffixCapsAtOneDay() {
        val decision = AiRetryPolicy.decide("BILLING_ERROR:999999", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(86_400_000L, decision.delayMs)
        assertEquals("quota exceeded; do not auto-retry", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, decision.visibleErrorCode)
    }

    @Test
    fun globalWindowFullBlocksHeavyCategoryToo() {
        AiRetryPolicy.clearSession()
        repeat(240) { index ->
            AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT)
        }
        assertFalse(AiRetryPolicy.canCallNow("fresh-chat", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.remainingDelayMs("fresh-chat", AiRetryPolicyCategory.HEAVY) > 0L)
        AiRetryPolicy.clearSession()
    }

    @Test
    fun decideRateLimitedTooManyWithSuffixUsesRateWait() {
        val decision = AiRetryPolicy.decide("RATE_LIMITED_TOO_MANY:45", attempts = 1)
        assertFalse(decision.shouldRetry)
        assertEquals(45_000L, decision.delayMs)
        assertEquals("rate-limited; ask user to wait", decision.explanation)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, decision.visibleErrorCode)
    }

}
