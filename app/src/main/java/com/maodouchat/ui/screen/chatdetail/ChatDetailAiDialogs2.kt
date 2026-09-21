package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 聊天内 AI 功能的对话框一族（G106 从 `ChatDetailComponents.kt` 拆出，原 805 行）。
 *
 * 覆盖图片/文件分析、摘要、周报、消息分类、群 AI 助手等入口的对话框与结果展示。
 * 含两个纯文本辅助（`profileText` / `classifyText`）与一个 `IdentityTrustState.toLabel()` 扩展。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

@Composable
internal fun AiImageAnalysisModeDialog(
    onSelect: (AiImageAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = AiImageAnalysisMode.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_image_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                modes.forEach { mode ->
                    val icon = when (mode) {
                        AiImageAnalysisMode.DESCRIBE -> Icons.Outlined.Image
                        AiImageAnalysisMode.OCR -> Icons.Outlined.Search
                        AiImageAnalysisMode.SAFETY -> Icons.Outlined.Security
                    }
                    val description = stringResource(when (mode) {
                        AiImageAnalysisMode.DESCRIBE -> R.string.chat_ai_image_mode_describe_description
                        AiImageAnalysisMode.OCR -> R.string.chat_ai_image_mode_ocr_description
                        AiImageAnalysisMode.SAFETY -> R.string.chat_ai_image_mode_safety_description
                    })
                    TextButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.localizedLabel(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_image_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun AiImageAnalysisResultDialog(
    result: String,
    mode: AiImageAnalysisMode,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_image_result_title))
                }
                Text(mode.localizedLabel(), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_image_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_copy)) } }
    )
}

@Composable
internal fun AiFileAnalysisModeDialog(
    fileName: String,
    onSelect: (AiFileAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_file_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fileName.isNotBlank()) {
                    Text(fileName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                }
                AiFileAnalysisMode.entries.forEach { mode ->
                    val icon = if (mode == AiFileAnalysisMode.SUMMARIZE) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.HelpOutline
                    val description = stringResource(if (mode == AiFileAnalysisMode.SUMMARIZE) R.string.chat_ai_file_mode_summarize_description else R.string.chat_ai_file_mode_question_description)
                    TextButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.localizedLabel(), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                            Text(description, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_file_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun AiFileQuestionDialog(
    fileName: String,
    question: String,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_ai_file_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fileName.isNotBlank()) {
                    Text(fileName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text(stringResource(R.string.chat_ai_file_question_placeholder)) },
                    supportingText = { Text("${question.length}/500") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = question.isNotBlank()) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun AiFileAnalysisResultDialog(
    result: String,
    fileName: String,
    mode: AiFileAnalysisMode,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_file_result_title))
                }
                Text(
                    listOf(fileName.takeIf(String::isNotBlank), mode.localizedLabel()).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(stringResource(R.string.chat_ai_file_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_copy)) } }
    )
}

@Composable
internal fun AiSummaryScopeDialog(
    searchResultCount: Int,
    onDismiss: () -> Unit,
    onSelect: (AiSummaryScope, String) -> Unit
) {
    var selectedStyle by rememberSaveable { mutableStateOf("brief") }
    val scopes = listOf(
        AiSummaryScope.RECENT,
        AiSummaryScope.TODAY,
        AiSummaryScope.SEVEN_DAYS,
        AiSummaryScope.THIRTY_DAYS,
        AiSummaryScope.SEARCH_RESULTS
    )
    val styleOptions = listOf(
        "brief" to R.string.chat_ai_summary_style_brief,
        "detailed" to R.string.chat_ai_summary_style_detailed,
        "decisions" to R.string.chat_ai_summary_style_decisions,
        "tasks" to R.string.chat_ai_summary_style_tasks,
        "timeline" to R.string.chat_ai_summary_style_timeline,
        "risks" to R.string.chat_ai_summary_style_risks
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_summary_scope_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.chat_ai_summary_style_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styleOptions.forEach { (styleId, labelRes) ->
                        FilterChip(
                            selected = selectedStyle == styleId,
                            onClick = { selectedStyle = styleId },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                scopes.forEach { scope ->
                    val enabled = scope != AiSummaryScope.SEARCH_RESULTS || searchResultCount > 0
                    val icon = when (scope) {
                        AiSummaryScope.RECENT -> Icons.Outlined.History
                        AiSummaryScope.TODAY -> Icons.Outlined.Today
                        AiSummaryScope.SEVEN_DAYS -> Icons.Outlined.DateRange
                        AiSummaryScope.THIRTY_DAYS -> Icons.Outlined.DateRange
                        AiSummaryScope.SEARCH_RESULTS -> Icons.Outlined.Search
                        AiSummaryScope.UNREAD -> Icons.Outlined.History
                    }
                    val description = when (scope) {
                        AiSummaryScope.RECENT -> stringResource(R.string.chat_ai_summary_scope_recent_description)
                        AiSummaryScope.TODAY -> stringResource(R.string.chat_ai_summary_scope_today_description)
                        AiSummaryScope.SEVEN_DAYS -> stringResource(R.string.chat_ai_summary_scope_week_description)
                        AiSummaryScope.THIRTY_DAYS -> stringResource(R.string.chat_ai_summary_scope_month_description)
                        AiSummaryScope.SEARCH_RESULTS -> if (searchResultCount > 0) {
                            stringResource(R.string.chat_ai_summary_scope_search_count, searchResultCount)
                        } else {
                            stringResource(R.string.chat_ai_summary_scope_search_empty)
                        }
                        AiSummaryScope.UNREAD -> ""
                    }
                    TextButton(
                        enabled = enabled,
                        onClick = { onSelect(scope, selectedStyle) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (enabled) Primary else TextHint,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                scope.localizedLabel(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (enabled) OnSurface else TextHint
                            )
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_summary_scope_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun AiSummaryHistoryDialog(
    summaries: List<AiSummaryHistoryUi>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var historySearch by rememberSaveable { mutableStateOf("") }
    val filteredSummaries = remember(summaries, historySearch) {
        val q = historySearch.trim()
        if (q.isEmpty()) {
            summaries
        } else {
            summaries.filter { item ->
                item.summary.contains(q, ignoreCase = true) ||
                    item.scope.name.contains(q, ignoreCase = true) ||
                    item.cacheKey.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_summary_history_title))
            }
        },
        text = {
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
                summaries.isEmpty() -> Text(
                    stringResource(R.string.chat_ai_summary_history_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textHint,
                    textAlign = TextAlign.Center
                )
                else -> Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summaries.size >= 4) {
                        OutlinedTextField(
                            value = historySearch,
                            onValueChange = { historySearch = it.take(120) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_ai_summary_history_search_hint)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Outline,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                cursorColor = Primary
                            )
                        )
                    }
                    if (filteredSummaries.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_ai_summary_history_search_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textHint,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        filteredSummaries.forEachIndexed { index, item ->
                            TextButton(
                                onClick = { onSelect(item.cacheKey) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.scope.localizedLabel(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            dateFormat.format(Date(item.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textHint
                                        )
                                    }
                                    Text(
                                        pluralStringResource(R.plurals.chat_ai_summary_messages, item.messageCount, item.messageCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary
                                    )
                                    Text(
                                        item.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (index != filteredSummaries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
internal fun AiConsentDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_ai_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.chat_ai_consent_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.chat_ai_consent_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.chat_ai_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun AiSummaryDialog(
    summary: String,
    scope: AiSummaryScope,
    messageCount: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_summary_title))
                }
                if (messageCount > 0) {
                    Text(
                        "${scope.localizedLabel()} · ${pluralStringResource(R.plurals.chat_ai_summary_messages, messageCount, messageCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.chat_ai_summary_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

// B4：会话画像对话框（本地统计 + 可选叙事摘要）
@Composable
internal fun ConversationProfileDialog(
    loading: Boolean,
    profile: com.maodouchat.ai.AiConversationProfile.ConversationProfile?,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.317：复制会话画像
    onCopyProfile: (String) -> Unit = {}
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_conversation_profile_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.chat_ai_conversation_profile_loading), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.chat_ai_conversation_profile_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                profile == null -> Text(stringResource(R.string.chat_ai_profile_no_data), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.chat_ai_conversation_profile_stats,
                            profile.local.messageCount,
                            profile.local.activeDays
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    Text(
                        stringResource(
                            R.string.chat_ai_profile_time_distribution,
                            profile.local.morning, profile.local.afternoon,
                            profile.local.evening, profile.local.night
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    if (profile.local.topTerms.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.chat_ai_profile_top_terms,
                                profile.local.topTerms.take(8).joinToString(" · ")
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Text(
                        profile.narrative?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_ai_profile_no_narrative),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.317：复制会话画像（转发/归档）
        dismissButton = {
            if (profile != null && !failed && !loading) {
                TextButton(onClick = { onCopyProfile(profileText(context, profile)) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 1.317：将会话画像序列化为纯文本（复制用）。 */
internal fun profileText(context: android.content.Context, profile: com.maodouchat.ai.AiConversationProfile.ConversationProfile): String = buildString {
    val ctx = context
    append(
        ctx.getString(
            R.string.chat_ai_conversation_profile_stats,
            profile.local.messageCount,
            profile.local.activeDays
        )
    )
    append("\n")
    append(
        ctx.getString(
            R.string.chat_ai_profile_time_distribution,
            profile.local.morning, profile.local.afternoon,
            profile.local.evening, profile.local.night
        )
    )
    if (profile.local.topTerms.isNotEmpty()) {
        append("\n")
        append(
            ctx.getString(
                R.string.chat_ai_profile_top_terms,
                profile.local.topTerms.take(8).joinToString(" · ")
            )
        )
    }
    profile.narrative?.takeIf { it.isNotBlank() }?.let {
        append("\n\n").append(it)
    }
}

// B4：本周周报对话框（生成并缓存到本地）
@Composable
internal fun WeeklyReportDialog(
    loading: Boolean,
    report: com.maodouchat.ai.AiWeeklyReport.WeeklyReport?,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.310：复制周报全文
    onCopyReport: (String) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_weekly_report_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.chat_ai_weekly_report_loading), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.chat_ai_weekly_report_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                report == null -> Text(stringResource(R.string.chat_ai_weekly_report_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(report.report, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.chat_ai_summary_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.310：复制周报（转发/归档）
        dismissButton = {
            if (report != null && !failed && !loading) {
                TextButton(onClick = { onCopyReport(report.report) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 8.47：消息分类统计对话框（纯本地词典分类，结果仅本机）。 */
@Composable
internal fun MessageClassifyDialog(
    loading: Boolean,
    categories: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.349：复制分类结果
    onCopyClassify: (String) -> Unit = {}
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_enhance_classify_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.ai_enhance_classify_hint), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.ai_enhance_classify_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                categories.isEmpty() -> Text(stringResource(R.string.ai_enhance_classify_empty), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.ai_enhance_classify_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                    categories.forEach { row ->
                        val label = when (row.category) {
                            "notice" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_notice)
                            "todo" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_todo)
                            "finance" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_finance)
                            "study" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_study)
                            "tech" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_tech)
                            "social" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_social)
                            else -> stringResource(com.maodouchat.R.string.ai_enhance_classify_other)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.ai_enhance_classify_count, row.count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (row.count.toFloat() / (categories.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        stringResource(R.string.ai_enhance_classify_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.349：复制分类结果（dismissButton 位，仅分类成功时显示）
        dismissButton = {
            if (categories.isNotEmpty() && !failed && !loading) {
                val classifyCopyText = classifyText(context, categories)
                TextButton(onClick = { onCopyClassify(classifyCopyText) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 1.349：将消息分类结果序列化为纯文本（复制用）。 */
@Composable
internal fun classifyText(context: android.content.Context, categories: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>): String = buildString {
    val ctx = context
    categories.forEach { row ->
        val label = when (row.category) {
            "notice" -> ctx.getString(R.string.ai_enhance_classify_notice)
            "todo" -> ctx.getString(R.string.ai_enhance_classify_todo)
            "finance" -> ctx.getString(R.string.ai_enhance_classify_finance)
            "study" -> ctx.getString(R.string.ai_enhance_classify_study)
            "tech" -> ctx.getString(R.string.ai_enhance_classify_tech)
            "social" -> ctx.getString(R.string.ai_enhance_classify_social)
            else -> ctx.getString(R.string.ai_enhance_classify_other)
        }
        append(label).append(": ").append(ctx.getString(R.string.ai_enhance_classify_count, row.count)).append('\n')
    }
    append(ctx.getString(R.string.ai_enhance_classify_disclaimer))
}
