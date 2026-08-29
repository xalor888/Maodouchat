package com.maodouchat.ui.screen.settings

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

internal class AndroidSettingsRepository(application: Application) : SettingsRepository {
    private val appContext = application.applicationContext
    private val tokenManager = TokenManager.getInstance(application)

    override fun currentSession(): SettingsSession? = tokenManager.getUserId().orEmpty()
        .takeIf(String::isNotBlank)
        ?.let(::SettingsSession)

    override fun isCurrent(session: SettingsSession): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = session.ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    override suspend fun loadProfile(session: SettingsSession): Result<SettingsProfile> =
        withToken(session) { token -> ApiService.getCurrentUser(token).map { user ->
            SettingsProfile(
                id = user.id,
                name = user.name,
                avatar = user.avatar,
                status = user.status,
                username = user.username,
                isModerator = user.isModerator,
            )
        } }

    override suspend fun loadPrivacy(session: SettingsSession): Result<SettingsPrivacy> =
        withToken(session) { token -> ApiService.getPrivacy(token).map { it.toSettingsPrivacy() } }

    override suspend fun savePrivacy(
        session: SettingsSession,
        privacy: SettingsPrivacyPatch,
    ): Result<SettingsPrivacy> = withToken(session) { token ->
        ApiService.updatePrivacy(
            token = token,
            showOnline = privacy.showOnline,
            showStatus = privacy.showStatus,
            searchable = privacy.searchable,
            defaultPostVisibility = privacy.defaultPostVisibility,
            onlineVisibility = privacy.onlineVisibility,
        ).map { it.toSettingsPrivacy() }
    }

    override suspend fun loadSecurityPreferences(session: SettingsSession): Result<SecurityPreferences> =
        withToken(session) { token -> ApiService.getClientPrefs(token) }
            .map {
                com.maodouchat.util.ClientPrefsSync.apply(appContext, it)
                it.toSecurityPreferences()
            }

    override suspend fun saveSecurityPreferences(
        session: SettingsSession,
        patch: SecurityPreferencesPatch,
    ): Result<SecurityPreferences> = withToken(session) { token ->
        ApiService.putClientPrefs(
            token,
            ClientPrefsUpdateRequest(
                appLockTimeoutMinutes = patch.appLockTimeoutMinutes,
                screenSecureEnabled = patch.screenSecureEnabled,
                sensitiveGateEnabled = patch.sensitiveGateEnabled,
            ),
        ).map { it.toSecurityPreferences() }
    }

    private suspend fun <T> withToken(
        session: SettingsSession,
        block: suspend (String) -> Result<T>,
    ): Result<T> {
        if (!isCurrent(session)) return Result.failure(SettingsSessionChangedException())
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return Result.failure(SettingsSessionChangedException())
        val result = block(token)
        return if (isCurrent(session)) result else Result.failure(SettingsSessionChangedException())
    }
}

private fun com.maodouchat.network.UserPrivacyDto.toSettingsPrivacy() = SettingsPrivacy(
    showOnline = showOnline,
    showStatus = showStatus,
    searchable = searchable,
    defaultPostVisibility = defaultPostVisibility,
    onlineVisibility = onlineVisibility,
)

private fun com.maodouchat.network.ClientPrefsDto.toSecurityPreferences() = SecurityPreferences(
    appLockTimeoutMinutes = appLockTimeoutMinutes,
    screenSecureEnabled = screenSecureEnabled,
    sensitiveGateEnabled = sensitiveGateEnabled,
)
