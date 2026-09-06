package com.maodouchat.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.maodouchat.ui.screen.login.LoginScreen
import kotlinx.coroutines.launch

/**
 * 登录/主壳目的地（P08：自 `NavGraph.kt` 迁出的第七个 feature 簇）。
 * 只依赖 `navController`；逐字搬运，行为不变。
 */
fun NavGraphBuilder.authDestinations(navController: NavHostController) {
    composable(Routes.LOGIN) {
        LoginScreen(
            onLoginSuccess = {
                // Multi-device UX prefs before main chrome paints with stale local theme/lang.
                com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                    runCatching {
                        com.maodouchat.util.ClientPrefsSync.pullAndApply(
                            com.maodouchat.MaodouchatApp.instance
                        )
                    }
                }
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            },
            onOpenServer = { navController.navigate(Routes.SETTINGS_SERVER) }
        )
    }

    composable(Routes.MAIN) {
        MainContainer(navController = navController)
    }
}
