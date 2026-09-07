package com.maodouchat.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.maodouchat.ui.screen.chatlist.GlobalSearchScreen
import com.maodouchat.ui.screen.chatlist.NotificationCenterScreen

/**
 * 搜索/通知中心目的地（P08：自 `NavGraph.kt` 迁出的第五个 feature 簇）。
 * 只依赖 `navController`；原块闭包 `context` 改为块内 `LocalContext.current`
 *（同一 Activity，等价）；其余逐字搬运，行为不变。
 */
fun NavGraphBuilder.searchCenterDestinations(navController: NavHostController) {
    composable(Routes.GLOBAL_SEARCH) {
        GlobalSearchScreen(
            onBack = { navController.popBackStack() },
            onOpenResult = { chatId, messageId ->
                // launchSingleTop keeps highlight path stable when reopening same chat from search.
                navController.navigate(Routes.chatDetail(chatId, messageId)) {
                    launchSingleTop = true
                }
            }
        )
    }

    composable(Routes.NOTIFICATION_CENTER) {
        val context = androidx.compose.ui.platform.LocalContext.current
        NotificationCenterScreen(
            onBack = { navController.popBackStack() },
            onOpenItem = onOpenItem@{ item ->
                // 已登出/会话失效时点击通知：先回登录页，避免进入无有效会话的空会话页（正确性 + 防异常）
                if (com.maodouchat.network.TokenManager.getInstance(context).getToken().isNullOrBlank()) {
                    // 8.49：与 401 路径对齐清栈——否则 LOGIN 压在通知中心之上，
                    // 返回键回到死会话页面，重复点击堆叠多个 LOGIN entry
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                    return@onOpenItem
                }
                // MESSAGE 无 deeplink：走 extra.chatId（与历史行兼容）
                if (item.type == "MESSAGE" && item.deeplink == null) {
                    val chatId = item.extra["chatId"].orEmpty()
                    if (chatId.isNotBlank()) {
                        com.maodouchat.notification.MessageNotificationService.cancelMessage(
                            context.applicationContext,
                            chatId,
                        )
                        navController.navigate(Routes.chatDetail(chatId)) { launchSingleTop = true }
                    }
                    return@onOpenItem
                }
                // MISSED_CALL 类型无 deeplink 时仍打开未接箱，并按 callId 清托盘
                if (item.type == "MISSED_CALL" && item.deeplink.isNullOrBlank()) {
                    val callId = item.extra["callId"].orEmpty()
                    if (callId.isNotBlank()) {
                        com.maodouchat.notification.CallNotificationService.cancelMissedCall(
                            context.applicationContext,
                            callId,
                        )
                    }
                    com.maodouchat.MaodouchatApp.emitOpenMissedCalls()
                    navController.popBackStack()
                    return@onOpenItem
                }
                if (item.type == "FRIEND_REQUEST" && item.deeplink.isNullOrBlank()) {
                    com.maodouchat.MaodouchatApp.emitOpenContacts()
                    navController.popBackStack()
                    return@onOpenItem
                }
                val dest = AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())
                    ?: return@onOpenItem
                when (dest) {
                    is AppLinkDestination.MissedCallsTab -> {
                        val callId = item.extra["callId"].orEmpty()
                        if (callId.isNotBlank()) {
                            com.maodouchat.notification.CallNotificationService.cancelMissedCall(
                                context.applicationContext,
                                callId,
                            )
                        }
                        com.maodouchat.MaodouchatApp.emitOpenMissedCalls()
                        navController.popBackStack()
                    }
                    is AppLinkDestination.ContactsTab -> {
                        com.maodouchat.MaodouchatApp.emitOpenContacts()
                        navController.popBackStack()
                    }
                    is AppLinkDestination.ChatDetail -> {
                        com.maodouchat.notification.MessageNotificationService.cancelMessage(
                            context.applicationContext,
                            dest.chatId,
                        )
                        navController.navigate(dest.toRoute()) { launchSingleTop = true }
                    }
                    is AppLinkDestination.AiTasksChat -> {
                        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(
                            context.applicationContext,
                            dest.chatId,
                        )
                        navController.navigate(dest.toRoute()) { launchSingleTop = true }
                    }
                    is AppLinkDestination.PostDetail -> {
                        com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(
                            context.applicationContext,
                            dest.postId,
                        )
                        // 1.121：打开动态时将该动态的全部互动通知标记已读（角标/未读同步归零）
                        runCatching {
                            (context.applicationContext as? com.maodouchat.MaodouchatApp)
                                ?.notificationCenter?.markPostInteractionsRead(dest.postId)
                        }
                        navController.navigate(dest.toRoute()) { launchSingleTop = true }
                    }
                    else -> Unit
                }
            }
        )
    }
}
