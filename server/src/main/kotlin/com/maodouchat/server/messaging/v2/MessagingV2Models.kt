package com.maodouchat.server.messaging.v2

import kotlinx.serialization.Serializable

object MessagingV2RecordClass {
    const val MESSAGE = "MESSAGE"
    const val EVENT = "EVENT"
    const val INTERNAL = "INTERNAL"
}

@Serializable
data class ServiceMessagingV2Content(
    val version: Int = 1,
    val type: String,
    val body: String = "",
    val event: ServiceMessagingV2Event? = null,
)

@Serializable
data class ServiceMessagingV2Event(
    val action: String,
    val targetMessageId: String,
    val content: String? = null,
    val editedAt: Long? = null,
    val reactionEmoji: String? = null,
)

@Serializable
data class EncryptedDeviceEnvelopeRequest(
    val recipientUserId: String,
    val recipientDeviceId: Int,
    val ciphertextType: String,
    val ciphertext: String,
)

@Serializable
data class SendMessageV2Request(
    val id: String,
    val conversationId: String,
    val kind: String,
    val clientTimestamp: Long,
    val groupRevision: Long? = null,
    val attachmentIds: List<String> = emptyList(),
    val envelopes: List<EncryptedDeviceEnvelopeRequest>,
)

@Serializable
data class SendMessageV2Response(
    val messageId: String,
    val serverTimestamp: Long,
    val envelopeCount: Int,
    val idempotentReplay: Boolean,
)

@Serializable
data class PendingEnvelopeV2(
    val envelopeId: String,
    val sequence: Long,
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: Int,
    val kind: String,
    val groupRevision: Long? = null,
    val clientTimestamp: Long,
    val serverTimestamp: Long,
    val ciphertextType: String,
    val ciphertext: String,
)

@Serializable
data class PendingInboxV2Response(
    val envelopes: List<PendingEnvelopeV2>,
    val hasMore: Boolean,
)

@Serializable
data class AcknowledgeEnvelopesV2Request(val envelopeIds: List<String>)

@Serializable
data class AcknowledgeEnvelopesV2Response(val acknowledged: Int)

@Serializable
data class ConversationDeviceTargetV2(
    val userId: String,
    val deviceId: Int,
)

@Serializable
data class ConversationSnapshotV2Response(
    val conversationId: String,
    val isGroup: Boolean,
    val memberRevision: Long,
    val participantUserIds: List<String>,
    val targets: List<ConversationDeviceTargetV2>,
)

data class DeviceTarget(val userId: String, val deviceId: Int)

data class OutboundEnvelope(
    val target: DeviceTarget,
    val ciphertextType: String,
    val ciphertext: String,
)

data class SendMessageV2Command(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: Int,
    val kind: String,
    val clientTimestamp: Long,
    val groupRevision: Long?,
    val attachmentIds: List<String> = emptyList(),
    val envelopes: List<OutboundEnvelope>,
)

data class SendMessageV2Result(
    val messageId: String,
    val serverTimestamp: Long,
    val envelopeCount: Int,
    val idempotentReplay: Boolean,
    val recipientUserIds: Set<String>,
)

class MessagingV2DuplicateMessageException : IllegalArgumentException("message id already has different content")
class MessagingV2ConversationNotFoundException : IllegalArgumentException("conversation not found")
class MessagingV2NotParticipantException : IllegalStateException("sender is not a conversation participant")
class MessagingV2SenderMutedException : IllegalStateException("sender is muted")
class MessagingV2SenderRestrictedException : IllegalStateException("sender is restricted")
class MessagingV2BlockedConversationException : IllegalStateException("direct conversation is blocked")
class MessagingV2ChannelReadOnlyException : IllegalStateException("channel is read only for member")
class MessagingV2RateLimitedException : IllegalStateException("message rate limit exceeded")
class MessagingV2RevisionMismatchException(val expected: Long) :
    IllegalStateException("group revision mismatch")

class MessagingV2CoverageException(
    val missing: Set<DeviceTarget>,
    val unexpected: Set<DeviceTarget>,
) : IllegalArgumentException("encrypted device coverage does not match current membership")

class MessagingV2AttachmentNotReadyException : IllegalStateException("attachment is not uploaded or does not belong to message")
class MessagingV2ProtocolViolationException : IllegalArgumentException("invalid messaging v2 protocol command")
