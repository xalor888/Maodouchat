package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 会话内跳转定位（日期跳转 / 引用预览跳转 / 置顶跳转）：逻辑从 ChatDetailViewModel 纯搬移。
class ChatJumpController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val chatId: () -> String,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (ChatJumpMessage) -> String,
    private val sessionActive: (String) -> Boolean,
    private val getFirstMessageAtOrAfter: suspend (String, Long) -> String?,
    private val logWarning: (String, Throwable?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val searchSelectionStateController: ChatSearchSelectionStateController,
) {
    fun jumpToDate(dayStartMillis: Long) {
        val targetChatId = activeChatId().ifBlank { chatId() }
        if (targetChatId.isBlank() || dayStartMillis <= 0L) return
        val ownerId = ownerUserId()
        if (ownerId.isBlank() || ownerId == "me") return
        scope.launch(ioDispatcher) {
            try {
                if (!sessionActive(ownerId)) {
                    return@launch
                }
                val anchorId = getFirstMessageAtOrAfter(targetChatId, dayStartMillis)
                if (anchorId.isNullOrBlank()) {
                    withContext(mainDispatcher) {
                        updateState { it.copy(groupEncryptionWarning = text(ChatJumpMessage.DATE_EMPTY)) }
                    }
                    return@launch
                }
                withContext(mainDispatcher) {
                    updateState { it.copy(navigationTargetMessageId = anchorId) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                logWarning("jumpToDate failed", error)
            }
        }
    }

    fun jumpToPinnedMessage(messageId: String) {
        if (messageId.isBlank()) return
        updateState { state -> searchSelectionStateController.navigateTo(state, messageId) }
    }

    fun jumpToMessage(messageId: String) {
        if (messageId.isBlank()) return
        updateState { state -> searchSelectionStateController.navigateTo(state, messageId) }
    }
}
