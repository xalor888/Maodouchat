package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import com.maodouchat.core.model.MessagingV2ContentPolicy
import com.maodouchat.core.model.MessagingV2ServiceEnvelopePolicy
import kotlinx.serialization.json.Json

private val serviceEnvelopeJson = Json { ignoreUnknownKeys = false }

/**
 * A SERVICE-kind envelope is plaintext from the service sender, so it never
 * goes through Signal decryption. Returns the content to commit, or null
 * when the sender fails the service policy, the ciphertext does not decode,
 * or the content fails the content policy for its kind.
 */
fun serviceEnvelopeContent(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
    kind: String,
    ciphertext: String,
): MessagingV2Content? {
    if (!MessagingV2ServiceEnvelopePolicy.accepts(senderUserId, senderDeviceId, ciphertextType)) return null
    val content = runCatching {
        serviceEnvelopeJson.decodeFromString<MessagingV2Content>(ciphertext)
    }.getOrNull() ?: return null
    return content.takeIf { MessagingV2ContentPolicy.accepts(kind, it) }
}
