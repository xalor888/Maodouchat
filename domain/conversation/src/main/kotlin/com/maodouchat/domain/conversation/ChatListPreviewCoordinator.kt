package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.ChatListPreviewPolicy
import com.maodouchat.core.session.OwnerSessionSnapshot
import com.maodouchat.core.model.NudgeDisplayPolicy
import com.maodouchat.core.model.Templates
import com.maodouchat.core.model.applyPreviewToChats
import com.maodouchat.core.model.listSenderLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 会话列表 last-message 预览（ChatList 瘦身：内存投影 / Room 回写 / 服务端占位本地化）。 */
class ChatListPreviewCoordinator(
    private val scope: CoroutineScope,
    private val getChats: () -> List<Chat>,
    private val updateChats: ((List<Chat>) -> List<Chat>) -> Unit,
    private val currentUserId: () -> String,
    private val currentSessionGeneration: () -> Long,
    private val isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean,
    private val withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean,
    private val getCachedChat: suspend (chatId: String) -> Chat?,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    private val mediaPreviewText: (MessageType) -> String,
    private val encryptedPlaceholder: String,
    private val revokedPlaceholder: String,
    private val nudgePlaceholder: String,
    private val systemPlaceholder: String,
    private val logWarning: (String, Throwable?) -> Unit,
    private val nudgeYouNudged: (target: String) -> String,
    private val nudgeTheyNudgedYou: (sender: String) -> String,
    private val nudgeTheyNudgedTarget: (sender: String, target: String) -> String,
) {
    // 更新内存与 Room 里的列表预览与排序键：unreadDelta 只在会话已在列表时应用
    //（incoming 路径）；forceTimestamp 原样取给定时间，默认取单调最大值，迟到的 WS 不能倒退排序。
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
        updateChats { chats ->
            applyPreviewToChats(
                chats = chats,
                chatId = chatId,
                previewText = previewText,
                messageType = messageType,
                timestamp = timestamp,
                unreadDelta = unreadDelta,
                forceTimestamp = forceTimestamp,
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
                                    getChats().find { it.id == chatId }?.unreadCount
                                        ?: cached.unreadCount
                                }
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logWarning("Failed to persist last-message preview", error)
            }
        }
    }

    fun mediaPreviewLabel(type: MessageType): String = mediaPreviewText(type)

    // 收件人视角的 NUDGE 文案；服务端存的 body 恒为发送者视角。
    fun listNudgePreview(
        isOwnMessage: Boolean,
        storedContent: String,
        senderId: String,
        chatId: String,
        chatHint: Chat? = null,
    ): String {
        val chat = chatHint ?: getChats().find { it.id == chatId }
        val senderName = listSenderLabel(chat, senderId)
        return NudgeDisplayPolicy.displayText(
            isOwnMessage = isOwnMessage,
            storedContent = storedContent,
            senderDisplayName = senderName,
            isDirectChat = chat?.isGroup != true,
            templates = Templates(
                youNudged = nudgeYouNudged,
                theyNudgedYou = nudgeTheyNudgedYou,
                theyNudgedTarget = nudgeTheyNudgedTarget,
            )
        )
    }

    // 服务端列表用类型占位符，优先取本地 Room 尾巴与客户端本地化媒体文案。
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
                val localizedLabel = nudgePlaceholder
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
                    server.copy(lastMessage = systemPlaceholder)
                } else {
                    server
                }
            }
            MessageType.REVOKED -> server.copy(
                lastMessage = revokedPlaceholder
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
                encryptedPlaceholder = encryptedPlaceholder,
                revokedPlaceholder = revokedPlaceholder,
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
                            encryptedPlaceholder
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
                        preview.text == encryptedPlaceholder
                    if (looksEncrypted) {
                        localizedMedia.copy(
                            lastMessage = ChatListPreviewPolicy.listVisibleText(
                                localizedMedia.lastMessage,
                                encryptedPlaceholder
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

    // 删除/撤回后从本地 Room 重算列表 last-message：绝对写入，空/旧尾巴直接替换陈旧 head。
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
                    encryptedPlaceholder = encryptedPlaceholder,
                    revokedPlaceholder = revokedPlaceholder,
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
                logWarning("Failed to refresh list preview from local", error)
            }
        }
    }
}
