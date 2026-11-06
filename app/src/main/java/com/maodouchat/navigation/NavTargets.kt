package com.maodouchat.navigation

import com.maodouchat.core.model.NotificationTarget

/** P08：系统入口目标 → 类型化导航目标（路由字符串经 `toRoute()` 统一生成）。 */
fun NotificationTarget.toDestination(): AppLinkDestination = when (this) {
    is NotificationTarget.Chat -> AppLinkDestination.ChatDetail(id, messageId)
    is NotificationTarget.AiTasks -> AppLinkDestination.AiTasksChat(chatId)
    is NotificationTarget.Post -> AppLinkDestination.PostDetail(id)
    is NotificationTarget.PublicProfile -> AppLinkDestination.PublicProfile(username)
    is NotificationTarget.GroupInvite -> AppLinkDestination.GroupInvite(code)
}
