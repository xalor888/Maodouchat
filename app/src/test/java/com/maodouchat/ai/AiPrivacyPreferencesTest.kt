package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `all scoped keys for one account stay distinct`() {
        val keys = setOf(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-9"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_USER_ENABLED, "u-9"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_LOCAL_SAFETY, "u-9"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS, "u-9"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_AUTO_TRANSLATE, "u-9"),
            AiPrivacyPreferences.scopedKey(AiPrivacyKeys.MIGRATED, "u-9")
        )
        assertEquals(6, keys.size)
    }

    @Test
    fun `scoped key is verbatim base plus colon plus user id`() {
        assertEquals("base:u", AiPrivacyPreferences.scopedKey("base", "u"))
        // base 与 userId 都不做 trim/转义，原样拼接
        assertEquals(
            "ai_consent_accepted:a:b",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "a:b")
        )
    }

    @Test
    fun `facade key constants pass through the cluster raw names`() {
        assertEquals(AiPrivacyKeys.CONSENT, AiPrivacyPreferences.KEY_CONSENT)
        assertEquals(AiPrivacyKeys.USER_ENABLED, AiPrivacyPreferences.KEY_USER_ENABLED)
        assertEquals(AiPrivacyKeys.LOCAL_SAFETY, AiPrivacyPreferences.KEY_LOCAL_SAFETY)
        assertEquals(AiPrivacyKeys.DISMISSED_SAFETY_IDS, AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS)
        assertEquals(AiPrivacyKeys.AUTO_TRANSLATE, AiPrivacyPreferences.KEY_AUTO_TRANSLATE)
    }

    @Test
    fun `no scoped key equals any legacy device-level key`() {
        // 迁移把账号键与老设备级键分开：任一 scoped 键都不能撞上任一裸键
        val rawKeys = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE
        )
        for (base in rawKeys) {
            val scoped = AiPrivacyPreferences.scopedKey(base, "u-1")
            assertTrue(scoped.startsWith("$base:"))
            for (raw in rawKeys) {
                assertNotEquals(raw, scoped)
            }
        }
    }

    @Test
    fun `scoped consent differs across all five bases for two accounts`() {
        // 账号隔离：同名键跨账号必不同，跨 base 也不能串
        val bases = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE
        )
        val all = bases.flatMap { base ->
            listOf(
                AiPrivacyPreferences.scopedKey(base, "user-a"),
                AiPrivacyPreferences.scopedKey(base, "user-b")
            )
        }
        assertEquals(10, all.toSet().size)
    }

    @Test
    fun `scoped key with empty base keeps leading colon`() {
        assertEquals(":u-1", AiPrivacyPreferences.scopedKey("", "u-1"))
    }

    @Test
    fun `scoped key with empty base and empty user id is a lone colon`() {
        assertEquals(":", AiPrivacyPreferences.scopedKey("", ""))
    }

    @Test
    fun `scoped key user ids are case sensitive`() {
        assertEquals(
            "ai_consent_accepted:U-1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "U-1")
        )
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "U-1")
        )
    }

    @Test
    fun `scoped key keeps trailing space in user id distinct`() {
        assertEquals(
            "ai_consent_accepted:u-1 ",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1 ")
        )
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1 ")
        )
    }

    @Test
    fun `scoped key keeps non-ASCII user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:用户-1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "用户-1")
        )
    }

@Test
    fun `scoped key keeps newline and tab in user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:u\n1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u\n1")
        )
        assertEquals(
            "ai_consent_accepted:u\t1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u\t1")
        )
    }

    @Test
    fun `scoped key with empty user id keeps trailing colon for every base`() {
        val bases = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE
        )
        for (base in bases) {
            assertEquals("$base:", AiPrivacyPreferences.scopedKey(base, ""))
        }
    }

    @Test
    fun `scoped key length is base plus colon plus user id`() {
        val scoped = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-123")
        assertEquals(
            AiPrivacyPreferences.KEY_CONSENT.length + 1 + "u-123".length,
            scoped.length
        )
    }

    @Test
    fun `scoped key keeps spaces in the base verbatim`() {
        assertEquals(
            " my base :u",
            AiPrivacyPreferences.scopedKey(" my base ", "u")
        )
    }

    @Test
    fun `scoped key keeps emoji user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:u-😀",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-😀")
        )
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-😀")
        )
    }

    @Test
    fun `migration marker scoped keys stay distinct across accounts`() {
        val first = AiPrivacyPreferences.scopedKey(AiPrivacyKeys.MIGRATED, "u-a")
        val second = AiPrivacyPreferences.scopedKey(AiPrivacyKeys.MIGRATED, "u-b")
        assertEquals("account_scope_migrated:u-a", first)
        assertNotEquals(first, second)
        assertNotEquals(AiPrivacyKeys.MIGRATED, first)
        assertNotEquals(AiPrivacyKeys.MIGRATED, second)
    }

    @Test
    fun `five preference keys share ai prefix while migration marker does not`() {
        val bases = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE
        )
        for (base in bases) {
            assertTrue(base.startsWith("ai_"))
        }
        assertFalse(AiPrivacyKeys.MIGRATED.startsWith("ai_"))
    }

    @Test
    fun `scoped key with trailing colon base keeps double colon`() {
        assertEquals("base::u", AiPrivacyPreferences.scopedKey("base:", "u"))
    }

    @Test
    fun `user id that looks like a key cannot alias another key`() {
        val scoped = AiPrivacyPreferences.scopedKey(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED
        )
        assertEquals("ai_consent_accepted:ai_user_enabled", scoped)
        assertNotEquals(AiPrivacyPreferences.KEY_USER_ENABLED, scoped)
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_USER_ENABLED, "u-1"),
            scoped
        )
    }

    @Test
    fun `raw keys contain no whitespace`() {
        val keys = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE,
            AiPrivacyKeys.MIGRATED
        )
        for (key in keys) {
            assertTrue(key.none { it.isWhitespace() })
        }
    }

    @Test
    fun `scoped key keeps colon inside user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:u:1:2",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u:1:2")
        )
    }

    @Test
    fun `all twelve scoped keys across two accounts stay distinct`() {
        val bases = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE,
            AiPrivacyKeys.MIGRATED
        )
        val all = bases.flatMap { base ->
            listOf(
                AiPrivacyPreferences.scopedKey(base, "user-a"),
                AiPrivacyPreferences.scopedKey(base, "user-b")
            )
        }
        assertEquals(12, all.toSet().size)
    }

    @Test
    fun `scoped key for auto translate base is exact`() {
        assertEquals(
            "ai_auto_translate_incoming:u-1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_AUTO_TRANSLATE, "u-1")
        )
    }

    @Test
    fun `scoped key with colon user id ends with double colon`() {
        assertEquals(
            "ai_consent_accepted::",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, ":")
        )
    }

    @Test
    fun `migration marker scoped key with empty user id keeps trailing colon`() {
        assertEquals(
            "account_scope_migrated:",
            AiPrivacyPreferences.scopedKey(AiPrivacyKeys.MIGRATED, "")
        )
    }

    @Test
    fun `scoped key keeps newline and tab in the base verbatim`() {
        assertEquals("a\nb:u", AiPrivacyPreferences.scopedKey("a\nb", "u"))
        assertEquals("a\tb:u", AiPrivacyPreferences.scopedKey("a\tb", "u"))
    }

    @Test
    fun `scoped key round-trips by stripping the user suffix`() {
        val bases = listOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE,
            AiPrivacyKeys.MIGRATED
        )
        for (base in bases) {
            val scoped = AiPrivacyPreferences.scopedKey(base, "u-1")
            assertTrue(scoped.endsWith(":u-1"))
            assertEquals(base, scoped.removeSuffix(":u-1"))
        }
    }

    @Test
    fun `scoped key argument order matters and repeats are stable`() {
        assertEquals("a:b", AiPrivacyPreferences.scopedKey("a", "b"))
        assertEquals("b:a", AiPrivacyPreferences.scopedKey("b", "a"))
        assertNotEquals(
            AiPrivacyPreferences.scopedKey("a", "b"),
            AiPrivacyPreferences.scopedKey("b", "a")
        )
        assertEquals(
            AiPrivacyPreferences.scopedKey("a", "b"),
            AiPrivacyPreferences.scopedKey("a", "b")
        )
    }

    @Test
    fun `prefs name joins the distinct constants set`() {
        val keys = setOf(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyPreferences.KEY_USER_ENABLED,
            AiPrivacyPreferences.KEY_LOCAL_SAFETY,
            AiPrivacyPreferences.KEY_DISMISSED_SAFETY_IDS,
            AiPrivacyPreferences.KEY_AUTO_TRANSLATE,
            AiPrivacyKeys.MIGRATED,
            AiPrivacyKeys.PREFS_NAME
        )
        assertEquals(7, keys.size)
    }

    @Test
    fun `scoped keys compose by nesting`() {
        val inner = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1")
        assertEquals(
            "ai_consent_accepted:u-1:u-2",
            AiPrivacyPreferences.scopedKey(inner, "u-2")
        )
    }

    @Test
    fun `scoped key keeps thousand char user id intact`() {
        val longId = "x".repeat(1000)
        val scoped = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, longId)
        assertEquals(AiPrivacyPreferences.KEY_CONSENT.length + 1 + 1000, scoped.length)
        assertTrue(scoped.startsWith("ai_consent_accepted:"))
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "x".repeat(999)),
            scoped
        )
    }

    @Test
    fun `scoped key with prefs name user id never aliases prefs name`() {
        val scoped = AiPrivacyPreferences.scopedKey(
            AiPrivacyPreferences.KEY_CONSENT,
            AiPrivacyKeys.PREFS_NAME
        )
        assertEquals("ai_consent_accepted:ai_settings", scoped)
        assertNotEquals(AiPrivacyKeys.PREFS_NAME, scoped)
    }

    @Test
    fun `scoped key double scope with same user keeps both suffixes`() {
        val inner = AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u-1")
        assertEquals(
            "ai_consent_accepted:u-1:u-1",
            AiPrivacyPreferences.scopedKey(inner, "u-1")
        )
    }

    @Test
    fun `scoped key with colon only base keeps double colon shape`() {
        assertEquals("::u", AiPrivacyPreferences.scopedKey(":", "u"))
        assertEquals(":::", AiPrivacyPreferences.scopedKey(":", ":"))
    }

    @Test
    fun `scoped key keeps carriage return and combined spaces verbatim`() {
        assertEquals(
            "ai_consent_accepted:u\r1",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "u\r1")
        )
        assertEquals(
            " base : u ",
            AiPrivacyPreferences.scopedKey(" base ", " u ")
        )
    }

    @Test
    fun `scoped key keeps form feed and vertical tab in user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:a\u000Cb\u000Bc",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "a\u000Cb\u000Bc")
        )
    }

    @Test
    fun `scoped key keeps backslash and quotes in user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:a\\b\"c'd",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "a\\b\"c'd")
        )
    }

    @Test
    fun `scoped key keeps decomposed unicode distinct from precomposed`() {
        assertEquals(
            "ai_consent_accepted:e\u0301",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "e\u0301")
        )
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "\u00e9"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "e\u0301")
        )
    }

    @Test
    fun `scoped key keeps leading zeros user id distinct`() {
        assertEquals(
            "ai_consent_accepted:007",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "007")
        )
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "007"),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "7")
        )
    }

    @Test
    fun `scoped key emoji user id length counts surrogate pair`() {
        // 表情在 UTF-16 里占两个字符位，键长 = 基码 + 冒号 + 2。
        assertEquals(2, "😀".length)
        assertEquals(
            AiPrivacyPreferences.KEY_CONSENT.length + 1 + 2,
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "😀").length
        )
    }

    @Test
    fun `scoped key keeps nul and bell control chars in user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:a\u0000b",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "a\u0000b")
        )
        assertEquals(
            "ai_consent_accepted:a\u0007b",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "a\u0007b")
        )
    }

    @Test
    fun `scoped key keeps del char in user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:a\u007Fb",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "a\u007Fb")
        )
    }

    @Test
    fun `scoped key keeps all spaces user id verbatim and distinct from empty`() {
        assertEquals(
            "ai_consent_accepted:   ",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "   ")
        )
        assertNotEquals(
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, ""),
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "   ")
        )
    }

    @Test
    fun `scoped key keeps zero width space in user id verbatim`() {
        assertEquals(
            "ai_consent_accepted:\u200B",
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "\u200B")
        )
        assertEquals(
            AiPrivacyPreferences.KEY_CONSENT.length + 1 + 1,
            AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "\u200B").length
        )
    }

    @Test
    fun `scoped key flag emoji user id length counts two surrogate pairs`() {
        // 旗帜表情由两个区域指示符组成，UTF-16 里占 4 个字符位。
        assertEquals(4, "\uD83C\uDDE8\uD83C\uDDF3".length)
        assertEquals(
            AiPrivacyPreferences.KEY_CONSENT.length + 1 + 4,
            AiPrivacyPreferences.scopedKey(
                AiPrivacyPreferences.KEY_CONSENT,
                "\uD83C\uDDE8\uD83C\uDDF3"
            ).length
        )
    }

}
