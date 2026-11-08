package com.maodouchat.core.model


sealed interface MessagingV2MessageGatewayOutcome {
    data class Staged(val message: Message) : MessagingV2MessageGatewayOutcome

    sealed interface Rejected : MessagingV2MessageGatewayOutcome {
        data class TerminalTombstone(val messageId: String) : Rejected
    }
}

/** Injectable staging boundary used by conversation commands and focused JVM tests. */
interface ConversationMessageStagingGateway {
    suspend fun stage(
        message: Message,
        payload: DecodedContentPayload,
        groupRevision: Long? = null,
    ): MessagingV2MessageGatewayOutcome

    suspend fun retry(
        message: Message,
        payload: DecodedContentPayload,
        groupRevision: Long? = null,
    ): MessagingV2MessageGatewayOutcome

    suspend fun cancel(messageId: String): Boolean = false
}
