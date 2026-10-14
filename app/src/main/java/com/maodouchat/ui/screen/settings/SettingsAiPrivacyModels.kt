package com.maodouchat.ui.screen.settings

import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.network.AiAuditLogResponse

/** 「AI 与隐私」设置页的 UI 状态。 */
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
