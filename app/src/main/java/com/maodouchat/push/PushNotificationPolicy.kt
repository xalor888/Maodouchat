package com.maodouchat.push

/**
 * Pure gates for whether a data-only FCM payload should surface a local notification.
 * Server already filters muted chats / SK_DIST when up to date; client still enforces so
 * stale servers and racey mute toggles cannot wake the tray.
 */
object PushNotificationPolicy {

    /**
     * Drop pushes addressed to another account when the device still holds a prior FCM
     * registration briefly after logout/account switch (unregister race).
     * Missing [payloadRecipientId] is rejected: after account switching the same device token
     * may briefly exist under two accounts, so an unstamped legacy payload has no safe owner.
     */
    fun isAddressedToCurrentUser(payloadRecipientId: String?, currentUserId: String?): Boolean {
        if (payloadRecipientId.isNullOrBlank()) return false
        if (currentUserId.isNullOrBlank()) return false
        return payloadRecipientId == currentUserId
    }

    fun shouldShowNewMessage(
        messageTypeWire: String?,
        chatId: String?,
        activeChatId: String?,
        chatNotificationsMuted: Boolean?
    ): Boolean {
        if (chatId.isNullOrBlank()) return false
        if (!activeChatId.isNullOrBlank() && activeChatId == chatId) return false
        if (messageTypeWire.equals("SK_DIST", ignoreCase = true)) return false
        // Unknown mute state (chat not in Room yet) → allow; only suppress when known muted.
        if (chatNotificationsMuted == true) return false
        return true
    }

    /** FCM / 托盘 extras 用的会话 id：空白视为无效，避免点进错误会话。 */
    fun resolveChatId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
