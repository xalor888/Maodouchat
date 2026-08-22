package com.maodouchat.server.watermark

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminWatermarkExtractorTest {

    @Test
    fun `demo embed extracts via dwt svd path`() {
        val b64 = AdminWatermarkExtractor.embedDemoPngBase64("user-abc", "chat-xyz", "device-1")
        val result = AdminWatermarkExtractor.extractFromBase64(b64)
        assertTrue(result.found, result.message)
        assertEquals("ok", result.message)
        val expected = FrequencyWatermark.decodePayloadHex(
            FrequencyWatermark.buildPayload("user-abc", "chat-xyz", "device-1")
        )
        assertEquals(expected, result.payloadHex)
        assertEquals(256, result.width)
        assertEquals(256, result.height)
    }

    @Test
    fun `clean image reports no watermark`() {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(180, 190, 200)
        g.fillRect(0, 0, 256, 256)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        val result = AdminWatermarkExtractor.extractFromBytes(baos.toByteArray())
        assertFalse(result.found)
        assertEquals("no_watermark", result.message)
    }

    @Test
    fun `legacy dct qim screenshot still extracts via fallback`() {
        val w = 256
        val h = 256
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(240, 242, 248)
        g.fillRect(0, 0, w, h)
        g.color = Color(40, 90, 200)
        g.fillOval(40, 40, 176, 176)
        g.dispose()
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val gc = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.299f * r + 0.587f * gc + 0.114f * b
        }
        val payload = FrequencyWatermark.buildPayload("legacy-user", "legacy-chat", "legacy-device")
        val outLuma = FrequencyWatermark.embed(luma, w, h, payload)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val gc = (p shr 8) and 0xFF
            val b = p and 0xFF
            val yOld = 0.299f * r + 0.587f * gc + 0.114f * b
            val delta = outLuma[i] - yOld
            val nr = (r + delta).roundToInt().coerceIn(0, 255)
            val ng = (gc + delta).roundToInt().coerceIn(0, 255)
            val nb = (b + delta).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        img.setRGB(0, 0, w, h, pixels, 0, w)
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        val result = AdminWatermarkExtractor.extractFromBytes(baos.toByteArray())
        assertTrue(result.found, result.message)
        assertEquals(FrequencyWatermark.decodePayloadHex(payload), result.payloadHex)
    }

    @Test
    fun `invalid input is rejected without crash`() {
        val empty = AdminWatermarkExtractor.extractFromBase64("")
        assertFalse(empty.found)
        assertEquals("empty_payload", empty.message)
        val bad = AdminWatermarkExtractor.extractFromBase64("%%%not-base64%%%")
        assertFalse(bad.found)
        assertEquals("invalid_base64", bad.message)
        val tiny = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val tinyResult = AdminWatermarkExtractor.extractFromImage(tiny)
        assertFalse(tinyResult.found)
        assertEquals("image_too_small", tinyResult.message)
        assertNotNull(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
    }
}
