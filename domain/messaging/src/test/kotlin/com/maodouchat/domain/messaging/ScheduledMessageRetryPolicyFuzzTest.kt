// Fuzz / random-oracle tests for ScheduledMessageRetryPolicy.shouldAbandon
// (checklist line 931, 137th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: abandon once the
// run attempt count reaches MAX_TRANSIENT_RETRIES (5), so 4 keeps retrying
// and 5 gives up. The constant itself is pinned to 5 here.
// No ScheduledMessageRetryPolicy fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies -- the bare-name
// `shouldAbandon` hit in fuzz-108 is the two-argument
// SenderKeyRetryPolicy.shouldAbandon, a different function).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun shouldAbandonOracle(runAttemptCount: Int): Boolean =
    runAttemptCount >= 5

// ---------- generators (mirrored by Oracle137 GEN, seed 137001) ----------

private val SMR_PINNED_INTS = intArrayOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -100, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 100,
    Int.MAX_VALUE - 1, Int.MAX_VALUE
)

private fun genSmrInt(rnd: Random): Int = when (rnd.nextInt(4)) {
    0 -> rnd.nextInt(-10, 21)
    1 -> rnd.nextInt()
    2 -> rnd.nextInt(-1000, 1001)
    else -> SMR_PINNED_INTS[rnd.nextInt(SMR_PINNED_INTS.size)]
}

class ScheduledMessageRetryPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The cap the oracle hardcodes is the production constant itself.
        assertEquals(5, ScheduledMessageRetryPolicy.MAX_TRANSIENT_RETRIES)
        for (n in SMR_PINNED_INTS) {
            assertEquals(shouldAbandonOracle(n), ScheduledMessageRetryPolicy.shouldAbandon(n))
        }
        // One below the threshold keeps retrying; at it, abandon.
        assertEquals(false, ScheduledMessageRetryPolicy.shouldAbandon(4))
        assertEquals(true, ScheduledMessageRetryPolicy.shouldAbandon(5))
        // Negative counts never abandon: the predicate is a plain >=.
        assertEquals(false, ScheduledMessageRetryPolicy.shouldAbandon(-1))
        assertEquals(false, ScheduledMessageRetryPolicy.shouldAbandon(Int.MIN_VALUE))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle137.genSmr consumption order exactly, so seed
        // 137001 replays the same 1500 GEN lines drive137.py ran.
        val rnd = Random(137001)
        repeat(1500) {
            val n = genSmrInt(rnd)
            assertEquals(shouldAbandonOracle(n), ScheduledMessageRetryPolicy.shouldAbandon(n))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every count in a dense band around the threshold.
        for (n in -1000..1000) {
            assertEquals(shouldAbandonOracle(n), ScheduledMessageRetryPolicy.shouldAbandon(n))
        }
        // Int extremes and their neighbors.
        for (n in listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            assertEquals(shouldAbandonOracle(n), ScheduledMessageRetryPolicy.shouldAbandon(n))
        }
        // Powers of two and their neighbors: no hidden second threshold.
        for (k in 0..30) {
            val p = 1 shl k
            for (n in listOf(p - 1, p, p + 1, -p - 1, -p, -p + 1)) {
                assertEquals(shouldAbandonOracle(n), ScheduledMessageRetryPolicy.shouldAbandon(n))
            }
        }
    }
}
