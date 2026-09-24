package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.maodouchat.data.repository.ChatNetworkRepository

// 密聊（自 ChatDetailViewModel.kt 拆分）。
// 涵盖密聊态刷新、已打开密聊清理与从普通单聊发起独立密聊，复用 chatRepo / secretTtlRepo。

internal fun ChatDetailViewModel.refreshSecretChatState() {
    val targetChatId = activeChatId.ifBlank { chatId }
    if (targetChatId.isBlank()) {
        _uiState.update { it.copy(isSecretChat = false) }
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val loaded = _uiState.value.chat ?: try {
            chatRepo.getChatById(targetChatId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val secret = try {
            loaded?.isSecret ?: app.secretConversationController.capabilities(targetChatId).isSecretChat
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            true
        }
        if (secret) {
            try {
                secretTtlRepo.touch(targetChatId)
            } catch (error: Exception) {
                // G328c：TTL 心跳写失败会让密聊**提前过期或永不过期**，两种都影响安全语义，
                // 不能完全静默（原先这里是 catch (_: Exception) {}）。
                android.util.Log.w("ChatDetailSecretChat", "secret TTL touch failed: ${error.message}")
            }
            com.maodouchat.security.SecretChatSession.markSurfaceActive(targetChatId)
        } else {
            try {
                secretTtlRepo.remove(targetChatId)
            } catch (error: Exception) {
                android.util.Log.w("ChatDetailSecretChat", "secret TTL remove failed: ${error.message}")
            }
            com.maodouchat.security.SecretChatSession.clearSurfaceMarker(targetChatId)
        }
        _uiState.update { it.copy(isSecretChat = secret) }
    }
}

internal fun ChatDetailViewModel.clearOpenedSecretChat() {
    _uiState.update { it.copy(openedSecretChatId = null) }
}

/** 从当前普通单聊发起一场独立密聊（双方同步，不改写本会话）。 */
internal fun ChatDetailViewModel.startSecretChat() {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_CHAT)) {
        _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_feature_disabled)) }
        return
    }
    val snapshot = _uiState.value
    if (snapshot.chatIsGroup || snapshot.chat?.isChannel == true) {
        _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_direct_only)) }
        return
    }
    if (snapshot.isSecretChat == true || snapshot.chat?.isSecret == true) {
        _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_already)) }
        return
    }
    if (!com.maodouchat.security.SecretChatPolicy.canStartFromDirect(
            isGroup = snapshot.chatIsGroup,
            chatType = snapshot.chat?.chatType
        )
    ) {
        _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_direct_only)) }
        return
    }
    val peerId = snapshot.contact.id
    val ownerUserId = currentUserId
    if (peerId.isBlank() || ownerUserId.isBlank() || token.isBlank()) {
        _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return@launch
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val result = ChatNetworkRepository().createChat(
            liveToken,
            listOf(peerId),
            isGroup = false,
            groupName = null,
            chatType = com.maodouchat.security.SecretChatPolicy.CHAT_TYPE
        )
        result.fold(
            onSuccess = { chatDto ->
                try {
                    secretTtlRepo.touch(chatDto.id)
                } catch (error: Exception) {
                    android.util.Log.w("ChatDetailSecretChat", "secret TTL touch failed: ${error.message}")
                }
                try {
                    chatRepo.cacheChats(listOf(chatDto.toDomainChat()))
                } catch (error: Exception) {
                    android.util.Log.w("ChatDetailSecretChat", "cacheChats after secret start failed: ${error.message}")
                }
                _uiState.update {
                    it.copy(
                        openedSecretChatId = chatDto.id,
                        secretChatInfoMessage = text(R.string.secret_chat_started)
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: text(R.string.secret_chat_start_failed))
                }
            }
        )
    }
}
