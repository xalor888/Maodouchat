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

    @Test
    fun nearestOptionKm_tieBreaksTowardLowerOption() {
        // minByOrNull 在距离并列时取列表中先出现的（更小档）
        assertEquals(0.5, NearbyPolicy.nearestOptionKm(0.75), 0.001)
        assertEquals(2.0, NearbyPolicy.nearestOptionKm(3.5), 0.001)
        assertEquals(0.5, NearbyPolicy.nearestOptionKm(0.1), 0.001)
    }

    @Test
    fun normalizeRadiusKm_infiniteAndNaNEdges() {
        assertEquals(30.0, NearbyPolicy.normalizeRadiusKm(Double.POSITIVE_INFINITY), 0.001)
        assertEquals(0.5, NearbyPolicy.normalizeRadiusKm(Double.NEGATIVE_INFINITY), 0.001)
        // coerceIn 对 NaN 三个比较全是 false，原样穿过；钉住该行为而非静默修正
        assertTrue(NearbyPolicy.normalizeRadiusKm(Double.NaN).isNaN())
    }

    @Test
    fun remainingVisibleMs_boundaryExpiryIsZero() {
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(1_000L, 1_000L))
        assertFalse(NearbyPolicy.isStillVisible(1_000L, 1_000L))
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(5_000L, -10L))
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(-5L, 1_000L))
    }

    @Test
    fun compareNearby_equalEverythingReturnsZeroAndIsAntisymmetric() {
        assertEquals(
            0,
            NearbyPolicy.compareNearby(
                isOnlineA = true, distanceA = 100, updatedAtA = 5L,
                isOnlineB = true, distanceB = 100, updatedAtB = 5L
            )
        )
        val forward = NearbyPolicy.compareNearby(
            isOnlineA = true, distanceA = 500, updatedAtA = 1L,
            isOnlineB = false, distanceB = 100, updatedAtB = 9L
        )
        val backward = NearbyPolicy.compareNearby(
            isOnlineA = false, distanceA = 100, updatedAtA = 9L,
            isOnlineB = true, distanceB = 500, updatedAtB = 1L
        )
        assertTrue(forward < 0)
        assertEquals(-forward, backward)
    }

    @Test
    fun radiusOptions_arePinnedSortedAndRoundTrip() {
        assertEquals(NearbyPolicy.MIN_RADIUS_KM, NearbyPolicy.RADIUS_OPTIONS_KM.first(), 0.0)
        assertEquals(NearbyPolicy.MAX_RADIUS_KM, NearbyPolicy.RADIUS_OPTIONS_KM.last(), 0.0)
        assertTrue(NearbyPolicy.RADIUS_OPTIONS_KM.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(NearbyPolicy.RADIUS_OPTIONS_KM.size, NearbyPolicy.RADIUS_OPTIONS_KM.toSet().size)
        NearbyPolicy.RADIUS_OPTIONS_KM.forEach {
            assertEquals(it, NearbyPolicy.nearestOptionKm(it), 0.0)
        }
    }
}
