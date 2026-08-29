package com.maodouchat.notification

/**
 * Pure matching for which notification-center rows belong to a chat.
 *
 * Open-chat mark-read must only touch message rows (not AI-task / other chat-linked types).
 * Leave/delete cleanup removes every center row that would deeplink into that chat.
 */
object NotificationCenterReadPolicy {

    /**
     * Rows that represent incoming-message notifications for [chatId].
     * Used when the user opens a conversation: mark message rows read only.
     */
    fun isChatMessageItem(
        chatId: String,
        mergeKey: String,
        deeplink: String?,
        extraChatId: String?
    ): Boolean {
        if (chatId.isBlank()) return false
        if (mergeKey == "msg_$chatId") return true
        if (deeplink == "maodouchat:chat:$chatId") return true
        // Incomplete MESSAGE rows may only carry chatId in extra; require msg_ prefix
        // so AI_TASK (`ai_tasks_{chatId}`) is not treated as a message row.
        if (extraChatId == chatId && mergeKey.startsWith("msg_")) return true
        return false
    }

    /**
     * Any center row that is scoped to [chatId] (messages, AI tasks for that chat, etc.).
     * Used on leave/delete so deeplinks cannot open a missing conversation.
     */
    fun belongsToChat(
        chatId: String,
        mergeKey: String,
        deeplink: String?,
        extraChatId: String?
    ): Boolean {
        if (chatId.isBlank()) return false
        if (isChatMessageItem(chatId, mergeKey, deeplink, extraChatId)) return true
        if (mergeKey == "ai_tasks_$chatId") return true
        if (deeplink == "maodouchat:ai_tasks:$chatId") return true
        if (extraChatId == chatId) return true
        return false
    }

    /**
     * Whether a center row still surfaces [messageId] as its latest payload
     * (delete/revoke must drop tray/center so previews cannot outlive the message).
     */
    fun referencesMessage(
        messageId: String,
        itemId: String,
        extraMessageId: String?
    ): Boolean {
        if (messageId.isBlank()) return false
        if (extraMessageId == messageId) return true
        // showMessage writes id = "msg_{chatId}_{messageId}".
        // 8.46 修复：chatId（c_uuid）与 messageId（m_uuid）本身都可能含下划线——
        // 此前按第一个 '_' 分隔会取到 chatId 后半段，chatId 含下划线时永远不匹配，
        // delete/revoke 后通知中心/托盘残留已删消息预览。messageId 统一以 "m_" 前缀
        // 生成，用其作为锚点定位消息段（找不到则保守不匹配，extraMessageId 通道兜底）。
        if (itemId.startsWith("msg_")) {
            val marker = "_m_"
            val idx = itemId.lastIndexOf(marker)
            if (idx > 0) {
                val embedded = itemId.substring(idx + 1)
                if (embedded == messageId) return true
            }
        }
        return false
    }
}
