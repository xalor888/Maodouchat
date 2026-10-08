package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/** AI 画像与洞察对话框：会话画像、周报、消息分类。所需输入全经参数传入。 */
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
