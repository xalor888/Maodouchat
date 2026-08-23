package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus

/**
 * Telegram-style list ticks: only when the list tail is our own outgoing message.
 * No Room schema — inferred from the latest local message row + current user id.
 */
object ChatListReceiptPolicy {

    data class Receipt(
        val fromMe: Boolean,
        val status: MessageStatus,
    )

    fun fromLatest(latest: Message?, currentUserId: String): Receipt? {
        if (latest == null || currentUserId.isBlank()) return null
        if (latest.senderId != currentUserId) return null
        if (latest.type.isHidden) return null
        return Receipt(fromMe = true, status = latest.status)
    }

    /** Marked-unread with zero count still needs a visible pill (Telegram grey/green dot). */
    fun unreadBadgeText(unreadCount: Int, markedUnread: Boolean): String = when {
        unreadCount > 99 -> "99+"
        unreadCount > 0 -> unreadCount.toString()
        markedUnread -> ""
        else -> ""
    }

    fun showUnreadBadge(unreadCount: Int, markedUnread: Boolean): Boolean =
        unreadCount > 0 || markedUnread
}
