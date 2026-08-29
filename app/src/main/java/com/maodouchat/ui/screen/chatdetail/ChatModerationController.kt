package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ChatModerationController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
) {
    fun refreshBlockState(contactId: String = currentState().contact.id) {
        val owner = ownerUserId()
        if (contactId.isBlank() || token().isBlank() || owner.isBlank()) return
        scope.launch {
            if (!sessionActive(owner)) return@launch
            ApiService.getBlockedUsers(token()).onSuccess { blocked ->
                if (sessionActive(owner)) updateState { it.copy(isContactBlocked = contactId in blocked) }
            }
        }
    }

    fun blockContact() {
        val contactId = currentState().contact.id
        val owner = ownerUserId()
        if (contactId.isBlank() || contactId == owner) return
        if (!enabled()) {
            warn(R.string.feature_disabled_by_admin)
            return
        }
        if (token().isBlank() || owner.isBlank()) {
            warn(R.string.error_session_expired)
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
                ApiService.blockUser(token(), contactId).fold(
                    onSuccess = {
                        if (!sessionActive(owner)) return@fold
                        updateState {
                            it.copy(
                                isContactBlocked = true,
                                isBlockingContact = false,
                                groupEncryptionWarning = string(R.string.chat_blocked_user_status, it.contact.displayName),
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isBlockingContact = false,
                                groupEncryptionWarning = error.message ?: string(R.string.chat_block_failed),
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
            warn(R.string.error_session_expired)
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
                ApiService.unblockUser(token(), contactId).fold(
                    onSuccess = {
                        if (!sessionActive(owner)) return@fold
                        updateState {
                            it.copy(
                                isContactBlocked = false,
                                isBlockingContact = false,
                                groupEncryptionWarning = string(R.string.chat_unblocked_user_status, it.contact.displayName),
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isBlockingContact = false,
                                groupEncryptionWarning = error.message ?: string(R.string.chat_unblock_failed),
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
            warn(R.string.feature_disabled_by_admin)
            return
        }
        if (token().isBlank() || owner.isBlank()) {
            warn(R.string.error_session_expired)
            return
        }
        scope.launch {
            if (!sessionActive(owner)) return@launch
            ApiService.createReport(
                token = token(),
                targetType = "USER",
                targetId = contactId,
                chatId = activeChatId().takeIf { it.isNotBlank() },
                reason = reason,
                description = description,
            ).fold(
                onSuccess = {
                    if (sessionActive(owner)) warn(R.string.chat_report_submitted)
                },
                onFailure = { error -> warn(error.message ?: string(R.string.chat_report_failed)) },
            )
        }
    }

    fun reportMessage(messageId: String, reason: String, description: String? = null) {
        if (!enabled()) {
            warn(R.string.feature_disabled_by_admin)
            return
        }
        val owner = ownerUserId()
        if (messageId.isBlank()) return
        if (token().isBlank() || owner.isBlank()) {
            warn(R.string.error_session_expired)
            return
        }
        if (currentState().isReporting) return
        updateState { it.copy(isReporting = true) }
        scope.launch {
            if (!sessionActive(owner)) return@launch
            ApiService.createReport(
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
                    if (sessionActive(owner)) warn(R.string.chat_report_submitted)
                },
                onFailure = { error ->
                    updateState {
                        it.copy(
                            groupEncryptionWarning = error.message ?: string(R.string.chat_report_failed),
                            isReporting = false,
                        )
                    }
                },
            )
        }
    }

    private fun enabled(): Boolean = RuntimeFlags.isEnabled(application, RuntimeFlags.BLOCK_REPORT)

    private fun warn(resourceId: Int, vararg args: Any) = warn(string(resourceId, *args))

    private fun warn(message: String) {
        updateState { it.copy(groupEncryptionWarning = message) }
    }

    private fun string(resourceId: Int, vararg args: Any): String = text(resourceId, args)
}
