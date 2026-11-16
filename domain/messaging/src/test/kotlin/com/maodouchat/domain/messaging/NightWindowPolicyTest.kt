package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightWindowPolicyTest {

    @Test
    fun normalWindowInside() {
        assertTrue(NightWindowPolicy.isWithinNightWindow(12 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun normalWindowOutside() {
        assertFalse(NightWindowPolicy.isWithinNightWindow(18 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun normalWindowStartIsInclusive() {
        assertTrue(NightWindowPolicy.isWithinNightWindow(9 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun normalWindowEndIsExclusive() {
        assertFalse(NightWindowPolicy.isWithinNightWindow(17 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun overnightWindowInsideBothSides() {
        assertTrue(NightWindowPolicy.isWithinNightWindow(22 * 60, 21 * 60, 7 * 60))
        assertTrue(NightWindowPolicy.isWithinNightWindow(6 * 60, 21 * 60, 7 * 60))
    }

    @Test
    fun overnightWindowOutside() {
        assertFalse(NightWindowPolicy.isWithinNightWindow(12 * 60, 21 * 60, 7 * 60))
        assertFalse(NightWindowPolicy.isWithinNightWindow(7 * 60, 21 * 60, 7 * 60))
    }

    @Test
    fun midnightFormatsAsZeroZero() {
        assertEquals("00:00", NightWindowPolicy.formatNightWindowHour(0))
    }

    @Test
    fun singleDigitHourIsZeroPadded() {
        assertEquals("01:00", NightWindowPolicy.formatNightWindowHour(60))
        assertEquals("05:00", NightWindowPolicy.formatNightWindowHour(5 * 60))
    }

    @Test
    fun lateNightHours() {
        assertEquals("21:00", NightWindowPolicy.formatNightWindowHour(21 * 60))
        assertEquals("23:00", NightWindowPolicy.formatNightWindowHour(23 * 60 + 59))
    }
}
