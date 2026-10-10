package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiWritingStylePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import com.maodouchat.network.AiAuditLogResponse

/** 「AI 与隐私」设置页的 ViewModel：AI 总开关、本机授权、写作风格偏好与审计日志拉取。 */
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
    internal val writingStylePushMutex = Mutex()
    internal var writingStylePushGeneration = 0L
    internal var refreshJob: kotlinx.coroutines.Job? = null
    internal var refreshGeneration = 0L
    internal val aiMutationMutex = Mutex()
    internal var pendingAiMutationKey: String? = null
    internal var writingStyleRevision = 0L
    internal var aiSettingsRevision = 0L

    internal val _uiState = MutableStateFlow(
        AiPrivacySettingsUiState(
            userEnabled = AiPrivacyPreferences.userEnabled(application),
            effectiveEnabled = AiPrivacyPreferences.userEnabled(application) &&
                AiPrivacyPreferences.consentAccepted(application),
            aiConsentAccepted = AiPrivacyPreferences.consentAccepted(application),
            localSafetyEnabled = AiPrivacyPreferences.localSafetyEnabled(application)
        )
    )
    val uiState: StateFlow<AiPrivacySettingsUiState> = _uiState.asStateFlow()

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
}
