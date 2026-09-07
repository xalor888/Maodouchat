package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话列表 last-message 预览（ChatList 瘦身：内存投影 / Room 回写 / 服务端占位本地化）。
 */
internal class ChatListPreviewCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val currentUserId: () -> String,
    private val currentSessionGeneration: () -> Long,
    private val isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean,
    private val withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean,
    private val getCachedChat: suspend (chatId: String) -> Chat?,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    private val text: (Int) -> String,
    private val nudgeYouNudged: (target: String) -> String,
    private val nudgeTheyNudgedYou: (sender: String) -> String,
    private val nudgeTheyNudgedTarget: (sender: String, target: String) -> String,
) {
    /**
     * Update list preview + sort key in memory and Room.
     * [unreadDelta] is applied only when the chat is already on the list (incoming path).
     * [forceTimestamp] uses the given time as-is (delete/revoke/empty tail); default keeps
     * monotonic max so late WS cannot rewind sort order for ordinary sends.
     */
    fun applyChatListPreview(
        chatId: String,
        previewText: String,
        messageType: MessageType,
        timestamp: Long,
        unreadDelta: Int = 0,
        forceTimestamp: Boolean = false,
        ownerUserId: String = currentUserId(),
        sessionGeneration: Long = currentSessionGeneration(),
    ) {
        val session = OwnerSessionSnapshot(ownerUserId, sessionGeneration)
        if (chatId.isBlank() || !isOwnerSessionCurrent(session)) return
        uiState.update { state ->
            state.copy(
                chats = applyPreviewToChats(
                    chats = state.chats,
                    chatId = chatId,
                    previewText = previewText,
                    messageType = messageType,
                    timestamp = timestamp,
                    unreadDelta = unreadDelta,
                    forceTimestamp = forceTimestamp,
                )
            )
        }
        scope.launch {
            try {
                withOwnerRoomWrite(session) {
                    val cached = getCachedChat(chatId) ?: return@withOwnerRoomWrite
                    val nextTime = if (forceTimestamp) timestamp else maxOf(cached.lastMessageTime, timestamp)
                    cacheChats(
                        listOf(
                            cached.copy(
                                lastMessage = previewText,
                                lastMessageType = messageType,
                                lastMessageTime = nextTime,
                                unreadCount = if (unreadDelta != 0) {
                                    (cached.unreadCount + unreadDelta).coerceAtLeast(0)
                                } else {
                                    uiState.value.chats.find { it.id == chatId }?.unreadCount
                                        ?: cached.unreadCount
                                }
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to persist last-message preview", error)
            }
        }
    }

    fun mediaPreviewLabel(type: MessageType): String = when (type) {
        MessageType.IMAGE -> text(R.string.message_preview_image)
        MessageType.GIF -> text(R.string.message_preview_gif)
        MessageType.STICKER -> text(R.string.message_preview_sticker)
        MessageType.LOCATION -> text(R.string.message_preview_location)
        MessageType.VOICE -> text(R.string.message_preview_voice)
        MessageType.VIDEO -> text(R.string.message_preview_video)
        MessageType.FILE -> text(R.string.message_preview_file)
        MessageType.NUDGE -> text(R.string.message_preview_nudge)
        MessageType.SYSTEM -> text(R.string.message_preview_system)
        else -> text(R.string.message_preview_encrypted)
    }

    /** Recipient-facing NUDGE copy; stored body is always sender-centric from server. */
    fun listNudgePreview(
        isOwnMessage: Boolean,
        storedContent: String,
        senderId: String,
        chatId: String,
        chatHint: Chat? = null,
    ): String {
        val chat = chatHint ?: uiState.value.chats.find { it.id == chatId }
        val senderName = listSenderLabel(chat, senderId)
        return NudgeDisplayPolicy.displayText(
            isOwnMessage = isOwnMessage,
            storedContent = storedContent,
            senderDisplayName = senderName,
            isDirectChat = chat?.isGroup != true,
            templates = NudgeDisplayPolicy.Templates(
                youNudged = nudgeYouNudged,
                theyNudgedYou = nudgeTheyNudgedYou,
                theyNudgedTarget = nudgeTheyNudgedTarget,
            )
        )
    }

    /**
     * Server list uses type placeholders (NUDGE → "[提醒]", TEXT → encrypted label,
     * media → Chinese e2ee labels). Prefer local Room tail + client-localized media labels.
     */
    suspend fun enrichServerChatPreview(
        server: Chat,
        ownerUserId: String = currentUserId(),
    ): Chat {
        // Always localize media/revoked placeholders even when Room has no tail yet.
        val localizedMedia = when (server.lastMessageType) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> server.copy(lastMessage = mediaPreviewLabel(server.lastMessageType))
            MessageType.NUDGE -> {
                // Server placeholder is Chinese "[提醒]"; prefer local POV or localized label.
                val localizedLabel = text(R.string.message_preview_nudge)
                if (server.lastMessage.isBlank() ||
                    server.lastMessage == "[提醒]" ||
                    server.lastMessage == localizedLabel
                ) {
                    server.copy(lastMessage = localizedLabel)
                } else {
                    server
                }
            }
            MessageType.SYSTEM -> {
                // Server placeholder is Chinese "[系统]"; keep real system body when present.
                val body = server.lastMessage.trim()
                if (body.isBlank() || body == "[系统]") {
                    server.copy(lastMessage = text(R.string.message_preview_system))
                } else {
                    server
                }
            }
            MessageType.REVOKED -> server.copy(
                lastMessage = text(R.string.chat_message_revoked_placeholder)
            )
            else -> server
        }
        if (localizedMedia.lastMessageType != MessageType.NUDGE &&
            localizedMedia.lastMessageType != MessageType.TEXT &&
            localizedMedia.lastMessageType != MessageType.MARKDOWN
        ) {
            return localizedMedia
        }
        return try {
            val recent = getRecentMessages(localizedMedia.id, 24)
            val preview = ChatListPreviewPolicy.fromLatestMessages(
                candidatesNewestFirst = recent,
                mediaLabel = { mediaPreviewLabel(it) },
                encryptedPlaceholder = text(R.string.message_preview_encrypted),
                revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
                nudgeText = { msg ->
                    listNudgePreview(
                        isOwnMessage = msg.senderId == ownerUserId,
                        storedContent = msg.content,
                        senderId = msg.senderId,
                        chatId = localizedMedia.id,
                        chatHint = localizedMedia
                    )
                }
            )
            if (preview.text.isBlank()) {
                return if (localizedMedia.lastMessageType == MessageType.TEXT ||
                    localizedMedia.lastMessageType == MessageType.MARKDOWN
                ) {
                    localizedMedia.copy(
                        lastMessage = ChatListPreviewPolicy.listVisibleText(
                            localizedMedia.lastMessage,
                            text(R.string.message_preview_encrypted)
                        )
                    )
                } else {
                    localizedMedia
                }
            }
            when (preview.type) {
                MessageType.NUDGE -> localizedMedia.copy(
                    lastMessage = preview.text,
                    lastMessageType = preview.type,
                    lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                )
                MessageType.TEXT, MessageType.MARKDOWN -> {
                    // Only replace when local has readable plaintext (own send / decrypted).
                    val looksEncrypted = ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(preview.text) ||
                        preview.text == text(R.string.message_preview_encrypted)
                    if (looksEncrypted) {
                        localizedMedia.copy(
                            lastMessage = ChatListPreviewPolicy.listVisibleText(
                                localizedMedia.lastMessage,
                                text(R.string.message_preview_encrypted)
                            )
                        )
                    } else {
                        localizedMedia.copy(
                            lastMessage = preview.text.take(280),
                            lastMessageType = MessageType.TEXT,
                            lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                        )
                    }
                }
                MessageType.IMAGE,
                MessageType.GIF,
                MessageType.STICKER,
                MessageType.LOCATION,
                MessageType.VOICE,
                MessageType.VIDEO,
                MessageType.FILE,
                MessageType.REVOKED -> localizedMedia.copy(
                    lastMessage = preview.text,
                    lastMessageType = preview.type,
                    lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                )
                else -> localizedMedia
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            localizedMedia
        }
    }

    /**
     * Recompute list last-message from local Room after delete/revoke (and optional head edit).
     * Absolute write so empty/older tails replace a stale head without waiting for getChats.
     */
    fun refreshChatListPreviewFromLocal(
        chatId: String,
        ownerUserId: String = currentUserId(),
        sessionGeneration: Long = currentSessionGeneration(),
    ) {
        val session = OwnerSessionSnapshot(ownerUserId, sessionGeneration)
        if (chatId.isBlank() || !isOwnerSessionCurrent(session)) return
        scope.launch {
            try {
                // Fetch a few rows so a trailing SK_DIST does not wipe a real conversation head.
                val recent = getRecentMessages(chatId, 24)
                if (!isOwnerSessionCurrent(session)) return@launch
                val preview = ChatListPreviewPolicy.fromLatestMessages(
                    candidatesNewestFirst = recent,
                    mediaLabel = { mediaPreviewLabel(it) },
                    encryptedPlaceholder = text(R.string.message_preview_encrypted),
                    revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
                    nudgeText = { msg ->
                        listNudgePreview(
                            isOwnMessage = msg.senderId == ownerUserId,
                            storedContent = msg.content,
                            senderId = msg.senderId,
                            chatId = chatId
                        )
                    }
                )
                applyChatListPreview(
                    chatId = chatId,
                    previewText = preview.text,
                    messageType = preview.type,
                    timestamp = preview.timestamp,
                    forceTimestamp = true,
                    ownerUserId = ownerUserId,
                    sessionGeneration = sessionGeneration,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to refresh list preview from local", error)
            }
        }
    }

    companion object {
        private const val TAG = "ChatListPreview"
    }
}
