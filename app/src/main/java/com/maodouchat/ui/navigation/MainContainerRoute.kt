package com.maodouchat.ui.navigation

import com.maodouchat.util.RuntimeFlags
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.maodouchat.R
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.maodouchat.ui.screen.chatdetail.ChatDetailScreen
import com.maodouchat.ui.screen.chatdetail.AiTasksScreen
import com.maodouchat.ui.screen.chatdetail.GroupDetailScreen
import com.maodouchat.ui.screen.chatdetail.GroupDetailViewModel
import com.maodouchat.ui.screen.chatdetail.GroupEditScreen
import com.maodouchat.ui.screen.chatdetail.GroupInviteQrScreen
import com.maodouchat.ui.screen.chatdetail.StarredMessagesScreen
import com.maodouchat.ui.screen.chatdetail.MediaCenterScreen
import com.maodouchat.ui.screen.chatlist.BottomNavBar
import com.maodouchat.ui.screen.chatlist.ChatListScreen
import com.maodouchat.ui.screen.chatlist.GlobalSearchScreen
import com.maodouchat.ui.screen.chatlist.NotificationCenterScreen
import com.maodouchat.ui.screen.call.CallScreen
import com.maodouchat.ui.screen.call.CallViewModel
import com.maodouchat.ui.screen.contacts.ContactsScreen
import com.maodouchat.ui.screen.explore.ExploreScreen
import com.maodouchat.ui.screen.login.LoginScreen
import com.maodouchat.ui.screen.settings.SettingsScreen
import com.maodouchat.ui.screen.explore.PublicProfileScreen
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCSignaling
// B5 新增（仅追加）：平板双栏布局
import com.maodouchat.ui.layout.AdaptiveLayout
import com.maodouchat.ui.layout.rememberAdaptiveLayoutState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.rememberNavController
import com.maodouchat.network.PublicUpdatesDto
import com.maodouchat.update.AppUpdatePolicy
import com.maodouchat.update.AppUpdatePromptStore
import com.maodouchat.update.OfficialApkInstaller
import com.maodouchat.consumeIfStale


/**
 * 主框架容器 — 底部导航 + Tab 切换
 */
@Composable
internal fun MainContainer(navController: NavHostController) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(MainTab.CHATS) }
    val motion = LocalMotionSettings.current
    // Missed-call tray tap must land on chats inbox (not contacts/explore/settings/archive).
    var openMissedCallsRequest by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        com.maodouchat.MaodouchatApp.openMissedCallsEvents.collect { req ->
            if (consumeIfStale(req, com.maodouchat.MaodouchatApp::consumeOpenMissedCalls)) return@collect
            selectedTab = MainTab.CHATS
            openMissedCallsRequest = req.atMillis
            com.maodouchat.MaodouchatApp.consumeOpenMissedCalls(req)
        }
    }
    // Friend-request / contacts deep-link → contacts tab.
    LaunchedEffect(Unit) {
        com.maodouchat.MaodouchatApp.openContactsEvents.collect { req ->
            if (consumeIfStale(req, com.maodouchat.MaodouchatApp::consumeOpenContacts)) return@collect
            selectedTab = MainTab.CONTACTS
            com.maodouchat.MaodouchatApp.consumeOpenContacts(req)
        }
    }

    // 9.208：第三方服务器运营公告——同一内容只弹一次，更新后再弹
    val serverIdentity by com.maodouchat.network.ServerIdentity.current.collectAsState()
    var pendingAnnouncement by remember(serverIdentity) {
        mutableStateOf(com.maodouchat.util.ServerAnnouncementNotice.pendingAnnouncement(context))
    }
    if (pendingAnnouncement != null) {
        val announcementText = pendingAnnouncement.orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingAnnouncement = null },
            title = { Text(stringResource(com.maodouchat.R.string.server_announcement_title)) },
            text = { Text(announcementText) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.maodouchat.util.ServerAnnouncementNotice.markShown(context, announcementText)
                    pendingAnnouncement = null
                }) { Text(stringResource(com.maodouchat.R.string.common_confirm)) }
            }
        )
    }

    // 悬浮胶囊底栏叠在内容之上，不走 Scaffold.bottomBar（否则会变成贴底 NavigationBar）。
    // 内容层用 kyant layerBackdrop 采样，底栏才能做出 Murexide 同款液态玻璃折射。
    val liquidBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalLiquidGlassBackdrop provides liquidBackdrop) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(liquidBackdrop),
            targetState = selectedTab,
            transitionSpec = {
                if (!motion.animationsEnabled) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(motion.duration(90))) togetherWith fadeOut(tween(motion.duration(70)))
                }
            },
            label = "mainTabContent"
        ) { tab ->
                // 渐隐遮罩必须画在本层内：dock 的玻璃折射采样的是本层（liquidBackdrop）。
                // 画在外侧只会让胶囊外变淡，胶囊内折射出的仍是未渐隐原文（底栏文字叠印的根源）。
                Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    MainTab.CHATS -> ChatListScreen(
                        onChatClick = { chatId -> navController.navigate(Routes.chatDetail(chatId)) },
                        onOpenGroupDetail = { chatId -> navController.navigate(Routes.groupDetail(chatId)) },
                        onOpenGlobalSearch = { navController.navigate(Routes.GLOBAL_SEARCH) },
                        onOpenNotificationCenter = { navController.navigate(Routes.NOTIFICATION_CENTER) },
                        onNavigateToTab = { selectedTab = it },
                        onOpenScan = { navController.navigate(Routes.SCAN) },
                        openMissedCallsRequest = openMissedCallsRequest,
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
                    MainTab.CONTACTS -> ContactsScreen(
                        onChatCreated = { chatId -> navController.navigate(Routes.chatDetail(chatId)) },
                        onOpenScan = { navController.navigate(Routes.SCAN) }
                    )
                    MainTab.EXPLORE -> ExploreScreen(
                        onNavigateTo = { target ->
                            when (target) {
                                "scan" -> navController.navigate(Routes.SCAN)
                                "moments" -> navController.navigate(Routes.MOMENTS)
                                "my_qr_code" -> navController.navigate(Routes.MY_QR_CODE)
                            }
                        },
                        // 1.94：动态卡片正文点击 → 完整详情页
                        onOpenPost = { postId -> navController.navigate(Routes.postDetail(postId)) { launchSingleTop = true } },
                        // 1.110：动态卡片作者行点击 → 作者主页
                        onOpenAuthor = { authorId -> navController.navigate(Routes.authorProfile(authorId)) { launchSingleTop = true } }
                    )
                    else -> SettingsScreen(
                        onLogout = {
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(Routes.MAIN) { inclusive = true }
                            }
                        },
                        onOpenAccountSecurity = { navController.navigate(Routes.SETTINGS_ACCOUNT_SECURITY) },
                        onOpenMyReports = { navController.navigate(Routes.SETTINGS_MY_REPORTS) },
                        onOpenBlockedUsers = { navController.navigate(Routes.SETTINGS_BLOCKED_USERS) },
                        onOpenNotifications = { navController.navigate(Routes.SETTINGS_NOTIFICATIONS) },
                        onOpenAiPrivacy = { navController.navigate(Routes.SETTINGS_AI_PRIVACY) },
                        onOpenAgent = { navController.navigate(Routes.AGENT) },
                        onOpenModeration = { navController.navigate(Routes.SETTINGS_MODERATION) },
                        onOpenGeneral = { navController.navigate(Routes.SETTINGS_GENERAL) },
                        onOpenMyQrCode = { navController.navigate(Routes.MY_QR_CODE) },
                        onOpenStarredMessages = { navController.navigate(Routes.starredMessages()) },
                        onOpenServer = { navController.navigate(Routes.SETTINGS_SERVER) },
                        onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                        // 1.116：我的动态 → 作者主页（当前用户）
                        onOpenMyPosts = {
                            val myUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId().orEmpty()
                            if (myUserId.isNotBlank()) {
                                navController.navigate(Routes.authorProfile(myUserId)) { launchSingleTop = true }
                            }
                        }
                    )
                }
                val dockFadeActive = com.maodouchat.ui.theme.LocalLiquidGlassEnabled.current &&
                    com.maodouchat.util.ChromePreferences.floatingDock.collectAsState().value
                if (dockFadeActive) {
                    val fadeColor = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(com.maodouchat.ui.component.FloatingBottomBarContentPadding)
                            .background(
                                Brush.verticalGradient(
                                    0f to fadeColor.copy(alpha = 0f),
                                    0.55f to fadeColor.copy(alpha = 0.72f),
                                    1f to fadeColor.copy(alpha = 0.97f),
                                )
                            )
                    )
                }
                }
        }
        BottomNavBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }
}

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
                            val userColor = if (bubbleIsDark) com.maodouchat.ui.theme.ChatBubbleColorPalette.dark(id)
                            else com.maodouchat.ui.theme.ChatBubbleColorPalette.light(id)
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