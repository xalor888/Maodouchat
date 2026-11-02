package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPrivacyKeysTest {

    @Test
    fun `key values are unchanged`() {
        assertEquals("ai_settings", AiPrivacyKeys.PREFS_NAME)
        assertEquals("ai_consent_accepted", AiPrivacyKeys.CONSENT)
        assertEquals("ai_user_enabled", AiPrivacyKeys.USER_ENABLED)
        assertEquals("ai_local_safety_enabled", AiPrivacyKeys.LOCAL_SAFETY)
        assertEquals("ai_local_safety_dismissed_ids", AiPrivacyKeys.DISMISSED_SAFETY_IDS)
        assertEquals("ai_auto_translate_incoming", AiPrivacyKeys.AUTO_TRANSLATE)
        assertEquals("account_scope_migrated", AiPrivacyKeys.MIGRATED)
    }

    @Test
    fun `scoped key appends user id`() {
        assertEquals("ai_consent_accepted:u-1", AiPrivacyKeys.scopedKey(AiPrivacyKeys.CONSENT, "u-1"))
        assertEquals("ai_settings:u-2", AiPrivacyKeys.scopedKey(AiPrivacyKeys.PREFS_NAME, "u-2"))
    }

    @Test
    fun `scoped keys differ per account`() {
        val first = AiPrivacyKeys.scopedKey(AiPrivacyKeys.CONSENT, "alice")
        val second = AiPrivacyKeys.scopedKey(AiPrivacyKeys.CONSENT, "bob")
        assertTrue(first != second)
        assertTrue(first.startsWith(AiPrivacyKeys.CONSENT))
    }

    @Test
    fun `migrated marker is not ai prefixed`() {
        assertFalse(AiPrivacyKeys.MIGRATED.startsWith("ai_"))
    }
}
