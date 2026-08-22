package com.maodouchat.ui.screen.settings

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderPreferences
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.network.AiAuditLogResponse
import com.maodouchat.network.ApiService
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.RiskEventResponse
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.network.UpdateModerationRuleRequest
import com.maodouchat.network.ClientPrefsUpdateRequest
import com.maodouchat.network.NotificationSettingsRequest
import com.maodouchat.network.NotificationSettingsResponse
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationPreferences
import com.maodouchat.util.MediaCache
import com.maodouchat.util.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AiPrivacySettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val userEnabled: Boolean = true,
    val effectiveEnabled: Boolean = true,
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
            aiConsentAccepted = AiPrivacyPreferences.consentAccepted(application),
            localSafetyEnabled = AiPrivacyPreferences.localSafetyEnabled(application)
        )
    )
    val uiState: StateFlow<AiPrivacySettingsUiState> = _uiState.asStateFlow()

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
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
                val settingsResult = ApiService.getAiSettings(liveToken)
                val auditResult = ApiService.getAiAuditLogs(liveToken, limit = 50)
                // Pull multi-device writing-style prefs (non-secret tone hints)
                ApiService.getClientPrefs(liveToken).onSuccess { remote ->
                    if (!isCurrentOwner(ownerUserId)) return@onSuccess
                    if (refreshGeneration == generation && writingStyleRevision == styleRevisionAtStart) {
                        applyRemoteWritingStyle(remote)
                    }
                }
                if (refreshGeneration != generation || !isCurrentOwner(ownerUserId)) {
                    return@launch
                }
                val settings = settingsResult.getOrNull()
                val style = loadWritingStyleSnapshot()
                _uiState.update { state ->
                    val settingsAreCurrent = aiSettingsRevision == aiSettingsRevisionAtStart
                    state.copy(
                        isLoading = false,
                        userEnabled = if (settingsAreCurrent) settings?.userEnabled ?: state.userEnabled else state.userEnabled,
                        effectiveEnabled = if (settingsAreCurrent) settings?.effectiveEnabled ?: state.effectiveEnabled else state.effectiveEnabled,
                        writingStyleEnabled = style.enabled,
                        writingStylePresetId = style.preset.id,
                        writingStyleCustomNote = style.customNote,
                        auditLogs = auditResult.getOrNull() ?: state.auditLogs,
                        errorMessage = (if (settingsAreCurrent) settingsResult.exceptionOrNull()?.message else null)
                            ?: auditResult.exceptionOrNull()?.message
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
                    ApiService.putClientPrefs(
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
                    val liveToken = tokenManager.getToken() ?: token
                    ApiService.updateAiSettings(liveToken, chatId = null, enabled = enabled).fold(
                        onSuccess = { settings ->
                            if (aiSettingsRevision != revision || !isCurrentOwner(ownerUserId)) {
                                return@fold
                            }
                            pendingAiMutationKey = null
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    userEnabled = settings.userEnabled,
                                    effectiveEnabled = settings.effectiveEnabled,
                                    infoMessage = if (settings.userEnabled) text(R.string.ai_privacy_global_enabled) else text(R.string.ai_privacy_global_disabled)
                                )
                            }
                        },
                        onFailure = { error ->
                            if (aiSettingsRevision == revision && isCurrentOwner(ownerUserId)) {
                                pendingAiMutationKey = null
                                _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: text(R.string.ai_privacy_save_failed)) }
                            }
                        }
                    )
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
                        val liveToken = tokenManager.getToken() ?: token
                        ApiService.updateAiSettings(liveToken, chatId = null, enabled = false).fold(
                            onSuccess = {
                                if (aiSettingsRevision != revision || !isCurrentOwner(revokeOwnerUserId)) {
                                    return@fold
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
                            },
                            onFailure = { error ->
                                if (aiSettingsRevision != revision || !isCurrentOwner(revokeOwnerUserId)) {
                                    return@fold
                                }
                                pendingAiMutationKey = null
                                // Local revoke already applied; remote disable failure is still visible.
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
                                        infoMessage = text(R.string.ai_privacy_reset_done)
                                    )
                                }
                            }
                        )
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

data class ModerationUiState(
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val section: String = "REPORTS",
    val statusFilter: String = "OPEN",
    val reports: List<ReportResponse> = emptyList(),
    val riskEvents: List<RiskEventResponse> = emptyList(),
    val rules: List<ModerationRuleResponse> = emptyList(),
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class ModerationViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager.getInstance(application)
    private var reportsGeneration = 0L
    private var reportsJob: kotlinx.coroutines.Job? = null
    private var riskEventsGeneration = 0L
    private var riskEventsJob: kotlinx.coroutines.Job? = null
    private var rulesGeneration = 0L
    private var rulesJob: kotlinx.coroutines.Job? = null
    private val mutationMutex = Mutex()
    private val _uiState = MutableStateFlow(ModerationUiState())
    val uiState: StateFlow<ModerationUiState> = _uiState.asStateFlow()

    val filters = listOf("OPEN", "IN_REVIEW", "RESOLVED", "REJECTED", "ALL")

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    private fun launchMutation(
        fallbackErrorId: Int,
        block: suspend (liveToken: String, ownerUserId: String) -> Unit,
    ) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (!mutationMutex.tryLock()) return
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                _uiState.update { it.copy(isUpdating = true, errorMessage = null, infoMessage = null) }
                val liveToken = tokenManager.getToken() ?: token
                block(liveToken, ownerUserId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isUpdating = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            errorMessage = error.message ?: text(fallbackErrorId),
                        )
                    }
                }
            }
        }
        job.invokeOnCompletion { mutationMutex.unlock() }
    }

    init {
        refreshAll()
    }

    fun setSection(section: String) {
        if (section !in setOf("REPORTS", "RISKS", "RULES")) return
        _uiState.update { it.copy(section = section) }
    }

    fun refreshAll() {
        loadReports()
        loadRiskEvents()
        loadRules()
    }

    fun setFilter(status: String) {
        if (status !in filters) return
        _uiState.update { it.copy(statusFilter = status) }
        loadReports()
    }

    fun loadReports() {
        val generation = ++reportsGeneration
        reportsJob?.cancel()
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            if (reportsGeneration == generation) {
                _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired)) }
            }
            return
        }
        val filter = _uiState.value.statusFilter
        if (!isCurrentOwner(ownerUserId)) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val job = viewModelScope.launch {
            try {
                if (!isCurrentOwner(ownerUserId)) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getAdminReports(liveToken, filter).fold(
                    onSuccess = { reports ->
                        if (reportsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isLoading = false, reports = reports) }
                        }
                    },
                    onFailure = { error ->
                        if (reportsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.moderation_reports_load_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (reportsGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (reportsGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: text(R.string.moderation_reports_load_failed),
                        )
                    }
                }
            }
        }
        reportsJob = job
        job.invokeOnCompletion {
            if (reportsJob === job) reportsJob = null
        }
    }

    fun loadRiskEvents() {
        val generation = ++riskEventsGeneration
        riskEventsJob?.cancel()
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            if (riskEventsGeneration == generation) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            }
            return
        }
        val job = viewModelScope.launch {
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getRiskEvents(liveToken, needsReview = true).fold(
                    onSuccess = { events ->
                        if (riskEventsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(riskEvents = events) }
                        }
                    },
                    onFailure = { error ->
                        if (riskEventsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.moderation_risks_load_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (riskEventsGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: text(R.string.moderation_risks_load_failed))
                    }
                }
            }
        }
        riskEventsJob = job
        job.invokeOnCompletion {
            if (riskEventsJob === job) riskEventsJob = null
        }
    }

    fun loadRules() {
        val generation = ++rulesGeneration
        rulesJob?.cancel()
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            if (rulesGeneration == generation) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            }
            return
        }
        val job = viewModelScope.launch {
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getModerationRules(liveToken).fold(
                    onSuccess = { rules ->
                        if (rulesGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(rules = rules) }
                        }
                    },
                    onFailure = { error ->
                        if (rulesGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.moderation_rules_load_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (rulesGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: text(R.string.moderation_rules_load_failed))
                    }
                }
            }
        }
        rulesJob = job
        job.invokeOnCompletion {
            if (rulesJob === job) rulesJob = null
        }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        rulesGeneration++
        rulesJob?.cancel()
        launchMutation(R.string.moderation_rule_update_failed) { liveToken, ownerUserId ->
            ApiService.updateModerationRule(liveToken, ruleId, UpdateModerationRuleRequest(enabled = enabled)).fold(
                onSuccess = { updated ->
                    if (!isCurrentOwner(ownerUserId)) return@fold
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            rules = it.rules.map { rule -> if (rule.id == updated.id) updated else rule },
                            infoMessage = if (enabled) text(R.string.moderation_rule_enabled) else text(R.string.moderation_rule_disabled)
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isUpdating = false, errorMessage = error.message ?: text(R.string.moderation_rule_update_failed)) }
                    }
                }
            )
        }
    }

    fun updateRuleSettings(ruleId: String, action: String, hitThreshold: Int, windowMinutes: Long) {
        val request = UpdateModerationRuleRequest(
            action = action,
            hitThreshold = hitThreshold.coerceIn(1, 10_000),
            windowMs = windowMinutes.coerceIn(1, 43_200) * 60_000L
        )
        rulesGeneration++
        rulesJob?.cancel()
        launchMutation(R.string.moderation_rule_save_failed) { liveToken, ownerUserId ->
            ApiService.updateModerationRule(liveToken, ruleId, request).fold(
                onSuccess = { updated ->
                    if (!isCurrentOwner(ownerUserId)) return@fold
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            rules = it.rules.map { rule -> if (rule.id == updated.id) updated else rule },
                            infoMessage = text(R.string.moderation_rule_saved)
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isUpdating = false, errorMessage = error.message ?: text(R.string.moderation_rule_save_failed)) }
                    }
                }
            )
        }
    }

    fun acknowledgeRiskEvent(eventId: String) {
        riskEventsGeneration++
        riskEventsJob?.cancel()
        launchMutation(R.string.moderation_acknowledge_failed) { liveToken, ownerUserId ->
            ApiService.acknowledgeRiskEvent(liveToken, eventId).fold(
                onSuccess = {
                    if (!isCurrentOwner(ownerUserId)) return@fold
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            riskEvents = it.riskEvents.filterNot { event -> event.id == eventId },
                            infoMessage = text(R.string.moderation_risk_acknowledged)
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isUpdating = false, errorMessage = error.message ?: text(R.string.moderation_acknowledge_failed)) }
                    }
                }
            )
        }
    }

    fun updateReport(reportId: String, status: String, note: String? = null) {
        reportsGeneration++
        reportsJob?.cancel()
        _uiState.update { it.copy(isLoading = false) }
        launchMutation(R.string.moderation_status_update_failed) { liveToken, ownerUserId ->
            ApiService.updateReportStatus(liveToken, reportId, status, note).fold(
                onSuccess = { updated ->
                    if (!isCurrentOwner(ownerUserId)) return@fold
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            reports = it.reports.map { report -> if (report.id == updated.id) updated else report }
                                .filter { report -> it.statusFilter == "ALL" || report.status == it.statusFilter },
                            infoMessage = text(R.string.moderation_report_updated)
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isUpdating = false, errorMessage = error.message ?: text(R.string.moderation_status_update_failed)) }
                    }
                }
            )
        }
    }

    fun applyReportAction(reportId: String, action: String, note: String? = null) {
        reportsGeneration++
        reportsJob?.cancel()
        _uiState.update { it.copy(isLoading = false) }
        launchMutation(R.string.moderation_action_failed) { liveToken, ownerUserId ->
            ApiService.applyReportAction(liveToken, reportId, action, note).fold(
                onSuccess = { updated ->
                    if (!isCurrentOwner(ownerUserId)) return@fold
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            reports = it.reports.map { report -> if (report.id == updated.id) updated else report }
                                .filter { report -> it.statusFilter == "ALL" || report.status == it.statusFilter },
                            infoMessage = when (action) {
                                "DELETE_CONTENT" -> text(R.string.moderation_content_deleted)
                                "RESTRICT_MESSAGES_24H" -> text(R.string.moderation_messages_restricted)
                                "RESTRICT_POSTS_7D" -> text(R.string.moderation_posts_restricted)
                                "SUSPEND_24H" -> text(R.string.moderation_user_suspended)
                                else -> text(R.string.moderation_report_closed)
                            }
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isUpdating = false, errorMessage = error.message ?: text(R.string.moderation_action_failed)) }
                    }
                }
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }
}

data class NotificationSettingsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val enableNotifications: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val previewEnabled: Boolean = true,
    val ringtoneEnabled: Boolean = true,
    val taskRemindersEnabled: Boolean = true,
    val dndStartHour: Int = 22,     // 22:00-07:00
    val dndEndHour: Int = 7,
    /** 勿扰计划：显式开关 + 分钟级窗口（0-1439），默认 22:00-07:00 未启用。 */
    val dndEnabled: Boolean = false,
    val dndStartMinute: Int = 22 * 60,
    val dndEndMinute: Int = 7 * 60,
    /** FCM: config present in BuildConfig / process initialized. */
    val pushConfigured: Boolean = false,
    val pushReady: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class NotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val tokenManager = TokenManager.getInstance(application)
    private val syncMutex = Mutex()
    private var settingsRevision = 0L
    private var lastSyncedRevision = 0L
    private var syncGeneration = 0L
    private var refreshGeneration = 0L
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var revisionOwnerUserId: String? = tokenManager.getUserId()

    private val _uiState = MutableStateFlow(NotificationSettingsUiState(
        enableNotifications = NotificationPreferences.notificationsEnabled(application),
        soundEnabled = NotificationPreferences.soundEnabled(application),
        vibrationEnabled = NotificationPreferences.vibrationEnabled(application),
        previewEnabled = NotificationPreferences.previewEnabled(application),
        ringtoneEnabled = NotificationPreferences.ringtoneEnabled(application),
        taskRemindersEnabled = NotificationPreferences.taskRemindersEnabled(application),
        dndStartHour = NotificationPreferences.dndStartHour(application),
        dndEndHour = NotificationPreferences.dndEndHour(application),
        dndEnabled = NotificationPreferences.dndEnabled(application),
        dndStartMinute = NotificationPreferences.dndStartMinute(application),
        dndEndMinute = NotificationPreferences.dndEndMinute(application),
        pushConfigured = com.maodouchat.push.PushRegistrationManager.hasFirebaseConfiguration(),
        pushReady = com.maodouchat.push.PushRegistrationManager.isConfigured()
    ))
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    // 8.34 修复：sync 失败持久化标记——上次会话有未同步的本地修改时，新 ViewModel 的
    // 0/0 计数会令 refresh 把服务端旧值静默覆盖本地新值（设置无声回滚）。置 revision 使
    // refresh 的「无本地变更」守卫失效，保留本地值直至下次成功 sync 清除标记。
    init {
        if (NotificationPreferences.hasPendingSync(application)) {
            settingsRevision = 1L
            lastSyncedRevision = 0L
        }
    }

    private fun adoptRevisionOwner(ownerUserId: String) {
        if (revisionOwnerUserId == ownerUserId) return
        revisionOwnerUserId = ownerUserId
        settingsRevision = 0L
        lastSyncedRevision = 0L
        syncGeneration++
    }

    init {
        refresh()
    }

    fun refreshPushStatus() {
        _uiState.update {
            it.copy(
                pushConfigured = com.maodouchat.push.PushRegistrationManager.hasFirebaseConfiguration(),
                pushReady = com.maodouchat.push.PushRegistrationManager.isConfigured()
            )
        }
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        refreshPushStatus()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
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
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getNotificationSettings(liveToken).fold(
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
                                    pushConfigured = com.maodouchat.push.PushRegistrationManager.hasFirebaseConfiguration(),
                                    pushReady = com.maodouchat.push.PushRegistrationManager.isConfigured(),
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

    fun setEnableNotifications(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.PUSH_NOTIFICATIONS)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.push_notifications_disabled)) }
            return
        }
        if (_uiState.value.enableNotifications == value) return
        updateLocal { it.copy(enableNotifications = value) }
        updateReminderScheduling(value)
        if (!value) dismissPostedNotifications()
        sync()
    }

    fun setSoundEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_SOUND)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.notification_sound_disabled)) }
            return
        }
        if (_uiState.value.soundEnabled == value) return
        updateLocal { it.copy(soundEnabled = value) }
        sync()
    }

    fun setPreviewEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_PREVIEW)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.notification_preview_disabled)) }
            return
        }
        if (_uiState.value.previewEnabled == value) return
        updateLocal { it.copy(previewEnabled = value) }
        sync()
    }

    // 1.133：震动开关（渠道级，改动后重建渠道）
    fun setVibrationEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (_uiState.value.vibrationEnabled == value) return
        updateLocal { it.copy(vibrationEnabled = value) }
        com.maodouchat.notification.NotificationPreferences.setVibrationEnabled(context, value)
        com.maodouchat.util.AppNotifier.ensureChannels(context)
        sync()
    }

    fun setRingtoneEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.RINGTONE)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.ringtone_disabled)) }
            return
        }
        if (_uiState.value.ringtoneEnabled == value) return
        updateLocal { it.copy(ringtoneEnabled = value) }
        sync()
    }

    fun setTaskRemindersEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.TASK_REMINDERS)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.task_reminders_disabled)) }
            return
        }
        if (_uiState.value.taskRemindersEnabled == value) return
        _uiState.update {
            it.copy(
                taskRemindersEnabled = value,
                errorMessage = null,
                infoMessage = null,
            )
        }
        saveLocal(_uiState.value)
        AiTaskReminderPreferences.setTaskRemindersEnabled(app, value)
        updateReminderScheduling(_uiState.value.enableNotifications)
        // Turning reminders off must drop already-posted AI trays (WorkManager cancel
        // alone leaves shade entries until user swipes). Center AI rows stay for history;
        // tray cancel matches open-AiTasks / leave-chat cleanup.
        if (!value) {
            com.maodouchat.util.AppNotifier.cancelAllAiTaskReminders(app)
        }
        _uiState.update { it.copy(infoMessage = text(R.string.notifications_task_reminders_saved)) }
    }

    /**
     * 设置勿扰计划：显式开关 + 分钟级窗口（0-1439）。跨天窗口 start>end 表示夜间勿扰。
     * 关闭计划（enabled=false）时窗口数据仍保留，便于下次一键恢复。
     */
    fun setDndSchedule(enabled: Boolean, startMinute: Int, endMinute: Int) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.DND)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.dnd_disabled)) }
            return
        }
        val safeStart = startMinute.coerceIn(0, 1439)
        val safeEnd = endMinute.coerceIn(0, 1439)
        if (_uiState.value.dndEnabled == enabled &&
            _uiState.value.dndStartMinute == safeStart &&
            _uiState.value.dndEndMinute == safeEnd
        ) {
            return
        }
        updateLocal {
            it.copy(
                dndEnabled = enabled,
                dndStartMinute = safeStart,
                dndEndMinute = safeEnd,
                dndStartHour = safeStart / 60,
                dndEndHour = safeEnd / 60,
            )
        }
        AiTaskReminderScheduler.requestReconciliation(app, force = true)
        sync()
    }

    fun isDndActive(): Boolean {
        // UI badge: only when notifications are on, schedule enabled and current time inside DND.
        // Minute math must match FCM + list WS via LocalNotificationSuppressPolicy.
        if (!_uiState.value.enableNotifications) return false
        val context = getApplication<Application>()
        val now = java.util.Calendar.getInstance()
        return com.maodouchat.notification.LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = true,
            dndStartHour = _uiState.value.dndStartHour,
            dndEndHour = _uiState.value.dndEndHour,
            hourOfDay = now.get(java.util.Calendar.HOUR_OF_DAY),
            dndRuntimeEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.DND),
            dndEnabled = _uiState.value.dndEnabled,
            startMinute = _uiState.value.dndStartMinute,
            endMinute = _uiState.value.dndEndMinute,
            currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE),
        )
    }

    private fun updateLocal(transform: (NotificationSettingsUiState) -> NotificationSettingsUiState) {
        tokenManager.getUserId()?.takeIf { it.isNotBlank() }?.let(::adoptRevisionOwner)
        refreshGeneration++
        refreshJob?.cancel()
        settingsRevision++
        _uiState.update { current ->
            transform(current).copy(isLoading = false, errorMessage = null, infoMessage = null)
        }
        saveLocal(_uiState.value)
    }

    private fun sync() {
        val token = tokenManager.getToken()
        val syncOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || syncOwnerUserId.isBlank()) {
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
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
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
                    val liveToken = tokenManager.getToken() ?: token
                    ApiService.updateNotificationSettings(liveToken, request).fold(
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

    private fun saveLocal(state: NotificationSettingsUiState) {
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

    private fun updateReminderScheduling(notificationsEnabled: Boolean) {
        if (notificationsEnabled && _uiState.value.taskRemindersEnabled) {
            AiTaskReminderScheduler.ensureScheduled(app)
        } else {
            AiTaskReminderScheduler.cancelAll(app)
        }
    }

    /**
     * Global quiet mode: drop already-posted tray + AI reminders and clear
     * center unread (parity with per-chat mute-on). Safe to call on remote
     * pull when another device / account settings disabled notifications.
     */
    private fun dismissPostedNotifications() {
        com.maodouchat.util.AppNotifier.cancelAll(app)
        com.maodouchat.util.AppNotifier.cancelAllAiTaskReminders(app)
        (app as? com.maodouchat.MaodouchatApp)?.notificationCenter?.markAllRead()
    }

    private fun saveLocal(response: NotificationSettingsResponse) {
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
}

data class GeneralSettingsUiState(
    val themeMode: String = "system", // system / light / dark
    val themeStyle: String = "maodou", // maodou / tg_classic / tg_midnight / tg_graphite
    val accentColor: String = "none", // none / blue / green / purple / orange / pink / red / teal
    val languageMode: String = AppLocaleManager.MODE_SYSTEM,
    val linkPreviewEnabled: Boolean = true,
    val unreadPriorityEnabled: Boolean = true,
    val mediaAutoDownloadMode: String = com.maodouchat.util.MediaAutoDownloadPreferences.MODE_WIFI_ONLY,
    val chatWallpaper: String = com.maodouchat.util.ChatWallpaperPreset.DEFAULT.id,
    val chatFontScale: String = com.maodouchat.util.ChatFontScale.NORMAL.id,
    val cacheSizeText: String = "0 KB",
    val infoMessage: String? = null
)

class GeneralSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("general_settings", Application.MODE_PRIVATE)
    private val tokenManager = TokenManager.getInstance(application)
    private val clientPrefsPushMutex = Mutex()
    private var prefsRevision = 0L
    private var clientPrefsPullGeneration = 0L
    private var clientPrefsPullJob: kotlinx.coroutines.Job? = null
    private var clientPrefsPushGeneration = 0L
    private var cacheRefreshGeneration = 0L
    private var cacheRefreshJob: kotlinx.coroutines.Job? = null
    private var clearCacheJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(
        GeneralSettingsUiState(
            themeMode = prefs.getString(KEY_THEME, "system") ?: "system",
            themeStyle = com.maodouchat.util.ThemePreferences.getStyle(application),
            accentColor = com.maodouchat.util.ThemePreferences.getAccent(application),
            languageMode = AppLocaleManager.getMode(application),
            linkPreviewEnabled = com.maodouchat.util.LinkPreviewPreferences.isEnabled(application),
            unreadPriorityEnabled = com.maodouchat.util.UnreadPriorityPreferences.isEnabled(application),
            mediaAutoDownloadMode = com.maodouchat.util.MediaAutoDownloadPreferences.getMode(application),
            chatWallpaper = com.maodouchat.util.ChatAppearancePreferences.getWallpaper(application).id,
            chatFontScale = com.maodouchat.util.ChatAppearancePreferences.getFontScale(application).id
        )
    )
    val uiState: StateFlow<GeneralSettingsUiState> = _uiState.asStateFlow()

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    init {
        refreshCacheSize()
        pullClientPrefsFromCloud()
    }

    fun setThemeMode(mode: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.ThemePreferences.normalize(mode)
        if (_uiState.value.themeMode == normalized) return
        prefsRevision++
        com.maodouchat.util.ThemePreferences.setMode(context, normalized)
        prefs.edit().putString(KEY_THEME, normalized).apply()
        _uiState.update { it.copy(themeMode = normalized) }
        pushClientPrefs()
    }

    /** 主题风格家族（含 TG 1:1 还原主题），随客户端偏好云同步。 */
    fun setThemeStyle(style: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.ThemePreferences.normalizeStyle(style)
        if (_uiState.value.themeStyle == normalized) return
        prefsRevision++
        com.maodouchat.util.ThemePreferences.setStyle(context, normalized)
        _uiState.update { it.copy(themeStyle = normalized) }
        pushClientPrefs()
    }

    /** 自定义强调色（TG 式），随客户端偏好云同步。 */
    fun setAccentColor(accentId: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.ThemePreferences.normalizeAccent(accentId)
        if (_uiState.value.accentColor == normalized) return
        prefsRevision++
        com.maodouchat.util.ThemePreferences.setAccent(context, normalized)
        _uiState.update { it.copy(accentColor = normalized) }
        pushClientPrefs()
    }

    fun setLanguageMode(mode: String) {
        val context = getApplication<Application>()
        val normalized = when (mode.lowercase()) {
            AppLocaleManager.MODE_CHINESE -> AppLocaleManager.MODE_CHINESE
            AppLocaleManager.MODE_ENGLISH -> AppLocaleManager.MODE_ENGLISH
            else -> AppLocaleManager.MODE_SYSTEM
        }
        if (_uiState.value.languageMode == normalized) return
        prefsRevision++
        AppLocaleManager.setMode(context, normalized)
        _uiState.update { it.copy(languageMode = normalized) }
        pushClientPrefs()
    }

    fun setLinkPreviewEnabled(enabled: Boolean) {
        if (_uiState.value.linkPreviewEnabled == enabled) return
        prefsRevision++
        val context = getApplication<Application>()
        com.maodouchat.util.LinkPreviewPreferences.setEnabled(context, enabled)
        if (!enabled) {
            com.maodouchat.util.LinkPreviewRepository.clear()
        }
        _uiState.update { it.copy(linkPreviewEnabled = enabled) }
        pushClientPrefs()
    }

    fun setUnreadPriorityEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.UNREAD_PRIORITY)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.unread_priority_disabled)) }
            return
        }
        if (_uiState.value.unreadPriorityEnabled == enabled) return
        prefsRevision++
        com.maodouchat.util.UnreadPriorityPreferences.setEnabled(context, enabled)
        _uiState.update { it.copy(unreadPriorityEnabled = enabled) }
        pushClientPrefs()
    }

    fun setChatWallpaper(presetId: String) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_WALLPAPER)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.chat_wallpaper_disabled)) }
            return
        }
        val preset = com.maodouchat.util.ChatAppearancePolicy.normalizeWallpaper(presetId)
        if (_uiState.value.chatWallpaper == preset.id) return
        prefsRevision++
        com.maodouchat.util.ChatAppearancePreferences.setWallpaper(context, preset)
        _uiState.update { it.copy(chatWallpaper = preset.id) }
        pushClientPrefs()
    }

    fun setChatFontScale(scaleId: String) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_FONT_SCALE)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.chat_font_scale_disabled)) }
            return
        }
        val scale = com.maodouchat.util.ChatAppearancePolicy.normalizeFontScale(scaleId)
        if (_uiState.value.chatFontScale == scale.id) return
        prefsRevision++
        com.maodouchat.util.ChatAppearancePreferences.setFontScale(context, scale)
        _uiState.update { it.copy(chatFontScale = scale.id) }
        pushClientPrefs()
    }

    fun setMediaAutoDownloadMode(mode: String) {
        val context = getApplication<Application>()
        val normalized = com.maodouchat.util.MediaAutoDownloadPreferences.normalizeForWrite(mode)
        if (_uiState.value.mediaAutoDownloadMode == normalized) return
        prefsRevision++
        com.maodouchat.util.MediaAutoDownloadPreferences.setMode(context, normalized)
        _uiState.update { it.copy(mediaAutoDownloadMode = normalized) }
        pushClientPrefs()
    }

    private fun pullClientPrefsFromCloud() {
        val generation = ++clientPrefsPullGeneration
        clientPrefsPullJob?.cancel()
        val revisionAtStart = prefsRevision
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        val job = viewModelScope.launch {
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.getClientPrefs(liveToken).onSuccess { remote ->
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

    private fun applyRemotePrefs(remote: com.maodouchat.network.ClientPrefsDto) {
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

    private fun pushClientPrefs() {
        val generation = ++clientPrefsPushGeneration
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
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
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    ApiService.putClientPrefs(liveToken, request).onFailure { error ->
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

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache() {
        if (clearCacheJob?.isActive == true) return
        cacheRefreshGeneration++
        cacheRefreshJob?.cancel()
        val generation = cacheRefreshGeneration
        val job = viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val removedBytes = withContext(Dispatchers.IO) {
                    val before = MediaCache.currentCacheBytes(context)
                    AttachmentTransferCoordinator.deleteAll(context)
                    MediaCache.cleanupReturningBytes(context)
                    com.maodouchat.util.LinkPreviewRepository.clear()
                    // 1.35：清除 Coil 图片磁盘缓存（内存缓存在低内存时已清，这里补磁盘）
                    runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
                    (before - MediaCache.currentCacheBytes(context)).coerceAtLeast(0L)
                }
                if (cacheRefreshGeneration != generation) return@launch
                val text = formatSize(removedBytes)
                _uiState.update {
                    it.copy(
                        cacheSizeText = text,
                        infoMessage = context.getString(R.string.general_cache_cleared, text),
                    )
                }
                refreshCacheSize()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (cacheRefreshGeneration == generation) {
                    _uiState.update {
                        it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            }
        }
        clearCacheJob = job
        job.invokeOnCompletion {
            if (clearCacheJob === job) clearCacheJob = null
        }
    }

    fun showComingSoon() { _uiState.update { it.copy(infoMessage = getApplication<Application>().getString(R.string.general_about_summary)) } }

    private fun refreshCacheSize() {
        val generation = ++cacheRefreshGeneration
        cacheRefreshJob?.cancel()
        val job = viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val bytes = withContext(Dispatchers.IO) { MediaCache.currentCacheBytes(context) }
                if (cacheRefreshGeneration == generation) {
                    _uiState.update { it.copy(cacheSizeText = formatSize(bytes)) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (cacheRefreshGeneration == generation) {
                    _uiState.update {
                        it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            }
        }
        cacheRefreshJob = job
        job.invokeOnCompletion {
            if (cacheRefreshJob === job) cacheRefreshJob = null
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024L * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024L * 1024 * 1024) return "${bytes / (1024L * 1024)} MB"
        return "${bytes / (1024L * 1024 * 1024)} GB"
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}

/**
 * 改密码的成功回调扩展；扩展方法已在此 VM 上声明
 */
private val passwordChangeMutex = Mutex()

fun SettingsViewModel.changePassword(old: String, new: String, confirm: String, onSuccess: () -> Unit) {
    if (!passwordChangeMutex.tryLock()) return
    if (old.isBlank()) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_enter_old_password)) }
        return
    }
    if (new.length < 6) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_new_password_length)) }
        return
    }
    if (new != confirm) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_password_mismatch)) }
        return
    }
    val ownerUserId = tokenManager.getUserId().orEmpty()
    val token = tokenManager.getToken()
    if (token.isNullOrBlank() || ownerUserId.isBlank()) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
        return
    }
    while (true) {
        val state = _uiState.value
        if (state.isSaving) {
            passwordChangeMutex.unlock()
            return
        }
        if (_uiState.compareAndSet(state, state.copy(isSaving = true, errorMessage = null))) break
    }
    val job = viewModelScope.launch {
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                // 8.38：门禁失败需复位 isSaving，否则弹窗转圈且无法关闭
                _uiState.update { it.copy(isSaving = false, errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.changePassword(liveToken, old, new).fold(
                onSuccess = {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        // 8.38：成功路径二次门禁失败也需复位，否则弹窗无法关闭
                        _uiState.update { it.copy(isSaving = false) }
                        return@fold
                    }
                    // 服务端已 revoke 全部 token + 断 WS；本地必须立刻清会话，
                    // 否则下一次 401→refresh 失败会走 tokenExpired 并 destroyEncryptedDatabase。
                    val purged = withContext(kotlinx.coroutines.NonCancellable) {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@withContext false
                        }
                        com.maodouchat.network.WebSocketClient.disconnect()
                        // 9.140：带账号归属校验 purge——此前无 expectedOwnerUserId，
                        // 断连窗口内换号会把新账号的会话一并清掉
                        (getApplication() as com.maodouchat.MaodouchatApp).secureSessionManager
                            .purgeLocalSession(expectedOwnerUserId = ownerUserId)
                    }
                    if (!purged) {
                        _uiState.update { it.copy(isSaving = false) }
                        return@fold
                    }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isLoggedOut = true,
                            successMessage = text(R.string.settings_password_changed)
                        )
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: text(R.string.settings_password_change_failed)) }
                    }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { it.copy(isSaving = false) }
            }
            throw error
        } catch (error: Throwable) {
            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: text(R.string.settings_password_change_failed),
                    )
                }
            }
        }
    }
    job.invokeOnCompletion { passwordChangeMutex.unlock() }
}
