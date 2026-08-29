package com.maodouchat.attachment

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.TokenManager
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CancellationException

sealed interface AttachmentFinalizeOutcome {
    data class Sent(val message: Message) : AttachmentFinalizeOutcome
    data object AlreadyClaimed : AttachmentFinalizeOutcome
    /** claim 被 SK 轮换/清 wire 打断，状态已回 READY，worker 应 retry */
    data object ClaimInvalidated : AttachmentFinalizeOutcome
    data object ReuploadRequired : AttachmentFinalizeOutcome
    data object DiscardedTerminal : AttachmentFinalizeOutcome
    /**
     * Transient network/5xx: claim released to READY, message stays SENDING.
     * Worker should Result.retry() without showing FAILED mid-flight.
     */
    data class Transient(val error: Throwable) : AttachmentFinalizeOutcome
    data class Failed(val error: Throwable) : AttachmentFinalizeOutcome
}

/** Completes the encrypted-reference send independently from any visible chat screen. */
object AttachmentTransferFinalizer {
    suspend fun finalize(context: Context, messageId: String, expectedOwnerUserId: String): AttachmentFinalizeOutcome {
        val app = context.applicationContext as MaodouchatApp
        val dao = app.database.attachmentTransferDao()
        val tokenManager = TokenManager.getInstance(app)
        if (app.database.messagingV2Dao().isMessageTerminal(expectedOwnerUserId, messageId)) {
            AttachmentTransferCoordinator.discardTerminal(app, messageId, expectedOwnerUserId)
            return AttachmentFinalizeOutcome.DiscardedTerminal
        }
        if (tokenManager.getUserId().orEmpty() != expectedOwnerUserId) {
            return AttachmentFinalizeOutcome.AlreadyClaimed
        }
        val sessionToken = tokenManager.getToken().orEmpty()
        if (sessionToken.isBlank() || tokenManager.getUserId().orEmpty() != expectedOwnerUserId) {
            return AttachmentFinalizeOutcome.AlreadyClaimed
        }
        if (dao.claimForSending(messageId, ownerUserId = expectedOwnerUserId) != 1) {
            return AttachmentFinalizeOutcome.AlreadyClaimed
        }
        if (app.database.messagingV2Dao().isMessageTerminal(expectedOwnerUserId, messageId)) {
            AttachmentTransferCoordinator.discardTerminal(app, messageId, expectedOwnerUserId)
            return AttachmentFinalizeOutcome.DiscardedTerminal
        }
        ensureSessionActive(tokenManager, expectedOwnerUserId)
        val transfer = dao.get(messageId, ownerUserId = expectedOwnerUserId)
            ?: return AttachmentFinalizeOutcome.AlreadyClaimed
        ensureSessionActive(tokenManager, expectedOwnerUserId)
        val messageType = MessageType.fromWire(transfer.messageType)
        val messageRepo = LocalMessageStore(app.database.messageDao(), app.database)
        val original = messageRepo.getMessageById(messageId)
            ?: return fail(
                dao,
                transfer.messageId,
                expectedOwnerUserId,
                IllegalStateException("attachment_message_missing"),
                tokenManager,
                messageRepo
            )
        ensureSessionActive(tokenManager, expectedOwnerUserId)
        if (messageType !in RELIABLE_TYPES ||
            !transfer.hasCompletedUpload() ||
            original.senderId != expectedOwnerUserId ||
            original.chatId != transfer.chatId
        ) {
            return fail(
                dao,
                transfer.messageId,
                expectedOwnerUserId,
                IllegalStateException("attachment_transfer_invalid"),
                tokenManager,
                messageRepo
            )
        }
        val attachmentId = transfer.attachmentId
            ?: return fail(
                dao,
                transfer.messageId,
                expectedOwnerUserId,
                IllegalStateException("attachment_id_missing"),
                tokenManager,
                messageRepo
            )

        var attachmentReadyVerified = false
        return try {
            // Prefer local chat (offline + cheap peer resolve); network list only if cache miss.
            val chat = resolveChatForFinalize(app, sessionToken, transfer.chatId)
                ?: throw IllegalStateException("attachment_chat_missing")
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            check(chat.participants.any { it.id == expectedOwnerUserId }) { "attachment_chat_owner_mismatch" }
            ApiService.verifyEncryptedAttachmentReady(
                token = sessionToken,
                chatId = transfer.chatId,
                messageId = messageId,
                attachmentId = attachmentId,
                expectedSha256 = transfer.cipherSha256,
                expectedSize = transfer.cipherSize
            ).getOrThrow()
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            attachmentReadyVerified = true

            val reference = MediaCache.EncryptedAttachmentReference(
                attachmentId = attachmentId,
                keyBase64 = transfer.keyBase64,
                ivBase64 = transfer.ivBase64,
                cipherSha256 = transfer.cipherSha256,
                plainSha256 = transfer.plainSha256,
                cipherSize = transfer.cipherSize,
                fileName = transfer.fileName,
                mimeType = transfer.mimeType,
                plainSize = transfer.plainSize,
                durationMs = transfer.durationMs
            )
            val referencePayload = MediaCache.encodeEncryptedAttachmentReference(reference)
            val finalMeta = original.parsedMeta().copy(
                fileName = transfer.fileName,
                fileMimeType = transfer.mimeType,
                fileSizeBytes = transfer.plainSize,
                voiceDurationMs = transfer.durationMs,
                attachmentId = attachmentId,
                attachmentKeyBase64 = transfer.keyBase64,
                attachmentIvBase64 = transfer.ivBase64,
                attachmentCipherSha256 = transfer.cipherSha256,
                attachmentPlainSha256 = transfer.plainSha256,
                attachmentCipherSize = transfer.cipherSize
            )
            val finalMessage = original.copy(
                chatId = transfer.chatId,
                content = composeContent(MediaCache.attachmentUri(attachmentId), finalMeta),
                type = messageType,
                status = MessageStatus.SENDING,
                meta = finalMeta,
            )
            val staged = MessagingV2MessageGateway(
                database = app.database,
                messageStore = messageRepo,
                outbox = app.messagingV2Outbox,
            ).stageAndEnqueue(
                message = finalMessage,
                body = referencePayload,
                type = messageType,
                groupRevision = chat.memberRevision.takeIf { chat.isGroup && it > 0L },
            )
            if (!staged) {
                AttachmentTransferCoordinator.discardTerminal(app, messageId, expectedOwnerUserId)
                return AttachmentFinalizeOutcome.DiscardedTerminal
            }
            AttachmentTransferCoordinator.complete(app, messageId, expectedOwnerUserId)
            AttachmentFinalizeOutcome.Sent(finalMessage)
        } catch (cancelled: CancellationException) {
            // Worker stop / coroutine cancel must free the claim; otherwise next finalize sees
            // AlreadyClaimed until STALE_SENDING_MS and attachments appear stuck mid-send.
            // Safe: client message id is idempotent; 409 is accepted above if send already landed.
            if (sessionActive(tokenManager, expectedOwnerUserId)) {
                releaseSendingBestEffort(dao, messageId, expectedOwnerUserId)
            }
            throw cancelled
        } catch (error: Throwable) {
            if (!sessionActive(tokenManager, expectedOwnerUserId)) {
                throw CancellationException("attachment_session_changed").apply { initCause(error) }
            }
            val staleUpload = !attachmentReadyVerified && error is ApiException && error.statusCode in setOf(400, 404, 410)
            when {
                staleUpload -> {
                    dao.resetForReupload(messageId, "ATTACHMENT_${error.statusCode}", ownerUserId = expectedOwnerUserId)
                    AttachmentFinalizeOutcome.ReuploadRequired
                }
                isRetryable(error) -> {
                    // Keep READY + message SENDING so weak-net does not flash FAILED mid-retry.
                    releaseSendingBestEffort(dao, messageId, expectedOwnerUserId)
                    AttachmentFinalizeOutcome.Transient(error)
                }
                else -> {
                    ensureSessionActive(tokenManager, expectedOwnerUserId)
                    dao.markFailed(messageId, "SEND_${error.javaClass.simpleName.take(60)}", ownerUserId = expectedOwnerUserId)
                    ensureSessionActive(tokenManager, expectedOwnerUserId)
                    messageRepo.insertMessage(original.copy(status = MessageStatus.FAILED))
                    AttachmentFinalizeOutcome.Failed(error)
                }
            }
        }
    }

    private suspend fun resolveChatForFinalize(app: MaodouchatApp, token: String, chatId: String): Chat? {
        val chatRepo = ChatRepository(app.database.chatDao(), app.database.userDao())
        chatRepo.getChatById(chatId)?.let { return it }
        if (token.isBlank()) return null
        val dto = ApiService.getChats(token).getOrThrow().firstOrNull { it.id == chatId } ?: return null
        return dto.toDomainChat()
    }

    private fun ChatDto.toDomainChat(): Chat = Chat(
        id = id,
        participants = participants.map {
            com.maodouchat.data.model.User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status)
        },
        lastMessage = lastMessage,
        lastMessageType = MessageType.fromWire(lastMessageType),
        lastMessageTime = lastMessageTime,
        unreadCount = unreadCount,
        isGroup = isGroup,
        chatType = chatType,
        groupName = groupName,
        groupAnnouncement = groupAnnouncement,
        groupAvatar = groupAvatar,
        memberRevision = memberRevision,
        pinnedAt = pinnedAt,
        notificationsMuted = notificationsMuted,
        archived = archived,
        markedUnread = markedUnread,
        settingsUpdatedAt = settingsUpdatedAt,
        disappearingMessageSeconds = disappearingMessageSeconds
    )

    fun isRetryable(error: Throwable): Boolean = AttachmentSendAfterUploadPolicy.isRetryable(error)

    private suspend fun fail(
        dao: com.maodouchat.data.local.dao.AttachmentTransferDao,
        messageId: String,
        ownerUserId: String,
        error: Throwable,
        tokenManager: TokenManager,
        messageRepo: LocalMessageStore? = null
    ): AttachmentFinalizeOutcome.Failed {
        ensureSessionActive(tokenManager, ownerUserId)
        dao.markFailed(messageId, "SEND_${error.javaClass.simpleName.take(60)}", ownerUserId = ownerUserId)
        // 与 catch 路径一致：把本地消息标为 FAILED，避免进程重启后永远停在 SENDING
        if (messageRepo != null) {
            ensureSessionActive(tokenManager, ownerUserId)
            messageRepo.getMessageById(messageId)?.let { original ->
                ensureSessionActive(tokenManager, ownerUserId)
                messageRepo.insertMessage(original.copy(status = MessageStatus.FAILED))
            }
        }
        return AttachmentFinalizeOutcome.Failed(error)
    }

    private suspend fun releaseSendingBestEffort(
        dao: com.maodouchat.data.local.dao.AttachmentTransferDao,
        messageId: String,
        ownerUserId: String
    ) {
        try {
            dao.releaseSending(messageId, ownerUserId = ownerUserId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A stale SENDING claim is recovered by reconcile after process/work interruption.
        }
    }

    private fun sessionActive(tokenManager: TokenManager, expectedOwnerUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedOwnerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    private fun ensureSessionActive(tokenManager: TokenManager, expectedOwnerUserId: String) {
        if (!sessionActive(tokenManager, expectedOwnerUserId)) {
            throw CancellationException("attachment_session_changed")
        }
    }

    private fun composeContent(text: String, meta: MessageMeta): String {
        // Must encode MessageMeta as a JSON object; JsonFormat.encode(data class) used to
        // stringify toString() and permanently lose attachment keys after process death.
        val encoded = JsonFormat.encodeMessageMeta(meta)
        return "$text${Message.META_TAG_PREFIX}$encoded</meta>"
    }

    private val RELIABLE_TYPES = setOf(
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE,
        MessageType.FILE
    )
}
