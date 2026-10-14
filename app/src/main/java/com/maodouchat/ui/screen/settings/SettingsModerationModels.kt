package com.maodouchat.ui.screen.settings

import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.RiskEventResponse

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
