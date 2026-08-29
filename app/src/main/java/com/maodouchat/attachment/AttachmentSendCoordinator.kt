package com.maodouchat.attachment

import android.content.Context
import android.net.Uri
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CancellationException

internal data class AttachmentSendRequest(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val initialMetadata: MediaCache.LocalFileMetadata,
    val voiceDurationMs: Long?,
    val optimisticMessage: Message,
)

internal sealed interface AttachmentSendResult {
    data object ExistingTransfer : AttachmentSendResult
    data class Queued(val message: Message) : AttachmentSendResult
}

/**
 * Owns the handoff from a chat command to the durable attachment transfer pipeline.
 * It deliberately has no UI or Compose dependencies; after the handoff, WorkManager and the
 * finalizer are the only owners of upload/send convergence.
 */
internal class AttachmentSendCoordinator(
    context: Context,
    private val messageStore: LocalMessageStore,
    private val tokenManager: TokenManager,
    private val resolveChatId: suspend () -> Result<String>,
    private val onEncryptionProgress: (messageId: String, completed: Long, total: Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val app = appContext as com.maodouchat.MaodouchatApp

    suspend fun prepareAndEnqueue(
        request: AttachmentSendRequest,
        lease: AttachmentPreparationLease,
    ): AttachmentSendResult {
        val ownerUserId = tokenManager.getUserId().orEmpty().takeIf(String::isNotBlank)
            ?: throw IllegalStateException("attachment_owner_missing")
        if (tokenManager.getToken().orEmpty().isBlank()) {
            throw IllegalStateException("attachment_token_missing")
        }
        ensureSession(ownerUserId)

        val existingTransfer = app.database.attachmentTransferDao().get(request.messageId, ownerUserId)
        if (existingTransfer != null) {
            return AttachmentSendResult.ExistingTransfer
        }

        val executor = AttachmentPreparationExecutor(
            context = appContext,
            ownerUserId = tokenManager::getUserId,
            resolveChatId = resolveChatId,
            onEncryptionProgress = { _, completed, total ->
                ensureSession(ownerUserId)
                onEncryptionProgress(request.messageId, completed, total)
            },
        )
        var queuedMessage: Message? = null
        executor.prepareAndEnqueue(
            request = AttachmentPreparationRequest(
                messageId = request.messageId,
                sourceUri = request.sourceUri,
                type = request.type,
                initialMetadata = request.initialMetadata,
                voiceDurationMs = request.voiceDurationMs,
            ),
            lease = lease,
            persistPreparedMessage = { prepared ->
                ensureSession(ownerUserId)
                val queuedMeta = request.optimisticMessage.parsedMeta().copy(
                    fileName = prepared.fileName,
                    fileMimeType = prepared.mimeType,
                    fileSizeBytes = prepared.plainSize,
                )
                queuedMessage = request.optimisticMessage.copy(
                    chatId = prepared.chatId,
                    content = JsonFormat.composeContentWithMeta(prepared.sourceUri, queuedMeta),
                    meta = queuedMeta,
                    status = MessageStatus.SENDING,
                )
                messageStore.insertMessage(checkNotNull(queuedMessage))
            },
        )
        return AttachmentSendResult.Queued(checkNotNull(queuedMessage) { "attachment_durable_message_missing" })
    }

    private fun ensureSession(ownerUserId: String) {
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw CancellationException("attachment_prepare_session_changed")
        }
    }
}
