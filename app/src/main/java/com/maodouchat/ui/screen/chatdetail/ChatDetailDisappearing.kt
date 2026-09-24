package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiService
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.data.repository.ChatNetworkRepository

// 阅后即焚 / 消失消息（自 ChatDetailViewModel.kt 拆分）。
// 涵盖定时设置、密聊默认时限、截止时间落库与过期本地清理，复用 messageRepo / chatRepo。

internal fun ChatDetailViewModel.setDisappearingMessages(seconds: Int) {
    val state = _uiState.value
    val chat = state.chat ?: return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.DISAPPEARING_MESSAGES)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
        return
    }
    if (chat.isGroup) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.disappear_group_unsupported)) }
        return
    }
    val caps = getApplication<com.maodouchat.MaodouchatApp>().secretConversationController.capabilities(chat.id)
    if (caps.isSecretChat) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_timer_locked)) }
        return
    }
    val normalized = com.maodouchat.util.DisappearingMessagePolicy.normalizeSeconds(seconds)
    if (!com.maodouchat.util.DisappearingMessagePolicy.isAllowedSeconds(normalized)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.disappear_invalid_timer)) }
        return
    }
    val ownerUserId = currentUserId
    if (token.isBlank() || ownerUserId.isBlank() || chatId.isBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
        return
    }
    if (state.isUpdatingDisappearing) return
    val previous = state.disappearingMessageSeconds
    _uiState.update {
        it.copy(
            isUpdatingDisappearing = true,
            disappearingMessageSeconds = normalized,
            chat = it.chat?.copy(disappearingMessageSeconds = normalized)
        )
    }
    viewModelScope.launch {
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update {
                    it.copy(
                        isUpdatingDisappearing = false,
                        disappearingMessageSeconds = previous,
                        chat = it.chat?.copy(disappearingMessageSeconds = previous),
                        groupEncryptionWarning = text(R.string.error_session_expired)
                    )
                }
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ChatNetworkRepository().updateDisappearingMessages(liveToken, chatId, normalized).fold(
                onSuccess = { response ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    val applied = com.maodouchat.util.DisappearingMessagePolicy.normalizeSeconds(response.seconds)
                    _uiState.update {
                        it.copy(
                            isUpdatingDisappearing = false,
                            disappearingMessageSeconds = applied,
                            chat = it.chat?.copy(disappearingMessageSeconds = applied),
                            groupEncryptionWarning = text(R.string.disappear_updated)
                        )
                    }
                    // 同步本地会话缓存
                    withContext(Dispatchers.IO) {
                        val local = chatRepo.getChatById(chatId)
                        if (local != null) {
                            chatRepo.cacheChats(listOf(local.copy(disappearingMessageSeconds = applied)))
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isUpdatingDisappearing = false,
                            disappearingMessageSeconds = previous,
                            chat = it.chat?.copy(disappearingMessageSeconds = previous),
                            groupEncryptionWarning = error.message ?: text(R.string.disappear_update_failed)
                        )
                    }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update {
                it.copy(
                    isUpdatingDisappearing = false,
                    disappearingMessageSeconds = previous,
                    chat = it.chat?.copy(disappearingMessageSeconds = previous)
                )
            }
            throw error
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isUpdatingDisappearing = false,
                    disappearingMessageSeconds = previous,
                    chat = it.chat?.copy(disappearingMessageSeconds = previous),
                    groupEncryptionWarning = error.message ?: text(R.string.disappear_update_failed)
                )
            }
        }
    }
}

internal suspend fun ChatDetailViewModel.armSecretDisappearing(targetChatId: String, throughId: String?) {
    if (targetChatId.isBlank()) return
    val app = getApplication<com.maodouchat.MaodouchatApp>()
    val caps = app.secretConversationController.capabilities(targetChatId)
    if (!caps.isSecretChat) return
    app.secretConversationController.armOnRead(targetChatId)
}

internal suspend fun ChatDetailViewModel.applyMessageExpires(messageId: String, expiresAt: Long) {
    if (messageId.isBlank() || expiresAt <= 0L) return
    val existing = withContext(Dispatchers.IO) { messageRepo.getMessageById(messageId) } ?: return
    if (existing.expiresAt != null && existing.expiresAt > 0L) {
        // 已有截止时间不延长
        if (existing.expiresAt <= expiresAt) return
    }
    val updated = existing.copy(expiresAt = expiresAt)
    withContext(Dispatchers.IO) { messageRepo.insertMessage(updated) }
    _uiState.update { state ->
        if (!state.messages.any { it.id == messageId }) return@update state
        state.copy(
            messages = state.messages.map { if (it.id == messageId) it.copy(expiresAt = expiresAt) else it }
        )
    }
}

internal suspend fun ChatDetailViewModel.purgeExpiredLocalMessages(nowMs: Long = System.currentTimeMillis()) {
    val app = getApplication<com.maodouchat.MaodouchatApp>()
    app.secretConversationController.purgeExpiredMessages(nowMs)
    _uiState.update { s ->
        s.copy(
            messages = s.messages.filter { it.expiresAt == null || it.expiresAt <= 0L || it.expiresAt > nowMs },
            pinnedMessages = s.pinnedMessages.filter { pin ->
                s.messages.none { it.id == pin.messageId && it.expiresAt != null && it.expiresAt > 0L && it.expiresAt <= nowMs }
            }
        )
    }
}
