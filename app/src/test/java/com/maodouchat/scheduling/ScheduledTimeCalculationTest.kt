package com.maodouchat.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduledTimeCalculationTest {

    @Test
    fun `recurrence preserves wall clock across DST transition`() {
        // US Eastern Time: Clocks spring forward on 2026-03-08 at 02:00 -> 03:00
        val zoneId = "America/New_York"
        val zone = ZoneId.of(zoneId)

        // 2026-03-07 09:00:00 EST (UTC-5)
        val initialZdt = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, zone)
        val initialMillis = initialZdt.toInstant().toEpochMilli()

        val dailyIntervalMs = 24 * 60 * 60 * 1000L // 1 day

        val nextMillis = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = initialMillis,
            repeatIntervalMs = dailyIntervalMs,
            weekdaysOnly = false,
            timeZoneId = zoneId,
            nowMillis = initialMillis + 1000L,
        )

        val nextZdt = Instant.ofEpochMilli(nextMillis).atZone(zone)

        // Wall-clock time should still be 09:00:00 AM on March 8 (EDT, UTC-4), NOT 10:00:00 AM
        assertEquals(2026, nextZdt.year)
        assertEquals(3, nextZdt.monthValue)
        assertEquals(8, nextZdt.dayOfMonth)
        assertEquals(9, nextZdt.hour)
        assertEquals(0, nextZdt.minute)
    }

    @Test
    fun `weekdays-only skips weekend to Monday`() {
        val zoneId = "UTC"
        val zone = ZoneId.of(zoneId)

        // Friday 2026-09-04 10:00:00 UTC
        val fridayZdt = ZonedDateTime.of(2026, 9, 4, 10, 0, 0, 0, zone)
        val fridayMillis = fridayZdt.toInstant().toEpochMilli()

        val dailyIntervalMs = 24 * 60 * 60 * 1000L

        val nextMillis = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = fridayMillis,
            repeatIntervalMs = dailyIntervalMs,
            weekdaysOnly = true,
            timeZoneId = zoneId,
            nowMillis = fridayMillis + 1000L,
        )

        val nextZdt = Instant.ofEpochMilli(nextMillis).atZone(zone)

        // Should skip Saturday (Sept 5) and Sunday (Sept 6) and advance to Monday Sept 7
        assertEquals(DayOfWeek.MONDAY, nextZdt.dayOfWeek)
        assertEquals(2026, nextZdt.year)
        assertEquals(9, nextZdt.monthValue)
        assertEquals(7, nextZdt.dayOfMonth)
        assertEquals(10, nextZdt.hour)
    }

    @Test
    fun `catches up past nowMillis if multiple periods have elapsed`() {
        val initialMillis = 1_000_000L
        val interval = 10_000L
        val currentNow = 1_045_000L

        val nextMillis = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = initialMillis,
            repeatIntervalMs = interval,
            weekdaysOnly = false,
            timeZoneId = "UTC",
            nowMillis = currentNow,
        )

        assertTrue(nextMillis > currentNow)
        assertEquals(1_050_000L, nextMillis)
    }

    @Test
    fun `zero interval returns unchanged sendAtMillis`() {
        val sendAt = 500_000L
        val next = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = sendAt,
            repeatIntervalMs = 0L,
            weekdaysOnly = false,
        )
        assertEquals(sendAt, next)
    }
}
