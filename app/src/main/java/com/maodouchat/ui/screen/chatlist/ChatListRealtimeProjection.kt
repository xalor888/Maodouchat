package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.repository.NotificationCenterItem

/**
 * 会话列表实时投影纯函数（ChatList 瘦身第 3 切片）。
 *
 * 自 `ChatListViewModel.observeRealtime` 抽出的三处纯投影：已读归零、在线状态
 * 叠加、管理广播映射。collector 编排已收敛至 [ChatListRealtimeCoordinator]。
 */
fun zeroChatUnread(chats: List<Chat>, chatId: String): List<Chat> {
    if (chatId.isBlank()) return chats
    return zeroChatsUnread(chats, setOf(chatId))
}

/** 批量归零（`batchMarkReadSelected` 与 `markAllUnreadChatsRead` 的公共投影）。 */
fun zeroChatsUnread(chats: List<Chat>, chatIds: Set<String>): List<Chat> {
    if (chatIds.isEmpty()) return chats
    return chats.map { chat ->
        if (chat.id in chatIds) chat.copy(unreadCount = 0, markedUnread = false) else chat
    }
}

fun applyPresenceProjection(
    chats: List<Chat>,
    userId: String,
    eventIsOnline: Boolean,
    eventLastSeen: Long,
    onlineRevoked: Boolean,
    statusRevoked: Boolean,
): List<Chat> = chats.map { chat ->
    chat.copy(
        participants = chat.participants.map { p ->
            if (p.id != userId) return@map p
            val visibility = com.maodouchat.network.resolveUserVisibility(
                currentIsOnline = p.isOnline,
                currentStatus = p.status,
                currentLastSeen = p.lastSeen,
                eventIsOnline = eventIsOnline,
                eventLastSeen = eventLastSeen,
                onlineRevoked = onlineRevoked,
                statusRevoked = statusRevoked,
            )
            p.copy(
                isOnline = visibility.isOnline,
                status = visibility.status,
                lastSeen = visibility.lastSeen,
            )
        }
    )
}

data class AdminBroadcastProjection(
    val item: NotificationCenterItem,
    val bannerText: String,
)

/**
 * 管理广播 → 通知中心条目 + 横幅。空正文返回 null（调用方直接忽略）。
 * `title`/`senderLabel` 由调用方完成字符串资源解析后传入。
 */
fun buildAdminBroadcastProjection(
    title: String,
    body: String,
    timestamp: Long,
    senderLabel: String,
): AdminBroadcastProjection? {
    val trimmed = body.trim()
    if (trimmed.isBlank()) return null
    return AdminBroadcastProjection(
        item = NotificationCenterItem(
            id = "admin_bc_${timestamp}_${trimmed.hashCode()}",
            type = "SECURITY",
            mergeKey = "admin_broadcast_${timestamp}",
            title = title,
            subtitle = senderLabel,
            preview = trimmed.take(200),
            deeplink = null,
            extra = mapOf("kind" to "admin_broadcast"),
        ),
        bannerText = "$title: $trimmed".take(240),
    )
}
