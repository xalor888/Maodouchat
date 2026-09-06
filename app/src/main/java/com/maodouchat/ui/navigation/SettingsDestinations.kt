package com.maodouchat.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * 设置域目的地（P08：自 `NavGraph.kt` 迁出的第一个 feature 簇）。
 * 只依赖 `navController`；逐字搬运，行为不变。
 */
fun NavGraphBuilder.settingsDestinations(navController: NavHostController) {
    composable(Routes.SETTINGS_ACCOUNT_SECURITY) {
        com.maodouchat.ui.screen.settings.AccountSecurityScreen(
            onBack = { navController.popBackStack() },
            onOpenMyQrCode = { navController.navigate(Routes.MY_QR_CODE) },
            onLogout = {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(Routes.MAIN) { inclusive = true }
                }
            }
        )
    }
    composable(Routes.SETTINGS_MY_REPORTS) {
        com.maodouchat.ui.screen.settings.MyReportsScreen(
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SETTINGS_BLOCKED_USERS) {
        com.maodouchat.ui.screen.settings.BlockedUsersScreen(
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SETTINGS_NOTIFICATIONS) {
        com.maodouchat.ui.screen.settings.NotificationSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_AI_PRIVACY) {
        com.maodouchat.ui.screen.settings.AiPrivacySettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAgent = { navController.navigate(Routes.AGENT) }
        )
    }
    composable(Routes.AGENT) {
        com.maodouchat.ai.agent.MaodouAgentScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_MODERATION) {
        com.maodouchat.ui.screen.settings.ModerationScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_GENERAL) {
        com.maodouchat.ui.screen.settings.GeneralSettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
            onOpenWatermarkForensic = { navController.navigate(Routes.WATERMARK_FORENSIC) },
            onOpenDeveloperBots = { navController.navigate(Routes.DEVELOPER_BOTS) },
            onOpenThemeEditor = { navController.navigate(Routes.SETTINGS_THEME_EDITOR) },
            onOpenThemeWorkbench = { navController.navigate(Routes.SETTINGS_THEME_WORKBENCH) }
        )
    }
    composable(Routes.SETTINGS_THEME_EDITOR) {
        com.maodouchat.ui.screen.settings.ThemeEditorScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_THEME_WORKBENCH) {
        com.maodouchat.ui.screen.settings.ThemeWorkbenchScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_ABOUT) {
        com.maodouchat.ui.screen.settings.AboutScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_SERVER) {
        com.maodouchat.ui.screen.settings.ServerSettingsScreen(
            onBack = { navController.popBackStack() },
            onServerChanged = {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        )
    }
    composable(Routes.WATERMARK_FORENSIC) {
        com.maodouchat.ui.screen.settings.WatermarkForensicScreen(
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.DEVELOPER_BOTS) {
        com.maodouchat.ui.screen.settings.DeveloperBotsScreen(
            onBack = { navController.popBackStack() },
            onOpenChat = { chatId ->
                navController.navigate(Routes.chatDetail(chatId)) {
                    launchSingleTop = true
                }
            }
        )
    }
}
