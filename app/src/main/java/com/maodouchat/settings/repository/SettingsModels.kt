package com.maodouchat.settings.repository

data class SettingsSession(val ownerUserId: String)

data class SettingsProfile(
    val id: String,
    val name: String,
    val avatar: String?,
    val status: String,
    val username: String?,
    val isModerator: Boolean,
)

data class SettingsPrivacy(
    val showOnline: Boolean,
    val showStatus: Boolean,
    val searchable: Boolean,
    val defaultPostVisibility: String,
    val onlineVisibility: String,
)

data class SecurityPreferences(
    val appLockTimeoutMinutes: Long,
    val screenSecureEnabled: Boolean,
    val sensitiveGateEnabled: Boolean,
)

data class SecurityPreferencesPatch(
    val appLockTimeoutMinutes: Long? = null,
    val screenSecureEnabled: Boolean? = null,
    val sensitiveGateEnabled: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = appLockTimeoutMinutes == null && screenSecureEnabled == null && sensitiveGateEnabled == null
}

data class SettingsPrivacyPatch(
    val showOnline: Boolean? = null,
    val showStatus: Boolean? = null,
    val searchable: Boolean? = null,
    val defaultPostVisibility: String? = null,
    val onlineVisibility: String? = null,
)
