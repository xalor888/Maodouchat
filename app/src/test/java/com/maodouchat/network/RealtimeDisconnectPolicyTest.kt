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
}
