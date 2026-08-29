package com.maodouchat.conversation

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.ContentPayload
import com.maodouchat.messaging.v2.ConversationMessageStagingGateway
import com.maodouchat.messaging.v2.MessagingV2MessageGatewayOutcome
import java.util.UUID

sealed interface ConversationCommandOutcome {
    data class Staged(val message: Message) : ConversationCommandOutcome
    data class Rejected(val reason: ConversationCommandRejection) : ConversationCommandOutcome
}

enum class ConversationCommandRejection {
    EMPTY_TEXT,
    CHAT_UNAVAILABLE,
    TERMINAL_MESSAGE,
    SECRET_CONVERSATION,
    LOCKED_CONVERSATION,
}

/**
 * Injectable message command API for conversation entry points.
 *
 * It owns content normalization, terminal tombstone handling, and privacy admission. Existing UI
 * adapters may retain their direct gateway use while they migrate to this API.
 */
class ConversationCommandFacade(
    private val gateway: ConversationMessageStagingGateway,
    private val privacyPolicy: ConversationPrivacyPolicy = ConversationPrivacyPolicy(),
    private val messageId: () -> String = { "m_${UUID.randomUUID()}" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sendText(
        chat: Chat,
        ownerUserId: String,
        text: String,
        metadata: MessageMeta = MessageMeta(),
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
    ): ConversationCommandOutcome {
        val normalized = text.trim()
        if (normalized.isBlank()) return ConversationCommandOutcome.Rejected(ConversationCommandRejection.EMPTY_TEXT)
        val type = if (com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(normalized)) {
            MessageType.MARKDOWN
        } else {
            MessageType.TEXT
        }
        return stage(
            capability = ConversationCapability.SEND_TEXT,
            privacy = privacy,
            message = Message(
                id = messageId(),
                chatId = chat.id,
                senderId = ownerUserId,
                content = normalized,
                type = type,
                timestamp = now(),
                status = MessageStatus.SENDING,
                meta = metadata.copy(markdown = type == MessageType.MARKDOWN),
            ),
            payload = ContentPayload(
                type = type,
                body = normalized,
                metadata = metadata.copy(markdown = type == MessageType.MARKDOWN),
            ),
            groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        )
    }

    suspend fun retry(
        chat: Chat,
        message: Message,
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
    ): ConversationCommandOutcome = stage(
        capability = ConversationCapability.RETRY_MESSAGE,
        privacy = privacy,
        message = message,
        payload = ContentPayload(
            type = message.type,
            body = message.parsedContent(),
            metadata = message.parsedMeta(),
        ),
        groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        retry = true,
    )

    suspend fun forwardText(
        target: Chat,
        ownerUserId: String,
        source: Message,
        sourceName: String?,
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = target.isSecret),
    ): ConversationCommandOutcome {
        val sourceMeta = source.parsedMeta()
        val metadata = sourceMeta.copy(forwardedFrom = sourceMeta.forwardedFrom ?: sourceName)
        return stage(
            capability = ConversationCapability.FORWARD,
            privacy = privacy,
            message = Message(
                id = messageId(),
                chatId = target.id,
                senderId = ownerUserId,
                content = source.parsedContent(),
                type = source.type,
                timestamp = now(),
                status = MessageStatus.SENDING,
                meta = metadata,
            ),
            payload = ContentPayload(
                type = source.type,
                body = source.parsedContent(),
                metadata = metadata,
            ),
            groupRevision = target.memberRevision.takeIf { target.isGroup && it > 0L },
        )
    }

    suspend fun stageScheduledText(
        chat: Chat,
        ownerUserId: String,
        text: String,
        deterministicMessageId: String,
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
    ): ConversationCommandOutcome = stageTextWithId(
        capability = ConversationCapability.SCHEDULE,
        chat = chat,
        ownerUserId = ownerUserId,
        text = text,
        deterministicMessageId = deterministicMessageId,
        privacy = privacy,
    )

    suspend fun stageQuickReply(
        chat: Chat,
        ownerUserId: String,
        text: String,
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
    ): ConversationCommandOutcome = stageTextWithId(
        capability = ConversationCapability.QUICK_REPLY,
        chat = chat,
        ownerUserId = ownerUserId,
        text = text,
        deterministicMessageId = messageId(),
        privacy = privacy,
    )

    private suspend fun stageTextWithId(
        capability: ConversationCapability,
        chat: Chat,
        ownerUserId: String,
        text: String,
        deterministicMessageId: String,
        privacy: ConversationPrivacyContext,
    ): ConversationCommandOutcome {
        val normalized = text.trim()
        if (normalized.isBlank()) return ConversationCommandOutcome.Rejected(ConversationCommandRejection.EMPTY_TEXT)
        val type = if (com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(normalized)) {
            MessageType.MARKDOWN
        } else {
            MessageType.TEXT
        }
        val metadata = MessageMeta(markdown = type == MessageType.MARKDOWN)
        return stage(
            capability = capability,
            privacy = privacy,
            message = Message(
                id = deterministicMessageId,
                chatId = chat.id,
                senderId = ownerUserId,
                content = normalized,
                type = type,
                timestamp = now(),
                status = MessageStatus.SENDING,
                meta = metadata,
            ),
            payload = ContentPayload(type = type, body = normalized, metadata = metadata),
            groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        )
    }

    private suspend fun stage(
        capability: ConversationCapability,
        privacy: ConversationPrivacyContext,
        message: Message,
        payload: ContentPayload,
        groupRevision: Long?,
        retry: Boolean = false,
    ): ConversationCommandOutcome {
        when (val decision = privacyPolicy.evaluate(capability, privacy)) {
            ConversationPrivacyDecision.Allowed -> Unit
            is ConversationPrivacyDecision.Rejected -> return ConversationCommandOutcome.Rejected(
                decision.reason.toCommandRejection(),
            )
        }
        return when (
            val staged = if (retry) gateway.retry(message, payload, groupRevision)
            else gateway.stage(message, payload, groupRevision)
        ) {
            is MessagingV2MessageGatewayOutcome.Staged -> ConversationCommandOutcome.Staged(staged.message)
            is MessagingV2MessageGatewayOutcome.Rejected.TerminalTombstone -> {
                ConversationCommandOutcome.Rejected(ConversationCommandRejection.TERMINAL_MESSAGE)
            }
        }
    }

    private fun ConversationPrivacyRejection.toCommandRejection(): ConversationCommandRejection = when (this) {
        ConversationPrivacyRejection.SECRET_CONVERSATION -> ConversationCommandRejection.SECRET_CONVERSATION
        ConversationPrivacyRejection.LOCKED_CONVERSATION -> ConversationCommandRejection.LOCKED_CONVERSATION
    }
}
