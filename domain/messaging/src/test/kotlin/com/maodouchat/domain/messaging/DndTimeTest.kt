package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class DndTimeTest {

    @Test
    fun midnightIsZero() {
        assertEquals("00:00", formatDndTime(0))
    }

    @Test
    fun hourAndMinute() {
        assertEquals("01:00", formatDndTime(60))
        assertEquals("01:30", formatDndTime(90))
        assertEquals("23:59", formatDndTime(1439))
    }

    @Test
    fun singleDigitMinuteIsPadded() {
        assertEquals("00:05", formatDndTime(5))
        assertEquals("01:01", formatDndTime(61))
    }

    @Test
    fun outOfRangeIsClamped() {
        assertEquals("00:00", formatDndTime(-5))
        assertEquals("23:59", formatDndTime(1500))
    }
}
