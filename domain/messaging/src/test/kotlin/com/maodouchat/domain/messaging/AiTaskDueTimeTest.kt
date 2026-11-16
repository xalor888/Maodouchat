package com.maodouchat.domain.messaging

import java.util.Calendar
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AiTaskDueTimeTest {

    private fun localMillis(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    @Test
    fun formatsFullDateTime() {
        assertEquals("2026-04-10 14:30", formatAiTaskDue(localMillis(2026, 4, 10, 14, 30), Locale.US))
    }

    @Test
    fun padsSingleDigitMonthDayAndMinutes() {
        assertEquals("2026-01-05 09:05", formatAiTaskDue(localMillis(2026, 1, 5, 9, 5), Locale.US))
    }

    @Test
    fun midnightFormatsAsZero() {
        assertEquals("2026-11-09 00:00", formatAiTaskDue(localMillis(2026, 11, 9, 0, 0), Locale.US))
    }

    @Test
    fun dueLabelJoinsBothParts() {
        assertEquals("tomorrow · 2026-04-10 14:30", aiTaskDueLabel("tomorrow", "2026-04-10 14:30"))
    }

    @Test
    fun dueLabelDedupesIdenticalParts() {
        assertEquals("2026-04-10 14:30", aiTaskDueLabel("2026-04-10 14:30", "2026-04-10 14:30"))
    }

    @Test
    fun dueLabelDropsBlankDueText() {
        assertEquals("2026-04-10 14:30", aiTaskDueLabel("   ", "2026-04-10 14:30"))
    }

    @Test
    fun dueLabelIsNullWhenBothEmpty() {
        assertEquals(null, aiTaskDueLabel(null, null))
        assertEquals(null, aiTaskDueLabel("  ", null))
    }

    @Test
    fun overdueWhenUncompletedAndPastDue() {
        assertEquals(true, isAiTaskOverdue(false, 1000L, 2000L))
    }

    @Test
    fun notOverdueWhenCompleted() {
        assertEquals(false, isAiTaskOverdue(true, 1000L, 2000L))
    }

    @Test
    fun notOverdueWithoutDueTimeOrInFuture() {
        assertEquals(false, isAiTaskOverdue(false, null, 2000L))
        assertEquals(false, isAiTaskOverdue(false, 3000L, 2000L))
        assertEquals(false, isAiTaskOverdue(false, 2000L, 2000L))
    }
}
