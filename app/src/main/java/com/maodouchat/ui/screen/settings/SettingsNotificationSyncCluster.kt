package com.maodouchat.ui.screen.settings

import com.maodouchat.data.repository.NotificationSettingsNetworkRepository
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.NotificationSettingsRequest
import com.maodouchat.network.NotificationSettingsResponse
import com.maodouchat.notification.NotificationPreferences
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun NotificationSettingsViewModel.refresh() {
    val generation = ++refreshGeneration
    refreshJob?.cancel()
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    refreshPushStatus()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
        _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired)) }
        return
    }
    adoptRevisionOwner(ownerUserId)
    val revisionAtStart = settingsRevision
    if (!isCurrentOwner(ownerUserId)) return
    _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
    val job = viewModelScope.launch {
        try {
            if (!isCurrentOwner(ownerUserId)) {
                return@launch
            }
            NotificationSettingsNetworkRepository().settings().fold(
                onSuccess = { remote ->
                    if (refreshGeneration != generation || !isCurrentOwner(ownerUserId)) {
                        return@fold
                    }
                    if (settingsRevision == revisionAtStart && revisionAtStart <= lastSyncedRevision) {
                        saveLocal(remote)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                enableNotifications = remote.enableNotifications,
                                soundEnabled = remote.soundEnabled,
                                previewEnabled = remote.previewEnabled,
                                ringtoneEnabled = remote.ringtoneEnabled,
                                dndStartHour = remote.dndStartHour.coerceIn(0, 23),
                                dndEndHour = remote.dndEndHour.coerceIn(0, 23),
                                dndEnabled = remote.dndEnabled,
                                dndStartMinute = remote.dndStartMinute.coerceIn(0, 1439),
                                dndEndMinute = remote.dndEndMinute.coerceIn(0, 1439),
                                pushConfigured = com.maodouchat.session.CurrentSession.hasSession(),
                                pushReady = isRealtimeConnected(),
                                infoMessage = text(R.string.notifications_synced)
                            )
                        }
                        updateReminderScheduling(remote.enableNotifications)
                        if (!remote.enableNotifications) dismissPostedNotifications()
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                },
                onFailure = { error ->
                    if (refreshGeneration == generation && isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.notifications_sync_failed)) }
                    }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (refreshGeneration == generation && isCurrentOwner(ownerUserId)) {
                _uiState.update { it.copy(isLoading = false) }
            }
            throw error
        } catch (error: Throwable) {
            if (refreshGeneration == generation && isCurrentOwner(ownerUserId)) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: text(R.string.notifications_sync_failed),
                    )
                }
            }
        }
    }
    refreshJob = job
    job.invokeOnCompletion {
        if (refreshJob === job) refreshJob = null
    }
}

internal fun NotificationSettingsViewModel.refreshPushStatus() {
    _uiState.update {
        it.copy(
            pushConfigured = com.maodouchat.session.CurrentSession.hasSession(),
            pushReady = isRealtimeConnected()
        )
    }
}

internal fun NotificationSettingsViewModel.sync() {
    val syncOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || syncOwnerUserId.isBlank()) {
        syncGeneration++
        _uiState.update {
            it.copy(isSaving = false, errorMessage = text(R.string.error_session_expired))
        }
        return
    }
    adoptRevisionOwner(syncOwnerUserId)
    val generation = ++syncGeneration
    if (!isCurrentOwner(syncOwnerUserId)) return
    _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = null) }
    viewModelScope.launch {
        try {
            syncMutex.withLock {
                if (generation != syncGeneration) return@withLock
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = syncOwnerUserId,
                )
                ) {
                    return@withLock
                }
                val state = _uiState.value
                val request = NotificationSettingsRequest(
                    enableNotifications = state.enableNotifications,
                    soundEnabled = state.soundEnabled,
                    previewEnabled = state.previewEnabled,
                    ringtoneEnabled = state.ringtoneEnabled,
                    dndStartHour = state.dndStartHour,
                    dndEndHour = state.dndEndHour,
                    dndEnabled = state.dndEnabled,
                    dndStartMinute = state.dndStartMinute,
                    dndEndMinute = state.dndEndMinute
                )
                NotificationSettingsNetworkRepository().updateSettings(request = request).fold(
                    onSuccess = { remote ->
                        if (generation != syncGeneration || !isCurrentOwner(syncOwnerUserId)) {
                            return@fold
                        }
                        lastSyncedRevision = settingsRevision
                        // 8.34：同步成功清除未同步标记（失败置位见 onFailure）
                        NotificationPreferences.markPendingSync(app, false)
                        saveLocal(remote)
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                enableNotifications = remote.enableNotifications,
                                soundEnabled = remote.soundEnabled,
                                previewEnabled = remote.previewEnabled,
                                ringtoneEnabled = remote.ringtoneEnabled,
                                dndStartHour = remote.dndStartHour.coerceIn(0, 23),
                                dndEndHour = remote.dndEndHour.coerceIn(0, 23),
                                dndEnabled = remote.dndEnabled,
                                dndStartMinute = remote.dndStartMinute.coerceIn(0, 1439),
                                dndEndMinute = remote.dndEndMinute.coerceIn(0, 1439),
                                infoMessage = text(R.string.notifications_saved_to_account)
                            )
                        }
                        updateReminderScheduling(remote.enableNotifications)
                        if (!remote.enableNotifications) {
                            dismissPostedNotifications()
                        }
                    },
                    onFailure = { error ->
                        if (generation == syncGeneration && isCurrentOwner(syncOwnerUserId)) {
                            // 8.34：失败持久化标记——本地新值不得被后续 refresh 静默回滚
                            NotificationPreferences.markPendingSync(app, true)
                            val message = error.message ?: text(R.string.notifications_save_failed)
                            _uiState.update { it.copy(isSaving = false, errorMessage = message) }
                            _snackbar.tryEmit(message)
                        }
                    }
                )
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (generation == syncGeneration && isCurrentOwner(syncOwnerUserId)) {
                _uiState.update { it.copy(isSaving = false) }
            }
            throw error
        } catch (error: Throwable) {
            if (generation == syncGeneration && isCurrentOwner(syncOwnerUserId)) {
                // 8.34：同步异常同样置未同步标记（防刷新回滚）
                NotificationPreferences.markPendingSync(app, true)
                val message = error.message ?: text(R.string.notifications_save_failed)
                _uiState.update { it.copy(isSaving = false, errorMessage = message) }
                _snackbar.tryEmit(message)
            }
        }
    }
}

internal fun NotificationSettingsViewModel.updateLocal(transform: (NotificationSettingsUiState) -> NotificationSettingsUiState) {
    com.maodouchat.session.CurrentSession.snapshot().userId?.takeIf { it.isNotBlank() }?.let(::adoptRevisionOwner)
    refreshGeneration++
    refreshJob?.cancel()
    settingsRevision++
    _uiState.update { current ->
        transform(current).copy(isLoading = false, errorMessage = null, infoMessage = null)
    }
    saveLocal(_uiState.value)
}

internal fun NotificationSettingsViewModel.adoptRevisionOwner(ownerUserId: String) {
    if (revisionOwnerUserId == ownerUserId) return
    revisionOwnerUserId = ownerUserId
    settingsRevision = 0L
    lastSyncedRevision = 0L
    syncGeneration++
}

internal fun NotificationSettingsViewModel.saveLocal(state: NotificationSettingsUiState) {
    NotificationPreferences.save(
        context = app,
        enableNotifications = state.enableNotifications,
        soundEnabled = state.soundEnabled,
        previewEnabled = state.previewEnabled,
        ringtoneEnabled = state.ringtoneEnabled,
        dndStartHour = state.dndStartHour,
        dndEndHour = state.dndEndHour,
        taskRemindersEnabled = state.taskRemindersEnabled,
        dndEnabled = state.dndEnabled,
        dndStartMinute = state.dndStartMinute,
        dndEndMinute = state.dndEndMinute
    )
}

internal fun NotificationSettingsViewModel.saveLocal(response: NotificationSettingsResponse) {
    NotificationPreferences.save(
        context = app,
        enableNotifications = response.enableNotifications,
        soundEnabled = response.soundEnabled,
        previewEnabled = response.previewEnabled,
        ringtoneEnabled = response.ringtoneEnabled,
        dndStartHour = response.dndStartHour,
        dndEndHour = response.dndEndHour,
        dndEnabled = response.dndEnabled,
        dndStartMinute = response.dndStartMinute,
        dndEndMinute = response.dndEndMinute
    )
}

internal fun NotificationSettingsViewModel.text(id: Int): String = getApplication<Application>().getString(id)

internal fun NotificationSettingsViewModel.isRealtimeConnected(): Boolean {
    return com.maodouchat.session.AppRuntime
        .realtimeDispatcherOrNull(getApplication())
        ?.connectionState?.value == com.maodouchat.core.realtime.RealtimeConnectionState.CONNECTED
}

internal fun NotificationSettingsViewModel.isCurrentOwner(expectedUserId: String): Boolean =
    com.maodouchat.security.BackgroundSessionGate.mayContinue(
        expectedUserId = expectedUserId,
    )
