package com.maodouchat.ui.screen.chatlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.domain.conversation.NotificationTrayDismissAction
import com.maodouchat.domain.conversation.NotificationTrayDismissPolicy
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

    private fun resolveLiveItem(id: String, hint: NotificationCenterItem? = null): NotificationCenterItem? =
        NotificationTrayDismissPolicy.resolveLiveItem(repo.snapshot(), id, hint)

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
        val affected = NotificationTrayDismissPolicy.affectedItemsForChat(repo.snapshot(), chatId)
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
                val action = NotificationTrayDismissPolicy.dismissActionFor(item) ?: continue
                when (action) {
                    is NotificationTrayDismissAction.CancelMissedCall ->
                        com.maodouchat.notification.CallNotificationService.cancelMissedCall(ctx, action.callId)
                    is NotificationTrayDismissAction.CancelAiTaskRemindersForChat ->
                        // 8.44：优先按 chat 整组清理（含 group-summary）——cancelAiTaskReminder
                        // 单任务不清理 summary，会造成托盘残留
                        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(ctx, action.chatId)
                    is NotificationTrayDismissAction.CancelAiTaskReminder ->
                        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskReminder(ctx, action.taskId)
                    is NotificationTrayDismissAction.CancelMessage ->
                        com.maodouchat.notification.MessageNotificationService.cancelMessage(ctx, action.chatId)
                    is NotificationTrayDismissAction.CancelPostInteraction ->
                        com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(ctx, action.postId)
                    NotificationTrayDismissAction.CancelAllFriendRequests ->
                        com.maodouchat.notification.SocialNotificationService.cancelAllFriendRequests(ctx)
                    NotificationTrayDismissAction.CancelAllGroupInvites ->
                        com.maodouchat.notification.SocialNotificationService.cancelAllGroupInvites(ctx)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }
}
