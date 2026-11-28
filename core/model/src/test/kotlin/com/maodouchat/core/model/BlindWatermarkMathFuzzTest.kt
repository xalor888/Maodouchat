package com.maodouchat.core.model

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [BlindWatermarkMath] (checklist line 931, 33rd block).
 * Test-only, zero production changes. The round-trip oracles (DWT/DCT/SVD) are
 * mathematical identities, so expected values come from the transform
 * definitions rather than from re-running production code. A Python mini-model
 * (float32-rounded transcription of the same arithmetic) differentially checks
 * the tolerance: worst observed relative round-trip error is ~2e-7 (DWT/DCT)
 * and ~8e-8 (SVD reconstruction), so the 1e-3 relative tolerance below has
 * four orders of magnitude of headroom while still catching a wrong transform
 * (e.g. a missing 1/sqrt(2) factor shifts every value by ~30%).
 */
class BlindWatermarkMathFuzzTest {

    private fun randomMatrix(r: Random, n: Int, lo: Float = -255f, hi: Float = 255f): Array<FloatArray> =
        Array(n) { FloatArray(n) { lo + r.nextFloat() * (hi - lo) } }

    private fun maxAbs(m: Array<FloatArray>): Float {
        var s = 0f
        for (row in m) for (v in row) s = maxOf(s, abs(v))
        return s
    }

    private fun maxErr(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        var e = 0f
        for (r in a.indices) for (c in a[r].indices) e = maxOf(e, abs(a[r][c] - b[r][c]))
        return e
    }

    private fun assertRoundTrip(orig: Array<FloatArray>, back: Array<FloatArray>) {
        assertEquals(orig.size, back.size)
        assertEquals(orig[0].size, back[0].size)
        val tol = 1e-3f * maxOf(1f, maxAbs(orig))
        val err = maxErr(orig, back)
        assertTrue(err <= tol, "round-trip err $err > tol $tol")
    }

    @Test
    fun dwt2AndIdwt2RoundTripRandomEvenMatrices() {
        val r = Random(20261122)
        repeat(300) {
            val n = listOf(2, 4, 6, 8)[r.nextInt(4)]
            val src = randomMatrix(r, n)
            val q = BlindWatermarkMath.dwt2(src)
            assertRoundTrip(src, BlindWatermarkMath.idwt2(q.ca, q.ch, q.cv, q.cd))
        }
    }

    @Test
    fun dwt2AndIdwt2RoundTripExtremeValues() {
        val r = Random(3301)
        val values = listOf(0f, 1e-3f, -1e-3f, 1e6f, -1e6f, 255f, -255f)
        repeat(300) {
            val n = listOf(2, 4, 6, 8)[r.nextInt(4)]
            val src = Array(n) { FloatArray(n) { values[r.nextInt(values.size)] } }
            val q = BlindWatermarkMath.dwt2(src)
            assertRoundTrip(src, BlindWatermarkMath.idwt2(q.ca, q.ch, q.cv, q.cd))
        }
    }

    @Test
    fun dct2AndIdct2RoundTripRandomBlocks() {
        val r = Random(7701)
        repeat(300) {
            val block = randomMatrix(r, 4)
            assertRoundTrip(block, BlindWatermarkMath.idct2(BlindWatermarkMath.dct2(block)))
        }
    }

    @Test
    fun svdReconstructionAndDescendingSingularValues() {
        val r = Random(9101)
        repeat(300) {
            val n = listOf(2, 3, 4)[r.nextInt(3)]
            val a = randomMatrix(r, n, -10f, 10f)
            val (u, s, vt) = BlindWatermarkMath.svd(a)
            assertEquals(n, s.size)
            for (i in 0 until n) assertTrue(s[i] >= 0.0, "singular value must be non-negative")
            for (i in 0 until n - 1) assertTrue(s[i] >= s[i + 1], "singular values must be descending")
            val scale = maxOf(1.0, maxAbs(a).toDouble())
            var err = 0.0
            for (row in 0 until n) for (col in 0 until n) {
                var sum = 0.0
                for (k in 0 until n) sum += u[row][k].toDouble() * s[k] * vt[k][col].toDouble()
                err = maxOf(err, abs(sum - a[row][col].toDouble()))
            }
            assertTrue(err <= 1e-3 * scale, "svd reconstruction err $err > tol ${1e-3 * scale}")
        }
    }

    @Test
    fun dwt2OddDimensionsTruncateToEvenPrefix() {
        // dwt2 silently drops the last row/column when a dimension is odd
        // (integer division in the pair loops); idwt2 then recovers exactly
        // the even top-left prefix. Current behaviour, pinned, not changed.
        val r = Random(555)
        for (n in listOf(3, 5)) {
            val src = randomMatrix(r, n)
            val q = BlindWatermarkMath.dwt2(src)
            assertEquals(n / 2, q.ca.size)
            assertEquals(n / 2, q.ca[0].size)
            val prefix = Array(n - 1) { row -> FloatArray(n - 1) { col -> src[row][col] } }
            assertRoundTrip(prefix, BlindWatermarkMath.idwt2(q.ca, q.ch, q.cv, q.cd))
        }
    }

    @Test
    fun dwt2EmptyInputAndDct2SmallBlockThrow() {
        assertFailsWith<IndexOutOfBoundsException> {
            BlindWatermarkMath.dwt2(emptyArray<FloatArray>())
        }
        assertFailsWith<IndexOutOfBoundsException> {
            BlindWatermarkMath.dct2(Array(2) { FloatArray(2) })
        }
    }

    @Test
    fun dct2ReadsOnlyTopLeft4x4() {
        val r = Random(4242)
        val big = randomMatrix(r, 6)
        val prefix = Array(4) { row -> FloatArray(4) { col -> big[row][col] } }
        val a = BlindWatermarkMath.dct2(big)
        val b = BlindWatermarkMath.dct2(prefix)
        for (row in 0 until 4) for (col in 0 until 4) assertEquals(a[row][col], b[row][col])
    }

    @Test
    fun svdZeroMatrixAnd1x1Pins() {
        val (_, sZero, _) = BlindWatermarkMath.svd(Array(4) { FloatArray(4) })
        for (v in sZero) assertEquals(0.0, v, 0.0)
        val (uOne, sOne, vtOne) = BlindWatermarkMath.svd(arrayOf(floatArrayOf(-7.5f)))
        assertEquals(7.5, sOne[0], 0.0)
        assertEquals(-7.5, uOne[0][0].toDouble() * sOne[0] * vtOne[0][0].toDouble(), 0.0)
    }
}
