package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatListPreview
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.Templates
import com.maodouchat.core.session.OwnerSessionSnapshot
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr211c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit211c(b: Boolean): String = if (b) "1" else "0"

private fun opt211c(s: String?): String = s?.let { encStr211c(it) } ?: "~"

private fun optLong211c(v: Long?): String = v?.toString() ?: "~"

private data class LstUser211c(
    val id: String,
    val name: String = "",
    val nickname: String? = null,
)

private fun LstUser211c.toUser(): User = User(id, name, nickname = nickname)

private fun lstUserDisplay211c(u: LstUser211c): String =
    u.nickname?.takeIf(String::isNotBlank)
        ?: u.name.takeIf(String::isNotBlank)
        ?: u.id

private fun lstUserEnc211c(u: LstUser211c): String =
    encStr211c(u.id) + "," + encStr211c(u.name) + "," + opt211c(u.nickname)

private data class LstChat211c(
    val id: String,
    val participants: List<LstUser211c> = emptyList(),
    val lastMessage: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    val chatType: String = "DIRECT",
    val groupName: String? = null,
    val pinnedAt: Long = 0L,
    val notificationsMuted: Boolean = false,
    val archived: Boolean = false,
    val markedUnread: Boolean = false,
    val settingsUpdatedAt: Long = 0L,
    val groupAnnouncement: String? = null,
    val groupAvatar: String? = null,
    val memberRevision: Long = 0L,
    val disappearingMessageSeconds: Int = 0,
)

private fun LstChat211c.toChat(): Chat = Chat(
    id = id,
    participants = participants.map { it.toUser() },
    lastMessage = lastMessage,
    lastMessageType = MessageType.valueOf(lastMessageType),
    lastMessageTime = lastMessageTime,
    unreadCount = unreadCount,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
    pinnedAt = pinnedAt,
    notificationsMuted = notificationsMuted,
    archived = archived,
    markedUnread = markedUnread,
    settingsUpdatedAt = settingsUpdatedAt,
    groupAnnouncement = groupAnnouncement,
    groupAvatar = groupAvatar,
    memberRevision = memberRevision,
    disappearingMessageSeconds = disappearingMessageSeconds,
)

private fun lstChatFrom211c(c: Chat): LstChat211c = LstChat211c(
    id = c.id,
    participants = c.participants.map { LstUser211c(it.id, it.name, it.nickname) },
    lastMessage = c.lastMessage,
    lastMessageType = c.lastMessageType.name,
    lastMessageTime = c.lastMessageTime,
    unreadCount = c.unreadCount,
    isGroup = c.isGroup,
    chatType = c.chatType,
    groupName = c.groupName,
    pinnedAt = c.pinnedAt,
    notificationsMuted = c.notificationsMuted,
    archived = c.archived,
    markedUnread = c.markedUnread,
    settingsUpdatedAt = c.settingsUpdatedAt,
    groupAnnouncement = c.groupAnnouncement,
    groupAvatar = c.groupAvatar,
    memberRevision = c.memberRevision,
    disappearingMessageSeconds = c.disappearingMessageSeconds,
)

private fun lstChatEnc211c(c: LstChat211c): String = listOf(
    encStr211c(c.id),
    c.participants.joinToString("+") { lstUserEnc211c(it) },
    encStr211c(c.lastMessage),
    c.lastMessageType,
    c.lastMessageTime.toString(),
    c.unreadCount.toString(),
    bit211c(c.isGroup),
    encStr211c(c.chatType),
    opt211c(c.groupName),
    c.pinnedAt.toString(),
    bit211c(c.notificationsMuted),
    bit211c(c.archived),
    bit211c(c.markedUnread),
    c.settingsUpdatedAt.toString(),
    opt211c(c.groupAnnouncement),
    opt211c(c.groupAvatar),
    c.memberRevision.toString(),
    c.disappearingMessageSeconds.toString(),
).joinToString(":")

// 最近消息只读 content/type/timestamp/senderId，取最小字段集。
private data class LstMsg211c(
    val id: String,
    val chatId: String = "c1",
    val senderId: String = "u1",
    val content: String = "hello",
    val type: String = "TEXT",
    val timestamp: Long = 1000L,
)

private fun LstMsg211c.toMessage(): Message = Message(
    id = id,
    chatId = chatId,
    senderId = senderId,
    content = content,
    type = MessageType.valueOf(type),
    timestamp = timestamp,
)

private fun lstMsgEnc211c(m: LstMsg211c): String = listOf(
    encStr211c(m.id),
    encStr211c(m.chatId),
    encStr211c(m.senderId),
    encStr211c(m.content),
    m.type,
    m.timestamp.toString(),
).joinToString(":")

// op kind：0 applyChatListPreview，1 listNudgePreview，
// 2 enrichServerChatPreview，3 refreshChatListPreviewFromLocal，
// 4 mediaPreviewLabel。
// recentFault：0 成功、1 getRecentMessages 抛 RT、2 抛 CE。
// roomFault：0 成功、1 getCachedChat 抛 RT、2 抛 CE、3 cacheChats 抛 RT、
// 4 cacheChats 抛 CE。chatHintIdx / serverChatIdx：种子会话下标，-1 表无。
private data class LstOp211c(
    val kind: Int,
    val chatId: String = "",
    val previewText: String = "",
    val messageType: String = "TEXT",
    val timestamp: Long = 0L,
    val unreadDelta: Int = 0,
    val forceTimestamp: Boolean = false,
    val ownerUserId: String = "",
    val sessionGeneration: Long = 1L,
    val isOwnMessage: Boolean = false,
    val storedContent: String = "",
    val senderId: String = "",
    val chatHintIdx: Int = -1,
    val serverChatIdx: Int = 0,
    val recentFault: Int = 0,
    val roomFault: Int = 0,
)

private data class LstCase211c(
    val owner: String = "me",
    val sessionGen: Long = 1L,
    val sessOk: Boolean = true,
    val encryptedPlaceholder: String = "[enc]",
    val revokedPlaceholder: String = "[revoked]",
    val nudgePlaceholder: String = "[nudge]",
    val systemPlaceholder: String = "[sys]",
    val seedChats: List<LstChat211c> = emptyList(),
    val seedRecent: List<Pair<String, List<LstMsg211c>>> = emptyList(),
    val ops: List<LstOp211c> = emptyList(),
)

// ---- ChatListPreviewPolicy / NudgeDisplayPolicy / 相关纯函数的镜像 ----

private val LST_PLACEHOLDERS211c = setOf(
    "无法解密", "解密失败", "群密钥缺失", "密钥缺失", "decrypt failed",
    "session missing", "identity changed", "[无法解密的消息]",
    "[无法解密的群聊消息]", "[加密消息]", "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]", "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]", "[群聊密钥 epoch 较新，请刷新群信息]",
    "[无法解密的图片]", "[无法解密的 gif]", "[无法解密的贴纸]",
    "[无法解密的位置]", "[无法解密的视频]", "[无法解密的语音]",
    "[无法解密的文件]", "[unable to decrypt message]",
    "[unable to decrypt group message]", "[encrypted message]",
    "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group key epoch. refresh group info]",
    "[unable to decrypt image]", "[unable to decrypt gif]",
    "[unable to decrypt sticker]", "[unable to decrypt location]",
    "[unable to decrypt video]", "[unable to decrypt voice message]",
    "[unable to decrypt file]",
)

private fun lstIsPlaceholder211c(content: String): Boolean {
    val normalized = content.trim().lowercase()
    if (normalized.isEmpty()) return false
    return normalized in LST_PLACEHOLDERS211c
}

private fun lstUnescape211c(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = Regex("&#(\\d{1,7});").replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE).replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

private fun lstIsRemoteAttachmentUri211c(value: String): Boolean =
    value.startsWith("maodou-attachment://")

private fun lstLooksLikeLocalMediaUri211c(content: String): Boolean {
    val t = content.trim()
    return t.startsWith("file:") || t.startsWith("content:") ||
        lstIsRemoteAttachmentUri211c(t)
}

private fun lstIsSignalWireEnvelope211c(content: String): Boolean {
    val t = content.trimStart()
    if (!t.startsWith("{") && !t.startsWith("[{")) return false
    if (t.contains("signal-")) {
        return t.contains("\"algorithm\"") || t.contains("\"distributionMessage\"") ||
            t.contains("\"ciphertext\"") || t.contains("\"senderKey")
    }
    val hasCiphertext = t.contains("\"ciphertext\"")
    val hasSenderDevice = t.contains("\"senderDeviceId\"")
    val hasEntries = t.contains("\"entries\"")
    val hasCiphertextType = t.contains("\"ciphertextType\"")
    val hasDistribution = t.contains("\"distributionMessage\"")
    return hasDistribution ||
        (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
}

private fun lstLooksLikeGarbage211c(content: String): Boolean {
    val t = content.trim()
    if (t.isBlank()) return true
    if (lstLooksLikeLocalMediaUri211c(t)) return true
    if (lstIsSignalWireEnvelope211c(t)) return true
    if (t.startsWith("eyJ") && t.length >= 40) return true
    if (t.contains("<meta>", ignoreCase = true) || t.contains("</meta>", ignoreCase = true)) return true
    if (lstIsPlaceholder211c(t)) return true
    val isUrlOnly = t.isNotEmpty() && !t.contains(' ') && !t.contains('\n') &&
        (t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("www.", ignoreCase = true))
    if (isUrlOnly) {
        val lower = t.lowercase()
        if (lower.contains("/api/attachments") || lower.contains("maodou-attachment") ||
            lower.contains("ciphertext") || lower.contains("senderdeviceid")
        ) {
            return true
        }
    }
    return false
}

private fun lstVisiblePreviewText211c(raw: String): String {
    val withoutMeta = raw.lastIndexOf(Message.META_TAG_PREFIX).let { idx ->
        if (idx < 0) raw else raw.substring(0, idx)
    }.trim()
    if (withoutMeta.isBlank()) return ""
    if (lstLooksLikeLocalMediaUri211c(withoutMeta)) return ""
    return lstUnescape211c(withoutMeta)
}

private fun lstListVisibleText211c(raw: String, encryptedPlaceholder: String): String {
    val body = lstVisiblePreviewText211c(raw)
    if (body.isBlank() || lstLooksLikeGarbage211c(body)) return encryptedPlaceholder
    return body
}

private fun lstIsNoise211c(type: MessageType): Boolean = type == MessageType.SK_DIST

private fun lstIsUnreadableHead211c(message: Message): Boolean {
    if (lstIsNoise211c(message.type)) return true
    return when (message.type) {
        MessageType.TEXT, MessageType.MARKDOWN ->
            lstLooksLikeGarbage211c(lstVisiblePreviewText211c(message.content).ifBlank { message.content })
        else -> false
    }
}

private fun lstNudgeTarget211c(storedContent: String): String {
    val c = storedContent.trim()
    return when {
        c.startsWith("你拍了拍") -> c.removePrefix("你拍了拍").trim()
        c.startsWith("You nudged ", ignoreCase = true) -> c.substring("You nudged ".length).trim()
        else -> ""
    }
}

private fun lstNudgeDisplay211c(
    isOwnMessage: Boolean,
    storedContent: String,
    senderDisplayName: String,
    isDirectChat: Boolean,
    templates: Templates,
): String {
    val target = lstNudgeTarget211c(storedContent).ifBlank { storedContent.trim() }
    if (isOwnMessage) {
        return if (target.isNotBlank()) templates.youNudged(target) else storedContent
    }
    val sender = senderDisplayName.trim().ifBlank { "…" }
    return if (isDirectChat) {
        templates.theyNudgedYou(sender)
    } else {
        templates.theyNudgedTarget(sender, target.ifBlank { "…" })
    }
}

private fun lstTruncatedSenderId211c(senderId: String): String? {
    val id = senderId.trim()
    if (id.isEmpty()) return null
    return if (id.length <= 8) id else id.take(8)
}

private fun lstSenderLabel211c(chat: Chat?, senderId: String): String {
    val fromParticipant = chat?.participants
        ?.firstOrNull { it.id == senderId }
        ?.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val truncated = lstTruncatedSenderId211c(senderId)
    return fromParticipant ?: truncated ?: senderId
}

private fun lstFromLatestMessage211c(
    latest: Message?,
    mediaLabel: (MessageType) -> String,
    encryptedPlaceholder: String,
    revokedPlaceholder: String,
    nudgeText: ((Message) -> String)?,
): ChatListPreview {
    if (latest == null || lstIsNoise211c(latest.type)) {
        return ChatListPreview(text = "", type = MessageType.TEXT, timestamp = 0L)
    }
    val text = when (latest.type) {
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.STICKER,
        MessageType.LOCATION,
        MessageType.VOICE,
        MessageType.VIDEO,
        MessageType.FILE -> mediaLabel(latest.type)
        MessageType.NUDGE -> nudgeText?.invoke(latest) ?: latest.content
        MessageType.REVOKED -> revokedPlaceholder
        MessageType.SYSTEM -> latest.content
        MessageType.SK_DIST -> ""
        MessageType.TEXT, MessageType.MARKDOWN -> {
            lstListVisibleText211c(latest.content, encryptedPlaceholder)
        }
    }
    return ChatListPreview(text = text, type = latest.type, timestamp = latest.timestamp)
}

private fun lstFromLatestMessages211c(
    candidatesNewestFirst: List<Message>,
    mediaLabel: (MessageType) -> String,
    encryptedPlaceholder: String,
    revokedPlaceholder: String,
    nudgeText: ((Message) -> String)?,
): ChatListPreview {
    val head = candidatesNewestFirst.firstOrNull { candidate ->
        !lstIsNoise211c(candidate.type) && !lstIsUnreadableHead211c(candidate)
    }
    return lstFromLatestMessage211c(head, mediaLabel, encryptedPlaceholder, revokedPlaceholder, nudgeText)
}

private val lstPinnedFirstOrder211c: Comparator<Chat> =
    compareByDescending<Chat> { it.pinnedAt > 0 }
        .thenByDescending { it.pinnedAt }
        .thenByDescending { it.lastMessageTime }

private fun lstApplyPreviewToChats211c(
    chats: List<Chat>,
    chatId: String,
    previewText: String,
    messageType: MessageType,
    timestamp: Long,
    unreadDelta: Int = 0,
    forceTimestamp: Boolean = false,
): List<Chat> {
    val target = chats.find { it.id == chatId } ?: return chats
    val others = chats.filterNot { it.id == chatId }
    val nextTime = if (forceTimestamp) timestamp else maxOf(target.lastMessageTime, timestamp)
    val updatedTarget = target.copy(
        lastMessage = previewText,
        lastMessageType = messageType,
        lastMessageTime = nextTime,
        unreadCount = (target.unreadCount + unreadDelta).coerceAtLeast(0),
    )
    return (others + updatedTarget).sortedWith(lstPinnedFirstOrder211c)
}

private fun lstOracle211c(c: LstCase211c): String {
    val effects = mutableListOf<String>()
    var chats = c.seedChats.map { it.toChat() }
    var roomChats = c.seedChats.map { it.toChat() }
    val warnings = mutableListOf<String>()
    fun snap(prefix: String) {
        val snap = listOf(
            chats.joinToString(";") { lstChatEnc211c(lstChatFrom211c(it)) },
            roomChats.joinToString(";") { lstChatEnc211c(lstChatFrom211c(it)) },
            warnings.joinToString(";") { encStr211c(it) },
        ).joinToString("|")
        effects += prefix + ":" + snap
    }
    fun mediaLabel(type: MessageType): String = "M:" + type.name
    fun templates(): Templates = Templates(
        youNudged = { t -> "YN:" + t },
        theyNudgedYou = { s -> "TY:" + s },
        theyNudgedTarget = { s, t -> "TT:" + s + "/" + t },
    )
    fun updateChats(transform: (List<Chat>) -> List<Chat>) {
        chats = transform(chats)
        snap("ST")
    }
    fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean {
        effects += "CALL:session"
        return c.sessOk
    }
    fun logWarning(msg: String, err: Throwable?) {
        val token = msg + ":" + (err?.javaClass?.simpleName ?: "~")
        effects += "WARN:" + encStr211c(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
        warnings += token
    }
    fun getCachedChat(chatId: String, op: LstOp211c): Chat? {
        effects += "CALL:getCached:" + encStr211c(chatId)
        when (op.roomFault) {
            1 -> throw RuntimeException("boom")
            2 -> throw CancellationException("cancel-boom")
        }
        return roomChats.find { it.id == chatId }
    }
    fun cacheChats(list: List<Chat>, op: LstOp211c) {
        effects += "CALL:cache:" + list.joinToString(";") { lstChatEnc211c(lstChatFrom211c(it)) }
        when (op.roomFault) {
            3 -> throw RuntimeException("boom")
            4 -> throw CancellationException("cancel-boom")
        }
        for (chat in list) {
            roomChats = if (roomChats.any { it.id == chat.id }) {
                roomChats.map { if (it.id == chat.id) chat else it }
            } else {
                roomChats + chat
            }
        }
    }
    fun getRecentMessages(chatId: String, op: LstOp211c): List<Message> {
        effects += "CALL:recent:" + encStr211c(chatId) + ":24"
        when (op.recentFault) {
            1 -> throw RuntimeException("boom")
            2 -> throw CancellationException("cancel-boom")
        }
        return c.seedRecent.firstOrNull { it.first == chatId }?.second?.map { it.toMessage() } ?: emptyList()
    }
    fun nudgeText(msg: Message, chatId: String, ownerUserId: String): String {
        val chat = chats.find { it.id == chatId }
        return lstNudgeDisplay211c(
            isOwnMessage = msg.senderId == ownerUserId,
            storedContent = msg.content,
            senderDisplayName = lstSenderLabel211c(chat, msg.senderId),
            isDirectChat = chat?.isGroup != true,
            templates = templates(),
        )
    }
    // launch 体：CE 被协程机制静默吞掉（不记 token），其他异常由 handler 记一次。
    fun launchBody(body: () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            // 不记 token。
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
    }
    fun withOwnerRoomWrite(op: LstOp211c, block: () -> Unit) {
        effects += "CALL:room"
        block()
    }
    fun applyPreview(op: LstOp211c) {
        val session = OwnerSessionSnapshot(op.ownerUserId, op.sessionGeneration)
        if (op.chatId.isBlank() || !isOwnerSessionCurrent(session)) return
        updateChats { list ->
            lstApplyPreviewToChats211c(
                chats = list,
                chatId = op.chatId,
                previewText = op.previewText,
                messageType = MessageType.valueOf(op.messageType),
                timestamp = op.timestamp,
                unreadDelta = op.unreadDelta,
                forceTimestamp = op.forceTimestamp,
            )
        }
        launchBody {
            try {
                withOwnerRoomWrite(op) {
                    val cached = getCachedChat(op.chatId, op) ?: return@withOwnerRoomWrite
                    val nextTime = if (op.forceTimestamp) op.timestamp else maxOf(cached.lastMessageTime, op.timestamp)
                    cacheChats(
                        listOf(
                            cached.copy(
                                lastMessage = op.previewText,
                                lastMessageType = MessageType.valueOf(op.messageType),
                                lastMessageTime = nextTime,
                                unreadCount = if (op.unreadDelta != 0) {
                                    (cached.unreadCount + op.unreadDelta).coerceAtLeast(0)
                                } else {
                                    chats.find { it.id == op.chatId }?.unreadCount
                                        ?: cached.unreadCount
                                },
                            ),
                        ),
                        op,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Failed to persist last-message preview", e)
            }
        }
    }
    fun listNudge(op: LstOp211c): String {
        val chat = if (op.chatHintIdx >= 0) chats.getOrNull(op.chatHintIdx) else null
            ?: chats.find { it.id == op.chatId }
        val senderName = lstSenderLabel211c(chat, op.senderId)
        return lstNudgeDisplay211c(
            isOwnMessage = op.isOwnMessage,
            storedContent = op.storedContent,
            senderDisplayName = senderName,
            isDirectChat = chat?.isGroup != true,
            templates = templates(),
        )
    }
    fun enrich(op: LstOp211c): Chat {
        val server = chats[op.serverChatIdx]
        val localizedMedia = when (MessageType.valueOf(server.lastMessageType.name)) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> server.copy(lastMessage = mediaLabel(server.lastMessageType))
            MessageType.NUDGE -> {
                if (server.lastMessage.isBlank() ||
                    server.lastMessage == "[提醒]" ||
                    server.lastMessage == c.nudgePlaceholder
                ) {
                    server.copy(lastMessage = c.nudgePlaceholder)
                } else {
                    server
                }
            }
            MessageType.SYSTEM -> {
                val body = server.lastMessage.trim()
                if (body.isBlank() || body == "[系统]") {
                    server.copy(lastMessage = c.systemPlaceholder)
                } else {
                    server
                }
            }
            MessageType.REVOKED -> server.copy(lastMessage = c.revokedPlaceholder)
            else -> server
        }
        if (localizedMedia.lastMessageType != MessageType.NUDGE &&
            localizedMedia.lastMessageType != MessageType.TEXT &&
            localizedMedia.lastMessageType != MessageType.MARKDOWN
        ) {
            return localizedMedia
        }
        return try {
            val recent = getRecentMessages(localizedMedia.id, op)
            val preview = lstFromLatestMessages211c(
                candidatesNewestFirst = recent,
                mediaLabel = { mediaLabel(it) },
                encryptedPlaceholder = c.encryptedPlaceholder,
                revokedPlaceholder = c.revokedPlaceholder,
                nudgeText = { msg ->
                    nudgeText(msg, localizedMedia.id, op.ownerUserId)
                },
            )
            if (preview.text.isBlank()) {
                if (localizedMedia.lastMessageType == MessageType.TEXT ||
                    localizedMedia.lastMessageType == MessageType.MARKDOWN
                ) {
                    localizedMedia.copy(
                        lastMessage = lstListVisibleText211c(
                            localizedMedia.lastMessage,
                            c.encryptedPlaceholder,
                        ),
                    )
                } else {
                    localizedMedia
                }
            } else {
                when (preview.type) {
                    MessageType.NUDGE -> localizedMedia.copy(
                        lastMessage = preview.text,
                        lastMessageType = preview.type,
                        lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp),
                    )
                    MessageType.TEXT, MessageType.MARKDOWN -> {
                        val looksEncrypted = lstLooksLikeGarbage211c(preview.text) ||
                            preview.text == c.encryptedPlaceholder
                        if (looksEncrypted) {
                            localizedMedia.copy(
                                lastMessage = lstListVisibleText211c(
                                    localizedMedia.lastMessage,
                                    c.encryptedPlaceholder,
                                ),
                            )
                        } else {
                            localizedMedia.copy(
                                lastMessage = preview.text.take(280),
                                lastMessageType = MessageType.TEXT,
                                lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp),
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
                        lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp),
                    )
                    else -> localizedMedia
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            localizedMedia
        }
    }
    fun refresh(op: LstOp211c) {
        val session = OwnerSessionSnapshot(op.ownerUserId, op.sessionGeneration)
        if (op.chatId.isBlank() || !isOwnerSessionCurrent(session)) return
        launchBody {
            try {
                val recent = getRecentMessages(op.chatId, op)
                if (!isOwnerSessionCurrent(session)) return@launchBody
                val preview = lstFromLatestMessages211c(
                    candidatesNewestFirst = recent,
                    mediaLabel = { mediaLabel(it) },
                    encryptedPlaceholder = c.encryptedPlaceholder,
                    revokedPlaceholder = c.revokedPlaceholder,
                    nudgeText = { msg ->
                        nudgeText(msg, op.chatId, op.ownerUserId)
                    },
                )
                applyPreview(
                    op.copy(
                        previewText = preview.text,
                        messageType = preview.type.name,
                        timestamp = preview.timestamp,
                        unreadDelta = 0,
                        forceTimestamp = true,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Failed to refresh list preview from local", e)
            }
        }
    }
    for (op in c.ops) {
        try {
            when (op.kind) {
                0 -> applyPreview(op)
                1 -> effects += "NUDGE:" + encStr211c(listNudge(op))
                2 -> {
                    val out = enrich(op)
                    effects += "ENRICH:" + lstChatEnc211c(lstChatFrom211c(out))
                }
                3 -> refresh(op)
                else -> effects += "LABEL:" + encStr211c(mediaLabel(MessageType.valueOf(op.messageType)))
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun lstProd211c(c: LstCase211c): String {
    val effects = mutableListOf<String>()
    var chats = c.seedChats.map { it.toChat() }
    var roomChats = c.seedChats.map { it.toChat() }
    val warnings = mutableListOf<String>()
    fun snap(prefix: String) {
        val snap = listOf(
            chats.joinToString(";") { lstChatEnc211c(lstChatFrom211c(it)) },
            roomChats.joinToString(";") { lstChatEnc211c(lstChatFrom211c(it)) },
            warnings.joinToString(";") { encStr211c(it) },
        ).joinToString("|")
        effects += prefix + ":" + snap
    }
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            repeat(100) {
                val kids = scope.coroutineContext[Job]?.children?.toList() ?: emptyList()
                if (kids.isEmpty()) return@runBlocking
                for (child in kids) runCatching { child.join() }
            }
        }
    }
    var curOp = LstOp211c(0)
    val coordinator = ChatListPreviewCoordinator(
        scope = scope,
        getChats = { chats },
        updateChats = { transform ->
            chats = transform(chats)
            snap("ST")
        },
        currentUserId = { c.owner },
        currentSessionGeneration = { c.sessionGen },
        isOwnerSessionCurrent = {
            effects += "CALL:session"
            c.sessOk
        },
        withOwnerRoomWrite = { _, block ->
            effects += "CALL:room"
            block()
            true
        },
        getCachedChat = { chatId ->
            effects += "CALL:getCached:" + encStr211c(chatId)
            when (curOp.roomFault) {
                1 -> throw RuntimeException("boom")
                2 -> throw CancellationException("cancel-boom")
                else -> roomChats.find { it.id == chatId }
            }
        },
        cacheChats = { list ->
            effects += "CALL:cache:" + list.joinToString(";") { lstChatEnc211c(lstChatFrom211c(it)) }
            when (curOp.roomFault) {
                3 -> throw RuntimeException("boom")
                4 -> throw CancellationException("cancel-boom")
                else -> {
                    for (chat in list) {
                        roomChats = if (roomChats.any { it.id == chat.id }) {
                            roomChats.map { if (it.id == chat.id) chat else it }
                        } else {
                            roomChats + chat
                        }
                    }
                }
            }
        },
        getRecentMessages = { chatId, limit ->
            effects += "CALL:recent:" + encStr211c(chatId) + ":" + limit
            when (curOp.recentFault) {
                1 -> throw RuntimeException("boom")
                2 -> throw CancellationException("cancel-boom")
                else -> c.seedRecent.firstOrNull { it.first == chatId }?.second?.map { it.toMessage() } ?: emptyList()
            }
        },
        mediaPreviewText = { type -> "M:" + type.name },
        encryptedPlaceholder = c.encryptedPlaceholder,
        revokedPlaceholder = c.revokedPlaceholder,
        nudgePlaceholder = c.nudgePlaceholder,
        systemPlaceholder = c.systemPlaceholder,
        logWarning = { msg, err ->
            val token = msg + ":" + (err?.javaClass?.simpleName ?: "~")
            effects += "WARN:" + encStr211c(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
            warnings += token
        },
        nudgeYouNudged = { t -> "YN:" + t },
        nudgeTheyNudgedYou = { s -> "TY:" + s },
        nudgeTheyNudgedTarget = { s, t -> "TT:" + s + "/" + t },
    )
    for (op in c.ops) {
        curOp = op
        try {
            when (op.kind) {
                0 -> coordinator.applyChatListPreview(
                    chatId = op.chatId,
                    previewText = op.previewText,
                    messageType = MessageType.valueOf(op.messageType),
                    timestamp = op.timestamp,
                    unreadDelta = op.unreadDelta,
                    forceTimestamp = op.forceTimestamp,
                    ownerUserId = op.ownerUserId,
                    sessionGeneration = op.sessionGeneration,
                )
                1 -> {
                    val chat = if (op.chatHintIdx >= 0) chats.getOrNull(op.chatHintIdx) else null
                    val out = coordinator.listNudgePreview(
                        isOwnMessage = op.isOwnMessage,
                        storedContent = op.storedContent,
                        senderId = op.senderId,
                        chatId = op.chatId,
                        chatHint = chat,
                    )
                    effects += "NUDGE:" + encStr211c(out)
                }
                2 -> {
                    val out = runBlocking {
                        coordinator.enrichServerChatPreview(
                            chats[op.serverChatIdx],
                            op.ownerUserId,
                        )
                    }
                    effects += "ENRICH:" + lstChatEnc211c(lstChatFrom211c(out))
                }
                3 -> coordinator.refreshChatListPreviewFromLocal(
                    chatId = op.chatId,
                    ownerUserId = op.ownerUserId,
                    sessionGeneration = op.sessionGeneration,
                )
                else -> effects += "LABEL:" + encStr211c(
                    coordinator.mediaPreviewLabel(MessageType.valueOf(op.messageType)),
                )
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val LST_CONTENTS211c = listOf(
    "hello", "  spaced  ", "", "normal text",
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsRR",
    "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"xx\"}]}",
    "无法解密", "[无法解密的消息]", "file:///x.png",
    "https://example.com/api/attachments/abc", "你拍了拍小明",
    "You nudged Bob", "  ", "[提醒]", "[系统]", "a &amp; b", "&#72;i",
    "hello <meta>{\"aiAssisted\":true}</meta> tail", "www.example.com/ciphertext/x",
)
private val LST_TYPES211c = listOf("TEXT", "MARKDOWN", "IMAGE", "SK_DIST", "NUDGE", "REVOKED", "SYSTEM", "VIDEO")

private fun randomLstChat211c(r: Random, id: String): LstChat211c = LstChat211c(
    id = id,
    participants = listOf(
        LstUser211c("u1", "Ann", if (r.nextInt(3) == 0) "阿宁" else null),
        LstUser211c("u2", "", null),
        LstUser211c("me", "Me", null),
    ),
    lastMessage = LST_CONTENTS211c[r.nextInt(6)],
    lastMessageType = LST_TYPES211c[r.nextInt(LST_TYPES211c.size)],
    lastMessageTime = listOf(0L, 1000L, 2000L)[r.nextInt(3)],
    unreadCount = r.nextInt(0, 5),
    isGroup = r.nextInt(2) == 0,
    chatType = listOf("DIRECT", "GROUP", "SECRET")[r.nextInt(3)],
    groupName = listOf<String?>(null, "G", "")[r.nextInt(3)],
    pinnedAt = listOf(0L, 100L, 200L)[r.nextInt(3)],
    notificationsMuted = r.nextInt(2) == 0,
    archived = r.nextInt(2) == 0,
    markedUnread = r.nextInt(2) == 0,
)

private fun randomLstMsg211c(r: Random, chatId: String, idx: Int): LstMsg211c = LstMsg211c(
    id = "rm$idx",
    chatId = chatId,
    senderId = listOf("u1", "u2", "me", "ghost-long-sender-id")[r.nextInt(4)],
    content = LST_CONTENTS211c[r.nextInt(LST_CONTENTS211c.size)],
    type = LST_TYPES211c[r.nextInt(LST_TYPES211c.size)],
    timestamp = listOf(0L, 500L, 1000L, 3000L)[r.nextInt(4)],
)

private fun randomLstOp211c(r: Random, nChats: Int): LstOp211c {
    val kind = r.nextInt(5)
    return LstOp211c(
        kind = kind,
        chatId = listOf("c1", "c2", "ghost", "")[r.nextInt(4)],
        previewText = LST_CONTENTS211c[r.nextInt(8)],
        messageType = LST_TYPES211c[r.nextInt(LST_TYPES211c.size)],
        timestamp = listOf(0L, 500L, 1500L, 2500L)[r.nextInt(4)],
        unreadDelta = listOf(-2, -1, 0, 1, 3)[r.nextInt(5)],
        forceTimestamp = r.nextInt(2) == 0,
        ownerUserId = listOf("me", "u1", "")[r.nextInt(3)],
        sessionGeneration = listOf(1L, 2L)[r.nextInt(2)],
        isOwnMessage = r.nextInt(2) == 0,
        storedContent = listOf("你拍了拍小明", "You nudged Bob", "hello", "", "你拍了拍", "拍了拍小明")[r.nextInt(6)],
        senderId = listOf("u1", "u2", "me", "ghost-long-sender-id", "")[r.nextInt(5)],
        chatHintIdx = if (nChats > 0 && r.nextInt(3) == 0) r.nextInt(nChats) else -1,
        serverChatIdx = if (nChats > 0) r.nextInt(nChats) else 0,
        recentFault = r.nextInt(3),
        roomFault = r.nextInt(5),
    )
}

private fun randomLstCase211c(r: Random): LstCase211c {
    val n = r.nextInt(0, 4)
    val chats = (0 until n).map { randomLstChat211c(r, "c" + (it + 1)) }
    val recent = chats.map { chat ->
        chat.id to List(r.nextInt(0, 4)) { idx -> randomLstMsg211c(r, chat.id, idx) }
    }
    return LstCase211c(
        owner = listOf("me", "u1")[r.nextInt(2)],
        sessionGen = listOf(1L, 2L)[r.nextInt(2)],
        sessOk = r.nextInt(5) != 0,
        encryptedPlaceholder = listOf("[enc]", "")[r.nextInt(2)],
        revokedPlaceholder = "[revoked]",
        nudgePlaceholder = "[nudge]",
        systemPlaceholder = "[sys]",
        seedChats = chats,
        seedRecent = recent,
        ops = List(r.nextInt(0, 9)) { randomLstOp211c(r, n) },
    )
}

private fun boundaryLstCase211c(r: Random): LstCase211c {
    val chats = listOf(
        LstChat211c("c1", participants = listOf(LstUser211c("u1", "Ann", "阿宁"), LstUser211c("me", "Me")), lastMessage = "old", lastMessageType = "TEXT", lastMessageTime = 1000L, unreadCount = 2, pinnedAt = 100L),
        LstChat211c("c2", participants = listOf(LstUser211c("u2", "")), lastMessage = "[提醒]", lastMessageType = "NUDGE", lastMessageTime = 2000L, unreadCount = 0, isGroup = true, chatType = "GROUP"),
        LstChat211c("c3", lastMessage = "", lastMessageType = "TEXT", lastMessageTime = 500L, unreadCount = 1),
    )
    val recent = listOf(
        "c1" to listOf(
            LstMsg211c("rm0", chatId = "c1", senderId = "u1", content = "hello", type = "TEXT", timestamp = 3000L),
            LstMsg211c("rm1", chatId = "c1", senderId = "u1", content = "old tail", type = "TEXT", timestamp = 1000L),
        ),
        "c2" to listOf(
            LstMsg211c("rm0", chatId = "c2", senderId = "u1", content = "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"xx\"}]}", type = "TEXT", timestamp = 3000L),
            LstMsg211c("rm1", chatId = "c2", senderId = "u1", content = "real tail", type = "TEXT", timestamp = 1000L),
        ),
        "c3" to emptyList(),
    )
    val base = LstCase211c(seedChats = chats, seedRecent = recent)
    return when (r.nextInt(28)) {
        0 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L)))
        1 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 500L)))
        2 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 500L, forceTimestamp = true)))
        3 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, unreadDelta = -5)))
        4 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, unreadDelta = 3)))
        5 -> base.copy(ops = listOf(LstOp211c(0, chatId = "ghost", previewText = "new", messageType = "TEXT", timestamp = 1500L)))
        6 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, roomFault = 1)))
        7 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, roomFault = 2)))
        8 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, roomFault = 3)))
        9 -> base.copy(ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L)), sessOk = false)
        10 -> base.copy(ops = listOf(LstOp211c(1, isOwnMessage = true, storedContent = "你拍了拍小明", senderId = "u1", chatId = "c1")))
        11 -> base.copy(ops = listOf(LstOp211c(1, isOwnMessage = false, storedContent = "你拍了拍小明", senderId = "u1", chatId = "c1")))
        12 -> base.copy(ops = listOf(LstOp211c(1, isOwnMessage = false, storedContent = "你拍了拍小明", senderId = "u1", chatId = "c2")))
        13 -> base.copy(ops = listOf(LstOp211c(1, isOwnMessage = false, storedContent = "You nudged Bob", senderId = "ghost-long-sender-id", chatId = "c1")))
        14 -> base.copy(ops = listOf(LstOp211c(1, isOwnMessage = false, storedContent = "", senderId = "u1", chatId = "c1")))
        15 -> base.copy(ops = listOf(LstOp211c(1, isOwnMessage = true, storedContent = "你拍了拍", senderId = "u1", chatId = "c1")))
        16 -> base.copy(ops = listOf(LstOp211c(2, serverChatIdx = 0, ownerUserId = "me")))
        17 -> base.copy(ops = listOf(LstOp211c(2, serverChatIdx = 1, ownerUserId = "me")))
        18 -> base.copy(ops = listOf(LstOp211c(2, serverChatIdx = 2, ownerUserId = "me")))
        19 -> base.copy(ops = listOf(LstOp211c(2, serverChatIdx = 0, ownerUserId = "me", recentFault = 1)))
        20 -> base.copy(ops = listOf(LstOp211c(2, serverChatIdx = 0, ownerUserId = "me", recentFault = 2)))
        21 -> base.copy(ops = listOf(LstOp211c(3, chatId = "c1", ownerUserId = "me")))
        22 -> base.copy(ops = listOf(LstOp211c(3, chatId = "c2", ownerUserId = "me")))
        23 -> base.copy(ops = listOf(LstOp211c(3, chatId = "c3", ownerUserId = "me")))
        24 -> base.copy(ops = listOf(LstOp211c(3, chatId = "c1", ownerUserId = "me", recentFault = 1)))
        25 -> base.copy(ops = listOf(LstOp211c(3, chatId = "c1", ownerUserId = "me", roomFault = 4)))
        26 -> base.copy(ops = listOf(LstOp211c(4, messageType = "IMAGE")))
        else -> base.copy(ops = listOf(LstOp211c(4, messageType = "TEXT")))
    }
}

// ---------- tests ----------

class ChatListPreviewCoordinatorFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(211301)
        repeat(300) {
            val c = randomLstCase211c(r)
            assertEquals(lstOracle211c(c), lstProd211c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(211302)
        repeat(200) {
            val c = boundaryLstCase211c(r)
            assertEquals(lstOracle211c(c), lstProd211c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val chats = listOf(
            LstChat211c("c1", participants = listOf(LstUser211c("u1", "Ann", "阿宁")), lastMessage = "old", lastMessageType = "TEXT", lastMessageTime = 1000L, unreadCount = 2, pinnedAt = 100L),
            LstChat211c("c2", participants = listOf(LstUser211c("u2", "")), lastMessage = "[提醒]", lastMessageType = "NUDGE", lastMessageTime = 2000L, unreadCount = 0, isGroup = true, chatType = "GROUP"),
        )
        val recent = listOf(
            "c1" to listOf(LstMsg211c("rm0", chatId = "c1", senderId = "u1", content = "hello", type = "TEXT", timestamp = 3000L)),
            "c2" to listOf(LstMsg211c("rm0", chatId = "c2", senderId = "u1", content = "real tail", type = "TEXT", timestamp = 1000L)),
        )
        val cases = listOf(
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 500L, forceTimestamp = true))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, unreadDelta = -5))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(0, chatId = "c1", previewText = "new", messageType = "TEXT", timestamp = 1500L, roomFault = 1))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(1, isOwnMessage = true, storedContent = "你拍了拍小明", senderId = "u1", chatId = "c1"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(1, isOwnMessage = false, storedContent = "你拍了拍小明", senderId = "u1", chatId = "c2"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(1, isOwnMessage = false, storedContent = "You nudged Bob", senderId = "ghost-long-sender-id", chatId = "c1"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(2, serverChatIdx = 0, ownerUserId = "me"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(2, serverChatIdx = 1, ownerUserId = "me"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(2, serverChatIdx = 0, ownerUserId = "me", recentFault = 1))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(3, chatId = "c1", ownerUserId = "me"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(3, chatId = "c2", ownerUserId = "me"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = listOf(LstOp211c(4, messageType = "IMAGE"))),
            LstCase211c(seedChats = chats, seedRecent = recent, ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(lstOracle211c(c), lstProd211c(c))
        }
    }
}
