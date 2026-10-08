package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import java.util.Date

/** 输入栏 AI 附加条：AI 操作状态条与 AI 草稿流式条。（录音/语音预览见 ChatDetailComposerExtras） */

@Composable
internal fun AiOperationStatusBar(
    operations: List<AiOperationUi>,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    val operation = operations.firstOrNull() ?: return
    val isFailed = operation.state == AiOperationState.FAILED
    val context = LocalContext.current
    val title = when (operation.type) {
        AiOperationType.TRANSCRIBE_VOICE -> stringResource(R.string.chat_ai_operation_transcribe)
        AiOperationType.TRANSLATE_MESSAGE -> stringResource(R.string.chat_ai_operation_translate)
        AiOperationType.SUMMARIZE_MESSAGES -> stringResource(R.string.chat_ai_operation_summary)
        AiOperationType.ANALYZE_IMAGE -> stringResource(R.string.chat_ai_operation_image)
        AiOperationType.ANALYZE_FILE -> stringResource(R.string.chat_ai_operation_file)
        else -> stringResource(R.string.chat_ai_consent_title)
    }
    val errorBase = com.maodouchat.ai.AiCostVisibilityPolicy.baseErrorCode(operation.lastErrorCode)
    val waitSeconds = operation.retryAfterSeconds
        ?: com.maodouchat.ai.AiCostVisibilityPolicy.waitSecondsFor(operation.lastErrorCode)
    val status = when {
        errorBase == AiOperationError.INTERRUPTED ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        errorBase == AiOperationError.OUTCOME_UNKNOWN ||
            errorBase == AiOperationError.TIMEOUT ||
            errorBase == AiOperationError.UNKNOWN ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        errorBase == AiOperationError.RATE_LIMITED ->
            stringResource(
                R.string.chat_ai_operation_rate_limited,
                waitSeconds.coerceAtLeast(1L)
            )
        errorBase == AiOperationError.QUOTA_EXCEEDED ->
            stringResource(R.string.chat_ai_operation_quota_exceeded)
        errorBase == AiOperationError.CONNECTION_NOT_ESTABLISHED &&
            operation.nextRetryAtMs != null -> stringResource(
                R.string.chat_ai_operation_retry_scheduled,
                android.text.format.DateFormat.getTimeFormat(context).format(Date(operation.nextRetryAtMs))
            )
        errorBase == AiOperationError.CONTEXT_MISSING ->
            stringResource(R.string.chat_ai_operation_context_missing)
        errorBase in setOf(
            AiOperationError.NETWORK,
            AiOperationError.CONNECTION_NOT_ESTABLISHED
        ) ->
            stringResource(R.string.chat_ai_operation_network_failed)
        errorBase == AiOperationError.SERVER ->
            stringResource(R.string.chat_ai_operation_server_failed)
        errorBase in setOf(AiOperationError.EMPTY_RESULT, AiOperationError.INVALID_RESPONSE) ->
            stringResource(R.string.chat_ai_operation_invalid_result)
        operation.state == AiOperationState.QUEUED -> stringResource(R.string.chat_ai_operation_queued)
        operation.state == AiOperationState.RUNNING ->
            stringResource(R.string.chat_ai_operation_running_billing)
        else -> stringResource(R.string.chat_ai_operation_failed)
    }
    val showRetryBillHint = isFailed &&
        com.maodouchat.ai.AiCostVisibilityPolicy.shouldWarnRetryBills(operation.lastErrorCode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryFixed.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFailed) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(status, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            if (showRetryBillHint) {
                Text(
                    stringResource(R.string.chat_ai_operation_retry_may_bill),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (operation.attempts > 0) {
                Text(
                    stringResource(R.string.chat_ai_operation_attempts, operation.attempts),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (operations.size > 1) {
                Text(
                    stringResource(R.string.chat_ai_operation_more, operations.size - 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        }
        if (isFailed) {
            IconButton(onClick = { onRetry(operation.id) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.chat_ai_operation_retry),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(
            onClick = {
                if (isFailed) onDismiss(operation.id) else onCancel(operation.id)
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    if (isFailed) R.string.chat_ai_operation_dismiss else R.string.chat_ai_operation_cancel
                ),
                tint = LocalChatPalette.current.textSecondary
            )
        }
    }
}

@Composable
internal fun AiDraftStreamBar(
    preview: String,
    isStreaming: Boolean,
    errorCode: String?,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val isCancelled = errorCode == "CANCELLED"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_ai_draft_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isStreaming) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (isStreaming || errorCode != null) {
                Text(
                    if (isStreaming) stringResource(R.string.chat_ai_stream_generating)
                    else aiStreamStatusText(errorCode),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errorCode != null && !isCancelled) UnreadRed else TextSecondary
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when {
            isStreaming -> {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_cancel), tint = LocalChatPalette.current.textSecondary)
                }
            }
            errorCode == null -> {
                IconButton(onClick = onApply, enabled = preview.isNotBlank(), modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Check, stringResource(R.string.chat_ai_stream_apply), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_discard), tint = LocalChatPalette.current.textSecondary)
                }
            }
            else -> {
                if (isCancelled && preview.isNotBlank()) {
                    IconButton(onClick = onApply, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Check, stringResource(R.string.chat_ai_stream_apply), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_discard), tint = LocalChatPalette.current.textSecondary)
                }
            }
        }
    }
}
