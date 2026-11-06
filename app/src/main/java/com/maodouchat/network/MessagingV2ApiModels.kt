package com.maodouchat.network

import com.maodouchat.core.model.PendingEnvelopeV2Dto
import com.maodouchat.data.local.entity.MessagingV2InboxEntity

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
