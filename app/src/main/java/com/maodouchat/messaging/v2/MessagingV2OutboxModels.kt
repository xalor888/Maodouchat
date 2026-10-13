package com.maodouchat.messaging.v2

import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2

data class PreparedMessageV2(
    val groupRevision: Long?,
    val attachmentIds: List<String> = emptyList(),
    val envelopes: List<EncryptedDeviceEnvelopeRequestV2>,
)

fun interface MessagingV2EnvelopePreparer {
    /** Resolves the current membership/device snapshot and encrypts for every destination device. */
    suspend fun prepare(token: String, message: MessagingV2OutboxEntity): PreparedMessageV2
}
