package com.maodouchat.server.watermark

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Server-side blind watermark extraction for admin forensics.
 * Mirrors app SecretImageWatermark.extract using Java ImageIO (no Android).
 */
object AdminWatermarkExtractor {
    // 8.46 修复：解压炸弹防御——与 FileStorageService 同标准（维度 ≤ 8192、像素 ≤ 16M），
    // 否则一个几 KB 的 50000×50000 PNG 解码即分配 ~10GB 堆内存打崩进程。
    const val MAX_DIMENSION = 8192
    const val MAX_PIXELS = 16_000_000

    data class ExtractResult(
        val found: Boolean,
        val payloadHex: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val message: String = ""
    )

    fun extractFromBase64(dataUrlOrBase64: String): ExtractResult {
        val raw = dataUrlOrBase64.trim()
        if (raw.isBlank()) return ExtractResult(false, message = "empty_payload")
        val b64 = when {
            raw.contains("base64,") -> raw.substringAfter("base64,")
            else -> raw
        }
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (_: Exception) {
            return ExtractResult(false, message = "invalid_base64")
        }
        return extractFromBytes(bytes)
    }

    fun extractFromBytes(bytes: ByteArray): ExtractResult {
        if (bytes.isEmpty()) return ExtractResult(false, message = "empty_bytes")
        // 解码前先读头部尺寸，超限直接拒绝，避免 ImageIO.read 完整解码直接 OOM
        probeImageSize(bytes)?.let { (w, h) ->
            if (w > MAX_DIMENSION || h > MAX_DIMENSION || w.toLong() * h > MAX_PIXELS) {
                return ExtractResult(false, width = w, height = h, message = "image_too_large")
            }
        }
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        } ?: return ExtractResult(false, message = "unsupported_image")
        return extractFromImage(image)
    }

    fun extractFromImage(image: BufferedImage): ExtractResult {
        val w = image.width
        val h = image.height
        if (w < 64 || h < 64) {
            return ExtractResult(false, width = w, height = h, message = "image_too_small")
        }
        if (w > MAX_DIMENSION || h > MAX_DIMENSION || w.toLong() * h > MAX_PIXELS) {
            return ExtractResult(false, width = w, height = h, message = "image_too_large")
        }
        val argb = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = argb.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        val luma = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb.getRGB(x, y)
                val r = (p shr 16) and 0xFF
                val gch = (p shr 8) and 0xFF
                val b = p and 0xFF
                luma[y * w + x] = 0.299f * r + 0.587f * gch + 0.114f * b
            }
        }
        val payload = FrequencyWatermark.extract(luma, w, h)
            ?: return ExtractResult(false, width = w, height = h, message = "no_watermark")
        return ExtractResult(
            found = true,
            payloadHex = FrequencyWatermark.decodePayloadHex(payload),
            width = w,
            height = h,
            message = "ok"
        )
    }

    /**
     * 仅读取图像头获取宽高（不解码像素），用于解码前的尺寸守卫。
     * 返回 null 表示无法探测（交给 ImageIO.read 统一处理）。
     */
    private fun probeImageSize(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            javax.imageio.ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
                val readers = javax.imageio.ImageIO.getImageReaders(input)
                val reader = if (readers.hasNext()) readers.next() else return null
                try {
                    reader.setInput(input, true, true)
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w > 0 && h > 0) w to h else null
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Encode a synthetic watermarked image for self-test / docs demos. */
    fun embedDemoPngBase64(userId: String, chatId: String, deviceHint: String = "admin"): String {
        val w = 256
        val h = 256
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(240, 242, 248)
        g.fillRect(0, 0, w, h)
        g.color = Color(40, 90, 200)
        g.fillOval(40, 40, 176, 176)
        g.dispose()
        val luma = FloatArray(w * h)
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val gc = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.299f * r + 0.587f * gc + 0.114f * b
        }
        val payload = FrequencyWatermark.buildPayload(userId, chatId, deviceHint)
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
        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
