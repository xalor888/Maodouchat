package com.maodouchat.util

import androidx.compose.ui.graphics.Color
import com.maodouchat.domain.messaging.ChatFontScale
import com.maodouchat.domain.messaging.ChatWallpaperColorPolicy
import com.maodouchat.domain.messaging.ChatWallpaperPreset

object ChatAppearancePolicy {
    fun normalizeWallpaper(raw: String?): ChatWallpaperPreset = ChatWallpaperPreset.fromId(raw)
    fun normalizeFontScale(raw: String?): ChatFontScale = ChatFontScale.fromId(raw)

    fun wallpaperColorLight(preset: ChatWallpaperPreset): Color? =
        Color(ChatWallpaperColorPolicy.wallpaperArgbLight(preset))

    fun wallpaperColorDark(preset: ChatWallpaperPreset): Color? =
        Color(ChatWallpaperColorPolicy.wallpaperArgbDark(preset))

    fun resolveBackground(preset: ChatWallpaperPreset, isDark: Boolean, fallback: Color): Color =
        Color(ChatWallpaperColorPolicy.resolveBackgroundArgb(preset, isDark, fallback.value.toLong()))
}
