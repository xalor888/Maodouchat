package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.R
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.session.OwnerSessionSnapshot
import com.maodouchat.domain.conversation.ChatListPreviewCoordinator
import kotlinx.coroutines.CoroutineScope

/** 媒体预览文案种类到 R.string 的映射。 */
internal fun chatListPreviewMediaResId(type: MessageType): Int = when (type) {
    MessageType.IMAGE -> R.string.message_preview_image
    MessageType.GIF -> R.string.message_preview_gif
    MessageType.STICKER -> R.string.message_preview_sticker
    MessageType.LOCATION -> R.string.message_preview_location
    MessageType.VOICE -> R.string.message_preview_voice
    MessageType.VIDEO -> R.string.message_preview_video
    MessageType.FILE -> R.string.message_preview_file
    MessageType.NUDGE -> R.string.message_preview_nudge
    MessageType.SYSTEM -> R.string.message_preview_system
    else -> R.string.message_preview_encrypted
}

/** ChatListViewModel 的会话列表预览接线：列表状态、文案与日志由 app 侧提供。 */
internal fun createChatListPreviewCoordinator(
    scope: CoroutineScope,
    getChats: () -> List<Chat>,
    updateChats: ((List<Chat>) -> List<Chat>) -> Unit,
    currentUserId: () -> String,
    currentSessionGeneration: () -> Long,
    isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean,
    withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean,
    getCachedChat: suspend (chatId: String) -> Chat?,
    cacheChats: suspend (List<Chat>) -> Unit,
    getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    text: (Int) -> String,
    nudgeYouNudged: (target: String) -> String,
    nudgeTheyNudgedYou: (sender: String) -> String,
    nudgeTheyNudgedTarget: (sender: String, target: String) -> String,
): ChatListPreviewCoordinator = ChatListPreviewCoordinator(
    scope = scope,
    getChats = getChats,
    updateChats = updateChats,
    currentUserId = currentUserId,
    currentSessionGeneration = currentSessionGeneration,
    isOwnerSessionCurrent = isOwnerSessionCurrent,
    withOwnerRoomWrite = withOwnerRoomWrite,
    getCachedChat = getCachedChat,
    cacheChats = cacheChats,
    getRecentMessages = getRecentMessages,
    mediaPreviewText = { type -> text(chatListPreviewMediaResId(type)) },
    encryptedPlaceholder = text(R.string.message_preview_encrypted),
    revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
    nudgePlaceholder = text(R.string.message_preview_nudge),
    systemPlaceholder = text(R.string.message_preview_system),
    logWarning = { message, error -> Log.w("ChatListPreview", message, error) },
    nudgeYouNudged = nudgeYouNudged,
    nudgeTheyNudgedYou = nudgeTheyNudgedYou,
    nudgeTheyNudgedTarget = nudgeTheyNudgedTarget,
)
