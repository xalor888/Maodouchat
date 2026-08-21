package com.maodouchat.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

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

    /** 对 [src] 嵌入 6 字节载荷，返回新的 ARGB_8888 Bitmap；原图不变。
     *  9.4xx：算法切换为参考仓库 blind_watermark 的 DWT+SVD 频域盲水印（ReferenceBlindWatermark）。 */
    fun embed(src: Bitmap, payload: ByteArray): Bitmap {
        require(payload.size == FrequencyWatermark.PAYLOAD_BYTES) {
            "payload must be ${FrequencyWatermark.PAYLOAD_BYTES} bytes"
        }
        val w = src.width
        val h = src.height
        val bmp = toMutableArgb(src)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ReferenceBlindWatermark.embedPixels(pixels, w, h, payload)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 从 [src] 提取载荷；无水印或图像过小返回 null。 */
    fun extract(src: Bitmap): ByteArray? {
        // 9.4xx：HARDWARE 位图 getPixels 会抛 IllegalStateException——先统一转为软件位图
        val soft = toMutableArgb(src)
        val w = soft.width
        val h = soft.height
        val pixels = IntArray(w * h)
        soft.getPixels(pixels, 0, w, 0, 0, w, h)
        return ReferenceBlindWatermark.extractPayload(
            pixels, w, h,
            payloadBitCount = FrequencyWatermark.PAYLOAD_BYTES * 8
        )
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
