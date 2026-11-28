package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AiRetryDecision] (checklist line 931, 33rd block).
 * Test-only, zero production changes. The oracle below re-derives the
 * decision table by hand (branch order: attempts cap, rate-limit, auth,
 * quota, safe-connection retry, else); a Python mini-model differentially
 * checks it against an independent transcription of the production code
 * (5000 random codes x attempts, 0 differences) and pins the exact
 * embedded-retry-after / backoff values.
 * The shared codec ([AiCostVisibilityPolicy.baseErrorCode]) is used as-is by
 * the oracle: it is a helper, not the unit under test.
 * Note: the 2^5 backoff档 is unreachable in practice — MAX_TOTAL_ATTEMPTS is
 * 3, so attempts 1..2 only ever see 800/1600ms; pinned below.
 */
class AiRetryDecisionFuzzTest {

    private fun oracleEmbeddedSeconds(code: String?): Long? {
        val raw = (code ?: "").trim()
        val sep = raw.indexOf(':')
        if (sep <= 0 || sep >= raw.lastIndex) return null
        return raw.substring(sep + 1).trim().toLongOrNull()?.takeIf { it > 0L }?.coerceAtMost(86_400L)
    }

    private fun oracleWaitSeconds(code: String?): Long {
        oracleEmbeddedSeconds(code)?.let { return it }
        return when (AiCostVisibilityPolicy.baseErrorCode(code)) {
            AiCostVisibilityPolicy.ERROR_QUOTA -> 300L
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT -> 30L
            else -> 0L
        }
    }

    private fun oracleDecide(code: String?, attempts: Int): RetryDecision {
        val normalized = AiCostVisibilityPolicy.baseErrorCode(code).ifBlank { "UNKNOWN" }
        if (attempts >= 3) {
            return RetryDecision(
                shouldRetry = false,
                delayMs = 0L,
                explanation = "reached max auto retries; user must retry",
                visibleErrorCode = normalized,
            )
        }
        return when {
            normalized.startsWith("RATE_LIMITED") || normalized.contains("TOO_MANY") ->
                RetryDecision(
                    shouldRetry = false,
                    delayMs = oracleWaitSeconds(code) * 1_000L,
                    explanation = "rate-limited; ask user to wait",
                    visibleErrorCode = AiCostVisibilityPolicy.ERROR_RATE_LIMIT,
                )
            normalized.contains("UNAUTHORIZED") || normalized.contains("AUTH") ->
                RetryDecision(false, 0L, "auth required; do not auto-retry", normalized)
            normalized.contains("QUOTA") || normalized.contains("BUDGET") ||
                normalized.contains("INSUFFICIENT") || normalized.contains("PAYMENT") ||
                normalized.contains("BILLING") ->
                RetryDecision(
                    shouldRetry = false,
                    delayMs = oracleWaitSeconds(code) * 1_000L,
                    explanation = "quota exceeded; do not auto-retry",
                    visibleErrorCode = AiCostVisibilityPolicy.ERROR_QUOTA,
                )
            normalized == "CONNECTION_NOT_ESTABLISHED" -> {
                val completed = (attempts - 1).coerceAtLeast(0)
                RetryDecision(
                    shouldRetry = true,
                    delayMs = 800L * (1L shl completed.coerceAtMost(5)),
                    explanation = "connection was not established; safe to retry",
                    visibleErrorCode = "CONNECTION_NOT_ESTABLISHED",
                )
            }
            else -> RetryDecision(false, 0L, "non-transient error", normalized)
        }
    }

    private val codes: List<String?> = listOf(
        null, "", "   ", ":", ":45",
        "RATE_LIMITED", "RATE_LIMITED:45", "RATE_LIMITED:0", "RATE_LIMITED:-5",
        "RATE_LIMITED:abc", "RATE_LIMITED:", "RATE_LIMITED:999999", "RATE_LIMITEDX",
        "rate_limited:45", "  RATE_LIMITED  ",
        "TOO_MANY_REQUESTS", "TOO_MANY:60", "TOO_MANY_QUOTA", "AUTH_TOO_MANY",
        "UNAUTHORIZED", "AUTH_EXPIRED", "AUTH:123",
        "QUOTA_AUTH", "QUOTA_EXCEEDED", "QUOTA:120", "QUOTA:0",
        "BUDGET_EXCEEDED", "INSUFFICIENT_FUNDS", "PAYMENT_REQUIRED", "BILLING_DISABLED", "BILLING_AUTH",
        "CONNECTION_NOT_ESTABLISHED", "connection_not_established", "CONNECTION_NOT_ESTABLISHED:30",
        "UNKNOWN_ERROR", "TIMEOUT", "X-RATE_LIMITED", "RATE_LIMITED_AUTH", "QUOTA:999999",
    )

    private val suffixes = listOf("", ":30", "X", "_AUTH", "_QUOTA", " TOO_MANY")

    @Test
    fun decideMatchesBranchTableOracle() {
        val r = Random(20261122)
        repeat(500) {
            val base = codes[r.nextInt(codes.size)]
            val code = if (base != null && r.nextInt(10) < 3) base + suffixes[r.nextInt(suffixes.size)] else base
            val attempts = r.nextInt(-5, 11)
            assertEquals(oracleDecide(code, attempts), AiRetryDecision.decide(code, attempts))
        }
    }

    @Test
    fun attemptsCapPins() {
        for (attempts in listOf(3, 4, 100)) {
            val connection = AiRetryDecision.decide("CONNECTION_NOT_ESTABLISHED", attempts)
            assertFalse(connection.shouldRetry)
            assertEquals(0L, connection.delayMs)
            assertEquals("reached max auto retries; user must retry", connection.explanation)
            assertEquals("CONNECTION_NOT_ESTABLISHED", connection.visibleErrorCode)
            val limited = AiRetryDecision.decide("RATE_LIMITED", attempts)
            assertFalse(limited.shouldRetry)
            assertEquals(0L, limited.delayMs)
        }
    }

    @Test
    fun connectionBackoffTable() {
        // attempts <= 1 all map to 800ms (completed retries clamp at 0);
        // the 2^5 cap is unreachable because attempts >= 3 never retries.
        assertEquals(800L, AiRetryDecision.decide("CONNECTION_NOT_ESTABLISHED", -5).delayMs)
        assertEquals(800L, AiRetryDecision.decide("CONNECTION_NOT_ESTABLISHED", 0).delayMs)
        assertEquals(800L, AiRetryDecision.decide("CONNECTION_NOT_ESTABLISHED", 1).delayMs)
        assertEquals(1_600L, AiRetryDecision.decide("CONNECTION_NOT_ESTABLISHED", 2).delayMs)
        assertTrue(AiRetryDecision.decide("CONNECTION_NOT_ESTABLISHED", 1).shouldRetry)
        assertTrue(AiRetryDecision.decide("connection_not_established", 1).shouldRetry)
    }

    @Test
    fun branchPriorityPins() {
        // TOO_MANY beats AUTH (rate-limit branch is checked first).
        assertEquals("RATE_LIMITED", AiRetryDecision.decide("AUTH_TOO_MANY", 1).visibleErrorCode)
        assertEquals("RATE_LIMITED", AiRetryDecision.decide("TOO_MANY_QUOTA", 1).visibleErrorCode)
        // AUTH beats QUOTA (auth branch is checked before quota).
        assertEquals("QUOTA_AUTH", AiRetryDecision.decide("QUOTA_AUTH", 1).visibleErrorCode)
        assertEquals("BILLING_AUTH", AiRetryDecision.decide("BILLING_AUTH", 1).visibleErrorCode)
        assertEquals("RATE_LIMITED", AiRetryDecision.decide("RATE_LIMITED_AUTH", 1).visibleErrorCode)
    }

    @Test
    fun embeddedRetryAfterPins() {
        assertEquals(45_000L, AiRetryDecision.decide("RATE_LIMITED:45", 1).delayMs)
        assertEquals(86_400_000L, AiRetryDecision.decide("RATE_LIMITED:999999", 1).delayMs)
        assertEquals(120_000L, AiRetryDecision.decide("QUOTA:120", 1).delayMs)
        // Unparseable / missing / non-positive embedded seconds fall back
        // to the 30s rate-limit default.
        assertEquals(30_000L, AiRetryDecision.decide("RATE_LIMITED:abc", 1).delayMs)
        assertEquals(30_000L, AiRetryDecision.decide("RATE_LIMITED:", 1).delayMs)
        assertEquals(30_000L, AiRetryDecision.decide("RATE_LIMITED:0", 1).delayMs)
        assertEquals(30_000L, AiRetryDecision.decide("RATE_LIMITED:-5", 1).delayMs)
        // No embedded seconds and no matching default: delay 0.
        assertEquals(0L, AiRetryDecision.decide("RATE_LIMITEDX", 1).delayMs)
        assertEquals("RATE_LIMITED", AiRetryDecision.decide("RATE_LIMITEDX", 1).visibleErrorCode)
        // A bare ':' has no head: base code is the raw string itself.
        assertEquals(":", AiRetryDecision.decide(":", 1).visibleErrorCode)
        assertEquals(":45", AiRetryDecision.decide(":45", 1).visibleErrorCode)
    }

    @Test
    fun nullAndBlankCodes() {
        for (code in listOf<String?>(null, "", "   ")) {
            val decision = AiRetryDecision.decide(code, 1)
            assertFalse(decision.shouldRetry)
            assertEquals(0L, decision.delayMs)
            assertEquals("UNKNOWN", decision.visibleErrorCode)
            assertEquals("non-transient error", decision.explanation)
        }
    }
}
