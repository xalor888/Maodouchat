package com.maodouchat.ui.screen.chatlist


import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled
import com.maodouchat.ui.theme.LocalMotionSettings

/** 1.54：底部导航「会话」未读角标总计数（ChatListViewModel 推送，BottomNavBar 订阅）。 */
object UnreadBadgeStore {
    val totalUnread = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/** 1.112：底部导航「动态」未读互动角标（POST_INTERACTION 未读数，ChatListViewModel 推送）。 */
object ExploreBadgeStore {
    val count = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/** Chat list (recovered): NavGraph API + folders + secret gates + public/status runtime sync. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
fun ChatListScreen(
    onChatClick: (String) -> Unit,
    onOpenGroupDetail: (String) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenNotificationCenter: () -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenScan: () -> Unit,
    openMissedCallsRequest: Long = 0L,
    onVoiceCall: (String, String) -> Unit = { _, _ -> },
    onVideoCall: (String, String) -> Unit = { _, _ -> },
    // 1.185：长按菜单「查看共享媒体」
    onOpenMediaCenter: (String) -> Unit = {},
    // 1.215：长按菜单「查看收藏」
    onOpenStarredMessages: (String) -> Unit = {},
    // 1.251：长按菜单「查看资料」（单聊打开作者主页）
    onOpenProfile: (String) -> Unit = {},
    viewModel: ChatListViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notifUnread by viewModel.notificationCenterUnread.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var publicBanner by remember { mutableStateOf<String?>(null) }
    var menuChat by remember { mutableStateOf<Chat?>(null) }
    // 1.373：多选批量删除确认（防止误触批量清空）
    var showBatchDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    // 9.286：第三方服务器提醒弹窗
    var showThirdPartyServerDialog by rememberSaveable { mutableStateOf(false) }
    // 1.31：会话列表长按菜单「临时静音至」目标会话
    var silentUntilChat by remember { mutableStateOf<Chat?>(null) }
    var showMissedCallsSheet by rememberSaveable { mutableStateOf(false) }
    var showFolderManager by rememberSaveable { mutableStateOf(false) }
    var showCreateFolder by rememberSaveable { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var createFolderName by remember { mutableStateOf("") }
    var createFolderError by remember { mutableStateOf<String?>(null) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    var renameFolderName by remember { mutableStateOf("") }
    var renameFolderError by remember { mutableStateOf<String?>(null) }
    var folderMoveChat by remember { mutableStateOf<Chat?>(null) }
    // 1.171：确认清空本地聊天记录的会话
    var clearHistoryChat by remember { mutableStateOf<Chat?>(null) }
    // 8.47：首次登录引导（账号隔离，展示后不再弹）——登录成功进主页首帧检查
    var showPostLoginGuide by remember {
        mutableStateOf(com.maodouchat.util.PostLoginGuidePreferences.shouldShow(context))
    }

    // 9.150：菜单/弹窗持有的 Chat 快照——目标会话被删除（他端/WS 同步）后自动关闭，
    // 避免对已不存在的会话继续静音/归档/清空操作
    LaunchedEffect(state.chats) {
        val ids = state.chats.mapTo(hashSetOf()) { it.id }
        if (menuChat?.let { it.id !in ids } == true) menuChat = null
        if (silentUntilChat?.let { it.id !in ids } == true) silentUntilChat = null
        if (folderMoveChat?.let { it.id !in ids } == true) folderMoveChat = null
        if (clearHistoryChat?.let { it.id !in ids } == true) clearHistoryChat = null
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshUnreadPriorityPreference()
                viewModel.refreshLockedChats()
                viewModel.refreshSecretChats()
                viewModel.refreshOnForeground()
                viewModel.refreshAnnouncements()
                // 1.146：定时消息数随恢复刷新（详情页排期/取消后回到列表即时反映）
                viewModel.refreshScheduledCounts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.createdSecretChatId) {
        val secretId = state.createdSecretChatId ?: return@LaunchedEffect
        viewModel.clearCreatedSecretChat()
        onChatClick(secretId)
    }

    LaunchedEffect(openMissedCallsRequest) {
        if (openMissedCallsRequest > 0L) {
            showMissedCallsSheet = true
            viewModel.markMissedCallsRead()
        }
    }

    LaunchedEffect(Unit) {
        // G144：公屏状态拉取（53 行）抽到 ChatListServerFlags.kt，纯搬移不改判断。
        publicBanner = fetchPublicStatusBanner(context)
        viewModel.refreshSecretChats()
        viewModel.refreshLockedChats()
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearError()
    }

    // G139：置顶公告条（35 行）抽到 ChatListAnnouncementBanner.kt，纯搬移不改判断。
    ChatListAnnouncementBanner(
        priorityAnnouncement = state.activeAnnouncements.firstOrNull { a ->
            a.level == "EMERGENCY" || a.level == "MAINTENANCE"
        },
        onAck = { viewModel.ackAnnouncement(it) },
    )

    // 1.368：多选模式下系统返回优先退出多选（再返回才退出聊天列表）
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) {
        viewModel.exitSelectionMode()
    }

    val liquidGlass = LocalLiquidGlassEnabled.current
    val floatingDockOn by com.maodouchat.util.ChromePreferences.floatingDock.collectAsState()
    val floatingDock = liquidGlass && floatingDockOn
    Scaffold(
        // G140：顶栏（143 行）抽到 ChatListScaffoldChrome.kt，纯搬移不改判断。
        topBar = {
            ChatListTopBar(
            state = state,
            viewModel = viewModel,
            liquidGlass = liquidGlass,
            notifUnread = notifUnread,
            showCreateMenu = showCreateMenu,
            showBatchDeleteConfirm = showBatchDeleteConfirm,
            showThirdPartyServerDialog = showThirdPartyServerDialog,
            onShowCreateMenuChange = { showCreateMenu = it },
            onShowBatchDeleteConfirmChange = { showBatchDeleteConfirm = it },
            onShowThirdPartyServerDialogChange = { showThirdPartyServerDialog = it },
            onNavigateToTab = onNavigateToTab,
            onOpenGlobalSearch = onOpenGlobalSearch,
            onOpenNotificationCenter = onOpenNotificationCenter,
            onOpenScan = onOpenScan,
            )
        },
        containerColor = if (liquidGlass) Color.Transparent else MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        // G140：悬浮新建按钮（50 行）抽到 ChatListScaffoldChrome.kt，纯搬移不改判断。
        floatingActionButton = {
            ChatListFab(
            state = state,
            viewModel = viewModel,
            liquidGlass = liquidGlass,
            notifUnread = notifUnread,
            showCreateMenu = showCreateMenu,
            showBatchDeleteConfirm = showBatchDeleteConfirm,
            showThirdPartyServerDialog = showThirdPartyServerDialog,
            onShowCreateMenuChange = { showCreateMenu = it },
            onShowBatchDeleteConfirmChange = { showBatchDeleteConfirm = it },
            onShowThirdPartyServerDialogChange = { showThirdPartyServerDialog = it },
            onNavigateToTab = onNavigateToTab,
            onOpenGlobalSearch = onOpenGlobalSearch,
            onOpenNotificationCenter = onOpenNotificationCenter,
            onOpenScan = onOpenScan,
            )
        },
    ) { padding ->
        // G141：列表主体（206 行）抽到 ChatListContent.kt，纯搬移不改判断。
        ChatListContent(
            paddingValues = padding,
            state = state,
            viewModel = viewModel,
            floatingDock = floatingDock,
            motion = motion,
            menuChat = menuChat,
            publicBanner = publicBanner,
            showMissedCallsSheet = showMissedCallsSheet,
            showFolderManager = showFolderManager,
            showCreateFolder = showCreateFolder,
            onMenuChatChange = { menuChat = it },
            onPublicBannerChange = { publicBanner = it },
            onShowMissedCallsSheetChange = { showMissedCallsSheet = it },
            onShowFolderManagerChange = { showFolderManager = it },
            onShowCreateFolderChange = { showCreateFolder = it },
            onChatClick = onChatClick,
            onNavigateToTab = onNavigateToTab,
            onOpenGlobalSearch = onOpenGlobalSearch,
            onOpenGroupDetail = onOpenGroupDetail,
            onOpenMediaCenter = onOpenMediaCenter,
            onOpenNotificationCenter = onOpenNotificationCenter,
            onOpenProfile = onOpenProfile,
            onOpenScan = onOpenScan,
            onOpenStarredMessages = onOpenStarredMessages,
            onVideoCall = onVideoCall,
            onVoiceCall = onVoiceCall,
        )
    }













    // G136：会话长按菜单 + 第三方服务器提醒 + 批量删除确认 + 清空历史确认
    // （174 行）抽到 ChatListScreenDialogs.kt，纯搬移不改判断。
    ChatListScreenDialogs(
        state = state,
        viewModel = viewModel,
        menuChat = menuChat,
        clearHistoryChat = clearHistoryChat,
        silentUntilChat = silentUntilChat,
        folderMoveChat = folderMoveChat,
        showBatchDeleteConfirm = showBatchDeleteConfirm,
        showThirdPartyServerDialog = showThirdPartyServerDialog,
        onMenuChatChange = { menuChat = it },
        onClearHistoryChatChange = { clearHistoryChat = it },
        onSilentUntilChatChange = { silentUntilChat = it },
        onFolderMoveChatChange = { folderMoveChat = it },
        onShowBatchDeleteConfirmChange = { showBatchDeleteConfirm = it },
        onShowThirdPartyServerDialogChange = { showThirdPartyServerDialog = it },
        onOpenMediaCenter = onOpenMediaCenter,
        onOpenStarredMessages = onOpenStarredMessages,
        onOpenProfile = onOpenProfile,
        onOpenGroupDetail = onOpenGroupDetail,
    )

    // G137：未拨来电 + 文件夹管理弹层（222 行）抽到 ChatListFolderDialogs.kt，纯搬移不改判断。
    ChatListFolderDialogs(
        state = state,
        viewModel = viewModel,
        showMissedCallsSheet = showMissedCallsSheet,
        showCreateFolder = showCreateFolder,
        showFolderManager = showFolderManager,
        createFolderName = createFolderName,
        createFolderError = createFolderError,
        renameFolderId = renameFolderId,
        renameFolderName = renameFolderName,
        renameFolderError = renameFolderError,
        folderMoveChat = folderMoveChat,
        onShowMissedCallsSheetChange = { showMissedCallsSheet = it },
        onShowCreateFolderChange = { showCreateFolder = it },
        onShowFolderManagerChange = { showFolderManager = it },
        onCreateFolderNameChange = { createFolderName = it },
        onCreateFolderErrorChange = { createFolderError = it },
        onRenameFolderIdChange = { renameFolderId = it },
        onRenameFolderNameChange = { renameFolderName = it },
        onRenameFolderErrorChange = { renameFolderError = it },
        onFolderMoveChatChange = { folderMoveChat = it },
        onOpenGroupDetail = onOpenGroupDetail,
        onChatClick = onChatClick,
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
    )

    // G138：临时静音 + 首次登录引导弹层（65 行）抽到 ChatListScreenDialogs.kt，纯搬移不改判断。
    ChatListMiscDialogs(
        silentUntilChat = silentUntilChat,
        showPostLoginGuide = showPostLoginGuide,
        onSilentUntilChatChange = { silentUntilChat = it },
        onShowPostLoginGuideChange = { showPostLoginGuide = it },
        onNavigateToTab = onNavigateToTab,
        onOpenScan = onOpenScan,
    )
}
