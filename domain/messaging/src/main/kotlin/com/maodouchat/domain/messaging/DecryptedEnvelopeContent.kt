package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import com.maodouchat.core.model.MessagingV2ContentPolicy
import kotlinx.serialization.json.Json

private val decryptedEnvelopeJson = Json { ignoreUnknownKeys = false }

/**
 * Decides whether freshly decrypted plaintext carries committable content.
 * Returns the content, or null when the plaintext does not decode or the
 * content fails the content policy for its envelope kind — a sender-controlled
 * kind must never smuggle a mismatched payload into the timeline.
 */
fun decryptedEnvelopeContent(kind: String, plaintext: String): MessagingV2Content? {
    val content = runCatching {
        decryptedEnvelopeJson.decodeFromString<MessagingV2Content>(plaintext)
    }.getOrNull() ?: return null
    return content.takeIf { MessagingV2ContentPolicy.accepts(kind, it) }
}
