package com.maodouchat.ui.screen.chatdetail

package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 输入栏附加条：AI 状态条与录音/语音预览。（贴纸和杂项对话框见 ChatDetailComposerDialogs） */

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

/**
 * 录音指示器：脉冲点 + 实时时长 + 振幅波形。
 */
@Composable
internal fun RecordingIndicator(
    elapsedMs: Long = 0L,
    waveform: List<Float> = emptyList(),
    amplitude: Float = 0f,
) {
    val alpha by rememberMotionPulse(
        initialValue = 0.3f,
        targetValue = 1.0f,
        durationMillis = 800,
        label = "recordingPulse"
    )
    val durationLabel = com.maodouchat.util.VoiceRecorder.formatDuration(elapsedMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UnreadRed.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.background(UnreadRed, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.chat_recording), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(durationLabel, color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        RecordingWaveformRow(waveform = waveform, liveAmplitude = amplitude)
    }
}

@Composable
internal fun RecordingWaveformRow(
    waveform: List<Float>,
    liveAmplitude: Float,
    barCount: Int = 32,
) {
    val bars = remember(waveform, liveAmplitude, barCount) {
        val base = if (waveform.isEmpty()) {
            List(barCount) { 0.08f }
        } else {
            val src = waveform
            List(barCount) { i ->
                val idx = ((i.toFloat() / (barCount - 1).coerceAtLeast(1)) * (src.size - 1).coerceAtLeast(0)).toInt()
                    .coerceIn(0, src.lastIndex.coerceAtLeast(0))
                src.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            }
        }
        // 末尾条跟瞬时振幅，增强“正在说话”感
        base.mapIndexed { i, v ->
            if (i >= barCount - 3) maxOf(v, liveAmplitude * (0.7f + 0.1f * (i - (barCount - 3)))) else v
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { h ->
            val heightFrac = (0.12f + h * 0.88f).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height((36 * heightFrac).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(UnreadRed.copy(alpha = 0.35f + h * 0.55f))
            )
        }
    }
}

@Composable
internal fun VoicePreviewBar(
    durationMs: Long,
    onPlay: () -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    val playerState by com.maodouchat.util.VoicePlayer.state.collectAsState()
    val isPreviewPlaying =
        playerState.messageId == com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.VOICE_PREVIEW_MESSAGE_ID &&
            playerState.isPlaying
    val durationLabel = com.maodouchat.util.VoiceRecorder.formatDuration(durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_voice_preview_discard),
                tint = LocalChatPalette.current.textHint
            )
        }
        IconButton(
            onClick = {
                if (isPreviewPlaying) {
                    com.maodouchat.util.VoicePlayer.stop()
                } else {
                    onPlay()
                }
            },
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                imageVector = if (isPreviewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPreviewPlaying) R.string.chat_voice_preview_stop else R.string.chat_voice_preview_play
                ),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_voice_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(durationLabel, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textSecondary)
            if (isPreviewPlaying) {
                LinearProgressIndicator(
                    progress = { playerState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Primary.copy(alpha = 0.15f),
                )
            }
        }
        TextButton(onClick = onSend) {
            Text(stringResource(R.string.chat_voice_preview_send), color = MaterialTheme.colorScheme.primary)
        }
    }
}
