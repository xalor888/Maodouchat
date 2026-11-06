package com.maodouchat.core.model


/** 「AI 与隐私」设置页的 UI 状态。 */
data class AiPrivacySettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val userEnabled: Boolean = false,
    val effectiveEnabled: Boolean = false,
    val aiConsentAccepted: Boolean = false,
    val localSafetyEnabled: Boolean = false,
    val writingStyleEnabled: Boolean = false,
    val writingStylePresetId: String = Preset.NONE.id,
    val writingStyleCustomNote: String = "",
    val auditLogs: List<AiAuditLogResponse> = emptyList(),
    val errorMessage: String? = null,
    val infoMessage: String? = null
)
