package com.maodouchat.quickreply

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.domain.messaging.ContentPayload
import com.maodouchat.domain.messaging.QuickReplyRequest
import com.maodouchat.domain.messaging.QuickReplyUseCase
import com.maodouchat.domain.messaging.SendMessageCommand
import com.maodouchat.domain.messaging.SendMessageResult
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.DefaultSecretConversationController

import com.maodouchat.widget.ConversationWidgetData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M11 快捷回复统一用例实现。
 *
 * 同时服务通知栏 RemoteInput 与桌面小组件，
 * 通过 [ConversationCommandFacade] 统一调度入队出站，不直接碰底层 DAO 与 raw crypto。
 */
class DefaultQuickReplyUseCase(
    private val context: Context,
    private val commandFacade: ConversationCommandFacade,
    private val secretConversationController: DefaultSecretConversationController,
    private val tokenManager: TokenManager = TokenManager.getInstance(context),
) : QuickReplyUseCase {

    override suspend fun reply(request: QuickReplyRequest): Result<Unit> = withContext(Dispatchers.IO) {
        val domainValid = com.maodouchat.domain.messaging.QuickReplyPolicy.validate(
            request.conversationId,
            request.text,
        )
        if (!domainValid) {
            return@withContext Result.failure(IllegalArgumentException("invalid_input"))
        }

        val sanitizedText = QuickReplyPolicy.sanitizeReplyText(request.text)
        if (sanitizedText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("empty_text"))
        }

        if (!QuickReplyPolicy.isEnabled(context)) {
            return@withContext Result.failure(IllegalStateException("quick_reply_disabled"))
        }

        val liveUserId = tokenManager.getUserId().orEmpty()
        val liveToken = tokenManager.getToken().orEmpty()
        if (liveUserId.isBlank() || liveToken.isBlank() ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = liveUserId,
                liveToken = liveToken,
                liveUserId = liveUserId,
            )
        ) {
            return@withContext Result.failure(IllegalStateException("not_logged_in"))
        }

        val caps = secretConversationController.capabilities(request.conversationId)
        if (caps.isSecretChat) {
            return@withContext Result.failure(IllegalStateException("secret_chat"))
        }
        if (caps.isLocked) {
            return@withContext Result.failure(IllegalStateException("chat_locked"))
        }

        val effectiveDedupeKey = if (request.idempotencyKey.isNotBlank()) {
            request.idempotencyKey
        } else {
            QuickReplyPolicy.dedupeKey(liveUserId, request.conversationId, sanitizedText)
        }

        if (QuickReplyPolicy.shouldSuppressDuplicate(context, liveUserId, request.conversationId, sanitizedText)) {
            // 重复回复：安全静默降级（防止系统多次回调 RemoteInput 发送两条相同消息）
            return@withContext Result.success(Unit)
        }

        try {
            val command = SendMessageCommand(
                conversationId = request.conversationId,
                content = ContentPayload.Text(sanitizedText),
                idempotencyKey = effectiveDedupeKey,
            )
            when (val sendResult = commandFacade.send(command)) {
                is SendMessageResult.Success -> {
                    QuickReplyPolicy.rememberSent(context, liveUserId, request.conversationId, sanitizedText)
                    MessageNotificationService.cancelMessage(context, request.conversationId)
                    MaodouchatApp.emitMessageSent(
                        chatId = request.conversationId,
                        previewText = sanitizedText,
                        messageTypeWire = "TEXT",
                        playSendSound = false,
                    )
                    ConversationWidgetData.refreshAll(context)
                    Result.success(Unit)
                }
                is SendMessageResult.Failure -> {
                    Result.failure(IllegalStateException("send_failed: ${sendResult.reason}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
