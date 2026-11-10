package com.maodouchat.domain.conversation

import com.maodouchat.core.model.UpdateChatSettingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.maodouchat.core.model.ChatDetailUiState

// 会话级设置开关（置顶 / 标记未读）：乐观更新 UI，失败回滚。逻辑从 ChatDetailViewModel 纯搬移。
class ChatSettingToggleController(
    private val scope: CoroutineScope,
    private val chatPinEnabled: () -> Boolean,
    private val sessionActive: (String) -> Boolean,
    private val liveToken: () -> String,
    private val chatSettingsGateway: ChatSettingsGateway,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val chatId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val featureDisabledText: () -> String,
) {
    fun toggleChatMarkedUnread() {
        val chat = currentState().chat ?: return
        val ownerId = ownerUserId()
        if (token().isBlank() || ownerId.isBlank() || chatId().isBlank()) return
        val next = !chat.markedUnread
        updateState { it.copy(chat = it.chat?.copy(markedUnread = next)) }
        scope.launch {
            try {
                if (!sessionActive(ownerId)) {
                    return@launch
                }
                val tokenNow = liveToken()
                chatSettingsGateway.updateChatSettings(
                    tokenNow,
                    chatId(),
                    UpdateChatSettingsRequest(markedUnread = next)
                ).onFailure {
                    updateState { state -> state.copy(chat = state.chat?.copy(markedUnread = chat.markedUnread)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateState { state -> state.copy(chat = state.chat?.copy(markedUnread = chat.markedUnread)) }
            }
        }
    }

    fun toggleChatPinned() {
        val chat = currentState().chat ?: return
        if (!chatPinEnabled()) {
            updateState { it.copy(groupEncryptionWarning = featureDisabledText()) }
            return
        }
        val ownerId = ownerUserId()
        if (token().isBlank() || ownerId.isBlank() || chatId().isBlank()) return
        val wasPinned = chat.pinnedAt > 0
        val nextPinnedAt = if (wasPinned) 0L else System.currentTimeMillis()
        updateState { it.copy(chat = it.chat?.copy(pinnedAt = nextPinnedAt)) }
        scope.launch {
            try {
                if (!sessionActive(ownerId)) {
                    return@launch
                }
                val tokenNow = liveToken()
                chatSettingsGateway.updateChatSettings(
                    tokenNow,
                    chatId(),
                    UpdateChatSettingsRequest(pinned = !wasPinned)
                ).onFailure {
                    updateState { state -> state.copy(chat = state.chat?.copy(pinnedAt = chat.pinnedAt)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateState { state -> state.copy(chat = state.chat?.copy(pinnedAt = chat.pinnedAt)) }
            }
        }
    }
}
