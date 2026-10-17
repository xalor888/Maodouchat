package com.maodouchat.ui.screen.chatdetail

import android.content.Context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import com.maodouchat.data.model.MessageType

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailMessageArrivalEffects(
    state: ChatDetailUiState,
    targets: ChatDetailMessageTargetState,
    context: Context,
) {
    // 1.05：语音连续播放——一条语音自然播放结束后自动播下一条同会话语音
    LaunchedEffect(com.maodouchat.util.VoicePlayer.lastCompletedId) {
        val completed = com.maodouchat.util.VoicePlayer.lastCompletedId ?: return@LaunchedEffect
        val voiceMessages = state.messages.filter { it.type == MessageType.VOICE }
        val completedMsg = voiceMessages.firstOrNull { it.id == completed } ?: return@LaunchedEffect
        val next = voiceMessages
            .filter { m -> m.timestamp > completedMsg.timestamp || (m.timestamp == completedMsg.timestamp && m.id > completedMsg.id) }
            .minByOrNull { it.timestamp }
        next?.let {
            com.maodouchat.util.VoicePlayer.ensureContext(context)
            com.maodouchat.util.VoicePlayer.play(it.id, it.parsedContent(), context)
        }
    }

    // Bot force-reply: focus composer as reply to latest forced message from peer/bot.
    LaunchedEffect(state.messages.lastOrNull()?.id, state.currentUserId) {
        val last = state.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.senderId == state.currentUserId) return@LaunchedEffect
        val meta = last.parsedMeta()
        if (meta.forceReply && targets.replyTarget?.id != last.id) {
            targets.replyTarget = last
        }
    }
}
