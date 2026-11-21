package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentNotificationFilterPolicy
import com.maodouchat.domain.messaging.AgentNotificationRowPolicy
import com.maodouchat.domain.messaging.AgentReadEmptyPolicy
import com.maodouchat.domain.messaging.AgentReadPreviewPolicy

// Agent 工具执行簇：通知中心只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskNotificationReads {
    internal suspend fun listNotifications(ports: AgentToolPorts, limit: Int): String {
        val blocked = AgentMessagingReadGates.blockedChatIds(ports)
        val secretPeers = AgentMessagingReadGates.secretPeerIds(ports)
        val items = ports.notificationCenter.items.value
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
        if (items.isEmpty()) return AgentReadEmptyPolicy.noNotifications()
        return items.joinToString("\n") { item ->
            AgentNotificationRowPolicy.notificationRow(
                item.id,
                item.type,
                item.title,
                AgentReadPreviewPolicy.notificationPreview(item.preview.orEmpty()),
                item.read,
            )
        }
    }
}
