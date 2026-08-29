package com.maodouchat.conversation

internal data class ConversationLocalCleanupSession(
    val ownerUserId: String,
    val generation: Long,
)

internal enum class ConversationLocalCleanupMode {
    CLEAR_HISTORY,
    CLEAR_HISTORY_AND_LOCK,
    DELETE_CONVERSATION,
}

internal enum class ConversationLocalCleanupStep {
    CAPTURE_MESSAGE_IDS,
    TOMBSTONE_MESSAGES,
    CANCEL_ATTACHMENTS,
    CANCEL_SCHEDULED_MESSAGES,
    CANCEL_REMINDERS,
    DELETE_AI_TASKS,
    DELETE_AI_OPERATIONS,
    DELETE_AI_SUMMARIES,
    DELETE_DRAFT,
    DELETE_LOCK,
    CLEAR_LOCK_SESSION,
    DELETE_SECRET_TTL,
    DEACTIVATE_SECRET_SESSION,
    DELETE_SENDER_KEY_RETRY,
    INVALIDATE_SENDER_KEY,
    CLEAR_MESSAGING_V2_STATE,
    CLEAR_ATTACHMENT_WIRE,
    DELETE_MEDIA,
    DELETE_SEARCH_INDEX,
    CLEAR_SYNC_CURSORS,
    REMOVE_NOTIFICATION_ITEMS,
    CANCEL_MESSAGE_NOTIFICATION,
    CANCEL_AI_REMINDERS,
    DELETE_MESSAGES,
    RESET_CONVERSATION_PREVIEW,
    DELETE_CONVERSATION,
}

internal data class ConversationLocalCleanupFailure(
    val step: ConversationLocalCleanupStep,
    val error: Throwable,
)

internal data class ConversationLocalCleanupReport(
    val sessionChanged: Boolean,
    val failures: List<ConversationLocalCleanupFailure>,
) {
    val completed: Boolean get() = !sessionChanged

    fun failed(step: ConversationLocalCleanupStep): Boolean =
        failures.any { it.step == step }
}

internal interface ConversationLocalStateBackend {
    suspend fun messageIds(chatId: String): List<String>
    suspend fun tombstoneMessages(ownerUserId: String, chatId: String)
    suspend fun cancelAttachments(chatId: String)
    suspend fun cancelScheduledMessages(ownerUserId: String, chatId: String)
    suspend fun cancelReminders(ownerUserId: String, chatId: String)
    suspend fun deleteAiTasks(chatId: String)
    suspend fun deleteAiOperations(ownerUserId: String, chatId: String)
    suspend fun deleteAiSummaries(chatId: String)
    suspend fun deleteDraft(ownerUserId: String, chatId: String)
    suspend fun deleteLock(chatId: String)
    suspend fun clearLockSession(chatId: String)
    suspend fun deleteSecretTtl(chatId: String)
    suspend fun deactivateSecretSession(chatId: String)
    suspend fun deleteSenderKeyRetry(ownerUserId: String, chatId: String)
    suspend fun invalidateSenderKey(chatId: String)
    suspend fun clearMessagingV2State(
        session: ConversationLocalCleanupSession,
        chatId: String,
        serverParticipantStateDeleted: Boolean,
    )
    suspend fun clearAttachmentWire(ownerUserId: String, chatId: String)
    suspend fun deleteMedia(messageId: String)
    suspend fun deleteSearchIndex(chatId: String)
    suspend fun clearSyncCursors(chatId: String)
    suspend fun removeNotificationItems(chatId: String)
    suspend fun cancelMessageNotification(chatId: String)
    suspend fun cancelAiReminders(chatId: String)
    suspend fun deleteMessages(chatId: String)
    suspend fun resetConversationPreview(chatId: String)
    suspend fun deleteConversation(chatId: String)
}

/**
 * Owns destructive local conversation convergence after a user command or authoritative leave.
 * Every step is isolated so one damaged cache cannot prevent the remaining private state cleanup.
 */
internal class ConversationLocalStateCoordinator(
    private val currentSession: () -> ConversationLocalCleanupSession?,
    private val backend: ConversationLocalStateBackend,
) {
    suspend fun cleanup(
        chatId: String,
        expectedSession: ConversationLocalCleanupSession,
        mode: ConversationLocalCleanupMode,
    ): ConversationLocalCleanupReport {
        require(chatId.isNotBlank()) { "conversation_cleanup_chat_missing" }
        require(expectedSession.ownerUserId.isNotBlank()) { "conversation_cleanup_owner_missing" }

        val failures = mutableListOf<ConversationLocalCleanupFailure>()
        var sessionChanged = false
        suspend fun step(
            name: ConversationLocalCleanupStep,
            operation: suspend () -> Unit,
        ) {
            if (sessionChanged) return
            if (currentSession() != expectedSession) {
                sessionChanged = true
                return
            }
            try {
                operation()
            } catch (error: Exception) {
                failures += ConversationLocalCleanupFailure(name, error)
            }
        }

        var cachedMessageIds = emptyList<String>()
        step(ConversationLocalCleanupStep.CAPTURE_MESSAGE_IDS) {
            cachedMessageIds = backend.messageIds(chatId)
        }
        step(ConversationLocalCleanupStep.TOMBSTONE_MESSAGES) {
            backend.tombstoneMessages(expectedSession.ownerUserId, chatId)
        }
        step(ConversationLocalCleanupStep.CANCEL_ATTACHMENTS) {
            backend.cancelAttachments(chatId)
        }
        step(ConversationLocalCleanupStep.CANCEL_SCHEDULED_MESSAGES) {
            backend.cancelScheduledMessages(expectedSession.ownerUserId, chatId)
        }

        if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
            step(ConversationLocalCleanupStep.CANCEL_REMINDERS) {
                backend.cancelReminders(expectedSession.ownerUserId, chatId)
            }
            step(ConversationLocalCleanupStep.DELETE_AI_TASKS) { backend.deleteAiTasks(chatId) }
            step(ConversationLocalCleanupStep.DELETE_AI_OPERATIONS) {
                backend.deleteAiOperations(expectedSession.ownerUserId, chatId)
            }
            step(ConversationLocalCleanupStep.DELETE_AI_SUMMARIES) {
                backend.deleteAiSummaries(chatId)
            }
            step(ConversationLocalCleanupStep.DELETE_DRAFT) {
                backend.deleteDraft(expectedSession.ownerUserId, chatId)
            }
        }

        if (mode != ConversationLocalCleanupMode.CLEAR_HISTORY) {
            step(ConversationLocalCleanupStep.DELETE_LOCK) { backend.deleteLock(chatId) }
            step(ConversationLocalCleanupStep.CLEAR_LOCK_SESSION) {
                backend.clearLockSession(chatId)
            }
        }

        if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
            step(ConversationLocalCleanupStep.DELETE_SECRET_TTL) { backend.deleteSecretTtl(chatId) }
            step(ConversationLocalCleanupStep.DEACTIVATE_SECRET_SESSION) {
                backend.deactivateSecretSession(chatId)
            }
            step(ConversationLocalCleanupStep.DELETE_SENDER_KEY_RETRY) {
                backend.deleteSenderKeyRetry(expectedSession.ownerUserId, chatId)
            }
            step(ConversationLocalCleanupStep.INVALIDATE_SENDER_KEY) {
                backend.invalidateSenderKey(chatId)
            }
        }

        step(ConversationLocalCleanupStep.CLEAR_MESSAGING_V2_STATE) {
            backend.clearMessagingV2State(
                session = expectedSession,
                chatId = chatId,
                serverParticipantStateDeleted = mode == ConversationLocalCleanupMode.DELETE_CONVERSATION,
            )
        }

        step(ConversationLocalCleanupStep.CLEAR_ATTACHMENT_WIRE) {
            backend.clearAttachmentWire(expectedSession.ownerUserId, chatId)
        }
        cachedMessageIds.forEach { messageId ->
            step(ConversationLocalCleanupStep.DELETE_MEDIA) { backend.deleteMedia(messageId) }
        }
        step(ConversationLocalCleanupStep.DELETE_SEARCH_INDEX) {
            backend.deleteSearchIndex(chatId)
        }

        if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
            step(ConversationLocalCleanupStep.CLEAR_SYNC_CURSORS) {
                backend.clearSyncCursors(chatId)
            }
        }

        step(ConversationLocalCleanupStep.REMOVE_NOTIFICATION_ITEMS) {
            backend.removeNotificationItems(chatId)
        }
        step(ConversationLocalCleanupStep.CANCEL_MESSAGE_NOTIFICATION) {
            backend.cancelMessageNotification(chatId)
        }
        if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
            step(ConversationLocalCleanupStep.CANCEL_AI_REMINDERS) {
                backend.cancelAiReminders(chatId)
            }
        }
        step(ConversationLocalCleanupStep.DELETE_MESSAGES) { backend.deleteMessages(chatId) }

        if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
            step(ConversationLocalCleanupStep.DELETE_CONVERSATION) {
                backend.deleteConversation(chatId)
            }
        } else {
            step(ConversationLocalCleanupStep.RESET_CONVERSATION_PREVIEW) {
                backend.resetConversationPreview(chatId)
            }
        }

        return ConversationLocalCleanupReport(
            sessionChanged = sessionChanged,
            failures = failures.toList(),
        )
    }
}
