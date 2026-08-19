package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 聊天壁纸 / 字体档位：按账号隔离本地偏好。
 */
object ChatAppearancePreferences {
    private const val PREFS_NAME = "chat_appearance_prefs"
    private const val KEY_WALLPAPER = "wallpaper"
    private const val KEY_CUSTOM_WALLPAPER = "custom_wallpaper_uri"
    private const val KEY_FONT = "font_scale"
    private const val KEY_BUBBLE_COLOR = "bubble_color"
    private const val KEY_BUBBLE_SHAPE = "bubble_shape"

    fun getWallpaper(context: Context): ChatWallpaperPreset {
        val userId = currentUserId(context) ?: return ChatWallpaperPreset.DEFAULT
        val raw = prefs(context).getString(key(KEY_WALLPAPER, userId), null)
        return ChatAppearancePolicy.normalizeWallpaper(raw)
    }

    fun setWallpaper(context: Context, preset: ChatWallpaperPreset) {
        val userId = currentUserId(context) ?: return
        prefs(context).edit().putString(key(KEY_WALLPAPER, userId), preset.id).apply()
    }

    /** 自定义图片壁纸的本地 URI（用户选择的图片）；null 表示未设置。 */
    fun getCustomWallpaperUri(context: Context): String? {
        val userId = currentUserId(context) ?: return null
        return prefs(context).getString(key(KEY_CUSTOM_WALLPAPER, userId), null)
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * 把用户选择的自定义壁纸复制到应用私有目录并记录路径。
     * content:// 授权在设备重启后失效，必须持久化副本；返回存储后的 file:// URI，失败返回 null。
     */
    fun persistCustomWallpaper(context: Context, sourceUri: String): String? {
        val userId = currentUserId(context) ?: return null
        if (sourceUri.isBlank()) return null
        return runCatching {
            val dir = java.io.File(context.filesDir, "wallpapers").apply { mkdirs() }
            val target = java.io.File(dir, "custom_$userId.jpg")
            context.contentResolver.openInputStream(android.net.Uri.parse(sourceUri))?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absoluteFile.toURI().toString()
        }.getOrNull()?.also { stored ->
            prefs(context).edit().putString(key(KEY_CUSTOM_WALLPAPER, userId), stored).apply()
        }
    }

    fun clearCustomWallpaperUri(context: Context) {
        val userId = currentUserId(context) ?: return
        prefs(context).edit().remove(key(KEY_CUSTOM_WALLPAPER, userId)).apply()
        runCatching {
            java.io.File(context.filesDir, "wallpapers/custom_$userId.jpg").delete()
        }
    }

    fun getFontScale(context: Context): ChatFontScale {
        val userId = currentUserId(context) ?: return ChatFontScale.NORMAL
        val raw = prefs(context).getString(key(KEY_FONT, userId), null)
        return ChatAppearancePolicy.normalizeFontScale(raw)
    }

    fun setFontScale(context: Context, scale: ChatFontScale) {
        val userId = currentUserId(context) ?: return
        prefs(context).edit().putString(key(KEY_FONT, userId), scale.id).apply()
    }

    /** 聊天气泡颜色 id（见 ChatBubbleColorPalette）。 */
    fun getBubbleColor(context: Context): String {
        val userId = currentUserId(context) ?: return com.maodouchat.ui.theme.ChatBubbleColorPalette.BLUE
        val raw = prefs(context).getString(key(KEY_BUBBLE_COLOR, userId), null)
        return com.maodouchat.ui.theme.ChatBubbleColorPalette.normalize(raw)
    }

    /** 用户是否显式自定义过气泡色（主题接管发送气泡配色时用于判断优先级）。 */
    fun hasCustomBubbleColor(context: Context): Boolean {
        val userId = currentUserId(context) ?: return false
        return prefs(context).getString(key(KEY_BUBBLE_COLOR, userId), null) != null
    }

    fun setBubbleColor(context: Context, colorId: String) {
        val userId = currentUserId(context) ?: return
        val normalized = com.maodouchat.ui.theme.ChatBubbleColorPalette.normalize(colorId)
        prefs(context).edit().putString(key(KEY_BUBBLE_COLOR, userId), normalized).apply()
    }

    /** 气泡圆角风格 id（default / tg / round）。 */
    fun getBubbleShape(context: Context): String {
        val userId = currentUserId(context) ?: return "default"
        val raw = prefs(context).getString(key(KEY_BUBBLE_SHAPE, userId), null)
        return normalizeBubbleShape(raw)
    }

    fun setBubbleShape(context: Context, shapeId: String) {
        val userId = currentUserId(context) ?: return
        prefs(context).edit().putString(key(KEY_BUBBLE_SHAPE, userId), normalizeBubbleShape(shapeId)).apply()
    }

    fun normalizeBubbleShape(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id == "tg" || id == "round") id else "default"
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit()
            .remove(key(KEY_WALLPAPER, userId))
            .remove(key(KEY_CUSTOM_WALLPAPER, userId))
            .remove(key(KEY_FONT, userId))
            .remove(key(KEY_BUBBLE_COLOR, userId))
            .remove(key(KEY_BUBBLE_SHAPE, userId))
            .apply()
        runCatching {
            java.io.File(context.filesDir, "wallpapers/custom_$userId.jpg").delete()
        }
    }

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(prefix: String, userId: String): String = "${prefix}_$userId"
}
