package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 拉黑/举报：状态机与会话门禁是纯编排，网络调用走 ChatModerationGateway 端口，
// 功能开关走 moderationEnabled 端口（app 侧读 RuntimeFlags）。
class ChatModerationController(
    private val moderationGateway: ChatModerationGateway,
    private val moderationEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (ChatModerationMessage, Array<out Any>) -> String,
) {
    fun refreshBlockState(contactId: String = currentState().contact.id) {
        val owner = ownerUserId()
        if (contactId.isBlank() || token().isBlank() || owner.isBlank()) return
        scope.launch {
            if (!sessionActive(owner)) return@launch
            moderationGateway.blockedUserIds(token()).onSuccess { blocked ->
                if (sessionActive(owner)) updateState { it.copy(isContactBlocked = contactId in blocked) }
            }
        }
    }

    fun blockContact() {
        val contactId = currentState().contact.id
        val owner = ownerUserId()
        if (contactId.isBlank() || contactId == owner) return
        if (!enabled()) {
            warn(ChatModerationMessage.FEATURE_DISABLED_BY_ADMIN)
            return
        }
        if (token().isBlank() || owner.isBlank()) {
            warn(ChatModerationMessage.SESSION_EXPIRED)
            return
        }
        if (currentState().isBlockingContact) return
        scope.launch {
            updateState { it.copy(isBlockingContact = true, groupEncryptionWarning = null) }
            try {
                if (!sessionActive(owner)) {
                    updateState { it.copy(isBlockingContact = false) }
                    return@launch
                }
                moderationGateway.blockUser(token(), contactId).fold(
                    onSuccess = {
                        if (!sessionActive(owner)) return@fold
                        updateState {
                            it.copy(
                                isContactBlocked = true,
                                isBlockingContact = false,
                                groupEncryptionWarning = string(ChatModerationMessage.CHAT_BLOCKED_USER_STATUS, it.contact.displayName),
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isBlockingContact = false,
                                groupEncryptionWarning = error.message ?: string(ChatModerationMessage.CHAT_BLOCK_FAILED),
                            )
                        }
                    },
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isBlockingContact = false) }
                throw error
            }
        }
    }

    fun unblockContact() {
        val contactId = currentState().contact.id
        val owner = ownerUserId()
        if (contactId.isBlank()) return
        if (token().isBlank() || owner.isBlank()) {
            warn(ChatModerationMessage.SESSION_EXPIRED)
            return
        }
        if (currentState().isBlockingContact) return
        scope.launch {
            updateState { it.copy(isBlockingContact = true, groupEncryptionWarning = null) }
            try {
                if (!sessionActive(owner)) {
                    updateState { it.copy(isBlockingContact = false) }
                    return@launch
                }
                moderationGateway.unblockUser(token(), contactId).fold(
                    onSuccess = {
                        if (!sessionActive(owner)) return@fold
                        updateState {
                            it.copy(
                                isContactBlocked = false,
                                isBlockingContact = false,
                                groupEncryptionWarning = string(ChatModerationMessage.CHAT_UNBLOCKED_USER_STATUS, it.contact.displayName),
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isBlockingContact = false,
                                groupEncryptionWarning = error.message ?: string(ChatModerationMessage.CHAT_UNBLOCK_FAILED),
                            )
                        }
                    },
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isBlockingContact = false) }
                throw error
            }
        }
    }

    fun reportContact(reason: String, description: String? = null) {
        val contactId = currentState().contact.id
        val owner = ownerUserId()
        if (contactId.isBlank() || contactId == owner) return
        if (!enabled()) {
            warn(ChatModerationMessage.FEATURE_DISABLED_BY_ADMIN)
            return
        }
        if (token().isBlank() || owner.isBlank()) {
            warn(ChatModerationMessage.SESSION_EXPIRED)
            return
        }
        scope.launch {
            if (!sessionActive(owner)) return@launch
            moderationGateway.createReport(
                token = token(),
                targetType = "USER",
                targetId = contactId,
                chatId = activeChatId().takeIf { it.isNotBlank() },
                messageId = null,
                reason = reason,
                description = description,
            ).fold(
                onSuccess = {
                    if (sessionActive(owner)) warn(ChatModerationMessage.CHAT_REPORT_SUBMITTED)
                },
                onFailure = { error -> warn(error.message ?: string(ChatModerationMessage.CHAT_REPORT_FAILED)) },
            )
        }
    }

    fun reportMessage(messageId: String, reason: String, description: String? = null) {
        if (!enabled()) {
            warn(ChatModerationMessage.FEATURE_DISABLED_BY_ADMIN)
            return
        }
        val owner = ownerUserId()
        if (messageId.isBlank()) return
        if (token().isBlank() || owner.isBlank()) {
            warn(ChatModerationMessage.SESSION_EXPIRED)
            return
        }
        if (currentState().isReporting) return
        updateState { it.copy(isReporting = true) }
        scope.launch {
            if (!sessionActive(owner)) return@launch
            moderationGateway.createReport(
                token = token(),
                targetType = "MESSAGE",
                targetId = messageId,
                chatId = activeChatId().takeIf { it.isNotBlank() },
                messageId = messageId,
                reason = reason,
                description = description,
            ).fold(
                onSuccess = {
                    updateState { it.copy(isReporting = false) }
                    if (sessionActive(owner)) warn(ChatModerationMessage.CHAT_REPORT_SUBMITTED)
                },
                onFailure = { error ->
                    updateState {
                        it.copy(
                            groupEncryptionWarning = error.message ?: string(ChatModerationMessage.CHAT_REPORT_FAILED),
                            isReporting = false,
                        )
                    }
                },
            )
        }
    }

    private fun enabled(): Boolean = moderationEnabled()

    private fun warn(message: ChatModerationMessage, vararg args: Any) = warn(string(message, *args))

    private fun warn(message: String) {
        updateState { it.copy(groupEncryptionWarning = message) }
    }

    private fun string(message: ChatModerationMessage, vararg args: Any): String = text(message, args)
}
