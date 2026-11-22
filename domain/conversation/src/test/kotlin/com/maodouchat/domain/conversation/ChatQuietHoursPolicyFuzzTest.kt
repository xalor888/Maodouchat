package com.maodouchat.domain.conversation

import com.maodouchat.core.model.QuietWindow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ChatQuietHoursPolicy.shouldSuppress]
 * (checklist line 931, 35th block). Test-only, zero production changes.
 *
 * The oracle restates the interval rule independently: clamp start, end and
 * minute into [0, 1439]; disabled or start == end never suppresses; otherwise
 * [start, end) for a daytime window and minute >= start || minute < end for
 * an overnight window. A Python mini-model differentially checks the oracle
 * on 5000 random windows (0 diffs) before this file was written.
 */
class ChatQuietHoursPolicyFuzzTest {

    private fun oracleSuppress(enabled: Boolean, start: Int, end: Int, minute: Int): Boolean {
        if (!enabled) return false
        val s = start.coerceIn(0, 1439)
        val e = end.coerceIn(0, 1439)
        if (s == e) return false
        val m = minute.coerceIn(0, 1439)
        return if (s < e) m in s until e else m >= s || m < e
    }

    @Test
    fun shouldSuppressRandomOracle() {
        val rnd = Random(35)
        repeat(500) {
            val enabled = rnd.nextBoolean()
            val start = rnd.nextInt(-100, 1600)
            val end = rnd.nextInt(-100, 1600)
            val minute = rnd.nextInt(-100, 1600)
            assertEquals(
                oracleSuppress(enabled, start, end, minute),
                ChatQuietHoursPolicy.shouldSuppress(QuietWindow(enabled, start, end), minute),
                "enabled=$enabled start=$start end=$end minute=$minute"
            )
        }
    }

    @Test
    fun shouldSuppressPins() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 600), 600))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 900), 600))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 900), 900))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 900), 599))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 1320, 360), 1400))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 1320, 360), 0))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 1320, 360), 360))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 1320, 360), 1319))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(false, 600, 900), 700))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, -50, 900), 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 1500), 1438))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 1500), 1439))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 900), -5))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, 600, 900), 2000))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow(true, -10, -10), 0))
    }
}
