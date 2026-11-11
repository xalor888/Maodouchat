package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// 语音"已播放"回执上报：从 ChatDetailViewModel 纯搬移，判断逻辑一行未动。
// 播放流/回执出箱/会话门禁/Log 走端口；ViewModel 只做接线。
class ChatVoicePlaybackReporter(
    private val scope: CoroutineScope,
    private val currentUserId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val activeChatId: () -> String,
    private val chatId: () -> String,
    private val playingMessageIds: Flow<String>,
    private val sessionMayContinue: (String) -> Boolean,
    private val enqueuePlayReceipt: suspend (String, String, Long?) -> Unit,
    private val logWarning: (String, Throwable?) -> Unit,
) {
    fun markVoiceMessagePlayed(messageId: String) {
        val ownerUserId = currentUserId()
        if (
            ownerUserId.isBlank() ||
            ownerUserId == "me" ||
            !sessionMayContinue(ownerUserId)
        ) {
            return
        }
        val target = currentState().messages.firstOrNull { it.id == messageId } ?: return
        if (target.senderId == ownerUserId) return
        scope.launch(Dispatchers.IO) {
            try {
                val effectiveChatId = activeChatId().ifBlank { chatId() }
                val chat = currentState().chat
                enqueuePlayReceipt(
                    effectiveChatId,
                    messageId,
                    chat?.memberRevision.takeIf { chat?.isGroup == true },
                )
            } catch (error: Exception) {
                logWarning("v2 play receipt enqueue failed: " + error.message, error)
            }
        }
    }

    fun observeVoicePlayback() {
        val playedEnqueued = mutableSetOf<String>()
        playingMessageIds
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
