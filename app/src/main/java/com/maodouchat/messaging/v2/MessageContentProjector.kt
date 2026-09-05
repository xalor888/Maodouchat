package com.maodouchat.messaging.v2

import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache

/** M02：DATA 内容投影——把解码后的 content 与 metadata 投影为本地时间线可展示形式。 */
internal object MessageContentProjector {

    private val ATTACHMENT_TYPES = setOf(
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE,
        MessageType.FILE,
    )

    fun projectContent(payload: DecodedContentPayload): String {
        if (payload.type !in ATTACHMENT_TYPES) return payload.body
        val reference = MediaCache.decodeEncryptedAttachmentReference(payload.body) ?: return payload.body
        return MediaCache.attachmentUri(reference.attachmentId)
    }

    fun projectMetadata(payload: DecodedContentPayload): MessageMeta {
        if (payload.type !in ATTACHMENT_TYPES) return payload.metadata
        val reference = MediaCache.decodeEncryptedAttachmentReference(payload.body) ?: return payload.metadata
        return payload.metadata.copy(
            fileName = reference.fileName,
            fileMimeType = reference.mimeType,
            fileSizeBytes = reference.plainSize,
            attachmentId = reference.attachmentId,
            attachmentKeyBase64 = reference.keyBase64,
            attachmentIvBase64 = reference.ivBase64,
            attachmentCipherSha256 = reference.cipherSha256,
            attachmentPlainSha256 = reference.plainSha256,
            attachmentCipherSize = reference.cipherSize,
            voiceDurationMs = reference.durationMs,
        )
    }
}
