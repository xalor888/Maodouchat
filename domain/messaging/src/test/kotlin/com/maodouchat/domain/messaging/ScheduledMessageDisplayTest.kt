package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledMessageDisplayTest {

    @Test
    fun muteRemainingPicksMinutesBelowOneHour() {
        assertEquals(MuteRemaining.Minutes(1L), ScheduledMessageDisplay.muteRemaining(60_000L))
        assertEquals(MuteRemaining.Minutes(30L), ScheduledMessageDisplay.muteRemaining(30 * 60_000L))
        assertEquals(MuteRemaining.Minutes(59L), ScheduledMessageDisplay.muteRemaining(59 * 60_000L))
    }

    @Test
    fun muteRemainingFallsBackToJustNowAtOrBelowZero() {
        assertEquals(MuteRemaining.JustNow, ScheduledMessageDisplay.muteRemaining(0L))
        assertEquals(MuteRemaining.JustNow, ScheduledMessageDisplay.muteRemaining(59_000L))
        assertEquals(MuteRemaining.JustNow, ScheduledMessageDisplay.muteRemaining(-5 * 60_000L))
    }

    @Test
    fun muteRemainingPicksHoursBetweenOneHourAndOneDay() {
        assertEquals(MuteRemaining.Hours(1L), ScheduledMessageDisplay.muteRemaining(60 * 60_000L))
        assertEquals(MuteRemaining.Hours(1L), ScheduledMessageDisplay.muteRemaining(90 * 60_000L))
        assertEquals(MuteRemaining.Hours(23L), ScheduledMessageDisplay.muteRemaining(23 * 60 * 60_000L))
        assertEquals(MuteRemaining.Hours(23L), ScheduledMessageDisplay.muteRemaining(23 * 60 * 60_000L + 59 * 60_000L))
    }

    @Test
    fun muteRemainingPicksDaysFromOneDayUp() {
        assertEquals(MuteRemaining.Days(1L), ScheduledMessageDisplay.muteRemaining(24 * 60 * 60_000L))
        assertEquals(MuteRemaining.Days(1L), ScheduledMessageDisplay.muteRemaining(25 * 60 * 60_000L))
        assertEquals(MuteRemaining.Days(7L), ScheduledMessageDisplay.muteRemaining(7 * 24 * 60 * 60_000L))
    }

    @Test
    fun weekdaysOnlyWinsOverInterval() {
        assertEquals(
            ScheduleRepeatKind.Weekdays,
            ScheduledMessageDisplay.repeatKind(24L * 3600_000L, weekdaysOnly = true),
        )
    }

    @Test
    fun exactDailyAndWeeklyIntervalsGetTheirOwnKind() {
        assertEquals(ScheduleRepeatKind.Daily, ScheduledMessageDisplay.repeatKind(24L * 3600_000L, weekdaysOnly = false))
        assertEquals(ScheduleRepeatKind.Weekly, ScheduledMessageDisplay.repeatKind(7L * 24 * 3600_000L, weekdaysOnly = false))
    }

    @Test
    fun arbitraryPositiveIntervalFallsBackToCustom() {
        assertEquals(ScheduleRepeatKind.Custom, ScheduledMessageDisplay.repeatKind(3L * 3600_000L, weekdaysOnly = false))
        assertEquals(ScheduleRepeatKind.Custom, ScheduledMessageDisplay.repeatKind(23L * 3600_000L, weekdaysOnly = false))
    }

    @Test
    fun nonPositiveIntervalReturnsNull() {
        assertNull(ScheduledMessageDisplay.repeatKind(0L, weekdaysOnly = false))
        assertNull(ScheduledMessageDisplay.repeatKind(-1L, weekdaysOnly = false))
    }

    @Test
    fun remainingRepeatCountAppendsRemainingOccurrences() {
        assertEquals(3, ScheduledMessageDisplay.remainingRepeatCount(5, 2))
        assertEquals(4, ScheduledMessageDisplay.remainingRepeatCount(4, 0))
        assertEquals(0, ScheduledMessageDisplay.remainingRepeatCount(3, 3))
    }

    @Test
    fun overSentOccurrencesCoerceRemainingToZero() {
        assertEquals(0, ScheduledMessageDisplay.remainingRepeatCount(2, 9))
    }

    @Test
    fun noRepeatCountMeansNoRemainingSuffix() {
        assertNull(ScheduledMessageDisplay.remainingRepeatCount(0, 3))
        assertNull(ScheduledMessageDisplay.remainingRepeatCount(-2, 1))
    }
}
