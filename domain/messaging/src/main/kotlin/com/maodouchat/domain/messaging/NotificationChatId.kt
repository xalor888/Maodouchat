package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AppLinkDestination
import com.maodouchat.core.model.AppLinkRouter
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.core.model.parseLegacyCenterDeeplink

// 通知中心会话 id：extra.chatId → 深链 → mergeKey（msg_/ai_tasks_）三路回落。
fun resolvedNotificationChatId(item: NotificationCenterItem): String? {
    item.extra["chatId"]?.takeIf { it.isNotBlank() }?.let { return it }
    when (val dest = AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())) {
        is AppLinkDestination.ChatDetail -> return dest.chatId
        is AppLinkDestination.AiTasksChat -> return dest.chatId
        else -> Unit
    }
    if (item.mergeKey.startsWith("msg_") && item.mergeKey.length > 4) {
        return item.mergeKey.removePrefix("msg_").takeIf { it.isNotBlank() }
    }
    if (item.mergeKey.startsWith("ai_tasks_") && item.mergeKey.length > 9) {
        return item.mergeKey.removePrefix("ai_tasks_").takeIf { it.isNotBlank() }
    }
    return null
}
