package com.maodouchat.notification

object NotificationIntentPolicy {
    fun belongsToCurrentAccount(
        notificationOwnerUserId: String?,
        currentUserId: String?,
        sessionPurgeInProgress: Boolean,
    ): Boolean {
        if (sessionPurgeInProgress) return false
        if (notificationOwnerUserId.isNullOrBlank() || currentUserId.isNullOrBlank()) return false
        return notificationOwnerUserId == currentUserId
    }

    /**
     * 点击打开会话的 chatId：空白 / 纯空白字符一律丢弃，避免 PendingIntent extra
     * 缺字段时落到「空会话」或复用上一次 Intent 里的 chatId。
     */
    fun resolveOpenChatId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
