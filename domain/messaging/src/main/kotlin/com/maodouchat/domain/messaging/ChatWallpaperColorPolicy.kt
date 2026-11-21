package com.maodouchat.domain.messaging

// 16 预设壁纸底色，浅/深两套 ARGB Long；domain 没有 Compose 依赖，调用方再包 Color(argb)。
object ChatWallpaperColorPolicy {
    fun wallpaperArgbLight(preset: ChatWallpaperPreset): Long = when (preset) {
        ChatWallpaperPreset.DEFAULT -> 0xFFDBE7F3L
        ChatWallpaperPreset.MINT -> 0xFFE8F6F1L
        ChatWallpaperPreset.LAVENDER -> 0xFFF0ECFAL
        ChatWallpaperPreset.SAND -> 0xFFF7F1E6L
        ChatWallpaperPreset.NIGHT -> 0xFF1B2430L
        ChatWallpaperPreset.ROSE -> 0xFFFBECEEL
        ChatWallpaperPreset.SKY -> 0xFFE8F3FCL
        ChatWallpaperPreset.SLATE -> 0xFFECEFF3L
        ChatWallpaperPreset.PEACH -> 0xFFFFF0E8L
        ChatWallpaperPreset.OLIVE -> 0xFFEEF3E6L
        ChatWallpaperPreset.CORAL -> 0xFFFFEBE6L
        ChatWallpaperPreset.PLUM -> 0xFFF3E8F6L
        ChatWallpaperPreset.INDIGO -> 0xFFE8ECFAL
        ChatWallpaperPreset.AMBER -> 0xFFFFF6E0L
        ChatWallpaperPreset.TEAL -> 0xFFE4F6F5L
        ChatWallpaperPreset.GRAPHITE -> 0xFFE8EAEDL
    }

    fun wallpaperArgbDark(preset: ChatWallpaperPreset): Long = when (preset) {
        ChatWallpaperPreset.DEFAULT -> 0xFF0E1621L
        ChatWallpaperPreset.MINT -> 0xFF14241FL
        ChatWallpaperPreset.LAVENDER -> 0xFF1C1830L
        ChatWallpaperPreset.SAND -> 0xFF2A241AL
        ChatWallpaperPreset.NIGHT -> 0xFF0B1018L
        ChatWallpaperPreset.ROSE -> 0xFF2A161CL
        ChatWallpaperPreset.SKY -> 0xFF101A28L
        ChatWallpaperPreset.SLATE -> 0xFF151A20L
        ChatWallpaperPreset.PEACH -> 0xFF2A1A14L
        ChatWallpaperPreset.OLIVE -> 0xFF171E14L
        ChatWallpaperPreset.CORAL -> 0xFF2A1614L
        ChatWallpaperPreset.PLUM -> 0xFF1E1424L
        ChatWallpaperPreset.INDIGO -> 0xFF141828L
        ChatWallpaperPreset.AMBER -> 0xFF2A2010L
        ChatWallpaperPreset.TEAL -> 0xFF102422L
        ChatWallpaperPreset.GRAPHITE -> 0xFF141618L
    }

    fun resolveBackgroundArgb(preset: ChatWallpaperPreset, isDark: Boolean, fallbackArgb: Long): Long =
        if (isDark) wallpaperArgbDark(preset) else wallpaperArgbLight(preset)
}
