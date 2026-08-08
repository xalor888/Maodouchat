package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPolicyTest {
    @Test
    fun normalizeRadius_clampsToServerRange() {
        assertEquals(0.5, NearbyPolicy.normalizeRadiusKm(0.1), 0.001)
        assertEquals(30.0, NearbyPolicy.normalizeRadiusKm(99.0), 0.001)
        assertEquals(10.0, NearbyPolicy.normalizeRadiusKm(null), 0.001)
        assertEquals(5.0, NearbyPolicy.normalizeRadiusKm(5.0), 0.001)
    }

    @Test
    fun nearestOption_picksClosestChip() {
        assertEquals(1.0, NearbyPolicy.nearestOptionKm(1.2), 0.001)
        assertEquals(10.0, NearbyPolicy.nearestOptionKm(9.0), 0.001)
        assertEquals(20.0, NearbyPolicy.nearestOptionKm(18.0), 0.001)
    }

    @Test
    fun remainingVisible_handlesExpiry() {
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(0L, 1_000L))
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(500L, 1_000L))
        assertEquals(500L, NearbyPolicy.remainingVisibleMs(1_500L, 1_000L))
        assertTrue(NearbyPolicy.isStillVisible(2_000L, 1_000L))
        assertFalse(NearbyPolicy.isStillVisible(500L, 1_000L))
    }

    @Test
    fun compareNearby_onlineThenDistanceThenRecency() {
        // online before offline
        assertTrue(
            NearbyPolicy.compareNearby(
                isOnlineA = true, distanceA = 500, updatedAtA = 1L,
                isOnlineB = false, distanceB = 100, updatedAtB = 9L
            ) < 0
        )
        // closer first when online equal
        assertTrue(
            NearbyPolicy.compareNearby(
                isOnlineA = false, distanceA = 100, updatedAtA = 1L,
                isOnlineB = false, distanceB = 200, updatedAtB = 9L
            ) < 0
        )
        // more recent first when distance equal
        assertTrue(
            NearbyPolicy.compareNearby(
                isOnlineA = true, distanceA = 100, updatedAtA = 9L,
                isOnlineB = true, distanceB = 100, updatedAtB = 1L
            ) < 0
        )
    }
}
