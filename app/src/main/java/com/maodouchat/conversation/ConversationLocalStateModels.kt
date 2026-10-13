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
