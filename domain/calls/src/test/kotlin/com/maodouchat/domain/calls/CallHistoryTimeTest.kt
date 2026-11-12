package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class CallHistoryTimeTest {

    @Test
    fun zeroDurationReturnsEmpty() {
        assertEquals("", CallHistoryTime.formatDuration(0L))
    }

    @Test
    fun negativeDurationReturnsEmpty() {
        assertEquals("", CallHistoryTime.formatDuration(-500L))
    }

    @Test
    fun subSecondDurationReturnsZeroZero() {
        assertEquals("0:00", CallHistoryTime.formatDuration(999L))
    }

    @Test
    fun oneSecondDuration() {
        assertEquals("0:01", CallHistoryTime.formatDuration(1000L))
    }

    @Test
    fun fiftyNineSecondsDuration() {
        assertEquals("0:59", CallHistoryTime.formatDuration(59_999L))
    }

    @Test
    fun oneMinuteDuration() {
        assertEquals("1:00", CallHistoryTime.formatDuration(60_000L))
    }

    @Test
    fun oneMinuteOneSecondDuration() {
        assertEquals("1:01", CallHistoryTime.formatDuration(61_000L))
    }

    @Test
    fun fiftyNineMinutesFiftyNineSecondsDuration() {
        assertEquals("59:59", CallHistoryTime.formatDuration(3_599_000L))
    }

    @Test
    fun sixtyMinutesDuration() {
        assertEquals("60:00", CallHistoryTime.formatDuration(3_600_000L))
    }

    @Test
    fun justNowBelowOneMinute() {
        assertEquals(CallRelativeTime.JustNow, CallHistoryTime.relativeTime(1_000_000_000_000L, 1_000_000_000_000L - 59_999L))
    }

    @Test
    fun minutesBoundary() {
        assertEquals(CallRelativeTime.MinutesAgo(1), CallHistoryTime.relativeTime(1_000_000_000_000L, 1_000_000_000_000L - 60_000L))
    }

    @Test
    fun fiftyNineMinutesBoundary() {
        assertEquals(CallRelativeTime.MinutesAgo(59), CallHistoryTime.relativeTime(1_000_000_000_000L, 1_000_000_000_000L - 3_599_999L))
    }

    @Test
    fun hoursBoundary() {
        assertEquals(CallRelativeTime.HoursAgo(1), CallHistoryTime.relativeTime(1_000_000_000_000L, 1_000_000_000_000L - 3_600_000L))
    }

    @Test
    fun daysBoundary() {
        assertEquals(CallRelativeTime.DaysAgo(1), CallHistoryTime.relativeTime(1_000_000_000_000L, 1_000_000_000_000L - 86_400_000L))
    }

    @Test
    fun sixDaysBoundary() {
        assertEquals(CallRelativeTime.DaysAgo(6), CallHistoryTime.relativeTime(1_000_000_000_000L, 1_000_000_000_000L - 7L * 86_400_000L + 1L))
    }

    @Test
    fun weekBoundaryFallsBackToDate() {
        val millis = 1_000_000_000_000L - 7L * 86_400_000L
        assertEquals(CallRelativeTime.Date(millis), CallHistoryTime.relativeTime(1_000_000_000_000L, millis))
    }
}
