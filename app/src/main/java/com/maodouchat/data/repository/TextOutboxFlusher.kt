package com.maodouchat.data.repository

import android.util.Log
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
// 9.4xx：outbox 判定策略已迁至 data 层（本包内），消除 data→UI 循环依赖
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background-safe flush of local SENDING text/sticker/location rows.
 *
 * Survives leaving ChatDetail: ChatList can invoke this on reconnect so 1:1/group
 * outbox does not wait until the user re-opens each chat.
 */
object TextOutboxFlusher {
    private val mutex = Mutex()
    private val attachmentTypes = setOf(
        MessageType.FILE,
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE
    )
    // Only types with durable plaintext encrypt + REST idempotent client id.
    private val flusableTypes = setOf(
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.STICKER,
        MessageType.LOCATION
    )

    /**
     * @param activeChatId optional open chat — used only as peer-resolve preference
     * @param activeContactId optional open 1:1 contact id
     * @param onMessageUpdated invoked after a row converges to SENT/FAILED (UI merge)
     */
    suspend fun flush(
        app: MaodouchatApp,
        activeChatId: String = "",
        activeContactId: String? = null,
        onMessageUpdated: (suspend (Message) -> Unit)? = null
    ) = mutex.withLock {
        val tokenManager = TokenManager.getInstance(app)
        val token = tokenManager.getToken().orEmpty()
        val userId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || userId.isBlank()) return@withLock
        // The snapshot above can become stale while initialization uploads the key bundle.
        // Never touch the Signal store with a token/user that has already been replaced.
        if (!TextOutboxSessionPolicy.mayContinueFlush(
                userId,
                tokenManager.getToken().orEmpty(),
                tokenManager.getUserId().orEmpty()
            )
        ) {
            Log.i(TAG, "outbox flush aborted: session changed before crypto initialization")
            return@withLock
        }

        val messageRepo = MessageRepository(app.database.messageDao(), app.database)
        val chatRepo = ChatRepository(app.database.chatDao(), app.database.userDao())
        val pending = messageRepo.getSendingOutbox(userId)

        // Reconnect calls this even with an empty outbox. Use that network-ready signal to
        // republish a locally restored/generated key package that previously failed to upload.
        if (!app.signalProtocol.isInitializedFor(userId)) {
            app.signalProtocol.initialize(token, userId)
            if (!TextOutboxSessionPolicy.mayContinueFlush(
                    userId,
                    tokenManager.getToken().orEmpty(),
                    tokenManager.getUserId().orEmpty()
                )
            ) {
                Log.i(TAG, "outbox flush aborted: session changed during crypto initialization")
                return@withLock
            }
        }
        if (pending.isEmpty()) return@withLock

        pending.forEach { msg ->
            if (msg.type in attachmentTypes || msg.type !in flusableTypes) return@forEach
            // Logout/account switch mid-flush: stop before encrypt/REST under a dead session.
            val liveToken = tokenManager.getToken().orEmpty()
            val liveUserId = tokenManager.getUserId().orEmpty()
            if (!TextOutboxSessionPolicy.mayContinueFlush(userId, liveToken, liveUserId)) {
                Log.i(TAG, "outbox flush aborted: session changed during batch")
                return@withLock
            }
            try {
                val chatId = msg.chatId.ifBlank { activeChatId }
                if (chatId.isBlank()) throw IllegalStateException("outbox_chat_missing")
                val chatEntity = chatRepo.getChatById(chatId)
                val isGroup = chatEntity?.isGroup == true
                val groupEpoch = if (isGroup) {
                    if (!app.signalProtocol.isLocalCryptoReadyFor(userId)) {
                        throw com.maodouchat.crypto.LocalCryptoNotReadyException()
                    }
                    requireGroupEpoch(liveToken, chatRepo, chatId, chatEntity).also { epoch ->
                        app.senderKeyRetryManager.ensureCoverageNow(chatId, epoch).getOrThrow()
                    }
                } else null
                val wireContent = if (groupEpoch != null) {
                    when (msg.type) {
                        MessageType.TEXT, MessageType.MARKDOWN ->
                            app.signalProtocol.encryptGroupTextEnvelope(
                                chatId, msg.content, msg.type.name, groupEpoch
                            ).getOrThrow()
                        else ->
                            app.signalProtocol.encryptGroupContentEnvelope(
                                chatId, msg.content, msg.type.name, groupEpoch
                            ).getOrThrow()
                    }
                } else {
                    val peerId = resolveDirectOutboxPeerId(
                        chatId = chatId,
                        activeChatId = activeChatId,
                        activeContactId = activeContactId,
                        selfUserId = userId,
                        chatParticipants = chatEntity?.participants
                    ) ?: throw IllegalStateException("outbox_peer_unresolved")
                    if (com.maodouchat.bot.BotCommandPolicy.isBotUserId(peerId)) {
                        msg.content
                    } else {
                        if (!app.signalProtocol.isLocalCryptoReadyFor(userId)) {
                            throw com.maodouchat.crypto.LocalCryptoNotReadyException()
                        }
                        when (msg.type) {
                            MessageType.TEXT, MessageType.MARKDOWN ->
                                app.signalProtocol.encryptSyncedContentEnvelope(liveToken, peerId, msg.content, msg.type.name).getOrThrow()
                            else ->
                                app.signalProtocol.encryptSyncedContentEnvelope(
                                    liveToken, peerId, msg.content, msg.type.name
                                ).getOrThrow()
                        }
                    }
                }
                // Encrypt can take long enough for logout/switch; re-check before REST.
                val postEncryptToken = tokenManager.getToken().orEmpty()
                val postEncryptUserId = tokenManager.getUserId().orEmpty()
                if (!TextOutboxSessionPolicy.mayContinueFlush(userId, postEncryptToken, postEncryptUserId)) {
                    Log.i(TAG, "outbox flush aborted: session changed after encrypt for ${msg.id}")
                    return@withLock
                }
                val sendToken = postEncryptToken.ifBlank { liveToken }
                val sealedCertificate = if (msg.sealedSender) {
                    com.maodouchat.crypto.SealedSenderSupport
                        .fetchCertificate(sendToken, userId, app.signalProtocol.getDeviceId())
                        .getOrElse {
                            throw com.maodouchat.crypto.LocalCryptoNotReadyException(
                                "sealed_sender_certificate_unavailable"
                            )
                        }
                        .certificate
                        .takeIf(String::isNotBlank)
                        ?: throw com.maodouchat.crypto.LocalCryptoNotReadyException(
                            "sealed_sender_certificate_unavailable"
                        )
                } else {
                    null
                }
                val sendResult = ApiService.sendMessage(
                    token = sendToken,
                    chatId = chatId,
                    content = wireContent,
                    type = msg.type.name,
                    id = msg.id,
                    sealedSender = msg.sealedSender,
                    sealedSenderCertificate = sealedCertificate,
                )
                if (sendResult.isFailure) {
                    val err = sendResult.exceptionOrNull()
                    // 8.45：本地 sessionChangedResult() 的 409（serverCode=SESSION_CHANGED，
                    // 切换账号瞬间响应晚到）与真实「重复客户端消息 ID」的 409 必须区分——
                    // 前者不是送达确认，标 SENT 会在窗口内污染新账号数据
                    val isSessionChanged = err is ApiException && err.serverCode == "SESSION_CHANGED"
                    if (isSessionChanged) {
                        Log.i(TAG, "outbox flush aborted: session changed during send for ${msg.id}")
                        return@withLock
                    }
                    // Only a dedicated protocol code may acknowledge an already-applied send.
                    // In particular, MESSAGE_ID_CONFLICT (and a localized "消息 ID 已存在") is a
                    // real conflict and must flow through shouldMarkOutboxFailed -> FAILED.
                    // Current servers return exact idempotent retries as 2xx, so this is only a
                    // compatibility escape hatch for an older explicit acknowledgement code.
                    val alreadyAccepted = err is ApiException &&
                        err.kind == ApiFailureKind.HTTP &&
                        err.serverCode?.trim()?.equals("MESSAGE_ALREADY_ACCEPTED", ignoreCase = true) == true
                    if (!alreadyAccepted) throw err ?: IllegalStateException("outbox_send_failed")
                }
                if (!TextOutboxSessionPolicy.mayContinueFlush(
                        userId,
                        tokenManager.getToken().orEmpty(),
                        tokenManager.getUserId().orEmpty()
                    )
                ) {
                    Log.i(TAG, "outbox flush aborted: session changed after send for ${msg.id}")
                    return@withLock
                }
                if (groupEpoch != null) {
                    app.signalProtocol.markGroupSenderKeyMessageSent(chatId, groupEpoch, msg.id)
                }
                val sent = msg.copy(status = MessageStatus.SENT)
                messageRepo.insertMessage(sent)
                if (!TextOutboxSessionPolicy.mayContinueFlush(
                        userId,
                        tokenManager.getToken().orEmpty(),
                        tokenManager.getUserId().orEmpty()
                    )
                ) {
                    messageRepo.deleteMessage(sent.id)
                    return@withLock
                }
                // Background flush may be the first durable plaintext for this id on this process.
                try {
                    MessageSearchRepository(app.database).indexMessage(sent)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (indexError: Exception) {
                    Log.w(TAG, "indexMessage after outbox flush failed for ${msg.id}", indexError)
                }
                onMessageUpdated?.invoke(sent)
                // Keep chat-list tail in sync after background REST flush (leave-chat / process death).
                // STICKER/LOCATION list UI maps type→localized label; TEXT needs plaintext snippet.
                val preview = when (msg.type) {
                    MessageType.TEXT, MessageType.MARKDOWN -> msg.parsedContent().take(200)
                    MessageType.STICKER -> app.getString(com.maodouchat.R.string.message_preview_sticker)
                    MessageType.LOCATION -> app.getString(com.maodouchat.R.string.message_preview_location)
                    else -> msg.parsedContent().take(200)
                }
                MaodouchatApp.emitMessageSent(chatId, preview, msg.type.name)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "outbox flush failed for ${msg.id}: ${error.message}")
                if (!TextOutboxSessionPolicy.mayContinueFlush(
                        userId,
                        tokenManager.getToken().orEmpty(),
                        tokenManager.getUserId().orEmpty()
                    )
                ) {
                    return@withLock
                }
                if (shouldMarkOutboxFailed(error)) {
                    val failed = msg.copy(status = MessageStatus.FAILED)
                    messageRepo.insertMessage(failed)
                    if (!TextOutboxSessionPolicy.mayContinueFlush(
                            userId,
                            tokenManager.getToken().orEmpty(),
                            tokenManager.getUserId().orEmpty()
                        )
                    ) {
                        messageRepo.deleteMessage(failed.id)
                        return@withLock
                    }
                    onMessageUpdated?.invoke(failed)
                }
            }
        }
    }

    private suspend fun requireGroupEpoch(
        token: String,
        chatRepo: ChatRepository,
        groupId: String,
        known: Chat?
    ): Long {
        if (known?.isGroup == true && known.memberRevision > 0L) return known.memberRevision
        val cached = chatRepo.getChatById(groupId)
        if (cached?.isGroup == true && cached.memberRevision > 0L) return cached.memberRevision
        val liveResult = ApiService.getChats(token)
        liveResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        val live = liveResult.getOrNull()?.firstOrNull { it.id == groupId }
        val rev = live?.takeIf { it.isGroup }?.memberRevision
        if (rev != null && rev > 0L) return rev
        throw IllegalStateException("group_epoch_unknown:$groupId")
    }

    private const val TAG = "TextOutboxFlusher"
}
