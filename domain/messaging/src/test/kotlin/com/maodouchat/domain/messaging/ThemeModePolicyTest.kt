package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModePolicyTest {

    @Test
    fun nullReturnsSystem() {
        assertEquals("system", ThemeModePolicy.normalizeThemeMode(null))
    }

    @Test
    fun blankReturnsSystem() {
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("   "))
    }

    @Test
    fun lightIsCaseInsensitiveAndTrimmed() {
        assertEquals("light", ThemeModePolicy.normalizeThemeMode(" LIGHT "))
    }

    @Test
    fun darkPassesThrough() {
        assertEquals("dark", ThemeModePolicy.normalizeThemeMode("dark"))
    }

    @Test
    fun scheduledPassesThrough() {
        assertEquals("scheduled", ThemeModePolicy.normalizeThemeMode("scheduled"))
    }

    @Test
    fun unknownReturnsSystem() {
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("amoled"))
    }
}
