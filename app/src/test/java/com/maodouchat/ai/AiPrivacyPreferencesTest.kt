package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiPrivacyPreferencesTest {
    @Test
    fun `consent keys are isolated between accounts`() {
        val first = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "user-a")
        val second = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "user-b")

        assertEquals("ai_consent_accepted:user-a", first)
        assertNotEquals(first, second)
    }

    @Test
    fun `consent and safety dismissals cannot collide`() {
        val consent = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "user-a")
        val dismissed = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS, "user-a")

        assertNotEquals(consent, dismissed)
    }
}
