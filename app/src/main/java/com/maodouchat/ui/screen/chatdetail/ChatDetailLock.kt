package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.conversation.ConversationLocalCleanupStep
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 聊天锁（PIN）与会话本地清理（自 ChatDetailViewModel.kt 拆分）。
// 涵盖 PIN 校验/设置/移除/遗忘、锁定态刷新与本地明文清理，集中复用 chatLockRepo 与 conversationLocalStateCoordinator。

internal fun ChatDetailViewModel.refreshChatLockState() {
    val lockChatId = activeChatId.ifBlank { chatId }
    if (lockChatId.isBlank()) {
        _uiState.update { it.copy(isChatLocked = false, isChatUnlocked = true) }
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val locked = try { chatLockRepo.get(lockChatId) != null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        val processUnlocked = com.maodouchat.security.ChatLockSession.isUnlocked(lockChatId)
        _uiState.update {
            it.copy(
                isChatLocked = locked,
                // Process-scoped unlock survives detail→media navigation; no lock → always open.
                isChatUnlocked = if (locked) (it.isChatUnlocked || processUnlocked) else true
            )
        }
    }
}

/**
 * 校验 PIN。成功返回 true 并标记本进程已解锁；结果经 [onResult] 回调，**不阻塞调用方**。
 *
 * G328c 更正：这段 KDoc 此前写着「在主线程调用并使用 `runBlocking(Dispatchers.IO)`
 * 同步等待 Room 查询」——**与代码不符**：实现早已改成 `viewModelScope.launch` +
 * `withContext(Dispatchers.IO)`，文件里也没有任何 `runBlocking`。
 * 审计正是靠这条过期注释把本函数记成了「主线程阻塞、有 ANR 风险」，
 * 所以这里改成描述真实行为，而不是保留一个更吓人的说法。
 */
internal fun ChatDetailViewModel.unlockChatWithPin(pin: String, onResult: (Boolean) -> Unit) {
    val lockChatId = activeChatId.ifBlank { chatId }
    if (lockChatId.isBlank()) {
        onResult(true)
        return
    }
    viewModelScope.launch {
        val ok = try {
            withContext(Dispatchers.IO) {
                chatLockRepo.verify(lockChatId, pin)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) {
            com.maodouchat.security.ChatLockSession.markUnlocked(lockChatId)
            _uiState.update { it.copy(isChatUnlocked = true) }
        }
        onResult(ok)
    }
}

internal fun ChatDetailViewModel.setChatLockPin(pin: String) {
    val lockChatId = activeChatId.ifBlank { chatId }
    if (lockChatId.isBlank()) return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_LOCK)) {
        _uiState.update { it.copy(chatLockInfoMessage = text(R.string.feature_disabled_by_admin)) }
        return
    }
    if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
        _uiState.update { it.copy(chatLockInfoMessage = text(R.string.chat_lock_pin_length)) }
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val ok = chatLockRepo.setLock(lockChatId, pin)
        if (ok) {
            com.maodouchat.security.ChatLockSession.markUnlocked(lockChatId)
            _uiState.update {
                it.copy(
                    isChatLocked = true,
                    isChatUnlocked = true,
                    chatLockInfoMessage = text(R.string.chat_lock_enabled)
                )
            }
        } else {
            _uiState.update { it.copy(chatLockInfoMessage = text(R.string.chat_lock_pin_length)) }
        }
    }
}

internal fun ChatDetailViewModel.removeChatLock(pin: String) {
    val lockChatId = activeChatId.ifBlank { chatId }
    if (lockChatId.isBlank()) return
    viewModelScope.launch(Dispatchers.IO) {
        val ok = chatLockRepo.verify(lockChatId, pin)
        if (!ok) {
            _uiState.update { it.copy(chatLockInfoMessage = text(R.string.chat_lock_wrong_pin)) }
            return@launch
        }
        chatLockRepo.remove(lockChatId)
        com.maodouchat.security.ChatLockSession.clear(lockChatId)
        _uiState.update {
            it.copy(
                isChatLocked = false,
                isChatUnlocked = true,
                chatLockInfoMessage = text(R.string.chat_lock_disabled)
            )
        }
    }
}

/**
 * 忘记 PIN：清除本会话本地明文与锁。服务端仍是密文；再拉同一信封会 Duplicate，解不开。
 * 同步游标必须保留，否则会整段重拉密文变成「无法解密」。
 */
internal fun ChatDetailViewModel.forgotChatLockAndClearLocal() {
    val lockChatId = activeChatId.ifBlank { chatId }
    if (lockChatId.isBlank()) return
    val ownerUserId = currentUserId
    val cleanupSession = conversationLocalCleanupSession(ownerUserId)
    viewModelScope.launch(Dispatchers.IO) {
        if (
            ownerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        ) {
            return@launch
        }
        val cleanup = clearLocalChatContent(
            targetChatId = lockChatId,
            cleanupSession = cleanupSession,
            removePin = true,
        )
        if (!cleanup.completed) return@launch
        val messagesCleared = !cleanup.failed(ConversationLocalCleanupStep.DELETE_MESSAGES)
        val schedulesCleared = !cleanup.failed(ConversationLocalCleanupStep.CANCEL_SCHEDULED_MESSAGES)
        val lockCleared = !cleanup.failed(ConversationLocalCleanupStep.DELETE_LOCK)
        _uiState.update {
            it.copy(
                messages = if (messagesCleared) emptyList() else it.messages,
                pinnedMessages = if (messagesCleared) emptyList() else it.pinnedMessages,
                scheduledMessages = if (schedulesCleared) emptyList() else it.scheduledMessages,
                chat = if (messagesCleared) {
                    it.chat?.copy(
                        lastMessage = "",
                        lastMessageType = MessageType.TEXT,
                        unreadCount = 0,
                        markedUnread = false,
                    )
                } else {
                    it.chat
                },
                isChatLocked = if (lockCleared) false else it.isChatLocked,
                isChatUnlocked = if (lockCleared) true else it.isChatUnlocked,
                chatLockInfoMessage = if (cleanup.failures.isEmpty()) {
                    text(R.string.chat_lock_cleared_local)
                } else {
                    text(R.string.chat_lock_clear_partial)
                },
            )
        }
    }
}

/**
 * 清除本会话本地明文/索引/媒体与待发定时（保留会话、PIN、草稿、同步游标）。
 * 服务端仍是密文。清空游标再同步会 Duplicate，气泡变成「无法解密」。
 */
internal fun ChatDetailViewModel.clearLocalChatHistory() {
    val targetChatId = activeChatId.ifBlank { chatId }
    if (targetChatId.isBlank()) return
    val ownerUserId = currentUserId
    val cleanupSession = conversationLocalCleanupSession(ownerUserId)
    viewModelScope.launch(Dispatchers.IO) {
        if (
            ownerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        ) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return@launch
        }
        val cleanup = clearLocalChatContent(
            targetChatId = targetChatId,
            cleanupSession = cleanupSession,
            removePin = false,
        )
        if (!cleanup.completed) return@launch
        val messagesCleared = !cleanup.failed(ConversationLocalCleanupStep.DELETE_MESSAGES)
        val schedulesCleared = !cleanup.failed(ConversationLocalCleanupStep.CANCEL_SCHEDULED_MESSAGES)
        _uiState.update {
            it.copy(
                messages = if (messagesCleared) emptyList() else it.messages,
                pinnedMessages = if (messagesCleared) emptyList() else it.pinnedMessages,
                scheduledMessages = if (schedulesCleared) emptyList() else it.scheduledMessages,
                chat = if (messagesCleared) {
                    it.chat?.copy(
                        lastMessage = "",
                        lastMessageType = MessageType.TEXT,
                        unreadCount = 0,
                        markedUnread = false,
                    )
                } else {
                    it.chat
                },
                groupEncryptionWarning = if (cleanup.failures.isEmpty()) {
                    text(R.string.chat_clear_history_done)
                } else {
                    text(R.string.chat_clear_history_partial)
                },
            )
        }
    }
}

internal suspend fun ChatDetailViewModel.clearLocalChatContent(
    targetChatId: String,
    cleanupSession: ConversationLocalCleanupSession,
    removePin: Boolean,
) = withContext(NonCancellable) {
    conversationLocalStateCoordinator.cleanup(
        chatId = targetChatId,
        expectedSession = cleanupSession,
        mode = if (removePin) {
            ConversationLocalCleanupMode.CLEAR_HISTORY_AND_LOCK
        } else {
            ConversationLocalCleanupMode.CLEAR_HISTORY
        },
    ).also { report ->
        report.failures.forEach { failure ->
            Log.w(
                "ChatDetailViewModel",
                "local conversation cleanup failed at ${failure.step} for $targetChatId",
                failure.error,
            )
        }
    }
}
