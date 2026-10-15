package com.maodouchat.navigation

import com.maodouchat.data.repository.NotificationCenterItem

class NotificationCenterOpenController(
    /** 原判据：`TokenManager.getInstance(context).getToken().isNullOrBlank()`。 */
    private val hasSessionToken: () -> Boolean,
    private val clearMessageTray: (chatId: String) -> Unit,
    private val clearMissedCallTray: (callId: String) -> Unit,
    private val clearAiTaskReminders: (chatId: String) -> Unit,
    private val clearPostInteractionTray: (postId: String) -> Unit,
    private val markPostInteractionsRead: (postId: String) -> Unit,
    private val emitOpenMissedCalls: () -> Unit,
    private val emitOpenContacts: () -> Unit,
    private val parseLegacyDeeplink: (String) -> AppLinkDestination? = AppLinkRouter::parseLegacyCenterDeeplink,
) {

    fun onOpenItem(item: NotificationCenterItem): NotificationCenterOpenOutcome {
        if (!hasSessionToken()) {
            return NotificationCenterOpenOutcome.GoLogin
        }
        // MESSAGE 无 deeplink：走 extra.chatId（与历史行兼容）
        if (item.type == "MESSAGE" && item.deeplink == null) {
            val chatId = item.extra["chatId"].orEmpty()
            if (chatId.isBlank()) return NotificationCenterOpenOutcome.None
            clearMessageTray(chatId)
            return NotificationCenterOpenOutcome.Navigate(Routes.chatDetail(chatId))
        }
        // MISSED_CALL 类型无 deeplink 时仍打开未接箱，并按 callId 清托盘
        if (item.type == "MISSED_CALL" && item.deeplink.isNullOrBlank()) {
            item.extra["callId"].orEmpty().takeIf { it.isNotBlank() }?.let(clearMissedCallTray)
            emitOpenMissedCalls()
            return NotificationCenterOpenOutcome.PopBackStack
        }
        if (item.type == "FRIEND_REQUEST" && item.deeplink.isNullOrBlank()) {
            emitOpenContacts()
            return NotificationCenterOpenOutcome.PopBackStack
        }
        val dest = parseLegacyDeeplink(item.deeplink.orEmpty()) ?: return NotificationCenterOpenOutcome.None
        return when (dest) {
            is AppLinkDestination.MissedCallsTab -> {
                item.extra["callId"].orEmpty().takeIf { it.isNotBlank() }?.let(clearMissedCallTray)
                emitOpenMissedCalls()
                NotificationCenterOpenOutcome.PopBackStack
            }
            is AppLinkDestination.ContactsTab -> {
                emitOpenContacts()
                NotificationCenterOpenOutcome.PopBackStack
            }
            is AppLinkDestination.ChatDetail -> {
                clearMessageTray(dest.chatId)
                NotificationCenterOpenOutcome.Navigate(dest.toRoute())
            }
            is AppLinkDestination.AiTasksChat -> {
                clearAiTaskReminders(dest.chatId)
                NotificationCenterOpenOutcome.Navigate(dest.toRoute())
            }
            is AppLinkDestination.PostDetail -> {
                clearPostInteractionTray(dest.postId)
                markPostInteractionsRead(dest.postId)
                NotificationCenterOpenOutcome.Navigate(dest.toRoute())
            }
            else -> NotificationCenterOpenOutcome.None
        }
    }
}
