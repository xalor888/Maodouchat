package com.maodouchat.util

import androidx.compose.ui.graphics.Color

/**
 * 聊天壁纸预设 + 消息字体档位（本地，不进 E2EE 明文）。
 */
enum class ChatWallpaperPreset(val id: String) {
    DEFAULT("default"),
    MINT("mint"),
    LAVENDER("lavender"),
    SAND("sand"),
    NIGHT("night"),
    ROSE("rose"),
    SKY("sky"),
    SLATE("slate"),
    PEACH("peach"),
    OLIVE("olive"),
    CORAL("coral"),
    PLUM("plum"),
    INDIGO("indigo"),
    AMBER("amber"),
    TEAL("teal"),
    GRAPHITE("graphite");

    companion object {
        fun fromId(raw: String?): ChatWallpaperPreset {
            val id = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

enum class ChatFontScale(val id: String, val multiplier: Float) {
    SMALL("small", 0.92f),
    NORMAL("normal", 1.0f),
    LARGE("large", 1.12f),
    XLARGE("xlarge", 1.24f),
    XXLARGE("xxlarge", 1.36f);

    companion object {
        fun fromId(raw: String?): ChatFontScale {
            val id = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }
}

object ChatAppearancePolicy {
    fun normalizeWallpaper(raw: String?): ChatWallpaperPreset = ChatWallpaperPreset.fromId(raw)
    fun normalizeFontScale(raw: String?): ChatFontScale = ChatFontScale.fromId(raw)

    /** 浅色模式下的壁纸底色；DEFAULT 返回 null 表示用主题默认。 */
    fun wallpaperColorLight(preset: ChatWallpaperPreset): Color? = when (preset) {
        ChatWallpaperPreset.DEFAULT -> null
        ChatWallpaperPreset.MINT -> Color(0xFFE8F6F1)
        ChatWallpaperPreset.LAVENDER -> Color(0xFFF0ECFA)
        ChatWallpaperPreset.SAND -> Color(0xFFF7F1E6)
        ChatWallpaperPreset.NIGHT -> Color(0xFF1B2430)
        ChatWallpaperPreset.ROSE -> Color(0xFFFBECEE)
        ChatWallpaperPreset.SKY -> Color(0xFFE8F3FC)
        ChatWallpaperPreset.SLATE -> Color(0xFFECEFF3)
        ChatWallpaperPreset.PEACH -> Color(0xFFFFF0E8)
        ChatWallpaperPreset.OLIVE -> Color(0xFFEEF3E6)
        ChatWallpaperPreset.CORAL -> Color(0xFFFFEBE6)
        ChatWallpaperPreset.PLUM -> Color(0xFFF3E8F6)
        ChatWallpaperPreset.INDIGO -> Color(0xFFE8ECFA)
        ChatWallpaperPreset.AMBER -> Color(0xFFFFF6E0)
        ChatWallpaperPreset.TEAL -> Color(0xFFE4F6F5)
        ChatWallpaperPreset.GRAPHITE -> Color(0xFFE8EAED)
    }

    fun wallpaperColorDark(preset: ChatWallpaperPreset): Color? = when (preset) {
        ChatWallpaperPreset.DEFAULT -> null
        ChatWallpaperPreset.MINT -> Color(0xFF14241F)
        ChatWallpaperPreset.LAVENDER -> Color(0xFF1C1830)
        ChatWallpaperPreset.SAND -> Color(0xFF2A241A)
        ChatWallpaperPreset.NIGHT -> Color(0xFF0B1018)
        ChatWallpaperPreset.ROSE -> Color(0xFF2A161C)
        ChatWallpaperPreset.SKY -> Color(0xFF101A28)
        ChatWallpaperPreset.SLATE -> Color(0xFF151A20)
        ChatWallpaperPreset.PEACH -> Color(0xFF2A1A14)
        ChatWallpaperPreset.OLIVE -> Color(0xFF171E14)
        ChatWallpaperPreset.CORAL -> Color(0xFF2A1614)
        ChatWallpaperPreset.PLUM -> Color(0xFF1E1424)
        ChatWallpaperPreset.INDIGO -> Color(0xFF141828)
        ChatWallpaperPreset.AMBER -> Color(0xFF2A2010)
        ChatWallpaperPreset.TEAL -> Color(0xFF102422)
        ChatWallpaperPreset.GRAPHITE -> Color(0xFF141618)
    }

    fun resolveBackground(
        preset: ChatWallpaperPreset,
        isDark: Boolean,
        fallback: Color
    ): Color {
        val override = if (isDark) wallpaperColorDark(preset) else wallpaperColorLight(preset)
        return override ?: fallback
    }
}
