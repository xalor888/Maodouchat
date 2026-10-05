package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import com.maodouchat.R
import com.maodouchat.data.repository.LocalMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 会话内跳转定位（日期跳转 / 引用预览跳转 / 置顶跳转）：逻辑从 ChatDetailViewModel 纯搬移。
internal class ChatJumpController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val chatId: () -> String,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (Int) -> String,
    private val messageRepo: LocalMessageStore,
    private val searchSelectionStateController: ChatSearchSelectionStateController,
) {
    fun jumpToDate(dayStartMillis: Long) {
        val targetChatId = activeChatId().ifBlank { chatId() }
        if (targetChatId.isBlank() || dayStartMillis <= 0L) return
        val ownerId = ownerUserId()
        if (ownerId.isBlank() || ownerId == "me") return
        scope.launch(Dispatchers.IO) {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerId,
                )
                ) {
                    return@launch
                }
                val anchorId = messageRepo.getFirstMessageAtOrAfter(targetChatId, dayStartMillis)?.id
                if (anchorId.isNullOrBlank()) {
                    withContext(Dispatchers.Main.immediate) {
                        updateState { it.copy(groupEncryptionWarning = text(R.string.chat_jump_date_empty)) }
                    }
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    updateState { it.copy(navigationTargetMessageId = anchorId) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "jumpToDate failed", error)
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
