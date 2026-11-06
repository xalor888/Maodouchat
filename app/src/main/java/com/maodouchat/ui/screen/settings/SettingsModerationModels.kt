package com.maodouchat.ui.screen.settings

import com.maodouchat.core.model.ModerationRuleResponse
import com.maodouchat.core.model.ReportResponse
import com.maodouchat.core.model.RiskEventResponse

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
