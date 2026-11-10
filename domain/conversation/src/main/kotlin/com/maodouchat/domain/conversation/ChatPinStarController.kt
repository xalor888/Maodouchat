package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessagePinPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.ChatDetailUiState

/**
 * 置顶/星标操作向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * pinStarMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class PinStarMessage {
    FEATURE_DISABLED_BY_ADMIN,
    SESSION_EXPIRED,
    PIN_FORBIDDEN,
    PIN_LIMIT,
    PIN_SUCCESS,
    UNPIN_SUCCESS,
    PIN_FAILED,
    BATCH_PIN_PARTIAL,
    BATCH_UNPIN_PARTIAL,
    BATCH_PIN_SUCCESS,
    BATCH_UNPIN_SUCCESS,
    STARRING_DISABLED,
    SECRET_STAR_BLOCKED,
    STARRED,
    UNSTARRED,
    STAR_FAILED,
}

class ChatPinStarController(
    private val messagePinEnabled: () -> Boolean,
    private val messageStarringEnabled: () -> Boolean,
    private val secretStarBlockEnabled: () -> Boolean,
    private val pinStarGateway: PinStarGateway,
    private val scope: CoroutineScope,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val persistMessage: suspend (Message) -> Unit,
    private val text: (PinStarMessage, Array<out Any>) -> String,
) {
    suspend fun refreshPinnedMessages(expectedUserId: String) {
        val targetChatId = chatId()
        if (expectedUserId.isBlank() || targetChatId.isBlank() || !sessionActive(expectedUserId)) return
        val liveToken = token()
        if (liveToken.isBlank()) return
        pinStarGateway.pinned(liveToken, targetChatId).onSuccess { response ->
            if (sessionActive(expectedUserId)) {
                updateState { it.copy(pinnedMessages = response.pins) }
            }
        }
    }

    fun togglePinMessage(messageId: String) {
        if (!messagePinEnabled()) {
            warn(PinStarMessage.FEATURE_DISABLED_BY_ADMIN)
            return
        }
        val state = currentState()
        val message = state.messages.find { it.id == messageId } ?: return
        val owner = ownerUserId()
        val targetChatId = chatId()
        if (token().isBlank() || owner.isBlank() || targetChatId.isBlank()) {
            warn(PinStarMessage.SESSION_EXPIRED)
            return
        }
        if (!MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, message.type)) {
            warn(PinStarMessage.PIN_FORBIDDEN)
            return
        }
        val alreadyPinned = state.pinnedMessages.any { it.messageId == messageId }
        if (MessagePinPolicy.wouldExceedLimit(state.pinnedMessages.size, alreadyPinned)) {
            warn(PinStarMessage.PIN_LIMIT, MessagePinPolicy.MAX_PINS)
            return
        }
        if (state.isTogglingPin) return
        updateState { it.copy(isTogglingPin = true) }
        scope.launch {
            try {
                if (!sessionActive(owner)) {
                    updateState {
                        it.copy(isTogglingPin = false, groupEncryptionWarning = string(PinStarMessage.SESSION_EXPIRED))
                    }
                    return@launch
                }
                pinStarGateway.togglePin(token(), targetChatId, messageId).fold(
                    onSuccess = { response ->
                        if (!sessionActive(owner)) return@fold
                        updateState {
                            it.copy(
                                isTogglingPin = false,
                                pinnedMessages = response.pins,
                                groupEncryptionWarning = string(
                                    if (response.pinned) PinStarMessage.PIN_SUCCESS else PinStarMessage.UNPIN_SUCCESS,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isTogglingPin = false,
                                groupEncryptionWarning = error.message ?: string(PinStarMessage.PIN_FAILED),
                            )
                        }
                    },
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isTogglingPin = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isTogglingPin = false,
                        groupEncryptionWarning = error.message ?: string(PinStarMessage.PIN_FAILED),
                    )
                }
            }
        }
    }

    fun togglePinMessages(messageIds: List<String>, shouldPin: Boolean) {
        if (!messagePinEnabled()) {
            warn(PinStarMessage.FEATURE_DISABLED_BY_ADMIN)
            return
        }
        val state = currentState()
        val owner = ownerUserId()
        val targetChatId = chatId()
        if (token().isBlank() || owner.isBlank() || targetChatId.isBlank()) {
            warn(PinStarMessage.SESSION_EXPIRED)
            return
        }
        if (state.isTogglingPin) return
        val pinnedIds = state.pinnedMessages.map { it.messageId }.toSet()
        val targetIds = messageIds.filter { (it in pinnedIds) != shouldPin }
        if (targetIds.isEmpty()) return
        val targets = state.messages.filter { it.id in targetIds }
        if (targets.size != targetIds.size) {
            warn(PinStarMessage.PIN_FAILED)
            return
        }
        val pinnableTargets = targets.filter {
            MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, it.type)
        }
        if (pinnableTargets.isEmpty()) {
            warn(PinStarMessage.PIN_FORBIDDEN)
            return
        }
        val effectiveTargetIds = pinnableTargets.map { it.id }
        if (shouldPin) {
            val newCount = pinnedIds.size + effectiveTargetIds.count { it !in pinnedIds }
            if (newCount > MessagePinPolicy.MAX_PINS) {
                warn(PinStarMessage.PIN_LIMIT, MessagePinPolicy.MAX_PINS)
                return
            }
        }
        updateState { it.copy(isTogglingPin = true) }
        scope.launch {
            var currentPins = state.pinnedMessages
            var successCount = 0
            var sessionExpired = false
            try {
                for (targetId in effectiveTargetIds) {
                    if (!sessionActive(owner)) {
                        sessionExpired = true
                        break
                    }
                    pinStarGateway.togglePin(token(), targetChatId, targetId).fold(
                        onSuccess = { response ->
                            successCount += 1
                            currentPins = response.pins
                        },
                        onFailure = {},
                    )
                }
            } catch (error: CancellationException) {
                updateState { it.copy(isTogglingPin = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isTogglingPin = false,
                        groupEncryptionWarning = error.message ?: string(PinStarMessage.PIN_FAILED),
                    )
                }
                return@launch
            }
            val failedCount = effectiveTargetIds.size - successCount
            updateState {
                it.copy(
                    isTogglingPin = false,
                    pinnedMessages = currentPins,
                    groupEncryptionWarning = when {
                        sessionExpired -> string(PinStarMessage.SESSION_EXPIRED)
                        failedCount > 0 && successCount > 0 -> string(
                            if (shouldPin) PinStarMessage.BATCH_PIN_PARTIAL else PinStarMessage.BATCH_UNPIN_PARTIAL,
                            successCount,
                            failedCount,
                        )
                        successCount > 0 -> string(
                            if (shouldPin) PinStarMessage.BATCH_PIN_SUCCESS else PinStarMessage.BATCH_UNPIN_SUCCESS,
                            successCount,
                        )
                        else -> string(PinStarMessage.PIN_FAILED)
                    },
                )
            }
        }
    }

    fun toggleStarMessage(messageId: String) {
        if (!canStar()) return
        val original = currentState().messages.find { it.id == messageId } ?: return
        val owner = ownerUserId()
        if (token().isBlank() || owner.isBlank()) {
            warn(PinStarMessage.SESSION_EXPIRED)
            return
        }
        val optimistic = original.copy(starred = !original.starred)
        replaceMessage(optimistic)
        scope.launch {
            try {
                if (!sessionActive(owner)) {
                    replaceMessage(original, string(PinStarMessage.SESSION_EXPIRED))
                    return@launch
                }
                pinStarGateway.toggleStar(token(), messageId).fold(
                    onSuccess = { response ->
                        if (!sessionActive(owner)) return@fold
                        val updated = original.copy(starred = response.starred)
                        replaceMessage(
                            updated,
                            string(if (response.starred) PinStarMessage.STARRED else PinStarMessage.UNSTARRED),
                        )
                        withContext(Dispatchers.IO) { persistMessage(updated) }
                    },
                    onFailure = { error ->
                        replaceMessage(original, error.message ?: string(PinStarMessage.STAR_FAILED))
                    },
                )
            } catch (error: CancellationException) {
                replaceMessage(original)
                throw error
            }
        }
    }

    fun toggleStarMessagesBatch(messageIds: List<String>, shouldStar: Boolean) {
        if (!canStar()) return
        val owner = ownerUserId()
        if (token().isBlank() || owner.isBlank()) {
            warn(PinStarMessage.SESSION_EXPIRED)
            return
        }
        val idSet = messageIds.toSet()
        val targets = currentState().messages.filter { it.id in idSet && it.starred != shouldStar }
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }.toSet()
        updateState { state ->
            state.copy(messages = state.messages.map { if (it.id in targetIds) it.copy(starred = shouldStar) else it })
        }
        scope.launch {
            var failed = 0
            try {
                for (message in targets) {
                    if (!sessionActive(owner)) {
                        warn(PinStarMessage.SESSION_EXPIRED)
                        return@launch
                    }
                    pinStarGateway.toggleStar(token(), message.id).fold(
                        onSuccess = { response ->
                            val updated = message.copy(starred = response.starred)
                            replaceMessage(updated)
                            withContext(Dispatchers.IO) { persistMessage(updated) }
                        },
                        onFailure = {
                            failed++
                            replaceMessage(message)
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            }
            warn(if (failed == 0) {
                if (shouldStar) PinStarMessage.STARRED else PinStarMessage.UNSTARRED
            } else {
                PinStarMessage.STAR_FAILED
            })
        }
    }

    private fun canStar(): Boolean {
        if (!messageStarringEnabled()) {
            warn(PinStarMessage.STARRING_DISABLED)
            return false
        }
        if (currentState().isSecretChat == true && secretStarBlockEnabled()) {
            warn(PinStarMessage.SECRET_STAR_BLOCKED)
            return false
        }
        return true
    }

    private fun replaceMessage(message: Message, warning: String? = null) {
        updateState { state ->
            state.copy(
                messages = state.messages.map { if (it.id == message.id) message else it },
                groupEncryptionWarning = warning ?: state.groupEncryptionWarning,
            )
        }
    }

    private fun warn(message: PinStarMessage, vararg args: Any) {
        updateState { it.copy(groupEncryptionWarning = string(message, *args)) }
    }

    private fun string(message: PinStarMessage, vararg args: Any): String = text(message, args)
}
