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
    if (chat.isSecret) {
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
            ApiService.updateDisappearingMessages(liveToken, chatId, normalized).fold(
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
    if (_uiState.value.isSecretChat != true) return
    val timer = DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS
    if (!DisappearingMessagePolicy.shouldArmOnVisible(true, timer)) return
    val now = System.currentTimeMillis()
    val messages = _uiState.value.messages
    val boundary = throughId?.let { boundaryId -> messages.firstOrNull { it.id == boundaryId } }
    val localMessages = messages.filter { message ->
        val withinBoundary = boundary == null ||
            message.timestamp < boundary.timestamp ||
            (message.timestamp == boundary.timestamp && message.id <= boundary.id)
        withinBoundary &&
            message.type != MessageType.SK_DIST &&
            message.type != MessageType.REVOKED &&
            (message.expiresAt == null || message.expiresAt <= 0L)
    }
    localMessages.forEach { msg ->
        val armed = DisappearingMessagePolicy.resolveExpiresAt(null, timer, now) ?: return@forEach
        applyMessageExpires(msg.id, armed)
    }
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
    // 阅后即焚：基于数据库扫描所有已过期消息（含已滚出内存窗口/分页未加载的），
    // 不再只依赖当前屏幕内存列表，避免私密内容因不在可见窗口而永久留存。
    val expiredIds = withContext(Dispatchers.IO) { messageRepo.deleteExpiredMessages(nowMs) }
    if (expiredIds.isEmpty()) return
    withContext(Dispatchers.IO) {
        expiredIds.forEach { id ->
            MediaCache.deleteCachedMediaForMessage(getApplication(), id)
        }
    }
    // 8.32 修复 F9（隐私）：自毁消息删除后同步清理 tray 预览与通知中心条目，
    // 否则密文已删但通知栏仍展示正文预览。
    try {
        com.maodouchat.MaodouchatApp.instance.notificationCenter.deleteItemsForMessages(expiredIds)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        // 通知中心清理失败不阻塞自毁消息删除
    }
    runCatching {
        com.maodouchat.util.AppNotifier.cancelMessage(getApplication(), activeChatId)
    }
    val expiredSet = expiredIds.toSet()
    _uiState.update { s ->
        s.copy(
            messages = s.messages.filterNot { it.id in expiredSet },
            pinnedMessages = s.pinnedMessages.filterNot { it.messageId in expiredSet }
        )
    }
}
