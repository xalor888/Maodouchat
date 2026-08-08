package com.maodouchat.watermark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.roundToInt

class FrequencyWatermarkTest {

    private val w = 256
    private val h = 256
    private val payload = FrequencyWatermark.buildPayload("user-abc123", "chat-xyz789", "deadbeef")

    private fun synthImage(seed: Int = 7): FloatArray {
        val luma = FloatArray(w * h)
        var s = seed.toLong() and 0xFFFFFFFFL
        fun rnd(): Float {
            s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL
            return (s % 256).toFloat()
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val grad = (x + y) * 0.25f
                val noise = rnd() * 0.15f
                luma[y * w + x] = (grad + noise).coerceIn(0f, 255f)
            }
        }
        return luma
    }

    @Test
    fun `dct round trip preserves signal`() {
        val block = DoubleArray(Dct8.BLOCK) { (it * 13.0) % 255.0 }
        val original = block.copyOf()
        Dct8.forward(block)
        Dct8.inverse(block)
        // orthonormal DCT 往返误差应极小
        var maxErr = 0.0
        for (i in 0 until Dct8.BLOCK) {
            maxErr = maxOf(maxErr, kotlin.math.abs(block[i] - original[i]))
        }
        assertTrue("DCT round-trip max error $maxErr too large", maxErr < 1e-6)
    }

    @Test
    fun `build payload is deterministic and distinct`() {
        val a = FrequencyWatermark.buildPayload("user-abc123", "chat-xyz789", "deadbeef")
        val a2 = FrequencyWatermark.buildPayload("user-abc123", "chat-xyz789", "deadbeef")
        assertArrayEquals(a, a2)
        val b = FrequencyWatermark.buildPayload("user-abc123", "chat-xyz789", "deadbee0")
        // 仅 deviceHint 末位不同，载荷应不同
        assertEquals(false, a.contentEquals(b))
        assertEquals(6, a.size)
        assertEquals(12, FrequencyWatermark.decodePayloadHex(a).length)
    }

    @Test
    fun `embed then extract recovers payload`() {
        val img = synthImage()
        val marked = FrequencyWatermark.embed(img, w, h, payload)
        val recovered = FrequencyWatermark.extract(marked, w, h)
        assertNotNull("extraction returned null", recovered)
        assertArrayEquals(payload, recovered)
    }

    @Test
    fun `watermark is imperceptible - high psnr`() {
        val img = synthImage()
        val marked = FrequencyWatermark.embed(img, w, h, payload)
        var mse = 0.0
        for (i in img.indices) {
            val d = marked[i] - img[i]
            mse += d * d
        }
        mse /= img.size
        val psnr = 10.0 * log10(255.0 * 255.0 / mse)
        assertTrue("PSNR $psnr dB too low (should be > 38)", psnr > 38.0)
    }

    @Test
    fun `survives additive noise attack`() {
        val img = synthImage()
        val marked = FrequencyWatermark.embed(img, w, h, payload)
        var s = 99L
        val attacked = FloatArray(marked.size)
        for (i in marked.indices) {
            s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL
            val n = ((s % 17) - 8).toFloat() // ±8
            attacked[i] = (marked[i] + n).coerceIn(0f, 255f)
        }
        val recovered = FrequencyWatermark.extract(attacked, w, h)
        assertNotNull(recovered)
        assertArrayEquals(payload, recovered)
    }

    @Test
    fun `survives center crop attack`() {
        val img = synthImage()
        val marked = FrequencyWatermark.embed(img, w, h, payload)
        val cw = (w * 0.75f).toInt() / Dct8.N * Dct8.N
        val ch = (h * 0.75f).toInt() / Dct8.N * Dct8.N
        val offX = (w - cw) / 2 / Dct8.N * Dct8.N
        val offY = (h - ch) / 2 / Dct8.N * Dct8.N
        val cropped = FloatArray(cw * ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                cropped[y * cw + x] = marked[(offY + y) * w + (offX + x)]
            }
        }
        val recovered = FrequencyWatermark.extract(cropped, cw, ch)
        assertNotNull(recovered)
        assertArrayEquals(payload, recovered)
    }

    @Test
    fun `survives jpeg-like quantization at quality 75`() {
        val img = synthImage()
        val marked = FrequencyWatermark.embed(img, w, h, payload)
        val attacked = jpegQuantize(marked, w, h, quality = 75)
        val recovered = FrequencyWatermark.extract(attacked, w, h)
        assertNotNull(recovered)
        assertArrayEquals(payload, recovered)
    }

    @Test
    fun `survives jpeg-like quantization at quality 50`() {
        val img = synthImage()
        val marked = FrequencyWatermark.embed(img, w, h, payload)
        val attacked = jpegQuantize(marked, w, h, quality = 50)
        val recovered = FrequencyWatermark.extract(attacked, w, h)
        assertNotNull(recovered)
        assertArrayEquals(payload, recovered)
    }

    @Test
    fun `extract returns null on unwatermarked image`() {
        val img = synthImage()
        assertNull(FrequencyWatermark.extract(img, w, h))
    }

    @Test
    fun `too small image is left untouched`() {
        val sw = 8
        val sh = 8
        val img = FloatArray(sw * sh) { 128f }
        val out = FrequencyWatermark.embed(img, sw, sh, payload)
        assertArrayEquals(img, out, 0f)
        assertNull(FrequencyWatermark.extract(out, sw, sh))
    }

    /** 标准 JPEG 亮度量化表 + 质量缩放，对每个 8x8 块做量化/反量化，模拟 JPEG 压缩。 */
    private fun jpegQuantize(luma: FloatArray, width: Int, height: Int, quality: Int): FloatArray {
        val base = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99
        )
        val scale = if (quality < 50) 5000.0 / quality else 200.0 - 2.0 * quality
        val q = IntArray(64) { ((base[it] * scale + 50) / 100).toInt().coerceIn(1, 255) }

        val out = luma.copyOf()
        val blocksX = width / Dct8.N
        val blocksY = height / Dct8.N
        val block = DoubleArray(Dct8.BLOCK)
        val work = DoubleArray(Dct8.BLOCK)
        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                for (r in 0 until Dct8.N) {
                    val base0 = (by * Dct8.N + r) * width + bx * Dct8.N
                    for (c in 0 until Dct8.N) block[r * Dct8.N + c] = out[base0 + c].toDouble()
                }
                Dct8.forward(block, work)
                for (i in 0 until Dct8.BLOCK) {
                    work[i] = kotlin.math.round(work[i] / q[i]) * q[i]
                }
                Dct8.inverse(work, block)
                for (r in 0 until Dct8.N) {
                    val base0 = (by * Dct8.N + r) * width + bx * Dct8.N
                    for (c in 0 until Dct8.N) {
                        out[base0 + c] = block[r * Dct8.N + c].roundToInt().coerceIn(0, 255).toFloat()
                    }
                }
            }
        }
        return out
    }
}
