package com.maodouchat.domain.calls

import com.maodouchat.core.model.NetworkQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class CallNetworkQualityPolicyTest {

    @Test
    fun `network quality buckets from rtt and loss`() {
        assertEquals(NetworkQuality.UNKNOWN, CallNetworkQualityPolicy.fromStats(null, 0.0))
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(120, 0.5))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(350, 2.0))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(800, 1.0))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(100, 8.0))
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(180, null))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(400, null))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(900, null))
    }

    @Test
    fun `network quality pins boundary values`() {
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(200, 1.0))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(500, 4.0))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(201, 1.0))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(501, 0.0))
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(200, null))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(500, null))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(501, null))
    }
}
