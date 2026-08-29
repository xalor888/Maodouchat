package com.maodouchat.attachment

import android.content.Context
import android.net.Uri
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.EncryptedAttachmentCrypto
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.MediaCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns cache/download/decrypt convergence for encrypted message attachments. */
internal class AttachmentDownloadCoordinator(
    context: Context,
    private val messageStore: LocalMessageStore,
    private val tokenManager: TokenManager,
    private val isSecretChat: suspend (Message) -> Boolean,
    private val onProgress: (messageId: String, completed: Long, total: Long, start: Float, end: Float) -> Unit,
    private val onMessageUpdated: (Message) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locks = ConcurrentHashMap<String, LockRef>()

    suspend fun ensureLocalAttachment(message: Message): Result<Message> {
        return withAttachmentLock(message.id) { ensureLocked(message) }
    }

    suspend fun <T> withAttachmentLock(messageId: String, block: suspend () -> T): T {
        val lock = locks.compute(messageId) { _, existing ->
            (existing ?: LockRef()).also { it.users++ }
        }!!
        return try {
            lock.mutex.withLock { block() }
        } finally {
            locks.computeIfPresent(messageId) { _, current ->
                if (current === lock) {
                    if (current.users > 1) current.also { it.users-- } else null
                } else current
            }
        }
    }

    private suspend fun ensureLocked(message: Message): Result<Message> {
        if (MediaCache.isReadableLocalUri(appContext, message.parsedContent())) {
            return Result.success(message)
        }
        val reference = message.toEncryptedAttachmentReference()
            ?: return Result.failure(AttachmentReferenceInvalidException())
        return try {
            val target = MediaCache.createAttachmentCacheFile(
                appContext,
                message.id,
                reference.fileName,
                secretChatId = if (isSecretChat(message)) message.chatId else null,
            )
            val ownerUserId = tokenManager.getUserId().orEmpty()
            requireSession(ownerUserId)
            if (!EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference)) {
                val encrypted = MediaCache.createEncryptedDownloadFile(
                    appContext,
                    reference.attachmentId,
                    message.id,
                )
                try {
                    ApiService.downloadEncryptedAttachment(
                        token = tokenManager.getToken().orEmpty(),
                        attachmentId = reference.attachmentId,
                        expectedSha256 = reference.cipherSha256,
                        expectedSize = reference.cipherSize,
                        target = encrypted,
                    ) { completed, total ->
                        requireSession(ownerUserId)
                        onProgress(message.id, completed, total, 0f, 0.7f)
                    }.getOrThrow()
                    EncryptedAttachmentCrypto.decrypt(encrypted, target, reference) { completed, total ->
                        requireSession(ownerUserId)
                        onProgress(message.id, completed, total, 0.7f, 1f)
                    }
                } finally {
                    encrypted.delete()
                }
            }
            requireSession(ownerUserId)
            val meta = message.parsedMeta()
            val updated = message.copy(
                content = JsonFormat.composeContentWithMeta(Uri.fromFile(target).toString(), meta),
                meta = meta,
            )
            messageStore.insertMessage(updated)
            onMessageUpdated(updated)
            Result.success(updated)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun requireSession(ownerUserId: String) {
        if (ownerUserId.isBlank() || !BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw CancellationException("attachment_download_session_changed")
        }
    }

    private class LockRef(val mutex: Mutex = Mutex(), var users: Int = 0)
}

internal class AttachmentReferenceInvalidException : IllegalStateException("attachment_reference_invalid")

private fun Message.toEncryptedAttachmentReference(): MediaCache.EncryptedAttachmentReference? {
    val metadata = parsedMeta()
    val reference = MediaCache.EncryptedAttachmentReference(
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
        MediaCache.decodeEncryptedAttachmentReference(MediaCache.encodeEncryptedAttachmentReference(reference))
    }.getOrNull()
}
