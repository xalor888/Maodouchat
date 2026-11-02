package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceModelsTest {

    @Test
    fun `fromId falls back and normalizes`() {
        assertEquals(ChatWallpaperPreset.DEFAULT, ChatWallpaperPreset.fromId(null))
        assertEquals(ChatWallpaperPreset.DEFAULT, ChatWallpaperPreset.fromId("nope"))
        assertEquals(ChatWallpaperPreset.MINT, ChatWallpaperPreset.fromId("  Mint "))
        assertEquals(ChatFontScale.NORMAL, ChatFontScale.fromId("nope"))
        assertEquals(ChatFontScale.LARGE, ChatFontScale.fromId(" LARGE "))
    }

    @Test
    fun `font multipliers increase with size`() {
        val ordered = listOf(
            ChatFontScale.SMALL,
            ChatFontScale.NORMAL,
            ChatFontScale.LARGE,
            ChatFontScale.XLARGE,
            ChatFontScale.XXLARGE,
        )
        val multipliers = ordered.map { it.multiplier }
        assertEquals(multipliers.sorted(), multipliers)
        assertEquals(16, ChatWallpaperPreset.entries.size)
    }
}
