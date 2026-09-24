package com.maodouchat.settings.repository

import android.app.Application
import com.maodouchat.network.ApiService
import com.maodouchat.network.ClientPrefsUpdateRequest
import com.maodouchat.network.TokenManager

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

interface SettingsRepository {
    fun currentSession(): SettingsSession?
    fun isCurrent(session: SettingsSession): Boolean
    suspend fun loadProfile(session: SettingsSession): Result<SettingsProfile>
    suspend fun loadPrivacy(session: SettingsSession): Result<SettingsPrivacy>
    suspend fun savePrivacy(session: SettingsSession, privacy: SettingsPrivacyPatch): Result<SettingsPrivacy>
    suspend fun loadSecurityPreferences(session: SettingsSession): Result<SecurityPreferences>
    suspend fun saveSecurityPreferences(
        session: SettingsSession,
        patch: SecurityPreferencesPatch,
    ): Result<SecurityPreferences>
}

data class SettingsPrivacyPatch(
    val showOnline: Boolean? = null,
    val showStatus: Boolean? = null,
    val searchable: Boolean? = null,
    val defaultPostVisibility: String? = null,
    val onlineVisibility: String? = null,
)

class SettingsSessionChangedException : IllegalStateException("Settings session changed")

internal class AndroidSettingsRepository(
    application: Application,
    private val delegate: com.maodouchat.settings.repository.VersionedSettingsRepository =
        com.maodouchat.settings.repository.DefaultVersionedSettingsRepository(application),
) : com.maodouchat.settings.repository.VersionedSettingsRepository by delegate
