package com.maodouchat.server.repository

import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.messaging.v2.SendMessageV2Result
import com.maodouchat.server.model.MessageResponse

/** Dedicated plaintext store for server-authored bot messages. */
class ServiceMessageRepository(
    internal val messagingV2Repository: MessagingV2Repository = MessagingV2Repository(),
)

data class ServiceMessageMetadata(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val serviceMessage: MessageResponse?,
)

sealed class PublishResult {
    data class Published(
        val message: MessageResponse,
        val mailbox: SendMessageV2Result,
    ) : PublishResult()

    data object Rejected : PublishResult()
}

internal const val MAX_CONTENT_LENGTH = 8_000
internal val NON_STORABLE_TYPES = setOf("SK_DIST", "REVOKED")
