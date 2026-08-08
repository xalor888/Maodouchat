package com.maodouchat.ui.screen.chatlist

/**
 * Merge server unread with local state so getChats cannot resurrect a badge the user
 * already cleared (open chat / emitChatRead) when mark-read is still in flight.
 */
object ChatListUnreadPolicy {

    /**
     * @param serverUnread unread from getChats
     * @param localUnread Room / previous UI unread (null if chat not cached)
     * @param isActiveChat user is currently viewing this conversation
     * @param localMarkedUnread user manually marked unread (must not be wiped by stale zero)
     * @param serverLastMessageTime server tail time
     * @param localLastMessageTime local tail time (0 if unknown)
     */
    fun mergeUnreadCount(
        serverUnread: Int,
        localUnread: Int?,
        isActiveChat: Boolean,
        localMarkedUnread: Boolean = false,
        serverLastMessageTime: Long = 0L,
        localLastMessageTime: Long = 0L
    ): Int {
        if (isActiveChat) return 0
        val server = serverUnread.coerceAtLeast(0)
        val local = localUnread?.coerceAtLeast(0)
        if (local == null) return server
        // Local already zero after open-chat/emitChatRead and server tail is not newer →
        // keep zero so in-flight markAllAsRead cannot resurrect the badge.
        // Require localLastMessageTime > 0 so empty/stub local rows still accept server unread.
        if (local == 0 && !localMarkedUnread &&
            localLastMessageTime > 0L &&
            serverLastMessageTime <= localLastMessageTime
        ) {
            return 0
        }
        // Prefer the higher count when server is clearly ahead (new messages while offline).
        return maxOf(server, local)
    }

    fun mergeMarkedUnread(
        serverMarked: Boolean,
        localMarked: Boolean?,
        isActiveChat: Boolean
    ): Boolean {
        if (isActiveChat) return false
        if (localMarked == null) return serverMarked
        // Optimistic toggle may not have reached server yet — keep local when true.
        if (localMarked && !serverMarked) return true
        return serverMarked
    }
}
