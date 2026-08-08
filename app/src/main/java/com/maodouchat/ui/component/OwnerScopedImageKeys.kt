package com.maodouchat.ui.component

import android.content.Context
import coil.request.ImageRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.watermark.FrequencyWatermark
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.watermark.SecretWatermarkTransformation

/**
 * Coil memory/disk keys must include the logged-in owner so account A cannot
 * briefly paint account B's decoded bitmap when logout Coil clear races or fails.
 * Matches [Avatar] / [GroupAvatar] isolation.
 */
object OwnerScopedImageKeys {
    /** Pure key format used by [cacheKey]; unit-tested without Android. */
    fun formatKey(ownerUserId: String, data: String): String? {
        if (data.isBlank()) return null
        return "${ownerUserId}:${data}"
    }

    /**
     * @param secretPayload 非空时表示当前为密聊图片，需注入频域盲水印；
     *        缓存键追加水印标记，避免与普通版缓存冲突。
     * @param sizeWidth/Height 非空时追加目标尺寸——8.33 修复：同一图片在列表（1024px）与
     *        查看器（2048px）间切换曾共用同一缓存键，最后一次解码尺寸覆盖前一次，来回切换
     *        反复全量解码且查看器打开后列表气泡持有 2048px 位图（约 4 倍内存）。
     */
    fun cacheKey(
        context: Context,
        data: Any?,
        secretPayload: ByteArray? = null,
        sizeWidth: Int? = null,
        sizeHeight: Int? = null
    ): String? {
        if (data == null) return null
        val owner = TokenManager.getInstance(context).getUserId().orEmpty()
        val raw = when (data) {
            is String -> data
            else -> data.toString()
        }
        val base = formatKey(owner, raw) ?: return null
        val withSize = if (sizeWidth != null && sizeHeight != null) "$base#size:${sizeWidth}x${sizeHeight}" else base
        return if (secretPayload != null && RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) {
            "$withSize#swm:${FrequencyWatermark.decodePayloadHex(secretPayload)}"
        } else {
            withSize
        }
    }

    fun request(
        context: Context,
        data: Any?,
        crossfade: Boolean = true,
        sizeWidth: Int? = null,
        sizeHeight: Int? = null,
        secretPayload: ByteArray? = null
    ): ImageRequest {
        val key = cacheKey(context, data, secretPayload, sizeWidth, sizeHeight)
        val builder = ImageRequest.Builder(context).data(data)
        if (crossfade) builder.crossfade(true)
        if (sizeWidth != null && sizeHeight != null) builder.size(sizeWidth, sizeHeight)
        if (secretPayload != null && RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) {
            builder.transformations(SecretWatermarkTransformation(secretPayload))
        }
        if (key != null) {
            builder.memoryCacheKey(key).diskCacheKey(key)
        }
        // 密聊图片/视频帧禁止落盘：磁盘缓存为明文且可被备份或其他具备存储权限的应用读取，违反密聊预期。
        // 仅保留内存缓存（易失、随进程死亡清除，且已按 owner 隔离）；即便盲水印关闭也禁用磁盘缓存。
        if (secretPayload != null) {
            builder.diskCachePolicy(coil.request.CachePolicy.DISABLED)
        }
        return builder.build()
    }
}
