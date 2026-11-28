package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- oracle ----------

private val THEME_MODES189 = listOf("system", "light", "dark", "")
private val THEME_STYLES189 = listOf("maodou", "plain", "")
private val ACCENTS189 = listOf("none", "red", "blue", "")
private val LANG_MODES189 = listOf("system", "en", "zh", "")
private val WALLPAPERS189 = listOf("default", "dark", "")
private val FONT_SCALES189 = listOf("normal", "large", "small", "")
private val PRESETS189 = listOf("none", "formal", "casual", "")
private val CUSTOMS189 = listOf("", "custom", "x".repeat(100))
private val TIMEOUTS189 = listOf(0L, 1L, 5L, 30L, -1L, 60L, Long.MAX_VALUE, Long.MIN_VALUE)
private val REVISIONS189 = listOf(0L, 1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE)
private val TIMESTAMPS189 = listOf(0L, 1L, 1700000000000L, Long.MAX_VALUE, Long.MIN_VALUE)

private fun randomPrefs189(r: Random): MultiDevicePreferences = MultiDevicePreferences(
    ownerUserId = listOf("", "u1", "user-42").random(r),
    revision = REVISIONS189.random(r),
    updatedAtEpochMs = TIMESTAMPS189.random(r),
    themeMode = THEME_MODES189.random(r),
    themeStyle = THEME_STYLES189.random(r),
    accentColor = ACCENTS189.random(r),
    languageMode = LANG_MODES189.random(r),
    chatWallpaper = WALLPAPERS189.random(r),
    chatFontScale = FONT_SCALES189.random(r),
    linkPreviewEnabled = r.nextBoolean(),
    unreadPriorityEnabled = r.nextBoolean(),
    writingStyleEnabled = r.nextBoolean(),
    writingStylePreset = PRESETS189.random(r),
    writingStyleCustom = CUSTOMS189.random(r),
    appLockTimeoutMinutes = TIMEOUTS189.random(r),
    screenSecureEnabled = r.nextBoolean(),
    sensitiveGateEnabled = r.nextBoolean()
)

private fun randomPatch189(r: Random): PreferencesPatch {
    fun <T> maybe(v: T): T? = if (r.nextBoolean()) null else v
    return PreferencesPatch(
        themeMode = maybe(THEME_MODES189.random(r)),
        themeStyle = maybe(THEME_STYLES189.random(r)),
        accentColor = maybe(ACCENTS189.random(r)),
        languageMode = maybe(LANG_MODES189.random(r)),
        chatWallpaper = maybe(WALLPAPERS189.random(r)),
        chatFontScale = maybe(FONT_SCALES189.random(r)),
        linkPreviewEnabled = maybe(r.nextBoolean()),
        unreadPriorityEnabled = maybe(r.nextBoolean()),
        writingStyleEnabled = maybe(r.nextBoolean()),
        writingStylePreset = maybe(PRESETS189.random(r)),
        writingStyleCustom = maybe(CUSTOMS189.random(r)),
        appLockTimeoutMinutes = maybe(TIMEOUTS189.random(r)),
        screenSecureEnabled = maybe(r.nextBoolean()),
        sensitiveGateEnabled = maybe(r.nextBoolean())
    )
}

private fun toPatchOracle189(p: MultiDevicePreferences): PreferencesPatch = PreferencesPatch(
    themeMode = p.themeMode,
    themeStyle = p.themeStyle,
    accentColor = p.accentColor,
    languageMode = p.languageMode,
    chatWallpaper = p.chatWallpaper,
    chatFontScale = p.chatFontScale,
    linkPreviewEnabled = p.linkPreviewEnabled,
    unreadPriorityEnabled = p.unreadPriorityEnabled,
    writingStyleEnabled = p.writingStyleEnabled,
    writingStylePreset = p.writingStylePreset,
    writingStyleCustom = p.writingStyleCustom,
    appLockTimeoutMinutes = p.appLockTimeoutMinutes,
    screenSecureEnabled = p.screenSecureEnabled,
    sensitiveGateEnabled = p.sensitiveGateEnabled
)

private fun applyToOracle189(
    patch: PreferencesPatch,
    base: MultiDevicePreferences,
    newRevision: Long,
    timestampMs: Long
): MultiDevicePreferences = base.copy(
    revision = newRevision,
    updatedAtEpochMs = timestampMs,
    themeMode = patch.themeMode ?: base.themeMode,
    themeStyle = patch.themeStyle ?: base.themeStyle,
    accentColor = patch.accentColor ?: base.accentColor,
    languageMode = patch.languageMode ?: base.languageMode,
    chatWallpaper = patch.chatWallpaper ?: base.chatWallpaper,
    chatFontScale = patch.chatFontScale ?: base.chatFontScale,
    linkPreviewEnabled = patch.linkPreviewEnabled ?: base.linkPreviewEnabled,
    unreadPriorityEnabled = patch.unreadPriorityEnabled ?: base.unreadPriorityEnabled,
    writingStyleEnabled = patch.writingStyleEnabled ?: base.writingStyleEnabled,
    writingStylePreset = patch.writingStylePreset ?: base.writingStylePreset,
    writingStyleCustom = patch.writingStyleCustom ?: base.writingStyleCustom,
    appLockTimeoutMinutes = patch.appLockTimeoutMinutes ?: base.appLockTimeoutMinutes,
    screenSecureEnabled = patch.screenSecureEnabled ?: base.screenSecureEnabled,
    sensitiveGateEnabled = patch.sensitiveGateEnabled ?: base.sensitiveGateEnabled
)

private fun isEmptyOracle189(patch: PreferencesPatch): Boolean =
    patch.themeMode == null &&
        patch.themeStyle == null &&
        patch.accentColor == null &&
        patch.languageMode == null &&
        patch.chatWallpaper == null &&
        patch.chatFontScale == null &&
        patch.linkPreviewEnabled == null &&
        patch.unreadPriorityEnabled == null &&
        patch.writingStyleEnabled == null &&
        patch.writingStylePreset == null &&
        patch.writingStyleCustom == null &&
        patch.appLockTimeoutMinutes == null &&
        patch.screenSecureEnabled == null &&
        patch.sensitiveGateEnabled == null

// ---------- tests ----------

class PreferencesPatchFuzzTest {

    @Test
    fun toPatchDifferential189() {
        val r = Random(189001)
        repeat(200) {
            val p = randomPrefs189(r)
            assertEquals(toPatchOracle189(p), p.toPatch())
        }
    }

    @Test
    fun applyToDifferential189() {
        val r = Random(189002)
        repeat(200) {
            val base = randomPrefs189(r)
            val patch = randomPatch189(r)
            val rev = REVISIONS189.random(r)
            val ts = TIMESTAMPS189.random(r)
            assertEquals(applyToOracle189(patch, base, rev, ts), patch.applyTo(base, rev, ts))
            assertEquals(isEmptyOracle189(patch), patch.isEmpty)
        }
    }

    @Test
    fun preferencesPatchPinned189() {
        val defaults = MultiDevicePreferences()
        assertEquals(toPatchOracle189(defaults), defaults.toPatch())
        val empty = PreferencesPatch()
        assertTrue(empty.isEmpty)
        assertEquals(
            defaults.copy(revision = 9L, updatedAtEpochMs = 123L),
            empty.applyTo(defaults, 9L, 123L)
        )
        val full = PreferencesPatch(
            themeMode = "dark", themeStyle = "plain", accentColor = "red",
            languageMode = "en", chatWallpaper = "dark", chatFontScale = "large",
            linkPreviewEnabled = false, unreadPriorityEnabled = false,
            writingStyleEnabled = true, writingStylePreset = "formal",
            writingStyleCustom = "custom", appLockTimeoutMinutes = 30L,
            screenSecureEnabled = true, sensitiveGateEnabled = false
        )
        assertFalse(full.isEmpty)
        assertEquals(
            MultiDevicePreferences(
                ownerUserId = defaults.ownerUserId, revision = 7L, updatedAtEpochMs = 456L,
                themeMode = "dark", themeStyle = "plain", accentColor = "red",
                languageMode = "en", chatWallpaper = "dark", chatFontScale = "large",
                linkPreviewEnabled = false, unreadPriorityEnabled = false,
                writingStyleEnabled = true, writingStylePreset = "formal",
                writingStyleCustom = "custom", appLockTimeoutMinutes = 30L,
                screenSecureEnabled = true, sensitiveGateEnabled = false
            ),
            full.applyTo(defaults, 7L, 456L)
        )
        val one = PreferencesPatch(themeMode = "light")
        assertFalse(one.isEmpty)
        assertEquals("light", one.applyTo(defaults, 1L, 1L).themeMode)
        assertEquals(defaults.accentColor, one.applyTo(defaults, 1L, 1L).accentColor)
        val extreme = MultiDevicePreferences(
            appLockTimeoutMinutes = Long.MIN_VALUE, revision = Long.MAX_VALUE
        )
        assertEquals(Long.MIN_VALUE, extreme.toPatch().appLockTimeoutMinutes)
    }
}
