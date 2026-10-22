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

    @Test
    fun `negative interval returns unchanged sendAtMillis`() {
        // 边界：负间隔与零间隔同路径，不前推。
        val sendAt = 500_000L
        val next = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = sendAt,
            repeatIntervalMs = -60_000L,
            weekdaysOnly = false,
        )
        assertEquals(sendAt, next)
    }

    @Test
    fun `resolveZone falls back to system default on blank or invalid id`() {
        val systemDefault = ZoneId.systemDefault()
        assertEquals(systemDefault, ScheduledTimeCalculation.resolveZone(null))
        assertEquals(systemDefault, ScheduledTimeCalculation.resolveZone(""))
        assertEquals(systemDefault, ScheduledTimeCalculation.resolveZone("Not_A_Real_Zone"))
    }

    @Test
    fun `weekdays-only advances Saturday start to Monday`() {
        val zoneId = "UTC"
        val zone = ZoneId.of(zoneId)

        // Saturday 2026-09-05 10:00:00 UTC
        val satZdt = ZonedDateTime.of(2026, 9, 5, 10, 0, 0, 0, zone)
        val satMillis = satZdt.toInstant().toEpochMilli()

        val nextMillis = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = satMillis,
            repeatIntervalMs = 24 * 60 * 60 * 1000L,
            weekdaysOnly = true,
            timeZoneId = zoneId,
            nowMillis = satMillis + 1000L,
        )

        val nextZdt = Instant.ofEpochMilli(nextMillis).atZone(zone)

        // 起始落在周六：周日候选直接被工作日跳过，落到周一 09-07
        assertEquals(DayOfWeek.MONDAY, nextZdt.dayOfWeek)
        assertEquals(7, nextZdt.dayOfMonth)
        assertEquals(10, nextZdt.hour)
    }

    @Test
    fun `three-day step advances whole calendar days`() {
        val zoneId = "UTC"
        val zone = ZoneId.of(zoneId)

        // Wednesday 2026-09-02 10:00:00 UTC
        val wedZdt = ZonedDateTime.of(2026, 9, 2, 10, 0, 0, 0, zone)
        val wedMillis = wedZdt.toInstant().toEpochMilli()

        val nextMillis = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = wedMillis,
            repeatIntervalMs = 3 * 24 * 60 * 60 * 1000L,
            weekdaysOnly = false,
            timeZoneId = zoneId,
            nowMillis = wedMillis + 1000L,
        )

        val nextZdt = Instant.ofEpochMilli(nextMillis).atZone(zone)

        // 整天数步进走日历日：Wed + 3 = Sat 09-05
        assertEquals(DayOfWeek.SATURDAY, nextZdt.dayOfWeek)
        assertEquals(5, nextZdt.dayOfMonth)
        assertEquals(10, nextZdt.hour)
    }

    @Test
    fun `recurrence preserves wall clock across fall-back DST transition`() {
        // US Eastern: 2026-11-01 02:00 EDT -> 01:00 EST (fall back)
        val zoneId = "America/New_York"
        val zone = ZoneId.of(zoneId)

        // 2026-10-31 09:00:00 EDT (UTC-4)
        val initialZdt = ZonedDateTime.of(2026, 10, 31, 9, 0, 0, 0, zone)
        val initialMillis = initialZdt.toInstant().toEpochMilli()

        val nextMillis = ScheduledTimeCalculation.nextRepeatSendAt(
            sendAtMillis = initialMillis,
            repeatIntervalMs = 24 * 60 * 60 * 1000L,
            weekdaysOnly = false,
            timeZoneId = zoneId,
            nowMillis = initialMillis + 1000L,
        )

        val nextZdt = Instant.ofEpochMilli(nextMillis).atZone(zone)

        // 挂钟仍为 11-01 09:00（EST，UTC-5），不是 08:00
        assertEquals(11, nextZdt.monthValue)
        assertEquals(1, nextZdt.dayOfMonth)
        assertEquals(9, nextZdt.hour)
    }
}
