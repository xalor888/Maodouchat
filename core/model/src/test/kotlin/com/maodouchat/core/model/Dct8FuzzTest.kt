package com.maodouchat.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [Dct8] (checklist line 931, 54th block).
 * Test-only, zero production changes.
 *
 * The oracle computes the same 2D DCT-II/IDCT-II by direct matrix
 * multiplication (production does two 1D passes), so comparisons use a
 * 1e-9 tolerance; in-place vs out-of-place must match exactly.
 * Random inputs use Random(42), fresh per @Test.
 */
class Dct8FuzzTest {

// ---------- B2 Dct8 oracle ----------

// Direct 2D matrix multiply (production does two 1D passes); same math,
// different summation order, so comparisons use a 1e-9 tolerance.
private val DCT_M_ORACLE: DoubleArray = DoubleArray(64) { i ->
    val k = i / 8
    val n = i % 8
    val alpha = if (k == 0) 1.0 / sqrt(8.0) else sqrt(2.0 / 8.0)
    alpha * cos(PI * (2 * n + 1) * k / 16.0)
}

private fun dctForwardOracle(block: DoubleArray): DoubleArray {
    require(block.size == 64)
    val tmp = DoubleArray(64)
    for (r in 0 until 8) for (k in 0 until 8) {
        var s = 0.0
        for (n in 0 until 8) s += DCT_M_ORACLE[k * 8 + n] * block[r * 8 + n]
        tmp[r * 8 + k] = s
    }
    val out = DoubleArray(64)
    for (c in 0 until 8) for (k in 0 until 8) {
        var s = 0.0
        for (r in 0 until 8) s += DCT_M_ORACLE[k * 8 + r] * tmp[r * 8 + c]
        out[k * 8 + c] = s
    }
    return out
}

private fun dctInverseOracle(block: DoubleArray): DoubleArray {
    require(block.size == 64)
    val tmp = DoubleArray(64)
    for (r in 0 until 8) for (n in 0 until 8) {
        var s = 0.0
        for (k in 0 until 8) s += DCT_M_ORACLE[k * 8 + n] * block[r * 8 + k]
        tmp[r * 8 + n] = s
    }
    val out = DoubleArray(64)
    for (c in 0 until 8) for (n in 0 until 8) {
        var s = 0.0
        for (k in 0 until 8) s += DCT_M_ORACLE[k * 8 + n] * tmp[k * 8 + c]
        out[n * 8 + c] = s
    }
    return out
}

// ---------- B2 generators (Random(42) in the CI tests) ----------

private fun randomBlock(rnd: Random): DoubleArray {
    return DoubleArray(64) {
        when (rnd.nextInt(6)) {
            0 -> rnd.nextDouble(-1000.0, 1000.0)
            1 -> rnd.nextDouble(0.0, 255.0)
            2 -> rnd.nextDouble(-1.0, 1.0)
            3 -> rnd.nextDouble(-1e-6, 1e-6)
            4 -> rnd.nextInt(-5, 6).toDouble()
            else -> rnd.nextDouble(-1e6, 1e6)
        }
    }
}

    private fun assertDctClose(expected: DoubleArray, actual: DoubleArray, scale: Double = 1.0) {
        assertEquals(64, actual.size)
        for (i in 0 until 64) {
            // Tolerance is relative to the block scale: a round trip passes
            // through coefficients up to 8x the block max, so near-zero
            // outputs of a large block legitimately carry ~1e-9 absolute error.
            val tol = 1e-9 * maxOf(1.0, scale, abs(expected[i]), abs(actual[i]))
            assertTrue(
                abs(expected[i] - actual[i]) <= tol,
                "dct[$i]: expected ${expected[i]} actual ${actual[i]} tol $tol"
            )
        }
    }

    @Test
    fun forwardMatchesOracleRandom() {
        val rnd = Random(42)
        repeat(300) {
            val block = randomBlock(rnd)
            val fwd = block.copyOf()
            Dct8.forward(fwd)
            assertDctClose(dctForwardOracle(block), fwd)
            val inv = block.copyOf()
            Dct8.inverse(inv)
            assertDctClose(dctInverseOracle(block), inv)
        }
    }

    @Test
    fun roundTripRandom() {
        val rnd = Random(42)
        repeat(300) {
            val block = randomBlock(rnd)
            val scale = 8.0 * block.maxOf { abs(it) }
            val fwd = block.copyOf()
            Dct8.forward(fwd)
            val back = fwd.copyOf()
            Dct8.inverse(back)
            assertDctClose(block, back, scale)
            val inv = block.copyOf()
            Dct8.inverse(inv)
            val fwdAgain = inv.copyOf()
            Dct8.forward(fwdAgain)
            assertDctClose(block, fwdAgain, scale)
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Zero block stays zero.
        val zero = DoubleArray(64)
        Dct8.forward(zero)
        assertDctClose(DoubleArray(64), zero)
        // Constant block c: only [0][0] = 8c survives.
        val constBlock = DoubleArray(64) { 2.0 }
        val constFwd = constBlock.copyOf()
        Dct8.forward(constFwd)
        val expectedConst = DoubleArray(64)
        expectedConst[0] = 16.0
        assertDctClose(expectedConst, constFwd)
        // Impulse at [0][0]: matches the oracle within tolerance.
        val impulse = DoubleArray(64)
        impulse[0] = 1.0
        val impulseFwd = impulse.copyOf()
        Dct8.forward(impulseFwd)
        assertDctClose(dctForwardOracle(impulse), impulseFwd)
        val impulseInv = impulse.copyOf()
        Dct8.inverse(impulseInv)
        assertDctClose(dctInverseOracle(impulse), impulseInv)
        // In-place (default out) vs a separate out array: bit-identical.
        val block = DoubleArray(64) { (it * 7 - 100).toDouble() }
        val inPlace = block.copyOf()
        Dct8.forward(inPlace)
        val outOfPlace = DoubleArray(64)
        Dct8.forward(block.copyOf(), outOfPlace)
        assertTrue(inPlace.contentEquals(outOfPlace))
        val inPlaceInv = block.copyOf()
        Dct8.inverse(inPlaceInv)
        val outOfPlaceInv = DoubleArray(64)
        Dct8.inverse(block.copyOf(), outOfPlaceInv)
        assertTrue(inPlaceInv.contentEquals(outOfPlaceInv))
    }
}
