package com.maodouchat.ui.screen.settings

import com.maodouchat.settings.repository.SecurityPreferences
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.settings.repository.SettingsSession
import com.maodouchat.settings.repository.SettingsSessionChangedException

/**
 * 门面协调器：继承领域层 SecurityCoordinator，并集成 SettingsRepository 的持久化能力。
 */
class SecurityCoordinator(
    private val repository: SettingsRepository
) : com.maodouchat.security.coordinator.SecurityCoordinator() {

    fun currentSession(): SettingsSession? = repository.currentSession()

    suspend fun pull(session: SettingsSession): Result<SecurityPreferences> =
        repository.loadSecurityPreferences(session).onlyFor(session)

    suspend fun push(
        session: SettingsSession,
        patch: SecurityPreferencesPatch,
    ): Result<SecurityPreferences> {
        if (patch.isEmpty) return Result.failure(IllegalArgumentException("Security patch is empty"))
        return repository.saveSecurityPreferences(session, patch).onlyFor(session)
    }

    private fun <T> Result<T>.onlyFor(session: SettingsSession): Result<T> =
        if (repository.isCurrent(session)) this else Result.failure(SettingsSessionChangedException())
}
