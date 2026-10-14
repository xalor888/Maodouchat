package com.maodouchat.settings.repository

import android.app.Application

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

class SettingsSessionChangedException : IllegalStateException("Settings session changed")

internal class AndroidSettingsRepository(
    application: Application,
    private val delegate: com.maodouchat.settings.repository.VersionedSettingsRepository =
        com.maodouchat.settings.repository.DefaultVersionedSettingsRepository(application),
) : com.maodouchat.settings.repository.VersionedSettingsRepository by delegate
