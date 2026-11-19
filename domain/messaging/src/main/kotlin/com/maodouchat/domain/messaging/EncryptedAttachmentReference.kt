package com.maodouchat.domain.messaging

import kotlinx.serialization.Serializable

private const val ATTACHMENT_PAYLOAD_KIND = "maodouchat-attachment-v1"

@Serializable
data class EncryptedAttachmentReference(
    val kind: String = ATTACHMENT_PAYLOAD_KIND,
    val attachmentId: String,
    val keyBase64: String,
    val ivBase64: String,
    val cipherSha256: String,
    val plainSha256: String,
    val cipherSize: Long,
    val fileName: String,
    val mimeType: String,
    val plainSize: Long,
    val durationMs: Long? = null
)
