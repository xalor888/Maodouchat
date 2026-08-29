package com.maodouchat.network

import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import kotlinx.serialization.Serializable

@Serializable
data class EncryptedDeviceEnvelopeRequestV2(
    val recipientUserId: String,
    val recipientDeviceId: Int,
    val ciphertextType: String,
    val ciphertext: String,
)

@Serializable
data class SendMessageRequestV2(
    val id: String,
    val conversationId: String,
    val kind: String,
    val clientTimestamp: Long,
    val groupRevision: Long? = null,
    val attachmentIds: List<String> = emptyList(),
    val envelopes: List<EncryptedDeviceEnvelopeRequestV2>,
)

@Serializable
data class SendMessageResponseV2(
    val messageId: String,
    val serverTimestamp: Long,
    val envelopeCount: Int,
    val idempotentReplay: Boolean,
)

@Serializable
data class PendingEnvelopeV2Dto(
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
data class PendingInboxResponseV2(
    val envelopes: List<PendingEnvelopeV2Dto>,
    val hasMore: Boolean,
)

@Serializable
data class AcknowledgeEnvelopesRequestV2(val envelopeIds: List<String>)

@Serializable
data class AcknowledgeEnvelopesResponseV2(val acknowledged: Int)

@Serializable
data class ConversationDeviceTargetV2Dto(
    val userId: String,
    val deviceId: Int,
)

@Serializable
data class ConversationSnapshotV2Dto(
    val conversationId: String,
    val isGroup: Boolean,
    val memberRevision: Long,
    val participantUserIds: List<String>,
    val targets: List<ConversationDeviceTargetV2Dto>,
)

fun PendingEnvelopeV2Dto.toEntity(
    ownerUserId: String,
    deviceId: Int,
    now: Long,
): MessagingV2InboxEntity = MessagingV2InboxEntity(
    envelopeId = envelopeId,
    ownerUserId = ownerUserId,
    deviceId = deviceId,
    sequence = sequence,
    messageId = messageId,
    conversationId = conversationId,
    senderUserId = senderUserId,
    senderDeviceId = senderDeviceId,
    kind = kind,
    groupRevision = groupRevision,
    clientTimestamp = clientTimestamp,
    serverTimestamp = serverTimestamp,
    ciphertextType = ciphertextType,
    ciphertext = ciphertext,
    receivedAt = now,
    updatedAt = now,
)
