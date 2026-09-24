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
// B5 新增（仅追加）：平板双栏布局
import com.maodouchat.ui.layout.AdaptiveLayout
import com.maodouchat.ui.layout.rememberAdaptiveLayoutState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.rememberNavController
import com.maodouchat.network.PublicUpdatesDto
import com.maodouchat.update.AppUpdateDownloadScheduler
import com.maodouchat.update.AppUpdatePolicy
import com.maodouchat.update.AppUpdatePromptStore
import androidx.work.WorkInfo
import com.maodouchat.navigation.AppLinkRouter
import com.maodouchat.navigation.MainTab
import com.maodouchat.navigation.Routes
import com.maodouchat.navigation.callDestinations
import com.maodouchat.navigation.exploreDestinations
import com.maodouchat.navigation.groupPlayDestinations
import com.maodouchat.navigation.settingsDestinations

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

    LaunchedEffect(appUpdateDownloading) {
        if (!appUpdateDownloading) return@LaunchedEffect
        AppUpdateDownloadScheduler.observe(context).collectLatest { info ->
            appUpdateProgress = AppUpdateDownloadScheduler.progressOf(info)
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    appUpdateDownloading = false
                    appUpdateOffer = null
                }
                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED -> {
                    appUpdateDownloading = false
                }
                else -> Unit
            }
        }
    }

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
                        appUpdateProgress = 0
                        AppUpdateDownloadScheduler.enqueue(
                            context = context,
                            apkUrl = offer.apkUrl,
                            expectedSha256 = offer.apkSha256,
                            expectedVersionCode = offer.versionCode,
                        )
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
        // P08：登录/主壳目的地见 authDestinations。
        authDestinations(navController)

        // P08：聊天域目的地见 chatDestinations。
        chatDestinations(navController)

        // P08：群玩法域目的地见 groupPlayDestinations。
        groupPlayDestinations(navController)

        // P08：搜索/通知中心目的地见 searchCenterDestinations。
        searchCenterDestinations(navController)

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