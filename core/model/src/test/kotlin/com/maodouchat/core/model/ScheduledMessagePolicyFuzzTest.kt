package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ScheduledMessagePolicy] (checklist line 931, 32nd block).
 * Test-only, zero production changes. The oracle below re-implements trim/take
 * over UTF-16 code units and the send-window arithmetic by hand; a Python
 * mini-model differentially checks the oracle transcription against an
 * independent production transcription.
 * Note: Kotlin trim() only strips chars <= U+0020, so NBSP (U+00A0) is NOT trimmed.
 * Long arithmetic wraps: isValidSendAt/delayFromNow see the wrapped delay, and
 * clampSendAt throws when now is so close to Long.MAX_VALUE that now+MAX wraps
 * below now+MIN (pinned as current behaviour, not changed).
 */
class ScheduledMessagePolicyFuzzTest {

    // Manual trim: drop leading/trailing chars with code <= ' ' (Kotlin trim semantics).
    private fun oracleTrim(s: String): String {
        var start = 0
        while (start < s.length && s[start] <= ' ') start++
        var end = s.length
        while (end > start && s[end - 1] <= ' ') end--
        return s.substring(start, end)
    }

    // take(n) counts UTF-16 code units: String.length / substring are code-unit based.
    private fun oracleTake(s: String, n: Int): String = s.substring(0, minOf(n, s.length))

    private fun oracleNormalize(raw: String?): String =
        oracleTake(oracleTrim(raw.orEmpty()), ScheduledMessagePolicy.MAX_TEXT_LENGTH)

    private fun oracleIsValidText(raw: String?): Boolean = oracleNormalize(raw).isNotEmpty()

    private fun oracleDelay(sendAtMillis: Long, nowMillis: Long): Long =
        sendAtMillis - nowMillis // wraps exactly like the production subtraction

    private fun oracleIsValidSendAt(sendAtMillis: Long, nowMillis: Long): Boolean {
        val delay = oracleDelay(sendAtMillis, nowMillis)
        return delay in ScheduledMessagePolicy.MIN_DELAY_MS..ScheduledMessagePolicy.MAX_DELAY_MS
    }

    private fun oracleClamp(sendAtMillis: Long, nowMillis: Long): Long {
        val min = nowMillis + ScheduledMessagePolicy.MIN_DELAY_MS
        val max = nowMillis + ScheduledMessagePolicy.MAX_DELAY_MS
        return minOf(maxOf(sendAtMillis, min), max)
    }

    private val alphabet = listOf(
        "a", "z", "A", "Z", "0", "9", " ", "_", "-", "@", "\t", "\n",
        "\u000B", "\u000C", "\u00A0", "你", "好", "é", "\uD83D\uDE00"
    )

    private fun randomRaw(r: Random): String {
        val n = if (r.nextInt(10) == 0) r.nextInt(3900, 4101) else r.nextInt(0, 61)
        return buildString {
            repeat(n) { append(alphabet[r.nextInt(alphabet.size)]) }
        }
    }

    private val maxDelay = ScheduledMessagePolicy.MAX_DELAY_MS

    // now values where now+MIN and now+MAX cannot wrap, so clampSendAt never throws.
    private fun randomSafeNow(r: Random): Long =
        if (r.nextInt(10) < 7) r.nextLong(0L, Long.MAX_VALUE - maxDelay)
        else listOf(0L, 1L, 1_000_000L, Long.MAX_VALUE - maxDelay - 1, Long.MAX_VALUE - maxDelay)[r.nextInt(5)]

    private val boundaryOffsets = listOf(
        59_999L, 60_000L, 60_001L, maxDelay - 1, maxDelay, maxDelay + 1,
        0L, -1L, -60_000L, -maxDelay, 2 * maxDelay, -2 * maxDelay
    )

    private fun randomSendAt(r: Random, now: Long): Long {
        val offset = if (r.nextInt(2) == 0) boundaryOffsets[r.nextInt(boundaryOffsets.size)]
        else r.nextLong(-2 * maxDelay, 2 * maxDelay + 1)
        return now + offset // wraps like production arithmetic
    }

    @Test
    fun randomTextNormalizeAndValidateMatchOracle() {
        val r = Random(3201)
        repeat(300) {
            val raw = if (r.nextInt(10) == 0) null else randomRaw(r)
            assertEquals(oracleNormalize(raw), ScheduledMessagePolicy.normalizeText(raw), "raw=$raw")
            assertEquals(oracleIsValidText(raw), ScheduledMessagePolicy.isValidText(raw), "raw=$raw")
        }
    }

    @Test
    fun randomSendWindowAndDelayMatchOracle() {
        val r = Random(3202)
        repeat(300) {
            val now = randomSafeNow(r)
            val sendAt = randomSendAt(r, now)
            assertEquals(
                oracleIsValidSendAt(sendAt, now),
                ScheduledMessagePolicy.isValidSendAt(sendAt, now),
                "now=$now sendAt=$sendAt"
            )
            val expectedDelay = maxOf(oracleDelay(sendAt, now), 0L)
            assertEquals(expectedDelay, ScheduledMessagePolicy.delayFromNow(sendAt, now), "now=$now sendAt=$sendAt")
        }
    }

    @Test
    fun randomClampStaysInsideWindow() {
        val r = Random(3203)
        repeat(300) {
            val now = randomSafeNow(r)
            val sendAt = randomSendAt(r, now)
            val clamped = ScheduledMessagePolicy.clampSendAt(sendAt, now)
            assertEquals(oracleClamp(sendAt, now), clamped, "now=$now sendAt=$sendAt")
            assertTrue(clamped in now + ScheduledMessagePolicy.MIN_DELAY_MS..now + maxDelay)
            // Clamping is idempotent and a no-op once inside the window.
            assertEquals(clamped, ScheduledMessagePolicy.clampSendAt(clamped, now))
            assertTrue(ScheduledMessagePolicy.isValidSendAt(clamped, now))
        }
    }

    @Test
    fun quickDelaysAreStrictlyIncreasingFromMinToMax() {
        val delays = ScheduledMessagePolicy.QUICK_DELAYS_MS
        assertEquals(14, delays.size)
        assertEquals(ScheduledMessagePolicy.MIN_DELAY_MS, delays.first())
        assertEquals(ScheduledMessagePolicy.MAX_DELAY_MS, delays.last())
        var previous = 0L
        val now = 1_000_000L
        delays.forEach { d ->
            assertTrue(d > previous, "delays strictly increasing: $d after $previous")
            assertTrue(d in ScheduledMessagePolicy.MIN_DELAY_MS..maxDelay)
            assertTrue(ScheduledMessagePolicy.isValidSendAt(now + d, now), "quick delay $d is a valid sendAt")
            previous = d
        }
    }

    @Test
    fun pendingCapMatchesOracle() {
        val r = Random(3205)
        val boundaryCounts = listOf(-1, 0, 1, 55, 56, 57, Int.MAX_VALUE, Int.MIN_VALUE)
        repeat(300) {
            val count = if (r.nextInt(2) == 0) boundaryCounts[r.nextInt(boundaryCounts.size)]
            else r.nextInt(-100, 200)
            assertEquals(
                count < ScheduledMessagePolicy.MAX_PENDING_PER_CHAT,
                ScheduledMessagePolicy.canAddMore(count),
                "count=$count"
            )
        }
    }

    @Test
    fun overflowAndWrapPins() {
        // now so close to Long.MAX_VALUE that now+MAX wraps below now+MIN:
        // coerceIn requires min <= max, so clampSendAt throws (current behaviour).
        val nearMax = Long.MAX_VALUE - 100_000L
        assertFailsWith<IllegalArgumentException> {
            ScheduledMessagePolicy.clampSendAt(1_000L, nearMax)
        }
        // The largest now where clampSendAt still works does not throw.
        val lastSafeNow = Long.MAX_VALUE - maxDelay
        assertEquals(lastSafeNow + maxDelay, ScheduledMessagePolicy.clampSendAt(Long.MAX_VALUE, lastSafeNow))
        // Wrapped delay: MIN_VALUE - MAX_VALUE wraps to 1, outside the window.
        assertFalse(ScheduledMessagePolicy.isValidSendAt(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(1L, ScheduledMessagePolicy.delayFromNow(Long.MIN_VALUE, Long.MAX_VALUE))
        // Exact lower bound from a past sendAt.
        assertTrue(ScheduledMessagePolicy.isValidSendAt(Long.MAX_VALUE, Long.MAX_VALUE - 60_000L))
    }
}
