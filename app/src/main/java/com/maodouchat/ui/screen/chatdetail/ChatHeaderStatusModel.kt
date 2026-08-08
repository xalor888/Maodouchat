package com.maodouchat.ui.screen.chatdetail

internal sealed interface ChatHeaderStatus {
    data class Typing(val userId: String) : ChatHeaderStatus
    data object Online : ChatHeaderStatus
    data object Offline : ChatHeaderStatus
    data class LastSeen(val timestamp: Long) : ChatHeaderStatus
    data class Custom(val text: String) : ChatHeaderStatus
    data object None : ChatHeaderStatus
}

/**
 * Resolves transient presence before durable contact presence.
 *
 * A typing event is more useful than the generic online marker and must remain visible while
 * its three-second receive timeout is active. Blank/stale typing IDs are ignored defensively.
 *
 * For 1:1 chats, offline shows last-seen when available; groups omit it.
 * Groups omit offline so the subtitle does not imply whole-group presence.
 */
internal fun resolveChatHeaderStatus(
    typingUserId: String?,
    isOnline: Boolean,
    customStatus: String,
    isGroup: Boolean = false,
    lastSeen: Long = 0
): ChatHeaderStatus {
    val activeTypingUserId = typingUserId?.trim().orEmpty()
    return when {
        activeTypingUserId.isNotEmpty() -> ChatHeaderStatus.Typing(activeTypingUserId)
        isOnline -> ChatHeaderStatus.Online
        customStatus.isNotBlank() -> ChatHeaderStatus.Custom(customStatus)
        !isGroup && lastSeen > 0 -> ChatHeaderStatus.LastSeen(lastSeen)
        !isGroup -> ChatHeaderStatus.Offline
        else -> ChatHeaderStatus.None
    }
}
