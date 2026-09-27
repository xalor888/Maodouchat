package com.maodouchat.navigation

import com.maodouchat.data.repository.NotificationCenterItem

/**
 * 通知中心行点击的「决策 + 副作用」控制器（U02 延伸：把 `ui/navigation` 里的
 * app 单例通道收进非 ui 层）。
 *
 * 为什么收：`SearchCenterDestinations.kt` 此前把四类行的点击处理全部写在一个
 * Composable 的 lambda 里，直接触碰 `MaodouchatApp.emitOpenMissedCalls()`、
 * `notificationCenter.markPostInteractionsRead()` 与四个通知服务——ui 直连持久层
 * 棘轮记了 5 处命中，而且这段「点哪行 → 清哪块托盘 → 去哪」的映射零测试覆盖。
 *
 * 现在：本类只做**决策**（返回 [Outcome]）与**注入进来的副作用**，不引用
 * Context/TokenManager/NavController；Android 侧接线在 [NotificationCenterEffects]，
 * NavController 留在 UI。行为逐字对齐原实现（含登出守卫、托盘清理、事件发射、
 * legacy deeplink 解析与 `markPostInteractionsRead` 的 runCatching 语义）。
 */
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

    sealed interface Outcome {
        /** 导航到该路由（UI 侧加 `launchSingleTop`）。 */
        data class Navigate(val route: String) : Outcome

        /** 清栈回上一页（未接箱/联系人 Tab）。 */
        data object PopBackStack : Outcome

        /** 已登出/会话失效：回登录页并清栈（8.49：与 401 路径对齐）。 */
        data object GoLogin : Outcome

        /** 无动作。 */
        data object None : Outcome
    }

    fun onOpenItem(item: NotificationCenterItem): Outcome {
        if (!hasSessionToken()) {
            return Outcome.GoLogin
        }
        // MESSAGE 无 deeplink：走 extra.chatId（与历史行兼容）
        if (item.type == "MESSAGE" && item.deeplink == null) {
            val chatId = item.extra["chatId"].orEmpty()
            if (chatId.isBlank()) return Outcome.None
            clearMessageTray(chatId)
            return Outcome.Navigate(Routes.chatDetail(chatId))
        }
        // MISSED_CALL 类型无 deeplink 时仍打开未接箱，并按 callId 清托盘
        if (item.type == "MISSED_CALL" && item.deeplink.isNullOrBlank()) {
            item.extra["callId"].orEmpty().takeIf { it.isNotBlank() }?.let(clearMissedCallTray)
            emitOpenMissedCalls()
            return Outcome.PopBackStack
        }
        if (item.type == "FRIEND_REQUEST" && item.deeplink.isNullOrBlank()) {
            emitOpenContacts()
            return Outcome.PopBackStack
        }
        val dest = parseLegacyDeeplink(item.deeplink.orEmpty()) ?: return Outcome.None
        return when (dest) {
            is AppLinkDestination.MissedCallsTab -> {
                item.extra["callId"].orEmpty().takeIf { it.isNotBlank() }?.let(clearMissedCallTray)
                emitOpenMissedCalls()
                Outcome.PopBackStack
            }
            is AppLinkDestination.ContactsTab -> {
                emitOpenContacts()
                Outcome.PopBackStack
            }
            is AppLinkDestination.ChatDetail -> {
                clearMessageTray(dest.chatId)
                Outcome.Navigate(dest.toRoute())
            }
            is AppLinkDestination.AiTasksChat -> {
                clearAiTaskReminders(dest.chatId)
                Outcome.Navigate(dest.toRoute())
            }
            is AppLinkDestination.PostDetail -> {
                clearPostInteractionTray(dest.postId)
                markPostInteractionsRead(dest.postId)
                Outcome.Navigate(dest.toRoute())
            }
            else -> Outcome.None
        }
    }
}
