package com.maodouchat.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * 频域盲水印的 Android Bitmap 适配层。
 *
 * - 嵌入：把 Bitmap 的亮度平面抽出，交给 [FrequencyWatermark] 做 DCT-QIM 嵌入，
 *   再把亮度增量等量叠加回 R/G/B（色度不变，提升不可见性）。
 * - 提取：从 Bitmap 亮度平面恢复载荷，用于取证比对泄露截图。
 *
 * 适用于密聊图片：渲染前对显示用 Bitmap 注入不可见水印，即便 FLAG_SECURE 被绕过
 * （root 截屏 / adb / 外部相机翻拍后转发的截图），仍可从像素中提取归因载荷。
 */
object SecretImageWatermark {

    /** 对 [src] 嵌入 6 字节载荷，返回新的 ARGB_8888 Bitmap；原图不变。 */
    fun embed(src: Bitmap, payload: ByteArray): Bitmap {
        require(payload.size == FrequencyWatermark.PAYLOAD_BYTES) {
            "payload must be ${FrequencyWatermark.PAYLOAD_BYTES} bytes"
        }
        val w = src.width
        val h = src.height
        val bmp = toMutableArgb(src)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        val outLuma = FrequencyWatermark.embed(luma, w, h, payload)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val yOld = 0.299f * r + 0.587f * g + 0.114f * b
            val delta = outLuma[i] - yOld
            val nr = (r + delta).roundToInt().coerceIn(0, 255)
            val ng = (g + delta).roundToInt().coerceIn(0, 255)
            val nb = (b + delta).roundToInt().coerceIn(0, 255)
            pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 从 [src] 提取载荷；无水印或图像过小返回 null。 */
    fun extract(src: Bitmap): ByteArray? {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return FrequencyWatermark.extract(luma, w, h)
    }

    fun extractHex(src: Bitmap): String? =
        extract(src)?.let { FrequencyWatermark.decodePayloadHex(it) }

    /** 统一转为可写的 ARGB_8888（处理 HARDWARE / 565 / null 配置）。 */
    private fun toMutableArgb(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.ARGB_8888 && !src.isRecycled) {
            return try {
                src.copy(Bitmap.Config.ARGB_8888, true)
            } catch (_: Throwable) {
                redraw(src)
            }
        }
        return redraw(src)
    }

    private fun redraw(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
