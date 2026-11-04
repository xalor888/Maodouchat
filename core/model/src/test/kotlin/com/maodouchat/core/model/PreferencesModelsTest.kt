package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesModelsTest {

    @Test
    fun `multi device defaults match previous app side values`() {
        val prefs = MultiDevicePreferences()
        assertEquals("", prefs.ownerUserId)
        assertEquals(0L, prefs.revision)
        assertEquals("system", prefs.themeMode)
        assertEquals("maodou", prefs.themeStyle)
        assertEquals(true, prefs.linkPreviewEnabled)
        assertEquals(5L, prefs.appLockTimeoutMinutes)
    }

    @Test
    fun `to patch carries all fields`() {
        val patch = MultiDevicePreferences(themeMode = "dark", accentColor = "blue").toPatch()
        assertEquals("dark", patch.themeMode)
        assertEquals("blue", patch.accentColor)
        assertEquals("system", patch.languageMode)
    }

    @Test
    fun `empty patch reports empty and apply overrides only set fields`() {
        assertTrue(PreferencesPatch().isEmpty)
        assertFalse(PreferencesPatch(themeMode = "dark").isEmpty)
        val applied = PreferencesPatch(themeMode = "dark").applyTo(
            MultiDevicePreferences(),
            newRevision = 3L,
            timestampMs = 99L,
        )
        assertEquals("dark", applied.themeMode)
        assertEquals(3L, applied.revision)
        assertEquals(99L, applied.updatedAtEpochMs)
        assertEquals("maodou", applied.themeStyle)
    }

    @Test
    fun `local device defaults and scope values`() {
        val local = LocalDevicePreferences()
        assertTrue(local.notificationsEnabled)
        assertEquals(512L, local.localCacheMaxMegaBytes)
        assertEquals(2, PreferenceScope.values().size)
    }
}
