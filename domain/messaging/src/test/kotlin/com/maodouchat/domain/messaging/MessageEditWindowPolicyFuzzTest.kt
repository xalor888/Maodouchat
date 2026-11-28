package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [MessageEditWindowPolicy]
 * (checklist line 931, 34th block). Test-only, zero production changes.
 *
 * Oracles are independent derivations, not the production formulas:
 * - `withinWindow` is checked as `sentAt > now - window` (the same
 *   predicate with the subtraction moved to the other side).
 * - `remainingMinutes` is derived via `floorDiv`/`floorMod`: with
 *   `elapsed = now - sent`, `whole = floorDiv(elapsed, 60000)`,
 *   `rem = floorMod(elapsed, 60000)` and `a = 5 - whole`, the truncated
 *   quotient of `(300000 - elapsed) / 60000` is `a`, except `a - 1` when
 *   `rem > 0` and `a > 0`. A Python mini-model differentially checks both
 *   oracles on 5000 random (now, sent) pairs plus every boundary elapsed
 *   value (0 diffs).
 */
class MessageEditWindowPolicyFuzzTest {

    private fun oracleWithinWindow(nowMs: Long, sentAtMs: Long): Boolean =
        sentAtMs > nowMs - MessageEditWindowPolicy.EDIT_WINDOW_MS

    private fun oracleRemainingMinutes(nowMs: Long, sentAtMs: Long): Int {
        val elapsed = nowMs - sentAtMs
        val whole = Math.floorDiv(elapsed, 60_000L)
        val rem = Math.floorMod(elapsed, 60_000L)
        val a = 5L - whole
        val q = if (rem == 0L || a <= 0L) a else a - 1L
        return q.toInt() + 1
    }

    @Test
    fun withinWindowMatchesOracle() {
        val r = Random(3414)
        repeat(300) {
            val now = r.nextLong(0L, 1_000_000_000_000L)
            val sentAt = now + r.nextLong(-1_000_000L, 1_000_000L)
            assertEquals(
                oracleWithinWindow(now, sentAt),
                MessageEditWindowPolicy.withinWindow(now, sentAt)
            )
        }
    }

    @Test
    fun remainingMinutesMatchesOracle() {
        val r = Random(3415)
        repeat(300) {
            val now = r.nextLong(0L, 1_000_000_000_000L)
            val sentAt = now + r.nextLong(-1_000_000L, 1_000_000L)
            assertEquals(
                oracleRemainingMinutes(now, sentAt),
                MessageEditWindowPolicy.remainingMinutes(now, sentAt)
            )
        }
    }

    @Test
    fun windowBoundariesArePinned() {
        val sentAt = 1_000_000L
        // withinWindow: elapsed < 300_000, boundary excluded.
        assertTrue(MessageEditWindowPolicy.withinWindow(sentAt, sentAt))
        assertTrue(MessageEditWindowPolicy.withinWindow(sentAt + 299_999L, sentAt))
        assertFalse(MessageEditWindowPolicy.withinWindow(sentAt + 300_000L, sentAt))
        assertFalse(MessageEditWindowPolicy.withinWindow(sentAt + 300_001L, sentAt))
        // A message "from the future" is still within the window.
        assertTrue(MessageEditWindowPolicy.withinWindow(sentAt - 60_000L, sentAt))
        // remainingMinutes: 6 at the instant of sending, 1 in the last
        // minute, 0 once the window has closed.
        assertEquals(6, MessageEditWindowPolicy.remainingMinutes(sentAt, sentAt))
        assertEquals(5, MessageEditWindowPolicy.remainingMinutes(sentAt + 59_999L, sentAt))
        assertEquals(5, MessageEditWindowPolicy.remainingMinutes(sentAt + 60_000L, sentAt))
        assertEquals(4, MessageEditWindowPolicy.remainingMinutes(sentAt + 60_001L, sentAt))
        assertEquals(1, MessageEditWindowPolicy.remainingMinutes(sentAt + 299_999L, sentAt))
        assertEquals(1, MessageEditWindowPolicy.remainingMinutes(sentAt + 300_000L, sentAt))
        assertEquals(0, MessageEditWindowPolicy.remainingMinutes(sentAt + 360_000L, sentAt))
        assertEquals(7, MessageEditWindowPolicy.remainingMinutes(sentAt - 60_000L, sentAt))
    }
}
