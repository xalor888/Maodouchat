// Fuzz / random-oracle tests for ChatListTimeFormat.daysBetween
// (checklist line 931, 140th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: both calendars
// are truncated to local midnight, then the millisecond difference is
// divided by one day (Kotlin Long division truncates toward zero, so a
// negative span under a day yields 0, not -1). Only daysBetween is
// taken: formatChatTime in the same file reads the wall clock and the
// default timezone/locale, so it is not a pure function. No fuzz test
// covered daysBetween before this block (content-grep dedup 0 hits on
// all remote refactor branches and main's FuzzTest files for
// ChatListTimeFormat / daysBetween / formatChatTime). A hand-written
// ChatListTimeFormatTest covers three fixed cases; this file adds the
// random oracle plus day/hour sweeps. The test-side oracle truncates
// with Calendar.getInstance() in the same JVM, so it agrees with
// production under any default timezone; the differential drive pins
// the JVM to Asia/Shanghai and models that zone in Python.
package com.maodouchat.domain.conversation

import java.util.Calendar
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

private fun midnightMsOracle(ms: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

private fun daysBetweenOracle(earlierMs: Long, laterMs: Long): Int =
    ((midnightMsOracle(laterMs) - midnightMsOracle(earlierMs)) / 86400000L).toInt()

// ---------- generators (mirrored by Oracle140 GEN, seed 140002) ----------

private data class CtCase(
    val earlierMs: Long,
    val laterMs: Long,
)

private fun genCtCase(rnd: Random): CtCase {
    val base = 1577836800000L + rnd.nextLong(0L, 8L * 365 * 86400000L)
    val delta = rnd.nextLong(-30L * 86400000L, 30L * 86400000L + 1)
    return CtCase(base, base + delta)
}

private fun ctCalendar(ms: Long): Calendar =
    Calendar.getInstance().apply { timeInMillis = ms }

private fun calMs(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0, second: Int = 0, ms: Int = 0): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
        set(Calendar.MILLISECOND, ms)
    }.timeInMillis

class ChatListTimeFormatFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Same calendar day at different hours is 0 days, not 24h spans.
        assertEquals(0, daysBetween(ctCalendar(calMs(2026, 4, 10, 0)), ctCalendar(calMs(2026, 4, 10, 23))))
        assertEquals(1, daysBetween(ctCalendar(calMs(2026, 4, 10)), ctCalendar(calMs(2026, 4, 11))))
        assertEquals(1, daysBetween(ctCalendar(calMs(2026, 4, 30)), ctCalendar(calMs(2026, 5, 1))))
        assertEquals(1, daysBetween(ctCalendar(calMs(2026, 12, 31, 23)), ctCalendar(calMs(2027, 1, 1, 0))))
        // Leap year: Feb 28 to Mar 1 is two calendar days in 2024.
        assertEquals(1, daysBetween(ctCalendar(calMs(2026, 2, 28, 12)), ctCalendar(calMs(2026, 3, 1, 12))))
        assertEquals(2, daysBetween(ctCalendar(calMs(2024, 2, 28, 12)), ctCalendar(calMs(2024, 3, 1, 12))))
        // Reversed spans go negative; the same instant is 0.
        assertEquals(-1, daysBetween(ctCalendar(calMs(2026, 4, 11)), ctCalendar(calMs(2026, 4, 10))))
        assertEquals(0, daysBetween(ctCalendar(calMs(2026, 4, 10, 12)), ctCalendar(calMs(2026, 4, 10, 12))))
        // Just under a day within one calendar day is 0; exactly one
        // day at midnight is 1; two days minus a millisecond is 1.
        val start = calMs(2026, 4, 10, 0)
        assertEquals(0, daysBetween(ctCalendar(start), ctCalendar(start + 86399999L)))
        assertEquals(1, daysBetween(ctCalendar(start), ctCalendar(start + 86400000L)))
        assertEquals(1, daysBetween(ctCalendar(start), ctCalendar(start + 2 * 86400000L - 1)))
        // A negative span under a day truncates toward zero, not -1.
        assertEquals(-1, daysBetween(ctCalendar(calMs(2026, 4, 11, 12)), ctCalendar(calMs(2026, 4, 10, 11))))
        assertEquals(0, daysBetween(ctCalendar(calMs(2026, 4, 10, 12)), ctCalendar(calMs(2026, 4, 10, 0))))
        assertEquals(-1, daysBetween(ctCalendar(calMs(2026, 4, 10, 12)), ctCalendar(calMs(2026, 4, 9, 12))))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle140.genCt consumption order exactly, so seed
        // 140002 replays the same 1500 GEN lines drive140.py ran.
        val rnd = Random(140002)
        repeat(1500) {
            val c = genCtCase(rnd)
            assertEquals(
                daysBetweenOracle(c.earlierMs, c.laterMs),
                daysBetween(ctCalendar(c.earlierMs), ctCalendar(c.laterMs))
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Day offsets -5..5 x three hours on each side, from one base day.
        val base = calMs(2026, 6, 15, 12)
        for (dayOffset in -5..5) {
            for (eHour in listOf(0, 12, 23)) {
                for (lHour in listOf(0, 12, 23)) {
                    val e = base + (eHour - 12) * 3600000L
                    val l = base + dayOffset * 86400000L + (lHour - 12) * 3600000L
                    assertEquals(
                        daysBetweenOracle(e, l),
                        daysBetween(ctCalendar(e), ctCalendar(l))
                    )
                }
            }
        }
        // Every month-end of 2026 rolls to the next month's first day.
        for (month in 1..12) {
            val lastDay = when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> 28
            }
            val nextMonth = if (month == 12) 1 else month + 1
            val nextYear = if (month == 12) 2027 else 2026
            val e = calMs(2026, month, lastDay, 12)
            val l = calMs(nextYear, nextMonth, 1, 12)
            assertEquals(1, daysBetween(ctCalendar(e), ctCalendar(l)))
            assertEquals(
                daysBetweenOracle(e, l),
                daysBetween(ctCalendar(e), ctCalendar(l))
            )
        }
    }
}
