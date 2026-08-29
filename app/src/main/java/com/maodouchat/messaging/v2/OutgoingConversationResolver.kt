package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Chat
import kotlinx.coroutines.CancellationException

data class OutgoingConversationRequest(
    val ownerUserId: String,
    val authToken: String,
    val activeConversationId: String,
    val constructorConversationId: String,
    val paintedConversation: Chat?,
    val activeContactId: String,
    val createSecretConversation: Boolean,
)

data class ResolvedOutgoingConversation(
    val conversation: Chat,
    val peerUserId: String?,
)

data class OutgoingConversationErrors(
    val notLoggedIn: () -> String,
    val recipientNotReady: () -> String,
    val cannotSendToSelf: () -> String,
)

/**
 * Resolves the durable conversation authority before an outgoing envelope is staged.
 * Existing local conversations stay sendable offline; network access is only required
 * when metadata is incomplete or a direct conversation has never been created.
 */
class OutgoingConversationResolver(
    private val getCachedConversation: suspend (String) -> Chat?,
    private val fetchConversations: suspend (String) -> List<Chat>,
    private val createDirectConversation: suspend (String, String, Boolean) -> Chat,
    private val cacheConversation: suspend (Chat) -> Unit,
    private val ensureLocalCryptoReady: suspend (String, String) -> Boolean,
    private val isBotUserId: (String) -> Boolean,
    private val isOwnerSessionCurrent: (String) -> Boolean,
    private val errors: OutgoingConversationErrors,
) {
    suspend fun resolve(request: OutgoingConversationRequest): Result<ResolvedOutgoingConversation> {
        return try {
            Result.success(resolveOrThrow(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun resolveOrThrow(
        request: OutgoingConversationRequest,
    ): ResolvedOutgoingConversation {
        if (request.authToken.isBlank() || request.ownerUserId.isBlank()) {
            throw IllegalStateException(errors.notLoggedIn())
        }
        requireCurrentSession(request.ownerUserId)

        val knownConversationId = knownConversationId(request)
        if (knownConversationId.isNotBlank()) {
            val painted = request.paintedConversation
                ?.takeIf { it.id == knownConversationId }
                ?.takeIf { metadataReady(it, request.ownerUserId, request.activeContactId) }
            if (painted != null) {
                return prepare(painted, request)
            }

            val cached = getCachedConversation(knownConversationId)
                ?.takeIf { metadataReady(it, request.ownerUserId, request.activeContactId) }
            if (cached != null) {
                return prepare(cached, request)
            }

            val fetched = fetchConversations(request.authToken)
                .firstOrNull { it.id == knownConversationId }
                ?: throw IllegalStateException(errors.recipientNotReady())
            requireCurrentSession(request.ownerUserId)
            cacheConversation(fetched)
            return prepare(fetched, request)
        }

        val recipientId = request.activeContactId
        if (recipientId.isBlank()) {
            throw IllegalStateException(errors.recipientNotReady())
        }
        if (recipientId == request.ownerUserId || recipientId == "me") {
            throw IllegalStateException(errors.cannotSendToSelf())
        }
        if (!isBotUserId(recipientId)) {
            requireCrypto(request)
        }

        val created = createDirectConversation(
            request.authToken,
            recipientId,
            request.createSecretConversation,
        )
        requireCurrentSession(request.ownerUserId)
        cacheConversation(created)
        return prepare(
            chat = created,
            request = request,
            cryptoAlreadyReady = !isBotUserId(recipientId),
        )
    }

    private suspend fun prepare(
        chat: Chat,
        request: OutgoingConversationRequest,
        cryptoAlreadyReady: Boolean = false,
    ): ResolvedOutgoingConversation {
        val peerUserId = directPeerId(chat, request.ownerUserId, request.activeContactId)
        if (!chat.isGroup && peerUserId == null) {
            throw IllegalStateException(errors.recipientNotReady())
        }
        val requiresCrypto = chat.isGroup || !isBotUserId(peerUserId.orEmpty())
        if (requiresCrypto && !cryptoAlreadyReady) {
            requireCrypto(request)
        }
        requireCurrentSession(request.ownerUserId)
        return ResolvedOutgoingConversation(chat, peerUserId)
    }

    private suspend fun requireCrypto(request: OutgoingConversationRequest) {
        if (!ensureLocalCryptoReady(request.authToken, request.ownerUserId)) {
            throw com.maodouchat.crypto.LocalCryptoNotReadyException()
        }
        requireCurrentSession(request.ownerUserId)
    }

    private fun requireCurrentSession(ownerUserId: String) {
        if (!isOwnerSessionCurrent(ownerUserId)) {
            throw CancellationException("resolve_outgoing_session_changed")
        }
    }

    private fun knownConversationId(request: OutgoingConversationRequest): String =
        request.activeConversationId.takeIf(String::isNotBlank)
            ?: request.constructorConversationId.takeIf(String::isNotBlank)
            ?: request.paintedConversation?.id.orEmpty().takeIf(String::isNotBlank)
            ?: ""

    private fun metadataReady(chat: Chat, ownerUserId: String, activeContactId: String): Boolean =
        chat.isGroup || directPeerId(chat, ownerUserId, activeContactId) != null

    private fun directPeerId(chat: Chat, ownerUserId: String, activeContactId: String): String? {
        if (chat.isGroup) return null
        return chat.participants
            .asSequence()
            .map { it.id }
            .firstOrNull { it.isNotBlank() && it != ownerUserId }
            ?: activeContactId.takeIf { it.isNotBlank() && it != ownerUserId && it != "me" }
    }
}
