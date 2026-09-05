package com.maodouchat.attachment

import android.content.Context
import android.net.Uri
import com.maodouchat.data.model.MessageType
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentKind
import com.maodouchat.domain.messaging.AttachmentPreparationService
import com.maodouchat.domain.messaging.PreparedAttachmentResult
import com.maodouchat.util.EncryptedAttachmentCrypto
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * M07: 默认附件准备服务实现。
 * 负责文件探测、图片压缩、元数据规范化与 AES-GCM 本地加密。
 */
class DefaultAttachmentPreparationService(
    private val context: Context,
) : AttachmentPreparationService {

    private val appContext = context.applicationContext

    override suspend fun prepare(
        intent: AttachmentIntent,
        ownerUserId: String,
        onProgress: (completed: Long, total: Long) -> Unit
    ): Result<PreparedAttachmentResult> = runCatching {
        require(ownerUserId.isNotBlank()) { "attachment_owner_missing" }
        val sourceUri = Uri.parse(intent.uri)
        val messageType = intent.kind.toMessageType()

        val described = withContext(Dispatchers.IO) {
            MediaCache.describeFile(appContext, sourceUri)
        }
        val rawMetadata = MediaCache.LocalFileMetadata(
            fileName = intent.fileName ?: described.fileName,
            mimeType = intent.mimeType ?: described.mimeType,
            sizeBytes = described.sizeBytes.takeIf { it > 0L } ?: 0L
        )
        if (rawMetadata.sizeBytes > MediaCache.MAX_ATTACHMENT_PLAIN_BYTES) {
            throw AttachmentTooLargeException()
        }
        val metadata = normalizeAttachmentMetadata(messageType, rawMetadata)
        validateAttachmentContent(appContext, sourceUri, messageType)

        val messageId = if (intent.idempotencyKey.isNotBlank()) intent.idempotencyKey else "m_${UUID.randomUUID()}"

        val (actualSourceUri, actualMetadata) = if (messageType == MessageType.IMAGE) {
            val target = MediaCache.createPreparedAttachmentSource(appContext, messageId, ".jpg")
            val compressed = ImagePicker.compressToFile(appContext, sourceUri, target)
                ?: throw IllegalStateException("attachment_image_prepare_failed")
            val compressedUri = Uri.fromFile(compressed)
            compressedUri to MediaCache.LocalFileMetadata(
                fileName = metadata.fileName.substringBeforeLast('.', metadata.fileName).ifBlank { "image" } + ".jpg",
                mimeType = "image/jpeg",
                sizeBytes = compressed.length()
            )
        } else {
            sourceUri to metadata
        }

        val encrypted = withContext(Dispatchers.IO) {
            EncryptedAttachmentCrypto.encrypt(
                context = appContext,
                uri = actualSourceUri,
                expectedPlainSize = actualMetadata.sizeBytes,
                onProgress = onProgress
            )
        }

        PreparedAttachmentResult(
            messageId = messageId,
            sourceUri = actualSourceUri.toString(),
            encryptedPath = encrypted.file.absolutePath,
            plainSize = encrypted.plainSize,
            cipherSize = encrypted.cipherSize,
            mimeType = actualMetadata.mimeType,
            fileName = actualMetadata.fileName,
            keyBase64 = encrypted.keyBase64,
            ivBase64 = encrypted.ivBase64,
            cipherSha256 = encrypted.cipherSha256,
            plainSha256 = encrypted.plainSha256,
            durationMs = intent.durationMs
        )
    }

    private fun AttachmentKind.toMessageType(): MessageType = when (this) {
        AttachmentKind.IMAGE -> MessageType.IMAGE
        AttachmentKind.VIDEO -> MessageType.VIDEO
        AttachmentKind.VOICE -> MessageType.VOICE
        AttachmentKind.FILE -> MessageType.FILE
        AttachmentKind.STICKER -> MessageType.STICKER
        AttachmentKind.GIF -> MessageType.GIF
        AttachmentKind.LOCATION -> MessageType.LOCATION
        AttachmentKind.CONTACT -> MessageType.TEXT
    }
}
