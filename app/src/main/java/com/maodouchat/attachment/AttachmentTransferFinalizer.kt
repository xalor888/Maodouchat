package com.maodouchat.attachment

import android.content.Context
import android.net.Uri
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.TokenManager
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface AttachmentFinalizeOutcome {
    data class Sent(val message: Message) : AttachmentFinalizeOutcome
    data object AlreadyClaimed : AttachmentFinalizeOutcome
    /** claim 被 SK 轮换/清 wire 打断，状态已回 READY，worker 应 retry */
    data object ClaimInvalidated : AttachmentFinalizeOutcome
    data object ReuploadRequired : AttachmentFinalizeOutcome
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
        ensureSessionActive(tokenManager, expectedOwnerUserId)
        val transfer = dao.get(messageId, ownerUserId = expectedOwnerUserId)
            ?: return AttachmentFinalizeOutcome.AlreadyClaimed
        ensureSessionActive(tokenManager, expectedOwnerUserId)
        val messageType = MessageType.fromWire(transfer.messageType)
        val messageRepo = MessageRepository(app.database.messageDao(), app.database)
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
        var sendAttempted = false  // BUG 1.2: 跟踪 sendMessage 是否已成功
        var acceptedMessage: Message? = null
        return try {
            if (!app.signalProtocol.isInitializedFor(expectedOwnerUserId)) {
                check(app.signalProtocol.initialize(sessionToken, expectedOwnerUserId)) { "signal_initialization_failed" }
            }
            ensureSessionActive(tokenManager, expectedOwnerUserId)
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
            var groupEpochUsed: Long? = null
            val liveEpoch = chat.memberRevision.takeIf { it > 0L }
            // 复用 wire 仅当群 epoch 仍匹配且本地仍持有该 epoch 的 distribution
            val reusableWire = transfer.wireContent?.takeIf { wire ->
                if (!chat.isGroup) return@takeIf true
                val wireEpoch = app.signalProtocol.parseSenderKeyEnvelopeEpoch(wire)
                wireEpoch != null &&
                    liveEpoch != null &&
                    wireEpoch == liveEpoch &&
                    app.signalProtocol.hasGroupDistributionId(chat.id, wireEpoch)
            }
            if (transfer.wireContent != null && reusableWire == null) {
                dao.clearWireContent(messageId, ownerUserId = expectedOwnerUserId)
            }
            // Logout/account switch after claim: abort before encrypt under a dead session.
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            val wireContent = reusableWire ?: if (chat.isGroup) {
                val epochExpected = liveEpoch ?: throw IllegalStateException("group_epoch_unknown")
                // 后台 finalize：ensureCoverageNow 校验/补齐覆盖并以返回 epoch 加密，避免快照过期
                val epoch = app.senderKeyRetryManager.ensureCoverageNow(chat.id, epochExpected).getOrThrow()
                ensureSessionActive(tokenManager, expectedOwnerUserId)
                groupEpochUsed = epoch
                app.signalProtocol.encryptGroupContentEnvelope(chat.id, referencePayload, messageType.name, epoch).getOrThrow()
            } else {
                val recipientId = chat.participants.firstOrNull { it.id != expectedOwnerUserId }?.id
                    ?: throw IllegalStateException("attachment_recipient_missing")
                app.signalProtocol.encryptSyncedContentEnvelope(
                    sessionToken,
                    recipientId,
                    referencePayload,
                    messageType.name,
                ).getOrThrow()
            }.also { generated ->
                ensureSessionActive(tokenManager, expectedOwnerUserId)
                // SK rotate / clearWire 可能在 encrypt 期间把 SENDING→READY；不得 markFailed
                if (dao.storeWireContent(messageId, generated, ownerUserId = expectedOwnerUserId) != 1) {
                    ensureSessionActive(tokenManager, expectedOwnerUserId)
                    val row = dao.get(messageId, ownerUserId = expectedOwnerUserId)
                    return if (row != null &&
                        row.state == AttachmentTransferState.READY &&
                        row.hasCompletedUpload()
                    ) {
                        AttachmentFinalizeOutcome.ClaimInvalidated
                    } else if (row == null) {
                        fail(
                            dao,
                            messageId,
                            expectedOwnerUserId,
                            IllegalStateException("attachment_transfer_missing"),
                            tokenManager,
                            messageRepo
                        )
                    } else {
                        AttachmentFinalizeOutcome.AlreadyClaimed
                    }
                }
            }
            if (chat.isGroup) {
                groupEpochUsed = groupEpochUsed
                    ?: app.signalProtocol.parseSenderKeyEnvelopeEpoch(wireContent)
            }

            // 发送前再读一次：invalidate 可能已清 wire / 释放 SENDING
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            val liveTransfer = dao.get(messageId, ownerUserId = expectedOwnerUserId)
                ?: return fail(
                    dao,
                    messageId,
                    expectedOwnerUserId,
                    IllegalStateException("attachment_transfer_missing"),
                    tokenManager,
                    messageRepo
                )
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            if (liveTransfer.state != AttachmentTransferState.SENDING) {
                // clearWireContentForChat 会把 SENDING→READY；worker 需 retry 重加密
                return if (liveTransfer.state == AttachmentTransferState.READY && liveTransfer.hasCompletedUpload()) {
                    AttachmentFinalizeOutcome.ClaimInvalidated
                } else {
                    AttachmentFinalizeOutcome.AlreadyClaimed
                }
            }
            val sendWire = when {
                liveTransfer.wireContent == wireContent -> wireContent
                liveTransfer.wireContent != null -> {
                    // 其它路径写了新 wire（罕见）；以 DB 为准并校验 epoch
                    val dbWire = liveTransfer.wireContent
                    if (chat.isGroup) {
                        val dbEpoch = app.signalProtocol.parseSenderKeyEnvelopeEpoch(dbWire)
                        if (dbEpoch == null || liveEpoch == null || dbEpoch != liveEpoch ||
                            !app.signalProtocol.hasGroupDistributionId(chat.id, dbEpoch)
                        ) {
                            dao.releaseSending(messageId, ownerUserId = expectedOwnerUserId)
                            return AttachmentFinalizeOutcome.ClaimInvalidated
                        }
                        groupEpochUsed = dbEpoch
                    }
                    dbWire
                }
                else -> {
                    // wire 被清：释放 claim，worker retry 按新 epoch 重加密
                    dao.releaseSending(messageId, ownerUserId = expectedOwnerUserId)
                    return AttachmentFinalizeOutcome.ClaimInvalidated
                }
            }

            ensureSessionActive(tokenManager, expectedOwnerUserId)
            val sendResult = ApiService.sendMessage(sessionToken, transfer.chatId, sendWire, messageType.name, messageId)
            if (sendResult.isFailure) {
                val err = sendResult.exceptionOrNull()
                // 409 / 消息 ID 已存在：与文本 outbox 一致，按已投递收敛，避免重加密后永久 FAILED。
                val alreadyAccepted = err is ApiException &&
                    err.kind == ApiFailureKind.HTTP &&
                    (err.statusCode == 409 || err.serverMessage?.contains("消息 ID") == true)
                if (!alreadyAccepted) throw err ?: IllegalStateException("attachment_send_failed")
            }
            sendAttempted = true // BUG 1.2: sendMessage 已成功
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            val metadata = MediaCache.LocalFileMetadata(transfer.fileName, transfer.mimeType, transfer.plainSize)
            val localUri = MediaCache.copyFileToCache(app, Uri.parse(transfer.sourceUri), messageId, metadata)
                ?: MediaCache.attachmentUri(attachmentId)
            ensureSessionActive(tokenManager, expectedOwnerUserId)
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
                content = composeContent(localUri, finalMeta),
                type = messageType,
                status = MessageStatus.SENT,
                meta = finalMeta
            )
            acceptedMessage = finalMessage
            // 8.60：finalize 与 cancelForChat/deleteAll 竞态——sendMessage 已成功后本地 transfer 行
            // 若已被删除（用户删除会话/登出清理），不得再插本地 SENT 消息（服务端已投递，
            // 重同步会拉回），也跳过 commit/complete，避免幽灵消息复活与孤儿对象
            val liveRowAfterSend = dao.get(messageId, ownerUserId = expectedOwnerUserId)
            if (liveRowAfterSend == null || liveRowAfterSend.state != AttachmentTransferState.SENDING) {
                return AttachmentFinalizeOutcome.ClaimInvalidated
            }
            // Persist the complete reference before the compatibility commit call. sendMessage already
            // committed the attachment atomically on current servers, so a later HTTP failure must not
            // discard the only local copy of the attachment key and hashes.
            messageRepo.insertMessage(finalMessage)
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            // sendMessage commits atomically on the server; this idempotent call also supports
            // older servers and recovery after a process death between send and local cleanup.
            ApiService.commitEncryptedAttachment(sessionToken, attachmentId, messageId).getOrThrow()
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            if (chat.isGroup) {
                // 只 mark 实际发送 envelope 的 epoch，禁止用 live revision 误标
                val epoch = groupEpochUsed
                    ?: throw IllegalStateException("group_epoch_unknown_after_send")
                app.signalProtocol.markGroupSenderKeyMessageSent(chat.id, epoch, messageId)
            }

            AttachmentTransferCoordinator.complete(app, messageId, expectedOwnerUserId)
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            emitSentEvents(app, finalMessage)
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
                    // sendMessage 已原子提交附件；兼容性 commit 失败不能回退为 FAILED，
                    // 也不能删除包含附件密钥/哈希的本地最终消息。
                    if (sendAttempted) {
                        val sent = acceptedMessage ?: run {
                            ensureSessionActive(tokenManager, expectedOwnerUserId)
                            releaseSendingBestEffort(dao, messageId, expectedOwnerUserId)
                            return AttachmentFinalizeOutcome.Transient(error)
                        }
                        try {
                            messageRepo.insertMessage(sent)
                            AttachmentTransferCoordinator.complete(app, messageId, expectedOwnerUserId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (recoveryError: Throwable) {
                            if (!sessionActive(tokenManager, expectedOwnerUserId)) {
                                throw CancellationException("attachment_session_changed").apply { initCause(recoveryError) }
                            }
                            releaseSendingBestEffort(dao, messageId, expectedOwnerUserId)
                            return AttachmentFinalizeOutcome.Transient(recoveryError)
                        }
                        ensureSessionActive(tokenManager, expectedOwnerUserId)
                        emitSentEvents(app, sent)
                        AttachmentFinalizeOutcome.Sent(sent)
                    } else {
                        ensureSessionActive(tokenManager, expectedOwnerUserId)
                        dao.markFailed(messageId, "SEND_${error.javaClass.simpleName.take(60)}", ownerUserId = expectedOwnerUserId)
                        ensureSessionActive(tokenManager, expectedOwnerUserId)
                        val failed = original.copy(status = MessageStatus.FAILED)
                        messageRepo.insertMessage(failed)
                        ensureSessionActive(tokenManager, expectedOwnerUserId)
                        AttachmentFinalizeOutcome.Failed(error)
                    }
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

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is ApiException -> error.kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT) || (error.statusCode ?: 0) >= 500
        is IOException -> true
        else -> false
    }

    private suspend fun fail(
        dao: com.maodouchat.data.local.dao.AttachmentTransferDao,
        messageId: String,
        ownerUserId: String,
        error: Throwable,
        tokenManager: TokenManager,
        messageRepo: MessageRepository? = null
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

    private fun emitSentEvents(app: MaodouchatApp, message: Message) {
        MaodouchatApp.emitAttachmentFinalized(message)
        val preview = when (message.type) {
            MessageType.IMAGE -> app.getString(R.string.message_preview_image)
            MessageType.GIF -> app.getString(R.string.message_preview_gif)
            MessageType.VIDEO -> app.getString(R.string.message_preview_video)
            MessageType.VOICE -> app.getString(R.string.message_preview_voice)
            else -> app.getString(R.string.message_preview_file)
        }
        MaodouchatApp.emitMessageSent(message.chatId, preview, message.type.name)
    }

    private val RELIABLE_TYPES = setOf(
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE,
        MessageType.FILE
    )
}
