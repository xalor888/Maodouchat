package com.maodouchat.watermark

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceBlindWatermarkTest {

    private fun synthImage(w: Int, h: Int, seed: Long): IntArray {
        val rng = java.util.Random(seed)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            // 平滑渐变 + 噪声，模拟真实照片的频域结构
            val x = i % w
            val y = i / w
            val r = (128 + 100 * kotlin.math.sin(x / 17.0) * kotlin.math.cos(y / 23.0) + rng.nextInt(24) - 12)
                .toInt().coerceIn(0, 255)
            val g = (128 + 100 * kotlin.math.cos(x / 29.0) * kotlin.math.sin(y / 19.0) + rng.nextInt(24) - 12)
                .toInt().coerceIn(0, 255)
            val b = (128 + 80 * kotlin.math.sin((x + y) / 31.0) + rng.nextInt(24) - 12)
                .toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }

    @Test
    fun `embed and extract round trip on 256x256`() {
        val payload = byteArrayOf(0x1A, 0x2B, 0x3C, 0x4D, 0x5E.toByte(), 0x6F)
        val w = 256
        val h = 256
        val src = synthImage(w, h, 42)
        val embedded = ReferenceBlindWatermark.embedPixels(src, w, h, payload)
        // 视觉不可见性粗检：平均每像素变化应很小
        var maxDelta = 0
        var sumDelta = 0.0
        for (i in src.indices) {
            val dr = ((src[i] shr 16) and 0xFF) - ((embedded[i] shr 16) and 0xFF)
            val dg = ((src[i] shr 8) and 0xFF) - ((embedded[i] shr 8) and 0xFF)
            val db = (src[i] and 0xFF) - (embedded[i] and 0xFF)
            val delta = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
            if (delta > maxDelta) maxDelta = delta
            sumDelta += delta
        }
        assertTrue(maxDelta <= 40, "watermark should be visually subtle, maxDelta=$maxDelta")
        assertTrue(sumDelta / src.size < 8.0, "mean delta too high: ${sumDelta / src.size}")

        val extracted = ReferenceBlindWatermark.extractPayload(embedded, w, h, payload.size * 8)
        assertNotNull(extracted, "round-trip extraction must succeed")
        assertContentEquals(payload, extracted)
    }

    @Test
    fun `extract survives light pixel noise`() {
        val payload = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x09, 0x0F)
        val w = 256
        val h = 256
        val src = synthImage(w, h, 7)
        val embedded = ReferenceBlindWatermark.embedPixels(src, w, h, payload)
        // 加 ±2 噪声（模拟轻度压缩/处理）
        val rng = java.util.Random(99)
        val noisy = IntArray(embedded.size) { i ->
            val p = embedded[i]
            val r = (((p shr 16) and 0xFF) + rng.nextInt(5) - 2).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + rng.nextInt(5) - 2).coerceIn(0, 255)
            val b = ((p and 0xFF) + rng.nextInt(5) - 2).coerceIn(0, 255)
            (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        val extracted = ReferenceBlindWatermark.extractPayload(noisy, w, h, payload.size * 8)
        assertNotNull(extracted, "noisy extraction should succeed")
        assertContentEquals(payload, extracted)
    }

    @Test
    fun `too small image returns unchanged and null`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val w = 32
        val h = 32 // 8×8=64 blocks ≤ 48 bits? 64 > 48 可嵌入；改用 16×16（4×4=16 blocks）
        val small = synthImage(16, 16, 3)
        val embedded = ReferenceBlindWatermark.embedPixels(small, 16, 16, payload)
        assertContentEquals(small, embedded, "too-small image must be returned unchanged")
        assertNull(
            ReferenceBlindWatermark.extractPayload(small, 16, 16, payload.size * 8),
            "too-small image must yield null"
        )
    }

    @Test
    fun `clean image without watermark yields null`() {
        val w = 256
        val h = 256
        val clean = synthImage(w, h, 12345)
        // 未嵌入任何水印的图片不得提取出假阳性载荷
        assertNull(
            ReferenceBlindWatermark.extractPayload(clean, w, h, 6 * 8),
            "clean image must not produce a false-positive payload"
        )
    }

    @Test
    fun `bits and bytes conversion round trips`() {
        val bytes = byteArrayOf(0, 1, 2, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val bits = ReferenceBlindWatermark.bytesToBits(bytes)
        assertEquals(48, bits.size)
        assertContentEquals(bytes, ReferenceBlindWatermark.bitsToBytes(bits))
    }

    @Test
    fun `dct and idct are inverses`() {
        val rng = java.util.Random(11)
        val block = Array(4) { FloatArray(4) { rng.nextFloat() * 50f } }
        val back = ReferenceBlindWatermark.idct2(ReferenceBlindWatermark.dct2(block))
        for (r in 0 until 4) for (c in 0 until 4) {
            assertTrue(kotlin.math.abs(block[r][c] - back[r][c]) < 0.5f, "dct/idct must invert (${block[r][c]} vs ${back[r][c]})")
        }
    }

    @Test
    fun `svd reconstruction is exact`() {
        val a = Array(4) { FloatArray(4) }
        val rng = java.util.Random(5)
        for (r in 0 until 4) for (c in 0 until 4) a[r][c] = rng.nextFloat() * 100f
        val (u, s, vt) = ReferenceBlindWatermark.svd(a)
        for (r in 0 until 4) for (c in 0 until 4) {
            var rec = 0.0
            for (k in 0 until 4) rec += u[r][k] * s[k] * vt[k][c]
            assertTrue(kotlin.math.abs(a[r][c] - rec) < 0.5f, "svd must reconstruct A (${a[r][c]} vs $rec)")
        }
    }
}
