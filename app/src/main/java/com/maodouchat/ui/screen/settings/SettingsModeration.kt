package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.imePadding

/**
 * 「内容审核」页（G100 从 `SettingsSubScreens.kt` 拆出，原 496 行）。
 *
 * 风险事件列表、审核规则、举报处理与复核。行组件、对话框与五个本地化辅助见 `ModerationRows.kt`。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）。
 * 纯搬移，不改判断。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(
    onBack: () -> Unit = {},
    viewModel: ModerationViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedReport by remember { mutableStateOf<ReportResponse?>(null) }
    var selectedRule by remember { mutableStateOf<ModerationRuleResponse?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_moderation), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshAll, enabled = !state.isLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SecurityGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "REPORTS" to stringResource(R.string.moderation_section_reports),
                        "RISKS" to stringResource(R.string.moderation_section_risks),
                        "RULES" to stringResource(R.string.moderation_section_rules)
                    ).forEach { (section, label) ->
                        val selected = state.section == section
                        TextButton(
                            onClick = { viewModel.setSection(section) },
                            modifier = Modifier.background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                        ) {
                            Text(label, color = if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary, maxLines = 1)
                        }
                    }
                }
            }
            if (state.section == "REPORTS") {
                Spacer(modifier = Modifier.height(8.dp))
                SecurityGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        viewModel.filters.forEach { status ->
                            val selected = state.statusFilter == status
                            TextButton(
                                onClick = { viewModel.setFilter(status) },
                                modifier = Modifier.background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                            ) {
                                Text(reportStatusLabel(status), color = if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            state.errorMessage?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.infoMessage?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            SecurityGroup {
                when (state.section) {
                    "RISKS" -> {
                        if (state.riskEvents.isEmpty()) {
                            Text(stringResource(R.string.moderation_empty_risks), modifier = Modifier.fillMaxWidth().padding(16.dp), color = LocalChatPalette.current.textSecondary)
                        } else {
                            var riskSearch by rememberSaveable { mutableStateOf("") }
                            val filteredRisks = remember(state.riskEvents, riskSearch) {
                                val query = riskSearch.trim()
                                if (query.isBlank()) {
                                    state.riskEvents
                                } else {
                                    state.riskEvents.filter {
                                        it.userId.contains(query, ignoreCase = true) ||
                                            it.source.contains(query, ignoreCase = true) ||
                                            it.action.contains(query, ignoreCase = true) ||
                                            it.matched.orEmpty().contains(query, ignoreCase = true) ||
                                            it.ruleId.orEmpty().contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            if (state.riskEvents.size >= 5) {
                                OutlinedTextField(
                                    value = riskSearch,
                                    onValueChange = { riskSearch = it.take(120) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.moderation_risk_search_hint)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            if (filteredRisks.isEmpty()) {
                                Text(
                                    stringResource(R.string.moderation_risk_search_empty),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = LocalChatPalette.current.textSecondary
                                )
                            } else {
                                filteredRisks.forEachIndexed { index, event ->
                                    RiskEventRow(event = event, enabled = !state.isUpdating, onAcknowledge = { viewModel.acknowledgeRiskEvent(event.id) })
                                    if (index != filteredRisks.lastIndex) HorizontalDividerLite()
                                }
                            }
                        }
                    }
                    "RULES" -> {
                        if (state.rules.isEmpty()) {
                            Text(stringResource(R.string.moderation_empty_rules), modifier = Modifier.fillMaxWidth().padding(16.dp), color = LocalChatPalette.current.textSecondary)
                        } else {
                            var ruleSearch by rememberSaveable { mutableStateOf("") }
                            val filteredRules = remember(state.rules, ruleSearch) {
                                val query = ruleSearch.trim()
                                if (query.isBlank()) {
                                    state.rules
                                } else {
                                    state.rules.filter {
                                        it.name.contains(query, ignoreCase = true) ||
                                            it.description.orEmpty().contains(query, ignoreCase = true) ||
                                            it.scope.contains(query, ignoreCase = true) ||
                                            it.action.contains(query, ignoreCase = true) ||
                                            it.matchType.contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            if (state.rules.size >= 4) {
                                OutlinedTextField(
                                    value = ruleSearch,
                                    onValueChange = { ruleSearch = it.take(120) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.moderation_rule_search_hint)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            if (filteredRules.isEmpty()) {
                                Text(
                                    stringResource(R.string.moderation_rule_search_empty),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = LocalChatPalette.current.textSecondary
                                )
                            } else {
                                filteredRules.forEachIndexed { index, rule ->
                                    ModerationRuleRow(
                                        rule = rule,
                                        enabled = !state.isUpdating,
                                        onClick = { selectedRule = rule },
                                        onEnabledChange = { viewModel.setRuleEnabled(rule.id, it) }
                                    )
                                    if (index != filteredRules.lastIndex) HorizontalDividerLite()
                                }
                            }
                        }
                    }
                    else -> when {
                        state.isLoading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.moderation_loading_reports), color = LocalChatPalette.current.textSecondary)
                        }
                        state.reports.isEmpty() -> Text(
                            stringResource(R.string.moderation_empty_reports),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> {
                            var reportSearch by rememberSaveable { mutableStateOf("") }
                            val filteredReports = remember(state.reports, reportSearch) {
                                val query = reportSearch.trim()
                                if (query.isBlank()) {
                                    state.reports
                                } else {
                                    state.reports.filter {
                                        it.reason.contains(query, ignoreCase = true) ||
                                            it.status.contains(query, ignoreCase = true) ||
                                            it.targetType.contains(query, ignoreCase = true) ||
                                            it.targetId.contains(query, ignoreCase = true) ||
                                            it.reporterId.contains(query, ignoreCase = true) ||
                                            it.description.orEmpty().contains(query, ignoreCase = true) ||
                                            it.resolutionNote.orEmpty().contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            if (state.reports.size >= 5) {
                                OutlinedTextField(
                                    value = reportSearch,
                                    onValueChange = { reportSearch = it.take(120) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.moderation_report_search_hint)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            if (filteredReports.isEmpty()) {
                                Text(
                                    stringResource(R.string.moderation_report_search_empty),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = LocalChatPalette.current.textSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                filteredReports.forEachIndexed { index, report ->
                                    ReportModerationRow(report = report, onClick = { selectedReport = report })
                                    if (index != filteredReports.lastIndex) HorizontalDividerLite()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedReport?.let { report ->
        ReportReviewDialog(
            report = report,
            isUpdating = state.isUpdating,
            onDismiss = {
                selectedReport = null
                viewModel.clearMessages()
            },
            onUpdate = { status, note ->
                viewModel.updateReport(report.id, status, note)
                selectedReport = null
            },
            onAction = { action, note ->
                viewModel.applyReportAction(report.id, action, note)
                selectedReport = null
            }
        )
    }

    selectedRule?.let { rule ->
        ModerationRuleDialog(
            rule = rule,
            isUpdating = state.isUpdating,
            onDismiss = { selectedRule = null },
            onSave = { action, threshold, windowMinutes ->
                viewModel.updateRuleSettings(rule.id, action, threshold, windowMinutes)
                selectedRule = null
            }
        )
    }
}

/** 审计/审核时间戳 → 本地化短时间（AI 审计页与内容审核页共用）。 */
// SimpleDateFormat 非线程安全，ThreadLocal 每线程复用一个；只调 format，不残留状态。
private val auditTimeFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("M/d HH:mm", Locale.getDefault()) }

internal fun formatAuditTime(timestamp: Long): String =
    auditTimeFormat.get().format(Date(timestamp))
