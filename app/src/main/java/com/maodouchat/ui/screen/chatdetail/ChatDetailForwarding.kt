package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.domain.messaging.ForwardFailureReason
import com.maodouchat.domain.messaging.ForwardRequest
import com.maodouchat.domain.messaging.ForwardTargetResult
import com.maodouchat.forwarding.ConversationForwardCoordinator
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun ChatDetailViewModel.loadForwardTargets() {
    viewModelScope.launch {
        try {
            val targets = withContext(Dispatchers.IO) {
                conversationForwardCoordinator.loadTargets(activeChatId)
            }
            _uiState.update { it.copy(forwardTargets = targets) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    groupEncryptionWarning = error.message
                        ?: text(R.string.chat_refresh_failed_cached),
                )
            }
        }
    }
}

fun ChatDetailViewModel.forwardMessage(message: Message, targetChatId: String) {
    forwardMessagesBatch(listOf(message), listOf(targetChatId), null)
}

/**
 * 转发附带留言：向指定目标会话发送一条普通文本消息。
 * 写入 v2 持久发件箱；失败保留 SENDING 由进程级运行时重试。
 */
fun ChatDetailViewModel.sendTextToChat(targetChatId: String, text: String) {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) return
    val targetChat = _uiState.value.forwardTargets.firstOrNull { it.id == targetChatId } ?: return
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                conversationForwardCoordinator.sendNote(targetChat, trimmed)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w("ChatDetailViewModel", "forward note send failed", error)
        }
    }
}

/**
 * 批量转发：将消息与附带留言委托给 ConversationForwardCoordinator 统一处理。
 * UI 仅接收逐目标结果并更新状态，不再循环调用底层发送或附件实现。
 */
fun ChatDetailViewModel.forwardMessagesBatch(messages: List<Message>, targetChatIds: List<String>, note: String?) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.message_forwarding_disabled)) }
        return
    }
    val usable = messages.filter {
        it.type in ConversationForwardCoordinator.FORWARDABLE_TYPES
    }
    if (usable.isEmpty() || targetChatIds.isEmpty()) return
    if (_uiState.value.isForwarding) return
    viewModelScope.launch {
        _uiState.update { it.copy(isForwarding = true, groupEncryptionWarning = null) }
        val requests = usable.mapIndexed { index, msg ->
            ForwardRequest(
                sourceConversationId = activeChatId,
                sourceMessageId = msg.id,
                targetConversationIds = targetChatIds,
                caption = if (index == usable.lastIndex) note?.takeIf { it.isNotBlank() } else null,
            )
        }
        val resultMap = try {
            withContext(Dispatchers.IO) {
                conversationForwardCoordinator.forwardBatch(requests)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update { it.copy(isForwarding = false) }
            throw error
        } catch (error: Throwable) {
            emptyMap()
        }
        val allResults = resultMap.values.flatten()
        val anyFailed = allResults.any { !it.success }
        val anyPinLocked = allResults.any { it.reason == ForwardFailureReason.PIN_LOCKED }
        val anyForbidden = allResults.any { it.reason == ForwardFailureReason.FORBIDDEN }
        _uiState.update {
            it.copy(
                isForwarding = false,
                groupEncryptionWarning = when {
                    allResults.isNotEmpty() && !anyFailed -> text(R.string.chat_forwarded)
                    anyPinLocked -> text(R.string.secret_chat_forward_blocked)
                    anyForbidden -> text(R.string.secret_chat_forward_blocked)
                    usable.any { msg -> msg.type in RELIABLE_ATTACHMENT_TYPES } && anyFailed -> text(R.string.chat_attachment_upload_failed)
                    else -> if (anyFailed) text(R.string.chat_refresh_failed_cached) else text(R.string.chat_forwarded)
                },
            )
        }
    }
}

internal fun ChatDetailViewModel.forwardSourceName(message: Message): String? {
    if (_uiState.value.isSecretChat == true) return null
    return _uiState.value.chat
        ?.participants
        ?.firstOrNull { it.id == message.senderId }
        ?.name
        ?.takeIf(String::isNotBlank)
}

internal fun ChatDetailViewModel.forwardPreview(type: MessageType, content: String): String = when (type) {
    MessageType.IMAGE -> text(R.string.message_preview_image)
    MessageType.GIF -> text(R.string.message_preview_gif)
    MessageType.STICKER -> text(R.string.message_preview_sticker)
    MessageType.LOCATION -> text(R.string.message_preview_location)
    MessageType.VIDEO -> text(R.string.message_preview_video)
    MessageType.VOICE -> text(R.string.message_preview_voice)
    MessageType.FILE -> text(R.string.message_preview_file)
    else -> content.take(40)
}
