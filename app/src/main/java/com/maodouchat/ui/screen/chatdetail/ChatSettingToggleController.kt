package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 会话级设置开关（置顶 / 标记未读）：乐观更新 UI，失败回滚。逻辑从 ChatDetailViewModel 纯搬移。
internal class ChatSettingToggleController(
    private val scope: CoroutineScope,
    private val getApplication: () -> Application,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val chatId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (Int) -> String,
) {
    fun toggleChatMarkedUnread() {
        val chat = currentState().chat ?: return
        val ownerId = ownerUserId()
        if (token().isBlank() || ownerId.isBlank() || chatId().isBlank()) return
        val next = !chat.markedUnread
        updateState { it.copy(chat = it.chat?.copy(markedUnread = next)) }
        scope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerId,
                )
                ) {
                    return@launch
                }
                val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                ChatNetworkRepository().updateChatSettings(
                    liveToken,
                    chatId(),
                    com.maodouchat.network.UpdateChatSettingsRequest(markedUnread = next)
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
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_PIN)) {
            updateState { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val ownerId = ownerUserId()
        if (token().isBlank() || ownerId.isBlank() || chatId().isBlank()) return
        val wasPinned = chat.pinnedAt > 0
        val nextPinnedAt = if (wasPinned) 0L else System.currentTimeMillis()
        updateState { it.copy(chat = it.chat?.copy(pinnedAt = nextPinnedAt)) }
        scope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerId,
                )
                ) {
                    return@launch
                }
                val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                ChatNetworkRepository().updateChatSettings(
                    liveToken,
                    chatId(),
                    com.maodouchat.network.UpdateChatSettingsRequest(pinned = !wasPinned)
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
