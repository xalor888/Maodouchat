package com.maodouchat.ui.navigation

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.maodouchat.ui.screen.chatlist.GlobalSearchScreen
import com.maodouchat.ui.screen.chatlist.NotificationCenterScreen
import com.maodouchat.navigation.NotificationCenterEffects
import com.maodouchat.navigation.NotificationCenterOpenController
import com.maodouchat.navigation.Routes

/**
 * 搜索/通知中心目的地（P08：自 `NavGraph.kt` 迁出的第五个 feature 簇）。
 *
 * U02 延伸：通知中心行点击的「决策 + 副作用」已收进非 ui 的
 * [NotificationCenterOpenController]（接线见 [NotificationCenterEffects]），
 * 本文件只剩「Outcome → NavController 动作」这一层映射，不再直接触碰
 * app 单例/通知服务（ui 直连持久层棘轮随之归零）。
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
        val openController = remember(context) { NotificationCenterEffects.create(context) }
        NotificationCenterScreen(
            onBack = { navController.popBackStack() },
            onOpenItem = onOpenItem@ { item ->
                when (val outcome = openController.onOpenItem(item)) {
                    is NotificationCenterOpenController.Outcome.Navigate ->
                        navController.navigate(outcome.route) { launchSingleTop = true }

                    NotificationCenterOpenController.Outcome.PopBackStack ->
                        navController.popBackStack()

                    // 8.49：与 401 路径对齐清栈——否则 LOGIN 压在通知中心之上，
                    // 返回键回到死会话页面，重复点击堆叠多个 LOGIN entry
                    NotificationCenterOpenController.Outcome.GoLogin ->
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }

                    NotificationCenterOpenController.Outcome.None -> Unit
                }
            }
        )
    }
}
