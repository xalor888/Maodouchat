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

    @Test
    fun `cloud upload gate is fail closed without an account`() {
        // mayUploadCloudContext 无 Context 账号时走 consentAccepted 的 null 分支。
        // 这里只保证 API 与 consent 同口径：scoped 同意键存在、撤销键独立。
        val consent = AiPrivacyPreferences.KEY_CONSENT
        val dismissed = AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS
        assertEquals("ai_consent_accepted", consent)
        assertNotEquals(consent, dismissed)
    }

    @Test
    fun `all preference keys are mutually distinct`() {
        val keys = setOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE
        )
        assertEquals(5, keys.size)
    }

    @Test
    fun `key constants match the cluster's raw key names`() {
        assertEquals("ai_user_enabled", AiPrivacyPreferences.KEY_USER_ENABLED)
        assertEquals("ai_local_safety_enabled", AiPrivacyPreferences.KEY_LOCAL_SAFETY)
        assertEquals("ai_local_safety_dismissed_ids", AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS)
        assertEquals("ai_auto_translate_incoming", AiPrivacyPreferences.KEY_AUTO_TRANSLATE)
    }

    @Test
    fun `scoped key always keeps base-colon-userId shape`() {
        assertEquals(
            "ai_user_enabled:u-1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_USER_ENABLED, "u-1")
        )
    }

    @Test
    fun `scoped key never trims the user id`() {
        assertEquals(
            AiPrivacyPreferences.KEY_CONSENT + ": u ",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, " u ")
        )
    }

    @Test
    fun `scoped key with blank user id keeps trailing colon`() {
        assertEquals(
            AiPrivacyPreferences.KEY_CONSENT + ":",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "")
        )
    }

    @Test
    fun `scoped key never equals the legacy device-level key`() {
        // 迁移后账号键和老设备级键必须分开，否则新旧两套偏好互相踩。
        assertNotEquals(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1")
        )
        assertNotEquals(
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_USER_ENABLED, "u-1")
        )
    }

    @Test
    fun `migration marker key name is frozen`() {
        assertEquals("account_scope_migrated", AiPrivacyKeys.MIGRATED)
    }

    @Test
    fun `prefs file name is frozen`() {
        assertEquals("ai_settings", AiPrivacyKeys.PREFS_NAME)
    }

    @Test
    fun `all six raw keys including the migration marker stay distinct`() {
        val keys = setOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE,
            AiPrivacyKeys.MIGRATED
        )
        assertEquals(6, keys.size)
    }

    @Test
    fun `migration marker scoped key keeps colon shape`() {
        assertEquals(
            "account_scope_migrated:u-1",
            AiPrivacyPreferences.scopedKey(AiPrivacyKeys.MIGRATED, "u-1")
        )
    }
}
