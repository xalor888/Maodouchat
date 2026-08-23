package com.maodouchat.watermark

import com.maodouchat.util.RuntimeFlags
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretPageWatermarkTest {

    private fun synthPhoto(w: Int, h: Int, seed: Long): IntArray {
        val rng = java.util.Random(seed)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val x = i % w
            val y = i / w
            val r = (128 + 100 * sin(x / 17.0) * cos(y / 23.0) + rng.nextInt(24) - 12)
                .toInt().coerceIn(0, 255)
            val g = (128 + 100 * cos(x / 29.0) * sin(y / 19.0) + rng.nextInt(24) - 12)
                .toInt().coerceIn(0, 255)
            val b = (128 + 80 * sin((x + y) / 31.0) + rng.nextInt(24) - 12)
                .toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }

    /** 纯文字密聊页：背景 + 矩形气泡块，无照片。 */
    private fun synthTextPage(w: Int, h: Int): IntArray {
        val bg = 0xFFF5F0E8.toInt()
        val bubbleMine = 0xFFD4E8C8.toInt()
        val bubblePeer = 0xFFFFFFFF.toInt()
        val pixels = IntArray(w * h) { bg }
        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            val left = x0.coerceIn(0, w - 1)
            val right = x1.coerceIn(0, w - 1)
            val top = y0.coerceIn(0, h - 1)
            val bottom = y1.coerceIn(0, h - 1)
            for (y in top..bottom) {
                val row = y * w
                for (x in left..right) pixels[row + x] = color
            }
        }
        fillRect(24, 40, 180, 96, bubblePeer)
        fillRect(76, 112, 232, 168, bubbleMine)
        fillRect(24, 184, 200, 248, bubblePeer)
        return pixels
    }

    @Test
    fun `runtime flags have no readable overlay switch and secret path uses page carrier`() {
        val keys = RuntimeFlags::class.java.declaredFields
            .mapNotNull { field ->
                field.isAccessible = true
                (field.get(RuntimeFlags) as? RuntimeFlags.Flag)?.key
            }
        assertTrue(keys.none { it.startsWith("visible_") && it.contains("watermark") })
        assertTrue(RuntimeFlags.BLIND_WATERMARK.default)
        assertEquals("blind_watermark_enabled", RuntimeFlags.BLIND_WATERMARK.key)
        assertEquals(
            listOf("pageBlindWatermarkEnabled"),
            SecretWatermarkPolicy::class.java.declaredMethods
                .filter { it.declaringClass == SecretWatermarkPolicy::class.java }
                .map { it.name }
                .distinct()
                .sorted(),
        )
    }

    @Test
    fun `page blind watermark is gated by secret chat and blind flag`() {
        assertTrue(SecretWatermarkPolicy.pageBlindWatermarkEnabled(isSecretChat = true, blindWatermarkFlag = true))
        assertFalse(SecretWatermarkPolicy.pageBlindWatermarkEnabled(isSecretChat = false, blindWatermarkFlag = true))
        assertFalse(SecretWatermarkPolicy.pageBlindWatermarkEnabled(isSecretChat = true, blindWatermarkFlag = false))
    }

    @Test
    fun `full page photo capture embed extract round trip`() {
        val payload = SecretPageWatermark.buildPayload("user-abc123", "chat-xyz789", "deadbeef")
        val w = 256
        val h = 256
        val src = synthPhoto(w, h, 42)
        val embedded = SecretPageWatermark.embedCapture(src, w, h, payload)
        val extracted = SecretPageWatermark.extractCapture(embedded, w, h)
        assertNotNull(extracted)
        assertContentEquals(payload, extracted)
        assertEquals(FrequencyWatermark.decodePayloadHex(payload), SecretPageWatermark.extractHex(embedded, w, h))
    }

    @Test
    fun `plain text secret page capture still extracts attribution`() {
        val payload = SecretPageWatermark.buildPayload("alice", "secret-room", "dev1")
        val w = 256
        val h = 256
        val page = synthTextPage(w, h)
        val embedded = SecretPageWatermark.embedCapture(page, w, h, payload)
        val extracted = SecretPageWatermark.extractCapture(embedded, w, h)
        assertNotNull(extracted, "text-only secret page must still yield extractable payload")
        assertContentEquals(payload, extracted)
    }

    @Test
    fun `invisible tile is watermarked and contains no readable MC label bytes`() {
        val payload = SecretPageWatermark.buildPayload("user-abc123", "chat-xyz789", "deadbeef")
        val size = SecretPageWatermark.TILE
        val tile = SecretPageWatermark.composeInvisibleTile(payload, size)
        assertEquals(size * size, tile.size)
        val extracted = SecretPageWatermark.extractCapture(tile, size, size)
        assertNotNull(extracted)
        assertContentEquals(payload, extracted)
        // 载波不得编码可读 `MC·` 明水印字样（ASCII/UTF-8 扫描）。
        val bytes = ByteArray(tile.size * 3)
        var bi = 0
        for (p in tile) {
            bytes[bi++] = ((p shr 16) and 0xFF).toByte()
            bytes[bi++] = ((p shr 8) and 0xFF).toByte()
            bytes[bi++] = (p and 0xFF).toByte()
        }
        val asLatin = String(bytes, Charsets.ISO_8859_1)
        assertFalse(asLatin.contains("MC·"), "invisible tile must not encode readable MC label")
        assertFalse(asLatin.contains("MC."), "invisible tile must not encode readable MC label")
    }

    @Test
    fun `clean capture without embed yields null`() {
        val page = synthTextPage(256, 256)
        assertNull(SecretPageWatermark.extractCapture(page, 256, 256))
    }

    @Test
    fun `embed is visually subtle on text page`() {
        val payload = SecretPageWatermark.buildPayload("u", "c", "d")
        val src = synthTextPage(256, 256)
        val embedded = SecretPageWatermark.embedCapture(src, 256, 256, payload)
        var maxDelta = 0
        var sum = 0.0
        for (i in src.indices) {
            val dr = abs(((src[i] shr 16) and 0xFF) - ((embedded[i] shr 16) and 0xFF))
            val dg = abs(((src[i] shr 8) and 0xFF) - ((embedded[i] shr 8) and 0xFF))
            val db = abs((src[i] and 0xFF) - (embedded[i] and 0xFF))
            val delta = maxOf(dr, dg, db)
            if (delta > maxDelta) maxDelta = delta
            sum += delta
        }
        assertTrue(maxDelta <= 40, "maxDelta=$maxDelta")
        assertTrue(sum / src.size < 8.0, "mean delta too high: ${sum / src.size}")
    }
}
