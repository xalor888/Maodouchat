package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.net.Uri
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.EncryptedAttachmentCrypto
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AttachmentPreparationRequest(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val initialMetadata: MediaCache.LocalFileMetadata,
    val voiceDurationMs: Long?
)

internal data class PreparedAttachment(
    val chatId: String,
    val sourceUri: String,
    val fileName: String,
    val mimeType: String,
    val plainSize: Long
)

internal class AttachmentPreparationExecutor(
    context: Context,
    private val ownerUserId: () -> String?,
    private val resolveChatId: suspend () -> Result<String>,
    private val onEncryptionProgress: (messageId: String, completed: Long, total: Long) -> Unit
) {
    private val appContext = context.applicationContext

    suspend fun prepareAndEnqueue(
        request: AttachmentPreparationRequest,
        lease: AttachmentPreparationLease
    ): PreparedAttachment {
        val ownerId = ownerUserId()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("attachment_owner_missing")
        val chatId = resolveChatId().getOrThrow()
        if (ownerUserId()?.takeIf(String::isNotBlank) != ownerId) {
            throw kotlinx.coroutines.CancellationException("attachment_prepare_session_changed")
        }
        val (sourceUri, describedMetadata) = prepareSource(request, lease)
        val metadata = normalizeAttachmentMetadata(request.type, describedMetadata)
        validateAttachmentContent(appContext, sourceUri, request.type)

        val preparationContext = currentCoroutineContext()
        val encrypted = EncryptedAttachmentCrypto.encrypt(
            context = appContext,
            uri = sourceUri,
            expectedPlainSize = metadata.sizeBytes
        ) { completed, total ->
            preparationContext.ensureActive()
            if (ownerUserId()?.takeIf(String::isNotBlank) != ownerId) {
                throw kotlinx.coroutines.CancellationException("attachment_prepare_session_changed")
            }
            onEncryptionProgress(request.messageId, completed, total)
        }
        lease.recordEncryptedPath(encrypted.file.absolutePath)
        // Re-check owner after long encrypt so logout/switch does not enqueue under next account.
        val liveOwner = ownerUserId()?.takeIf(String::isNotBlank)
        if (liveOwner == null || liveOwner != ownerId) {
            encrypted.file.delete()
            throw kotlinx.coroutines.CancellationException("attachment_prepare_session_changed")
        }
        val actualMetadata = metadata.copy(sizeBytes = encrypted.plainSize)
        val transfer = AttachmentTransferEntity(
            messageId = request.messageId,
            ownerUserId = ownerId,
            chatId = chatId,
            messageType = request.type.name,
            sourceUri = sourceUri.toString(),
            encryptedPath = encrypted.file.absolutePath,
            fileName = actualMetadata.fileName,
            mimeType = actualMetadata.mimeType,
            plainSize = encrypted.plainSize,
            durationMs = request.voiceDurationMs,
            keyBase64 = encrypted.keyBase64,
            ivBase64 = encrypted.ivBase64,
            cipherSha256 = encrypted.cipherSha256,
            plainSha256 = encrypted.plainSha256,
            cipherSize = encrypted.cipherSize
        )
        AttachmentTransferCoordinator.enqueue(appContext, transfer)
        lease.handOff()
        return PreparedAttachment(
            chatId = chatId,
            sourceUri = sourceUri.toString(),
            fileName = actualMetadata.fileName,
            mimeType = actualMetadata.mimeType,
            plainSize = encrypted.plainSize
        )
    }

    private fun prepareSource(
        request: AttachmentPreparationRequest,
        lease: AttachmentPreparationLease
    ): Pair<Uri, MediaCache.LocalFileMetadata> {
        if (request.type != MessageType.IMAGE) {
            if (request.type == MessageType.VOICE) lease.recordPreparedSource(request.sourceUri.toString())
            return request.sourceUri to request.initialMetadata
        }
        val target = MediaCache.createPreparedAttachmentSource(appContext, request.messageId, ".jpg")
        val file = ImagePicker.compressToFile(appContext, request.sourceUri, target)
            ?: throw IllegalStateException("attachment_image_prepare_failed")
        val preparedUri = Uri.fromFile(file)
        lease.recordPreparedSource(preparedUri.toString())
        return preparedUri to MediaCache.LocalFileMetadata(
            fileName = request.initialMetadata.fileName.substringBeforeLast('.', request.initialMetadata.fileName)
                .ifBlank { "image" }.take(116) + ".jpg",
            mimeType = "image/jpeg",
            sizeBytes = file.length()
        )
    }
}
