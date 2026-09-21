package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.RiskEventResponse
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.R
import com.maodouchat.ui.theme.Error
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
 * 风险事件列表、审核规则、举报处理与复核。含它专属的一批小组件
 * （`RiskEventRow` / `ModerationRuleRow` / `ModerationRuleDialog` /
 * `ReportModerationRow` / `ReportReviewDialog`）与五个本地化辅助。
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
                title = { Text(stringResource(R.string.settings_moderation), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
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

@Composable
private fun RiskEventRow(event: RiskEventResponse, enabled: Boolean, onAcknowledge: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(riskActionLabel(event.action), style = MaterialTheme.typography.bodyLarge, color = if (event.needsReview) Error else MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.moderation_risk_summary, reportTargetLabel(event.source), event.userId, formatAuditTime(event.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            event.matched?.let { Text(stringResource(R.string.moderation_matched, it), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textHint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        TextButton(onClick = onAcknowledge, enabled = enabled) { Text(stringResource(R.string.moderation_acknowledge)) }
    }
}

@Composable
private fun ModerationRuleRow(
    rule: ModerationRuleResponse,
    enabled: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                rule.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.moderation_rule_summary, reportTargetLabel(rule.scope), riskActionLabel(rule.action), rule.hitThreshold),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textHint
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onEnabledChange, enabled = enabled)
    }
}

@Composable
private fun ModerationRuleDialog(
    rule: ModerationRuleResponse,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onSave: (action: String, hitThreshold: Int, windowMinutes: Long) -> Unit
) {
    var selectedAction by rememberSaveable(rule.id) { mutableStateOf(rule.action) }
    var threshold by rememberSaveable(rule.id) { mutableStateOf(rule.hitThreshold.coerceAtLeast(1).toString()) }
    var windowMinutes by rememberSaveable(rule.id) { mutableStateOf((rule.windowMs / 60_000L).coerceAtLeast(1).toString()) }
    val actions = listOf("WARN_MOD", "AUTO_RATE_LIMIT", "AUTO_HOLD", "AUTO_DELETE")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rule.description?.let { Text(it, color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        TextButton(
                            onClick = { selectedAction = action },
                            modifier = Modifier.background(
                                if (selectedAction == action) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                        ) { Text(riskActionLabel(action), color = if (selectedAction == action) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary) }
                    }
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.moderation_hit_threshold)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = windowMinutes,
                    onValueChange = { windowMinutes = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.moderation_window_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isUpdating && threshold.toIntOrNull() != null && windowMinutes.toLongOrNull() != null,
                onClick = { onSave(selectedAction, threshold.toIntOrNull() ?: 1, windowMinutes.toLongOrNull() ?: 1L) }
            ) { Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
    )
}

@Composable
private fun ReportModerationRow(report: ReportResponse, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reportTargetLabel(report.targetType), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(reportStatusLabel(report.status), style = MaterialTheme.typography.labelMedium, color = reportStatusColor(report.status))
            }
            Text(report.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${report.targetId} · ${formatAuditTime(report.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReportReviewDialog(
    report: ReportResponse,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onUpdate: (status: String, note: String?) -> Unit,
    onAction: (action: String, note: String?) -> Unit
) {
    var note by rememberSaveable(report.id) { mutableStateOf(report.resolutionNote.orEmpty()) }
    val canDeleteContent = report.targetType in setOf("MESSAGE", "POST", "COMMENT")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moderation_report_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${reportTargetLabel(report.targetType)} · ${reportStatusLabel(report.status)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.moderation_target, report.targetId), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                report.chatId?.let { Text(stringResource(R.string.moderation_chat, it), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.moderation_reason, report.reason), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                report.description?.let { Text(stringResource(R.string.moderation_description, it), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.moderation_reporter, report.reporterId), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                report.actionTaken?.let {
                    Text(stringResource(R.string.moderation_action_taken, reportActionLabel(it)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text(stringResource(R.string.moderation_resolution_note)) },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canDeleteContent) {
                        TextButton(enabled = !isUpdating, onClick = { onAction("DELETE_CONTENT", note.trim().takeIf { it.isNotBlank() }) }) {
                            Text(stringResource(R.string.moderation_delete_content), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(enabled = !isUpdating, onClick = { onAction("RESTRICT_MESSAGES_24H", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_restrict_messages), color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !isUpdating, onClick = { onAction("RESTRICT_POSTS_7D", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_restrict_posts), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(enabled = !isUpdating, onClick = { onAction("SUSPEND_24H", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_suspend_account), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(enabled = !isUpdating, onClick = { onAction("NO_ACTION", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_no_action), color = LocalChatPalette.current.textSecondary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !isUpdating, onClick = { onUpdate("IN_REVIEW", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_mark_in_review), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(enabled = !isUpdating, onClick = { onUpdate("REJECTED", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_reject), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
    )
}

@Composable
private fun reportStatusLabel(status: String): String = when (status) {
    "OPEN" -> stringResource(R.string.moderation_status_open)
    "IN_REVIEW" -> stringResource(R.string.moderation_status_in_review)
    "RESOLVED" -> stringResource(R.string.moderation_status_resolved)
    "REJECTED" -> stringResource(R.string.moderation_status_rejected)
    "ALL" -> stringResource(R.string.moderation_status_all)
    else -> status
}

@Composable
private fun reportTargetLabel(type: String): String = when (type) {
    "USER" -> stringResource(R.string.moderation_target_user)
    "MESSAGE" -> stringResource(R.string.moderation_target_message)
    "MESSAGE_META" -> stringResource(R.string.moderation_target_message_meta)
    "POST" -> stringResource(R.string.moderation_target_post)
    "COMMENT" -> stringResource(R.string.moderation_target_comment)
    "ALL" -> stringResource(R.string.moderation_target_all_public)
    else -> type
}

@Composable
private fun riskActionLabel(action: String): String = when (action) {
    "WARN_MOD" -> stringResource(R.string.moderation_action_review)
    "AUTO_HOLD" -> stringResource(R.string.moderation_action_hold)
    "AUTO_DELETE" -> stringResource(R.string.moderation_action_delete)
    "AUTO_RATE_LIMIT" -> stringResource(R.string.moderation_action_rate_limit)
    "OBSERVED" -> stringResource(R.string.moderation_action_observed)
    else -> action
}

@Composable
private fun reportActionLabel(action: String): String = when (action) {
    "DELETE_CONTENT" -> stringResource(R.string.moderation_result_deleted)
    "NO_ACTION" -> stringResource(R.string.moderation_no_action)
    "RESTRICT_MESSAGES_24H" -> stringResource(R.string.moderation_result_messages_restricted)
    "RESTRICT_POSTS_7D" -> stringResource(R.string.moderation_result_posts_restricted)
    "SUSPEND_24H" -> stringResource(R.string.moderation_result_suspended)
    else -> action
}

@Composable
private fun reportStatusColor(status: String): Color = when (status) {
    "OPEN" -> Error
    "IN_REVIEW" -> MaterialTheme.colorScheme.primary
    "RESOLVED" -> LocalChatPalette.current.textSecondary
    "REJECTED" -> LocalChatPalette.current.textHint
    else -> LocalChatPalette.current.textSecondary
}

/** 审计/审核时间戳 → 本地化短时间（AI 审计页与内容审核页共用）。 */
internal fun formatAuditTime(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))

