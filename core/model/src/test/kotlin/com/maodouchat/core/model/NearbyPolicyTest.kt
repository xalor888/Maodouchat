package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPolicyTest {

    @Test
    fun `normalizeRadiusKm defaults and clamps`() {
        assertEquals(10.0, NearbyPolicy.normalizeRadiusKm(null), 0.0)
        assertEquals(30.0, NearbyPolicy.normalizeRadiusKm(100.0), 0.0)
        assertEquals(0.5, NearbyPolicy.normalizeRadiusKm(0.1), 0.0)
    }

    @Test
    fun `nearestOptionKm picks the closest preset`() {
        assertEquals(5.0, NearbyPolicy.nearestOptionKm(7.0), 0.0)
        assertEquals(30.0, NearbyPolicy.nearestOptionKm(29.0), 0.0)
    }

    @Test
    fun `remainingVisibleMs is zero for expired or invalid input`() {
        assertEquals(500L, NearbyPolicy.remainingVisibleMs(1500L, 1000L))
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(1000L, 1500L))
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(0L, 1000L))
        assertTrue(NearbyPolicy.isStillVisible(1500L, 1000L))
    }

    @Test
    fun `compareNearby prefers online then nearer then fresher`() {
        assertTrue(NearbyPolicy.compareNearby(true, 10, 1L, false, 5, 2L) < 0)
        assertTrue(NearbyPolicy.compareNearby(true, 20, 1L, true, 5, 2L) > 0)
        assertTrue(NearbyPolicy.compareNearby(true, 5, 1L, true, 5, 2L) > 0)
    }
}
