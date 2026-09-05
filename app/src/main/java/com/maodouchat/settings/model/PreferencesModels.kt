package com.maodouchat.settings.model

/**
 * Defines whether a preference item roams across user's devices via cloud sync
 * or is bound strictly to the local hardware/sandbox environment.
 */
enum class PreferenceScope {
    /**
     * Roaming preferences synchronized across multiple devices for the same user account.
     * (e.g. Theme, Language, Chat Wallpaper, Font Scaling, Link Previews, App Lock Timeout).
     */
    MULTI_DEVICE_ROAMING,

    /**
     * Strictly local device-bound preferences that never leave the device.
     * (e.g. Hardware notification channels/vibration, Biometric authentication enrollment,
     * Local storage cache caps, Decoy launcher shortcuts, Active Server Host).
     */
    LOCAL_DEVICE_BOUND
}

/**
 * Multi-device roaming preferences payload with revision and owner tracking.
 */
data class MultiDevicePreferences(
    val ownerUserId: String = "",
    val revision: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val themeMode: String = "system",
    val themeStyle: String = "maodou",
    val accentColor: String = "none",
    val languageMode: String = "system",
    val chatWallpaper: String = "default",
    val chatFontScale: String = "normal",
    val linkPreviewEnabled: Boolean = true,
    val unreadPriorityEnabled: Boolean = true,
    val writingStyleEnabled: Boolean = false,
    val writingStylePreset: String = "none",
    val writingStyleCustom: String = "",
    val appLockTimeoutMinutes: Long = 5L,
    val screenSecureEnabled: Boolean = false,
    val sensitiveGateEnabled: Boolean = true,
) {
    fun toPatch(): PreferencesPatch = PreferencesPatch(
        themeMode = themeMode,
        themeStyle = themeStyle,
        accentColor = accentColor,
        languageMode = languageMode,
        chatWallpaper = chatWallpaper,
        chatFontScale = chatFontScale,
        linkPreviewEnabled = linkPreviewEnabled,
        unreadPriorityEnabled = unreadPriorityEnabled,
        writingStyleEnabled = writingStyleEnabled,
        writingStylePreset = writingStylePreset,
        writingStyleCustom = writingStyleCustom,
        appLockTimeoutMinutes = appLockTimeoutMinutes,
        screenSecureEnabled = screenSecureEnabled,
        sensitiveGateEnabled = sensitiveGateEnabled,
    )
}

/**
 * Patch for updating roaming preferences. Null fields indicate unchanged values.
 */
data class PreferencesPatch(
    val themeMode: String? = null,
    val themeStyle: String? = null,
    val accentColor: String? = null,
    val languageMode: String? = null,
    val chatWallpaper: String? = null,
    val chatFontScale: String? = null,
    val linkPreviewEnabled: Boolean? = null,
    val unreadPriorityEnabled: Boolean? = null,
    val writingStyleEnabled: Boolean? = null,
    val writingStylePreset: String? = null,
    val writingStyleCustom: String? = null,
    val appLockTimeoutMinutes: Long? = null,
    val screenSecureEnabled: Boolean? = null,
    val sensitiveGateEnabled: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = themeMode == null &&
            themeStyle == null &&
            accentColor == null &&
            languageMode == null &&
            chatWallpaper == null &&
            chatFontScale == null &&
            linkPreviewEnabled == null &&
            unreadPriorityEnabled == null &&
            writingStyleEnabled == null &&
            writingStylePreset == null &&
            writingStyleCustom == null &&
            appLockTimeoutMinutes == null &&
            screenSecureEnabled == null &&
            sensitiveGateEnabled == null

    fun applyTo(base: MultiDevicePreferences, newRevision: Long, timestampMs: Long): MultiDevicePreferences {
        return base.copy(
            revision = newRevision,
            updatedAtEpochMs = timestampMs,
            themeMode = themeMode ?: base.themeMode,
            themeStyle = themeStyle ?: base.themeStyle,
            accentColor = accentColor ?: base.accentColor,
            languageMode = languageMode ?: base.languageMode,
            chatWallpaper = chatWallpaper ?: base.chatWallpaper,
            chatFontScale = chatFontScale ?: base.chatFontScale,
            linkPreviewEnabled = linkPreviewEnabled ?: base.linkPreviewEnabled,
            unreadPriorityEnabled = unreadPriorityEnabled ?: base.unreadPriorityEnabled,
            writingStyleEnabled = writingStyleEnabled ?: base.writingStyleEnabled,
            writingStylePreset = writingStylePreset ?: base.writingStylePreset,
            writingStyleCustom = writingStyleCustom ?: base.writingStyleCustom,
            appLockTimeoutMinutes = appLockTimeoutMinutes ?: base.appLockTimeoutMinutes,
            screenSecureEnabled = screenSecureEnabled ?: base.screenSecureEnabled,
            sensitiveGateEnabled = sensitiveGateEnabled ?: base.sensitiveGateEnabled,
        )
    }
}

/**
 * Local device preferences stored strictly in local device storage.
 */
data class LocalDevicePreferences(
    val notificationsEnabled: Boolean = true,
    val notificationSoundEnabled: Boolean = true,
    val notificationVibrateEnabled: Boolean = true,
    val badgeCountEnabled: Boolean = true,
    val biometricUnlockEnabled: Boolean = false,
    val activeServerUrl: String = "",
    val localCacheMaxMegaBytes: Long = 512L,
    val camouflageCalculatorEnabled: Boolean = false,
)
