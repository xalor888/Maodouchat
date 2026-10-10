package com.maodouchat.ui.screen.settings

import com.maodouchat.util.RuntimeFlags
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.ai.AiWritingStylePreferences
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun AiPrivacySettingsViewModel.setUserAiEnabled(enabled: Boolean) {
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
        aiSettingsRevision++
        _uiState.update { it.copy(isSaving = false, errorMessage = text(R.string.error_session_expired)) }
        return
    }
    val mutationKey = "$ownerUserId:enabled:$enabled"
    if (pendingAiMutationKey == mutationKey) return
    val revision = ++aiSettingsRevision
    if (!isCurrentOwner(ownerUserId)) return
    pendingAiMutationKey = mutationKey
    _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = null) }
    viewModelScope.launch {
        try {
            aiMutationMutex.withLock {
                if (aiSettingsRevision != revision || !isCurrentOwner(ownerUserId)) {
                    return@withLock
                }
                pendingAiMutationKey = null
                AiPrivacyPreferences.setUserEnabled(getApplication(), enabled)
                val masterOn = com.maodouchat.util.RuntimeFlags.isEnabled(
                    getApplication(),
                    com.maodouchat.util.RuntimeFlags.AI_MASTER
                )
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        userEnabled = enabled,
                        effectiveEnabled = enabled && masterOn,
                        infoMessage = if (enabled) text(R.string.ai_privacy_global_enabled) else text(R.string.ai_privacy_global_disabled)
                    )
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (aiSettingsRevision == revision && isCurrentOwner(ownerUserId)) {
                pendingAiMutationKey = null
                _uiState.update { it.copy(isSaving = false) }
            }
            throw error
        } catch (error: Throwable) {
            if (aiSettingsRevision == revision && isCurrentOwner(ownerUserId)) {
                pendingAiMutationKey = null
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: text(R.string.ai_privacy_save_failed),
                    )
                }
            }
        } finally {
            if (aiSettingsRevision == revision && pendingAiMutationKey == mutationKey) {
                pendingAiMutationKey = null
            }
        }
    }
}

internal fun AiPrivacySettingsViewModel.setAiConsentAccepted(accepted: Boolean) {
    if (_uiState.value.aiConsentAccepted == accepted) return
    AiPrivacyPreferences.setConsentAccepted(getApplication(), accepted)
    _uiState.update {
        it.copy(
            aiConsentAccepted = accepted,
            infoMessage = if (accepted) text(R.string.ai_privacy_local_allowed) else text(R.string.ai_privacy_local_revoked)
        )
    }
}

internal fun AiPrivacySettingsViewModel.setLocalSafetyEnabled(enabled: Boolean) {
    if (_uiState.value.localSafetyEnabled == enabled) return
    AiPrivacyPreferences.setLocalSafetyEnabled(getApplication(), enabled)
    _uiState.update {
        it.copy(
            localSafetyEnabled = enabled,
            infoMessage = if (enabled) text(R.string.ai_privacy_local_safety_enabled) else text(R.string.ai_privacy_local_safety_disabled)
        )
    }
}

internal fun AiPrivacySettingsViewModel.enableAllDefaults() {
    AiPrivacyPreferences.enableAllDefaults(getApplication())
    val masterOn = com.maodouchat.util.RuntimeFlags.isEnabled(
        getApplication(),
        com.maodouchat.util.RuntimeFlags.AI_MASTER
    )
    _uiState.update {
        it.copy(
            userEnabled = true,
            aiConsentAccepted = true,
            effectiveEnabled = masterOn,
            localSafetyEnabled = true,
            infoMessage = text(R.string.ai_privacy_enable_all_active)
        )
    }
}

/** 清空本机 AI 授权：清掉 AI 偏好、停止本机 AI 任务通知。 */
internal fun AiPrivacySettingsViewModel.revokeLocalConsent() {
    val revokeOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    val mutationKey = "$revokeOwnerUserId:revoke"
    if (pendingAiMutationKey == mutationKey) return
    val revision = ++aiSettingsRevision
    writingStyleRevision++
    AiPrivacyPreferences.revoke(getApplication())
    AiWritingStylePreferences.clear(getApplication())
    pushWritingStylePrefs(false, AiWritingStylePolicy.Preset.NONE.id, "")
    // 立即停掉本地 AI 任务调度，避免撤销之后还能触发新提醒
    runCatching { AiTaskReminderScheduler.cancelAll(getApplication()) }
    if (com.maodouchat.session.CurrentSession.hasSession() && revokeOwnerUserId.isNotBlank()) {
        if (!isCurrentOwner(revokeOwnerUserId)) return
        pendingAiMutationKey = mutationKey
        _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            try {
                aiMutationMutex.withLock {
                    if (aiSettingsRevision != revision || !isCurrentOwner(revokeOwnerUserId)) {
                        return@withLock
                    }
                    pendingAiMutationKey = null
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            aiConsentAccepted = false,
                            localSafetyEnabled = false,
                            writingStyleEnabled = false,
                            writingStylePresetId = AiWritingStylePolicy.Preset.NONE.id,
                            writingStyleCustomNote = "",
                            userEnabled = false,
                            infoMessage = text(R.string.ai_privacy_reset_done)
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (aiSettingsRevision == revision && isCurrentOwner(revokeOwnerUserId)) {
                    pendingAiMutationKey = null
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            aiConsentAccepted = false,
                            localSafetyEnabled = false,
                            writingStyleEnabled = false,
                            writingStylePresetId = AiWritingStylePolicy.Preset.NONE.id,
                            writingStyleCustomNote = ""
                        )
                    }
                }
                throw error
            } catch (error: Throwable) {
                if (aiSettingsRevision == revision && isCurrentOwner(revokeOwnerUserId)) {
                    pendingAiMutationKey = null
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            aiConsentAccepted = false,
                            localSafetyEnabled = false,
                            writingStyleEnabled = false,
                            writingStylePresetId = AiWritingStylePolicy.Preset.NONE.id,
                            writingStyleCustomNote = "",
                            userEnabled = false,
                            errorMessage = error.message ?: text(R.string.ai_privacy_save_failed),
                            infoMessage = text(R.string.ai_privacy_reset_done),
                        )
                    }
                }
            } finally {
                if (aiSettingsRevision == revision && pendingAiMutationKey == mutationKey) {
                    pendingAiMutationKey = null
                }
            }
        }
    } else {
        _uiState.update {
            it.copy(
                isSaving = false,
                aiConsentAccepted = false,
                localSafetyEnabled = false,
                writingStyleEnabled = false,
                writingStylePresetId = AiWritingStylePolicy.Preset.NONE.id,
                writingStyleCustomNote = "",
                infoMessage = text(R.string.ai_privacy_reset_done)
            )
        }
    }
}

internal fun AiPrivacySettingsViewModel.clearMessages() {
    _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
}
