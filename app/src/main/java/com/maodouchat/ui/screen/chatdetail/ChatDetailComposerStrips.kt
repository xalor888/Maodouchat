package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.ReplyTargetBar
import com.maodouchat.ui.component.TypingPresence

/**
 * G353：输入区上方的「状态条带」从 `ChatDetailRoute.kt` 抽出（纯搬移不改判断）——
 * 加载指示器 / 引用中提示 / 录音指示器 / 发送前试听条 / 发送中指示器 / 群禁言提示 /
 * AI 操作状态条 / AI 草稿流条 / 恢复草稿面板 / 打字中指示器。
 *
 * 依赖全经参数注入；组合期内不新增状态所有权。
 */
@Suppress("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailComposerStrips(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    targets: ChatDetailMessageTargetState,
    muteExpiry: ChatDetailMuteExpiryState,
    resolveSenderName: (Message) -> String?,
) {
    val context = LocalContext.current
    // 加载指示器
    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
    }

    // 引用中提示
    targets.replyTarget?.let { target ->
        ReplyTargetBar(
            senderName = resolveSenderName(target) ?: "",
            preview = MessagePreviewText.replyOrQuote(
                message = target,
                mediaLabel = { type ->
                    when (type) {
                        MessageType.IMAGE -> context.getString(R.string.message_preview_image)
                        MessageType.GIF -> context.getString(R.string.message_preview_gif)
                        MessageType.STICKER -> context.getString(R.string.message_preview_sticker)
                        MessageType.VOICE -> context.getString(R.string.message_preview_voice)
                        MessageType.VIDEO -> context.getString(R.string.message_preview_video)
                        MessageType.FILE -> context.getString(R.string.message_preview_file)
                        MessageType.LOCATION -> context.getString(R.string.message_preview_location)
                        else -> context.getString(R.string.message_preview_encrypted)
                    }
                },
                encryptedPlaceholder = context.getString(R.string.message_preview_encrypted),
            ).take(60),
            onCancel = { targets.replyTarget = null }
        )
    }

    // 录音指示器（波形 + 时长）
    AnimatedVisibility(
        visible = state.isRecording,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        RecordingIndicator(
            elapsedMs = state.recordingElapsedMs,
            waveform = state.recordingWaveform,
            amplitude = state.recordingAmplitude,
        )
    }

    // 发送前试听条
    AnimatedVisibility(
        visible = state.voicePreviewPath != null && !state.isRecording,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        VoicePreviewBar(
            durationMs = state.voicePreviewDurationMs,
            onPlay = { viewModel.playVoicePreview() },
            onDiscard = { viewModel.discardVoicePreview() },
            onSend = { viewModel.sendVoicePreview() },
        )
    }

    // 发送中指示器
    if (state.isSending) {
        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
    }

    // 8.48：群禁言状态提示——被禁言时输入区上方明确显示，而非仅发送失败时提示
    if (state.chatIsGroup && state.myMutedUntil > 0L) {
        // 8.48 修复：禁言到期后无状态变化时提示条不消失——到期时刻触发一次重组
        LaunchedEffect(state.myMutedUntil) {
            val until = state.myMutedUntil
            val wait = until - System.currentTimeMillis()
            if (wait > 0L) {
                kotlinx.coroutines.delay(wait + 500L)
                muteExpiry.markExpired()
            }
        }
        // 读取 muteExpiry.muteTick 建立重组依赖（到期写入后提示条随重组消失）
        val recomposeOnExpiry = muteExpiry.muteTick
        val remaining = state.myMutedUntil - System.currentTimeMillis()
        if (remaining > 0L) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_group_muted_until, formatMuteRemaining(context, remaining)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    AnimatedVisibility(
        visible = state.aiOperations.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        AiOperationStatusBar(
            operations = state.aiOperations,
            onRetry = viewModel::retryAiOperation,
            onCancel = viewModel::cancelAiOperation,
            onDismiss = viewModel::dismissAiOperation
        )
    }

    AnimatedVisibility(
        visible = state.aiDraftOriginal != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        AiDraftStreamBar(
            preview = state.aiDraftPreview,
            isStreaming = state.isAiDraftStreaming,
            errorCode = state.aiDraftStreamErrorCode,
            onApply = viewModel::applyAiDraftPreview,
            onDiscard = viewModel::discardAiDraftPreview,
            onRetry = viewModel::retryAiDraftStream,
            onCancel = viewModel::cancelAiDraftStream
        )
    }

    RestoredDraftPanel(
        visible = state.hasSavedDraft && state.inputText.isNotBlank(),
        onClear = {
            viewModel.onInputChange("")
            viewModel.clearDraftPersistence()
        },
    )

    // 打字中微动效指示器 (Murexide / Telegram 风格悬浮指示)
    TypingPresence(
        visible = state.typingContact != null,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
    )
}
