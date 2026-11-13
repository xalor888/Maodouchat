package com.maodouchat.domain.messaging

import java.util.Calendar
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AuditTimeTest {

    private fun localMillis(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    @Test
    fun formatsMonthDayHourMinute() {
        assertEquals("4/10 14:30", formatAuditTime(localMillis(2026, 4, 10, 14, 30), Locale.US))
    }

    @Test
    fun padsSingleDigitMonthDayAndMinutes() {
        assertEquals("1/5 09:05", formatAuditTime(localMillis(2026, 1, 5, 9, 5), Locale.US))
    }

    @Test
    fun midnightFormatsAsZero() {
        assertEquals("11/9 00:00", formatAuditTime(localMillis(2026, 11, 9, 0, 0), Locale.US))
    }
}
