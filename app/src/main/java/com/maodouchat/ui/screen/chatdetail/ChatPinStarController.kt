package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatPinStarController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val persistMessage: suspend (Message) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
) {
    suspend fun refreshPinnedMessages(expectedUserId: String) {
        val targetChatId = chatId()
        if (expectedUserId.isBlank() || targetChatId.isBlank() || !sessionActive(expectedUserId)) return
        val liveToken = token()
        if (liveToken.isBlank()) return
        ApiService.getPinnedMessages(liveToken, targetChatId).onSuccess { response ->
            if (sessionActive(expectedUserId)) {
                updateState { it.copy(pinnedMessages = response.pins) }
            }
        }
    }

    fun togglePinMessage(messageId: String) {
        if (!enabled(RuntimeFlags.MESSAGE_PIN)) {
            warn(R.string.feature_disabled_by_admin)
            return
        }
        val state = currentState()
        val message = state.messages.find { it.id == messageId } ?: return
        val owner = ownerUserId()
        val targetChatId = chatId()
        if (token().isBlank() || owner.isBlank() || targetChatId.isBlank()) {
            warn(R.string.error_session_expired)
            return
        }
        if (!MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, message.type)) {
            warn(R.string.chat_pin_forbidden)
            return
        }
        val alreadyPinned = state.pinnedMessages.any { it.messageId == messageId }
        if (MessagePinPolicy.wouldExceedLimit(state.pinnedMessages.size, alreadyPinned)) {
            warn(R.string.chat_pin_limit, MessagePinPolicy.MAX_PINS)
            return
        }
        if (state.isTogglingPin) return
        updateState { it.copy(isTogglingPin = true) }
        scope.launch {
            try {
                if (!sessionActive(owner)) {
                    updateState {
                        it.copy(isTogglingPin = false, groupEncryptionWarning = string(R.string.error_session_expired))
                    }
                    return@launch
                }
                ApiService.togglePinnedMessage(token(), targetChatId, messageId).fold(
                    onSuccess = { response ->
                        if (!sessionActive(owner)) return@fold
                        updateState {
                            it.copy(
                                isTogglingPin = false,
                                pinnedMessages = response.pins,
                                groupEncryptionWarning = string(
                                    if (response.pinned) R.string.chat_pin_success else R.string.chat_unpin_success,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isTogglingPin = false,
                                groupEncryptionWarning = error.message ?: string(R.string.chat_pin_failed),
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
                        groupEncryptionWarning = error.message ?: string(R.string.chat_pin_failed),
                    )
                }
            }
        }
    }

    fun togglePinMessages(messageIds: List<String>, shouldPin: Boolean) {
        if (!enabled(RuntimeFlags.MESSAGE_PIN)) {
            warn(R.string.feature_disabled_by_admin)
            return
        }
        val state = currentState()
        val owner = ownerUserId()
        val targetChatId = chatId()
        if (token().isBlank() || owner.isBlank() || targetChatId.isBlank()) {
            warn(R.string.error_session_expired)
            return
        }
        if (state.isTogglingPin) return
        val pinnedIds = state.pinnedMessages.map { it.messageId }.toSet()
        val targetIds = messageIds.filter { (it in pinnedIds) != shouldPin }
        if (targetIds.isEmpty()) return
        val targets = state.messages.filter { it.id in targetIds }
        if (targets.size != targetIds.size) {
            warn(R.string.chat_pin_failed)
            return
        }
        val pinnableTargets = targets.filter {
            MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, it.type)
        }
        if (pinnableTargets.isEmpty()) {
            warn(R.string.chat_pin_forbidden)
            return
        }
        val effectiveTargetIds = pinnableTargets.map { it.id }
        if (shouldPin) {
            val newCount = pinnedIds.size + effectiveTargetIds.count { it !in pinnedIds }
            if (newCount > MessagePinPolicy.MAX_PINS) {
                warn(R.string.chat_pin_limit, MessagePinPolicy.MAX_PINS)
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
                    ApiService.togglePinnedMessage(token(), targetChatId, targetId).fold(
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
                        groupEncryptionWarning = error.message ?: string(R.string.chat_pin_failed),
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
                        sessionExpired -> string(R.string.error_session_expired)
                        failedCount > 0 && successCount > 0 -> string(
                            if (shouldPin) R.string.chat_batch_pin_partial else R.string.chat_batch_unpin_partial,
                            successCount,
                            failedCount,
                        )
                        successCount > 0 -> string(
                            if (shouldPin) R.string.chat_batch_pin_success else R.string.chat_batch_unpin_success,
                            successCount,
                        )
                        else -> string(R.string.chat_pin_failed)
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
            warn(R.string.error_session_expired)
            return
        }
        val optimistic = original.copy(starred = !original.starred)
        replaceMessage(optimistic)
        scope.launch {
            try {
                if (!sessionActive(owner)) {
                    replaceMessage(original, string(R.string.error_session_expired))
                    return@launch
                }
                ApiService.toggleStarMessage(token(), messageId).fold(
                    onSuccess = { response ->
                        if (!sessionActive(owner)) return@fold
                        val updated = original.copy(starred = response.starred)
                        replaceMessage(
                            updated,
                            string(if (response.starred) R.string.chat_starred_status else R.string.chat_unstarred_status),
                        )
                        withContext(Dispatchers.IO) { persistMessage(updated) }
                    },
                    onFailure = { error ->
                        replaceMessage(original, error.message ?: string(R.string.chat_star_failed))
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
            warn(R.string.error_session_expired)
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
                        warn(R.string.error_session_expired)
                        return@launch
                    }
                    ApiService.toggleStarMessage(token(), message.id).fold(
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
                if (shouldStar) R.string.chat_starred_status else R.string.chat_unstarred_status
            } else {
                R.string.chat_star_failed
            })
        }
    }

    private fun canStar(): Boolean {
        if (!enabled(RuntimeFlags.MESSAGE_STARRING)) {
            warn(R.string.message_starring_disabled)
            return false
        }
        if (currentState().isSecretChat == true && enabled(RuntimeFlags.SECRET_STAR_BLOCK)) {
            warn(R.string.secret_star_blocked)
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

    private fun enabled(flag: RuntimeFlags.Flag): Boolean = RuntimeFlags.isEnabled(application, flag)

    private fun warn(resourceId: Int, vararg args: Any) {
        updateState { it.copy(groupEncryptionWarning = string(resourceId, *args)) }
    }

    private fun string(resourceId: Int, vararg args: Any): String = text(resourceId, args)
}
