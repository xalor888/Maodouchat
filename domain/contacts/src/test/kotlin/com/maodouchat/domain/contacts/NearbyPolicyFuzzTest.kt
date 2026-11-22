package com.maodouchat.domain.contacts

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [NearbyPolicy] (checklist line 931, 36th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the radius clamp, nearest option, remaining time and
 * sort order from the documented rules. Two production quirks are reproduced
 * verbatim, not "fixed": coerceIn leaves NaN as NaN, and minByOrNull keeps
 * the first option on a tie (so a midpoint rounds down to the lower option).
 * A Python mini-model differentially checks both oracles on 5000 random
 * inputs each (0 diffs) before this file was written.
 */
class NearbyPolicyFuzzTest {

    private val options = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0)

    private fun oracleNormalize(radiusKm: Double?): Double {
        val value = radiusKm ?: 10.0
        return when {
            value.isNaN() -> Double.NaN
            value < 0.5 -> 0.5
            value > 30.0 -> 30.0
            else -> value
        }
    }

    private fun oracleNearest(radiusKm: Double): Double {
        val normalized = oracleNormalize(radiusKm)
        var best = options[0]
        var bestDiff = abs(best - normalized)
        for (option in options.drop(1)) {
            val diff = abs(option - normalized)
            if (diff < bestDiff) {
                best = option
                bestDiff = diff
            }
        }
        return best
    }

    private fun oracleRemaining(expiresAt: Long, nowMs: Long): Long {
        if (expiresAt <= 0L || nowMs <= 0L) return 0L
        return maxOf(0L, expiresAt - nowMs)
    }

    private fun oracleCompare(
        onlineA: Boolean, distanceA: Int, updatedA: Long,
        onlineB: Boolean, distanceB: Int, updatedB: Long
    ): Int {
        if (onlineA != onlineB) return if (onlineA) -1 else 1
        if (distanceA != distanceB) return distanceA.compareTo(distanceB)
        return updatedB.compareTo(updatedA)
    }

    private fun assertDoubleEquals(expected: Double, actual: Double, message: String) {
        if (expected.isNaN()) assertTrue(actual.isNaN(), message)
        else assertEquals(expected, actual, message)
    }

    private fun randomRadius(rnd: Random): Double? {
        return when (rnd.nextInt(12)) {
            0 -> null
            1 -> 0.0
            2 -> -1.0
            3 -> 0.5
            4 -> 30.0
            5 -> Double.NaN
            6 -> Double.POSITIVE_INFINITY
            7 -> Double.NEGATIVE_INFINITY
            8 -> rnd.nextDouble(0.5, 30.0)
            9 -> rnd.nextDouble(-5.0, 35.0)
            10 -> listOf(0.75, 1.5, 3.5, 7.5, 12.5, 17.5, 22.5, 27.5).random(rnd)
            else -> rnd.nextDouble(0.0, 100.0)
        }
    }

    private fun randomTimePair(rnd: Random): Pair<Long, Long> {
        val pool = listOf(0L, -1L, 1L, 999L, 1000L, 1001L, Long.MAX_VALUE, Long.MIN_VALUE)
        return when (rnd.nextInt(4)) {
            0 -> pool.random(rnd) to pool.random(rnd)
            1 -> rnd.nextLong(-10_000L, 100_000L) to rnd.nextLong(-10_000L, 100_000L)
            2 -> rnd.nextLong(1L, 2_000_000L) to rnd.nextLong(1L, 2_000_000L)
            else -> rnd.nextLong() to rnd.nextLong()
        }
    }

    private fun randomDistance(rnd: Random): Int {
        return when (rnd.nextInt(6)) {
            0 -> 0
            1 -> -1
            2 -> Int.MIN_VALUE
            3 -> Int.MAX_VALUE
            4 -> rnd.nextInt(-100, 101)
            else -> rnd.nextInt(0, 1000)
        }
    }

    private data class NearbyEntry(val online: Boolean, val distance: Int, val updatedAt: Long)

    @Test
    fun normalizeRadiusKmRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val radius = randomRadius(rnd)
            assertDoubleEquals(
                oracleNormalize(radius),
                NearbyPolicy.normalizeRadiusKm(radius),
                "radius=<$radius>"
            )
        }
    }

    @Test
    fun nearestOptionKmRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val radius = randomRadius(rnd) ?: 10.0
            assertDoubleEquals(
                oracleNearest(radius),
                NearbyPolicy.nearestOptionKm(radius),
                "radius=<$radius>"
            )
        }
    }

    @Test
    fun remainingVisibleMsRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val (expiresAt, nowMs) = randomTimePair(rnd)
            assertEquals(
                oracleRemaining(expiresAt, nowMs),
                NearbyPolicy.remainingVisibleMs(expiresAt, nowMs),
                "expiresAt=$expiresAt nowMs=$nowMs"
            )
        }
    }

    @Test
    fun isStillVisibleRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val (expiresAt, nowMs) = randomTimePair(rnd)
            assertEquals(
                oracleRemaining(expiresAt, nowMs) > 0L,
                NearbyPolicy.isStillVisible(expiresAt, nowMs),
                "expiresAt=$expiresAt nowMs=$nowMs"
            )
        }
    }

    @Test
    fun compareNearbyRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val a = NearbyEntry(rnd.nextBoolean(), randomDistance(rnd), rnd.nextLong())
            val b = NearbyEntry(rnd.nextBoolean(), randomDistance(rnd), rnd.nextLong())
            val forward = NearbyPolicy.compareNearby(a.online, a.distance, a.updatedAt, b.online, b.distance, b.updatedAt)
            assertEquals(
                oracleCompare(a.online, a.distance, a.updatedAt, b.online, b.distance, b.updatedAt),
                forward,
                "a=$a b=$b"
            )
            val backward = NearbyPolicy.compareNearby(b.online, b.distance, b.updatedAt, a.online, a.distance, a.updatedAt)
            assertEquals(-forward, backward, "antisymmetry a=$a b=$b")
        }
        // Sorted order is monotone: online first, then closer, then newer.
        repeat(50) {
            val entries = List(12) { NearbyEntry(rnd.nextBoolean(), randomDistance(rnd), rnd.nextLong()) }
            val sorted = entries.sortedWith { x, y ->
                NearbyPolicy.compareNearby(x.online, x.distance, x.updatedAt, y.online, y.distance, y.updatedAt)
            }
            for (i in 0 until sorted.size - 1) {
                val c = NearbyPolicy.compareNearby(
                    sorted[i].online, sorted[i].distance, sorted[i].updatedAt,
                    sorted[i + 1].online, sorted[i + 1].distance, sorted[i + 1].updatedAt
                )
                assertTrue(c <= 0, "not sorted at $i: ${sorted[i]} vs ${sorted[i + 1]}")
            }
        }
    }

    @Test
    fun pins() {
        assertDoubleEquals(10.0, NearbyPolicy.normalizeRadiusKm(null), "null radius")
        assertDoubleEquals(0.5, NearbyPolicy.normalizeRadiusKm(0.0), "zero radius")
        assertDoubleEquals(0.5, NearbyPolicy.normalizeRadiusKm(-3.0), "negative radius")
        assertDoubleEquals(30.0, NearbyPolicy.normalizeRadiusKm(31.0), "over max radius")
        assertTrue(NearbyPolicy.normalizeRadiusKm(Double.NaN).isNaN(), "NaN stays NaN")
        assertDoubleEquals(30.0, NearbyPolicy.normalizeRadiusKm(Double.POSITIVE_INFINITY), "+Inf")
        assertDoubleEquals(0.5, NearbyPolicy.normalizeRadiusKm(Double.NEGATIVE_INFINITY), "-Inf")
        // Midpoint ties round down to the lower option.
        assertDoubleEquals(0.5, NearbyPolicy.nearestOptionKm(0.75), "midpoint 0.75")
        assertDoubleEquals(1.0, NearbyPolicy.nearestOptionKm(1.5), "midpoint 1.5")
        assertDoubleEquals(2.0, NearbyPolicy.nearestOptionKm(3.5), "midpoint 3.5")
        assertDoubleEquals(5.0, NearbyPolicy.nearestOptionKm(7.5), "midpoint 7.5")
        assertDoubleEquals(10.0, NearbyPolicy.nearestOptionKm(12.5), "midpoint 12.5")
        assertDoubleEquals(0.5, NearbyPolicy.nearestOptionKm(Double.NaN), "NaN takes first option")
        assertDoubleEquals(30.0, NearbyPolicy.nearestOptionKm(100.0), "far radius")
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(0L, 100L), "zero expiresAt")
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(100L, 0L), "zero nowMs")
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(-5L, 10L), "negative expiresAt")
        assertEquals(1L, NearbyPolicy.remainingVisibleMs(1000L, 999L), "one ms left")
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(1000L, 1000L), "exact expiry")
        assertEquals(0L, NearbyPolicy.remainingVisibleMs(1000L, 1001L), "past expiry")
        assertEquals(Long.MAX_VALUE - 1, NearbyPolicy.remainingVisibleMs(Long.MAX_VALUE, 1L), "max expiry")
        assertTrue(NearbyPolicy.isStillVisible(1000L, 999L), "still visible")
        assertTrue(!NearbyPolicy.isStillVisible(1000L, 1000L), "not visible at expiry")
        assertEquals(-1, NearbyPolicy.compareNearby(true, 100, 1000L, false, 50, 2000L), "online first")
        assertEquals(1, NearbyPolicy.compareNearby(false, 100, 1000L, true, 50, 2000L), "offline last")
        assertEquals(-1, NearbyPolicy.compareNearby(true, 50, 1000L, true, 100, 2000L), "closer first")
        assertEquals(-1, NearbyPolicy.compareNearby(true, 100, 2000L, true, 100, 1000L), "newer first")
        assertEquals(0, NearbyPolicy.compareNearby(true, 100, 1000L, true, 100, 1000L), "all equal")
    }
}
