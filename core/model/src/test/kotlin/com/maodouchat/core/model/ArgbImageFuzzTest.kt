package com.maodouchat.core.model

import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// 块边长 4：与 BlindWatermarkMath.BLOCK 同值，oracle 里写死，pinned 另断言两者相等。
private const val BLOCK193 = 4

// ties-to-even 舍入（kotlin.math.round 的语义），手写实现不走生产路径：
// NaN/Inf 原样返回，交由 coerce 处理。
private fun round193(x: Float): Float {
    if (x.isNaN() || x.isInfinite()) return x
    val f = floor(x)
    val d = x - f
    return when {
        d < 0.5f -> f
        d > 0.5f -> f + 1f
        f % 2f == 0f -> f
        else -> f + 1f
    }
}

private fun coerce193(v: Float): Float = if (v < 0f) 0f else if (v > 255f) 255f else v

private fun maxBlocksOracle193(w: Int, h: Int): Int =
    ((h + 1) / 2 / BLOCK193) * ((w + 1) / 2 / BLOCK193)

// 通道拆分 + alpha 检测的独立重实现：bgr[0]=B，bgr[1]=G，bgr[2]=R。
private fun planesOracle193(pixels: IntArray, w: Int, h: Int): Triple<Array<IntArray>, IntArray?, Int> {
    val n = w * h
    val b = IntArray(n)
    val g = IntArray(n)
    val r = IntArray(n)
    val a = IntArray(n)
    var hasAlpha = false
    for (i in pixels.indices) {
        val p = pixels[i]
        b[i] = p and 0xFF
        g[i] = (p shr 8) and 0xFF
        r[i] = (p shr 16) and 0xFF
        val av = (p shr 24) and 0xFF
        a[i] = av
        if (av < 255) hasAlpha = true
    }
    return Triple(arrayOf(b, g, r), if (hasAlpha) a else null, maxBlocksOracle193(w, h))
}

private fun pixelsOracle193(bgr: Array<FloatArray>, alpha: FloatArray?, w: Int, h: Int): IntArray {
    val n = w * h
    val out = IntArray(n)
    for (i in 0 until n) {
        val rr = coerce193(round193(bgr[2][i])).toInt()
        val gg = coerce193(round193(bgr[1][i])).toInt()
        val bb = coerce193(round193(bgr[0][i])).toInt()
        val aa = alpha?.get(i)?.let { coerce193(round193(it)).toInt() } ?: 0xFF
        out[i] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    return out
}

private fun encodePlanes193(bgr: Array<FloatArray>, alpha: FloatArray?, w: Int, h: Int): String {
    val n = w * h
    val sb = StringBuilder()
    for (c in 0 until 3) {
        if (c > 0) sb.append('|')
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            sb.append(bgr[c][i].toInt())
        }
    }
    sb.append('|')
    if (alpha == null) sb.append("NULL") else for (i in 0 until n) {
        if (i > 0) sb.append(',')
        sb.append(alpha[i].toInt())
    }
    return sb.toString()
}

private fun encodeInts193(xs: IntArray): String = xs.joinToString(",")

private fun randomDims193(r: Random): Pair<Int, Int> =
    r.nextInt(0, 17) to r.nextInt(0, 17)

private fun randomPixels193(r: Random, w: Int, h: Int): IntArray {
    val n = w * h
    // 三成全不透明（alpha 缺席分支），其余随机 alpha。
    val opaqueOnly = r.nextInt(10) < 3
    return IntArray(n) {
        val rgb = r.nextInt()
        val a = if (opaqueOnly) 0xFF else when (r.nextInt(5)) {
            0 -> 0xFF
            1 -> 0
            2 -> 254
            else -> r.nextInt(256)
        }
        (a shl 24) or (rgb and 0x00FFFFFF)
    }
}

private fun randomFloatBits193(r: Random): Int {
    return when (r.nextInt(10)) {
        0 -> 0x7F800000 // +Inf
        1 -> 0xFF800000.toInt() // -Inf
        2 -> 0x7FC00000 // NaN
        3 -> 0 // +0.0
        4 -> 0x80000000.toInt() // -0.0
        5 -> java.lang.Float.floatToRawIntBits(r.nextFloat() * 510f - 127f)
        6 -> java.lang.Float.floatToRawIntBits(r.nextFloat() * 255f)
        7 -> java.lang.Float.floatToRawIntBits(r.nextInt(-300, 600).toFloat() + 0.5f)
        8 -> java.lang.Float.floatToRawIntBits(r.nextFloat() * 2f - 1f)
        else -> r.nextInt()
    }
}

// ---------- tests ----------

class ArgbImageFuzzTest {

    @Test
    fun argbDifferential193() {
        val r = Random(193003)
        repeat(300) {
            val (w, h) = randomDims193(r)
            val pixels = randomPixels193(r, w, h)
            val img = ArgbImage(pixels, w, h)
            val (bgr, alpha, blocks) = planesOracle193(pixels, w, h)
            assertEquals(blocks, img.maxBlocks())
            assertEquals(BLOCK193, BlindWatermarkMath.BLOCK)
            // 通道平面逐值比对（Float 存的是 0..255 整数，toInt 精确）。
            assertEquals(encodePlanes193(bgr.map { it.map { v -> v.toFloat() }.toFloatArray() }.toTypedArray(), alpha?.map { it.toFloat() }?.toFloatArray(), w, h),
                encodePlanes193(img.bgr, img.alpha, w, h))
            assertEquals(encodeInts193(pixels), encodeInts193(img.toPixels()))
        }
    }

    @Test
    fun argbMutateDifferential193() {
        val r = Random(193004)
        repeat(300) {
            val w = r.nextInt(1, 9)
            val h = r.nextInt(1, 9)
            val pixels = randomPixels193(r, w, h)
            val img = ArgbImage(pixels, w, h)
            val n = w * h
            // 随机 float 位模式写回通道平面（含 NaN/Inf/0.5 平局），再比 toPixels。
            val bgr = Array(3) { FloatArray(n) }
            for (c in 0 until 3) for (i in 0 until n) {
                val bits = randomFloatBits193(r)
                val v = java.lang.Float.intBitsToFloat(bits)
                bgr[c][i] = v
                img.bgr[c][i] = v
            }
            val alpha = img.alpha?.let { orig ->
                FloatArray(n) { i ->
                    val v = java.lang.Float.intBitsToFloat(randomFloatBits193(r))
                    orig[i] = v
                    v
                }
            }
            assertEquals(encodeInts193(pixelsOracle193(bgr, alpha, w, h)), encodeInts193(img.toPixels()))
        }
    }

    @Test
    fun argbPinned193() {
        assertEquals(BLOCK193, BlindWatermarkMath.BLOCK)
        // 1x1 全不透明像素：通道拆分、alpha 缺席、0 块、往返恒等。
        val one = ArgbImage(intArrayOf(0xFF112233.toInt()), 1, 1)
        assertEquals(0, one.maxBlocks())
        assertEquals(null, one.alpha)
        assertEquals(listOf(0x33, 0x22, 0x11), listOf(one.bgr[0][0].toInt(), one.bgr[1][0].toInt(), one.bgr[2][0].toInt()))
        assertEquals(0xFF112233.toInt(), one.toPixels()[0])
        // 含半透明像素时 alpha 出现。
        val two = ArgbImage(intArrayOf(0xFF000000.toInt(), 0x80000000.toInt()), 2, 1)
        assertEquals(listOf(255, 128), two.alpha!!.map { it.toInt() })
        assertEquals(0x80000000.toInt(), two.toPixels()[1])
        // 容量表：边长 8/9/16/17。
        assertEquals(1, ArgbImage(IntArray(64), 8, 8).maxBlocks())
        assertEquals(1, ArgbImage(IntArray(81), 9, 9).maxBlocks())
        assertEquals(4, ArgbImage(IntArray(256), 16, 16).maxBlocks())
        assertEquals(4, ArgbImage(IntArray(289), 17, 17).maxBlocks())
        assertEquals(0, ArgbImage(IntArray(0), 0, 0).maxBlocks())
        assertEquals(0, ArgbImage(IntArray(0), 0, 5).maxBlocks())
        // toPixels 舍入：0.5 平局向偶数靠，超界夹到 0/255，NaN 得 0。
        val m = ArgbImage(IntArray(6), 6, 1)
        val vals = floatArrayOf(126.5f, 127.5f, 300.5f, -1.5f, Float.NaN, Float.POSITIVE_INFINITY)
        for (i in vals.indices) {
            m.bgr[2][i] = vals[i]
            m.bgr[1][i] = vals[i]
            m.bgr[0][i] = vals[i]
        }
        val out = m.toPixels()
        assertEquals(126, (out[0] shr 16) and 0xFF)
        assertEquals(128, (out[1] shr 16) and 0xFF)
        assertEquals(255, (out[2] shr 16) and 0xFF)
        assertEquals(0, (out[3] shr 16) and 0xFF)
        assertEquals(0, (out[4] shr 16) and 0xFF)
        assertEquals(255, (out[5] shr 16) and 0xFF)
        assertEquals(encodeInts193(pixelsOracle193(m.bgr, m.alpha, 6, 1)), encodeInts193(out))
    }
}
