package com.maodouchat.attachment

import android.content.Context
import android.net.Uri
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AttachmentSendCommand(
    val messageId: String,
    val sourceUri: Uri,
    val type: MessageType,
    val chatId: String,
    val senderId: String,
    val existingMessage: Message? = null,
    val forwardedFrom: String? = null,
    val metadataOverride: MediaCache.LocalFileMetadata? = null,
    val voiceDurationMs: Long? = null,
    val viewOnce: Boolean = false,
    val spoilerMedia: Boolean = false,
)

internal sealed interface AttachmentSendWorkflowResult {
    data class Existing(val message: Message) : AttachmentSendWorkflowResult
    data class Queued(val message: Message) : AttachmentSendWorkflowResult
}

/**
 * Application-level attachment send workflow.
 *
 * This is intentionally independent from Compose and ViewModel state. It owns the
 * policy decisions that must be identical for image/video/voice/file sends:
 * session pinning, metadata normalization, optimistic persistence, and durable
 * handoff to the attachment transfer outbox.
 */
internal class AttachmentSendWorkflow(
    context: Context,
    private val messageStore: LocalMessageStore,
    private val tokenManager: TokenManager,
    private val resolveChatId: suspend () -> Result<String>,
    private val onEncryptionProgress: (messageId: String, completed: Long, total: Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val app = appContext as MaodouchatApp

    suspend fun execute(
        command: AttachmentSendCommand,
        lease: AttachmentPreparationLease,
        onOptimisticMessage: (Message) -> Unit = {},
    ): AttachmentSendWorkflowResult {
        require(command.type in RELIABLE_ATTACHMENT_TYPES) { "attachment_type_not_supported" }
        val ownerUserId = command.senderId.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("attachment_owner_missing")
        checkSession(ownerUserId)

        val existingTransfer = withContext(Dispatchers.IO) {
            app.database.attachmentTransferDao().get(command.messageId, ownerUserId)
        }
        if (existingTransfer != null) {
            return AttachmentSendWorkflowResult.Existing(
                command.existingMessage ?: messageStore.getMessageById(command.messageId)
                ?: throw IllegalStateException("attachment_existing_message_missing")
            )
        }

        val describedMetadata = withContext(Dispatchers.IO) {
            MediaCache.describeFile(appContext, command.sourceUri)
        }
        val initialMetadata = command.metadataOverride?.let { override ->
            override.copy(
                sizeBytes = describedMetadata.sizeBytes.takeIf { it > 0L } ?: override.sizeBytes,
            )
        } ?: describedMetadata
        checkSession(ownerUserId)
        if (initialMetadata.sizeBytes > MediaCache.MAX_ATTACHMENT_PLAIN_BYTES) {
            throw AttachmentTooLargeException()
        }

        val initialMeta = MessageMeta(
            forwardedFrom = command.forwardedFrom,
            voiceDurationMs = command.voiceDurationMs,
            fileName = initialMetadata.fileName,
            fileMimeType = initialMetadata.mimeType,
            fileSizeBytes = initialMetadata.sizeBytes.takeIf { it > 0 },
            viewOnce = command.viewOnce &&
                com.maodouchat.util.ViewOncePolicy.supports(command.type),
            spoilerMedia = command.spoilerMedia && !command.viewOnce &&
                command.type in setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF),
        )
        val optimistic = command.existingMessage?.copy(
            chatId = command.chatId,
            content = JsonFormat.composeContentWithMeta(command.sourceUri.toString(), initialMeta),
            status = MessageStatus.SENDING,
            meta = initialMeta,
        ) ?: Message(
            id = command.messageId,
            chatId = command.chatId,
            senderId = ownerUserId,
            content = JsonFormat.composeContentWithMeta(command.sourceUri.toString(), initialMeta),
            type = command.type,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            meta = initialMeta,
        )
        // This is deliberately UI-only until AttachmentTransferCoordinator commits the transfer
        // and final message in one transaction. Persisting it here would leave a permanent
        // SENDING row if the process died while preparing the transfer.
        onOptimisticMessage(optimistic)

        val coordinator = AttachmentSendCoordinator(
            context = appContext,
            messageStore = messageStore,
            tokenManager = tokenManager,
            resolveChatId = resolveChatId,
            onEncryptionProgress = onEncryptionProgress,
        )
        return try {
            val result = coordinator.prepareAndEnqueue(
                request = AttachmentSendRequest(
                    messageId = command.messageId,
                    sourceUri = command.sourceUri,
                    type = command.type,
                    initialMetadata = initialMetadata,
                    voiceDurationMs = command.voiceDurationMs,
                    optimisticMessage = optimistic,
                ),
                lease = lease,
            )
            when (result) {
                AttachmentSendResult.ExistingTransfer ->
                    AttachmentSendWorkflowResult.Existing(optimistic)
                is AttachmentSendResult.Queued ->
                    AttachmentSendWorkflowResult.Queued(result.message)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The caller decides how to paint the failure. The workflow only guarantees
            // that no stale account can continue writing after a session switch.
            checkSession(ownerUserId)
            throw error
        }
    }

    private fun checkSession(ownerUserId: String) {
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw CancellationException("attachment_send_session_changed")
        }
    }
}

internal class AttachmentTooLargeException : IllegalStateException("attachment_too_large")

private val RELIABLE_ATTACHMENT_TYPES = setOf(
    MessageType.IMAGE,
    MessageType.GIF,
    MessageType.VIDEO,
    MessageType.VOICE,
    MessageType.FILE,
)
