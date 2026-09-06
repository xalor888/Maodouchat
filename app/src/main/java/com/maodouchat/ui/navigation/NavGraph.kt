package com.maodouchat.ui.navigation

import com.maodouchat.notification.SocialNotificationService
import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.notification.CallNotificationService
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.maodouchat.ui.screen.contacts.ContactsScreen
import com.maodouchat.ui.screen.explore.ExploreScreen
import com.maodouchat.ui.screen.login.LoginScreen
import com.maodouchat.ui.screen.settings.SettingsScreen
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

/**
 * 导航图（注册装配；路由定义见 `NavRoutes.kt`，深链决策见 `AppLinkRouter`）。
 */

/**
 * 导航图
 */
@Composable
fun MaodouchatNavGraph(
    navController: NavHostController,
    startDestination: String = Routes.LOGIN
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val motion = LocalMotionSettings.current
    val navContext = LocalContext.current
    val navMotionEnabled = RuntimeFlags.isEnabled(navContext, RuntimeFlags.NAV_TRANSITIONS) && motion.animationsEnabled
    val groupCallDisplayName = stringResource(R.string.chat_group_call)
    val sessionExpiredMsg = stringResource(R.string.error_session_expired)
    val createChatFailedMsg = stringResource(R.string.contacts_create_chat_failed)
    val adminBroadcastDefaultTitle = stringResource(R.string.notification_admin_broadcast_default_title)
    var adminBroadcastDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var appUpdateOffer by remember { mutableStateOf<PublicUpdatesDto?>(null) }
    var appUpdateDownloading by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableIntStateOf(0) }
    val appUpdateScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val ownerUserId = TokenManager.getInstance(context).getUserId().orEmpty()
        val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return@LaunchedEffect
        app.realtimeEventDispatcher.adminNoticeEvents.collect { event ->
            if (ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = TokenManager.getInstance(context).getToken(),
                    liveUserId = TokenManager.getInstance(context).getUserId(),
                )
            ) {
                return@collect
            }
            val body = event.text.trim()
            if (body.isBlank()) return@collect
            adminBroadcastDialog = (event.title.ifBlank { adminBroadcastDefaultTitle }) to body
        }
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute != Routes.MAIN) return@LaunchedEffect
        val currentCode = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        }.getOrDefault(0)
        val remote = ApiService.getPublicUpdates().getOrNull() ?: return@LaunchedEffect
        if (!AppUpdatePolicy.shouldOfferUpdate(currentCode, remote.versionCode, remote.apkUrl, remote.apkSha256)) return@LaunchedEffect
        if (AppUpdatePromptStore.lastOfferedVersionCode(context) >= remote.versionCode) return@LaunchedEffect
        AppUpdatePromptStore.markOffered(context, remote.versionCode)
        appUpdateOffer = remote
    }

    appUpdateOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { if (!appUpdateDownloading) appUpdateOffer = null },
            title = { Text(stringResource(R.string.about_update_available, offer.versionName.ifBlank { offer.versionCode.toString() })) },
            text = {
                Text(
                    if (appUpdateDownloading) {
                        stringResource(R.string.about_update_downloading, appUpdateProgress)
                    } else {
                        com.maodouchat.update.AppUpdatePolicy.formatNotes(offer.notes).ifBlank { stringResource(R.string.about_update_notes) }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !appUpdateDownloading,
                    onClick = {
                        appUpdateDownloading = true
                        appUpdateScope.launch {
                            val result = OfficialApkInstaller.downloadAndPromptInstall(
                                context = context,
                                apkUrl = offer.apkUrl,
                                expectedSha256 = offer.apkSha256,
                                onProgress = { percent -> appUpdateProgress = percent },
                            )
                            appUpdateDownloading = false
                            if (result.isSuccess) appUpdateOffer = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.about_update_download))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !appUpdateDownloading,
                    onClick = { appUpdateOffer = null }
                ) {
                    Text(stringResource(R.string.common_later))
                }
            }
        )
    }

    adminBroadcastDialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { adminBroadcastDialog = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { adminBroadcastDialog = null }) {
                    Text(stringResource(R.string.chat_acknowledge))
                }
            }
        )
    }

    // Bug #20: 监听 Token 过期事件（401），完整清理本地会话后跳转登录页
    LaunchedEffect(Unit) {
        ApiService.tokenExpired.collectLatest { event ->
            val app = context.applicationContext as? com.maodouchat.MaodouchatApp
            if (!com.maodouchat.network.TokenExpiredEventPolicy.shouldHandle(
                    eventOwnerUserId = event.ownerUserId,
                    eventSessionGeneration = event.sessionGeneration,
                    currentOwnerUserId = TokenManager.getInstance(context).getUserId(),
                    currentSessionGeneration = com.maodouchat.MaodouchatApp.currentSessionGeneration(),
                )
            ) return@collectLatest
            val purged = try {
                app?.secureSessionManager?.purgeLocalSession(
                    destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                        com.maodouchat.security.LogoutStorePolicy.Reason.TOKEN_EXPIRED
                    ),
                    expectedOwnerUserId = event.ownerUserId
                ) ?: false
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("NavGraph", "Token-expiry session purge failed", error)
                false
            }
            if (!purged) return@collectLatest
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    if (currentRoute != null && currentRoute != Routes.LOGIN && currentRoute != Routes.INCOMING_CALL) {
        IncomingCallObserver(navController = navController)
    }

    // 群通话请求监听：把 ChatDetailViewModel 的"发起群通话"映射到路由跳转 + CallViewModel
    LaunchedEffect(Unit) {
        com.maodouchat.call.CallOrchestrator.groupCallRequests.collect { req ->
            // Drop buffered pre-logout requests after invalidateSession().
            if (req.sessionGeneration != com.maodouchat.call.CallOrchestrator.currentSessionGeneration()) {
                return@collect
            }
            // contactId 字段承载 "chatId|memberId1,memberId2,..."
            val packed = req.chatId + "|" + req.memberIds.joinToString(",")
            navController.navigate(Routes.call(packed, groupCallDisplayName, req.callType.name)) {
                launchSingleTop = true
            }
        }
    }

    // 扫一扫后"和某人创建私聊"
    LaunchedEffect(Unit) {
        com.maodouchat.call.CallOrchestrator.directChatRequests.collect { req ->
            if (req.sessionGeneration != com.maodouchat.call.CallOrchestrator.currentSessionGeneration()) {
                return@collect
            }
            // 通过 ContactsViewModel 创建/获取 1-on-1 私聊
            val app = context.applicationContext as com.maodouchat.MaodouchatApp
            val tokenManager = com.maodouchat.network.TokenManager.getInstance(app)
            val token = tokenManager.getToken().orEmpty()
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isBlank() || ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                Toast.makeText(context, sessionExpiredMsg, Toast.LENGTH_SHORT).show()
                return@collect
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            com.maodouchat.network.ApiService.createChat(liveToken, listOf(req.userId), isGroup = false, groupName = null)
                .onSuccess { chat ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@onSuccess
                    }
                    navController.navigate(Routes.chatDetail(chat.id)) { launchSingleTop = true }
                }
                .onFailure { error ->
                    android.util.Log.w("NavGraph", "createChat failed", error)
                    Toast.makeText(
                        context,
                        error.message?.takeIf { it.isNotBlank() } ?: createChatFailedMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            if (!navMotionEnabled) {
                EnterTransition.None
            } else {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
                ) + fadeIn(tween(motion.duration(MotionTokens.Emphasized)))
            }
        },
        exitTransition = {
            if (!navMotionEnabled) {
                ExitTransition.None
            } else {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
                ) + fadeOut(tween(motion.duration(MotionTokens.Emphasized)))
            }
        },
        popEnterTransition = {
            if (!navMotionEnabled) {
                EnterTransition.None
            } else {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
                ) + fadeIn(tween(motion.duration(MotionTokens.Emphasized)))
            }
        },
        popExitTransition = {
            if (!navMotionEnabled) {
                ExitTransition.None
            } else {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
                ) + fadeOut(tween(motion.duration(MotionTokens.Emphasized)))
            }
        }
    ) {
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

        // P08：群玩法域目的地见 groupPlayDestinations。
        groupPlayDestinations(navController)

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
                    when {
                        // Missed-call center rows open inbox via shared wake path.
                        item.type == "MISSED_CALL" ||
                            item.deeplink == "maodouchat:missed_calls" -> {
                            val callId = item.extra["callId"].orEmpty()
                            if (callId.isNotBlank()) {
                                com.maodouchat.notification.CallNotificationService.cancelMissedCall(
                                    context.applicationContext,
                                    callId
                                )
                            }
                            com.maodouchat.MaodouchatApp.emitOpenMissedCalls()
                            navController.popBackStack()
                        }
                        item.type == "MESSAGE" && item.deeplink == null -> {
                            val chatId = item.extra["chatId"].orEmpty()
                            if (chatId.isNotBlank()) {
                                com.maodouchat.notification.MessageNotificationService.cancelMessage(context.applicationContext, chatId)
                                navController.navigate(Routes.chatDetail(chatId)) { launchSingleTop = true }
                            }
                        }
                        item.deeplink?.startsWith("maodouchat:chat:") == true -> {
                            val chatId = item.deeplink.removePrefix("maodouchat:chat:")
                            if (chatId.isNotBlank()) {
                                // Center open should match open-chat tray dismiss.
                                com.maodouchat.notification.MessageNotificationService.cancelMessage(
                                    context.applicationContext,
                                    chatId
                                )
                                navController.navigate(Routes.chatDetail(chatId)) { launchSingleTop = true }
                            }
                        }
                        item.deeplink?.startsWith("maodouchat:ai_tasks:") == true -> {
                            val chatId = item.deeplink.removePrefix("maodouchat:ai_tasks:")
                            if (chatId.isNotBlank()) {
                                com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(
                                    context.applicationContext,
                                    chatId
                                )
                                navController.navigate(Routes.aiTasks(chatId)) { launchSingleTop = true }
                            }
                        }
                        item.deeplink?.startsWith("maodouchat:post:") == true -> {
                            val raw = item.deeplink.removePrefix("maodouchat:post:")
                            val postId = raw.substringBefore("?").trim()
                            val commentId = raw.substringAfter("?comment=", "").trim().takeIf { it.isNotBlank() }
                            if (postId.isNotBlank()) {
                                com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(
                                    context.applicationContext,
                                    postId
                                )
                                // 1.121：打开动态时将该动态的全部互动通知标记已读（角标/未读同步归零）
                                runCatching {
                                    (context.applicationContext as? com.maodouchat.MaodouchatApp)
                                        ?.notificationCenter?.markPostInteractionsRead(postId)
                                }
                                // 1.132：带评论 id 时详情页定位到该评论
                                navController.navigate(Routes.postDetail(postId, commentId)) { launchSingleTop = true }
                            }
                        }
                        item.type == "FRIEND_REQUEST" ||
                            item.deeplink == "maodouchat:contacts" -> {
                            com.maodouchat.MaodouchatApp.emitOpenContacts()
                            navController.popBackStack()
                        }
                    }
                }
            )
        }

        // P08：通话域目的地见 callDestinations。
        callDestinations(navController)

        // P08：设置域目的地见 settingsDestinations（本文件只保留装配顺序）。
        settingsDestinations(navController)
        // P08：动态/联系人域目的地见 exploreDestinations。
        exploreDestinations(navController)

        // ===== B5 新增（仅追加）：平板双栏布局 =====
        // 双栏总入口：宽屏（≥840dp 且宽≥高）时左栏列表 + 右栏会话详情；
        // 窄屏时退化为单栏会话列表（行为与 MainTab.CHATS 一致）。
        // 详情右栏由 ChatDetailListPaneRoute 内的嵌套 NavHost 渲染（见文件尾部）。
        composable(Routes.CHAT_DETAIL_LIST_PANE) {
            ChatDetailListPaneRoute(navController = navController)
        }
    }
}