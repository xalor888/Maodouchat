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

/**
 * 路由定义
 */
object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val CHAT_DETAIL = "chat_detail/{chatId}?messageId={messageId}"
    const val GROUP_DETAIL = "group_detail/{chatId}"
    const val GROUP_EDIT = "group_detail/{chatId}/edit"
    const val GROUP_INVITE = "group_detail/{chatId}/invite"
    const val STARRED_MESSAGES = "starred_messages?chatId={chatId}"
    const val AI_TASKS = "ai_tasks/{chatId}"
    const val MEDIA_CENTER = "media_center/{chatId}"
    const val CALL = "call/{contactId}/{contactName}/{callType}"
    const val INCOMING_CALL = "incoming_call"
    const val CALL_HISTORY = "call_history"
    const val SETTINGS_ACCOUNT_SECURITY = "settings/account_security"
    const val SETTINGS_MY_REPORTS = "settings/my_reports"
    const val SETTINGS_BLOCKED_USERS = "settings/blocked_users"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_AI_PRIVACY = "settings/ai_privacy"
    const val AGENT = "agent"
    const val SETTINGS_MODERATION = "settings/moderation"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_ABOUT = "settings/about"
    // 9.253：主题编辑器（TG 式高自定义 + .attheme 导入导出）
    const val SETTINGS_THEME_EDITOR = "settings/theme_editor"
    const val SETTINGS_THEME_WORKBENCH = "settings/theme_workbench"
    const val SETTINGS_SERVER = "settings/server"
    const val WATERMARK_FORENSIC = "watermark_forensic"
    const val DEVELOPER_BOTS = "developer_bots"
    const val MY_QR_CODE = "my_qr_code"
    const val SCAN = "scan"
    const val NEARBY = "nearby"
    const val MOMENTS = "moments"
    const val AUTHOR_PROFILE = "author/{authorId}"
    const val POST_DETAIL = "post/{postId}?comment={comment}"
    const val GLOBAL_SEARCH = "global_search"
    const val NOTIFICATION_CENTER = "notification_center"
    const val PUBLIC_PROFILE = "public_profile/{username}"
    // 群玩法 B3：群投票 / 群签到+排行 / 群接龙 / 群 PK
    const val GROUP_POLL = "group_poll/{chatId}"
    const val GROUP_CHECKIN = "group_checkin/{chatId}"
    const val GROUP_CHAIN = "group_chain/{chatId}"
    const val GROUP_PK = "group_pk/{chatId}"
    // B5 新增（仅追加）：平板双栏布局路由（列表左栏 / 聊天详情右栏）
    const val CHAT_DETAIL_LIST_PANE = "chat_detail_list_pane"
    const val CHAT_DETAIL_TWO_PANE = "chat_detail_two_pane/{chatId}"

    fun authorProfile(authorId: String) = "author/${Uri.encode(authorId)}"
    // 1.132：可带 comment 查询参数（通知跳转定位到具体评论）
    fun postDetail(postId: String, commentId: String? = null) =
        "post/${Uri.encode(postId)}?comment=${Uri.encode(commentId.orEmpty())}"
    fun publicProfile(username: String) = "public_profile/${Uri.encode(username)}"
    fun groupPoll(chatId: String) = "group_poll/${Uri.encode(chatId)}"
    fun groupCheckin(chatId: String) = "group_checkin/${Uri.encode(chatId)}"
    fun groupChain(chatId: String) = "group_chain/${Uri.encode(chatId)}"
    fun groupPk(chatId: String) = "group_pk/${Uri.encode(chatId)}"
    // B5 新增（仅追加）：双栏「详情」路由 builder（嵌套 NavHost 内使用）
    fun chatDetailTwoPane(chatId: String) = "chat_detail_two_pane/${Uri.encode(chatId)}"

    fun chatDetail(chatId: String, messageId: String? = null): String {
        val base = "chat_detail/${Uri.encode(chatId)}"
        return messageId?.takeIf(String::isNotBlank)?.let { "$base?messageId=${Uri.encode(it)}" } ?: base
    }
    fun groupDetail(chatId: String) = "group_detail/${Uri.encode(chatId)}"
    fun groupEdit(chatId: String) = "group_detail/${Uri.encode(chatId)}/edit"
    fun groupInvite(chatId: String) = "group_detail/${Uri.encode(chatId)}/invite"
    fun starredMessages(chatId: String? = null): String {
        val id = chatId?.takeIf { it.isNotBlank() }?.let { Uri.encode(it) }.orEmpty()
        return if (id.isEmpty()) "starred_messages" else "starred_messages?chatId=$id"
    }
    fun aiTasks(chatId: String) = "ai_tasks/${Uri.encode(chatId)}"
    fun mediaCenter(chatId: String) = "media_center/${Uri.encode(chatId)}"
    fun call(contactId: String, contactName: String, callType: String = "AUDIO") =
        "call/${Uri.encode(contactId)}/${Uri.encode(contactName)}/${Uri.encode(callType)}"
    fun incomingCall() = INCOMING_CALL
}

/**
 * 底部导航 Tab 索引
 */
object MainTab {
    const val CHATS = 0
    const val CONTACTS = 1
    const val EXPLORE = 2
    const val SETTINGS = 3
}

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

        // 通话页面
        composable(
            route = Routes.CALL,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                navArgument("contactName") { type = NavType.StringType },
                navArgument("callType") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val contactId = Uri.decode(backStackEntry.arguments?.getString("contactId") ?: "")
            val contactName = Uri.decode(backStackEntry.arguments?.getString("contactName") ?: "")
            val callTypeStr = Uri.decode(backStackEntry.arguments?.getString("callType") ?: "AUDIO")
            val callType = try { CallType.valueOf(callTypeStr) } catch (_: Exception) { CallType.AUDIO }

            val callViewModel: CallViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val callState by callViewModel.uiState.collectAsStateWithLifecycle()

            // 群通话解析：contactId="chatId|memberId1,memberId2,..."
            val isGroupCall = contactId.contains("|")
            val effectiveContactId = if (isGroupCall) contactId.substringBefore("|") else contactId
            val memberIds = if (isGroupCall) contactId.substringAfter("|").split(",").filter { it.isNotBlank() } else emptyList()

            val requiredPermissions = remember(callType) {
                com.maodouchat.webrtc.CallReliabilityPolicy.requiredPermissions(callType).map { permission ->
                    when (permission) {
                        com.maodouchat.webrtc.CallMediaPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                        com.maodouchat.webrtc.CallMediaPermission.CAMERA -> Manifest.permission.CAMERA
                    }
                }.toTypedArray()
            }
            val voiceCallPermissionMsg = stringResource(R.string.chat_permission_voice_call)
            val videoCallPermissionMsg = stringResource(R.string.chat_permission_video_call)
            val deniedCallPermissionMsg =
                if (callType == CallType.VIDEO) videoCallPermissionMsg else voiceCallPermissionMsg
            var callPermissionsGranted by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val callPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                callPermissionsGranted = requiredPermissions.all { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }
            }

            LaunchedEffect(requiredPermissions.contentHashCode()) {
                val granted = requiredPermissions.all { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }
                if (granted) callPermissionsGranted = true else callPermissionLauncher.launch(requiredPermissions)
            }

            // 仅在真正 IDLE 状态才发起通话，避免进程恢复后重复发起
            LaunchedEffect(callPermissionsGranted) {
                if (callPermissionsGranted == true && callViewModel.uiState.value.callState == com.maodouchat.webrtc.CallState.IDLE) {
                    if (isGroupCall && memberIds.isNotEmpty()) {
                        callViewModel.startGroupCall(effectiveContactId, memberIds, callType)
                    } else {
                        callViewModel.startCall(contactId, contactName, null, callType)
                    }
                } else if (callPermissionsGranted == false) {
                    Toast.makeText(
                        context,
                        deniedCallPermissionMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                    navController.popBackStack()
                }
            }

            // 系统返回键也走挂断流程 — 通知对端并退出通话页；避免直接 popBackStack 导致对端仍振铃
            BackHandler {
                callViewModel.hangUp()
                navController.popBackStack()
            }

            // 通话结束（对方挂断/网络中断/超时）后自动返回，延迟 800ms 让用户看到"通话已结束"
            LaunchedEffect(callState.callState) {
                if (callState.callState == CallState.DISCONNECTED) {
                    kotlinx.coroutines.delay(800)
                    navController.popBackStack()
                }
            }

            CallScreen(
                contactName = contactName,
                callType = callType,
                isIncoming = callState.isIncoming,
                isGroupCall = callState.isGroupCall,
                callState = callState.callState,
                duration = callState.duration,
                isInitializing = callState.isInitializing,
                networkReconnecting = callState.networkReconnecting,
                networkQuality = callState.networkStats,
                iceStunOnly = callState.iceStunOnly,
                availableAudioRoutes = callState.availableAudioRoutes,
                selectedAudioRoute = callState.selectedAudioRoute,
                groupParticipants = callState.groupParticipants,
                errorMessage = callState.errorMessage,
                nativeDownloadProgress = callState.nativeDownloadProgress,
                onDismissError = { callViewModel.clearError() },
                onHangUp = {
                    callViewModel.hangUp()
                    navController.popBackStack()
                },
                onToggleMute = { callViewModel.toggleMute(it) },
                onToggleVideo = { callViewModel.toggleVideo(it) },
                onSwitchCamera = { callViewModel.switchCamera() },
                onSelectAudioRoute = { callViewModel.selectAudioRoute(it) },
                onLocalRendererReady = { callViewModel.attachLocalRenderer(it) },
                onRemoteRendererReady = { callViewModel.attachRemoteRenderer(it) },
                onLocalRendererReleased = { callViewModel.detachLocalRenderer(it) },
                onRemoteRendererReleased = { callViewModel.detachRemoteRenderer(it) },
                onGroupRemoteRendererReady = { userId, renderer -> callViewModel.attachGroupRemoteRenderer(userId, renderer) },
                onGroupRemoteRendererReleased = { userId, renderer -> callViewModel.detachGroupRemoteRenderer(userId, renderer) }
            )
        }

        composable(Routes.INCOMING_CALL) {
            IncomingCallRoute(navController = navController)
        }

        // 1.29：通话记录
        composable(Routes.CALL_HISTORY) {
            com.maodouchat.ui.screen.call.CallHistoryScreen(
                onBack = { navController.popBackStack() },
                onCall = { contactId, contactName, callType ->
                    navController.navigate(Routes.call(contactId, contactName, callType))
                },
                // 1.366：长按单条「查看资料」跳作者主页
                onOpenProfile = { userId ->
                    if (userId.isNotBlank()) navController.navigate(Routes.authorProfile(userId))
                }
            )
        }

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
        composable(Routes.MY_QR_CODE) {
            com.maodouchat.ui.screen.contacts.MyQrCodeScreen(
                onBack = { navController.popBackStack() },
                onOpenScan = { navController.navigate(Routes.SCAN) }
            )
        }
        composable(Routes.SCAN) {
            com.maodouchat.ui.screen.contacts.ScanScreen(
                onBack = { navController.popBackStack() },
                onAddContact = { user ->
                    com.maodouchat.call.CallOrchestrator.requestDirectChat(user.id, user.name)
                },
                onOpenChat = { chatId ->
                    navController.navigate(Routes.chatDetail(chatId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.NEARBY) {
            androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.MOMENTS) {
            com.maodouchat.ui.screen.explore.MomentsScreen(
                onBack = { navController.popBackStack() },
                onOpenAuthor = { authorId -> navController.navigate(Routes.authorProfile(authorId)) },
                onOpenPost = { postId -> navController.navigate(Routes.postDetail(postId)) }
            )
        }
        composable(
            route = Routes.AUTHOR_PROFILE,
            arguments = listOf(navArgument("authorId") { type = NavType.StringType })
        ) { entry ->
            val authorId = Uri.decode(entry.arguments?.getString("authorId") ?: "")
            com.maodouchat.ui.screen.explore.AuthorProfileScreen(
                authorId = authorId,
                onBack = { navController.popBackStack() },
                onOpenChat = { id -> com.maodouchat.call.CallOrchestrator.requestDirectChat(id, "") },
                onOpenPost = { postId -> navController.navigate(Routes.postDetail(postId)) }
            )
        }
        composable(
            route = Routes.POST_DETAIL,
            arguments = listOf(
                navArgument("postId") { type = NavType.StringType },
                // 1.132：通知跳转定位到具体评论
                navArgument("comment") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val postId = Uri.decode(entry.arguments?.getString("postId") ?: "")
            val commentId = entry.arguments?.getString("comment")?.takeIf { it.isNotBlank() }
            com.maodouchat.ui.screen.explore.PostDetailScreen(
                postId = postId,
                initialCommentId = commentId,
                onBack = { navController.popBackStack() },
                // 1.107：详情页作者行 → 作者主页
                onOpenAuthor = { authorId -> navController.navigate(Routes.authorProfile(authorId)) { launchSingleTop = true } }
            )
        }
        composable(
            route = Routes.PUBLIC_PROFILE,
            arguments = listOf(navArgument("username") { type = NavType.StringType }),
            // P08：深链模式唯一事实源见 AppLinkRouter.publicProfileDeepLinkPatterns。
            deepLinks = AppLinkRouter.publicProfileDeepLinkPatterns.map { pattern ->
                navDeepLink { uriPattern = pattern }
            }
        ) { entry ->
            val username = Uri.decode(entry.arguments?.getString("username") ?: "")
            if (username.isNotBlank()) {
                PublicProfileScreen(
                    username = username,
                    onBack = { navController.popBackStack() },
                    onStartChat = { userId ->
                        com.maodouchat.call.CallOrchestrator.requestDirectChat(userId, "")
                        navController.popBackStack()
                    }
                )
            }
        }

        // ===== B5 新增（仅追加）：平板双栏布局 =====
        // 双栏总入口：宽屏（≥840dp 且宽≥高）时左栏列表 + 右栏会话详情；
        // 窄屏时退化为单栏会话列表（行为与 MainTab.CHATS 一致）。
        // 详情右栏由 ChatDetailListPaneRoute 内的嵌套 NavHost 渲染（见文件尾部）。
        composable(Routes.CHAT_DETAIL_LIST_PANE) {
            ChatDetailListPaneRoute(navController = navController)
        }
    }
}