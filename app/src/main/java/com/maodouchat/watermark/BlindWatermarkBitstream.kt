package com.maodouchat.watermark

// 位流<->字节、洗牌与 kmeans：从 ReferenceBlindWatermark 拆出的纯函数簇，零行为改动。
internal object BlindWatermarkBitstream {

    internal fun bytesToBits(bytes: ByteArray): BooleanArray {
        val bits = BooleanArray(bytes.size * 8)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            for (b in 0 until 8) bits[i * 8 + b] = ((v shr (7 - b)) and 1) == 1
        }
        return bits
    }

    internal fun bitsToBytes(bits: BooleanArray): ByteArray {
        require(bits.size % 8 == 0) { "bit length must be multiple of 8" }
        val bytes = ByteArray(bits.size / 8)
        for (i in bytes.indices) {
            var v = 0
            for (b in 0 until 8) if (bits[i * 8 + b]) v = v or (1 shl (7 - b))
            bytes[i] = v.toByte()
        }
        return bytes
    }

    /** numpy RandomState(seed).shuffle 的等价：返回洗牌后的索引排列（Fisher-Yates）。 */
    internal fun indexShuffle(n: Int, seed: Long): IntArray {
        val idx = IntArray(n) { it }
        val rng = java.util.Random(seed)
        for (i in n - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = idx[i]; idx[i] = idx[j]; idx[j] = t
        }
        return idx
    }

    internal fun shuffleBits(bits: BooleanArray, seed: Long): BooleanArray {
        val idx = indexShuffle(bits.size, seed)
        return BooleanArray(bits.size) { bits[idx[it]] }
    }

    /** bwm_core.one_dim_kmeans：1 维 2 类 kmeans 阈值二值化；返回 (类别, 两簇中心)。 */
    internal fun oneDimKmeans(inputs: DoubleArray): Pair<BooleanArray, DoubleArray> {
        if (inputs.isEmpty()) return BooleanArray(0) to doubleArrayOf()
        val lo = inputs.minOrNull() ?: 0.0
        val hi = inputs.maxOrNull() ?: 1.0
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

}
