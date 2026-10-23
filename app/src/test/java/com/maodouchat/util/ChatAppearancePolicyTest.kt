package com.maodouchat.util

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `every preset has distinct light and dark colors`() {
        // 16 个预设浅色/深色都有显式色值——resolveBackground 的 fallback 只是保险
        ChatWallpaperPreset.entries.forEach {
            val light = ChatAppearancePolicy.wallpaperColorLight(it)
            val dark = ChatAppearancePolicy.wallpaperColorDark(it)
            assertNotNull("$it light", light)
            assertNotNull("$it dark", dark)
            assertFalse("$it light == dark", light == dark)
        }
    }

    @Test
    fun `resolveBackground honors theme over fallback`() {
        val fallback = Color(0xFFFFFFFF)
        assertEquals(Color(0xFFDBE7F3), ChatAppearancePolicy.resolveBackground(ChatWallpaperPreset.DEFAULT, false, fallback))
        assertEquals(Color(0xFF0E1621), ChatAppearancePolicy.resolveBackground(ChatWallpaperPreset.DEFAULT, true, fallback))
    }

    @Test
    fun `normalize trims and lowercases`() {
        assertEquals(ChatWallpaperPreset.MINT, ChatAppearancePolicy.normalizeWallpaper("  Mint "))
        assertEquals(ChatWallpaperPreset.DEFAULT, ChatAppearancePolicy.normalizeWallpaper("  "))
        assertEquals(ChatFontScale.LARGE, ChatAppearancePolicy.normalizeFontScale("Large"))
        assertEquals(ChatFontScale.NORMAL, ChatAppearancePolicy.normalizeFontScale(null))
    }

    @Test
    fun `font multipliers stay ordered through xxlarge`() {
        val ordered = listOf(ChatFontScale.SMALL, ChatFontScale.NORMAL, ChatFontScale.LARGE, ChatFontScale.XLARGE, ChatFontScale.XXLARGE)
        ordered.zipWithNext { a, b -> assertTrue("${a.id} < ${b.id}", a.multiplier < b.multiplier) }
    }

    @Test
    fun `preset ids are unique lowercase`() {
        val ids = ChatWallpaperPreset.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(it, it.lowercase()) }
    }
}
