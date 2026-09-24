package com.maodouchat.settings.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.ApiService
import com.maodouchat.network.ClientPrefsDto
import com.maodouchat.network.ClientPrefsUpdateRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.settings.model.LocalDevicePreferences
import com.maodouchat.settings.model.MultiDevicePreferences
import com.maodouchat.settings.model.PreferencesPatch
import com.maodouchat.settings.policy.ConflictResolutionResult
import com.maodouchat.settings.policy.PreferenceConflictPolicy
import com.maodouchat.settings.server.ServerSwitchResult
import com.maodouchat.settings.server.ServerSwitchTransaction
import com.maodouchat.settings.repository.SecurityPreferences
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsPrivacy
import com.maodouchat.settings.repository.SettingsPrivacyPatch
import com.maodouchat.settings.repository.SettingsProfile
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.settings.repository.SettingsSession
import com.maodouchat.settings.repository.SettingsSessionChangedException
import com.maodouchat.util.ClientPrefsSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unified, versioned single source of truth for all application preferences.
 * Separates multi-device roaming preferences from local device-bound preferences.
 */
interface VersionedSettingsRepository : SettingsRepository {
    fun observeRoamingPreferences(): StateFlow<MultiDevicePreferences>
    suspend fun getRoamingPreferences(session: SettingsSession): Result<MultiDevicePreferences>
    suspend fun updateRoamingPreferences(
        session: SettingsSession,
        patch: PreferencesPatch,
    ): Result<MultiDevicePreferences>
    suspend fun syncWithRemote(session: SettingsSession): Result<ConflictResolutionResult>

    fun getLocalPreferences(): LocalDevicePreferences
    fun updateLocalPreferences(update: (LocalDevicePreferences) -> LocalDevicePreferences): LocalDevicePreferences

    suspend fun switchServer(targetUrl: String): ServerSwitchResult
}

/**
 * Default implementation of VersionedSettingsRepository backed by SharedPreferences,
 * ApiService client endpoints, and ServerSwitchTransaction.
 */
class DefaultVersionedSettingsRepository(
    private val application: Application,
) : VersionedSettingsRepository {

    private val context: Context = application.applicationContext
    private val tokenManager: TokenManager = TokenManager.getInstance(application)
    private val serverSwitchTransaction: ServerSwitchTransaction = ServerSwitchTransaction(context)
    private val syncMutex = Mutex()

    private val roamingPrefs: SharedPreferences =
        context.getSharedPreferences("roaming_client_prefs", Context.MODE_PRIVATE)
    private val localPrefs: SharedPreferences =
        context.getSharedPreferences("local_device_prefs", Context.MODE_PRIVATE)

    private val _roamingState = MutableStateFlow(loadStoredRoaming())
    override fun observeRoamingPreferences(): StateFlow<MultiDevicePreferences> = _roamingState.asStateFlow()

    override fun currentSession(): SettingsSession? =
        tokenManager.getUserId().orEmpty().takeIf(String::isNotBlank)?.let(::SettingsSession)

    override fun isCurrent(session: SettingsSession): Boolean =
        BackgroundSessionGate.mayContinue(
            expectedUserId = session.ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    override suspend fun loadProfile(session: SettingsSession): Result<SettingsProfile> =
        withToken(session) { token ->
            ApiService.getCurrentUser(token).map { user ->
                SettingsProfile(
                    id = user.id,
                    name = user.name,
                    avatar = user.avatar,
                    status = user.status,
                    username = user.username,
                    isModerator = user.isModerator,
                )
            }
        }

    override suspend fun loadPrivacy(session: SettingsSession): Result<SettingsPrivacy> =
        withToken(session) { token ->
            ApiService.getPrivacy(token).map {
                SettingsPrivacy(
                    showOnline = it.showOnline,
                    showStatus = it.showStatus,
                    searchable = it.searchable,
                    defaultPostVisibility = it.defaultPostVisibility,
                    onlineVisibility = it.onlineVisibility,
                )
            }
        }

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
        ).map {
            SettingsPrivacy(
                showOnline = it.showOnline,
                showStatus = it.showStatus,
                searchable = it.searchable,
                defaultPostVisibility = it.defaultPostVisibility,
                onlineVisibility = it.onlineVisibility,
            )
        }
    }

    override suspend fun loadSecurityPreferences(session: SettingsSession): Result<SecurityPreferences> =
        withToken(session) { token ->
            ApiService.getClientPrefs(token).map { remote ->
                ClientPrefsSync.apply(context, remote)
                SecurityPreferences(
                    appLockTimeoutMinutes = remote.appLockTimeoutMinutes,
                    screenSecureEnabled = remote.screenSecureEnabled,
                    sensitiveGateEnabled = remote.sensitiveGateEnabled,
                )
            }
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
        ).map { remote ->
            SecurityPreferences(
                appLockTimeoutMinutes = remote.appLockTimeoutMinutes,
                screenSecureEnabled = remote.screenSecureEnabled,
                sensitiveGateEnabled = remote.sensitiveGateEnabled,
            )
        }
    }

    override suspend fun getRoamingPreferences(session: SettingsSession): Result<MultiDevicePreferences> {
        if (!isCurrent(session)) return Result.failure(SettingsSessionChangedException())
        var current = _roamingState.value
        if (current.ownerUserId != session.ownerUserId) {
            current = loadStoredRoaming().copy(ownerUserId = session.ownerUserId)
            _roamingState.value = current
        }
        return Result.success(current)
    }

    override suspend fun updateRoamingPreferences(
        session: SettingsSession,
        patch: PreferencesPatch,
    ): Result<MultiDevicePreferences> = syncMutex.withLock {
        if (!isCurrent(session)) return Result.failure(SettingsSessionChangedException())
        if (patch.isEmpty) return Result.success(_roamingState.value)

        val current = _roamingState.value
        val now = System.currentTimeMillis()
        val nextRevision = current.revision + 1L
        val updated = patch.applyTo(current, nextRevision, now).copy(ownerUserId = session.ownerUserId)

        // Store locally first
        saveStoredRoaming(updated)
        _roamingState.value = updated

        // Push to remote if session is still valid
        val token = tokenManager.getToken().orEmpty()
        if (token.isNotBlank() && isCurrent(session)) {
            val updateReq = ClientPrefsUpdateRequest(
                themeMode = patch.themeMode,
                themeStyle = patch.themeStyle,
                accentColor = patch.accentColor,
                languageMode = patch.languageMode,
                chatWallpaper = patch.chatWallpaper,
                chatFontScale = patch.chatFontScale,
                linkPreviewEnabled = patch.linkPreviewEnabled,
                unreadPriorityEnabled = patch.unreadPriorityEnabled,
                writingStyleEnabled = patch.writingStyleEnabled,
                writingStylePreset = patch.writingStylePreset,
                writingStyleCustom = patch.writingStyleCustom,
                appLockTimeoutMinutes = patch.appLockTimeoutMinutes,
                screenSecureEnabled = patch.screenSecureEnabled,
                sensitiveGateEnabled = patch.sensitiveGateEnabled,
            )
            val remoteResult = ApiService.putClientPrefs(token, updateReq)
            remoteResult.onSuccess { remoteDto ->
                ClientPrefsSync.apply(context, remoteDto)
            }
        }

        Result.success(updated)
    }

    override suspend fun syncWithRemote(session: SettingsSession): Result<ConflictResolutionResult> = syncMutex.withLock {
        if (!isCurrent(session)) return Result.failure(SettingsSessionChangedException())
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return Result.failure(SettingsSessionChangedException())

        val remoteResult = ApiService.getClientPrefs(token)
        val remoteDto = remoteResult.getOrElse { return Result.failure(it) }

        val remote = remoteDto.toMultiDevicePreferences(session.ownerUserId)
        val local = _roamingState.value

        val resolution = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = session.ownerUserId,
            local = local,
            remote = remote,
        )

        when (resolution) {
            is ConflictResolutionResult.AcceptedRemote -> {
                saveStoredRoaming(resolution.preferences)
                _roamingState.value = resolution.preferences
                ClientPrefsSync.apply(context, remoteDto)
            }
            is ConflictResolutionResult.Merged -> {
                saveStoredRoaming(resolution.mergedPreferences)
                _roamingState.value = resolution.mergedPreferences
                if (resolution.requiresPushToRemote) {
                    val pushReq = resolution.mergedPreferences.toPatch().toUpdateRequest()
                    ApiService.putClientPrefs(token, pushReq)
                }
            }
            is ConflictResolutionResult.AccountMismatchRejected -> {
                // Ignore remote due to account mismatch
            }
        }

        Result.success(resolution)
    }

    override fun getLocalPreferences(): LocalDevicePreferences {
        return LocalDevicePreferences(
            notificationsEnabled = localPrefs.getBoolean("notifications_enabled", true),
            notificationSoundEnabled = localPrefs.getBoolean("notification_sound_enabled", true),
            notificationVibrateEnabled = localPrefs.getBoolean("notification_vibrate_enabled", true),
            badgeCountEnabled = localPrefs.getBoolean("badge_count_enabled", true),
            biometricUnlockEnabled = localPrefs.getBoolean("biometric_unlock_enabled", false),
            activeServerUrl = localPrefs.getString("active_server_url", "").orEmpty(),
            localCacheMaxMegaBytes = localPrefs.getLong("local_cache_max_mb", 512L),
            camouflageCalculatorEnabled = localPrefs.getBoolean("camouflage_calc", false),
        )
    }

    override fun updateLocalPreferences(
        update: (LocalDevicePreferences) -> LocalDevicePreferences,
    ): LocalDevicePreferences {
        val current = getLocalPreferences()
        val updated = update(current)
        localPrefs.edit()
            .putBoolean("notifications_enabled", updated.notificationsEnabled)
            .putBoolean("notification_sound_enabled", updated.notificationSoundEnabled)
            .putBoolean("notification_vibrate_enabled", updated.notificationVibrateEnabled)
            .putBoolean("badge_count_enabled", updated.badgeCountEnabled)
            .putBoolean("biometric_unlock_enabled", updated.biometricUnlockEnabled)
            .putString("active_server_url", updated.activeServerUrl)
            .putLong("local_cache_max_mb", updated.localCacheMaxMegaBytes)
            .putBoolean("camouflage_calc", updated.camouflageCalculatorEnabled)
            .apply()
        return updated
    }

    override suspend fun switchServer(targetUrl: String): ServerSwitchResult {
        val result = serverSwitchTransaction.executeSwitch(targetUrl)
        if (result is ServerSwitchResult.Success) {
            updateLocalPreferences { it.copy(activeServerUrl = result.newUrl) }
        }
        return result
    }

    private fun loadStoredRoaming(): MultiDevicePreferences {
        return MultiDevicePreferences(
            ownerUserId = roamingPrefs.getString("owner_user_id", "").orEmpty(),
            revision = roamingPrefs.getLong("revision", 0L),
            updatedAtEpochMs = roamingPrefs.getLong("updated_at", 0L),
            themeMode = roamingPrefs.getString("theme_mode", "system") ?: "system",
            themeStyle = roamingPrefs.getString("theme_style", "maodou") ?: "maodou",
            accentColor = roamingPrefs.getString("accent_color", "none") ?: "none",
            languageMode = roamingPrefs.getString("language_mode", "system") ?: "system",
            chatWallpaper = roamingPrefs.getString("chat_wallpaper", "default") ?: "default",
            chatFontScale = roamingPrefs.getString("chat_font_scale", "normal") ?: "normal",
            linkPreviewEnabled = roamingPrefs.getBoolean("link_preview", true),
            unreadPriorityEnabled = roamingPrefs.getBoolean("unread_priority", true),
            writingStyleEnabled = roamingPrefs.getBoolean("writing_style_enabled", false),
            writingStylePreset = roamingPrefs.getString("writing_style_preset", "none") ?: "none",
            writingStyleCustom = roamingPrefs.getString("writing_style_custom", "").orEmpty(),
            appLockTimeoutMinutes = roamingPrefs.getLong("app_lock_timeout", 5L),
            screenSecureEnabled = roamingPrefs.getBoolean("screen_secure", false),
            sensitiveGateEnabled = roamingPrefs.getBoolean("sensitive_gate", true),
        )
    }

    private fun saveStoredRoaming(prefs: MultiDevicePreferences) {
        roamingPrefs.edit()
            .putString("owner_user_id", prefs.ownerUserId)
            .putLong("revision", prefs.revision)
            .putLong("updated_at", prefs.updatedAtEpochMs)
            .putString("theme_mode", prefs.themeMode)
            .putString("theme_style", prefs.themeStyle)
            .putString("accent_color", prefs.accentColor)
            .putString("language_mode", prefs.languageMode)
            .putString("chat_wallpaper", prefs.chatWallpaper)
            .putString("chat_font_scale", prefs.chatFontScale)
            .putBoolean("link_preview", prefs.linkPreviewEnabled)
            .putBoolean("unread_priority", prefs.unreadPriorityEnabled)
            .putBoolean("writing_style_enabled", prefs.writingStyleEnabled)
            .putString("writing_style_preset", prefs.writingStylePreset)
            .putString("writing_style_custom", prefs.writingStyleCustom)
            .putLong("app_lock_timeout", prefs.appLockTimeoutMinutes)
            .putBoolean("screen_secure", prefs.screenSecureEnabled)
            .putBoolean("sensitive_gate", prefs.sensitiveGateEnabled)
            .apply()
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

private fun ClientPrefsDto.toMultiDevicePreferences(ownerUserId: String): MultiDevicePreferences {
    return MultiDevicePreferences(
        ownerUserId = ownerUserId,
        revision = 1L,
        updatedAtEpochMs = updatedAt,
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

private fun PreferencesPatch.toUpdateRequest(): ClientPrefsUpdateRequest {
    return ClientPrefsUpdateRequest(
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
