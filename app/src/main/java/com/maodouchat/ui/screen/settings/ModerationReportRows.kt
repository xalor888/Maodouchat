package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.network.ReportResponse
import com.maodouchat.R
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun ReportModerationRow(report: ReportResponse, onClick: () -> Unit) {
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
internal fun ReportReviewDialog(
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
internal fun reportStatusLabel(status: String): String = when (status) {
    "OPEN" -> stringResource(R.string.moderation_status_open)
    "IN_REVIEW" -> stringResource(R.string.moderation_status_in_review)
    "RESOLVED" -> stringResource(R.string.moderation_status_resolved)
    "REJECTED" -> stringResource(R.string.moderation_status_rejected)
    "ALL" -> stringResource(R.string.moderation_status_all)
    else -> status
}

@Composable
internal fun reportActionLabel(action: String): String = when (action) {
    "DELETE_CONTENT" -> stringResource(R.string.moderation_result_deleted)
    "NO_ACTION" -> stringResource(R.string.moderation_no_action)
    "RESTRICT_MESSAGES_24H" -> stringResource(R.string.moderation_result_messages_restricted)
    "RESTRICT_POSTS_7D" -> stringResource(R.string.moderation_result_posts_restricted)
    "SUSPEND_24H" -> stringResource(R.string.moderation_result_suspended)
    else -> action
}

@Composable
internal fun reportStatusColor(status: String): Color = when (status) {
    "OPEN" -> Error
    "IN_REVIEW" -> MaterialTheme.colorScheme.primary
    "RESOLVED" -> LocalChatPalette.current.textSecondary
    "REJECTED" -> LocalChatPalette.current.textHint
    else -> LocalChatPalette.current.textSecondary
}
