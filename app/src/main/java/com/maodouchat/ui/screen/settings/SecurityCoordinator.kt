package com.maodouchat.ui.screen.settings

class SecurityCoordinator(private val repository: SettingsRepository) {
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
