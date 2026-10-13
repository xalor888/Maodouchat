package com.maodouchat.conversation

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
