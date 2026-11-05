package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {

    @Test
    fun `security patch is empty only when all fields are null`() {
        assertTrue(SecurityPreferencesPatch().isEmpty)
        assertFalse(SecurityPreferencesPatch(appLockTimeoutMinutes = 5L).isEmpty)
        assertFalse(SecurityPreferencesPatch(screenSecureEnabled = true).isEmpty)
        assertFalse(SecurityPreferencesPatch(sensitiveGateEnabled = false).isEmpty)
    }

    @Test
    fun `privacy patch defaults to all null`() {
        val patch = SettingsPrivacyPatch()
        assertEquals(null, patch.showOnline)
        assertEquals(null, patch.showStatus)
        assertEquals(null, patch.searchable)
        assertEquals(null, patch.defaultPostVisibility)
        assertEquals(null, patch.onlineVisibility)
    }

    @Test
    fun `settings session and profile hold their values`() {
        assertEquals("owner", SettingsSession("owner").ownerUserId)
        val profile = SettingsProfile("id", "name", null, "status", "user", true)
        assertEquals("id", profile.id)
        assertEquals("name", profile.name)
        assertEquals(null, profile.avatar)
        assertTrue(profile.isModerator)
    }

    @Test
    fun `settings privacy holds its flags`() {
        val privacy = SettingsPrivacy(true, false, true, "public", "contacts")
        assertTrue(privacy.showOnline)
        assertFalse(privacy.showStatus)
        assertTrue(privacy.searchable)
        assertEquals("public", privacy.defaultPostVisibility)
        assertEquals("contacts", privacy.onlineVisibility)
    }

    @Test
    fun `security preferences hold their values`() {
        val prefs = SecurityPreferences(30L, true, false)
        assertEquals(30L, prefs.appLockTimeoutMinutes)
        assertTrue(prefs.screenSecureEnabled)
        assertFalse(prefs.sensitiveGateEnabled)
    }
}
