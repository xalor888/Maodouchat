package com.maodouchat.conversation

import com.maodouchat.MaodouchatApp
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.local.entity.MessageMutationTombstoneKind
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import com.maodouchat.scheduling.ConversationScheduleResult
import com.maodouchat.security.ChatLockSession
import com.maodouchat.security.SecretChatSession
import com.maodouchat.security.SecureSessionManager
import com.maodouchat.util.AppNotifier
import com.maodouchat.util.MediaCache

internal fun createAndroidConversationLocalStateCoordinator(
    app: MaodouchatApp,
    tokenManager: TokenManager,
    scheduleCoordinator: ConversationScheduleCoordinator,
): ConversationLocalStateCoordinator {
    val database = app.database
    val messageStore = LocalMessageStore(database.messageDao(), database)
    val chatRepository = ChatRepository(database.chatDao(), database.userDao())
    val aiTaskRepository = AiTaskRepository(database.aiTaskDao(), app)
    val aiOperationRepository = AiOperationRepository(database.aiOperationDao())
    val aiSummaryRepository = AiSummaryRepository(database.aiSummaryCacheDao())
    return ConversationLocalStateCoordinator(
        currentSession = {
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (
                ownerUserId.isBlank() ||
                SecureSessionManager.isPurgeInProgress()
            ) {
                null
            } else {
                ConversationLocalCleanupSession(
                    ownerUserId = ownerUserId,
                    generation = MaodouchatApp.currentSessionGeneration(),
                )
            }
        },
        backend = object : ConversationLocalStateBackend {
            override suspend fun messageIds(chatId: String) =
                messageStore.getMessageIdsByChatId(chatId)

            override suspend fun tombstoneMessages(ownerUserId: String, chatId: String) {
                database.messagingV2Dao().tombstoneConversationMessages(
                    ownerUserId = ownerUserId,
                    conversationId = chatId,
                    kind = MessageMutationTombstoneKind.CLEAR_HISTORY,
                    terminalAt = System.currentTimeMillis(),
                )
            }

            override suspend fun cancelAttachments(chatId: String) =
                AttachmentTransferCoordinator.cancelForChat(app, chatId)

            override suspend fun cancelScheduledMessages(ownerUserId: String, chatId: String) {
                when (val result = scheduleCoordinator.cancelAllForOwner(ownerUserId, chatId)) {
                    is ConversationScheduleResult.Success -> Unit
                    is ConversationScheduleResult.Failure -> throw result.cause
                        ?: IllegalStateException("conversation_schedule_cleanup_${result.reason.name.lowercase()}")
                }
            }

            override suspend fun cancelReminders(ownerUserId: String, chatId: String) {
                scheduleCoordinator.clearRemindersForOwner(ownerUserId, chatId)
            }

            override suspend fun deleteAiTasks(chatId: String) =
                aiTaskRepository.deleteByChatId(chatId)

            override suspend fun deleteAiOperations(ownerUserId: String, chatId: String) =
                aiOperationRepository.deleteByChatId(ownerUserId, chatId)

            override suspend fun deleteAiSummaries(chatId: String) =
                aiSummaryRepository.deleteByChatId(chatId)

            override suspend fun deleteDraft(ownerUserId: String, chatId: String) =
                database.chatDraftDao().deleteForChat(ownerUserId, chatId)

            override suspend fun deleteLock(chatId: String) = database.chatLockDao().remove(chatId)

            override suspend fun clearLockSession(chatId: String) = ChatLockSession.clear(chatId)

            override suspend fun deleteSecretTtl(chatId: String) =
                database.secretChatDao().remove(chatId)

            override suspend fun deactivateSecretSession(chatId: String) =
                SecretChatSession.markSurfaceInactive(chatId, app)

            override suspend fun deleteSenderKeyRetry(ownerUserId: String, chatId: String) =
                database.senderKeyRetryDao().delete(ownerUserId, chatId)

            override suspend fun invalidateSenderKey(chatId: String) {
                app.signalProtocol.invalidateGroupSenderKey(chatId)
            }

            override suspend fun clearMessagingV2State(
                session: ConversationLocalCleanupSession,
                chatId: String,
                serverParticipantStateDeleted: Boolean,
            ) {
                app.messagingV2Runtime.clearConversationState(
                    ownerUserId = session.ownerUserId,
                    sessionGeneration = session.generation,
                    conversationId = chatId,
                    serverParticipantStateDeleted = serverParticipantStateDeleted,
                )
            }

            override suspend fun clearAttachmentWire(ownerUserId: String, chatId: String) {
                database.attachmentTransferDao().clearWireContentForChat(
                    chatId = chatId,
                    ownerUserId = ownerUserId,
                )
            }

            override suspend fun deleteMedia(messageId: String) {
                MediaCache.deleteCachedMediaForMessage(app, messageId)
            }

            override suspend fun deleteSearchIndex(chatId: String) =
                database.messageSearchDao().deleteChatIndex(chatId)

            override suspend fun clearSyncCursors(chatId: String) {
                tokenManager.clearChatCursors(chatId)
            }

            override suspend fun removeNotificationItems(chatId: String) =
                app.notificationCenter.removeChatItems(chatId)

            override suspend fun cancelMessageNotification(chatId: String) =
                AppNotifier.cancelMessage(app, chatId)

            override suspend fun cancelAiReminders(chatId: String) =
                AppNotifier.cancelAiTaskRemindersForChat(app, chatId)

            override suspend fun deleteMessages(chatId: String) =
                messageStore.deleteMessagesByChatId(chatId)

            override suspend fun resetConversationPreview(chatId: String) {
                val local = chatRepository.getChatById(chatId) ?: return
                chatRepository.cacheChats(
                    listOf(
                        local.copy(
                            lastMessage = "",
                            lastMessageType = MessageType.TEXT,
                            unreadCount = 0,
                            markedUnread = false,
                        ),
                    ),
                )
            }

            override suspend fun deleteConversation(chatId: String) =
                chatRepository.deleteChat(chatId)
        },
    )
}

internal fun conversationLocalCleanupSession(
    ownerUserId: String,
): ConversationLocalCleanupSession = ConversationLocalCleanupSession(
    ownerUserId = ownerUserId,
    generation = MaodouchatApp.currentSessionGeneration(),
)
