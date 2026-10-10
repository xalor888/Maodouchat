package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.repository.ClientPrefsNetworkRepository
import com.maodouchat.network.ClientPrefsUpdateRequest
import com.maodouchat.util.AppLocaleManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun GeneralSettingsViewModel.pullClientPrefsFromCloud() {
    val generation = ++clientPrefsPullGeneration
    clientPrefsPullJob?.cancel()
    val revisionAtStart = prefsRevision
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
    val job = viewModelScope.launch {
        try {
            if (!isCurrentOwner(ownerUserId)) return@launch
            ClientPrefsNetworkRepository().prefs().onSuccess { remote ->
                if (
                    generation == clientPrefsPullGeneration &&
                    prefsRevision == revisionAtStart &&
                    isCurrentOwner(ownerUserId)
                ) {
                    applyRemotePrefs(remote)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation == clientPrefsPullGeneration && isCurrentOwner(ownerUserId)) {
                _uiState.update {
                    it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                }
            }
        }
    }
    clientPrefsPullJob = job
    job.invokeOnCompletion {
        if (clientPrefsPullJob === job) clientPrefsPullJob = null
    }
}

internal fun GeneralSettingsViewModel.applyRemotePrefs(remote: com.maodouchat.network.ClientPrefsDto) {
    val context = getApplication<Application>()
    com.maodouchat.util.ClientPrefsSync.apply(context, remote)
    val theme = com.maodouchat.util.ThemePreferences.normalize(remote.themeMode)
    // 9.204：主题风格云端拉取——写入本地偏好（ThemePreferences 的 StateFlow 驱动全局重组）
    val themeStyle = com.maodouchat.util.ThemePreferences.normalizeStyle(remote.themeStyle)
    com.maodouchat.util.ThemePreferences.setStyle(context, themeStyle)
    val accentColor = com.maodouchat.util.ThemePreferences.normalizeAccent(remote.accentColor)
    com.maodouchat.util.ThemePreferences.setAccent(context, accentColor)
    val language = when (remote.languageMode.lowercase()) {
        AppLocaleManager.MODE_CHINESE, "zh-cn", "chinese" -> AppLocaleManager.MODE_CHINESE
        AppLocaleManager.MODE_ENGLISH, "english" -> AppLocaleManager.MODE_ENGLISH
        else -> AppLocaleManager.MODE_SYSTEM
    }
    val wallpaper = com.maodouchat.util.ChatAppearancePolicy.normalizeWallpaper(remote.chatWallpaper)
    val font = com.maodouchat.util.ChatAppearancePolicy.normalizeFontScale(remote.chatFontScale)
    _uiState.update {
        it.copy(
            themeMode = theme,
            themeStyle = themeStyle,
            accentColor = accentColor,
            languageMode = language,
            linkPreviewEnabled = remote.linkPreviewEnabled,
            unreadPriorityEnabled = remote.unreadPriorityEnabled,
            chatWallpaper = wallpaper.id,
            chatFontScale = font.id
        )
    }
}

internal fun GeneralSettingsViewModel.pushClientPrefs() {
    val generation = ++clientPrefsPushGeneration
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
    viewModelScope.launch {
        try {
            clientPrefsPushMutex.withLock {
                if (generation != clientPrefsPushGeneration || !isCurrentOwner(ownerUserId)) {
                    return@withLock
                }
                val state = _uiState.value
                val request = ClientPrefsUpdateRequest(
                    themeMode = state.themeMode,
                    themeStyle = state.themeStyle,
                    accentColor = state.accentColor,
                    languageMode = state.languageMode,
                    chatWallpaper = state.chatWallpaper,
                    chatFontScale = state.chatFontScale,
                    linkPreviewEnabled = state.linkPreviewEnabled,
                    unreadPriorityEnabled = state.unreadPriorityEnabled
                )
                ClientPrefsNetworkRepository().putPrefs(request = request).onFailure { error ->
                    if (generation == clientPrefsPushGeneration && isCurrentOwner(ownerUserId)) {
                        _uiState.update {
                            it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation == clientPrefsPushGeneration && isCurrentOwner(ownerUserId)) {
                _uiState.update {
                    it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                }
            }
        }
    }
}
