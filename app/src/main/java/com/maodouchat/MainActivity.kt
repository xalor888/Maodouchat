package com.maodouchat

import com.maodouchat.util.RuntimeFlags
import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.maodouchat.ui.navigation.MaodouchatNavGraph
import com.maodouchat.ui.navigation.Routes
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.network.TokenManager
import com.maodouchat.util.AppNotifier
import com.maodouchat.util.AppLocaleManager
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.FakeChatManager
import com.maodouchat.security.ScreenSecureManager
import com.maodouchat.security.ScreenSecurePolicy
import com.maodouchat.security.SecretChatSession
import com.maodouchat.ui.screen.lock.FakeChatScreen
import com.maodouchat.ui.screen.lock.PasscodeLockScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    private val notificationTarget = MutableStateFlow<NotificationTarget?>(null)
    private var showAppLock by mutableStateOf(false)
    /** 假聊天模式：启用后冷启动/回前台先展示假聊天界面，密码解锁后才进入真实 App */
    private var showFakeChat by mutableStateOf(false)
    /** 当前是否位于含消息内容的界面（由 Nav 回写） */
    private var onChatSurface by mutableStateOf(false)
    /** 当前路由是否落在已开启密聊的会话表面 */
    private var onSecretChatSurface by mutableStateOf(false)
    /** 当前是否在会话 PIN 锁（ChatLockGate）表面；PIN 属敏感信息，需强制 FLAG_SECURE */
    private var onChatLockSurface by mutableStateOf(false)
    /** 8.48：当前导航路由（供通话 PiP 判断） */
    @Volatile
    private var currentNavRoute: String? = null

    // 多权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* 权限结果回调 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNotificationIntent(intent)
        requestPermissions()
        enableEdgeToEdge()
        showAppLock = AppLockManager.shouldLock(this)
        showFakeChat = FakeChatManager.shouldShowFake(this)
        refreshWindowPrivacy()
        observeCallLockScreenFlags()
        // B5 新增（仅追加）：会话失效/账号切换时兜底移除悬浮球，避免跨账号残留窗口。
        // 悬浮球仅承载通用小球，不展示会话内容；此处只负责生命周期清理。
        observeFloatingBallLifespan()
        // B2 新增（仅追加）：密聊防泄漏看门狗（SIM 变更防护，幂等注册周期任务）。
        com.maodouchat.security.SecretSurfaceWatchdogWorker.schedule(this)
        // 8.29 新增（仅追加）：断线窗口消息收敛（WS 断开/Doze 期间按游标拉增量消息 + 补通知）。
        com.maodouchat.sync.BacklogSyncWorker.schedule(this)

        // 系统认证会暂停 Activity；锁屏已显示时不能把认证弹窗误记成普通后台离开。
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                if (!showAppLock) AppLockManager.noteBackground(this@MainActivity)
                // 假聊天模式启用即视为「离开」：回前台需重新走假界面拦截
                FakeChatManager.noteBackground(this@MainActivity)
                // 后台期间若开启 App 锁、假聊天、全局防截屏或密聊表面，保持窗口安全
                if (
                    AppLockManager.isEnabled(this@MainActivity) ||
                    FakeChatManager.isEnabled(this@MainActivity) ||
                    ScreenSecureManager.isEnabled(this@MainActivity) ||
                    onSecretChatSurface ||
                    SecretChatSession.hasActiveSecretSurface()
                ) {
                    updateWindowPrivacy(true)
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                // 8.32 修复 F2：App 真正离开前台（onStop，非权限弹窗/指纹认证等瞬时遮挡）时清空
                // activeChatId——否则停留在聊天页期间新消息既不弹通知也不计未读（零提醒）。
                // onPause 会在系统认证/权限框/通知栏下拉时误触发，此时仍是前台，不应清空
                //（否则聊天页被指纹弹窗遮挡时到的新消息会被误判为后台消息而弹托盘通知）。
                // ChatDetailViewModel 回前台会重新设置。
                com.maodouchat.MaodouchatApp.activeChatId = null
            }
            override fun onResume(owner: LifecycleOwner) {
                if (!showFakeChat && FakeChatManager.shouldShowFake(this@MainActivity)) {
                    showFakeChat = true
                }
                if (!showAppLock) {
                    if (AppLockManager.shouldLock(this@MainActivity)) {
                        showAppLock = true
                    } else {
                        AppLockManager.markUnlocked(this@MainActivity)
                    }
                }
                refreshWindowPrivacy()
            }
        })

        setContent {
            MaodouchatTheme {
                Surface(color = Background, modifier = Modifier.fillMaxSize()) {
                    when {
                        showFakeChat -> {
                            // 假聊天界面在外层：不暴露真实 App 的锁屏提示，解锁后再按需走 App 锁
                            FakeChatScreen(
                                onUnlocked = {
                                    FakeChatManager.markUnlocked(this@MainActivity)
                                    showFakeChat = false
                                    if (AppLockManager.shouldLock(this@MainActivity)) {
                                        showAppLock = true
                                    } else {
                                        AppLockManager.markUnlocked(this@MainActivity)
                                    }
                                    refreshWindowPrivacy()
                                },
                                onFailed = { /* 输错密码留在假界面，可重试 */ }
                            )
                        }
                        showAppLock -> {
                            PasscodeLockScreen(
                                onUnlocked = {
                                    AppLockManager.markUnlocked(this@MainActivity)
                                    showAppLock = false
                                    refreshWindowPrivacy()
                                },
                                onFailed = { /* 保留锁屏，用户可重试 */ }
                            )
                        }
                        else -> AppNav()
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AppNav() {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route
        // 8.48：记录当前路由供 onUserLeaveHint 判断是否在通话（进 PiP 的前提）
        currentNavRoute = route
        // 首帧渲染完成后通知系统，让 startup 指标更准确
        LaunchedEffect(Unit) {
            com.maodouchat.perf.StartupTracer.mark("firstFrame")
            kotlinx.coroutines.delay(100)
            reportFullyDrawn()
            com.maodouchat.perf.StartupTracer.fullyDrawn()
        }
        // key 含 sessionGeneration：账号切换（route 不变但数据库归属变化）时重新查库，
        // 避免旧账号的密聊 FLAG_SECURE 残留或新账号密聊未被保护。
        LaunchedEffect(route, MaodouchatApp.currentSessionGeneration()) {
            onChatSurface = ScreenSecurePolicy.isChatSurfaceRoute(route)
            val chatId = ScreenSecurePolicy.extractChatIdFromRoute(route)
            // 失败闭合：进入聊天 surface 立即乐观加 FLAG_SECURE，避免异步查库间隙密聊内容被截图 /
            // 进入最近任务缩略图。即便全局截屏防护关闭，密聊也必须在查库确认前就进入安全态；
            // 查库确认非密聊后再降级（去掉 FLAG_SECURE）。
            onSecretChatSurface = chatId != null
            onChatLockSurface = false
            refreshWindowPrivacy()
            // 先查库再清空 surface，避免异步查库间隙 FLAG_SECURE 短暂丢失导致密聊内容在最近任务缩略图泄露
            val isSecret = if (chatId != null) {
                withContext(Dispatchers.IO) {
                    try {
                        (application as MaodouchatApp).database.secretChatDao().isSecret(chatId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
            } else false
            // 8.42：路由变化只清 surface 标记（FLAG_SECURE 释放依据），不删磁盘/索引——
            // 此前 clearAllSurfaces 会销毁仍在返回栈中的其它密聊会话的媒体缓存与搜索索引，
            // 返回 A 时图片需重新下载、密聊消息从搜索中消失。磁盘清除由离开单 surface /
            // 登出 / SIM 变更 / 密聊禁用承担。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SecretChatSession.clearSurfaceMarkers()
            }
            if (isSecret && chatId != null) {
                SecretChatSession.markSurfaceActive(chatId)
            }
            onSecretChatSurface = isSecret
            refreshWindowPrivacy()
        }
        LaunchedEffect(navController) {
            notificationTarget.filterNotNull().collect { target ->
                while (!TokenManager.getInstance(this@MainActivity).isLoggedIn() ||
                    navController.currentDestination?.route == null ||
                    navController.currentDestination?.route == Routes.LOGIN
                ) {
                    if (target.sessionGeneration != MaodouchatApp.currentSessionGeneration()) {
                        notificationTarget.value = null
                        return@collect
                    }
                    // 8.34 修复：PublicProfile 深链无账号归属概念（公开资料页），登录前到达时
                    // 捕获的 ownerUserId 为空，登录后不得被 owner 校验丢弃（此前外部深链 100% 静默丢失）
                    if (target !is NotificationTarget.PublicProfile) {
                        TokenManager.getInstance(this@MainActivity).getUserId()
                            ?.takeIf(String::isNotBlank)
                            ?.let { liveUserId ->
                                if (target.ownerUserId != liveUserId) {
                                    notificationTarget.value = null
                                    return@collect
                                }
                            }
                    }
                    delay(250)
                }
                // Logout bumps sessionGeneration — drop deep-links from the prior account.
                if (
                    target.sessionGeneration != MaodouchatApp.currentSessionGeneration() ||
                    (target !is NotificationTarget.PublicProfile &&
                        target.ownerUserId != TokenManager.getInstance(this@MainActivity).getUserId())
                ) {
                    notificationTarget.value = null
                    return@collect
                }
                when (target) {
                    is NotificationTarget.Chat -> navController.navigate(Routes.chatDetail(target.id, target.messageId)) { launchSingleTop = true }
                    is NotificationTarget.AiTasks -> navController.navigate(Routes.aiTasks(target.chatId)) { launchSingleTop = true }
                    is NotificationTarget.Post -> navController.navigate(Routes.postDetail(target.id)) { launchSingleTop = true }
                    is NotificationTarget.PublicProfile -> navController.navigate(Routes.publicProfile(target.username)) { launchSingleTop = true }
                }
                // 8.47 修复：仅当仍是刚处理的目标时才清空——等待登录/导航期间新通知到达会
                // 覆盖 value（TargetB）；无条件置 null 会把尚未处理的 TargetB 一并丢弃。
                if (notificationTarget.value == target) {
                    notificationTarget.value = null
                }
            }
        }
        MaodouchatNavGraph(navController = navController)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    // 8.48：通话中画中画（PiP）——按 HOME 时若在通话路由，进入 PiP 小窗保持通话可见
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val route = currentNavRoute
        val inCall = route == Routes.CALL || route == Routes.INCOMING_CALL
        if (inCall && isInPictureInPictureMode) return
        if (inCall && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching {
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                }
                enterPictureInPictureMode(builder.build())
            }
        }
    }

    private fun consumeNotificationIntent(intent: Intent) {
        // 调用来源校验（安全加固）：只接受系统投递（通知/Telecom，callingPackage 为 null）
        // 或本应用发起的 intent。第三方应用显式携带 ANSWER_CALL / EXTRA_OPEN_CHAT_ID 等
        // extra 启动本 Activity 可伪造来电、锁屏展示内容或取消通知 —— 一律丢弃。
        val caller = callingPackage ?: callingActivity?.packageName
        if (caller != null && caller != packageName) {
            clearNotificationExtras(intent)
            // 8.34 修复：外部调用者的合法 ACTION_VIEW 深链必须放行——浏览器/系统 resolver
            // 打开 chat.mdou.me/u/{username} 或 maodouchat://u/{username} 时 callingPackage
            // 恒为外部包，此前直接 return 导致 manifest BROWSABLE 外部深链 100% 失效。
            // 通知 extra 已清（防注入面），深链数据继续走下方白名单字符校验；非 VIEW 仍丢弃。
            if (intent.action != android.content.Intent.ACTION_VIEW || intent.data == null) {
                intent.data = null
                return
            }
        }
        // 深链接：maodouchat://u/<username> 或 https://chat.mdou.me/u/<username> → 公开资料页
        val data = intent.data
        if (intent.action == android.content.Intent.ACTION_VIEW && data != null) {
            val path = data.path.orEmpty()
            val username = when {
                data.scheme == "maodouchat" && data.host == "u" -> path.removePrefix("/").trim()
                data.scheme == "https" && data.host == "chat.mdou.me" && path.startsWith("/u/") ->
                    path.removePrefix("/u/").trim()
                else -> ""
            }
            // 规范化：只接受单路径段（拒绝 a/b 这类含额外段的值），且仅字母数字与部分安全字符
            val normalized = username
                .substringBefore('/')
                .substringBefore('?')
                .take(64)
                .takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' } }
            if (normalized != null) {
                val ownerUserId = TokenManager.getInstance(this).getUserId().orEmpty()
                notificationTarget.value = NotificationTarget.PublicProfile(
                    // 8.34 修复：跳转用规范化后的用户名（此前用原始值，校验可被
                    // maodouchat://u/alice/bob 这类值旁路，直达 404 请求）
                    username = normalized,
                    sessionGeneration = MaodouchatApp.currentSessionGeneration(),
                    ownerUserId = ownerUserId,
                )
                intent.data = null
                return
            }
        }
        // ConnectionService 接听 / 来电 intent：保持屏幕常亮并唤醒来电流程
        val telecomAction = intent.action
        if (telecomAction == "com.maodouchat.ANSWER_CALL" || telecomAction == "com.maodouchat.INCOMING_CALL") {
            applyCallLockScreenFlags(enabled = true)
            val callId = intent.getStringExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_CALL_ID).orEmpty()
            if (callId.isNotBlank()) {
                AppNotifier.cancelIncomingCall(this, callId)
            }
            MaodouchatApp.emitIncomingCallWake(
                IncomingCallWake(
                    callId = callId,
                    senderId = "",
                    isVideo = intent.getBooleanExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_IS_VIDEO, false),
                    // 8.56：系统 Telecom「接听」≠「来电拉起」——标记自动接听，应用内不再要求二次点击
                    autoAnswer = telecomAction == "com.maodouchat.ANSWER_CALL",
                )
            )
            intent.removeExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_CALL_ID)
            intent.removeExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_CALLER_NAME)
            intent.removeExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_IS_VIDEO)
        }
        val chatId = intent.getStringExtra(AppNotifier.EXTRA_OPEN_CHAT_ID)?.takeIf(String::isNotBlank)
        // 8.41：消息「稍后提醒」点击 → 打开聊天后高亮原消息
        val messageId = intent.getStringExtra(AppNotifier.EXTRA_OPEN_MESSAGE_ID)?.takeIf(String::isNotBlank)
        val aiTasksChatId = intent.getStringExtra(AppNotifier.EXTRA_OPEN_AI_TASKS_CHAT_ID)?.takeIf(String::isNotBlank)
        val postId = intent.getStringExtra(AppNotifier.EXTRA_OPEN_POST_ID)?.takeIf(String::isNotBlank)
        val openIncomingCall = intent.getBooleanExtra(AppNotifier.EXTRA_OPEN_INCOMING_CALL, false)
        val openMissedCalls = intent.getBooleanExtra(AppNotifier.EXTRA_OPEN_MISSED_CALL, false)
        val openContacts = intent.getBooleanExtra(AppNotifier.EXTRA_OPEN_CONTACTS, false)
        val notificationOwnerUserId = intent
            .getStringExtra(AppNotifier.EXTRA_NOTIFICATION_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
        val hasNotificationTarget = chatId != null || aiTasksChatId != null || postId != null ||
            openIncomingCall || openMissedCalls || openContacts
        if (
            hasNotificationTarget &&
            !com.maodouchat.notification.NotificationIntentPolicy.belongsToCurrentAccount(
                notificationOwnerUserId = notificationOwnerUserId,
                currentUserId = TokenManager.getInstance(this).getUserId(),
                sessionPurgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
            )
        ) {
            notificationTarget.value = null
            clearNotificationExtras(intent)
            return
        }
        if (openIncomingCall) {
            // FCM payload has no SDP — wake observer to poll /api/signaling/pending?offersOnly=true
            // Lock-screen / full-screen intent: keep screen on while user answers.
            // Cleared when CallForegroundService stops (see observeCallLockScreenFlags).
            applyCallLockScreenFlags(enabled = true)
            val wakeCallId = intent.getStringExtra(AppNotifier.EXTRA_INCOMING_CALL_ID).orEmpty()
            // Ongoing FCM call trays often ignore autoCancel; drop shade entry as soon as
            // the user opened the app for this call (poll / CallScreen still proceed).
            if (wakeCallId.isNotBlank()) {
                AppNotifier.cancelIncomingCall(this, wakeCallId)
            }
            MaodouchatApp.emitIncomingCallWake(
                IncomingCallWake(
                    callId = wakeCallId,
                    senderId = intent.getStringExtra(AppNotifier.EXTRA_INCOMING_CALL_SENDER_ID).orEmpty(),
                    isVideo = intent.getBooleanExtra(AppNotifier.EXTRA_INCOMING_CALL_VIDEO, false),
                )
            )
        }
        if (openMissedCalls) {
            // Tray autoCancel is unreliable for some OEMs; clear shade before list marks read.
            // Without a specific callId, cancelAll is too broad — ChatList markMissedCallsRead
            // cancels per-id; here we only emit open (per-id cancel happens after list loads).
            MaodouchatApp.emitOpenMissedCalls()
        }
        if (openContacts) {
            AppNotifier.cancelAllFriendRequests(this)
            MaodouchatApp.emitOpenContacts()
        }
        // Drop tray immediately on tap so badge/shade clear before the target screen mounts.
        // Center mark-read still happens in ChatDetail / AiTasks / PostDetail screens.
        val sessionGen = MaodouchatApp.currentSessionGeneration()
        when {
            aiTasksChatId != null -> {
                AppNotifier.cancelAiTaskRemindersForChat(this, aiTasksChatId)
                notificationTarget.value = NotificationTarget.AiTasks(aiTasksChatId, sessionGen, notificationOwnerUserId.orEmpty())
            }
            chatId != null -> {
                AppNotifier.cancelMessage(this, chatId)
                notificationTarget.value = NotificationTarget.Chat(chatId, sessionGen, notificationOwnerUserId.orEmpty(), messageId)
            }
            postId != null -> {
                AppNotifier.cancelPostInteraction(this, postId)
                notificationTarget.value = NotificationTarget.Post(postId, sessionGen, notificationOwnerUserId.orEmpty())
            }
            else -> notificationTarget.value = null
        }
        clearNotificationExtras(intent)
    }

    private fun clearNotificationExtras(intent: Intent?) {
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_CHAT_ID)
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_MESSAGE_ID)
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_AI_TASKS_CHAT_ID)
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_POST_ID)
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_INCOMING_CALL)
        intent?.removeExtra(AppNotifier.EXTRA_INCOMING_CALL_ID)
        intent?.removeExtra(AppNotifier.EXTRA_INCOMING_CALL_VIDEO)
        intent?.removeExtra(AppNotifier.EXTRA_INCOMING_CALL_SENDER_ID)
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_MISSED_CALL)
        intent?.removeExtra(AppNotifier.EXTRA_OPEN_CONTACTS)
        intent?.removeExtra(AppNotifier.EXTRA_NOTIFICATION_OWNER_USER_ID)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        // 通知权限（Android 13+）。录音/相机权限在具体功能入口按需请求。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun refreshWindowPrivacy() {
        val secure = ScreenSecurePolicy.shouldSecureWindow(
            appLockShowing = showAppLock,
            globalEnabled = ScreenSecureManager.isEnabled(this),
            onChatSurface = onChatSurface,
            secretChatSurfaceActive = onSecretChatSurface || SecretChatSession.hasActiveSecretSurface(),
            chatLockSurfaceActive = onChatLockSurface
        ) || showFakeChat
        updateWindowPrivacy(secure)
    }

    /** Detail screens call this after toggling 密聊 so FLAG_SECURE updates without re-nav. */
    fun notifySecretChatSurfaceChanged(chatId: String, isSecret: Boolean) {
        if (isSecret) SecretChatSession.markSurfaceActive(chatId)
        else SecretChatSession.markSurfaceInactive(chatId, this)
        onSecretChatSurface = SecretChatSession.hasActiveSecretSurface()
        refreshWindowPrivacy()
    }

    /** ChatLockGate 显示/隐藏时调用：PIN 输入期间强制 FLAG_SECURE，即便全局开关关闭、也非密聊。 */
    fun notifyChatLockSurfaceChanged(active: Boolean) {
        onChatLockSurface = active
        refreshWindowPrivacy()
    }

    private fun updateWindowPrivacy(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Hide task snapshot in recents while secure surfaces are active (secret / chat lock).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val hideRecents = secure && RuntimeFlags.isEnabled(this, RuntimeFlags.RECENTS_EXCLUSION)
                am.appTasks.firstOrNull()?.setExcludeFromRecents(hideRecents)
            }
        }
    }

    /**
     * Full-screen / lock-screen incoming call needs the activity above keyguard while
     * ringing or in-call. Must be cleared after the call ends so chat content does not
     * remain visible over the lock screen (privacy).
     */
    private fun applyCallLockScreenFlags(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        } else {
            @Suppress("DEPRECATION")
            if (enabled) {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    /**
     * Keep keyguard-bypass while an incoming ring or active call is present.
     * Drop flags when both coordinator pending and foreground service are idle
     * so chat content cannot stay above the lock screen after hang-up/missed.
     */
    private fun observeCallLockScreenFlags() {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var pollJob: kotlinx.coroutines.Job? = null
            override fun onStart(owner: LifecycleOwner) {
                pollJob?.cancel()
                pollJob = lifecycleScope.launch {
                    var heldForCall = false
                    while (true) {
                        val activeService = com.maodouchat.service.CallForegroundService
                            .getActiveCallId()
                            .isNotBlank()
                        val pendingRing = com.maodouchat.call.IncomingCallCoordinator
                            .peekPending() != null
                        val needFlags = activeService || pendingRing
                        if (needFlags) {
                            applyCallLockScreenFlags(true)
                            heldForCall = true
                        } else if (heldForCall) {
                            applyCallLockScreenFlags(false)
                            heldForCall = false
                        }
                        delay(500)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                pollJob?.cancel()
                pollJob = null
                val activeService = com.maodouchat.service.CallForegroundService
                    .getActiveCallId()
                    .isNotBlank()
                val pendingRing = com.maodouchat.call.IncomingCallCoordinator.peekPending() != null
                if (!activeService && !pendingRing) {
                    applyCallLockScreenFlags(false)
                }
            }
        })
    }

    /**
     * B5 新增（仅追加）：会话失效/账号切换（sessionGeneration 变化）时移除悬浮球，
     * 保证悬浮球不跨账号残留。悬浮球不承载会话内容，密聊/锁定门禁由其打开路径
     * （MainActivity 既有 App 锁/假聊天/密聊流程）在进入界面时执行。
     */
    private fun observeFloatingBallLifespan() {
        lifecycleScope.launch {
            var lastGeneration = MaodouchatApp.currentSessionGeneration()
            // 8.40：冷启动也按账号设置恢复悬浮球（此前仅代际变化时处理，进程重启后永不恢复）
            if (com.maodouchat.floating.FloatingBallController.isEnabled(this@MainActivity)) {
                com.maodouchat.floating.FloatingBallController.start(this@MainActivity)
            }
            while (true) {
                val current = MaodouchatApp.currentSessionGeneration()
                if (current != lastGeneration) {
                    lastGeneration = current
                    com.maodouchat.floating.FloatingBallController.stop(this@MainActivity)
                    // 8.40：账号切换后按新账号的设置恢复悬浮球（此前只 stop 不 start，
                    // 重启进程/切换账号后设置仍显示「开」但悬浮球永不出现）
                    if (com.maodouchat.floating.FloatingBallController.isEnabled(this@MainActivity)) {
                        com.maodouchat.floating.FloatingBallController.start(this@MainActivity)
                    }
                }
                delay(1000)
            }
        }
    }

    private sealed interface NotificationTarget {
        val sessionGeneration: Long
        val ownerUserId: String
        data class Chat(
            val id: String,
            override val sessionGeneration: Long,
            override val ownerUserId: String,
            /** 8.41：消息「稍后提醒」点击后打开聊天并高亮该消息。 */
            val messageId: String? = null,
        ) : NotificationTarget
        data class AiTasks(
            val chatId: String,
            override val sessionGeneration: Long,
            override val ownerUserId: String,
        ) : NotificationTarget
        data class Post(
            val id: String,
            override val sessionGeneration: Long,
            override val ownerUserId: String,
        ) : NotificationTarget
        /** 深链接打开公开资料页（maodouchat://u/<username> 或 https://chat.mdou.me/u/<username>）。 */
        data class PublicProfile(
            val username: String,
            override val sessionGeneration: Long,
            override val ownerUserId: String,
        ) : NotificationTarget
    }
}
