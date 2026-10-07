package com.maodouchat.ui.screen.chatlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.navigation.AppLinkDestination
import com.maodouchat.navigation.AppLinkRouter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class NotificationCenterViewModel(application: Application) : AndroidViewModel(application) {
    // U02 延伸：仓库入口收进非 ui 的 NotificationCenterAccess。
    private val repo: NotificationCenterRepository = com.maodouchat.notification.NotificationCenterAccess.repository

    val items = repo.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repo.snapshot()
    )

    fun markAllRead() {
        // Mark-all should also drop tray shadows so badge UX matches the center.
        val snapshot = repo.snapshot()
        repo.markAllRead()
        dismissTrayFor(snapshot)
    }

    /**
     * Merge can rewrite [NotificationCenterItem.id] to the incoming head while Compose
     * still holds the pre-merge id. Resolve by id first, then type + mergeKey.
     */
    private fun resolveLiveItem(id: String, hint: NotificationCenterItem? = null): NotificationCenterItem? {
        val snapshot = repo.snapshot()
        snapshot.firstOrNull { it.id == id }?.let { return it }
        val type = hint?.type
        val mergeKey = hint?.mergeKey
        if (!type.isNullOrBlank() && !mergeKey.isNullOrBlank()) {
            snapshot.firstOrNull { it.type == type && it.mergeKey == mergeKey }?.let { return it }
        }
        return null
    }

    fun markRead(id: String, hint: NotificationCenterItem? = null) {
        val item = resolveLiveItem(id, hint)
        val targetId = item?.id ?: id
        repo.markRead(targetId)
        if (item != null) dismissTrayFor(listOf(item))
    }

    fun markUnread(id: String, hint: NotificationCenterItem? = null) {
        val item = resolveLiveItem(id, hint)
        repo.markUnread(item?.id ?: id)
    }

    fun remove(id: String, hint: NotificationCenterItem? = null) {
        val item = resolveLiveItem(id, hint)
        val targetId = item?.id ?: id
        repo.remove(targetId)
        if (item != null) dismissTrayFor(listOf(item))
    }

    /** 1.170：清除某个会话的全部通知（含托盘通知）。 */
    fun removeChat(chatId: String) {
        if (chatId.isBlank()) return
        val affected = repo.snapshot().filter { item ->
            com.maodouchat.notification.NotificationCenterReadPolicy.belongsToChat(
                chatId = chatId,
                mergeKey = item.mergeKey,
                deeplink = item.deeplink,
                extraChatId = item.extra["chatId"]
            )
        }
        repo.removeChatItems(chatId)
        dismissTrayFor(affected)
    }

    fun clearAll() {
        val snapshot = repo.snapshot()
        repo.clearAll()
        dismissTrayFor(snapshot)
    }

    private fun dismissTrayFor(items: List<NotificationCenterItem>) {
        val ctx = getApplication<Application>()
        for (item in items) {
            try {
                when {
                    item.type == "MISSED_CALL" -> {
                        val callId = item.extra["callId"].orEmpty()
                        if (callId.isNotBlank()) {
                            com.maodouchat.notification.CallNotificationService.cancelMissedCall(ctx, callId)
                        }
                    }
                    item.type == "AI_TASK" -> {
                        val taskId = item.extra["taskId"].orEmpty()
                        val chatId = item.extra["chatId"].orEmpty()
                        when {
                            // 8.44：优先按 chat 整组清理（含 group-summary）——cancelAiTaskReminder
                            // 单任务不清理 summary，会造成托盘残留
                            chatId.isNotBlank() ->
                                com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(ctx, chatId)
                            taskId.isNotBlank() ->
                                com.maodouchat.notification.ReminderNotificationService.cancelAiTaskReminder(ctx, taskId)
                        }
                    }
                    item.type == "MESSAGE" || item.deeplink?.startsWith("maodouchat:chat:") == true -> {
                        val chatId = item.extra["chatId"]
                            ?: (AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                                as? AppLinkDestination.ChatDetail)?.chatId
                            ?: item.mergeKey.removePrefix("msg_")
                        if (chatId.isNotBlank()) {
                            com.maodouchat.notification.MessageNotificationService.cancelMessage(ctx, chatId)
                        }
                    }
                    item.type == "POST_INTERACTION" ||
                        item.deeplink?.startsWith("maodouchat:post:") == true -> {
                        val postId = item.extra["postId"]
                            ?: (AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                                as? AppLinkDestination.PostDetail)?.postId
                            ?: item.mergeKey.removePrefix("post_")
                        if (postId.isNotBlank()) {
                            com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(ctx, postId)
                        }
                    }
                    item.type == "FRIEND_REQUEST" ||
                        AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                            is AppLinkDestination.ContactsTab -> {
                        com.maodouchat.notification.SocialNotificationService.cancelAllFriendRequests(ctx)
                    }
                    item.type == "GROUP_INVITE" ||
                        AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                            is AppLinkDestination.GroupInvitesTab -> {
                        com.maodouchat.notification.SocialNotificationService.cancelAllGroupInvites(ctx)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }
}
