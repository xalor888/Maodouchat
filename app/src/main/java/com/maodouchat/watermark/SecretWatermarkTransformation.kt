package com.maodouchat.watermark

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Coil 频域盲水印 Transformation：在解码后的 Bitmap 上注入不可见 DCT-QIM 水印。
 *
 * 仅用于密聊图片渲染：显示前对 Bitmap 做频域水印嵌入，截图（即便绕过 FLAG_SECURE）
 * 仍携带可提取的归因载荷。[cacheKey] 含载荷摘要，确保水印版与普通版缓存隔离。
 */
class SecretWatermarkTransformation(
    private val payload: ByteArray
) : Transformation {

    override val cacheKey: String = "secret_freq_wm_" + FrequencyWatermark.decodePayloadHex(payload)

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // SecretImageWatermark.embed 内部会复制为可写 ARGB_8888，不消费 input
        return SecretImageWatermark.embed(input, payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecretWatermarkTransformation) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = payload.contentHashCode()
}
