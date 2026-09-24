package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.RiskEventResponse
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.network.UpdateModerationRuleRequest
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 「内容审核」设置页的 ViewModel（G120 从 `SettingsSubViewModels.kt` 拆出，原 371 行）。
 *
 * 管风险事件列表、审核规则（开关 + 阈值）、举报处理（通过/驳回）与复核。
 * 含它的 UI 状态数据类 `ModerationUiState`。
 *
 * **拆解约束**：不直接抓应用级数据库单例；网络经 `ApiService`，凭据经 `TokenManager`。
 * 纯搬移，不改判断。
 */

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
                com.maodouchat.data.repository.ModerationNetworkRepository().adminReports(liveToken, filter).fold(
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
                com.maodouchat.data.repository.ModerationNetworkRepository().riskEvents(liveToken, needsReview = true).fold(
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
                com.maodouchat.data.repository.ModerationNetworkRepository().moderationRules(liveToken).fold(
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
            com.maodouchat.data.repository.ModerationNetworkRepository().updateModerationRule(liveToken, ruleId, UpdateModerationRuleRequest(enabled = enabled)).fold(
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
            com.maodouchat.data.repository.ModerationNetworkRepository().updateModerationRule(liveToken, ruleId, request).fold(
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
            com.maodouchat.data.repository.ModerationNetworkRepository().acknowledgeRiskEvent(liveToken, eventId).fold(
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
            com.maodouchat.data.repository.ModerationNetworkRepository().updateReportStatus(liveToken, reportId, status, note).fold(
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
            com.maodouchat.data.repository.ModerationNetworkRepository().applyReportAction(liveToken, reportId, action, note).fold(
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
