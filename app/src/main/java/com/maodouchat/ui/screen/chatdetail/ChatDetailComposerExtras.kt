package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.DisappearingMessagePolicy
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/**
 * 输入栏相关的「杂项组件与对话框」（G110 从 `ChatDetailComponents.kt` 拆出，原 900 行）。
 *
 * 按职责分四组：
 * - **AI 状态**：`AiOperationStatusBar` / `AiDraftStreamBar` / `aiStreamStatusText`；
 * - **录音与语音**：`RecordingIndicator` / `RecordingWaveformRow` / `VoicePreviewBar`；
 * - **贴纸**：`stickerPackLabel` / `StickerPackChip` / `PressScaleGlyphItem`；
 * - **杂项对话框**：`ChatQuietHoursDialog` / `DisappearingMessagesDialog` /
 *   `disappearSecondsLabel` / `ReportDialog` / `DateJumpDialog` /
 *   `openScheduleDateTimePicker` / `ReactionPickerRow` / `pinnedPreviewText`。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

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

@Composable
internal fun stickerPackLabel(nameKey: String): String = when (nameKey) {
    "mood" -> stringResource(R.string.sticker_pack_mood)
    "gesture" -> stringResource(R.string.sticker_pack_gesture)
    "party" -> stringResource(R.string.sticker_pack_party)
    else -> nameKey
}



/**
 * 9.223：表情/贴纸格子项——按压缩放回弹（TG 式手感）。
 * 系统关闭动画时退化为无动效点击；无 indication（缩放即反馈）。
 */
@Composable
internal fun PressScaleGlyphItem(
    glyph: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val motion = LocalMotionSettings.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion.animationsEnabled) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "glyphPressScale"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
    }
}

@Composable
internal fun StickerPackChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Primary else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}


@Composable
internal fun ChatQuietHoursDialog(
    current: com.maodouchat.notification.ChatQuietHoursStore.QuietWindow,
    onPick: (com.maodouchat.notification.ChatQuietHoursStore.QuietWindow) -> Unit,
    onDismiss: () -> Unit
) {
    // 快捷时段：预置的常用静音窗口（start, end, labelRes）
    val presets = listOf(
        Triple(22 * 60, 7 * 60, R.string.chat_quiet_hours_night),
        Triple(12 * 60, 14 * 60, R.string.chat_quiet_hours_lunch),
        Triple(23 * 60, 8 * 60, R.string.chat_quiet_hours_sleep),
        Triple(9 * 60, 18 * 60, R.string.chat_quiet_hours_workday)
    )
    val enabled = current.enabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quiet_hours_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (enabled) R.string.chat_quiet_hours_active_summary
                        else R.string.chat_quiet_hours_inactive_summary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                presets.forEach { (start, end, labelRes) ->
                    val selected = enabled && current.startMinute == start && current.endMinute == end
                    TextButton(
                        onClick = {
                            onPick(
                                com.maodouchat.notification.ChatQuietHoursStore.QuietWindow(
                                    enabled = true,
                                    startMinute = start,
                                    endMinute = end
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(stringResource(labelRes), color = if (selected) Primary else OnSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (enabled) {
                    TextButton(
                        onClick = {
                            onPick(com.maodouchat.notification.ChatQuietHoursStore.QuietWindow.OFF)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_quiet_hours_clear), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    stringResource(R.string.chat_quiet_hours_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
internal fun DisappearingMessagesDialog(
    selectedSeconds: Int,
    isUpdating: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = DisappearingMessagePolicy.ALLOWED_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disappear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.disappear_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Text(
                    text = stringResource(R.string.disappear_limit_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { seconds ->
                    val selected = seconds == selectedSeconds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isUpdating) { onSelect(seconds) }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (seconds == 0) Icons.Outlined.Schedule else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = if (selected) Primary else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = disappearSecondsLabel(seconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Primary else OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun disappearSecondsLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.disappear_off)
    30 -> stringResource(R.string.disappear_30s)
    60 -> stringResource(R.string.disappear_1m)
    2 * 60 -> stringResource(R.string.disappear_2m)
    5 * 60 -> stringResource(R.string.disappear_5m)
    15 * 60 -> stringResource(R.string.disappear_15m)
    60 * 60 -> stringResource(R.string.disappear_1h)
    2 * 60 * 60 -> stringResource(R.string.disappear_2h)
    4 * 60 * 60 -> stringResource(R.string.disappear_4h)
    8 * 60 * 60 -> stringResource(R.string.disappear_8h)
    12 * 60 * 60 -> stringResource(R.string.disappear_12h)
    24 * 60 * 60 -> stringResource(R.string.disappear_24h)
    7 * 24 * 60 * 60 -> stringResource(R.string.disappear_7d)
    30 * 24 * 60 * 60 -> stringResource(R.string.disappear_30d)
    else -> stringResource(R.string.disappear_off)
}


@Composable
internal fun pinnedPreviewText(message: Message?): String {
    if (message == null) return stringResource(R.string.chat_pinned_preview_generic)
    return when (MessagePinPolicy.previewKind(message.type)) {
        MessagePinPolicy.PreviewKind.TEXT -> {
            val text = MessagePinPolicy.textPreview(message.content)
            if (text.isBlank()) stringResource(R.string.chat_pinned_preview_generic) else text
        }
        MessagePinPolicy.PreviewKind.IMAGE -> stringResource(R.string.chat_pinned_preview_image)
        MessagePinPolicy.PreviewKind.VOICE -> stringResource(R.string.chat_pinned_preview_voice)
        MessagePinPolicy.PreviewKind.VIDEO -> stringResource(R.string.chat_pinned_preview_video)
        MessagePinPolicy.PreviewKind.FILE -> stringResource(R.string.chat_pinned_preview_file)
        MessagePinPolicy.PreviewKind.LOCATION -> stringResource(R.string.chat_pinned_preview_location)
        MessagePinPolicy.PreviewKind.STICKER -> stringResource(R.string.chat_pinned_preview_sticker)
        MessagePinPolicy.PreviewKind.GENERIC -> stringResource(R.string.chat_pinned_preview_generic)
    }
}

@Composable
internal fun ReportDialog(
    title: String,
    onDismiss: () -> Unit,
    onReport: (reason: String, description: String?) -> Unit
) {
    val reasons = stringArrayResource(R.array.chat_report_reasons).toList()
    // 8.49 防御：资源数组为空时回退空串（此前 reasons.first() 依赖资源不被清空）
    var selectedReason by rememberSaveable { mutableStateOf(reasons.firstOrNull().orEmpty()) }
    var description by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reasons.forEach { reason ->
                    val selected = reason == selectedReason
                    TextButton(
                        onClick = { selectedReason = reason },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (selected) PrimaryFixed.copy(alpha = 0.42f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(
                            text = reason,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selected) Primary else OnSurface
                        )
                    }
                }
                TextField(
                    value = description,
                    onValueChange = { description = it.take(800) },
                    placeholder = { Text(stringResource(R.string.chat_report_description), color = LocalChatPalette.current.textHint) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onReport(selectedReason, description.trim().takeIf { it.isNotBlank() })
                }
            ) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * 日历跳转对话框：选日期后跳到该日第一条消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateJumpDialog(
    onDismiss: () -> Unit,
    onJump: (dayStartMillis: Long) -> Unit
) {
    val context = LocalContext.current
    val today = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_jump_date_title)) },
        text = {
            DatePicker(state = datePickerState, showModeToggle = false)
        },
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val utcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                    // 选中的是 UTC 当天 00:00；换算成本地时区当天 00:00
                    val localStart = java.time.Instant.ofEpochMilli(utcMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    onJump(localStart)
                }
            ) { Text(stringResource(R.string.chat_jump_date_jump)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
        }
    )
}

/**
 * System date → time picker; enforces [ScheduledMessagePolicy] min/max window before [onPicked].
 */
internal fun openScheduleDateTimePicker(
    context: Context,
    onPicked: (Long) -> Unit,
    onTooSoon: () -> Unit,
    onTooLate: () -> Unit
) {
    val now = java.util.Calendar.getInstance()
    val minCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS
    }
    val maxCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MAX_DELAY_MS
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            android.app.TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year)
                        set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    when {
                        picked < minCal.timeInMillis -> onTooSoon()
                        picked > maxCal.timeInMillis -> onTooLate()
                        else -> onPicked(picked)
                    }
                },
                minCal.get(java.util.Calendar.HOUR_OF_DAY),
                minCal.get(java.util.Calendar.MINUTE),
                true
            ).show()
        },
        minCal.get(java.util.Calendar.YEAR),
        minCal.get(java.util.Calendar.MONTH),
        minCal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply {
        datePicker.minDate = minCal.timeInMillis
        datePicker.maxDate = maxCal.timeInMillis
    }.show()
}

@Composable
internal fun ReactionPickerRow(onPick: (String) -> Unit) {
    val reactions = remember {
        listOf(
            "\uD83D\uDC4D", // 👍
            "\uD83D\uDC4E", // 👎
            "\u2764\uFE0F", // ❤️
            "\uD83D\uDE02", // 😂
            "\uD83D\uDE0D", // 😍
            "\uD83D\uDE2E", // 😮
            "\uD83D\uDE22", // 😢
            "\uD83D\uDE21", // 😠
            "\uD83D\uDD25", // 🔥
            "\uD83C\uDF89", // 🎉
            "\uD83D\uDC4F", // 👏
            "\uD83D\uDE4F", // 🙏
            "\uD83D\uDC40", // 👀
            "\uD83E\uDD14", // 🤔
            "\uD83D\uDCAF", // 💯
            "\u2705",      // ✅
            "\uD83D\uDE80", // 🚀
            "\u2B50",      // ⭐
            "\uD83C\uDF1F", // 🌟
            "\uD83E\uDD73", // 🥳
            "\uD83E\uDD70", // 🥰
            "\uD83D\uDCAA", // 💪
            "\uD83E\uDD1D", // 🤝
            "\uD83D\uDE0A", // 😊
            "\uD83D\uDE4C", // 🙌
            "\uD83E\uDD29", // 🤩
            "\uD83E\uDD72", // 🥲
            "\uD83E\uDD23", // 🤣
            "\uD83D\uDC4C", // 👌
            "\uD83E\uDEF6"  // 🫶
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp)
    ) {
        reactions.forEach { emoji ->
            TextButton(
                onClick = { onPick(emoji) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(emoji, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

