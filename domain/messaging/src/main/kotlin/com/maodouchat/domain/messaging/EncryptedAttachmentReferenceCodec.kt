package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.AttachmentCryptoException
import com.maodouchat.core.crypto.AttachmentCryptoFailure
import com.maodouchat.core.model.EncryptedAttachmentReference
import com.maodouchat.core.model.Message
import kotlinx.serialization.json.Json

// 加密附件引用编解码：原在 app 侧 MediaCache；两处重复的 Message→引用构造（下载协调器、本地图片 OCR）一并收敛到这里。
private const val ATTACHMENT_PAYLOAD_KIND = "maodouchat-attachment-v1"
private const val MAX_ATTACHMENT_PLAIN_BYTES = 100L * 1024L * 1024L
private const val MAX_ATTACHMENT_CIPHER_BYTES = MAX_ATTACHMENT_PLAIN_BYTES + 64L
private const val MAX_VOICE_DURATION_MS = 60L * 60L * 1_000L

private val attachmentIdRegex = Regex("^att_[A-Za-z0-9_-]{20,100}$")
private val sha256HexRegex = Regex("^[a-f0-9]{64}$")
private val filenameUnsafeCharsRegex = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

private val attachmentReferenceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeEncryptedAttachmentReference(reference: EncryptedAttachmentReference): String {
    if (!isValidAttachmentReference(reference)) {
        throw AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE)
    }
    return attachmentReferenceJson.encodeToString(EncryptedAttachmentReference.serializer(), reference.copy(
        fileName = sanitizeFileName(reference.fileName),
        mimeType = reference.mimeType.lowercase().take(100)
    ))
}

fun decodeEncryptedAttachmentReference(text: String): EncryptedAttachmentReference? {
    if (!text.startsWith('{') || text.length > 2_048) return null
    return runCatching { attachmentReferenceJson.decodeFromString(EncryptedAttachmentReference.serializer(), text) }
        .getOrNull()
        ?.takeIf(::isValidAttachmentReference)
        ?.let { it.copy(fileName = sanitizeFileName(it.fileName), mimeType = it.mimeType.lowercase().take(100)) }
}

// 由消息 meta 构造解密附件引用；字段缺失或编解码往返校验失败时返回 null。
fun Message.toEncryptedAttachmentReference(): EncryptedAttachmentReference? {
    val metadata = parsedMeta()
    val reference = EncryptedAttachmentReference(
        attachmentId = metadata.attachmentId ?: return null,
        keyBase64 = metadata.attachmentKeyBase64 ?: return null,
        ivBase64 = metadata.attachmentIvBase64 ?: return null,
        cipherSha256 = metadata.attachmentCipherSha256 ?: return null,
        plainSha256 = metadata.attachmentPlainSha256 ?: return null,
        cipherSize = metadata.attachmentCipherSize ?: return null,
        fileName = metadata.fileName ?: return null,
        mimeType = metadata.fileMimeType ?: "application/octet-stream",
        plainSize = metadata.fileSizeBytes ?: return null,
        durationMs = metadata.voiceDurationMs,
    )
    return runCatching {
        decodeEncryptedAttachmentReference(encodeEncryptedAttachmentReference(reference))
    }.getOrNull()
}

private fun isValidAttachmentReference(reference: EncryptedAttachmentReference): Boolean {
    return reference.kind == ATTACHMENT_PAYLOAD_KIND &&
        reference.attachmentId.matches(attachmentIdRegex) &&
        reference.keyBase64.length in 40..48 &&
        reference.ivBase64.length in 16..24 &&
        reference.cipherSha256.matches(sha256HexRegex) &&
        reference.plainSha256.matches(sha256HexRegex) &&
        reference.cipherSize in 17L..MAX_ATTACHMENT_CIPHER_BYTES &&
        reference.plainSize in 1L..MAX_ATTACHMENT_PLAIN_BYTES &&
        (reference.durationMs == null || reference.durationMs in 500L..MAX_VOICE_DURATION_MS) &&
        reference.fileName.isNotBlank() && reference.fileName.length <= 120 &&
        reference.mimeType.isNotBlank() && reference.mimeType.length <= 100
}

private fun sanitizeFileName(value: String): String {
    return value
        .replace(filenameUnsafeCharsRegex, "_")
        .trim()
        .trim('.')
        .take(120)
        .ifBlank { "file" }
}
