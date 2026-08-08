package com.maodouchat.util

import com.maodouchat.data.model.LocationPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationPolicyTest {
    @Test
    fun `live location expires at terminal edit timestamp`() {
        val payload = LocationPayload(
            latitude = 31.2,
            longitude = 121.5,
            live = true,
            liveUntil = 2_000L,
            sessionId = "live-1"
        )

        assertTrue(LiveLocationPolicy.isLive(payload, now = 1_999L))
        assertFalse(LiveLocationPolicy.isLive(payload, now = 2_000L))
        assertEquals(0L, LiveLocationPolicy.remainingMs(payload, now = 2_001L))
    }
}
