package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DecodedContentPayload
import com.maodouchat.core.model.EncryptedAttachmentReference
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType

/** M02：DATA 内容投影——把解码后的 content 与 metadata 投影为本地时间线可展示形式。附件引用解码与 URI 生成由调用方注入（app 侧 MediaCache），本模块不碰文件系统。 */
object MessageContentProjection {

    private val ATTACHMENT_TYPES = setOf(
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE,
        MessageType.FILE,
    )

    fun projectContent(
        payload: DecodedContentPayload,
        decodeReference: (String) -> EncryptedAttachmentReference?,
        attachmentUri: (String) -> String,
    ): String {
        if (payload.type !in ATTACHMENT_TYPES) return payload.body
        val reference = decodeReference(payload.body) ?: return payload.body
        return attachmentUri(reference.attachmentId)
    }

    fun projectMetadata(
        payload: DecodedContentPayload,
        decodeReference: (String) -> EncryptedAttachmentReference?,
    ): MessageMeta {
        if (payload.type !in ATTACHMENT_TYPES) return payload.metadata
        val reference = decodeReference(payload.body) ?: return payload.metadata
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
