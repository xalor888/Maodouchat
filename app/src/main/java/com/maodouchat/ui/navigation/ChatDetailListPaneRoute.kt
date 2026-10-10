package com.maodouchat.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.maodouchat.R
import com.maodouchat.ui.screen.chatdetail.ChatDetailScreen
import com.maodouchat.ui.screen.chatlist.ChatListScreen
// B5 新增（仅追加）：平板双栏布局
import com.maodouchat.ui.layout.AdaptiveLayout
import com.maodouchat.ui.layout.rememberAdaptiveLayoutState
import androidx.compose.material3.Text
import androidx.navigation.compose.rememberNavController
import com.maodouchat.navigation.Routes

/**
 * B5 新增（仅追加）：平板双栏布局 — 列表左栏路由。
 *
 * - 宽屏（≥840dp 且宽≥高）：AdaptiveLayout 左栏会话列表 + 右栏嵌套 NavHost 渲染会话详情；
 * - 窄屏：退化为单栏会话列表，点击仍走原有 chatDetail 全屏路由；
 * - 详情导航用 Routes.chatDetailTwoPane 路由（嵌套图内），切会话时保持左栏状态与宽度记忆。
 */
@Composable
internal fun ChatDetailListPaneRoute(navController: NavHostController) {
    val adaptiveState = rememberAdaptiveLayoutState()
    val detailNavController = rememberNavController()
    AdaptiveLayout(
        state = adaptiveState,
        listPane = {
            ChatListScreen(
                 onChatClick = { chatId ->
                    if (adaptiveState.isTwoPane) {
                        detailNavController.navigate(Routes.chatDetailTwoPane(chatId)) { launchSingleTop = true }
                    } else {
                        navController.navigate(Routes.chatDetail(chatId))
                    }
                },
                 onOpenGroupDetail = { chatId -> navController.navigate(Routes.groupDetail(chatId)) },
                 onOpenGlobalSearch = { navController.navigate(Routes.GLOBAL_SEARCH) },
                 onOpenNotificationCenter = { navController.navigate(Routes.NOTIFICATION_CENTER) },
                 onNavigateToTab = { tab -> navController.navigate(Routes.MAIN) { popUpTo(Routes.MAIN) { inclusive = false } } },
                 onOpenScan = { navController.navigate(Routes.SCAN) },
                 onVoiceCall = { contactId, contactName ->
                    navController.navigate(Routes.call(contactId, contactName, "AUDIO"))
                 },
                 onVideoCall = { contactId, contactName ->
                    navController.navigate(Routes.call(contactId, contactName, "VIDEO"))
                },
                // 1.185：长按菜单「查看共享媒体」
                onOpenMediaCenter = { chatId -> navController.navigate(Routes.mediaCenter(chatId)) { launchSingleTop = true } },
                // 1.215：长按菜单「查看收藏」
                onOpenStarredMessages = { chatId -> navController.navigate(Routes.starredMessages(chatId)) { launchSingleTop = true } },
                // 1.251：长按菜单「查看资料」
                onOpenProfile = { userId -> navController.navigate(Routes.authorProfile(userId)) { launchSingleTop = true } }
            )
        },
        detailPane = {
            NavHost(
                navController = detailNavController,
                startDestination = TwoPaneDetailRoute.EMPTY,
            ) {
                composable(TwoPaneDetailRoute.EMPTY) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(text = stringResource(R.string.two_pane_empty_hint))
                    }
                }
                composable(
                    route = Routes.CHAT_DETAIL_TWO_PANE,
                    arguments = listOf(navArgument("chatId") { type = NavType.StringType })
                ) { entry ->
                    val chatId = Uri.decode(entry.arguments?.getString("chatId") ?: "")
                    if (chatId.isNotBlank()) {
                        val bubbleCtx = LocalContext.current
                        val bubbleIsDark = com.maodouchat.ui.theme.LocalDarkTheme.current
                        val themeSentSpec = com.maodouchat.ui.theme.LocalSentBubbleSpec.current
                        val appearanceVersion by com.maodouchat.util.ChatAppearancePreferences.appearanceVersion.collectAsState()
                        val sentColors = remember(chatId, themeSentSpec, bubbleIsDark, appearanceVersion) {
                            val id = com.maodouchat.util.ChatAppearancePreferences.getBubbleColor(bubbleCtx)
                            val userColor = if (bubbleIsDark) com.maodouchat.theme.ChatBubbleColorPalette.dark(id)
                            else com.maodouchat.theme.ChatBubbleColorPalette.light(id)
                            val customized = com.maodouchat.util.ChatAppearancePreferences.hasCustomBubbleColor(bubbleCtx)
                            com.maodouchat.ui.theme.resolveSentBubble(themeSentSpec, customized, userColor)
                        }
                        val themeFamily = com.maodouchat.ui.theme.ThemeFamily.normalize(
                            com.maodouchat.util.ThemePreferences.family.collectAsState().value
                        )
                        val bubbleShapes = remember(chatId, appearanceVersion, themeFamily) {
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
                                onBack = { detailNavController.popBackStack() },
                                onVoiceCall = { contactId, contactName ->
                                    navController.navigate(Routes.call(contactId, contactName, "AUDIO")) { launchSingleTop = true }
                                },
                                onVideoCall = { contactId, contactName ->
                                    navController.navigate(Routes.call(contactId, contactName, "VIDEO")) { launchSingleTop = true }
                                },
                                onOpenSecretChat = { secretChatId ->
                                    detailNavController.navigate(Routes.chatDetailTwoPane(secretChatId)) { launchSingleTop = true }
                                },
                                onOpenGroupDetail = { id -> navController.navigate(Routes.groupDetail(id)) { launchSingleTop = true } },
                                onOpenStarredMessages = { id -> navController.navigate(Routes.starredMessages(id)) { launchSingleTop = true } },
                                onOpenMediaCenter = { id -> navController.navigate(Routes.mediaCenter(id)) { launchSingleTop = true } },
                                onOpenAiTasks = { id -> navController.navigate(Routes.aiTasks(id)) { launchSingleTop = true } },
                                // 9.3xx：真实群功能页
                                onOpenGroupPoll = { id -> navController.navigate(Routes.groupPoll(id)) { launchSingleTop = true } },
                                onOpenGroupCheckin = { id -> navController.navigate(Routes.groupCheckin(id)) { launchSingleTop = true } },
                                onOpenGroupChain = { id -> navController.navigate(Routes.groupChain(id)) { launchSingleTop = true } },
                                onOpenGroupPk = { id -> navController.navigate(Routes.groupPk(id)) { launchSingleTop = true } }
                            )
                        }
                    }
                }
            }
        },
        narrowContent = {
            ChatListScreen(
                onChatClick = { chatId -> navController.navigate(Routes.chatDetail(chatId)) },
                onOpenGroupDetail = { chatId -> navController.navigate(Routes.groupDetail(chatId)) },
                onOpenGlobalSearch = { navController.navigate(Routes.GLOBAL_SEARCH) },
                onOpenNotificationCenter = { navController.navigate(Routes.NOTIFICATION_CENTER) },
                onNavigateToTab = { tab -> navController.navigate(Routes.MAIN) { popUpTo(Routes.MAIN) { inclusive = false } } },
                onOpenScan = { navController.navigate(Routes.SCAN) },
                onVoiceCall = { contactId, contactName ->
                    navController.navigate(Routes.call(contactId, contactName, "AUDIO"))
                },
                 onVideoCall = { contactId, contactName ->
                    navController.navigate(Routes.call(contactId, contactName, "VIDEO"))
                },
                // 1.185：长按菜单「查看共享媒体」
                onOpenMediaCenter = { chatId -> navController.navigate(Routes.mediaCenter(chatId)) { launchSingleTop = true } },
                // 1.215：长按菜单「查看收藏」
                onOpenStarredMessages = { chatId -> navController.navigate(Routes.starredMessages(chatId)) { launchSingleTop = true } },
                // 1.251：长按菜单「查看资料」
                onOpenProfile = { userId -> navController.navigate(Routes.authorProfile(userId)) { launchSingleTop = true } }
            )
        }
    )
}

/** B5 双栏「详情」嵌套图内部路由（不进入顶层 NavHost） */
internal object TwoPaneDetailRoute {
    const val EMPTY = "detail_empty"
}
