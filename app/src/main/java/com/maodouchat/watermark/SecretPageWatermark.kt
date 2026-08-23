package com.maodouchat.watermark

import kotlin.math.cos
import kotlin.math.sin

/**
 * 密聊整页盲水印合成点（XAL-45）。
 *
 * 与密聊图片共用 [ReferenceBlindWatermark] DWT+SVD，不改算法。
 * Compose 无法回读 GPU framebuffer，因此：
 * - [embedCapture] / [extractCapture] 是截屏/翻拍归因的可测嵌入与提取入口；
 * - [composeInvisibleTile] 生成铺满密聊页的不可见纹理（无可读文字），进入密聊即绘制。
 */
object SecretPageWatermark {
    const val TILE = 256

    fun buildPayload(userId: String?, chatId: String?, deviceHint: String?): ByteArray =
        FrequencyWatermark.buildPayload(userId, chatId, deviceHint)

    /**
     * 对整页 ARGB 捕获做频域嵌入。纯文字页（背景 + 气泡块）与带图页走同一路径。
     * 图过小则原样返回。
     */
    fun embedCapture(
        pixels: IntArray,
        width: Int,
        height: Int,
        payload: ByteArray
    ): IntArray {
        require(payload.size == FrequencyWatermark.PAYLOAD_BYTES) {
            "payload must be ${FrequencyWatermark.PAYLOAD_BYTES} bytes"
        }
        return ReferenceBlindWatermark.embedPixels(pixels, width, height, payload)
    }

    /** 从整页捕获提取载荷；无水印或过小返回 null。 */
    fun extractCapture(
        pixels: IntArray,
        width: Int,
        height: Int
    ): ByteArray? = ReferenceBlindWatermark.extractPayload(
        pixels,
        width,
        height,
        payloadBitCount = FrequencyWatermark.PAYLOAD_BYTES * 8
    )

    fun extractHex(pixels: IntArray, width: Int, height: Int): String? =
        extractCapture(pixels, width, height)?.let { FrequencyWatermark.decodePayloadHex(it) }

    /**
     * 生成 [TILE] 方形不可见载波：中灰平滑渐变 + 同一套 DWT+SVD。
     * 运行时以 Overlay 铺满密聊表面，不含 `MC·uid·…` 可读字样。
     */
    fun composeInvisibleTile(
        payload: ByteArray,
        size: Int = TILE
    ): IntArray {
        require(payload.size == FrequencyWatermark.PAYLOAD_BYTES) {
            "payload must be ${FrequencyWatermark.PAYLOAD_BYTES} bytes"
        }
        require(size >= TILE) { "tile must be at least $TILE px to hold the payload" }
        val carrier = IntArray(size * size)
        for (i in carrier.indices) {
            val x = i % size
            val y = i / size
            val r = (128 + 18 * sin(x / 17.0) * cos(y / 23.0)).toInt().coerceIn(0, 255)
            val g = (128 + 18 * cos(x / 29.0) * sin(y / 19.0)).toInt().coerceIn(0, 255)
            val b = (128 + 14 * sin((x + y) / 31.0)).toInt().coerceIn(0, 255)
            carrier[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return ReferenceBlindWatermark.embedPixels(carrier, size, size, payload)
    }
}

/**
 * 密聊水印产品策略：表面不绘制可读明水印；整页盲水印随密聊 + [RuntimeFlags.BLIND_WATERMARK] 生效。
 */
object SecretWatermarkPolicy {
    /** 密聊表面永不绘制对角可读字；visible 开关不再驱动密聊路径。 */
    @Suppress("UNUSED_PARAMETER")
    fun drawsVisibleOverlayOnSecretSurface(visibleFlagEnabled: Boolean): Boolean = false

    fun pageBlindWatermarkEnabled(isSecretChat: Boolean, blindWatermarkFlag: Boolean): Boolean =
        isSecretChat && blindWatermarkFlag
}
