package com.maodouchat.network

import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeDisconnectPolicyTest {
    @Test
    fun bannerDelayCoversTypicalReconnectFlap() {
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS >= 2_000L)
        assertTrue(RealtimeDisconnectPolicy.BANNER_DELAY_MS <= 5_000L)
    }
}
