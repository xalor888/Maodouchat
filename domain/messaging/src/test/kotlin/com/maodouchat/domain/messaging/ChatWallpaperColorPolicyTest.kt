package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChatWallpaperColorPolicyTest {

    @Test
    fun `default argb values pinned`() {
        assertEquals(0xFFDBE7F3L, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.DEFAULT))
        assertEquals(0xFF0E1621L, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.DEFAULT))
    }

    @Test
    fun `spot light colors pinned`() {
        assertEquals(0xFFE8F6F1L, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.MINT))
        assertEquals(0xFFF0ECFAL, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.LAVENDER))
        assertEquals(0xFFF7F1E6L, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.SAND))
        assertEquals(0xFFFFF0E8L, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.PEACH))
        assertEquals(0xFFFFEBE6L, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.CORAL))
        assertEquals(0xFFE4F6F5L, ChatWallpaperColorPolicy.wallpaperArgbLight(ChatWallpaperPreset.TEAL))
    }

    @Test
    fun `spot dark colors pinned`() {
        assertEquals(0xFF14241FL, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.MINT))
        assertEquals(0xFF0B1018L, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.NIGHT))
        assertEquals(0xFF2A161CL, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.ROSE))
        assertEquals(0xFF101A28L, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.SKY))
        assertEquals(0xFF2A2010L, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.AMBER))
        assertEquals(0xFF141618L, ChatWallpaperColorPolicy.wallpaperArgbDark(ChatWallpaperPreset.GRAPHITE))
    }

    @Test
    fun `every preset has distinct light and dark argb`() {
        for (preset in ChatWallpaperPreset.entries) {
            assertNotEquals(
                ChatWallpaperColorPolicy.wallpaperArgbLight(preset),
                ChatWallpaperColorPolicy.wallpaperArgbDark(preset),
                "preset $preset",
            )
        }
    }

    @Test
    fun `light and dark palettes share no color`() {
        val light = ChatWallpaperPreset.entries.map { ChatWallpaperColorPolicy.wallpaperArgbLight(it) }.toSet()
        val dark = ChatWallpaperPreset.entries.map { ChatWallpaperColorPolicy.wallpaperArgbDark(it) }.toSet()
        assertTrue(light.intersect(dark).isEmpty())
    }

    @Test
    fun `palette colors are distinct within each theme`() {
        val light = ChatWallpaperPreset.entries.map { ChatWallpaperColorPolicy.wallpaperArgbLight(it) }
        val dark = ChatWallpaperPreset.entries.map { ChatWallpaperColorPolicy.wallpaperArgbDark(it) }
        assertEquals(16, light.toSet().size)
        assertEquals(16, dark.toSet().size)
    }

    @Test
    fun `every argb is fully opaque`() {
        for (preset in ChatWallpaperPreset.entries) {
            assertEquals(0xFFL, ChatWallpaperColorPolicy.wallpaperArgbLight(preset) shr 24, "light $preset")
            assertEquals(0xFFL, ChatWallpaperColorPolicy.wallpaperArgbDark(preset) shr 24, "dark $preset")
        }
    }

    @Test
    fun `resolveBackgroundArgb honors theme`() {
        val fallback = 0xFF000000L
        assertEquals(
            0xFFDBE7F3L,
            ChatWallpaperColorPolicy.resolveBackgroundArgb(ChatWallpaperPreset.DEFAULT, false, fallback),
        )
        assertEquals(
            0xFF0E1621L,
            ChatWallpaperColorPolicy.resolveBackgroundArgb(ChatWallpaperPreset.DEFAULT, true, fallback),
        )
    }

    @Test
    fun `resolveBackgroundArgb round trips the palette for every preset`() {
        for (preset in ChatWallpaperPreset.entries) {
            assertEquals(
                ChatWallpaperColorPolicy.wallpaperArgbLight(preset),
                ChatWallpaperColorPolicy.resolveBackgroundArgb(preset, false, 0xFF000000L),
            )
            assertEquals(
                ChatWallpaperColorPolicy.wallpaperArgbDark(preset),
                ChatWallpaperColorPolicy.resolveBackgroundArgb(preset, true, 0xFF000000L),
            )
        }
    }

    @Test
    fun `night preset is darker than default in both themes`() {
        for (isDark in listOf(false, true)) {
            val night = ChatWallpaperColorPolicy.resolveBackgroundArgb(ChatWallpaperPreset.NIGHT, isDark, 0xFF000000L)
            val default = ChatWallpaperColorPolicy.resolveBackgroundArgb(ChatWallpaperPreset.DEFAULT, isDark, 0xFF000000L)
            assertTrue(channel(night, 16) < channel(default, 16), "red isDark=$isDark")
            assertTrue(channel(night, 8) < channel(default, 8), "green isDark=$isDark")
            assertTrue(channel(night, 0) < channel(default, 0), "blue isDark=$isDark")
        }
    }

    private fun channel(argb: Long, shift: Int): Long = (argb shr shift) and 0xFFL
}
