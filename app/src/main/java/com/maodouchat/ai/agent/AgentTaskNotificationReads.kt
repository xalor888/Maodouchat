package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentNotificationFilterPolicy
import com.maodouchat.domain.messaging.AgentReadPreviewPolicy

// Agent 工具执行簇：通知中心只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskNotificationReads {
    internal suspend fun listNotifications(app: MaodouchatApp, limit: Int): String {
        val blocked = AgentMessagingReadGates.blockedChatIds(app)
        val secretPeers = AgentMessagingReadGates.secretPeerIds(app)
        val items = app.notificationCenter.items.value
            .filter { item ->
                AgentNotificationFilterPolicy.isVisible(
                    chatId = item.extra["chatId"].orEmpty(),
                    callerId = item.extra["callerId"].orEmpty(),
                    mergeKey = item.mergeKey,
                    deeplink = item.deeplink,
                    blockedChatIds = blocked,
                    secretPeerIds = secretPeers,
                )
            }
            .take(AgentNotificationFilterPolicy.notificationsLimit(limit))
        if (items.isEmpty()) return "No notifications."
        return items.joinToString("\n") { item ->
            "${item.id}\t${item.type}\t${item.title}\t${AgentReadPreviewPolicy.notificationPreview(item.preview.orEmpty())}\tread=${item.read}"
        }
    }
}
