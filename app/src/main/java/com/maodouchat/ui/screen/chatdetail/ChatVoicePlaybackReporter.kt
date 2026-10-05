package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.util.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// 语音"已播放"回执上报：从 ChatDetailViewModel 纯搬移，判断逻辑一行未动。
internal class ChatVoicePlaybackReporter(
    private val scope: CoroutineScope,
    private val currentUserId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val activeChatId: () -> String,
    private val chatId: () -> String,
) {
    fun markVoiceMessagePlayed(messageId: String) {
        val ownerUserId = currentUserId()
        if (
            ownerUserId.isBlank() ||
            ownerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        ) {
            return
        }
        val target = currentState().messages.firstOrNull { it.id == messageId } ?: return
        if (target.senderId == ownerUserId) return
        scope.launch(Dispatchers.IO) {
            try {
                val effectiveChatId = activeChatId().ifBlank { chatId() }
                val chat = currentState().chat
                ChatDetailAccess.messagingOutbox.enqueuePlayReceipt(
                    conversationId = effectiveChatId,
                    messageId = messageId,
                    groupRevision = chat?.memberRevision.takeIf { chat?.isGroup == true },
                )
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "v2 play receipt enqueue failed: " + error.message, error)
            }
        }
    }

    internal fun observeVoicePlayback() {
        val playedEnqueued = mutableSetOf<String>()
        VoicePlayer.state
            .filter { it.isPlaying && it.messageId != null }
            .mapNotNull { it.messageId }
            .distinctUntilChanged()
            .onEach { messageId ->
                // 同一条语音稍后再次播放也不重复上报：distinctUntilChanged 只去连续重复。
                if (playedEnqueued.add(messageId)) {
                    markVoiceMessagePlayed(messageId)
                }
            }
            .launchIn(scope)
    }
}
