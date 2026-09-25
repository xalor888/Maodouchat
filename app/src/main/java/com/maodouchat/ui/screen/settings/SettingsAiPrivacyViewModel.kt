package com.maodouchat.ui.screen.settings

import com.maodouchat.data.repository.ClientPrefsNetworkRepository
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.network.ClientPrefsUpdateRequest
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maodouchat.network.AiAuditLogResponse


/**
 * 「AI 与隐私」设置页的 ViewModel（G118 从 `SettingsSubViewModels.kt` 拆出，原 482 行）。
 *
 * 管 AI 总开关、本机授权（localSafety）、写作风格偏好、调用审计日志的拉取与保存。
 * 含它的 UI 状态数据类 `AiPrivacySettingsUiState`。
 *
 * **拆解约束**：不直接抓应用级数据库单例；网络经 `data/repository` 的薄仓库（G328c 起
 * 不再直连 `ApiService`），偏好经
 * `AiPrivacyPreferences` / `AiWritingStylePreferences`。纯搬移，不改判断。
 */

data class AiPrivacySettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val userEnabled: Boolean = false,
    val effectiveEnabled: Boolean = false,
    val aiConsentAccepted: Boolean = false,
    val localSafetyEnabled: Boolean = false,
    val writingStyleEnabled: Boolean = false,
    val writingStylePresetId: String = AiWritingStylePolicy.Preset.NONE.id,
    val writingStyleCustomNote: String = "",
    val auditLogs: List<AiAuditLogResponse> = emptyList(),
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class AiPrivacySettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager.getInstance(application)
    private val writingStylePushMutex = Mutex()
    private var writingStylePushGeneration = 0L
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var refreshGeneration = 0L
    private val aiMutationMutex = Mutex()
    private var pendingAiMutationKey: String? = null
    private var writingStyleRevision = 0L
    private var aiSettingsRevision = 0L

    private val _uiState = MutableStateFlow(
        AiPrivacySettingsUiState(
            userEnabled = AiPrivacyPreferences.userEnabled(application),
            effectiveEnabled = AiPrivacyPreferences.userEnabled(application) &&
                AiPrivacyPreferences.consentAccepted(application),
            aiConsentAccepted = AiPrivacyPreferences.consentAccepted(application),
            localSafetyEnabled = AiPrivacyPreferences.localSafetyEnabled(application)
        )
    )
    val uiState: StateFlow<AiPrivacySettingsUiState> = _uiState.asStateFlow()

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
        )

    private fun loadWritingStyleSnapshot(): AiWritingStylePolicy.Snapshot =
        AiWritingStylePreferences.snapshot(getApplication())

    init {
        val style = loadWritingStyleSnapshot()
        _uiState.update {
            it.copy(
                writingStyleEnabled = style.enabled,
                writingStylePresetId = style.preset.id,
                writingStyleCustomNote = style.customNote
            )
        }
        refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
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
                val liveToken = tokenManager.getToken() ?: token
                val localEnabled = AiPrivacyPreferences.userEnabled(getApplication())
                // Pull multi-device writing-style prefs (non-secret tone hints)
                ClientPrefsNetworkRepository().prefs(liveToken).onSuccess { remote ->
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

    private fun applyRemoteWritingStyle(remote: com.maodouchat.network.ClientPrefsDto) {
        val app = getApplication<Application>()
        AiWritingStylePreferences.save(
            app,
            enabled = remote.writingStyleEnabled,
            presetId = remote.writingStylePreset,
            customNote = remote.writingStyleCustom
        )
    }

    private fun pushWritingStylePrefs(
        enabled: Boolean,
        presetId: String,
        customNote: String,
        debounceMs: Long = 0L
    ) {
        val generation = ++writingStylePushGeneration
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            try {
                if (debounceMs > 0L) kotlinx.coroutines.delay(debounceMs)
                writingStylePushMutex.withLock {
                    if (generation != writingStylePushGeneration || !isCurrentOwner(ownerUserId)) {
                        return@withLock
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    ClientPrefsNetworkRepository().putPrefs(
                        liveToken,
                        ClientPrefsUpdateRequest(
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

    fun setUserAiEnabled(enabled: Boolean) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
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

    fun setAiConsentAccepted(accepted: Boolean) {
        if (_uiState.value.aiConsentAccepted == accepted) return
        AiPrivacyPreferences.setConsentAccepted(getApplication(), accepted)
        _uiState.update {
            it.copy(
                aiConsentAccepted = accepted,
                infoMessage = if (accepted) text(R.string.ai_privacy_local_allowed) else text(R.string.ai_privacy_local_revoked)
            )
        }
    }

    fun setLocalSafetyEnabled(enabled: Boolean) {
        if (_uiState.value.localSafetyEnabled == enabled) return
        AiPrivacyPreferences.setLocalSafetyEnabled(getApplication(), enabled)
        _uiState.update {
            it.copy(
                localSafetyEnabled = enabled,
                infoMessage = if (enabled) text(R.string.ai_privacy_local_safety_enabled) else text(R.string.ai_privacy_local_safety_disabled)
            )
        }
    }

    fun enableAllDefaults() {
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


    fun setWritingStyleEnabled(enabled: Boolean) {
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

    fun setWritingStylePreset(presetId: String) {
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

    fun setWritingStyleCustomNote(note: String) {
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

    fun clearWritingStyle() {
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

    /** 清空本机 AI 授权：清掉 AI 偏好、停止本机 AI 任务通知。 */
    fun revokeLocalConsent() {
        val token = tokenManager.getToken().orEmpty()
        val revokeOwnerUserId = tokenManager.getUserId().orEmpty()
        val mutationKey = "$revokeOwnerUserId:revoke"
        if (pendingAiMutationKey == mutationKey) return
        val revision = ++aiSettingsRevision
        writingStyleRevision++
        AiPrivacyPreferences.revoke(getApplication())
        AiWritingStylePreferences.clear(getApplication())
        pushWritingStylePrefs(false, AiWritingStylePolicy.Preset.NONE.id, "")
        // 立即停掉本地 AI 任务调度，避免撤销之后还能触发新提醒
        runCatching { AiTaskReminderScheduler.cancelAll(getApplication()) }
        if (token.isNotBlank() && revokeOwnerUserId.isNotBlank()) {
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

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }
}
