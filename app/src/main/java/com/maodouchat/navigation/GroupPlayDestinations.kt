package com.maodouchat.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * 群玩法域目的地（P08：自 `NavGraph.kt` 迁出的第四个 feature 簇）。
 * 只依赖 `navController`；逐字搬运，行为不变。
 */
fun NavGraphBuilder.groupPlayDestinations(navController: NavHostController) {
    // ── 群玩法 B3：投票 / 签到+排行 / 接龙 / PK ──
    composable(
        route = Routes.GROUP_POLL,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) {
        com.maodouchat.ui.screen.groupplay.GroupPollScreen(onBack = { navController.popBackStack() })
    }
    composable(
        route = Routes.GROUP_CHECKIN,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) {
        com.maodouchat.ui.screen.groupplay.GroupCheckinScreen(onBack = { navController.popBackStack() })
    }
    composable(
        route = Routes.GROUP_CHAIN,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) {
        com.maodouchat.ui.screen.groupplay.GroupChainScreen(onBack = { navController.popBackStack() })
    }
    composable(
        route = Routes.GROUP_PK,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) {
        com.maodouchat.ui.screen.groupplay.GroupPkScreen(onBack = { navController.popBackStack() })
    }
}
