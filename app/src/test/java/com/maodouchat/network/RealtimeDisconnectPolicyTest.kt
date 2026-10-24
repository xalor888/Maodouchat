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
}
