package com.maodouchat.conversation

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.DecodedContentPayload
import com.maodouchat.messaging.v2.ConversationMessageStagingGateway
import com.maodouchat.messaging.v2.MessagingV2MessageGatewayOutcome
import com.maodouchat.domain.messaging.AttachmentKind
import com.maodouchat.domain.messaging.ContentPayload
import com.maodouchat.domain.messaging.SendMessageCommand
import com.maodouchat.domain.messaging.SendMessageResult
import com.maodouchat.domain.messaging.SendFailureReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val resolveChat: (suspend (conversationId: String) -> Chat?)? = null,
    private val getMessage: (suspend (messageId: String) -> Message?)? = null,
    private val ownerUserId: () -> String = { "" },
    private val messageId: () -> String = { "m_${UUID.randomUUID()}" },
    private val now: () -> Long = System::currentTimeMillis,
) : com.maodouchat.domain.messaging.ConversationCommandFacade {
    private val idempotencyResults = ConcurrentHashMap<String, SendMessageResult.Success>()
    private val stagedIdempotencyMap = ConcurrentHashMap<String, Message>()

    override suspend fun send(command: SendMessageCommand): SendMessageResult {
        if (command.idempotencyKey.isNotBlank()) {
            val cached = idempotencyResults[command.idempotencyKey]
            if (cached != null) return cached
        }
        val (body, type, meta) = mapContentPayload(command.content)
        if (body.isBlank() && type != MessageType.STICKER) {
            return SendMessageResult.Failure(SendFailureReason.VALIDATION)
        }
        val chat = resolveChat?.invoke(command.conversationId)
            ?: Chat(id = command.conversationId)
        val id = messageId()
        val outcome = stage(
            capability = ConversationCapability.SEND_TEXT,
            privacy = ConversationPrivacyContext(isSecret = chat.isSecret),
            message = Message(
                id = id,
                chatId = chat.id,
                senderId = ownerUserId(),
                content = body,
                type = type,
                timestamp = now(),
                status = MessageStatus.SENDING,
                meta = meta,
            ),
            payload = DecodedContentPayload(type = type, body = body, metadata = meta),
            groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        )
        return when (outcome) {
            is ConversationCommandOutcome.Staged -> {
                val success = SendMessageResult.Success(outcome.message.id, durableCommitted = true)
                if (command.idempotencyKey.isNotBlank()) {
                    idempotencyResults[command.idempotencyKey] = success
                }
                success
            }
            is ConversationCommandOutcome.Rejected -> when (outcome.reason) {
                ConversationCommandRejection.EMPTY_TEXT -> SendMessageResult.Failure(SendFailureReason.VALIDATION)
                ConversationCommandRejection.TERMINAL_MESSAGE -> SendMessageResult.Failure(SendFailureReason.PERMANENT)
                else -> SendMessageResult.Failure(SendFailureReason.NOT_READY)
            }
        }
    }

    override suspend fun retry(localMessageId: String): SendMessageResult {
        val message = getMessage?.invoke(localMessageId)
            ?: return SendMessageResult.Failure(SendFailureReason.NOT_READY)
        val chat = resolveChat?.invoke(message.chatId)
            ?: Chat(id = message.chatId)
        return when (val outcome = retry(chat, message)) {
            is ConversationCommandOutcome.Staged -> SendMessageResult.Success(outcome.message.id, durableCommitted = true)
            is ConversationCommandOutcome.Rejected -> when (outcome.reason) {
                ConversationCommandRejection.TERMINAL_MESSAGE -> SendMessageResult.Failure(SendFailureReason.PERMANENT)
                else -> SendMessageResult.Failure(SendFailureReason.NOT_READY)
            }
        }
    }

    override suspend fun cancel(localMessageId: String): Boolean {
        return gateway.cancel(localMessageId)
    }

    suspend fun sendText(
        chat: Chat,
        ownerUserId: String,
        text: String,
        metadata: MessageMeta = MessageMeta(),
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
        idempotencyKey: String = "",
    ): ConversationCommandOutcome {
        if (idempotencyKey.isNotBlank()) {
            val staged = stagedIdempotencyMap[idempotencyKey]
            if (staged != null) return ConversationCommandOutcome.Staged(staged)
        }
        val normalized = text.trim()
        if (normalized.isBlank()) return ConversationCommandOutcome.Rejected(ConversationCommandRejection.EMPTY_TEXT)
        val type = if (com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(normalized)) {
            MessageType.MARKDOWN
        } else {
            MessageType.TEXT
        }
        val outcome = stage(
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
            payload = DecodedContentPayload(
                type = type,
                body = normalized,
                metadata = metadata.copy(markdown = type == MessageType.MARKDOWN),
            ),
            groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        )
        if (idempotencyKey.isNotBlank() && outcome is ConversationCommandOutcome.Staged) {
            stagedIdempotencyMap[idempotencyKey] = outcome.message
        }
        return outcome
    }

    suspend fun sendInline(
        chat: Chat,
        ownerUserId: String,
        content: String,
        type: MessageType,
        metadata: MessageMeta = MessageMeta(),
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
        idempotencyKey: String = "",
    ): ConversationCommandOutcome {
        if (idempotencyKey.isNotBlank()) {
            val staged = stagedIdempotencyMap[idempotencyKey]
            if (staged != null) return ConversationCommandOutcome.Staged(staged)
        }
        val outcome = stage(
            capability = ConversationCapability.SEND_TEXT,
            privacy = privacy,
            message = Message(
                id = messageId(),
                chatId = chat.id,
                senderId = ownerUserId,
                content = content,
                type = type,
                timestamp = now(),
                status = MessageStatus.SENDING,
                meta = metadata,
            ),
            payload = DecodedContentPayload(
                type = type,
                body = content,
                metadata = metadata,
            ),
            groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        )
        if (idempotencyKey.isNotBlank() && outcome is ConversationCommandOutcome.Staged) {
            stagedIdempotencyMap[idempotencyKey] = outcome.message
        }
        return outcome
    }

    private fun mapContentPayload(content: ContentPayload): Triple<String, MessageType, MessageMeta> = when (content) {
        is ContentPayload.Text -> {
            val trimmed = content.text.trim()
            val isMd = com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(trimmed)
            val msgType = if (isMd) MessageType.MARKDOWN else MessageType.TEXT
            val meta = MessageMeta(
                mentions = content.mentions.map { it.userId },
                markdown = isMd,
            )
            Triple(trimmed, msgType, meta)
        }
        is ContentPayload.Reply -> {
            val (innerBody, innerType, innerMeta) = mapContentPayload(content.content)
            Triple(innerBody, innerType, innerMeta.copy(replyToId = content.replyToMessageId))
        }
        is ContentPayload.Attachment -> {
            val msgType = when (content.kind) {
                AttachmentKind.IMAGE -> MessageType.IMAGE
                AttachmentKind.VIDEO -> MessageType.VIDEO
                AttachmentKind.VOICE -> MessageType.VOICE
                AttachmentKind.FILE -> MessageType.FILE
                AttachmentKind.STICKER -> MessageType.STICKER
                AttachmentKind.GIF -> MessageType.GIF
                AttachmentKind.LOCATION -> MessageType.LOCATION
                AttachmentKind.CONTACT -> MessageType.TEXT
            }
            val meta = MessageMeta(
                attachmentId = content.attachmentId,
                fileName = content.fileName,
                fileSizeBytes = content.sizeBytes,
                fileMimeType = content.mimeType,
                voiceDurationMs = content.durationMs,
            )
            Triple(content.attachmentId, msgType, meta)
        }
        is ContentPayload.Location -> {
            Triple("${content.latitude},${content.longitude}", MessageType.LOCATION, MessageMeta())
        }
        is ContentPayload.Contact -> {
            Triple(content.userId, MessageType.TEXT, MessageMeta())
        }
        is ContentPayload.Poll -> {
            Triple(content.pollId, MessageType.TEXT, MessageMeta())
        }
        is ContentPayload.SystemEvent -> {
            Triple(content.data ?: content.eventType, MessageType.SYSTEM, MessageMeta())
        }
        is ContentPayload.Unknown -> {
            Triple(content.rawPayload, MessageType.TEXT, MessageMeta())
        }
    }


    suspend fun retry(
        chat: Chat,
        message: Message,
        privacy: ConversationPrivacyContext = ConversationPrivacyContext(isSecret = chat.isSecret),
    ): ConversationCommandOutcome = stage(
        capability = ConversationCapability.RETRY_MESSAGE,
        privacy = privacy,
        message = message,
        payload = DecodedContentPayload(
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
            payload = DecodedContentPayload(
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
            payload = DecodedContentPayload(type = type, body = normalized, metadata = metadata),
            groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
        )
    }

    private suspend fun stage(
        capability: ConversationCapability,
        privacy: ConversationPrivacyContext,
        message: Message,
        payload: DecodedContentPayload,
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
