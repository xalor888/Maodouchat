package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp

// Agent 工具执行簇：通知中心只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskNotificationReads {
    internal suspend fun listNotifications(app: MaodouchatApp, limit: Int): String {
        val blocked = AgentMessagingReadGates.blockedChatIds(app)
        val secretPeers = AgentMessagingReadGates.secretPeerIds(app)
        val items = app.notificationCenter.items.value
            .filter { item ->
                val chatId = item.extra["chatId"].orEmpty()
                val callerId = item.extra["callerId"].orEmpty()
                (chatId.isBlank() || chatId !in blocked) &&
                    (callerId.isBlank() || callerId !in secretPeers) &&
                    blocked.none { id -> item.mergeKey.contains(id) || item.deeplink.orEmpty().contains(id) }
            }
            .take(limit.coerceIn(1, 40))
        if (items.isEmpty()) return "No notifications."
        return items.joinToString("\n") { item ->
            "${item.id}\t${item.type}\t${item.title}\t${item.preview.orEmpty().take(80)}\tread=${item.read}"
        }
    }
}
