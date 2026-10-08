package com.maodouchat.ui.screen.chatdetail

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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.rememberMotionPulse

/** 输入栏附加条：录音指示器、振幅波形与语音预览。（AI 状态条见 ChatDetailAiComposerBars；贴纸和杂项对话框见 ChatDetailComposerDialogs） */

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
