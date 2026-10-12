package com.maodouchat.server.watermark

import kotlin.math.round

// 原 ReferenceBlindWatermark 成员按域拆出的扩展函数（同包调用，零行为改动）。

// ── 像素容器（BGR float 平面，ARGB 兼容）───────────────────

internal class ArgbImage(pixels: IntArray, val width: Int, val height: Int) {
    /** 每通道平面：B=0, G=1, R=2（原版 BGR 顺序）。 */
    val bgr = Array(3) { FloatArray(width * height) }
    var alpha: FloatArray? = null

    init {
        var hasAlpha = false
        val a = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            bgr[0][i] = (p and 0xFF).toFloat()
            bgr[1][i] = ((p shr 8) and 0xFF).toFloat()
            bgr[2][i] = ((p shr 16) and 0xFF).toFloat()
            val av = (p shr 24) and 0xFF
            a[i] = av.toFloat()
            if (av < 255) hasAlpha = true
        }
        if (hasAlpha) alpha = a
    }

    /** 可嵌入的最大位容量（块数）。 */
    fun maxBlocks(): Int {
        val caH = (height + 1) / 2
        val caW = (width + 1) / 2
        return (caH / ReferenceBlindWatermark.BLOCK) * (caW / ReferenceBlindWatermark.BLOCK)
    }

    fun toPixels(): IntArray {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val r = round(bgr[2][i]).coerceIn(0f, 255f).toInt()
            val g = round(bgr[1][i]).coerceIn(0f, 255f).toInt()
            val b = round(bgr[0][i]).coerceIn(0f, 255f).toInt()
            val a = alpha?.get(i)?.let { round(it).coerceIn(0f, 255f).toInt() } ?: 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}
