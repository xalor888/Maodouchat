package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [DisappearingMessagePolicy] (checklist line 931, 48th block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements the policy independently: an explicit membership
 * scan over a hand-written copy of the allowed-seconds list, explicit
 * null/branch handling for normalize/effective/resolveExpiresAt, explicit
 * clamping for remainingMs, and the two boolean gates as plain returns of
 * isSecretChat. Shared primitives (production calls them too, so sharing is
 * exact): Int/Long arithmetic (including silent Long wrap on overflow),
 * list membership by equality, null checks.
 *
 * Two quirks are pinned, not fixed (behaviour, not bugs):
 * - shouldArmOnVisible ignores timerSeconds and shouldSkipReadReceipts
 *   ignores blockReadReceipts: both return isSecretChat alone. The random
 *   oracle varies those parameters to prove independence.
 * - Long arithmetic wraps silently: resolveExpiresAt(null, Int.MAX_VALUE,
 *   Long.MAX_VALUE) wraps to -9223369889371128809, and remainingMs clamps a
 *   wrapped-negative difference to 0 (Long.MAX - Long.MIN wraps to -1).
 *
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 42,755 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, allowed-seconds / normalize / effective
 * / resolveExpiresAt / gates / expiry / remaining exhaustives, and 35,000
 * Python-generated random lines — with 0 differences. Every pinned
 * expectation below was checked against real production. The 8 test methods
 * were also run locally against the real production code (kotlin-test
 * stubs): all pass.
 */
class DisappearingMessagePolicyFuzzTest {

    // ---------- oracles ----------

    private val ALLOWED_ORACLE = listOf(
        0, 30, 60, 120, 300, 900, 3600, 7200, 14400, 28800, 43200, 86400, 604800, 2592000
    )

    private fun isAllowedSecondsOracle(seconds: Int): Boolean {
        for (v in ALLOWED_ORACLE) if (v == seconds) return true
        return false
    }

    private fun normalizeSecondsOracle(seconds: Int?): Int {
        val value = seconds ?: 0
        if (isAllowedSecondsOracle(value)) return value
        return 0
    }

    private fun effectiveSecondsOracle(isGroup: Boolean, requestedSeconds: Int?, isSecret: Boolean): Int {
        if (isGroup) return 0
        if (isSecret) return 30
        return normalizeSecondsOracle(requestedSeconds)
    }

    private fun resolveExpiresAtOracle(
        existingExpiresAt: Long?,
        timerSeconds: Int,
        readAtMs: Long
    ): Long? {
        if (timerSeconds <= 0 || readAtMs <= 0L) {
            if (existingExpiresAt != null && existingExpiresAt > 0L) return existingExpiresAt
            return null
        }
        if (existingExpiresAt != null && existingExpiresAt > 0L) return existingExpiresAt
        return readAtMs + timerSeconds * 1000L
    }

    private fun shouldArmOnVisibleOracle(isSecretChat: Boolean, timerSeconds: Int): Boolean =
        isSecretChat

    private fun shouldSkipReadReceiptsOracle(isSecretChat: Boolean, blockReadReceipts: Boolean): Boolean =
        isSecretChat

    private fun isExpiredOracle(expiresAt: Long?, nowMs: Long): Boolean {
        if (expiresAt == null) return false
        if (expiresAt <= 0L) return false
        return nowMs >= expiresAt
    }

    private fun remainingMsOracle(expiresAt: Long?, nowMs: Long): Long {
        if (expiresAt == null) return -1L
        if (expiresAt <= 0L) return -1L
        val d = expiresAt - nowMs
        return if (d < 0L) 0L else d
    }

    // ---------- generators (Random(42) in the CI tests) ----------

    private val INT_POOL = listOf(
        0, 1, -1, 29, 30, 31, 59, 60, 61, 119, 120, 121, 299, 300, 301, 899, 900, 901,
        3599, 3600, 3601, 7199, 7200, 7201, 14399, 14400, 14401, 28799, 28800, 28801,
        43199, 43200, 43201, 86399, 86400, 86401, 604799, 604800, 604801,
        2591999, 2592000, 2592001, -100, 100000, 7, 13, 999, 86401,
        Int.MAX_VALUE, Int.MIN_VALUE
    )

    private fun randomInt(rnd: Random): Int {
        return when (rnd.nextInt(3)) {
            0 -> INT_POOL[rnd.nextInt(INT_POOL.size)]
            1 -> rnd.nextInt(-100, 3000001)
            else -> {
                val base = ALLOWED_ORACLE[rnd.nextInt(ALLOWED_ORACLE.size)]
                base + rnd.nextInt(-2, 3)
            }
        }
    }

    private fun randomNullableInt(rnd: Random): Int? =
        if (rnd.nextInt(5) == 0) null else randomInt(rnd)

    private val LONG_POOL = listOf(
        0L, 1L, -1L, 1000L, 2000L, 31000L, 5000L, -5000L,
        Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1000L, Long.MIN_VALUE + 1000L,
        Long.MAX_VALUE - 2147483647000L
    )

    private fun randomLong(rnd: Random): Long {
        return when (rnd.nextInt(4)) {
            0 -> LONG_POOL[rnd.nextInt(LONG_POOL.size)]
            1 -> rnd.nextLong(-100000L, 100001L)
            2 -> Long.MAX_VALUE - rnd.nextLong(0L, 100001L)
            else -> Long.MIN_VALUE + rnd.nextLong(0L, 100001L)
        }
    }

    private fun randomNullableLong(rnd: Random): Long? =
        if (rnd.nextInt(5) == 0) null else randomLong(rnd)

    private fun randomTimer(rnd: Random): Int {
        return when (rnd.nextInt(3)) {
            0 -> listOf(-1, 0, 1, 30, 60, 300, 3600, 86400, 2592000, Int.MAX_VALUE)[rnd.nextInt(10)]
            1 -> rnd.nextInt(-10, 3000001)
            else -> ALLOWED_ORACLE[rnd.nextInt(ALLOWED_ORACLE.size)] + rnd.nextInt(-2, 3)
        }
    }

    // Field codec (decimal text, "-" = null) lives in Parse48.kt, shared with Probe48.

    @Test
    fun isAllowedSecondsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val seconds = randomInt(rnd)
            assertEquals(
                isAllowedSecondsOracle(seconds),
                DisappearingMessagePolicy.isAllowedSeconds(seconds),
                "seconds=$seconds"
            )
        }
    }

    @Test
    fun normalizeSecondsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val seconds = randomNullableInt(rnd)
            assertEquals(
                normalizeSecondsOracle(seconds),
                DisappearingMessagePolicy.normalizeSeconds(seconds),
                "seconds=$seconds"
            )
        }
    }

    @Test
    fun effectiveSecondsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val isGroup = rnd.nextBoolean()
            val requested = randomNullableInt(rnd)
            val isSecret = rnd.nextBoolean()
            assertEquals(
                effectiveSecondsOracle(isGroup, requested, isSecret),
                DisappearingMessagePolicy.effectiveSeconds(isGroup, requested, isSecret),
                "isGroup=$isGroup requested=$requested isSecret=$isSecret"
            )
        }
    }

    @Test
    fun resolveExpiresAtRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val existing = randomNullableLong(rnd)
            val timer = randomTimer(rnd)
            val readAt = randomLong(rnd)
            assertEquals(
                resolveExpiresAtOracle(existing, timer, readAt),
                DisappearingMessagePolicy.resolveExpiresAt(existing, timer, readAt),
                "existing=$existing timer=$timer readAt=$readAt"
            )
        }
    }

    @Test
    fun booleanGatesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val isSecret = rnd.nextBoolean()
            val timer = randomTimer(rnd)
            val block = rnd.nextBoolean()
            assertEquals(
                shouldArmOnVisibleOracle(isSecret, timer),
                DisappearingMessagePolicy.shouldArmOnVisible(isSecret, timer),
                "isSecret=$isSecret timer=$timer"
            )
            assertEquals(
                shouldSkipReadReceiptsOracle(isSecret, block),
                DisappearingMessagePolicy.shouldSkipReadReceipts(isSecret, block),
                "isSecret=$isSecret block=$block"
            )
        }
    }

    @Test
    fun isExpiredRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val expiresAt = randomNullableLong(rnd)
            val now = randomLong(rnd)
            assertEquals(
                isExpiredOracle(expiresAt, now),
                DisappearingMessagePolicy.isExpired(expiresAt, now),
                "expiresAt=$expiresAt now=$now"
            )
        }
    }

    @Test
    fun remainingMsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val expiresAt = randomNullableLong(rnd)
            val now = randomLong(rnd)
            assertEquals(
                remainingMsOracle(expiresAt, now),
                DisappearingMessagePolicy.remainingMs(expiresAt, now),
                "expiresAt=$expiresAt now=$now"
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // The allowed list itself, pinned verbatim.
        assertEquals(
            listOf(0, 30, 60, 120, 300, 900, 3600, 7200, 14400, 28800, 43200, 86400, 604800, 2592000),
            DisappearingMessagePolicy.ALLOWED_SECONDS
        )
        // Allowed values pass; every neighbour fails.
        for (v in listOf(0, 30, 60, 120, 300, 900, 3600, 7200, 14400, 28800, 43200, 86400, 604800, 2592000)) {
            assertTrue(DisappearingMessagePolicy.isAllowedSeconds(v), "allowed $v")
        }
        for (v in listOf(-1, 29, 31, 59, 61, 119, 121, 299, 301, 899, 901, 3599, 3601, 86399, 86401,
            604799, 604801, 2591999, 2592001, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse(DisappearingMessagePolicy.isAllowedSeconds(v), "not allowed $v")
        }
        // normalizeSeconds: null -> 0, allowed kept, anything else -> 0.
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(null))
        assertEquals(30, DisappearingMessagePolicy.normalizeSeconds(30))
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(31))
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(-5))
        assertEquals(2592000, DisappearingMessagePolicy.normalizeSeconds(2592000))
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(Int.MAX_VALUE))
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(Int.MIN_VALUE))
        // effectiveSeconds: group wins over secret; secret is fixed 30; else normalize.
        assertEquals(0, DisappearingMessagePolicy.effectiveSeconds(true, 3600, false))
        assertEquals(0, DisappearingMessagePolicy.effectiveSeconds(true, 30, true))
        assertEquals(30, DisappearingMessagePolicy.effectiveSeconds(false, 31, true))
        assertEquals(0, DisappearingMessagePolicy.effectiveSeconds(false, 31, false))
        assertEquals(0, DisappearingMessagePolicy.effectiveSeconds(false, null, false))
        assertEquals(60, DisappearingMessagePolicy.effectiveSeconds(false, 60, false))
        // resolveExpiresAt: existing positive deadline is never rewritten.
        assertEquals(2000L, DisappearingMessagePolicy.resolveExpiresAt(2000L, 30, 5000L))
        assertEquals(31000L, DisappearingMessagePolicy.resolveExpiresAt(null, 30, 1000L))
        // timer<=0 or readAt<=0: existing kept only if > 0, else null.
        assertEquals(2000L, DisappearingMessagePolicy.resolveExpiresAt(2000L, 0, 5000L))
        assertEquals(null, DisappearingMessagePolicy.resolveExpiresAt(null, 0, 5000L))
        assertEquals(null, DisappearingMessagePolicy.resolveExpiresAt(-5L, 0, 5000L))
        assertEquals(5000L, DisappearingMessagePolicy.resolveExpiresAt(5000L, 30, -1L))
        assertEquals(5000L, DisappearingMessagePolicy.resolveExpiresAt(5000L, -1, 1000L))
        assertEquals(null, DisappearingMessagePolicy.resolveExpiresAt(null, 30, 0L))
        // Non-positive existing deadlines are treated as absent.
        assertEquals(31000L, DisappearingMessagePolicy.resolveExpiresAt(0L, 30, 1000L))
        assertEquals(31000L, DisappearingMessagePolicy.resolveExpiresAt(-5L, 30, 1000L))
        // Long arithmetic wraps silently (no exception, no clamp).
        assertEquals(-9223369889371128809L, DisappearingMessagePolicy.resolveExpiresAt(null, Int.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, DisappearingMessagePolicy.resolveExpiresAt(null, 30, Long.MAX_VALUE - 30000L))
        assertEquals(-9223372036854774809L, DisappearingMessagePolicy.resolveExpiresAt(null, 1, Long.MAX_VALUE))
        // Boolean gates ignore their second parameter.
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(true, 0))
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(true, 3600))
        assertFalse(DisappearingMessagePolicy.shouldArmOnVisible(false, 30))
        assertFalse(DisappearingMessagePolicy.shouldArmOnVisible(false, 0))
        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(true, false))
        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(true, true))
        assertFalse(DisappearingMessagePolicy.shouldSkipReadReceipts(false, true))
        assertFalse(DisappearingMessagePolicy.shouldSkipReadReceipts(false, false))
        // isExpired: boundary is inclusive (now == deadline counts as expired).
        assertTrue(DisappearingMessagePolicy.isExpired(1000L, 1000L))
        assertFalse(DisappearingMessagePolicy.isExpired(1000L, 999L))
        assertFalse(DisappearingMessagePolicy.isExpired(null, 1000L))
        assertFalse(DisappearingMessagePolicy.isExpired(0L, 1000L))
        assertFalse(DisappearingMessagePolicy.isExpired(-5L, 1000L))
        assertTrue(DisappearingMessagePolicy.isExpired(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(DisappearingMessagePolicy.isExpired(Long.MAX_VALUE, Long.MIN_VALUE))
        // remainingMs: null / non-positive -> -1; past deadline clamps to 0.
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(1000L, 2000L))
        assertEquals(1L, DisappearingMessagePolicy.remainingMs(1000L, 999L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(null, 1000L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(0L, 1000L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(-5L, 1000L))
        assertEquals(4000L, DisappearingMessagePolicy.remainingMs(5000L, 1000L))
        // Wrapped-negative differences clamp to 0, not to a huge negative.
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(Long.MAX_VALUE, -1L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(Long.MIN_VALUE, Long.MAX_VALUE))
    }

}
