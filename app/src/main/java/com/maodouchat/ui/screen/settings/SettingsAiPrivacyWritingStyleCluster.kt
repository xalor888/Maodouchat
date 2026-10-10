package com.maodouchat.ui.screen.settings

import com.maodouchat.data.repository.ClientPrefsNetworkRepository
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.network.ClientPrefsUpdateRequest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun AiPrivacySettingsViewModel.setWritingStyleEnabled(enabled: Boolean) {
    if (_uiState.value.writingStyleEnabled == enabled) return
    writingStyleRevision++
    val app = getApplication<Application>()
    if (!enabled) {
        AiWritingStylePreferences.clear(app)
        _uiState.update {
            it.copy(
                writingStyleEnabled = false,
                writingStylePresetId = AiWritingStylePolicy.Preset.NONE.id,
                writingStyleCustomNote = "",
                infoMessage = text(R.string.ai_privacy_writing_style_disabled)
            )
        }
        pushWritingStylePrefs(false, AiWritingStylePolicy.Preset.NONE.id, "")
        return
    }
    val current = _uiState.value
    AiWritingStylePreferences.save(
        app,
        enabled = true,
        presetId = current.writingStylePresetId,
        customNote = current.writingStyleCustomNote
    )
    _uiState.update {
        it.copy(
            writingStyleEnabled = true,
            infoMessage = text(R.string.ai_privacy_writing_style_enabled)
        )
    }
    pushWritingStylePrefs(true, current.writingStylePresetId, current.writingStyleCustomNote)
}

internal fun AiPrivacySettingsViewModel.setWritingStylePreset(presetId: String) {
    val app = getApplication<Application>()
    val preset = AiWritingStylePolicy.Preset.fromId(presetId)
    if (_uiState.value.writingStylePresetId == preset.id) return
    writingStyleRevision++
    val enabled = _uiState.value.writingStyleEnabled
    if (enabled) {
        AiWritingStylePreferences.save(
            app,
            enabled = true,
            presetId = preset.id,
            customNote = _uiState.value.writingStyleCustomNote
        )
    } else {
        AiWritingStylePreferences.setPreset(app, preset.id)
    }
    _uiState.update {
        it.copy(
            writingStylePresetId = preset.id,
            infoMessage = if (enabled) text(R.string.ai_privacy_writing_style_saved) else it.infoMessage
        )
    }
    if (enabled) {
        pushWritingStylePrefs(true, preset.id, _uiState.value.writingStyleCustomNote)
    }
}

internal fun AiPrivacySettingsViewModel.setWritingStyleCustomNote(note: String) {
    // Cap while typing; do not collapse whitespace mid-edit so the field stays natural.
    val capped = note.take(AiWritingStylePolicy.MAX_CUSTOM_CHARS)
    if (_uiState.value.writingStyleCustomNote == capped) return
    writingStyleRevision++
    val app = getApplication<Application>()
    val enabled = _uiState.value.writingStyleEnabled
    if (enabled) {
        AiWritingStylePreferences.save(
            app,
            enabled = true,
            presetId = _uiState.value.writingStylePresetId,
            customNote = capped
        )
    } else {
        AiWritingStylePreferences.setCustomNote(app, capped)
    }
    _uiState.update { it.copy(writingStyleCustomNote = capped) }
    if (enabled) {
        // Debounce free-text so multi-end sync doesn't fire per keystroke.
        pushWritingStylePrefs(true, _uiState.value.writingStylePresetId, capped, debounceMs = 600L)
    }
}

internal fun AiPrivacySettingsViewModel.clearWritingStyle() {
    val current = _uiState.value
    if (!current.writingStyleEnabled &&
        current.writingStylePresetId == AiWritingStylePolicy.Preset.NONE.id &&
        current.writingStyleCustomNote.isEmpty()
    ) return
    writingStyleRevision++
    AiWritingStylePreferences.clear(getApplication())
    _uiState.update {
        it.copy(
            writingStyleEnabled = false,
            writingStylePresetId = AiWritingStylePolicy.Preset.NONE.id,
            writingStyleCustomNote = "",
            infoMessage = text(R.string.ai_privacy_writing_style_cleared)
        )
    }
    pushWritingStylePrefs(false, AiWritingStylePolicy.Preset.NONE.id, "")
}

internal fun AiPrivacySettingsViewModel.pushWritingStylePrefs(
    enabled: Boolean,
    presetId: String,
    customNote: String,
    debounceMs: Long = 0L
) {
    val generation = ++writingStylePushGeneration
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
    viewModelScope.launch {
        try {
            if (debounceMs > 0L) kotlinx.coroutines.delay(debounceMs)
            writingStylePushMutex.withLock {
                if (generation != writingStylePushGeneration || !isCurrentOwner(ownerUserId)) {
                    return@withLock
                }
                ClientPrefsNetworkRepository().putPrefs(
                    request = ClientPrefsUpdateRequest(
                        writingStyleEnabled = enabled,
                        writingStylePreset = presetId,
                        writingStyleCustom = customNote
                    )
                ).onFailure { error ->
                    if (generation == writingStylePushGeneration && isCurrentOwner(ownerUserId)) {
                        _uiState.update {
                            it.copy(errorMessage = error.message ?: text(R.string.ai_privacy_save_failed))
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation == writingStylePushGeneration && isCurrentOwner(ownerUserId)) {
                _uiState.update {
                    it.copy(errorMessage = error.message ?: text(R.string.ai_privacy_save_failed))
                }
            }
        }
    }
}

internal fun AiPrivacySettingsViewModel.loadWritingStyleSnapshot(): AiWritingStylePolicy.Snapshot =
    AiWritingStylePreferences.snapshot(getApplication())
