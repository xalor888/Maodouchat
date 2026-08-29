package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.OutgoingConversationContext
import com.maodouchat.messaging.v2.OutgoingConversationErrors
import com.maodouchat.messaging.v2.OutgoingConversationRequest
import com.maodouchat.messaging.v2.OutgoingConversationResolver
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageCoordinator
import com.maodouchat.messaging.v2.OutgoingMessageResult
import kotlinx.coroutines.CancellationException

internal data class ResolvedOutgoingChat(
    val chatId: String,
    val chat: Chat,
    val peerId: String?,
)

internal class ChatOutgoingFacade(
    getCachedConversation: suspend (String) -> Chat?,
    fetchConversations: suspend (String) -> List<Chat>,
    createDirectConversation: suspend (String, String, Boolean) -> Chat,
    cacheConversation: suspend (Chat) -> Unit,
    ensureLocalCryptoReady: suspend (String, String) -> Boolean,
    isBotUserId: (String) -> Boolean,
    private val isOwnerSessionCurrent: (String) -> Boolean,
    errors: OutgoingConversationErrors,
    private val currentRequest: () -> OutgoingConversationRequest,
    private val hydrateConversation: suspend (Chat, String, String?) -> ResolvedOutgoingChat,
    stageDurableMessage: suspend (Message, Long?, String, MessageType) -> Unit,
    retryDurableMessage: suspend (Message, Long?, String, MessageType) -> Unit,
    persistFailedMessage: suspend (Message) -> Unit,
) {
    private val resolver = OutgoingConversationResolver(
        getCachedConversation = getCachedConversation,
        fetchConversations = fetchConversations,
        createDirectConversation = createDirectConversation,
        cacheConversation = cacheConversation,
        ensureLocalCryptoReady = ensureLocalCryptoReady,
        isBotUserId = isBotUserId,
        isOwnerSessionCurrent = isOwnerSessionCurrent,
        errors = errors,
    )

    private val coordinator = OutgoingMessageCoordinator(
        resolveConversation = {
            val resolved = resolve().getOrThrow()
            OutgoingConversationContext(
                conversationId = resolved.chatId,
                groupRevision = resolved.chat.memberRevision.takeIf { resolved.chat.isGroup },
                isGroup = resolved.chat.isGroup,
                peerUserId = resolved.peerId,
            )
        },
        stageDurableMessage = stageDurableMessage,
        retryDurableMessage = retryDurableMessage,
        persistFailedMessage = persistFailedMessage,
        isOwnerSessionCurrent = isOwnerSessionCurrent,
    )

    suspend fun resolve(): Result<ResolvedOutgoingChat> = try {
        val request = currentRequest()
        val resolved = resolver.resolve(request).getOrThrow()
        Result.success(hydrateConversation(resolved.conversation, request.ownerUserId, resolved.peerUserId))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun resolveChatId(): Result<String> = resolve().map { it.chatId }

    suspend fun enqueue(
        command: OutgoingMessageCommand,
        onDurableCommit: () -> Unit = {},
        onDurableFailure: () -> Unit = {},
        afterDurableCommit: suspend (OutgoingConversationContext, Message) -> Unit = { _, _ -> },
    ): OutgoingMessageResult = coordinator.enqueue(
        command = command,
        onDurableCommit = onDurableCommit,
        onDurableFailure = onDurableFailure,
        afterDurableCommit = afterDurableCommit,
    )

    suspend fun retry(command: OutgoingMessageCommand): OutgoingMessageResult = coordinator.retry(command)
}
