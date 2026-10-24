package com.maodouchat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeDisconnectPolicyTest {
    @Test
    fun bannerDelayCoversTypicalReconnectFlap() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS >= 2_000L)
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS <= 5_000L)
    }

    @Test
    fun bannerDelayPinsAtExactConfiguredValue() {
        assertEquals(2_500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS)
    }

    @Test
    fun bannerDelayIsPositive() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS > 0L)
    }

    @Test
    fun bannerDelayReadsAreStable() {
        val first = RealtimeDisconnectPolicy.BANNER_DELAY_MS
        val second = RealtimeDisconnectPolicy.BANNER_DELAY_MS
        assertEquals(first, second)
    }

    @Test
    fun bannerDelayIsMultipleOf100() {
        assertEquals(0L, RealtimeDisconnectPolicy.BANNER_DELAY_MS % 100L)
    }

    @Test
    fun bannerDelayConvertsToIntExactly() {
        assertEquals(2_500, RealtimeDisconnectPolicy.BANNER_DELAY_MS.toInt())
    }

    @Test
    fun bannerDelayWellUnderOneMinute() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS < 60_000L)
    }

    @Test
    fun bannerDelayDividesToTwoPointFiveSeconds() {
        assertEquals(2.5, RealtimeDisconnectPolicy.BANNER_DELAY_MS / 1000.0, 0.0)
    }

    @Test
    fun bannerDelayIsNotZero() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS != 0L)
    }

    @Test
    fun bannerDelayIsMultipleOf500() {
        assertEquals(0L, RealtimeDisconnectPolicy.BANNER_DELAY_MS % 500L)
    }

    @Test
    fun bannerDelayExceedsOneSecond() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS > 1_000L)
    }

    @Test
    fun bannerDelayIsLessThanThreeSeconds() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS < 3_000L)
    }

    @Test
    fun bannerDelayLeavesHalfSecondRemainder() {
        assertEquals(500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS % 1_000L)
    }

    @Test
    fun bannerDelayDoubledIsFiveSeconds() {
        assertEquals(5_000L, RealtimeDisconnectPolicy.BANNER_DELAY_MS * 2L)
    }

    @Test
    fun bannerDelayIsMultipleOf250() {
        assertEquals(0L, RealtimeDisconnectPolicy.BANNER_DELAY_MS % 250L)
    }

    @Test
    fun bannerDelayDividesToFiveHalfSeconds() {
        assertEquals(5L, RealtimeDisconnectPolicy.BANNER_DELAY_MS / 500L)
    }

    @Test
    fun bannerDelayPlusHalfSecondIsThreeSeconds() {
        assertEquals(3_000L, RealtimeDisconnectPolicy.BANNER_DELAY_MS + 500L)
    }

    @Test
    fun bannerDelayStringIs2500() {
        assertEquals("2500", RealtimeDisconnectPolicy.BANNER_DELAY_MS.toString())
    }

    @Test
    fun bannerDelayTimesFourIsTenSeconds() {
        assertEquals(10_000L, RealtimeDisconnectPolicy.BANNER_DELAY_MS * 4L)
    }

    @Test
    fun bannerDelayMinus500IsTwoSeconds() {
        assertEquals(2_000L, RealtimeDisconnectPolicy.BANNER_DELAY_MS - 500L)
    }

    @Test
    fun bannerDelayDividedByFiveIsFiveHundred() {
        assertEquals(500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS / 5L)
    }

    @Test
    fun bannerDelayModulo700Is400() {
        assertEquals(400L, RealtimeDisconnectPolicy.BANNER_DELAY_MS % 700L)
    }

    @Test
    fun bannerDelayTimesThreeIsSevenPointFiveSeconds() {
        assertEquals(7_500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS * 3L)
    }

    @Test
    fun bannerDelayMinusItselfIsZero() {
        assertEquals(0L, RealtimeDisconnectPolicy.BANNER_DELAY_MS - RealtimeDisconnectPolicy.BANNER_DELAY_MS)
    }

    @Test
    fun bannerDelayModulo2000Is500() {
        assertEquals(500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS % 2000L)
    }

    @Test
    fun bannerDelayIntegerDivisionBy1000IsTwo() {
        assertEquals(2L, RealtimeDisconnectPolicy.BANNER_DELAY_MS / 1000L)
    }

    @Test
    fun bannerDelayTimesFiveIsTwelvePointFiveSeconds() {
        assertEquals(12_500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS * 5L)
    }

    @Test
    fun bannerDelayPlusItselfIsFiveSeconds() {
        assertEquals(5_000L, RealtimeDisconnectPolicy.BANNER_DELAY_MS + RealtimeDisconnectPolicy.BANNER_DELAY_MS)
    }

    @Test
    fun bannerDelayMinus1000Is1500() {
        assertEquals(1_500L, RealtimeDisconnectPolicy.BANNER_DELAY_MS - 1000L)
    }
}
