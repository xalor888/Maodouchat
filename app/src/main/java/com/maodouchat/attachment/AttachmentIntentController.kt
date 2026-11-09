package com.maodouchat.attachment

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.AttachmentPreparationLease
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.AttachmentIntentPolicy
import com.maodouchat.domain.messaging.AttachmentKind
import com.maodouchat.domain.messaging.AttachmentPreparationService
import com.maodouchat.domain.messaging.AttachmentTransfer
import com.maodouchat.domain.messaging.ContentPayload
import com.maodouchat.domain.messaging.SendMessageCommand
import com.maodouchat.domain.messaging.SendMessageResult
import com.maodouchat.domain.messaging.SpecialAttachmentMessagePolicy
import com.maodouchat.domain.messaging.TransferRepository
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.core.model.JsonFormat
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.core.model.Flag
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.util.StickerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * M07: 默认附件意图控制器实现。
 * 作为 UI 发送附件的唯一入口，统一负责媒体准备、Lease 管理、权限/运行标志校验、
 * 乐观消息持久化、出站调度与特殊附件（贴纸、位置、名片）门面分发。
 */
class DefaultAttachmentIntentController(
    private val context: Context,
    private val transferRepository: TransferRepository = RoomTransferRepository(context.applicationContext as MaodouchatApp),
    private val preparationService: AttachmentPreparationService = DefaultAttachmentPreparationService(context),
    private val messageStore: LocalMessageStore? = null,
    private val tokenManager: TokenManager = TokenManager.getInstance(context),
    private val commandFacade: com.maodouchat.domain.messaging.ConversationCommandFacade? = null,
    private val ownerUserId: () -> String = { tokenManager.getUserId().orEmpty() },
    private val onProgress: (transferId: String, completed: Long, total: Long) -> Unit = { _, _, _ -> },
) : AttachmentIntentController {

    private val appContext = context.applicationContext ?: context

    override suspend fun submit(intent: AttachmentIntent): Result<String> = runCatching {
        val owner = ownerUserId().takeIf(String::isNotBlank)
            ?: throw IllegalStateException("attachment_owner_missing")
        ensureSessionActive(owner)

        when (intent.kind) {
            AttachmentKind.STICKER -> submitSticker(intent, owner)
            AttachmentKind.LOCATION -> submitLocation(intent, owner)
            AttachmentKind.CONTACT -> submitContact(intent, owner)
            AttachmentKind.IMAGE,
            AttachmentKind.VIDEO,
            AttachmentKind.VOICE,
            AttachmentKind.FILE,
            AttachmentKind.GIF -> submitMedia(intent, owner)
        }
    }

    private suspend fun submitSticker(intent: AttachmentIntent, owner: String): String {
        requireFlag(RuntimeFlags.STICKERS, "stickers_disabled")
        val stickerCode = AttachmentIntentPolicy.parseStickerCode(intent.uri)
        require(!stickerCode.isNullOrBlank()) { "sticker_empty" }
        runCatching { StickerPreferences.recordRecent(appContext, stickerCode) }

        if (commandFacade != null) {
            val res = commandFacade.send(
                SendMessageCommand(
                    conversationId = intent.conversationId,
                    content = ContentPayload.Text(stickerCode),
                    idempotencyKey = intent.idempotencyKey
                )
            )
            return (res as? SendMessageResult.Success)?.localMessageId
                ?: intent.idempotencyKey.ifBlank { "m_${UUID.randomUUID()}" }
        }
        val messageId = SpecialAttachmentMessagePolicy.resolveMessageId(intent.idempotencyKey)
        val msg = SpecialAttachmentMessagePolicy.stickerMessage(
            messageId, intent.conversationId, owner, stickerCode, System.currentTimeMillis(),
        )
        storeMessage(msg)
        return messageId
    }

    private suspend fun submitLocation(intent: AttachmentIntent, owner: String): String {
        requireFlag(RuntimeFlags.STATIC_LOCATION, "static_location_disabled")
        val location = AttachmentIntentPolicy.parseGeoLocation(intent.uri)

        if (commandFacade != null) {
            val res = commandFacade.send(
                SendMessageCommand(
                    conversationId = intent.conversationId,
                    content = ContentPayload.Location(latitude = location.latitude, longitude = location.longitude),
                    idempotencyKey = intent.idempotencyKey
                )
            )
            return (res as? SendMessageResult.Success)?.localMessageId
                ?: intent.idempotencyKey.ifBlank { "m_${UUID.randomUUID()}" }
        }
        val messageId = SpecialAttachmentMessagePolicy.resolveMessageId(intent.idempotencyKey)
        val msg = SpecialAttachmentMessagePolicy.locationMessage(
            messageId, intent.conversationId, owner, location, System.currentTimeMillis(),
        )
        storeMessage(msg)
        return messageId
    }

    private suspend fun submitContact(intent: AttachmentIntent, owner: String): String {
        requireFlag(RuntimeFlags.CONTACT_CARD, "contact_card_disabled")
        val card = AttachmentIntentPolicy.parseContactCard(intent.uri, intent.caption)

        if (commandFacade != null) {
            val res = commandFacade.send(
                SendMessageCommand(
                    conversationId = intent.conversationId,
                    content = ContentPayload.Contact(userId = card.userId, displayName = card.displayName),
                    idempotencyKey = intent.idempotencyKey
                )
            )
            return (res as? SendMessageResult.Success)?.localMessageId
                ?: intent.idempotencyKey.ifBlank { "m_${UUID.randomUUID()}" }
        }
        val messageId = SpecialAttachmentMessagePolicy.resolveMessageId(intent.idempotencyKey)
        val msg = SpecialAttachmentMessagePolicy.contactMessage(
            messageId, intent.conversationId, owner, card, System.currentTimeMillis(),
        )
        storeMessage(msg)
        return messageId
    }

    private suspend fun submitMedia(intent: AttachmentIntent, owner: String): String {
        validateMediaFlags(intent.kind, intent.viewOnce, intent.spoilerMedia)
        ensureSessionActive(owner)

        val messageId = intent.idempotencyKey.ifBlank { "m_${UUID.randomUUID()}" }
        val existing = transferRepository.get(messageId, owner)
        if (existing != null) {
            return messageId
        }

        val lease = AttachmentPreparationLease(
            originalSourceUri = intent.uri,
            deleteEncryptedFile = { path -> runCatching { File(path).delete() } },
            deletePreparedSource = { source -> MediaCache.deletePreparedAttachmentSource(appContext, source) },
            releasePersistablePermission = { source -> MediaCache.releasePersistableReadPermission(appContext, source) }
        )

        try {
            val prepared = preparationService.prepare(
                intent = intent.copy(idempotencyKey = messageId),
                ownerUserId = owner,
                onProgress = { completed, total ->
                    onProgress(messageId, completed, total)
                }
            ).getOrThrow()

            lease.recordEncryptedPath(prepared.encryptedPath)
            if (prepared.sourceUri != intent.uri) {
                lease.recordPreparedSource(prepared.sourceUri)
            }
            ensureSessionActive(owner)

            val msgType = AttachmentIntentPolicy.messageTypeForKind(intent.kind)

            val visibility = AttachmentIntentPolicy.resolveAttachmentVisibility(
                msgType, intent.viewOnce, intent.spoilerMedia,
            )
            val meta = MessageMeta(
                fileName = prepared.fileName,
                fileMimeType = prepared.mimeType,
                fileSizeBytes = prepared.plainSize.takeIf { it > 0L },
                voiceDurationMs = prepared.durationMs,
                viewOnce = visibility.viewOnce,
                spoilerMedia = visibility.spoilerMedia,
            )

            val optimisticMessage = Message(
                id = messageId,
                chatId = intent.conversationId,
                senderId = owner,
                content = JsonFormat.composeContentWithMeta(prepared.sourceUri, meta),
                type = msgType,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                meta = meta,
            )

            val transferEntity = AttachmentTransferEntity(
                messageId = messageId,
                ownerUserId = owner,
                chatId = intent.conversationId,
                messageType = msgType.name,
                sourceUri = prepared.sourceUri,
                encryptedPath = prepared.encryptedPath,
                fileName = prepared.fileName,
                mimeType = prepared.mimeType,
                plainSize = prepared.plainSize,
                durationMs = prepared.durationMs,
                keyBase64 = prepared.keyBase64,
                ivBase64 = prepared.ivBase64,
                cipherSha256 = prepared.cipherSha256,
                plainSha256 = prepared.plainSha256,
                cipherSize = prepared.cipherSize,
                state = com.maodouchat.data.local.entity.AttachmentTransferState.QUEUED
            )

            AttachmentTransferCoordinator.enqueue(appContext, transferEntity) {
                storeMessage(optimisticMessage)
            }
            lease.handOff()
            return messageId
        } catch (c: CancellationException) {
            lease.cleanupIfOwned()
            throw c
        } catch (e: Throwable) {
            val persisted = transferRepository.get(messageId, owner) != null
            if (persisted) lease.handOff() else lease.cleanupIfOwned()
            throw e
        }
    }

    override fun observe(transferId: String): Flow<AttachmentTransfer> {
        val owner = ownerUserId()
        return transferRepository.observe(transferId, owner).filterNotNull()
    }

    override suspend fun pause(transferId: String): Boolean {
        val owner = ownerUserId()
        return transferRepository.pause(transferId, owner)
    }

    override suspend fun resume(transferId: String): Boolean {
        val owner = ownerUserId()
        return transferRepository.resume(transferId, owner)
    }

    override suspend fun cancel(transferId: String): Boolean {
        val owner = ownerUserId()
        return transferRepository.cancel(transferId, owner)
    }

    private fun validateMediaFlags(kind: AttachmentKind, viewOnce: Boolean, spoilerMedia: Boolean) {
        requireFlag(RuntimeFlags.MEDIA_UPLOAD, "media_upload_disabled")
        when (kind) {
            AttachmentKind.IMAGE -> requireFlag(RuntimeFlags.IMAGE_SEND, "image_send_disabled")
            AttachmentKind.VIDEO -> requireFlag(RuntimeFlags.VIDEO_SEND, "video_send_disabled")
            AttachmentKind.GIF -> requireFlag(RuntimeFlags.GIF_SEND, "gif_send_disabled")
            AttachmentKind.FILE -> requireFlag(RuntimeFlags.FILE_SHARE, "file_share_disabled")
            AttachmentKind.VOICE -> {}
            else -> {}
        }
        if (viewOnce) requireFlag(RuntimeFlags.VIEW_ONCE, "view_once_disabled")
        if (spoilerMedia) requireFlag(RuntimeFlags.SPOILER_MEDIA, "spoiler_media_disabled")
    }

    private fun requireFlag(flag: Flag, message: String) {
        if (!RuntimeFlags.isEnabled(appContext, flag)) {
            throw IllegalStateException(message)
        }
    }

    private fun ensureSessionActive(expectedOwnerUserId: String) {
        if (!BackgroundSessionGate.mayContinue(
            expectedUserId = expectedOwnerUserId,
        )
        ) {
            throw CancellationException("attachment_session_changed")
        }
    }

    private suspend fun storeMessage(message: Message) {
        val store = messageStore ?: (appContext as? MaodouchatApp)?.let {
            LocalMessageStore(it.database.messageDao(), it.database)
        }
        if (store != null) {
            withContext(Dispatchers.IO) { store.insertMessage(message) }
        }
    }
}
