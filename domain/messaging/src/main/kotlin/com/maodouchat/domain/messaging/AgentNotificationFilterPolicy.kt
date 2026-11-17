package com.maodouchat.domain.messaging

// Agent 通知中心只读查询的可见性判定与条数边界，自 app 侧 ai/agent 任务读簇收敛；
// 通知源仍在 app 侧，这里只定过滤谓词。
object AgentNotificationFilterPolicy {

    fun isVisible(
        chatId: String,
        callerId: String,
        mergeKey: String,
        deeplink: String?,
        blockedChatIds: Set<String>,
        secretPeerIds: Set<String>,
    ): Boolean =
        (chatId.isBlank() || chatId !in blockedChatIds) &&
            (callerId.isBlank() || callerId !in secretPeerIds) &&
            blockedChatIds.none { id -> mergeKey.contains(id) || deeplink.orEmpty().contains(id) }

    fun notificationsLimit(limit: Int): Int = limit.coerceIn(1, 40)
}
