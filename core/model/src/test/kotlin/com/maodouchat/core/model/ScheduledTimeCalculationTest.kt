package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ScheduledTimeCalculationTest {

    @Test
    fun `non-positive interval returns original send time`() {
        assertEquals(
            1_700_000_000_000L,
            ScheduledTimeCalculation.nextRepeatSendAt(
                sendAtMillis = 1_700_000_000_000L,
                repeatIntervalMs = 0L,
                weekdaysOnly = false,
                nowMillis = 1_800_000_000_000L,
            ),
        )
    }

    @Test
    fun `weekdays only skips saturday and sunday`() {
        // 2026-10-30 is a Friday; next daily repeat must land on Monday 2026-11-02.
        val friday = java.time.ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val next = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = friday,
            repeatIntervalMs = 24 * 60 * 60 * 1000L,
            weekdaysOnly = true,
            timeZoneId = "UTC",
            nowMillis = friday + 1000L,
        )
        val zdt = java.time.Instant.ofEpochMilli(next).atZone(ZoneId.of("UTC"))
        assertEquals(2026, zdt.year)
        assertEquals(11, zdt.monthValue)
        assertEquals(2, zdt.dayOfMonth)
    }

    @Test
    fun `unknown zone falls back to system default`() {
        assertEquals(
            ZoneId.systemDefault(),
            ScheduledTimeCalculation.resolveZone("No/Such_Zone"),
        )
    }
}
