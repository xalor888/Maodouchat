package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import kotlinx.serialization.json.Json

private val outboxJson = Json { ignoreUnknownKeys = false }

/**
 * Reads the attachment ids carried by a durable outbox payload. Unparseable payloads
 * (pre-v2 rows, truncated writes) contribute nothing instead of failing the send.
 */
fun outboxAttachmentIds(localPayload: String): List<String> =
    runCatching {
        outboxJson.decodeFromString<MessagingV2Content>(localPayload).attachmentIds
    }.getOrDefault(emptyList())
