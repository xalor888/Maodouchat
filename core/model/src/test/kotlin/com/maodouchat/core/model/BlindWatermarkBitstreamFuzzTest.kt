package com.maodouchat.core.model

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [BlindWatermarkBitstream] (checklist line
 * 931, 54th block). Test-only, zero production changes.
 *
 * Oracles are independent transcriptions of the production arithmetic:
 * bytes<->bits MSB-first, java.util.Random 48-bit LCG Fisher-Yates
 * (reimplemented here, never calling the JDK RNG), and the 1D 2-class
 * kmeans loop (same iteration order, so bits match exactly and centers
 * within 1e-12). Random inputs use Random(42), fresh per @Test.
 */
class BlindWatermarkBitstreamFuzzTest {

// ---------- B1 BlindWatermarkBitstream oracle ----------

private fun bytesToBitsOracle(bytes: ByteArray): BooleanArray {
    val bits = BooleanArray(bytes.size * 8)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        for (b in 0 until 8) bits[i * 8 + b] = ((v shr (7 - b)) and 1) == 1
    }
    return bits
}

private fun bitsToBytesOracle(bits: BooleanArray): ByteArray {
    require(bits.size % 8 == 0) { "bit length must be multiple of 8" }
    val bytes = ByteArray(bits.size / 8)
    for (i in bytes.indices) {
        var v = 0
        for (b in 0 until 8) if (bits[i * 8 + b]) v = v or (1 shl (7 - b))
        bytes[i] = v.toByte()
    }
    return bytes
}

// Independent reimplementation of java.util.Random (48-bit LCG), so the
// oracle never calls the JDK RNG the production code uses.
private class JavaRandomOracle(seed: Long) {
    private var s = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)
    private fun next(bits: Int): Int {
        s = (s * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        return (s ushr (48 - bits)).toInt()
    }
    fun nextInt(bound: Int): Int {
        if (bound and (bound - 1) == 0) return ((bound.toLong() * next(31).toLong()) shr 31).toInt()
        var bits: Int
        var v: Int
        do {
            bits = next(31)
            v = bits % bound
        } while (bits - v + (bound - 1) < 0)
        return v
    }
}

private fun indexShuffleOracle(n: Int, seed: Long): IntArray {
    val idx = IntArray(n) { it }
    val rng = JavaRandomOracle(seed)
    for (i in n - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        val t = idx[i]; idx[i] = idx[j]; idx[j] = t
    }
    return idx
}

private fun shuffleBitsOracle(bits: BooleanArray, seed: Long): BooleanArray {
    val idx = indexShuffleOracle(bits.size, seed)
    return BooleanArray(bits.size) { bits[idx[it]] }
}

private fun oneDimKmeansOracle(inputs: DoubleArray): Pair<BooleanArray, DoubleArray> {
    if (inputs.isEmpty()) return BooleanArray(0) to doubleArrayOf()
    var lo = inputs[0]
    var hi = inputs[0]
    for (v in inputs) {
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (hi - lo < 1e-9) {
        return BooleanArray(inputs.size) { inputs[it] >= 0.5 } to doubleArrayOf(lo, hi)
    }
    var threshold = 0.0
    var center0 = lo
    var center1 = hi
    repeat(300) {
        threshold = (center0 + center1) / 2
        var s0 = 0.0; var n0 = 0
        var s1 = 0.0; var n1 = 0
        for (v in inputs) {
            if (v > threshold) { s1 += v; n1++ } else { s0 += v; n0++ }
        }
        val newC0 = if (n0 > 0) s0 / n0 else center0
        val newC1 = if (n1 > 0) s1 / n1 else center1
        center0 = newC0; center1 = newC1
        val newMid = (newC0 + newC1) / 2
        if (abs(newMid - threshold) < 1e-6) {
            threshold = newMid
            return BooleanArray(inputs.size) { inputs[it] > threshold } to doubleArrayOf(center0, center1)
        }
    }
    return BooleanArray(inputs.size) { inputs[it] > threshold } to doubleArrayOf(center0, center1)
}

// ---------- B1 generators (Random(42) in the CI tests) ----------

private fun randomBytes(rnd: Random): ByteArray {
    val len = when (rnd.nextInt(9)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32; 7 -> 64
        else -> rnd.nextInt(0, 65)
    }
    return ByteArray(len) { rnd.nextInt(256).toByte() }
}

private fun randomBits(rnd: Random): BooleanArray {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 7; 3 -> 8; 4 -> 16; 5 -> 64; 6 -> 128
        else -> rnd.nextInt(0, 129)
    }
    return BooleanArray(len) { rnd.nextBoolean() }
}

private fun randomSeed(rnd: Random): Long = when (rnd.nextInt(7)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 42L; 4 -> Long.MIN_VALUE; 5 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

private fun randomDoubles(rnd: Random, n: Int): DoubleArray {
    return DoubleArray(n) {
        when (rnd.nextInt(6)) {
            0 -> rnd.nextDouble(-1.0, 1.0)
            1 -> rnd.nextDouble(-1000.0, 1000.0)
            2 -> rnd.nextDouble(0.0, 255.0)
            3 -> rnd.nextDouble(-1e-6, 1e-6)
            4 -> rnd.nextInt(-5, 6).toDouble()
            else -> rnd.nextDouble(-1e6, 1e6)
        }
    }
}

private fun randomKmeansInput(rnd: Random): DoubleArray {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 5; 5 -> 10; 6 -> 20
        else -> rnd.nextInt(0, 41)
    }
    // Half the time cluster around two centers so convergence paths vary.
    return if (len >= 4 && rnd.nextBoolean()) {
        val c0 = rnd.nextDouble(-100.0, 100.0)
        val c1 = c0 + rnd.nextDouble(0.5, 50.0) * (if (rnd.nextBoolean()) 1 else -1)
        DoubleArray(len) {
            if (rnd.nextBoolean()) c0 + rnd.nextDouble(-0.1, 0.1)
            else c1 + rnd.nextDouble(-0.1, 0.1)
        }
    } else randomDoubles(rnd, len)
}

    @Test
    fun bytesRoundTripRandom() {
        val rnd = Random(42)
        repeat(300) {
            val bytes = randomBytes(rnd)
            assertTrue(bytesToBitsOracle(bytes).contentEquals(BlindWatermarkBitstream.bytesToBits(bytes)))
            assertTrue(bytes.contentEquals(BlindWatermarkBitstream.bitsToBytes(BlindWatermarkBitstream.bytesToBits(bytes))))
            assertTrue(bytes.contentEquals(bitsToBytesOracle(bytesToBitsOracle(bytes))))
        }
    }

    @Test
    fun shuffleRandom() {
        val rnd = Random(42)
        repeat(300) {
            val bits = randomBits(rnd)
            val seed = randomSeed(rnd)
            val idxProd = BlindWatermarkBitstream.indexShuffle(bits.size, seed)
            val idxOra = indexShuffleOracle(bits.size, seed)
            assertTrue(idxOra.contentEquals(idxProd))
            assertTrue(idxProd.contentEquals(BlindWatermarkBitstream.indexShuffle(bits.size, seed)))
            val sorted = idxProd.sorted()
            assertEquals((0 until bits.size).toList(), sorted)
            assertTrue(
                shuffleBitsOracle(bits, seed).contentEquals(
                    BlindWatermarkBitstream.shuffleBits(bits, seed)
                )
            )
        }
    }

    @Test
    fun kmeansRandom() {
        val rnd = Random(42)
        repeat(300) {
            val input = randomKmeansInput(rnd)
            val (bitsProd, centersProd) = BlindWatermarkBitstream.oneDimKmeans(input)
            val (bitsOra, centersOra) = oneDimKmeansOracle(input)
            assertTrue(bitsOra.contentEquals(bitsProd))
            assertEquals(centersOra.size, centersProd.size)
            for (i in centersOra.indices) {
                val tol = 1e-12 * maxOf(1.0, abs(centersOra[i]), abs(centersProd[i]))
                assertTrue(abs(centersOra[i] - centersProd[i]) <= tol,
                    "center[$i]: oracle ${centersOra[i]} prod ${centersProd[i]}")
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // 0xA5 = 10100101 MSB-first.
        assertTrue(
            booleanArrayOf(true, false, true, false, false, true, false, true).contentEquals(
                BlindWatermarkBitstream.bytesToBits(byteArrayOf(0xA5.toByte()))
            )
        )
        assertTrue(
            byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x01).contentEquals(
                BlindWatermarkBitstream.bitsToBytes(
                    BlindWatermarkBitstream.bytesToBits(
                        byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x01)
                    )
                )
            )
        )
        assertTrue(byteArrayOf().contentEquals(BlindWatermarkBitstream.bitsToBytes(booleanArrayOf())))
        assertTrue(booleanArrayOf().contentEquals(BlindWatermarkBitstream.bytesToBits(byteArrayOf())))
        // bitsToBytes guard: length not a multiple of 8 throws.
        assertFailsWith<IllegalArgumentException> {
            BlindWatermarkBitstream.bitsToBytes(BooleanArray(7) { true })
        }
        assertFailsWith<IllegalArgumentException> {
            BlindWatermarkBitstream.bitsToBytes(BooleanArray(9) { false })
        }
        // indexShuffle degenerate sizes are the identity.
        assertTrue(intArrayOf().contentEquals(BlindWatermarkBitstream.indexShuffle(0, 42L)))
        assertTrue(intArrayOf(0).contentEquals(BlindWatermarkBitstream.indexShuffle(1, 42L)))
        // kmeans: empty, constant, and the [0, 1] one-round convergence.
        val (emptyBits, emptyCenters) = BlindWatermarkBitstream.oneDimKmeans(doubleArrayOf())
        assertEquals(0, emptyBits.size)
        assertEquals(0, emptyCenters.size)
        val (constBits, constCenters) = BlindWatermarkBitstream.oneDimKmeans(doubleArrayOf(0.3, 0.3, 0.3))
        assertTrue(booleanArrayOf(false, false, false).contentEquals(constBits))
        assertTrue(doubleArrayOf(0.3, 0.3).contentEquals(constCenters))
        val (twoBits, twoCenters) = BlindWatermarkBitstream.oneDimKmeans(doubleArrayOf(0.0, 1.0))
        assertTrue(booleanArrayOf(false, true).contentEquals(twoBits))
        assertTrue(doubleArrayOf(0.0, 1.0).contentEquals(twoCenters))
        // shuffleBits of empty input stays empty for any seed.
        assertTrue(booleanArrayOf().contentEquals(BlindWatermarkBitstream.shuffleBits(booleanArrayOf(), -7L)))
    }
}
