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

    @Test
    fun `font scale multipliers are pinned`() {
        // 档位倍数是字号手感，改了就是改 UI 契约
        assertEquals(0.92f, ChatFontScale.SMALL.multiplier, 0.0001f)
        assertEquals(1.0f, ChatFontScale.NORMAL.multiplier, 0.0001f)
        assertEquals(1.12f, ChatFontScale.LARGE.multiplier, 0.0001f)
        assertEquals(1.24f, ChatFontScale.XLARGE.multiplier, 0.0001f)
        assertEquals(1.36f, ChatFontScale.XXLARGE.multiplier, 0.0001f)
    }

    @Test
    fun `every palette color is fully opaque`() {
        // 漏写 alpha 会透出底色，整包断言防手滑
        ChatWallpaperPreset.entries.forEach {
            val light = ChatAppearancePolicy.wallpaperColorLight(it)
            val dark = ChatAppearancePolicy.wallpaperColorDark(it)
            assertNotNull("$it light", light)
            assertNotNull("$it dark", dark)
            assertEquals("$it light alpha", 1f, light!!.alpha, 0.0001f)
            assertEquals("$it dark alpha", 1f, dark!!.alpha, 0.0001f)
        }
    }

    @Test
    fun `resolveBackground round trips the palette for every preset`() {
        val fallback = Color(0xFF000000)
        ChatWallpaperPreset.entries.forEach {
            assertEquals(
                ChatAppearancePolicy.wallpaperColorLight(it),
                ChatAppearancePolicy.resolveBackground(it, false, fallback),
            )
            assertEquals(
                ChatAppearancePolicy.wallpaperColorDark(it),
                ChatAppearancePolicy.resolveBackground(it, true, fallback),
            )
        }
    }

    @Test
    fun `normalize font scale unknown and blank ids fall back to normal`() {
        assertEquals(ChatFontScale.NORMAL, ChatAppearancePolicy.normalizeFontScale(""))
        assertEquals(ChatFontScale.NORMAL, ChatAppearancePolicy.normalizeFontScale("   "))
        assertEquals(ChatFontScale.NORMAL, ChatAppearancePolicy.normalizeFontScale("x-large"))
        assertEquals(ChatFontScale.SMALL, ChatAppearancePolicy.normalizeFontScale("SMALL"))
        assertEquals(ChatFontScale.XXLARGE, ChatAppearancePolicy.normalizeFontScale("xxlarge"))
    }

    @Test
    fun `preset and scale counts are pinned`() {
        // 增删预设/档位会影响存量用户配置与上面几条，变动必须显式
        assertEquals(16, ChatWallpaperPreset.entries.size)
        assertEquals(5, ChatFontScale.entries.size)
    }

    @Test
    fun `night preset is darker than default in both themes`() {
        val nightLight = ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.NIGHT)!!
        val defaultLight = ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.DEFAULT)!!
        assertTrue(nightLight.red < defaultLight.red && nightLight.green < defaultLight.green && nightLight.blue < defaultLight.blue)
        val nightDark = ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.NIGHT)!!
        val defaultDark = ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.DEFAULT)!!
        assertTrue(nightDark.red < defaultDark.red && nightDark.green < defaultDark.green && nightDark.blue < defaultDark.blue)
    }

    @Test
    fun `normalize wallpaper unknown and case variants`() {
        assertEquals(ChatWallpaperPreset.DEFAULT, ChatAppearancePolicy.normalizeWallpaper("neon"))
        assertEquals(ChatWallpaperPreset.MINT, ChatAppearancePolicy.normalizeWallpaper("MINT"))
        assertEquals(ChatWallpaperPreset.SKY, ChatAppearancePolicy.normalizeWallpaper(" sky "))
        assertEquals(ChatWallpaperPreset.DEFAULT, ChatAppearancePolicy.normalizeWallpaper("minty"))
    }

    @Test
    fun `normalize font scale trims and lowercases variants`() {
        assertEquals(ChatFontScale.SMALL, ChatAppearancePolicy.normalizeFontScale("small"))
        assertEquals(ChatFontScale.XLARGE, ChatAppearancePolicy.normalizeFontScale(" XLarge "))
        assertEquals(ChatFontScale.NORMAL, ChatAppearancePolicy.normalizeFontScale("xx_large"))
        assertEquals(ChatFontScale.XXLARGE, ChatAppearancePolicy.normalizeFontScale("XXLARGE"))
    }

    @Test
    fun `light and dark palettes share no color`() {
        val lights = ChatWallpaperPreset.entries.map { ChatAppearancePolicy.wallpaperColorLight(it) }.toSet()
        val darks = ChatWallpaperPreset.entries.map { ChatAppearancePolicy.wallpaperColorDark(it) }.toSet()
        assertTrue("light/dark palettes must not overlap", lights.intersect(darks).isEmpty())
    }

    @Test
    fun `spot palette colors are pinned`() {
        assertEquals(Color(0xFFE8F6F1), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.MINT))
        assertEquals(Color(0xFF0B1018), ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.NIGHT))
        assertEquals(Color(0xFFFFEBE6), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.CORAL))
        assertEquals(Color(0xFF141618), ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.GRAPHITE))
    }

    @Test
    fun `normalize wallpaper ids round trip`() {
        ChatWallpaperPreset.entries.forEach {
            assertEquals(it, ChatAppearancePolicy.normalizeWallpaper(it.id))
        }
    }

    @Test
    fun `normalize font scale ids round trip`() {
        ChatFontScale.entries.forEach {
            assertEquals(it, ChatAppearancePolicy.normalizeFontScale(it.id))
        }
    }

    @Test
    fun `spot palette dark colors are pinned`() {
        assertEquals(Color(0xFF14241F), ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.MINT))
        assertEquals(Color(0xFF2A161C), ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.ROSE))
        assertEquals(Color(0xFF101A28), ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.SKY))
        assertEquals(Color(0xFF2A2010), ChatAppearancePolicy.wallpaperColorDark(ChatWallpaperPreset.AMBER))
    }

    @Test
    fun `spot palette light colors are pinned for remaining presets`() {
        assertEquals(Color(0xFFF0ECFA), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.LAVENDER))
        assertEquals(Color(0xFFF7F1E6), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.SAND))
        assertEquals(Color(0xFFFFF0E8), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.PEACH))
        assertEquals(Color(0xFFE4F6F5), ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.TEAL))
    }

    @Test
    fun `palette colors are distinct within each theme`() {
        val lights = ChatWallpaperPreset.entries.map { ChatAppearancePolicy.wallpaperColorLight(it) }
        val darks = ChatWallpaperPreset.entries.map { ChatAppearancePolicy.wallpaperColorDark(it) }
        assertEquals(16, lights.toSet().size)
        assertEquals(16, darks.toSet().size)
    }
}
