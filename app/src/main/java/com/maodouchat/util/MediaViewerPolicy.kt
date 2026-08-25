package com.maodouchat.util

/**
 * 媒体大图浏览 / 保存 / 分享策略（纯函数）。
 * 不触网；仅基于本地 URI 与 MIME 判定。
 */
object MediaViewerPolicy {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 8f
    const val DOUBLE_TAP_SCALE = 3.5f

    fun isImageMime(mime: String?): Boolean {
        val m = mime.orEmpty().lowercase()
        return m.startsWith("image/") || m == "image" || m.endsWith("gif")
    }

    fun isVideoMime(mime: String?): Boolean {
        val m = mime.orEmpty().lowercase()
        return m.startsWith("video/")
    }

    fun defaultMime(typeName: String?, explicitMime: String?): String {
        explicitMime?.takeIf { it.isNotBlank() }?.let { return it }
        return when (typeName?.uppercase()) {
            "IMAGE" -> "image/jpeg"
            "GIF" -> "image/gif"
            "VIDEO" -> "video/mp4"
            "FILE" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    fun defaultFileName(typeName: String?, explicitName: String?, mime: String?): String {
        explicitName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val ext = when {
            mime?.contains("gif", ignoreCase = true) == true -> "gif"
            mime?.contains("png", ignoreCase = true) == true -> "png"
            mime?.contains("webp", ignoreCase = true) == true -> "webp"
            mime?.startsWith("video/", ignoreCase = true) == true -> "mp4"
            mime?.startsWith("image/", ignoreCase = true) == true -> "jpg"
            else -> "bin"
        }
        val prefix = when (typeName?.uppercase()) {
            "VIDEO" -> "video"
            "GIF" -> "gif"
            "IMAGE" -> "image"
            else -> "media"
        }
        return "${prefix}_${System.currentTimeMillis()}.$ext"
    }

    /** 双击：1x ↔ DOUBLE_TAP_SCALE */
    fun nextDoubleTapScale(current: Float): Float =
        if (current > 1.05f) MIN_SCALE else DOUBLE_TAP_SCALE

    fun clampScale(scale: Float): Float =
        // 9.162：双指同点等退化手势可产生 NaN 缩放——coerceIn 对 NaN 原样透传，
        // graphicsLayer 吃到 NaN 后图片渲染消失且无法再缩回；NaN 一律回落最小比例
        if (scale.isNaN()) MIN_SCALE else scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun canExportLocal(
        localReadable: Boolean,
        secretChat: Boolean = false,
        @Suppress("UNUSED_PARAMETER") exportBlockEnabled: Boolean = true
    ): Boolean = localReadable && !secretChat

    fun canShareLocal(
        localReadable: Boolean,
        secretChat: Boolean = false,
        @Suppress("UNUSED_PARAMETER") exportBlockEnabled: Boolean = true
    ): Boolean = localReadable && !secretChat
}
