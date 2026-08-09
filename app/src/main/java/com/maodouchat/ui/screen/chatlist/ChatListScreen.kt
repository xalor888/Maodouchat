package com.maodouchat.ui.screen.chatlist


import android.annotation.SuppressLint
import com.maodouchat.util.RuntimeFlags
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.MissedCall
import com.maodouchat.network.ApiService
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.PullToRefreshLayout
import com.maodouchat.ui.component.SwipeableChatItem
import com.maodouchat.ui.component.SearchBar
import com.maodouchat.ui.component.ShimmerChatRow
import com.maodouchat.ui.navigation.MainTab
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.ChatFolderPolicy
import com.maodouchat.util.HapticGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    // 1.31：会话列表长按菜单「临时静音至」目标会话
    var silentUntilChat by remember { mutableStateOf<Chat?>(null) }
    var showMissedCallsSheet by rememberSaveable { mutableStateOf(false) }
    var showFolderManager by rememberSaveable { mutableStateOf(false) }
    var showCreateFolder by rememberSaveable { mutableStateOf(false) }
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

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshUnreadPriorityPreference()
                viewModel.refreshLockedChats()
                viewModel.refreshSecretChats()
                viewModel.refresh()
                viewModel.refreshAnnouncements()
                // 1.146：定时消息数随恢复刷新（详情页排期/取消后回到列表即时反映）
                viewModel.refreshScheduledCounts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(openMissedCallsRequest) {
        if (openMissedCallsRequest > 0L) {
            showMissedCallsSheet = true
            viewModel.markMissedCallsRead()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val raw = ApiService.getPublicStatus().getOrNull().orEmpty()
            if (raw.isBlank()) return@withContext
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext
            // optString 缺失键返回字面 "null"（非 blank）——统一用 safeOpt 排除，避免横幅显示 "null"/写入垃圾 key
            fun safeOpt(key: String): String = if (o.has(key)) o.optString(key).takeIf { it != "null" }.orEmpty() else ""
            val banner = safeOpt("banner").ifBlank { safeOpt("globalBanner") }
            val e2eeBanner = safeOpt("forceE2eeBanner").ifBlank { safeOpt("e2eeBanner") }
            val announcement = safeOpt("publicAnnouncement").ifBlank { safeOpt("announcement") }
            val maintMsg = safeOpt("maintenanceMessage")
            // 兼容键名：服务端下发 "maintenance"；旧版本曾用 "maintenanceMode"
            val publicMaintenance = if (o.has("maintenance")) o.optBoolean("maintenance", false) else o.optBoolean("maintenanceMode", false)
            val minApp = safeOpt("minAppVersion")
            val pqxdh = o.optBoolean("pqxdhPreview", false)
            val secretRequired = o.optBoolean("secretChatRequired", false)
            val aiAnalyzeFileEnabledOn = if (o.has("aiAnalyzeFileEnabled")) o.optBoolean("aiAnalyzeFileEnabled", true) else true
            val aiAnalyzeImageEnabledOn = if (o.has("aiAnalyzeImageEnabled")) o.optBoolean("aiAnalyzeImageEnabled", true) else true
            val aiGroupAssistantEnabledOn = if (o.has("aiGroupAssistantEnabled")) o.optBoolean("aiGroupAssistantEnabled", true) else true
            val aiRewriteEnabledOn = if (o.has("aiRewriteEnabled")) o.optBoolean("aiRewriteEnabled", true) else true
            val aiSemanticSearchEnabledOn = if (o.has("aiSemanticSearchEnabled")) o.optBoolean("aiSemanticSearchEnabled", true) else true
            val aiSuggestRepliesEnabledOn = if (o.has("aiSuggestRepliesEnabled")) o.optBoolean("aiSuggestRepliesEnabled", true) else true
            val aiSummaryEnabledOn = if (o.has("aiSummaryEnabled")) o.optBoolean("aiSummaryEnabled", true) else true
            val aiTranscribeEnabledOn = if (o.has("aiTranscribeEnabled")) o.optBoolean("aiTranscribeEnabled", true) else true
            val aiTranslateEnabledOn = if (o.has("aiTranslateEnabled")) o.optBoolean("aiTranslateEnabled", true) else true
            val appLockEnabledOn = if (o.has("appLockEnabled")) o.optBoolean("appLockEnabled", true) else true
            val autoDownloadEnabledOn = if (o.has("autoDownloadEnabled")) o.optBoolean("autoDownloadEnabled", true) else true
            val blindWatermarkEnabledOn = if (o.has("blindWatermarkEnabled")) o.optBoolean("blindWatermarkEnabled", true) else true
            val blockReportEnabledOn = if (o.has("blockReportEnabled")) o.optBoolean("blockReportEnabled", true) else true
            val callsEnabledOn = if (o.has("callsEnabled")) o.optBoolean("callsEnabled", true) else true
            val captureAlertEnabledOn = if (o.has("captureAlertEnabled")) o.optBoolean("captureAlertEnabled", true) else true
            val chatAnimationsEnabledOn = if (o.has("chatAnimationsEnabled")) o.optBoolean("chatAnimationsEnabled", true) else true
            val chatArchiveEnabledOn = if (o.has("chatArchiveEnabled")) o.optBoolean("chatArchiveEnabled", true) else true
            val chatDraftsEnabledOn = if (o.has("chatDraftsEnabled")) o.optBoolean("chatDraftsEnabled", true) else true
            val chatExportEnabledOn = if (o.has("chatExportEnabled")) o.optBoolean("chatExportEnabled", true) else true
            val chatFoldersEnabledOn = if (o.has("chatFoldersEnabled")) o.optBoolean("chatFoldersEnabled", true) else true
            val chatFontScaleEnabledOn = if (o.has("chatFontScaleEnabled")) o.optBoolean("chatFontScaleEnabled", true) else true
            val chatLockEnabledOn = if (o.has("chatLockEnabled")) o.optBoolean("chatLockEnabled", true) else true
            val chatMuteEnabledOn = if (o.has("chatMuteEnabled")) o.optBoolean("chatMuteEnabled", true) else true
            val chatPinEnabledOn = if (o.has("chatPinEnabled")) o.optBoolean("chatPinEnabled", true) else true
            val chatWallpaperEnabledOn = if (o.has("chatWallpaperEnabled")) o.optBoolean("chatWallpaperEnabled", true) else true
            val contactCardEnabledOn = if (o.has("contactCardEnabled")) o.optBoolean("contactCardEnabled", true) else true
            val disappearingMessagesEnabledOn = if (o.has("disappearingMessagesEnabled")) o.optBoolean("disappearingMessagesEnabled", true) else true
            val dndEnabledOn = if (o.has("dndEnabled")) o.optBoolean("dndEnabled", true) else true
            val fileShareEnabledOn = if (o.has("fileShareEnabled")) o.optBoolean("fileShareEnabled", true) else true
            val friendRequestsEnabledOn = if (o.has("friendRequestsEnabled")) o.optBoolean("friendRequestsEnabled", true) else true
            val gifSendEnabledOn = if (o.has("gifSendEnabled")) o.optBoolean("gifSendEnabled", true) else true
            val globalSearchEnabledOn = if (o.has("globalSearchEnabled")) o.optBoolean("globalSearchEnabled", true) else true
            val groupInvitesEnabledOn = if (o.has("groupInvitesEnabled")) o.optBoolean("groupInvitesEnabled", true) else true
            val groupPlayEnabledOn = if (o.has("groupPlayEnabled")) o.optBoolean("groupPlayEnabled", true) else true
            val hapticsEnabledOn = if (o.has("hapticsEnabled")) o.optBoolean("hapticsEnabled", true) else true
            val imageSendEnabledOn = if (o.has("imageSendEnabled")) o.optBoolean("imageSendEnabled", true) else true
            val inAppSoundsEnabledOn = if (o.has("inAppSoundsEnabled")) o.optBoolean("inAppSoundsEnabled", true) else true
            val linkPreviewEnabledOn = if (o.has("linkPreviewEnabled")) o.optBoolean("linkPreviewEnabled", true) else true
            val liveLocationEnabledOn = if (o.has("liveLocationEnabled")) o.optBoolean("liveLocationEnabled", true) else true
            val markdownEnabledOn = if (o.has("markdownEnabled")) o.optBoolean("markdownEnabled", true) else true
            val markedUnreadEnabledOn = if (o.has("markedUnreadEnabled")) o.optBoolean("markedUnreadEnabled", true) else true
            val mediaUploadEnabledOn = if (o.has("mediaUploadEnabled")) o.optBoolean("mediaUploadEnabled", true) else true
            val mentionsEnabledOn = if (o.has("mentionsEnabled")) o.optBoolean("mentionsEnabled", true) else true
            val messageEditEnabledOn = if (o.has("messageEditEnabled")) o.optBoolean("messageEditEnabled", true) else true
            val messageForwardingEnabledOn = if (o.has("messageForwardingEnabled")) o.optBoolean("messageForwardingEnabled", true) else true
            val messagePinEnabledOn = if (o.has("messagePinEnabled")) o.optBoolean("messagePinEnabled", true) else true
            val messageRevokeEnabledOn = if (o.has("messageRevokeEnabled")) o.optBoolean("messageRevokeEnabled", true) else true
            val messageStarringEnabledOn = if (o.has("messageStarringEnabled")) o.optBoolean("messageStarringEnabled", true) else true
            val navTransitionsEnabledOn = if (o.has("navTransitionsEnabled")) o.optBoolean("navTransitionsEnabled", true) else true
            val nearbyEnabledOn = if (o.has("nearbyEnabled")) o.optBoolean("nearbyEnabled", true) else true
            val notificationPreviewEnabledOn = if (o.has("notificationPreviewEnabled")) o.optBoolean("notificationPreviewEnabled", true) else true
            val notificationSoundEnabledOn = if (o.has("notificationSoundEnabled")) o.optBoolean("notificationSoundEnabled", true) else true
            val nudgeEnabledOn = if (o.has("nudgeEnabled")) o.optBoolean("nudgeEnabled", true) else true
            val offlineAiEnabledOn = if (o.has("offlineAiEnabled")) o.optBoolean("offlineAiEnabled", true) else true
            val pollsEnabledOn = if (o.has("pollsEnabled")) o.optBoolean("pollsEnabled", true) else true
            val postsEnabledOn = if (o.has("postsEnabled")) o.optBoolean("postsEnabled", true) else true
            val presenceEnabledOn = if (o.has("presenceEnabled")) o.optBoolean("presenceEnabled", true) else true
            val pushNotificationsEnabledOn = if (o.has("pushNotificationsEnabled")) o.optBoolean("pushNotificationsEnabled", true) else true
            val qrCodeEnabledOn = if (o.has("qrCodeEnabled")) o.optBoolean("qrCodeEnabled", true) else true
            val reactionsEnabledOn = if (o.has("reactionsEnabled")) o.optBoolean("reactionsEnabled", true) else true
            val readReceiptsEnabledOn = if (o.has("readReceiptsEnabled")) o.optBoolean("readReceiptsEnabled", true) else true
            val recentsExclusionEnabledOn = if (o.has("recentsExclusionEnabled")) o.optBoolean("recentsExclusionEnabled", true) else true
            val ringtoneEnabledOn = if (o.has("ringtoneEnabled")) o.optBoolean("ringtoneEnabled", true) else true
            val safetyCodeEnabledOn = if (o.has("safetyCodeEnabled")) o.optBoolean("safetyCodeEnabled", true) else true
            val scheduledMessagesEnabledOn = if (o.has("scheduledMessagesEnabled")) o.optBoolean("scheduledMessagesEnabled", true) else true
            val screenSecureRuntimeEnabledOn = if (o.has("screenSecureRuntimeEnabled")) o.optBoolean("screenSecureRuntimeEnabled", true) else true
            val screenshotDetectEnabledOn = if (o.has("screenshotDetectEnabled")) o.optBoolean("screenshotDetectEnabled", true) else true
            val sealedSenderEnabledOn = if (o.has("sealedSenderEnabled")) o.optBoolean("sealedSenderEnabled", true) else true
            val secretAutoDisappearEnabledOn = if (o.has("secretAutoDisappearEnabled")) o.optBoolean("secretAutoDisappearEnabled", true) else true
            val secretChatExportBlockEnabledOn = if (o.has("secretChatExportBlockEnabled")) o.optBoolean("secretChatExportBlockEnabled", true) else true
            val secretChatEnabledOn = if (o.has("secretChatEnabled")) o.optBoolean("secretChatEnabled", true) else true
            val secretCopyBlockEnabledOn = if (o.has("secretCopyBlockEnabled")) o.optBoolean("secretCopyBlockEnabled", true) else true
            val secretExternalLinkBlockEnabledOn = if (o.has("secretExternalLinkBlockEnabled")) o.optBoolean("secretExternalLinkBlockEnabled", false) else false
            val secretForwardBlockEnabledOn = if (o.has("secretForwardBlockEnabled")) o.optBoolean("secretForwardBlockEnabled", true) else true
            val secretLinkPreviewBlockEnabledOn = if (o.has("secretLinkPreviewBlockEnabled")) o.optBoolean("secretLinkPreviewBlockEnabled", true) else true
            val secretListPreviewBlockEnabledOn = if (o.has("secretListPreviewBlockEnabled")) o.optBoolean("secretListPreviewBlockEnabled", true) else true
            val secretMediaExportBlockEnabledOn = if (o.has("secretMediaExportBlockEnabled")) o.optBoolean("secretMediaExportBlockEnabled", true) else true
            val secretNotifPreviewBlockEnabledOn = if (o.has("secretNotifPreviewBlockEnabled")) o.optBoolean("secretNotifPreviewBlockEnabled", true) else true
            val secretReactionBlockEnabledOn = if (o.has("secretReactionBlockEnabled")) o.optBoolean("secretReactionBlockEnabled", true) else true
            val secretStarBlockEnabledOn = if (o.has("secretStarBlockEnabled")) o.optBoolean("secretStarBlockEnabled", true) else true
            val secretTypingBlockEnabledOn = if (o.has("secretTypingBlockEnabled")) o.optBoolean("secretTypingBlockEnabled", true) else true
            val secretReadReceiptBlockEnabledOn = if (o.has("secretReadReceiptBlockEnabled")) o.optBoolean("secretReadReceiptBlockEnabled", true) else true
            val secretPresenceBlockEnabledOn = if (o.has("secretPresenceBlockEnabled")) o.optBoolean("secretPresenceBlockEnabled", true) else true
            val secretLastSeenBlockEnabledOn = if (o.has("secretLastSeenBlockEnabled")) o.optBoolean("secretLastSeenBlockEnabled", true) else true
            val pushHmacKey = safeOpt("pushHmacKey").ifBlank { null }
            val silentSendEnabledOn = if (o.has("silentSendEnabled")) o.optBoolean("silentSendEnabled", true) else true
            val spoilerMediaEnabledOn = if (o.has("spoilerMediaEnabled")) o.optBoolean("spoilerMediaEnabled", true) else true
            val staticLocationEnabledOn = if (o.has("staticLocationEnabled")) o.optBoolean("staticLocationEnabled", true) else true
            val stickersEnabledOn = if (o.has("stickersEnabled")) o.optBoolean("stickersEnabled", true) else true
            val taskRemindersEnabledOn = if (o.has("taskRemindersEnabled")) o.optBoolean("taskRemindersEnabled", true) else true
            val typingIndicatorsEnabledOn = if (o.has("typingIndicatorsEnabled")) o.optBoolean("typingIndicatorsEnabled", true) else true
            val unreadPriorityEnabledOn = if (o.has("unreadPriorityEnabled")) o.optBoolean("unreadPriorityEnabled", true) else true
            val videoCallEnabledOn = if (o.has("videoCallEnabled")) o.optBoolean("videoCallEnabled", true) else true
            val videoSendEnabledOn = if (o.has("videoSendEnabled")) o.optBoolean("videoSendEnabled", true) else true
            val viewOnceEnabledOn = if (o.has("viewOnceEnabled")) o.optBoolean("viewOnceEnabled", true) else true
            val visibleWatermarkEnabledOn = if (o.has("visibleWatermarkEnabled")) o.optBoolean("visibleWatermarkEnabled", true) else true
            val voiceCallEnabledOn = if (o.has("voiceCallEnabled")) o.optBoolean("voiceCallEnabled", true) else true
            val voiceMessagesEnabledOn = if (o.has("voiceMessagesEnabled")) o.optBoolean("voiceMessagesEnabled", true) else true
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_ANALYZE_FILE, aiAnalyzeFileEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_ANALYZE_IMAGE, aiAnalyzeImageEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_GROUP_ASSISTANT, aiGroupAssistantEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_REWRITE, aiRewriteEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_SEMANTIC_SEARCH, aiSemanticSearchEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_SUGGEST_REPLIES, aiSuggestRepliesEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_SUMMARY, aiSummaryEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_TRANSCRIBE, aiTranscribeEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AI_TRANSLATE, aiTranslateEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.APP_LOCK, appLockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.AUTO_DOWNLOAD, autoDownloadEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.BLIND_WATERMARK, blindWatermarkEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.BLOCK_REPORT, blockReportEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CALLS, callsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CAPTURE_ALERT, captureAlertEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_ANIMATIONS, chatAnimationsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_ARCHIVE, chatArchiveEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_DRAFTS, chatDraftsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_EXPORT, chatExportEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_FOLDERS, chatFoldersEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_FONT_SCALE, chatFontScaleEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_LOCK, chatLockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_MUTE, chatMuteEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_PIN, chatPinEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CHAT_WALLPAPER, chatWallpaperEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.CONTACT_CARD, contactCardEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.DISAPPEARING_MESSAGES, disappearingMessagesEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.DND, dndEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.FILE_SHARE, fileShareEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.FRIEND_REQUESTS, friendRequestsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.GIF_SEND, gifSendEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.GLOBAL_SEARCH, globalSearchEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.GROUP_INVITES, groupInvitesEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.GROUP_PLAY, groupPlayEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.HAPTICS, hapticsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.IMAGE_SEND, imageSendEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.IN_APP_SOUNDS, inAppSoundsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.LINK_PREVIEW, linkPreviewEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.LIVE_LOCATION, liveLocationEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MARKDOWN, markdownEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MARKED_UNREAD, markedUnreadEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MEDIA_UPLOAD, mediaUploadEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MENTIONS, mentionsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MESSAGE_EDIT, messageEditEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MESSAGE_FORWARDING, messageForwardingEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MESSAGE_PIN, messagePinEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MESSAGE_REVOKE, messageRevokeEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.MESSAGE_STARRING, messageStarringEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.NAV_TRANSITIONS, navTransitionsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.NEARBY, nearbyEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.NOTIFICATION_PREVIEW, notificationPreviewEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.NOTIFICATION_SOUND, notificationSoundEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.NUDGE, nudgeEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.OFFLINE_AI, offlineAiEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.POLLS, pollsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.POSTS, postsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.PRESENCE, presenceEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.PUSH_NOTIFICATIONS, pushNotificationsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.QR_CODE, qrCodeEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.REACTIONS, reactionsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.READ_RECEIPTS, readReceiptsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.RECENTS_EXCLUSION, recentsExclusionEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.RINGTONE, ringtoneEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SAFETY_CODE, safetyCodeEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SCHEDULED_MESSAGES, scheduledMessagesEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SCREEN_SECURE, screenSecureRuntimeEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SCREENSHOT_DETECT, screenshotDetectEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SEALED_SENDER, sealedSenderEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_AUTO_DISAPPEAR, secretAutoDisappearEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_CHAT_EXPORT_BLOCK, secretChatExportBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_CHAT, secretChatEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK, secretCopyBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK, secretExternalLinkBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK, secretForwardBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_LINK_PREVIEW_BLOCK, secretLinkPreviewBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_LIST_PREVIEW_BLOCK, secretListPreviewBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK, secretMediaExportBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK, secretNotifPreviewBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_REACTION_BLOCK, secretReactionBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_STAR_BLOCK, secretStarBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_TYPING_BLOCK, secretTypingBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_READ_RECEIPT_BLOCK, secretReadReceiptBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_PRESENCE_BLOCK, secretPresenceBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SECRET_LAST_SEEN_BLOCK, secretLastSeenBlockEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.SILENT_SEND, silentSendEnabledOn)
            pushHmacKey?.let { com.maodouchat.util.PushVerifyPrefs.setKey(context, it) }
            RuntimeFlags.setEnabled(context, RuntimeFlags.SPOILER_MEDIA, spoilerMediaEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.STATIC_LOCATION, staticLocationEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.STICKERS, stickersEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.TASK_REMINDERS, taskRemindersEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.TYPING_INDICATORS, typingIndicatorsEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.UNREAD_PRIORITY, unreadPriorityEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.VIDEO_CALL, videoCallEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.VIDEO_SEND, videoSendEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.VIEW_ONCE, viewOnceEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.VISIBLE_WATERMARK, visibleWatermarkEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.VOICE_CALL, voiceCallEnabledOn)
            RuntimeFlags.setEnabled(context, RuntimeFlags.VOICE_MESSAGES, voiceMessagesEnabledOn)

            // 服务端 AI 总开关 → 本地 AI_MASTER（false 时折叠全部 AI 入口）
            if (o.has("aiEnabled")) RuntimeFlags.setEnabled(context, RuntimeFlags.AI_MASTER, o.optBoolean("aiEnabled", true))
            // B2 密聊防泄漏扩展（surface #71–#78）：服务端开关 → SecretXxxPrefs。
            // 仅当用户从未在设置页显式设置过时接受服务端默认值，本地开关永远优先
            //（设置页声明"仅本机生效"；无条件覆盖会让用户"关了又开"）。
            val ssf = o.optJSONObject("secretSurfaceFlags")
            if (ssf != null) {
                com.maodouchat.util.SecretScreenshotBurnPrefs.applyServerDefault(context, ssf.optBoolean("secretScreenshotBurnEnabled", true))
                com.maodouchat.util.SecretAutoDestroyPrefs.applyServerDefault(context, ssf.optBoolean("secretAutoDestroyEnabled", true))
                com.maodouchat.util.SecretForwardWhitelistPrefs.applyServerDefault(context, ssf.optBoolean("secretForwardWhitelistEnabled", true))
                com.maodouchat.util.SecretSimChangePrefs.applyServerDefault(context, ssf.optBoolean("secretSimChangeProtectionEnabled", true))
                com.maodouchat.util.Secret2faGatePrefs.applyServerDefault(context, ssf.optBoolean("secret2faGateEnabled", true))
                com.maodouchat.util.SecretNewDeviceRiskPrefs.applyServerDefault(context, ssf.optBoolean("secretNewDeviceRiskEnabled", true))
                com.maodouchat.util.SecretDeviceVerifyPrefs.applyServerDefault(context, ssf.optBoolean("secretDeviceVerifyEnabled", true))
                com.maodouchat.util.SecretSessionNoticePrefs.applyServerDefault(context, ssf.optBoolean("secretSessionNoticeEnabled", true))
            }
            val upgradeHint = if (minApp.isNotBlank() && minApp != "0") "Min version: $minApp" else null
            val pqxdhHint = if (pqxdh) "PQXDH preview on" else null
            val secretHint = if (secretRequired) context.getString(R.string.secret_chat_required_banner) else null
            val parts = listOfNotNull(
                banner.takeIf { it.isNotBlank() },
                e2eeBanner.takeIf { it.isNotBlank() },
                announcement.takeIf { it.isNotBlank() },
                upgradeHint,
                pqxdhHint,
                secretHint
            )
            publicBanner = when {
                publicMaintenance && maintMsg.isNotBlank() -> maintMsg
                parts.isNotEmpty() -> parts.joinToString(" · ")
                else -> null
            }
        }
        viewModel.refreshSecretChats()
        viewModel.refreshLockedChats()
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearError()
    }

    // 公告中心：高优先级（EMERGENCY/MAINTENANCE）未读公告弹窗提示，确认后 ack（重复点击由 ViewModel 防重入）
    val priorityAnnouncement = state.activeAnnouncements.firstOrNull { a ->
        a.level == "EMERGENCY" || a.level == "MAINTENANCE"
    }
    if (priorityAnnouncement != null) {
        AlertDialog(
            onDismissRequest = { /* 高优先级公告不可跳过，必须确认 */ },
            title = { Text(priorityAnnouncement.title.ifBlank { stringResource(R.string.announcement_title_default) }, style = MaterialTheme.typography.titleMedium, color = OnSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (priorityAnnouncement.level) {
                            "EMERGENCY" -> stringResource(R.string.announcement_level_emergency)
                            else -> stringResource(R.string.announcement_level_maintenance)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (priorityAnnouncement.level == "EMERGENCY") UnreadRed else Primary
                    )
                    Text(
                        priorityAnnouncement.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.ackAnnouncement(priorityAnnouncement.id) }
                ) { Text(stringResource(R.string.common_confirm)) }
            }
        )
    }

    // 1.368：多选模式下系统返回优先退出多选（再返回才退出聊天列表）
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) {
        viewModel.exitSelectionMode()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.selectionMode) {
                        Text(
                            stringResource(R.string.chat_list_selected_count, state.selectedChatIds.size),
                            style = MaterialTheme.typography.titleLarge
                        )
                    } else {
                        Text(
                            if (state.showArchived) stringResource(R.string.chat_archived_title)
                            else stringResource(R.string.nav_chats)
                        )
                    }
                },
                navigationIcon = {
                    if (state.selectionMode) {
                        IconButton(onClick = viewModel::exitSelectionMode) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        // 1.368：多选模式操作条（置顶 / 已读 / 删除）
                        val hasSelection = state.selectedChatIds.isNotEmpty()
                        IconButton(
                            onClick = viewModel::batchTogglePinSelected,
                            enabled = hasSelection
                        ) {
                            Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.chat_pin), tint = if (hasSelection) MaterialTheme.colorScheme.onSurface else TextHint)
                        }
                        IconButton(
                            onClick = viewModel::batchMarkReadSelected,
                            enabled = hasSelection
                        ) {
                            Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.chat_mark_read), tint = if (hasSelection) MaterialTheme.colorScheme.onSurface else TextHint)
                        }
                        IconButton(
                            // 1.373：批量删除先确认（防止误触批量清空）
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = hasSelection
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.chat_delete), tint = if (hasSelection) UnreadRed else TextHint)
                        }
                    } else {
                        if (state.selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID) {
                            IconButton(
                                onClick = viewModel::markAllUnreadChatsRead,
                                enabled = state.unreadInFolder(com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID) > 0
                            ) {
                                Icon(
                                    Icons.Outlined.DoneAll,
                                    contentDescription = stringResource(R.string.notif_center_mark_all_read)
                                )
                            }
                        }
                        IconButton(onClick = onOpenGlobalSearch) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.global_search_title))
                        }
                        IconButton(onClick = onOpenNotificationCenter) {
                            Box {
                                Icon(Icons.Outlined.Notifications, contentDescription = stringResource(R.string.notif_center_title))
                                if (notifUnread > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.setShowArchived(!state.showArchived) }) {
                            Icon(if (state.showArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = stringResource(R.string.chat_archived_title))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToTab(MainTab.CONTACTS) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_empty_action_add))
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val bannerText = publicBanner ?: state.realtimeBanner
            AnimatedVisibility(visible = !bannerText.isNullOrBlank(), enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        bannerText.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { publicBanner = null; viewModel.clearRealtimeBanner() }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            }

            SearchBar(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = stringResource(R.string.global_search_chats_placeholder)
            )

            ChatFolderStrip(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                secretChatCount = state.secretChatIds.size,
                lockedChatCount = state.lockedChatIds.size,
                unreadInFolder = { state.unreadInFolder(it) },
                onSelectFolder = viewModel::selectFolder,
                onManage = { showFolderManager = true },
                onCreate = { showCreateFolder = true }
            )

            // 8.45：未读优先轻提示条（恢复被重写丢失的抛光项）——未读会话较多且未读优先开启时提示
            if (state.showUnreadPriorityHint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setUnreadPriorityEnabled(false) }
                        .background(Primary.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.chat_unread_priority_hint, state.unreadChatCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.setUnreadPriorityEnabled(false) }) {
                        Text(stringResource(R.string.chat_unread_priority_hint_dismiss), color = Primary)
                    }
                }
            }

            if (state.missedCalls.isNotEmpty()) {
                MissedCallsCard(calls = state.missedCalls, onOpen = {
                    showMissedCallsSheet = true
                    viewModel.markMissedCallsRead()
                })
            }

            // 8.47：智能归档建议卡片（纯本地启发式；采纳走现有归档流程）
            if (state.archiveSuggestions.isNotEmpty()) {
                ArchiveSuggestionsCard(
                    suggestions = state.archiveSuggestions.take(3),
                    chatsById = state.chats.associateBy { it.id },
                    onArchive = { chatId ->
                        state.chats.firstOrNull { it.id == chatId }?.let { viewModel.archiveChatFromSuggestion(it) }
                    },
                    onDismissOne = viewModel::dismissArchiveSuggestion,
                    onDismissAll = viewModel::dismissAllArchiveSuggestions
                )
            }

            when {
                state.isLoading && state.chats.isEmpty() -> ShimmerChatList()
                // 8.52 UX：会话列表支持下拉刷新（此前仅靠 ON_RESUME 触发，无手动刷新入口）
                else -> PullToRefreshLayout(
                    isRefreshing = state.isLoading,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column {
                        // 8.45：列表已有数据时的刷新指示（重写后丢失的抛光项）
                        if (state.isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = Primary
                            )
                        }
                        if (state.filteredChats.isEmpty()) {
                            EmptyChatState(
                                hasSearchQuery = state.searchQuery.isNotBlank(),
                                showArchived = state.showArchived,
                                selectedFolderId = state.selectedFolderId,
                                // 8.52 UX：加载失败且列表为空时显示错误态 + 重试
                                loadError = if (state.errorMessage != null && state.chats.isEmpty()) state.errorMessage else null,
                                onRetry = { viewModel.refresh() },
                                onAddContact = { onNavigateToTab(MainTab.CONTACTS) },
                                onScan = onOpenScan
                            )
                        } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(state.filteredChats, key = { it.id }) { chat ->
                                // 0.73：会话左滑操作（置顶/静音/归档 + 全滑删除）——组件早已存在未接入
                                SwipeableChatItem(
                                    isPinned = chat.pinnedAt > 0,
                                    isMuted = chat.notificationsMuted,
                                    isArchived = chat.archived,
                                    onPin = { viewModel.togglePinned(chat) },
                                    onMute = { viewModel.toggleNotificationsMuted(chat) },
                                    onArchive = { viewModel.toggleArchived(chat) },
                                    onDelete = { viewModel.deleteChat(chat.id) },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = motion.listItemFadeInSpec(),
                                        fadeOutSpec = motion.listItemFadeOutSpec(),
                                        placementSpec = motion.listItemPlacementSpec()
                                    )
                                ) {
                                    ChatListItem(
                                        chat = chat,
                                        draft = state.drafts[chat.id],
                                        typingUserId = state.typingByChat[chat.id],
                                        scheduledCount = state.scheduledByChat[chat.id] ?: 0,
                                        searchQuery = state.searchQuery,
                                        isLocked = chat.id in state.lockedChatIds,
                                        isSecret = chat.id in state.secretChatIds,
                                        identityChanged = !chat.isGroup && chat.participants.firstOrNull()?.id in state.identityChangedUserIds,
                                        isDeleting = chat.id in state.deletingChatIds,
                                        // 1.368：多选模式下点按勾选，长按保持原单条菜单
                                        isSelecting = state.selectionMode,
                                        isSelected = chat.id in state.selectedChatIds,
                                        onClick = {
                                            if (state.selectionMode) viewModel.toggleSelectChat(chat.id)
                                            else onChatClick(chat.id)
                                        },
                                        onLongClick = {
                                            if (state.selectionMode) viewModel.toggleSelectChat(chat.id)
                                            else menuChat = chat
                                        },
                                        // 1.182：点未读角标标记已读
                                        onBadgeClick = { viewModel.toggleMarkedUnread(chat) }
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        }
    }
}

    menuChat?.let { chat ->
        DropdownMenu(expanded = true, onDismissRequest = { menuChat = null }) {
            // 1.368：多选（长按菜单进入批量模式，先勾选当前会话）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_multi_select)) },
                onClick = {
                    menuChat = null
                    viewModel.enterSelectionMode()
                    viewModel.toggleSelectChat(chat.id)
                }
            )
            // 1.267：全部已读（所有未读会话）
            if (state.unreadChatCount > 0) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_mark_all_read)) }, onClick = { viewModel.markAllUnreadChatsRead(); menuChat = null })
            }
            DropdownMenuItem(text = { Text(stringResource(if (chat.pinnedAt > 0) R.string.chat_unpin else R.string.chat_pin)) }, onClick = { viewModel.togglePinned(chat); menuChat = null })
            DropdownMenuItem(text = { Text(stringResource(if (chat.notificationsMuted) R.string.chat_unmute_notifications else R.string.chat_mute_notifications)) }, onClick = { viewModel.toggleNotificationsMuted(chat); menuChat = null })
            // 1.31：临时静音至快捷项（本地，1/8/24 小时）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_silent_until_menu)) },
                onClick = { silentUntilChat = chat; menuChat = null }
            )
            DropdownMenuItem(text = { Text(stringResource(if (chat.archived) R.string.chat_unarchive else R.string.chat_archive)) }, onClick = { viewModel.toggleArchived(chat); menuChat = null })
            DropdownMenuItem(text = { Text(stringResource(if (chat.markedUnread || chat.unreadCount > 0) R.string.chat_mark_read else R.string.chat_mark_unread)) }, onClick = { viewModel.toggleMarkedUnread(chat); menuChat = null })
            // 1.142：有草稿时清除草稿（本地）
            if (state.drafts[chat.id]?.text?.isNotBlank() == true) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_clear_draft)) }, onClick = { viewModel.clearChatDraft(chat.id); menuChat = null })
            }
            // 1.171：清空本地聊天记录（保留会话）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_clear_local_history), color = UnreadRed) },
                onClick = { clearHistoryChat = chat; menuChat = null }
            )
            // 1.185：查看共享媒体
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_view_shared_media)) },
                onClick = { onOpenMediaCenter(chat.id); menuChat = null }
            )
            // 1.223：复制会话名称
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_chat_name)) },
                onClick = {
                    val name = when {
                        chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: ""
                        chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: ""
                        else -> chat.participants.firstOrNull()?.displayName.orEmpty()
                    }
                    if (name.isNotBlank()) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_copy_chat_name), name))
                        android.widget.Toast.makeText(context, context.getString(R.string.chat_copied), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    menuChat = null
                }
            )
            // 1.249：复制会话 ID（便于反馈/排查）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_chat_id)) },
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_copy_chat_id), chat.id))
                    android.widget.Toast.makeText(context, context.getString(R.string.chat_copied), android.widget.Toast.LENGTH_SHORT).show()
                    menuChat = null
                }
            )
            // 1.251：查看资料（群聊进群详情，单聊进作者主页）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_view_profile)) },
                onClick = {
                    if (chat.isGroup || chat.isChannel) {
                        onOpenGroupDetail(chat.id)
                    } else {
                        chat.participants.firstOrNull()?.id?.takeIf(String::isNotBlank)?.let(onOpenProfile)
                    }
                    menuChat = null
                }
            )
            // 1.215：查看收藏
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_view_starred)) },
                onClick = { onOpenStarredMessages(chat.id); menuChat = null }
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_folder_manage)) }, onClick = { folderMoveChat = chat; menuChat = null })
            DropdownMenuItem(text = {
                Text(
                    stringResource(
                        when {
                            chat.isChannel -> R.string.chat_channel_leave
                            chat.isGroup -> R.string.chat_leave_group
                            else -> R.string.chat_delete
                        }
                    )
                )
            }, onClick = { viewModel.deleteChat(chat.id); menuChat = null })
        }
    }

    // 1.373：多选批量删除确认（显示选中数，确认后才执行）
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_title), color = OnSurface) },
            text = { Text(stringResource(R.string.chat_list_batch_delete_confirm, state.selectedChatIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteConfirm = false
                    viewModel.batchDeleteSelected()
                }) { Text(stringResource(R.string.chat_delete), color = UnreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 1.31：会话列表「临时静音至」对话框（1/8/24 小时，本地 per-chat）
    silentUntilChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { silentUntilChat = null },
            title = { Text(stringResource(R.string.chat_silent_until_title)) },
            text = {
                Column {
                    listOf(
                        (1L * 3600_000L) to R.string.chat_silent_until_1h,
                        (8L * 3600_000L) to R.string.chat_silent_until_8h,
                        (24L * 3600_000L) to R.string.chat_silent_until_24h
                    ).forEach { (ms, labelRes) ->
                        TextButton(
                            onClick = {
                                com.maodouchat.notification.ChatQuietHoursStore.setSilentUntil(
                                    context,
                                    chat.id,
                                    System.currentTimeMillis() + ms
                                )
                                android.widget.Toast.makeText(context, context.getString(R.string.chat_silent_until_set), android.widget.Toast.LENGTH_SHORT).show()
                                silentUntilChat = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(labelRes), modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { silentUntilChat = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 1.171：清空本地聊天记录确认
    clearHistoryChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { clearHistoryChat = null },
            title = { Text(stringResource(R.string.chat_clear_local_history)) },
            text = { Text(stringResource(R.string.chat_clear_local_history_confirm, chat.groupName?.takeIf(String::isNotBlank) ?: chat.participants.firstOrNull()?.displayName.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLocalChatHistory(chat.id)
                    clearHistoryChat = null
                }) { Text(stringResource(R.string.chat_clear_history_yes), color = UnreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { clearHistoryChat = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }


    if (showMissedCallsSheet) {
        // 8.52：升级为全量通话记录——Room 未接（历史）+ CallLogStore（呼出/已接/未接）合并去重
        val callLogRows = remember(state.missedCalls) {
            val storeLog = runCatching { com.maodouchat.call.CallLogStore.list(context) }.getOrDefault(emptyList())
            val seen = HashSet<String>()
            buildList {
                // 8.53：CallLogStore 优先——同 id 竞态下（接听瞬间对端挂断）已接记录不被 Room 的 MISSED 覆盖
                storeLog.forEach { e ->
                    if (seen.add(e.id)) {
                        add(
                            CallLogRow(
                                id = e.id,
                                peerId = e.peerId,
                                peerName = e.peerName,
                                video = e.isVideo,
                                direction = e.direction,
                                state = e.state,
                                at = e.startedAt,
                                durationMs = e.durationMs
                            )
                        )
                    }
                }
                state.missedCalls.forEach { mc ->
                    if (seen.add(mc.id)) {
                        add(
                            CallLogRow(
                                id = mc.id,
                                peerId = mc.callerId,
                                peerName = mc.callerName,
                                video = mc.callType.equals("VIDEO", ignoreCase = true),
                                direction = com.maodouchat.call.CallLogStore.Direction.INCOMING,
                                state = com.maodouchat.call.CallLogStore.State.MISSED,
                                at = mc.receivedAt,
                                durationMs = 0L
                            )
                        )
                    }
                }
            }.sortedByDescending { it.at }
        }
        MissedCallsSheet(
            rows = callLogRows,
            onDismiss = { showMissedCallsSheet = false },
            onClear = {
                viewModel.clearMissedCalls()
                com.maodouchat.call.CallLogStore.clear(context)
                showMissedCallsSheet = false
            },
            onOpenChat = { userId, name, video ->
                showMissedCallsSheet = false
                val chatId = viewModel.findDirectChatIdForUser(userId)
                if (chatId != null) onChatClick(chatId)
                else if (video) onVideoCall(userId, name) else onVoiceCall(userId, name)
            },
            // 1.289：长按单条删除（与通话记录页一致；Room + CallLogStore + 本地 state 同步清理）
            onDeleteRow = { row ->
                com.maodouchat.call.CallLogStore.remove(context, row.id)
                viewModel.removeMissedCallLocally(row.id)
            }
        )
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false; createFolderError = null },
            title = { Text(stringResource(R.string.chat_folder_create)) },
            text = {
                Column {
                    TextField(value = createFolderName, onValueChange = { createFolderName = it }, singleLine = true)
                    createFolderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.createFolder(createFolderName)) { showCreateFolder = false; createFolderName = ""; createFolderError = null }
                    else createFolderError = context.getString(R.string.chat_folder_create_failed)
                }) { Text(stringResource(R.string.chat_folder_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    // 8.47：首次登录引导（去添加好友 / 扫一扫 / 稍后再说；任一动作后 markSeen 不再弹）
    if (showPostLoginGuide) {
        AlertDialog(
            onDismissRequest = {
                showPostLoginGuide = false
                com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
            },
            title = { Text(stringResource(R.string.post_login_guide_title)) },
            text = { Text(stringResource(R.string.post_login_guide_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showPostLoginGuide = false
                    com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
                    // 跳转通讯录 tab（MainTab.CONTACTS）
                    onNavigateToTab(MainTab.CONTACTS)
                }) { Text(stringResource(R.string.post_login_guide_add)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showPostLoginGuide = false
                        com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
                        onOpenScan()
                    }) { Text(stringResource(R.string.post_login_guide_scan)) }
                    TextButton(onClick = {
                        showPostLoginGuide = false
                        com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
                    }) { Text(stringResource(R.string.post_login_guide_later)) }
                }
            }
        )
    }

    if (showFolderManager) {
        AlertDialog(
            onDismissRequest = { showFolderManager = false },
            title = { Text(stringResource(R.string.chat_folder_manage)) },
            text = {
                Column {
                    if (state.folders.isEmpty()) Text(stringResource(R.string.chat_folder_list_empty))
                    else state.folders.forEach { folder ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(folder.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { renameFolderId = folder.id; renameFolderName = folder.name }) { Text(stringResource(R.string.chat_folder_rename)) }
                            TextButton(onClick = { viewModel.deleteFolder(folder.id) }) { Text(stringResource(R.string.chat_delete)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFolderManager = false }) { Text(stringResource(android.R.string.ok)) } }
        )
    }

    renameFolderId?.let { fid ->
        AlertDialog(
            onDismissRequest = { renameFolderId = null; renameFolderError = null },
            title = { Text(stringResource(R.string.chat_folder_manage)) },
            text = {
                Column {
                    TextField(value = renameFolderName, onValueChange = { renameFolderName = it }, singleLine = true)
                    renameFolderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.renameFolder(fid, renameFolderName)) { renameFolderId = null; renameFolderError = null }
                    else renameFolderError = context.getString(R.string.chat_folder_rename_failed)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renameFolderId = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    folderMoveChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { folderMoveChat = null },
            title = { Text(stringResource(R.string.chat_folder_manage)) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.moveChatToFolder(chat.id, null); folderMoveChat = null }) { Text(stringResource(R.string.chat_folder_show_all)) }
                    state.folders.forEach { folder ->
                        TextButton(onClick = { viewModel.moveChatToFolder(chat.id, folder.id); folderMoveChat = null }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { folderMoveChat = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    state.ownerTransferRequiredChatId?.let { chatId ->
        AlertDialog(
            onDismissRequest = { viewModel.clearOwnerTransferRequired() },
            title = { Text(stringResource(R.string.chat_leave_group)) },
            text = { Text(stringResource(R.string.chat_group)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearOwnerTransferRequired(); onOpenGroupDetail(chatId) }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}

@Composable
private fun ChatFolderStrip(
    folders: List<com.maodouchat.util.ChatFolder>,
    selectedFolderId: String?,
    secretChatCount: Int,
    lockedChatCount: Int,
    unreadInFolder: (String) -> Int,
    onSelectFolder: (String?) -> Unit,
    onManage: () -> Unit,
    onCreate: () -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderChip(stringResource(R.string.chat_folder_all), selectedFolderId.isNullOrBlank(), 0) { onSelectFolder(null) }
        FolderChip(stringResource(R.string.chat_folder_unread), selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_UNREAD_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_UNREAD_ID) }
        FolderChip(stringResource(R.string.chat_folder_groups), selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_GROUPS_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_GROUPS_ID) }
        FolderChip(stringResource(R.string.chat_folder_direct), selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_DIRECT_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_DIRECT_ID) }
        if (secretChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID) {
            FolderChip(stringResource(R.string.chat_folder_secret), selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID, secretChatCount) { onSelectFolder(ChatFolderPolicy.SYSTEM_SECRET_ID) }
        }
        if (lockedChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID) {
            FolderChip(stringResource(R.string.chat_folder_locked), selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID, lockedChatCount) { onSelectFolder(ChatFolderPolicy.SYSTEM_LOCKED_ID) }
        }
        folders.forEach { folder ->
            FolderChip(folder.name, selectedFolderId == folder.id, unreadInFolder(folder.id)) { onSelectFolder(folder.id) }
        }
        TextButton(onClick = onCreate) { Text(stringResource(R.string.chat_folder_create)) }
        TextButton(onClick = onManage) { Text(stringResource(R.string.chat_folder_manage)) }
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (badge > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(if (badge > 99) "99+" else badge.toString(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }
    )
}

@Composable
private fun ShimmerChatList() {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(8) { ShimmerChatRow() }
    }
}

@Composable
private fun EmptyChatState(
    hasSearchQuery: Boolean,
    showArchived: Boolean,
    selectedFolderId: String?,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
    onAddContact: () -> Unit,
    onScan: (() -> Unit)?
) {
    // 8.52 UX：初次加载失败且无本地缓存时，用错误空态代替误导性的「还没有聊天」+ 重试入口
    if (loadError != null && !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()) {
        EmptyState(
            type = EmptyStateType.NETWORK_ERROR,
            title = stringResource(R.string.chat_load_failed_title),
            subtitle = loadError,
            actionText = stringResource(R.string.chat_load_failed_retry),
            onAction = onRetry
        )
        return
    }
    val title = when {
        hasSearchQuery -> stringResource(R.string.chat_empty_search_title)
        showArchived -> stringResource(R.string.chat_empty_archived_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> stringResource(R.string.chat_folder_empty_unread_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> stringResource(R.string.chat_folder_empty_groups_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> stringResource(R.string.chat_folder_empty_direct_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> stringResource(R.string.chat_folder_empty_secret_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> stringResource(R.string.chat_folder_empty_locked_title)
        !selectedFolderId.isNullOrBlank() -> stringResource(R.string.chat_folder_empty)
        else -> stringResource(R.string.chat_empty_title)
    }
    val subtitle = when {
        hasSearchQuery -> stringResource(R.string.chat_empty_search_subtitle)
        showArchived -> stringResource(R.string.chat_empty_archived_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> stringResource(R.string.chat_folder_empty_unread_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> stringResource(R.string.chat_folder_empty_groups_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> stringResource(R.string.chat_folder_empty_direct_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> stringResource(R.string.chat_folder_empty_secret_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> stringResource(R.string.chat_folder_empty_locked_subtitle)
        // 8.45：此前误用「查看全部会话」操作按钮文案作空态副标题
        !selectedFolderId.isNullOrBlank() -> stringResource(R.string.chat_folder_empty_subtitle)
        else -> stringResource(R.string.chat_empty_subtitle)
    }
    val showActions = !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()
    EmptyState(
        type = if (hasSearchQuery) EmptyStateType.SEARCH else EmptyStateType.CHAT_LIST,
        title = title,
        subtitle = subtitle,
        actionText = if (showActions) stringResource(R.string.chat_empty_action_add) else null,
        onAction = if (showActions) onAddContact else null,
        secondaryActionText = if (showActions && onScan != null) stringResource(R.string.chat_empty_action_scan) else null,
        onSecondaryAction = if (showActions) onScan else null
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    chat: Chat,
    draft: ChatDraftEntity? = null,
    typingUserId: String? = null,
    scheduledCount: Int = 0,
    searchQuery: String = "",
    isLocked: Boolean = false,
    isSecret: Boolean = false,
    identityChanged: Boolean = false,
    isDeleting: Boolean = false,
    // 1.368：多选模式勾选态（勾选时显示选中复选框 + 高亮背景）
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onBadgeClick: (() -> Unit)? = null
) {
    val otherUser = chat.participants.firstOrNull()
    val displayName = when {
        chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_channel_default_name)
        chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_group)
        else -> otherUser?.displayName?.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_unknown)
    }
    val haptic = LocalHapticFeedback.current
    val hapticContext = LocalContext.current
    val motion = LocalMotionSettings.current
    val interactionSource = remember(chat.id) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.82f, stiffness = 520f),
        label = "chatListPressScale"
    )
    val hasUnread = chat.unreadCount > 0 || chat.markedUnread
    val unreadLabel = when {
        chat.unreadCount > 99 -> "99+"
        chat.unreadCount > 0 -> chat.unreadCount.toString()
        else -> ""
    }
    val listCtx = LocalContext.current
    val secretListBlock = isSecret && RuntimeFlags.isEnabled(listCtx, RuntimeFlags.SECRET_LIST_PREVIEW_BLOCK)
    val draftText = if (isLocked || secretListBlock) null else draft?.text?.takeIf(String::isNotBlank)
    val lockedPreview = stringResource(R.string.chat_lock_list_preview)
    val secretPreview = stringResource(R.string.secret_chat_notification_preview)
    val messagePreview = if (isLocked) lockedPreview
    else if (secretListBlock) secretPreview
    else when (chat.lastMessageType) {
        MessageType.IMAGE -> stringResource(R.string.message_preview_image)
        MessageType.GIF -> stringResource(R.string.message_preview_gif)
        MessageType.STICKER -> stringResource(R.string.message_preview_sticker)
        MessageType.LOCATION -> stringResource(R.string.message_preview_location)
        MessageType.VOICE -> stringResource(R.string.message_preview_voice)
        MessageType.VIDEO -> stringResource(R.string.message_preview_video)
        MessageType.FILE -> stringResource(R.string.message_preview_file)
        MessageType.NUDGE -> stringResource(R.string.message_preview_nudge)
        else -> com.maodouchat.ui.component.ChatMarkdown.stripContactCardMarker(chat.lastMessage)
    }
    // 1.103：对端正在输入 → 预览最优先（锁/密聊不泄露输入状态）
    val typingPreview = if (typingUserId != null && !isLocked && !secretListBlock) stringResource(R.string.chat_typing) else null
    val preview = typingPreview
        ?: if (!draftText.isNullOrBlank()) stringResource(R.string.chat_draft_prefix) + draftText
        else messagePreview
    // 1.146：待发送定时消息提示（最优先于草稿/消息预览）
    val scheduledLabel = if (scheduledCount > 0 && !isLocked) {
        stringResource(R.string.chat_scheduled_list_preview, scheduledCount)
    } else null
    val finalPreview = scheduledLabel ?: preview

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .alpha(if (isDeleting) 0.45f else 1f)
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = {
                    if (!isDeleting) onClick()
                },
                onLongClick = {
                    HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                    if (!isDeleting) onLongClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelecting) {
            // 1.368：多选模式前置复选框
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) Primary else TextHint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Avatar(
            name = displayName,
            avatarUrl = if (chat.isGroup) chat.groupAvatar else otherUser?.avatar,
            size = AvatarSize.MD,
            // 1.128：单聊显示对方在线绿点（群聊不显示）；1.141：密聊不显示（隐私）
            isOnline = !chat.isGroup && otherUser?.isOnline == true && !isSecret
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 1.148：搜索时关键词高亮
                if (searchQuery.isNotBlank()) {
                    Text(highlightedText(displayName, searchQuery), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    Text(displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Text(formatChatTime(chat.lastMessageTime), style = MaterialTheme.typography.labelSmall, color = TextHint)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 1.151：单聊显示「在线/最后在线」（密聊/群聊不显示）
                if (!chat.isGroup && !isSecret && otherUser != null) {
                    if (otherUser.isOnline) {
                        Text(stringResource(R.string.chat_online), color = Primary, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(4.dp))
                    } else if (otherUser.lastSeen > 0L) {
                        Text(
                            stringResource(R.string.user_last_seen_prefix) + " " +
                                android.text.format.DateUtils.getRelativeTimeSpanString(
                                    otherUser.lastSeen,
                                    System.currentTimeMillis(),
                                    android.text.format.DateUtils.MINUTE_IN_MILLIS
                                ),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                if (chat.pinnedAt > 0) {
                    Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
                    Spacer(Modifier.width(4.dp))
                }
                if (chat.isChannel) {
                    Icon(Icons.Outlined.Campaign, contentDescription = null, modifier = Modifier.size(14.dp), tint = Primary)
                    Spacer(Modifier.width(4.dp))
                }
                if (isSecret) {
                    Text(stringResource(R.string.secret_chat_list_indicator), color = Primary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                }
                // 1.165：身份密钥已变更（安全警告，红色）
                if (identityChanged) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = stringResource(R.string.chat_identity_changed_warning_short),
                        modifier = Modifier.size(14.dp),
                        tint = UnreadRed
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (isLocked) {
                    Text(stringResource(R.string.chat_lock_list_indicator), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                }
                if (chat.notificationsMuted) {
                    Icon(Icons.Outlined.NotificationsOff, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
                    Spacer(Modifier.width(4.dp))
                }
                // 0.70：会话免打扰时段显示（如「22:00–07:00 免扰」，否则用户无法在列表得知静音到何时）
                val quietWindow = com.maodouchat.notification.ChatQuietHoursStore.get(LocalContext.current, chat.id)
                // 1.02：临时静音至（优先显示，如「静音至 14:30」）
                val silentUntilMs = com.maodouchat.notification.ChatQuietHoursStore.silentUntil(LocalContext.current, chat.id)
                if (silentUntilMs > System.currentTimeMillis()) {
                    Text(
                        text = stringResource(
                            R.string.chat_silent_until_badge,
                            formatMinuteClock(((silentUntilMs % 86400000L) / 60000L).toInt())
                        ),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                } else if (quietWindow.enabled && quietWindow.startMinute != quietWindow.endMinute) {
                    Text(
                        text = stringResource(
                            R.string.chat_quiet_hours_badge,
                            formatMinuteClock(quietWindow.startMinute),
                            formatMinuteClock(quietWindow.endMinute)
                        ),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                // 1.227：草稿预览加铅笔图标
                if (!draftText.isNullOrBlank() && scheduledLabel == null && typingPreview == null) {
                    Icon(Icons.Outlined.EditNote, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (searchQuery.isNotBlank()) highlightedText(finalPreview, searchQuery) else androidx.compose.ui.text.AnnotatedString(finalPreview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (typingPreview != null) Primary else if (scheduledLabel != null) Secondary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasUnread) {
                    Spacer(Modifier.width(8.dp))
                    // 1.182：点击未读角标直接标记已读（不进入会话）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                            .then(if (onBadgeClick != null) Modifier.clickable(onClick = onBadgeClick!!) else Modifier)
                    ) {
                        Text(unreadLabel, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // 1.22：群公告预览（微信式群信息提示行）
            if (chat.isGroup && !chat.groupAnnouncement.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.chat_list_group_announcement_prefix) + chat.groupAnnouncement!!.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MissedCallsCard(calls: List<MissedCall>, onOpen: () -> Unit) {
    val unread = calls.count { !it.isRead }
    val title = if (unread > 0) stringResource(R.string.missed_calls_with_unread, calls.size, unread) else stringResource(R.string.missed_calls)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
            .combinedClickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(calls.firstOrNull()?.callerName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onOpen) { Text(stringResource(R.string.schedule_view_all)) }
    }
}

private data class CallLogRow(
    val id: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val direction: com.maodouchat.call.CallLogStore.Direction,
    val state: com.maodouchat.call.CallLogStore.State,
    val at: Long,
    val durationMs: Long
)

private fun formatCallDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = (durationMs / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MissedCallsSheet(
    rows: List<CallLogRow>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onOpenChat: (userId: String, name: String, video: Boolean) -> Unit,
    // 1.289：长按单条删除（与通话记录页单条删除一致）
    onDeleteRow: (CallLogRow) -> Unit = {}
) {
    val motion = LocalMotionSettings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_log_title)) },
        text = {
            if (rows.isEmpty()) Text(stringResource(R.string.missed_calls_empty))
            else LazyColumn {
                items(rows, key = { it.id }) { call ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = motion.listItemFadeInSpec(),
                                fadeOutSpec = motion.listItemFadeOutSpec(),
                                placementSpec = motion.listItemPlacementSpec()
                            )
                            .combinedClickable(
                                onClick = { onOpenChat(call.peerId, call.peerName, call.video) },
                                // 1.289：长按删除该条通话记录
                                onLongClick = { onDeleteRow(call) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            // CallMade/CallReceived 不在 material-icons-core；用 Call + tint 区分状态
                            imageVector = Icons.Filled.Call,
                            contentDescription = null,
                            tint = if (call.state == com.maodouchat.call.CallLogStore.State.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(call.peerName, fontWeight = FontWeight.Medium)
                            Text(
                                (if (call.video) stringResource(R.string.call_video) else stringResource(R.string.call_audio)) +
                                    " · " + relativeTime(call.at) +
                                    when (call.state) {
                                        com.maodouchat.call.CallLogStore.State.MISSED -> " · " + stringResource(R.string.missed_calls_badge)
                                        com.maodouchat.call.CallLogStore.State.ANSWERED -> {
                                            val d = formatCallDuration(call.durationMs)
                                            if (d.isNotEmpty()) " · " + d else ""
                                        }
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear) { Text(stringResource(R.string.chat_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
fun BottomNavBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    // 1.54：会话未读角标（ChatListViewModel 推送）
    val unreadTotal by UnreadBadgeStore.totalUnread.collectAsState()
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == MainTab.CHATS,
            onClick = { onTabSelected(MainTab.CHATS) },
            icon = {
                Box {
                    Icon(Icons.Outlined.ChatBubbleOutline, null)
                    if (unreadTotal > 0) {
                        Badge(modifier = Modifier.align(Alignment.TopEnd)) { Text(if (unreadTotal > 99) "99+" else unreadTotal.toString()) }
                    }
                }
            },
            label = { Text(stringResource(R.string.nav_chats)) }
        )
        NavigationBarItem(selected = selectedTab == MainTab.CONTACTS, onClick = { onTabSelected(MainTab.CONTACTS) }, icon = { Icon(Icons.Outlined.Group, null) }, label = { Text(stringResource(R.string.nav_contacts)) })
        // 1.112：动态未读互动角标
        val exploreBadge by ExploreBadgeStore.count.collectAsState()
        NavigationBarItem(
            selected = selectedTab == MainTab.EXPLORE,
            onClick = { onTabSelected(MainTab.EXPLORE) },
            icon = {
                Box {
                    Icon(Icons.Outlined.Explore, null)
                    if (exploreBadge > 0) {
                        Badge(modifier = Modifier.align(Alignment.TopEnd)) { Text(if (exploreBadge > 99) "99+" else exploreBadge.toString()) }
                    }
                }
            },
            label = { Text(stringResource(R.string.nav_explore)) }
        )
        NavigationBarItem(selected = selectedTab == MainTab.SETTINGS, onClick = { onTabSelected(MainTab.SETTINGS) }, icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) })
    }
}

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    // 8.45：此前硬编码 "now"/"5m"/"3h" 英文——改用地区分区间的相对时间（中英文界面均正确）
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        ts,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

private fun formatChatTime(ts: Long): String {
    if (ts <= 0L) return ""
    val cal = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = ts }
    return if (cal.get(Calendar.YEAR) == msg.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(ts))
    }
}

/**
 * 8.47：智能归档建议卡片（纯本地启发式，无 AI/服务端调用）。
 * 展示前 [suggestions] 条；每条可「归档」或「忽略」，整卡可一键关闭。
 */
@Composable
private fun ArchiveSuggestionsCard(
    suggestions: List<com.maodouchat.ai.AiArchiveSuggestion.Suggestion>,
    chatsById: Map<String, Chat>,
    onArchive: (String) -> Unit,
    onDismissOne: (String) -> Unit,
    onDismissAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ai_enhance_archive_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismissAll, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            stringResource(R.string.ai_enhance_archive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        suggestions.forEach { suggestion ->
            val chat = chatsById[suggestion.chatId]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = chatNameForSuggestion(chat) ?: suggestion.chatId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = suggestion.reason.take(40),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onArchive(suggestion.chatId) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.chat_archive), color = Primary, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onDismissOne(suggestion.chatId) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.common_later), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun chatNameForSuggestion(chat: Chat?): String? {
    if (chat == null) return null
    return chat.groupName
        ?: chat.participants.firstOrNull()?.displayName
        ?: chat.participants.firstOrNull()?.name
}

/** 0.70：分钟 → HH:mm（会话免打扰时段徽标用）。 */
private fun formatMinuteClock(minute: Int): String {
    val m = minute.coerceIn(0, 1439)
    return "%02d:%02d".format(m / 60, m % 60)
}

// 1.148：会话列表搜索关键词高亮（与全局搜索一致）
@androidx.compose.runtime.Composable
private fun highlightedText(text: String, query: String) = buildAnnotatedString {
    val snippet = remember(text, query) {
        GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    if (snippet.highlights.isEmpty()) {
        append(snippet.text)
        return@buildAnnotatedString
    }
    var cursor = 0
    snippet.highlights.forEach { span ->
        if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
        pushStyle(SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
        append(snippet.text.substring(span.start, span.end))
        pop()
        cursor = span.end
    }
    if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
}

