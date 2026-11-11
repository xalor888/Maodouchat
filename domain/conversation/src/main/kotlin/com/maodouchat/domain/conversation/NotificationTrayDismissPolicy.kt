package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AppLinkDestination
import com.maodouchat.core.model.AppLinkRouter
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.core.model.NotificationCenterReadPolicy
import com.maodouchat.core.model.parseLegacyCenterDeeplink

/**
 * 通知中心行要取消的托盘通知。domain 侧只定"取消哪一类、带什么 id"，
 * 真正的取消调用（NotificationService）仍在 app 侧执行。
 */
sealed interface NotificationTrayDismissAction {
    data class CancelMissedCall(val callId: String) : NotificationTrayDismissAction
    data class CancelAiTaskRemindersForChat(val chatId: String) : NotificationTrayDismissAction
    data class CancelAiTaskReminder(val taskId: String) : NotificationTrayDismissAction
    data class CancelMessage(val chatId: String) : NotificationTrayDismissAction
    data class CancelPostInteraction(val postId: String) : NotificationTrayDismissAction
    data object CancelAllFriendRequests : NotificationTrayDismissAction
    data object CancelAllGroupInvites : NotificationTrayDismissAction
}

// 通知中心行 → 托盘取消动作：按行类型/extra/deeplink 路由，从 NotificationCenterViewModel 纯搬移。
object NotificationTrayDismissPolicy {

    /**
     * Merge can rewrite [NotificationCenterItem.id] to the incoming head while Compose
     * still holds the pre-merge id. Resolve by id first, then type + mergeKey.
     */
    fun resolveLiveItem(
        items: List<NotificationCenterItem>,
        id: String,
        hint: NotificationCenterItem? = null,
    ): NotificationCenterItem? {
        items.firstOrNull { it.id == id }?.let { return it }
        val type = hint?.type
        val mergeKey = hint?.mergeKey
        if (!type.isNullOrBlank() && !mergeKey.isNullOrBlank()) {
            items.firstOrNull { it.type == type && it.mergeKey == mergeKey }?.let { return it }
        }
        return null
    }

    fun dismissActionFor(item: NotificationCenterItem): NotificationTrayDismissAction? {
        when {
            item.type == "MISSED_CALL" -> {
                val callId = item.extra["callId"].orEmpty()
                if (callId.isNotBlank()) return NotificationTrayDismissAction.CancelMissedCall(callId)
            }
            item.type == "AI_TASK" -> {
                val taskId = item.extra["taskId"].orEmpty()
                val chatId = item.extra["chatId"].orEmpty()
                when {
                    chatId.isNotBlank() -> return NotificationTrayDismissAction.CancelAiTaskRemindersForChat(chatId)
                    taskId.isNotBlank() -> return NotificationTrayDismissAction.CancelAiTaskReminder(taskId)
                }
            }
            item.type == "MESSAGE" || item.deeplink?.startsWith("maodouchat:chat:") == true -> {
                val chatId = item.extra["chatId"]
                    ?: (AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                        as? AppLinkDestination.ChatDetail)?.chatId
                    ?: item.mergeKey.removePrefix("msg_")
                if (chatId.isNotBlank()) return NotificationTrayDismissAction.CancelMessage(chatId)
            }
            item.type == "POST_INTERACTION" ||
                item.deeplink?.startsWith("maodouchat:post:") == true -> {
                val postId = item.extra["postId"]
                    ?: (AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                        as? AppLinkDestination.PostDetail)?.postId
                    ?: item.mergeKey.removePrefix("post_")
                if (postId.isNotBlank()) return NotificationTrayDismissAction.CancelPostInteraction(postId)
            }
            item.type == "FRIEND_REQUEST" ||
                AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                    is AppLinkDestination.ContactsTab -> {
                return NotificationTrayDismissAction.CancelAllFriendRequests
            }
            item.type == "GROUP_INVITE" ||
                AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                    is AppLinkDestination.GroupInvitesTab -> {
                return NotificationTrayDismissAction.CancelAllGroupInvites
            }
        }
        return null
    }

    /** 1.170：清除某个会话的全部通知（含托盘通知）——先算出受影响的行。 */
    fun affectedItemsForChat(items: List<NotificationCenterItem>, chatId: String): List<NotificationCenterItem> =
        items.filter { item ->
            NotificationCenterReadPolicy.belongsToChat(
                chatId = chatId,
                mergeKey = item.mergeKey,
                deeplink = item.deeplink,
                extraChatId = item.extra["chatId"]
            )
        }
}
