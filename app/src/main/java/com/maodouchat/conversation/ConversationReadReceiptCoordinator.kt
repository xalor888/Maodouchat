package com.maodouchat.conversation

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.MessagingV2Outbox
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationIntentPolicy
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.DefaultSecretConversationController
import com.maodouchat.security.SecureSessionManager

import com.maodouchat.widget.ConversationWidgetData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一会话已读协调器（M11）。
 *
 * 规范收敛：Widget、通知动作、前台会话等各入口标记已读均走统一协调器，
 * 避免 Widget/通知直连 DAO、ChatRepository 和 outbox。
 */
interface ConversationReadReceiptCoordinator {
    suspend fun markRead(chatId: String, expectedUserId: String? = null): Result<Unit>
}

class DefaultConversationReadReceiptCoordinator(
    private val context: Context,
    private val database: AppDatabase,
    private val outbox: MessagingV2Outbox,
    private val secretConversationController: DefaultSecretConversationController,
    private val tokenManager: TokenManager = TokenManager.getInstance(context),
    private val chatRepository: ChatRepository = ChatRepository(database.chatDao(), database.userDao()),
) : ConversationReadReceiptCoordinator {

    override suspend fun markRead(chatId: String, expectedUserId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        if (chatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("blank_chat_id"))
        }
        val liveUserId = tokenManager.getUserId().orEmpty()
        val liveToken = tokenManager.getToken().orEmpty()

        if (expectedUserId != null) {
            if (!NotificationIntentPolicy.belongsToCurrentAccount(
                    notificationOwnerUserId = expectedUserId,
                    currentUserId = liveUserId,
                    sessionPurgeInProgress = SecureSessionManager.isPurgeInProgress(),
                )
            ) {
                return@withContext Result.failure(IllegalStateException("account_mismatch"))
            }
        }

        if (liveUserId.isBlank() || liveToken.isBlank() ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId ?: liveUserId,
                liveToken = liveToken,
                liveUserId = liveUserId,
            )
        ) {
            return@withContext Result.failure(IllegalStateException("invalid_session"))
        }

        try {
            val chat = database.chatDao().getChatById(chatId)
            val caps = secretConversationController.capabilities(chatId)
            val isSecret = caps.isSecretChat || chat?.chatType == "SECRET"

            if (!isSecret) {
                val boundary = LocalMessageStore(
                    database.messageDao(),
                    database,
                ).getLatestIncomingMessage(chatId, liveUserId)
                if (boundary != null) {
                    runCatching {
                        outbox.enqueueReadReceipt(
                            conversationId = chatId,
                            throughMessageId = boundary.id,
                            groupRevision = chat?.memberRevision?.takeIf { chat.isGroup },
                        )
                    }.onFailure { err ->
                        android.util.Log.w("ReadReceiptCoord", "enqueueReadReceipt v2 failed", err)
                    }
                }
            }

            chatRepository.markChatRead(chatId)
            MaodouchatApp.emitChatRead(chatId)
            MessageNotificationService.cancelMessage(context, chatId)
            ConversationWidgetData.refreshAll(context)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
