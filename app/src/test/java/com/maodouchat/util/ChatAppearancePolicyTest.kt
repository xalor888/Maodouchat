package com.maodouchat.util

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatAppearancePolicyTest {
    @Test
    fun `normalize wallpaper and font`() {
        assertEquals(ChatWallpaperPreset.DEFAULT, ChatAppearancePolicy.normalizeWallpaper(null))
        assertEquals(ChatWallpaperPreset.MINT, ChatAppearancePolicy.normalizeWallpaper("mint"))
        assertEquals(ChatFontScale.NORMAL, ChatAppearancePolicy.normalizeFontScale("nope"))
        assertEquals(ChatFontScale.LARGE, ChatAppearancePolicy.normalizeFontScale("large"))
    }

    @Test
    fun `default wallpaper uses telegram light blue`() {
        // 默认壁纸已对齐 Telegram 浅蓝 #DBE7F3（不再返回 null 用主题默认灰白）
        assertEquals(Color(0xFFDBE7F3), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.DEFAULT))
        assertNotNull(ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.ROSE))
    }

    @Test
    fun `font multipliers ordered`() {
        assertEquals(true, ChatFontScale.SMALL.multiplier < ChatFontScale.NORMAL.multiplier)
        assertEquals(true, ChatFontScale.NORMAL.multiplier < ChatFontScale.LARGE.multiplier)
        assertEquals(true, ChatFontScale.LARGE.multiplier < ChatFontScale.XLARGE.multiplier)
    }
}
