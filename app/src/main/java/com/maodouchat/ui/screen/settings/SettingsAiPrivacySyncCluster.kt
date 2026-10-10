package com.maodouchat.ui.screen.settings

import com.maodouchat.data.repository.ClientPrefsNetworkRepository
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiWritingStylePreferences
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AiPrivacySettingsViewModel.refresh() {
    val generation = ++refreshGeneration
    refreshJob?.cancel()
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
        _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired)) }
        return
    }
    val styleRevisionAtStart = writingStyleRevision
    val aiSettingsRevisionAtStart = aiSettingsRevision
    if (!isCurrentOwner(ownerUserId)) return
    _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
    val job = viewModelScope.launch {
        try {
            if (!isCurrentOwner(ownerUserId)) {
                return@launch
            }
            val localEnabled = AiPrivacyPreferences.userEnabled(getApplication())
            // Pull multi-device writing-style prefs (non-secret tone hints)
            ClientPrefsNetworkRepository().prefs().onSuccess { remote ->
                if (!isCurrentOwner(ownerUserId)) return@onSuccess
                if (refreshGeneration == generation && writingStyleRevision == styleRevisionAtStart) {
                    applyRemoteWritingStyle(remote)
                }
            }
            if (refreshGeneration != generation || !isCurrentOwner(ownerUserId)) {
                return@launch
            }
            val style = loadWritingStyleSnapshot()
            _uiState.update { state ->
                val settingsAreCurrent = aiSettingsRevision == aiSettingsRevisionAtStart
                val masterOn = com.maodouchat.util.RuntimeFlags.isEnabled(
                    getApplication(),
                    com.maodouchat.util.RuntimeFlags.AI_MASTER
                )
                state.copy(
                    isLoading = false,
                    userEnabled = if (settingsAreCurrent) localEnabled else state.userEnabled,
                    effectiveEnabled = if (settingsAreCurrent) (localEnabled && masterOn) else state.effectiveEnabled,
                    writingStyleEnabled = style.enabled,
                    writingStylePresetId = style.preset.id,
                    writingStyleCustomNote = style.customNote,
                    auditLogs = emptyList(),
                    errorMessage = null
                )
            }
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
                        errorMessage = error.message ?: text(R.string.error_operation_failed),
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

internal fun AiPrivacySettingsViewModel.applyRemoteWritingStyle(remote: com.maodouchat.network.ClientPrefsDto) {
    val app = getApplication<Application>()
    AiWritingStylePreferences.save(
        app,
        enabled = remote.writingStyleEnabled,
        presetId = remote.writingStylePreset,
        customNote = remote.writingStyleCustom
    )
}

internal fun AiPrivacySettingsViewModel.text(id: Int): String = getApplication<Application>().getString(id)

internal fun AiPrivacySettingsViewModel.isCurrentOwner(expectedUserId: String): Boolean =
    com.maodouchat.security.BackgroundSessionGate.mayContinue(
        expectedUserId = expectedUserId,
    )
