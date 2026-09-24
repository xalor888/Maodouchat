package com.maodouchat.ui.navigation

import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.maodouchat.ui.screen.chatdetail.AiTasksScreen
import com.maodouchat.ui.screen.chatdetail.ChatDetailScreen
import com.maodouchat.ui.screen.chatdetail.GroupDetailScreen
import com.maodouchat.ui.screen.chatdetail.GroupDetailViewModel
import com.maodouchat.ui.screen.chatdetail.GroupEditScreen
import com.maodouchat.ui.screen.chatdetail.GroupInviteQrScreen
import com.maodouchat.ui.screen.chatdetail.MediaCenterScreen
import com.maodouchat.ui.screen.chatdetail.StarredMessagesScreen
import com.maodouchat.navigation.Routes

/**
 * 聊天域目的地（P08：自 `NavGraph.kt` 迁出的第六个 feature 簇，最大的一块）。
 * 只依赖 `navController`；逐字搬运，行为不变。
 */
fun NavGraphBuilder.chatDestinations(navController: NavHostController) {
    composable(
        route = Routes.CHAT_DETAIL,
        arguments = listOf(
            navArgument("chatId") { type = NavType.StringType },
            navArgument("messageId") { type = NavType.StringType; nullable = true; defaultValue = null }
        )
    ) { entry ->
        val chatIdArg = Uri.decode(entry.arguments?.getString("chatId") ?: "")
        val bubbleCtx = LocalContext.current
        val bubbleIsDark = com.maodouchat.ui.theme.LocalDarkTheme.current
        val themeSentSpec = com.maodouchat.ui.theme.LocalSentBubbleSpec.current
        // 9.207：外观版本号驱动重算——设置页改气泡色/圆角后返回聊天页即时生效
        val appearanceVersion by com.maodouchat.util.ChatAppearancePreferences.appearanceVersion.collectAsState()
        val sentColors = remember(chatIdArg, themeSentSpec, bubbleIsDark, appearanceVersion) {
            val id = com.maodouchat.util.ChatAppearancePreferences.getBubbleColor(bubbleCtx)
            val userColor = if (bubbleIsDark) com.maodouchat.ui.theme.ChatBubbleColorPalette.dark(id)
            else com.maodouchat.ui.theme.ChatBubbleColorPalette.light(id)
            val customized = com.maodouchat.util.ChatAppearancePreferences.hasCustomBubbleColor(bubbleCtx)
            com.maodouchat.ui.theme.resolveSentBubble(themeSentSpec, customized, userColor)
        }
        val themeFamily = com.maodouchat.ui.theme.ThemeFamily.normalize(
            com.maodouchat.util.ThemePreferences.family.collectAsState().value
        )
        val bubbleShapes = remember(chatIdArg, appearanceVersion, themeFamily) {
            com.maodouchat.ui.theme.bubbleShapesFor(
                com.maodouchat.util.ChatAppearancePreferences.getBubbleShape(bubbleCtx),
                themeFamily,
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.maodouchat.ui.theme.LocalChatBubbleColor provides sentColors.bubble,
            com.maodouchat.ui.theme.LocalSentBubbleContent provides sentColors.content,
            com.maodouchat.ui.theme.LocalSentBubbleContentSecondary provides sentColors.contentSecondary,
            com.maodouchat.ui.theme.LocalBubbleShapes provides bubbleShapes
        ) {
            ChatDetailScreen(
                onBack = {
                    if (!navController.popBackStack(Routes.MAIN, inclusive = false)) {
                        navController.navigate(Routes.MAIN) { launchSingleTop = true }
                    }
                },
                onVoiceCall = { contactId, contactName ->
                    navController.navigate(Routes.call(contactId, contactName, "AUDIO"))
                },
                onVideoCall = { contactId, contactName ->
                    navController.navigate(Routes.call(contactId, contactName, "VIDEO"))
                },
                onOpenSecretChat = { secretChatId ->
                    navController.navigate(Routes.chatDetail(secretChatId)) { launchSingleTop = true }
                },
                onOpenGroupDetail = { chatId -> navController.navigate(Routes.groupDetail(chatId)) },
                onOpenStarredMessages = { chatId -> navController.navigate(Routes.starredMessages(chatId)) },
                onOpenMediaCenter = { chatId -> navController.navigate(Routes.mediaCenter(chatId)) },
                onOpenAiTasks = { chatId -> navController.navigate(Routes.aiTasks(chatId)) },
                // 9.3xx：真实群功能页
                onOpenGroupPoll = { chatId -> navController.navigate(Routes.groupPoll(chatId)) },
                onOpenGroupCheckin = { chatId -> navController.navigate(Routes.groupCheckin(chatId)) },
                onOpenGroupChain = { chatId -> navController.navigate(Routes.groupChain(chatId)) },
                onOpenGroupPk = { chatId -> navController.navigate(Routes.groupPk(chatId)) },
                // 1.17：点击消息内名片 → 打开该用户资料
                onOpenProfile = { userId -> navController.navigate(Routes.authorProfile(userId)) },
                // 1.29：通话记录
                onOpenCallHistory = { navController.navigate(Routes.CALL_HISTORY) }
            )
        }
    }

    composable(
        route = Routes.GROUP_DETAIL,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) { entry ->
        val groupViewModel: GroupDetailViewModel = viewModel(entry)
        val groupChatId = Uri.decode(entry.arguments?.getString("chatId") ?: "")
        GroupDetailScreen(
            onBack = { navController.popBackStack() },
            viewModel = groupViewModel,
            onEditGroup = { id -> navController.navigate(Routes.groupEdit(id)) },
            onOpenGroupInvite = { id -> navController.navigate(Routes.groupInvite(id)) },
            // 1.08：点击群成员查看资料
            onOpenProfile = { userId -> navController.navigate(Routes.authorProfile(userId)) },
            onOpenGroupPoll = { id -> navController.navigate(Routes.groupPoll(id)) },
            onOpenGroupCheckin = { id -> navController.navigate(Routes.groupCheckin(id)) },
            onOpenGroupChain = { id -> navController.navigate(Routes.groupChain(id)) },
            onOpenGroupPk = { id -> navController.navigate(Routes.groupPk(id)) },
            onOpenMessage = { messageId ->
                navController.navigate(Routes.chatDetail(groupChatId, messageId)) {
                    popUpTo(Routes.GROUP_DETAIL) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onOpenAgent = { navController.navigate(Routes.AGENT) },
        )
    }

    composable(
        route = Routes.GROUP_EDIT,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) { entry ->
        val detailEntry = remember(entry) { navController.getBackStackEntry(Routes.GROUP_DETAIL) }
        val groupViewModel: GroupDetailViewModel = viewModel(detailEntry)
        GroupEditScreen(
            onBack = { navController.popBackStack() },
            viewModel = groupViewModel
        )
    }

    composable(
        route = Routes.GROUP_INVITE,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) { entry ->
        val detailEntry = remember(entry) { navController.getBackStackEntry(Routes.GROUP_DETAIL) }
        val groupViewModel: GroupDetailViewModel = viewModel(detailEntry)
        GroupInviteQrScreen(
            onBack = { navController.popBackStack() },
            viewModel = groupViewModel
        )
    }

    composable(
        route = Routes.STARRED_MESSAGES,
        arguments = listOf(
            navArgument("chatId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) {
        StarredMessagesScreen(
            onBack = { navController.popBackStack() },
            onOpenMessage = { chatId, messageId ->
                navController.navigate(Routes.chatDetail(chatId, messageId)) {
                    launchSingleTop = true
                }
            }
        )
    }

    composable(
        route = Routes.AI_TASKS,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) {
        AiTasksScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.MEDIA_CENTER,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
    ) { entry ->
        val chatId = Uri.decode(entry.arguments?.getString("chatId") ?: "")
        MediaCenterScreen(
            onBack = { navController.popBackStack() },
            onOpenMessage = { messageId ->
                navController.navigate(Routes.chatDetail(chatId, messageId)) {
                    popUpTo(Routes.MEDIA_CENTER) { inclusive = true }
                    launchSingleTop = true
                }
            }
        )
    }
}
