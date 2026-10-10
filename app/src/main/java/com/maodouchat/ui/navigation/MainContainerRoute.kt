package com.maodouchat.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavHostController
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalMotionSettings
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.maodouchat.session.CurrentSession
import com.maodouchat.ui.screen.chatlist.BottomNavBar
import com.maodouchat.ui.screen.chatlist.ChatListScreen
import com.maodouchat.ui.screen.contacts.ContactsScreen
import com.maodouchat.ui.screen.explore.ExploreScreen
import com.maodouchat.ui.screen.settings.SettingsScreen
import androidx.compose.material3.Text
import com.maodouchat.navigation.AppNavigationEvents
import com.maodouchat.navigation.MainTab
import com.maodouchat.navigation.Routes

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
        AppNavigationEvents.missedCallsEvents().collect { req ->
            if (AppNavigationEvents.missedCallsIsStale(req)) return@collect
            selectedTab = MainTab.CHATS
            openMissedCallsRequest = req.atMillis
            AppNavigationEvents.consumeMissedCalls(req)
        }
    }
    // Friend-request / contacts deep-link → contacts tab.
    LaunchedEffect(Unit) {
        AppNavigationEvents.contactsEvents().collect { req ->
            if (AppNavigationEvents.contactsIsStale(req)) return@collect
            selectedTab = MainTab.CONTACTS
            AppNavigationEvents.consumeContacts(req)
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
                            val myUserId = CurrentSession.ownerUserId()
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
