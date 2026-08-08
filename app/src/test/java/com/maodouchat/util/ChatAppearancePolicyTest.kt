package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `default wallpaper has no override color`() {
        assertNull(ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.DEFAULT))
        assertNotNull(ChatAppearancePolicy.wallpaperColorLight(ChatWallpaperPreset.ROSE))
    }

    @Test
    fun `font multipliers ordered`() {
        assertEquals(true, ChatFontScale.SMALL.multiplier < ChatFontScale.NORMAL.multiplier)
        assertEquals(true, ChatFontScale.NORMAL.multiplier < ChatFontScale.LARGE.multiplier)
        assertEquals(true, ChatFontScale.LARGE.multiplier < ChatFontScale.XLARGE.multiplier)
    }
}
