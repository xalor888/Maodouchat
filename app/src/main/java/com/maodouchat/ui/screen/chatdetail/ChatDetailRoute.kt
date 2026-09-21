@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.R
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.AiGroupTask
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.InlineTypingDots
import com.maodouchat.ui.component.TypingPresence
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.security.MessageSafetyScanner
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.security.findActivity
import com.maodouchat.ui.component.FloatingGlassTopBar
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.maodouchat.ui.component.ReplyPreview
import com.maodouchat.ui.component.ReplyTargetBar
import com.maodouchat.ui.component.ParticleDeleteEffect
import com.maodouchat.ui.component.ParticleState
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.bannerEnter
import com.maodouchat.ui.theme.composerBarEnter
import com.maodouchat.ui.theme.MotionPolicy
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.Divider
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.QrCodeGenerator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 8.43：图片发送前预览的待确认项（URI + 单次查看/剧透标记，选取时刻捕获）。 */
private enum class ParticleAction { DELETE, REVOKE }

internal data class TranslationLanguageOption(
    val wireValue: String,
    val labelResource: Int
)

internal val translationLanguageOptions = listOf(
    TranslationLanguageOption("中文", R.string.chat_language_chinese),
    TranslationLanguageOption("English", R.string.chat_language_english),
    TranslationLanguageOption("Japanese", R.string.chat_language_japanese),
    TranslationLanguageOption("Korean", R.string.chat_language_korean),
    TranslationLanguageOption("Spanish", R.string.chat_language_spanish),
    TranslationLanguageOption("French", R.string.chat_language_french),
    TranslationLanguageOption("German", R.string.chat_language_german),
    TranslationLanguageOption("Portuguese", R.string.chat_language_portuguese),
    TranslationLanguageOption("Russian", R.string.chat_language_russian),
    TranslationLanguageOption("Arabic", R.string.chat_language_arabic),
    TranslationLanguageOption("Thai", R.string.chat_language_thai),
    TranslationLanguageOption("Vietnamese", R.string.chat_language_vietnamese),
    TranslationLanguageOption("Indonesian", R.string.chat_language_indonesian),
    TranslationLanguageOption("Hindi", R.string.chat_language_hindi),
    TranslationLanguageOption("Italian", R.string.chat_language_italian),
    TranslationLanguageOption("Turkish", R.string.chat_language_turkish),
    TranslationLanguageOption("Dutch", R.string.chat_language_dutch),
    TranslationLanguageOption("Polish", R.string.chat_language_polish),
    TranslationLanguageOption("Swedish", R.string.chat_language_swedish),
    TranslationLanguageOption("Malay", R.string.chat_language_malay),
    TranslationLanguageOption("Finnish", R.string.chat_language_finnish),
    TranslationLanguageOption("Greek", R.string.chat_language_greek),
    TranslationLanguageOption("Czech", R.string.chat_language_czech),
    TranslationLanguageOption("Romanian", R.string.chat_language_romanian)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域；lint 无法区分
internal fun ChatDetailRoute(
    onBack: () -> Unit = {},
    onVoiceCall: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    onVideoCall: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    onOpenSecretChat: (chatId: String) -> Unit = {},
    onOpenGroupDetail: (chatId: String) -> Unit = {},
    onOpenStarredMessages: (chatId: String) -> Unit = {},
    onOpenMediaCenter: (chatId: String) -> Unit = {},
    onOpenAiTasks: (chatId: String) -> Unit = {},
    // 9.3xx：真实群功能页（投票/签到/接龙/PK）
    onOpenGroupPoll: (chatId: String) -> Unit = {},
    onOpenGroupCheckin: (chatId: String) -> Unit = {},
    onOpenGroupChain: (chatId: String) -> Unit = {},
    onOpenGroupPk: (chatId: String) -> Unit = {},
    // 1.17：点击消息内名片 → 打开对方资料
    onOpenProfile: ((userId: String) -> Unit)? = null,
    // 1.29：通话记录
    onOpenCallHistory: (() -> Unit)? = null,
    viewModel: ChatDetailViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    val listState = rememberLazyListState()
    val listScrollScope = rememberCoroutineScope()
    // 8.47：滚动合并执行器（B7 帧预算）——高频回底/跳转连点合并同帧请求，
    // 超距跳转瞬时 snap，避免长动画占帧（此前 4 处裸 animateScrollToItem）
    val chatListScroller = com.maodouchat.perf.rememberCoalescedScroller()
    val isNearBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    var pendingNewMessageCount by remember { mutableIntStateOf(0) }
    var lastAutoScrollMessageId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(state.openedSecretChatId) {
        val secretId = state.openedSecretChatId ?: return@LaunchedEffect
        viewModel.clearOpenedSecretChat()
        onOpenSecretChat(secretId)
    }

    var localSafetyEnabled by remember {
        mutableStateOf(com.maodouchat.ai.AiPrivacyPreferences.localSafetyEnabled(context))
    }
    var dismissedSafetyMessageIds by remember {
        mutableStateOf(com.maodouchat.ai.AiPrivacyPreferences.dismissedSafetyMessageIds(context))
    }
    fun dismissSafetyForMessage(messageId: String) {
        if (messageId.isBlank() || messageId in dismissedSafetyMessageIds) return
        val next = dismissedSafetyMessageIds + messageId
        dismissedSafetyMessageIds = next
        com.maodouchat.ai.AiPrivacyPreferences.setDismissedSafetyMessageIds(context, next)
    }
    // 9.150：壁纸/字号偏好改为可变状态并在 ON_RESUME 刷新——从设置页改完返回
    // 仍存活的聊天页实例不再持有陈旧背景/字号
    var chatWallpaperPreset by remember {
        mutableStateOf(com.maodouchat.util.ChatAppearancePreferences.getWallpaper(context))
    }
    // 自定义图片壁纸（本地 URI）：设置页选择图片后，聊天背景优先显示图片
    var customWallpaperUri by remember {
        mutableStateOf(com.maodouchat.util.ChatAppearancePreferences.getCustomWallpaperUri(context))
    }
    var chatFontScale by remember {
        mutableStateOf(com.maodouchat.util.ChatAppearancePreferences.getFontScale(context))
    }
    LaunchedEffect(viewModel.activeChatId, state.contact.id, state.chatIsGroup) {
        val peer = state.contact.id.takeIf { it.isNotBlank() && it != "me" && !state.chatIsGroup }
        when {
            state.chatIsGroup -> {
                viewModel.occupySessionCipher(
                    viewModel.activeChatId,
                    peerUserId = null,
                    updatePeer = true
                )
            }
            peer != null -> {
                viewModel.occupySessionCipher(
                    viewModel.activeChatId,
                    peer,
                    updatePeer = true
                )
            }
            else -> {
                // Contact not loaded yet — pin chatId only. Never pass updatePeer=true
                // with a blank peer: that clears openPeerUserId and lets list/backlog
                // decrypt the sibling DIRECT/SECRET ratchet.
                viewModel.occupySessionCipher(viewModel.activeChatId)
            }
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel.activeChatId, state.contact.id, state.chatIsGroup) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                localSafetyEnabled = com.maodouchat.ai.AiPrivacyPreferences.localSafetyEnabled(context)
                // 9.150：刷新外观偏好（设置页修改后返回即时生效）
                chatWallpaperPreset = com.maodouchat.util.ChatAppearancePreferences.getWallpaper(context)
                customWallpaperUri = com.maodouchat.util.ChatAppearancePreferences.getCustomWallpaperUri(context)
                chatFontScale = com.maodouchat.util.ChatAppearancePreferences.getFontScale(context)
                // 8.32 修复 F2：回到前台恢复 activeChatId（MainActivity.onPause 已清空），
                // 使「打开中的聊天」重新享有消息不弹通知/不计未读的语义。
                val resumePeer = state.contact.id.takeIf { it.isNotBlank() && it != "me" && !state.chatIsGroup }
                when {
                    state.chatIsGroup -> viewModel.occupySessionCipher(
                        viewModel.activeChatId,
                        peerUserId = null,
                        updatePeer = true
                    )
                    resumePeer != null -> viewModel.occupySessionCipher(
                        viewModel.activeChatId,
                        resumePeer,
                        updatePeer = true
                    )
                    else -> viewModel.occupySessionCipher(viewModel.activeChatId)
                }
                if (com.maodouchat.MaodouchatApp.activeChatOpenedAtMs == 0L) {
                    com.maodouchat.MaodouchatApp.activeChatOpenedAtMs = System.currentTimeMillis()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var messageToDelete by remember { mutableStateOf<Message?>(null) }
    var messageToRevoke by remember { mutableStateOf<Message?>(null) }
    var messageToCopy by remember { mutableStateOf<Message?>(null) }
    var messagesToForward by remember { mutableStateOf<List<Message>>(emptyList()) }
    var messageToRetry by remember { mutableStateOf<Message?>(null) }
    var messageToEdit by remember { mutableStateOf<Message?>(null) }
    var messageToActions by remember { mutableStateOf<Message?>(null) }
    var messageToRemind by remember { mutableStateOf<Message?>(null) }
    var messageToTranslate by remember { mutableStateOf<Message?>(null) }
    var messageToReport by remember { mutableStateOf<Message?>(null) }
    var messageForReadReceipts by remember { mutableStateOf<Message?>(null) }
    var messageToAnalyzeImage by remember { mutableStateOf<Message?>(null) }
    var messageToAnalyzeFile by remember { mutableStateOf<Message?>(null) }
    var fileQuestionMessage by remember { mutableStateOf<Message?>(null) }
    var fileQuestionDraft by rememberSaveable { mutableStateOf("") }
    var editDraft by rememberSaveable { mutableStateOf("") }
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    var showDateJumpDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchIndex by rememberSaveable { mutableIntStateOf(0) }
    var searchMode by rememberSaveable { mutableStateOf(ChatSearchMode.KEYWORD) }
    var searchScope by rememberSaveable { mutableStateOf(ChatSearchScope.ALL) }
    var searchWindow by rememberSaveable { mutableStateOf(ChatSearchWindow.ALL) }
    val chatAiSurfacesVisible = com.maodouchat.ai.AiEntryPolicy.shouldShowAiSurfaces(
        chatAiEnabled = state.aiEnabled,
        consentAccepted = com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(context),
        userEnabled = com.maodouchat.ai.AiPrivacyPreferences.userEnabled(context),
        masterEnabled = com.maodouchat.util.RuntimeFlags.isEnabled(
            context,
            com.maodouchat.util.RuntimeFlags.AI_MASTER
        )
    )
    androidx.compose.runtime.LaunchedEffect(chatAiSurfacesVisible) {
        if (!chatAiSurfacesVisible && searchMode == ChatSearchMode.SEMANTIC) {
            searchMode = ChatSearchMode.KEYWORD
            viewModel.clearSemanticSearch()
        }
    }
    var fullScreenImage by remember { mutableStateOf<Message?>(null) }
    var fullScreenVideo by remember { mutableStateOf<Message?>(null) }
    // 0.83：清空本机聊天记录确认
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    val chatSnackbarHostState = remember { SnackbarHostState() }
    var replyTarget by remember { mutableStateOf<Message?>(null) }
    var selectedMessageIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var animatingMessageId by remember { mutableStateOf<String?>(null) }
    var particleAction by remember { mutableStateOf<ParticleAction?>(null) }
    var showGroupInfo by rememberSaveable { mutableStateOf(false) }
    var showGroupCallTypeDialog by rememberSaveable { mutableStateOf(false) }
    var showGroupCallMemberDialog by rememberSaveable { mutableStateOf(false) }
    var pendingGroupCallType by rememberSaveable { mutableStateOf<com.maodouchat.webrtc.CallType?>(null) }
    var selectedGroupCallMemberIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var groupCallMemberSearch by rememberSaveable { mutableStateOf("") }
    var showContactActions by rememberSaveable { mutableStateOf(false) }
    var showContactProfile by rememberSaveable { mutableStateOf(false) }
    // 1.11：发送名片——联系人选择对话框
    var showContactCardPicker by rememberSaveable { mutableStateOf(false) }
    var showChatOverflow by remember { mutableStateOf(false) }
    var showDisappearDialog by rememberSaveable { mutableStateOf(false) }
    var showQuietHoursDialog by rememberSaveable { mutableStateOf(false) }
    // 1.02：临时静音至对话框
    var showSilentUntilDialog by rememberSaveable { mutableStateOf(false) }
    var showReminderList by rememberSaveable { mutableStateOf(false) }
    var showScheduleDialog by rememberSaveable { mutableStateOf(false) }
    var showScheduledList by rememberSaveable { mutableStateOf(false) }
    var rescheduleTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSetChatLock by rememberSaveable { mutableStateOf(false) }
    var showDisableChatLock by rememberSaveable { mutableStateOf(false) }
    var showForgotChatLockConfirm by rememberSaveable { mutableStateOf(false) }
    var showAnnouncementBanner by rememberSaveable { mutableStateOf(true) }
    var showAnnouncementDialog by rememberSaveable { mutableStateOf(false) }
    var showSecretChatConfirm by rememberSaveable { mutableStateOf(false) }
    var showLiveLocationDuration by rememberSaveable { mutableStateOf(false) }
    var pendingLiveLocationPermission by rememberSaveable { mutableStateOf(false) }

    var setLockPinDraft by rememberSaveable { mutableStateOf("") }
    var setLockPinConfirm by rememberSaveable { mutableStateOf("") }
    var disableLockPinDraft by rememberSaveable { mutableStateOf("") }
    var setLockError by remember { mutableStateOf<String?>(null) }
    var showGifSearch by rememberSaveable { mutableStateOf(false) }
    var showReportContactDialog by rememberSaveable { mutableStateOf(false) }
    var showAiSummaryScopeDialog by rememberSaveable { mutableStateOf(false) }
    var particleStates by remember { mutableStateOf<List<ParticleState>>(emptyList()) }
    var navigationHighlightMessageId by remember { mutableStateOf<String?>(null) }
    val bubbleBounds = remember { mutableMapOf<String, BubbleBounds>() }
    val configuration = LocalConfiguration.current
    // 9.205：用主题真实深浅替代系统深浅/palette 身份比较（TG 主题与强制模式下不再误判）
    val isDarkChat = com.maodouchat.ui.theme.LocalDarkTheme.current
    val themeChatPalette = LocalChatPalette.current
    val chatBackgroundColor = remember(chatWallpaperPreset, isDarkChat, themeChatPalette) {
        com.maodouchat.util.ChatAppearancePolicy.resolveBackground(
            preset = chatWallpaperPreset,
            isDark = isDarkChat,
            fallback = themeChatPalette.chatBackground
        )
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, chatFontScale) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density,
            fontScale = (baseDensity.fontScale * chatFontScale.multiplier).coerceIn(0.85f, 1.6f)
        )
    }
    val sensitiveAuthTitle = stringResource(R.string.sensitive_auth_title)
    val sensitiveAuthExport = stringResource(R.string.sensitive_auth_export_chat)
    val sensitiveAuthClearHistory = stringResource(R.string.sensitive_auth_clear_chat)
    val sensitiveAuthFailed = stringResource(R.string.sensitive_auth_failed)
    val chatPermissionRecordMsg = stringResource(R.string.chat_permission_record)
    val chatPermissionVoiceCallMsg = stringResource(R.string.chat_permission_voice_call)
    val chatPermissionVideoCallMsg = stringResource(R.string.chat_permission_video_call)
    val chatPermissionLocationMsg = stringResource(R.string.chat_permission_location)
    val chatCopiedMsg = stringResource(R.string.chat_copied)
    val chatAiImageResultTitle = stringResource(R.string.chat_ai_image_result_title)
    val chatAiFileResultTitle = stringResource(R.string.chat_ai_file_result_title)
    val chatGroupAiTitle = stringResource(R.string.chat_group_ai_title)
    val chatGroupAiCopiedMsg = stringResource(R.string.chat_group_ai_copied)
    val chatClipboardMessageLabel = stringResource(R.string.chat_clipboard_message)
    val chatClipboardTranslationLabel = stringResource(R.string.chat_clipboard_translation)
    val chatTranslationCopiedMsg = stringResource(R.string.chat_translation_copied)
    val chatClipboardTranscriptLabel = stringResource(R.string.chat_clipboard_transcript)
    val chatTranscriptCopiedMsg = stringResource(R.string.chat_transcript_copied)
    val chatItems = remember(state.messages, state.unreadSeparatorId, configuration.locales) {
        buildChatItems(
            state.messages,
            labelForTimestamp = { timestamp -> formatDateLabel(context, timestamp) },
            unreadSeparatorId = state.unreadSeparatorId
        )
    }
    val reversedChatItems = remember(chatItems) { chatItems.asReversed() }
    // 1.05：语音连续播放——一条语音自然播放结束后自动播下一条同会话语音
    LaunchedEffect(com.maodouchat.util.VoicePlayer.lastCompletedId) {
        val completed = com.maodouchat.util.VoicePlayer.lastCompletedId ?: return@LaunchedEffect
        val voiceMessages = state.messages.filter { it.type == MessageType.VOICE }
        val completedMsg = voiceMessages.firstOrNull { it.id == completed } ?: return@LaunchedEffect
        val next = voiceMessages
            .filter { m -> m.timestamp > completedMsg.timestamp || (m.timestamp == completedMsg.timestamp && m.id > completedMsg.id) }
            .minByOrNull { it.timestamp }
        next?.let {
            com.maodouchat.util.VoicePlayer.ensureContext(context)
            com.maodouchat.util.VoicePlayer.play(it.id, it.parsedContent(), context)
        }
    }
    val shouldLoadOlderMessages by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val oldestVisibleIndex = layout.visibleItemsInfo.maxOfOrNull { it.index } ?: return@derivedStateOf false
            layout.totalItemsCount > 0 && oldestVisibleIndex >= layout.totalItemsCount - 6
        }
    }
    val messagesById = remember(state.messages) { state.messages.associateBy(Message::id) }
    // Bot force-reply: focus composer as reply to latest forced message from peer/bot.
    LaunchedEffect(state.messages.lastOrNull()?.id, state.currentUserId) {
        val last = state.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.senderId == state.currentUserId) return@LaunchedEffect
        val meta = last.parsedMeta()
        if (meta.forceReply && replyTarget?.id != last.id) {
            replyTarget = last
        }
    }
    val participantNamesById = remember(state.chat?.participants, state.memberNicknameByUser, state.chatIsGroup) {
        val base = state.chat?.participants.orEmpty().associate { it.id to it.displayName }
        // 0.69 修复：群聊优先使用群内昵称（此前群昵称只作用于群成员列表，消息不生效）
        if (state.chatIsGroup && state.memberNicknameByUser.isNotEmpty()) {
            base + state.memberNicknameByUser
        } else {
            base
        }
    }
    val unknownSenderLabel = stringResource(R.string.chat_unknown)
    val groupMemberLabel = stringResource(R.string.chat_group_member)
    fun resolveSenderName(
        message: Message,
        isOwn: Boolean = message.senderId == state.currentUserId,
    ): String? = senderDisplayName(
        state = state,
        message = message,
        isOwn = isOwn,
        participantNamesById = participantNamesById,
        unknownLabel = unknownSenderLabel,
        groupMemberLabel = groupMemberLabel,
    )
    val headerStatus = resolveChatHeaderStatus(
        typingUserId = state.typingContact,
        isOnline = state.contact.isOnline,
        customStatus = state.contact.status,
        isGroup = state.chatIsGroup,
        lastSeen = if (state.isSecretChat == true && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_LAST_SEEN_BLOCK)) 0L else state.contact.lastSeen
    )
    val selectedMessages = remember(state.messages, selectedMessageIds) {
        state.messages.filter { it.id in selectedMessageIds }
    }
    val messageSelectionMode = selectedMessageIds.isNotEmpty()
    BackHandler(enabled = showChatOverflow || messageSelectionMode || showSearchBar) {
        when {
            showChatOverflow -> showChatOverflow = false
            messageSelectionMode -> selectedMessageIds = emptySet()
            showSearchBar -> showSearchBar = false
        }
    }
    val searchDocuments = remember(state.messages) { buildChatSearchDocuments(state.messages) }
    val localSearchResults = remember(searchQuery, searchScope, searchWindow, searchDocuments) {
        searchChatDocuments(
            documents = searchDocuments,
            query = searchQuery,
            scope = searchScope,
            window = searchWindow,
            currentUserId = state.currentUserId
        )
    }
    val semanticCandidates = remember(showSearchBar, searchMode, searchScope, searchWindow, searchDocuments) {
        if (showSearchBar && searchMode == ChatSearchMode.SEMANTIC) {
            semanticSearchCandidates(searchDocuments, searchScope, searchWindow, currentUserId = state.currentUserId)
        } else {
            emptyList()
        }
    }
    val semanticSearchResults = remember(
        state.semanticSearchResultIds,
        state.semanticSearchQuery,
        searchQuery,
        state.messages
    ) {
        if (state.semanticSearchQuery != searchQuery.trim()) {
            emptyList()
        } else {
            state.semanticSearchResultIds.mapNotNull(messagesById::get)
        }
    }
    val searchResults = if (searchMode == ChatSearchMode.SEMANTIC) semanticSearchResults else localSearchResults

    // 8.48：禁言到期重组触发器（到期写入后提示条随重组消失）
    var muteTick by remember { mutableLongStateOf(0L) }
    var pendingViewOnce by remember { mutableStateOf(false) }
    var pendingSpoiler by remember { mutableStateOf(false) }
    var pendingImageConfirm by remember { mutableStateOf<PendingImageSend?>(null) }
    var pendingVideoConfirm by remember { mutableStateOf<PendingImageSend?>(null) }
    // Photo Picker (PickVisualMedia) on some AVDs finishes MainActivity and lands on the launcher.
    // GetContent stays in our task and is enough for IMAGE/VIDEO send confirmation.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingImageConfirm = PendingImageSend(it, pendingViewOnce, pendingSpoiler)
        }
        pendingViewOnce = false
        pendingSpoiler = false
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // 0.69：视频改为先预览确认（与图片一致），确认后才发送
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingVideoConfirm = PendingImageSend(it, pendingViewOnce, pendingSpoiler)
        }
        pendingViewOnce = false
        pendingSpoiler = false
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.sendFile(it)
        }
    }

    val gifPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.sendGif(it)
            showGifSearch = false
        }
    }

    val gifMediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* GifSearchDialog reloads when recomposed after grant */ }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
        else Toast.makeText(context, chatPermissionRecordMsg, Toast.LENGTH_SHORT).show()
    }

    val voiceCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onVoiceCall(state.contact.id, state.contact.name)
        else Toast.makeText(context, chatPermissionVoiceCallMsg, Toast.LENGTH_SHORT).show()
    }

    val videoCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasAudio = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasAudio && hasCamera) onVideoCall(state.contact.id, state.contact.name)
        else Toast.makeText(context, chatPermissionVideoCallMsg, Toast.LENGTH_SHORT).show()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            com.maodouchat.util.LocationProvider.hasLocationPermission(context)
        if (granted) {
            if (pendingLiveLocationPermission) showLiveLocationDuration = true
            else viewModel.sendCurrentLocation()
        } else {
            Toast.makeText(context, chatPermissionLocationMsg, Toast.LENGTH_SHORT).show()
        }
        pendingLiveLocationPermission = false
    }

    // 粒子动效入口：提前在 @Composable 上下文中抓取 palette，避免在本地函数里调用
    val palette = LocalChatPalette.current
    val ownBubbleColor = com.maodouchat.ui.theme.LocalChatBubbleColor.current
    fun startParticleEffect(message: Message, action: ParticleAction) {
        val isOwn = message.senderId == state.currentUserId
        val bounds = bubbleBounds[message.id] ?: BubbleBounds(
            androidx.compose.ui.unit.IntOffset(if (isOwn) 280 else 80, 220),
            androidx.compose.ui.unit.IntSize(220, 56)
        )
        val bubbleColor = if (isOwn) ownBubbleColor else palette.chatBubbleReceived
        animatingMessageId = message.id
        particleAction = action
        particleStates = listOf(ParticleState(message.id, bounds.offset, bounds.size, bubbleColor))
    }

    LaunchedEffect(
        state.messages.lastOrNull()?.id,
        state.currentUserId,
        state.initialTimelineReady,
        reversedChatItems.size
    ) {
        val latestMessage = state.messages.lastOrNull() ?: return@LaunchedEffect
        val latestId = latestMessage.id
        if (state.navigationTargetMessageId != null || navigationHighlightMessageId != null) {
            lastAutoScrollMessageId = latestId
            return@LaunchedEffect
        }
        val previousId = lastAutoScrollMessageId
        lastAutoScrollMessageId = latestId
        // Open-chat: local seed paints an older tail first; history then prepends newer
        // bubbles in reverseLayout. Keep index 0 until that merge finishes, otherwise
        // the viewport stays on yesterday while list preview already shows today.
        val openingPin = !state.initialTimelineReady || previousId == null
        if (previousId == latestId && !openingPin) return@LaunchedEffect
        val shouldStickToBottom = openingPin || isNearBottom || latestMessage.senderId == state.currentUserId
        if (shouldStickToBottom) {
            chatListScroller.scrollToItem(listState, 0, animated = !openingPin)
            pendingNewMessageCount = 0
        } else if (latestMessage.senderId != state.currentUserId && previousId != latestId) {
            pendingNewMessageCount += 1
        }
    }

    LaunchedEffect(isNearBottom) {
        if (isNearBottom) pendingNewMessageCount = 0
    }

    LaunchedEffect(shouldLoadOlderMessages) {
        if (shouldLoadOlderMessages) viewModel.loadOlderMessages()
    }

    LaunchedEffect(state.fileReadyToOpenUri) {
        val uri = state.fileReadyToOpenUri ?: return@LaunchedEffect
        openFile(context, uri)
        viewModel.consumeFileReadyToOpen()
    }

    LaunchedEffect(state.scheduledInfoMessage) {
        val msg = state.scheduledInfoMessage ?: return@LaunchedEffect
        chatSnackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearScheduledInfo()
    }

    LaunchedEffect(state.exportInfoMessage) {
        val msg = state.exportInfoMessage ?: return@LaunchedEffect
        chatSnackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearExportInfo()
    }

    LaunchedEffect(state.chatLockInfoMessage) {
        val msg = state.chatLockInfoMessage ?: return@LaunchedEffect
        chatSnackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearChatLockInfo()
    }

    // 密聊 / 阅后即焚：MediaStore 旁路截屏/录屏检测（FLAG_SECURE 之外的补充）
    val disappearingActive = !state.chatIsGroup && state.disappearingMessageSeconds > 0
    val secretActive = state.isSecretChat == true

    // B2 密聊 TTL（ttlz）：会话活跃心跳——进入与驻留期间持续更新 lastActivityAt，避免无活动误销毁。
    // G9：逻辑已抽到 SecretChatActivityHeartbeat（非 Composable，可单测），这里只负责按会话触发。
    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        com.maodouchat.security.SecretChatActivityHeartbeat.start(context, chatId)
    }

    // B2 双因素门禁（2faz）：进入密聊会话前需系统认证，验证后窗口期内免重复验证
    var secretGateDismissed by remember { mutableStateOf(false) }
    var secretGateBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(secretActive, state.chat?.id, secretGateDismissed) {
        if (!secretActive) {
            secretGateBlocked = false
            return@LaunchedEffect
        }
        secretGateDismissed = false
        if (com.maodouchat.util.Secret2faGatePrefs.isGateOpen(context)) {
            secretGateBlocked = false
            return@LaunchedEffect
        }
        secretGateBlocked = true
        com.maodouchat.security.SensitiveActionGate.confirmSystemAuth(
            context = context,
            title = context.getString(R.string.secret_2fa_gate_title),
            subtitle = context.getString(R.string.secret_2fa_gate_subtitle),
            onSuccess = {
                secretGateBlocked = false
                com.maodouchat.util.Secret2faGatePrefs.markVerified(context)
            },
            onFailure = {
                secretGateDismissed = true
                Toast.makeText(context, context.getString(R.string.secret_2fa_gate_verify_hint), Toast.LENGTH_LONG).show()
            }
        )
    }

    // B2 设备核验（dvz）：进入密聊时若开关开启且对端指纹未核验 → 自动弹出安全码页；用户验证后不再弹
    var deviceVerifyPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(secretActive, state.chat?.id, state.contactIdentityFingerprint, secretGateBlocked) {
        if (!secretActive) {
            deviceVerifyPrompted = false
            return@LaunchedEffect
        }
        if (secretGateBlocked) return@LaunchedEffect
        if (deviceVerifyPrompted) return@LaunchedEffect
        if (!com.maodouchat.util.SecretDeviceVerifyPrefs.isEnabled(context)) return@LaunchedEffect
        val fp = state.contactIdentityFingerprint?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (com.maodouchat.util.SecretDeviceVerifyPrefs.isFingerprintVerified(context, fp)) return@LaunchedEffect
        deviceVerifyPrompted = true
        viewModel.showSafetyCodeDialog()
    }

    // B2 新设备风控（ndz）：首次进入密聊需登记本机设备指纹；未登记提示并保持锁定
    var deviceRiskPrompted by remember { mutableStateOf(false) }
    var showDeviceRiskDialog by remember { mutableStateOf(false) }
    var deviceRiskLocked by remember { mutableStateOf(false) }
    // 设备指纹 = 安装级 UUID（跨重启稳定；本应用关闭系统备份，重装后 SharedPreferences
    // 清空 → 重新生成 → 视为新设备）。此前用「当前日期窗」导致每天变化，已登记设备
    // 次日被误判为新设备，改为稳定的安装标识。
    val deviceRiskId = remember(context) {
        com.maodouchat.push.PushRegistrationManager.currentDeviceId(context)
    }
    LaunchedEffect(secretActive, state.chat?.id, deviceRiskPrompted, deviceRiskLocked, secretGateBlocked) {
        if (!secretActive) {
            deviceRiskPrompted = false
            return@LaunchedEffect
        }
        if (secretGateBlocked) return@LaunchedEffect
        if (deviceRiskPrompted) return@LaunchedEffect
        if (!com.maodouchat.util.SecretNewDeviceRiskPrefs.isEnabled(context)) return@LaunchedEffect
        if (deviceRiskId.isBlank() || com.maodouchat.util.SecretNewDeviceRiskPrefs.isDeviceTrusted(context, deviceRiskId)) return@LaunchedEffect
        deviceRiskPrompted = true
        showDeviceRiskDialog = true
    }
    if (showDeviceRiskDialog) {
        AlertDialog(
            onDismissRequest = { /* 未登记设备必须决策 */ },
            title = { Text(stringResource(R.string.secret_new_device_risk_prompt_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(R.string.secret_new_device_risk_prompt_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = {
                    showDeviceRiskDialog = false
                    deviceRiskLocked = false
                    if (deviceRiskId.isNotBlank()) {
                        com.maodouchat.util.SecretNewDeviceRiskPrefs.registerDevice(context, deviceRiskId)
                        Toast.makeText(context, context.getString(R.string.secret_new_device_risk_registered), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeviceRiskDialog = false
                    deviceRiskLocked = true
                    Toast.makeText(context, context.getString(R.string.secret_new_device_risk_locked), Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // B4 本地 AI 聚合：会话画像 / 本周周报（仅非密聊会话，密聊不参与避免落可搜索缓存）
    var showConversationProfile by rememberSaveable { mutableStateOf(false) }
    var conversationProfile by remember { mutableStateOf<com.maodouchat.ai.AiConversationProfile.ConversationProfile?>(null) }
    var conversationProfileLoading by remember { mutableStateOf(false) }
    var conversationProfileFailed by remember { mutableStateOf(false) }
    var showWeeklyReport by rememberSaveable { mutableStateOf(false) }
    var weeklyReport by remember { mutableStateOf<com.maodouchat.ai.AiWeeklyReport.WeeklyReport?>(null) }
    var weeklyReportLoading by remember { mutableStateOf(false) }
    var weeklyReportFailed by remember { mutableStateOf(false) }
    var emotionReplyRequested by remember { mutableStateOf(false) }
    // 8.47：消息分类（纯本地词典统计）
    var showMessageClassify by rememberSaveable { mutableStateOf(false) }
    var chatClassifications by remember { mutableStateOf<List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>>(emptyList()) }
    var classifyLoading by remember { mutableStateOf(false) }
    var classifyFailed by remember { mutableStateOf(false) }
    LaunchedEffect(showMessageClassify, state.chat?.id) {
        if (!showMessageClassify) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        classifyLoading = true
        classifyFailed = false
        chatClassifications = emptyList()
        // G73：经端口调用，不再自己抓 app 数据库单例
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { viewModel.aiChatClassificationSource.classify(chatId) }
        }
        classifyLoading = false
        classifyFailed = result.isFailure
        result.getOrNull()?.let { chatClassifications = it }
    }
    LaunchedEffect(emotionReplyRequested, state.chat?.id) {
        if (!emotionReplyRequested) return@LaunchedEffect
        emotionReplyRequested = false
        val chatId = state.chat?.id ?: return@LaunchedEffect
        // G73：经端口调用，不再自己抓 app 数据库单例
        val reply = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiEmotionReplySource.reply(chatId)
        }
        if (reply.isNotBlank()) {
            viewModel.onInputChange(reply)
            Toast.makeText(context, context.getString(R.string.chat_ai_emotion_reply_generated), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.chat_ai_emotion_reply_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(showConversationProfile, state.chat?.id) {
        if (!showConversationProfile) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        conversationProfileLoading = true
        conversationProfileFailed = false
        // G73：经 ViewModel 暴露的端口调用，不再自己抓 app 数据库单例
        val built = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiConversationProfileSource.build(chatId)
        }
        if (built.local.messageCount > 0 || !built.narrative.isNullOrBlank()) {
            conversationProfile = built
        } else {
            conversationProfileFailed = true
        }
        conversationProfileLoading = false
    }
    LaunchedEffect(showWeeklyReport, state.chat?.id) {
        if (!showWeeklyReport) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        weeklyReportLoading = true
        weeklyReportFailed = false
        // G73：经端口调用，不再自己抓 app 数据库单例
        weeklyReport = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiWeeklyReportSource.generate(chatId)
        }
        weeklyReportFailed = weeklyReport == null
        weeklyReportLoading = false
    }

    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        while (true) {
            viewModel.refreshSealedSenderCertificate()
            kotlinx.coroutines.delay(10 * 60 * 1000L)
        }
    }
    val captureGuardActive = disappearingActive || secretActive
    val screenshotMsgDisappear = stringResource(R.string.chat_screenshot_detected_disappearing)
    val screenshotMsgSecret = stringResource(R.string.secret_chat_screenshot_detected)
    LaunchedEffect(captureGuardActive, disappearingActive, secretActive) {
        if (!captureGuardActive) return@LaunchedEffect
        // 密聊必须始终启动检测器：FLAG_SECURE 挡住系统截屏时不会有 MediaStore 事件，
        // 检测器只覆盖 OEM / adb / 外置相机等绕过。SCREENSHOT_DETECT 只闸非密聊阅后即焚。
        val detectEnabled = com.maodouchat.security.ScreenshotDetector.shouldStart(
            secretActive = secretActive,
            screenshotDetectFlag = RuntimeFlags.isEnabled(context, RuntimeFlags.SCREENSHOT_DETECT)
        )
        if (!detectEnabled) return@LaunchedEffect
        val detector = com.maodouchat.security.ScreenshotDetector(context) {
            val msg = when {
                secretActive && disappearingActive ->
                    screenshotMsgSecret + " · " + screenshotMsgDisappear
                secretActive -> screenshotMsgSecret
                else -> screenshotMsgDisappear
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.notifyLocalCaptureDetected(msg)
        }
        detector.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            detector.stop()
        }
    }

    // B2 截屏即焚（surface #71 burnz）：仅密聊会话启用——检测到截屏/录屏立即焚毁本地解密缓存。
    // 与上方 ScreenshotDetector（告警）并存：即焚是更强动作，只清理本机缓存、不触碰服务端。
    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        val burnDetector = com.maodouchat.security.ScreenshotBurnDetector(context) { chatIds ->
            if (chatIds.isNotEmpty()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.secret_chat_screenshot_burned),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        burnDetector.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            burnDetector.stop()
        }
    }

    LaunchedEffect(state.secretChatInfoMessage) {
        val msg = state.secretChatInfoMessage ?: return@LaunchedEffect
        chatSnackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearSecretChatInfo()
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        chatSnackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearErrorMessage()
    }

    LaunchedEffect(state.infoMessage) {
        val msg = state.infoMessage ?: return@LaunchedEffect
        chatSnackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearInfoMessage()
    }

    // Keep FLAG_SECURE in sync when 密聊 toggles without navigation.
    // LocalContext is usually a ContextWrapper — unwrap, do not cast directly.
    // isSecretChat == null 是尚未查库：不得当成非密聊去清标记（会在乐观窗口里拆掉 FLAG_SECURE）。
    DisposableEffect(state.isSecretChat, state.chat?.id) {
        val chatId = state.chat?.id
        val secretState = state.isSecretChat
        val activity = context.findActivity() as? com.maodouchat.MainActivity
        if (chatId != null && secretState != null) {
            activity?.notifySecretChatSurfaceChanged(chatId, secretState)
        }
        onDispose {
            if (chatId != null && secretState == true) {
                activity?.notifySecretChatSurfaceLeft(chatId)
            }
        }
    }

    val chatLockPending = state.isChatLocked == null
    val chatLockBlocking = state.isChatLocked == true && !state.isChatUnlocked

    // 会话 PIN 锁（ChatLockGate）显示期间强制 FLAG_SECURE：PIN 属敏感信息，
    // 即便全局截屏防护关闭、也非密聊，也须阻止截屏/录屏。复用 MainActivity 的中心化机制，
    // 由 refreshWindowPrivacy 统一 addFlags/clearFlags，避免在解锁密聊会话时误清 FLAG_SECURE。
    DisposableEffect(chatLockBlocking) {
        val activity = context.findActivity() as? com.maodouchat.MainActivity
        activity?.notifyChatLockSurfaceChanged(chatLockBlocking)
        onDispose {
            if (chatLockBlocking) {
                activity?.notifyChatLockSurfaceChanged(false)
            }
        }
    }
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat == true,
        userId = com.maodouchat.network.TokenManager.getInstance(context).getUserId(),
        chatId = state.chat?.id,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    LaunchedEffect(searchResults, searchIndex) {
        val target = searchResults.getOrNull(searchIndex) ?: return@LaunchedEffect
        val targetIndex = reversedChatItems.indexOfFirst { it is ChatItem.Msg && it.message.id == target.id }
        if (targetIndex >= 0) {
            chatListScroller.scrollToItem(listState, targetIndex)
            // 1.343：搜索当前结果消息闪烁高亮（复用导航高亮机制，便于定位）
            navigationHighlightMessageId = target.id
            try {
                kotlinx.coroutines.delay(1_800)
            } finally {
                if (navigationHighlightMessageId == target.id) navigationHighlightMessageId = null
            }
        }
    }

    LaunchedEffect(state.navigationTargetMessageId, reversedChatItems.size) {
        val targetId = state.navigationTargetMessageId ?: return@LaunchedEffect
        val targetIndex = reversedChatItems.indexOfFirst { it is ChatItem.Msg && it.message.id == targetId }
        if (targetIndex < 0) {
            // 目标消息尚未加载（引用/置顶跳转可能指向已滚出当前窗口的旧消息）：
            // 还有更早历史则继续翻页加载，翻完仍不存在（消息被删/目标无效）则放弃并提示。
            if (state.hasMoreOlderMessages) {
                viewModel.loadOlderMessages()
            } else {
                viewModel.consumeNavigationTarget()
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_reply_target_not_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return@LaunchedEffect
        }
       chatListScroller.scrollToItem(listState, targetIndex)
       navigationHighlightMessageId = targetId
        try {
            kotlinx.coroutines.delay(1_800)
        } finally {
            if (navigationHighlightMessageId == targetId) navigationHighlightMessageId = null
        }
        viewModel.consumeNavigationTarget()
    }

    LaunchedEffect(searchMode, searchScope, searchWindow) {
        searchIndex = 0
    }

    LaunchedEffect(searchResults.size) {
        if (searchResults.isEmpty()) searchIndex = 0
        else if (searchIndex >= searchResults.size) searchIndex = searchResults.lastIndex
    }

    LaunchedEffect(showGroupInfo, state.chat?.id) {
        if (showGroupInfo && state.chatIsGroup) viewModel.loadGroupCandidates()
    }

    // G77：群信息对话框（190 行）抽到 ChatDetailGroupInfoDialog.kt，纯搬移不改判断。
    // 块内三个 groupInfo* 状态的所有权随之搬走，Route 不再持有。
    if (showGroupInfo) {
        ChatDetailGroupInfoDialog(
            chat = state.chat,
            currentUserId = state.currentUserId,
            groupCandidates = state.groupCandidates,
            isUpdatingGroup = state.isUpdatingGroup,
            onDismiss = { showGroupInfo = false },
            onRenameGroup = { name -> viewModel.renameGroup(name) },
            onAddGroupMember = { userId -> viewModel.addGroupMember(userId) },
            onRemoveGroupMember = { userId -> viewModel.removeGroupMember(userId) },
        )
    }

    if (state.showSafetyCodeDialog && !state.chatIsGroup) {
        SafetyCodeDialog(
            contactName = state.contact.name,
            contactId = state.contact.id,
            trustState = state.trustState,
            isGroup = state.chatIsGroup,
            safetyCode = state.safetyCode,
            warning = state.identityWarning,
            currentUserId = state.currentUserId,
            currentDeviceId = state.currentDeviceId,
            currentIdentityFingerprint = state.currentIdentityFingerprint,
            contactIdentityFingerprint = state.contactIdentityFingerprint,
            deviceSafetyWarning = state.deviceSafetyWarning,
            isLoadingDeviceSafety = state.isLoadingDeviceSafety,
            deviceSafetyStates = state.deviceSafetyStates,
            onDismiss = { viewModel.dismissSafetyCodeDialog() },
            onVerifyDevice = { deviceId -> viewModel.verifyAndTrustIdentity(deviceId) }
        )
    }

    // G74：AI 相关对话框簇（10 个）抽到 ChatDetailAiDialogs.kt，纯搬移不改判断。
    // 四个 rememberSaveable 开关的 setter 经回调传回，所有权仍在本 Composable。
    ChatDetailAiDialogs(
        state = state,
        viewModel = viewModel,
        searchResults = searchResults,
        showSearchBar = showSearchBar,
        showAiSummaryScopeDialog = showAiSummaryScopeDialog,
        onDismissAiSummaryScope = { showAiSummaryScopeDialog = false },
        showConversationProfile = showConversationProfile,
        onDismissConversationProfile = { showConversationProfile = false },
        conversationProfile = conversationProfile,
        conversationProfileLoading = conversationProfileLoading,
        conversationProfileFailed = conversationProfileFailed,
        showWeeklyReport = showWeeklyReport,
        onDismissWeeklyReport = { showWeeklyReport = false },
        weeklyReport = weeklyReport,
        weeklyReportLoading = weeklyReportLoading,
        weeklyReportFailed = weeklyReportFailed,
        showMessageClassify = showMessageClassify,
        onDismissMessageClassify = { showMessageClassify = false },
        chatClassifications = chatClassifications,
        classifyLoading = classifyLoading,
        classifyFailed = classifyFailed,
    )

    if (showDisappearDialog && !state.chatIsGroup && state.isSecretChat != true) {
        DisappearingMessagesDialog(
            selectedSeconds = state.disappearingMessageSeconds,
            isUpdating = state.isUpdatingDisappearing,
            onSelect = { seconds ->
                showDisappearDialog = false
                viewModel.setDisappearingMessages(seconds)
            },
            onDismiss = { showDisappearDialog = false }
        )
    }

    // 8.46：会话免打扰时段（本地 per-chat 静音窗）
    if (showQuietHoursDialog && state.chat?.id?.isNotBlank() == true) {
        // 9.219：捕获局部 chatId——onPick 回调延迟执行时 state.chat 可能已变空（会话删除竞态）
        val quietChatId = state.chat?.id ?: return
        @Suppress("NAME_SHADOWING")
        val _ignored = quietChatId
        ChatQuietHoursDialog(
            current = com.maodouchat.notification.ChatQuietHoursStore.get(
                context,
                quietChatId
            ),
            onPick = { window ->
                com.maodouchat.notification.ChatQuietHoursStore.set(
                    context,
                    quietChatId,
                    window
                )
                Toast.makeText(
                    context,
                    context.getString(
                        if (window.enabled) R.string.chat_quiet_hours_saved
                        else R.string.chat_quiet_hours_cleared
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                showQuietHoursDialog = false
            },
            onDismiss = { showQuietHoursDialog = false }
        )
    }

    // 1.02：临时静音至（本地，1/8/24 小时）
    // G83：静音至对话框（45 行）抽到 ChatDetailChatSettingsDialogs.kt，纯搬移不改判断。
    if (showSilentUntilDialog && state.chat?.id?.isNotBlank() == true) {
        // 9.219：捕获局部 chatId（同免打扰段，回调延迟执行防会话删除竞态）
        val chatIdForSilent = state.chat?.id ?: return
        ChatSilentUntilDialog(
            chatId = chatIdForSilent,
            onDismiss = { showSilentUntilDialog = false },
        )
    }

    // 8.48：稍后提醒列表（查看/取消）
    // G82：稍后提醒列表对话框（65 行）抽到 ChatDetailReminderListDialog.kt，纯搬移不改判断。
    if (showReminderList && state.chat?.id?.isNotBlank() == true) {
        // 9.219：捕获局部 chatId（同免打扰段，回调延迟执行防会话删除竞态）
        val reminderChatId = state.chat?.id ?: return
        var reminderList by remember(showReminderList, reminderChatId) {
            mutableStateOf(viewModel.listRemindersForChat(reminderChatId))
        }
        ChatDetailReminderListDialog(
            reminders = reminderList,
            chatId = reminderChatId,
            onDismiss = { showReminderList = false },
            onCancelReminder = { id ->
                viewModel.cancelReminder(id)
                reminderList = reminderList.filterNot { it.id == id }
            },
            onClearAll = { chatId ->
                viewModel.clearRemindersForChat(chatId)
                reminderList = emptyList()
            },
            onRemindersChange = { reminderList = it },
        )
    }

    if (showScheduleDialog) {
        ScheduleSendDialog(
            onPickDelay = { delayMs ->
                showScheduleDialog = false
                viewModel.scheduleMessage(delayMs)
            },
            onPickAt = { sendAt ->
                showScheduleDialog = false
                viewModel.scheduleMessageAt(sendAt)
            },
            onDismiss = { showScheduleDialog = false },
            // 1.07：重复定时发送（1.21：支持次数上限；1.62：工作日重复）
            onPickRepeat = { intervalMs, repeatCount, weekdaysOnly ->
                showScheduleDialog = false
                viewModel.scheduleMessageRepeat(intervalMs, repeatCount, weekdaysOnly)
            }
        )
    }

    if (showScheduledList && state.scheduledMessages.isNotEmpty()) {
        ScheduledMessagesListSheet(
            items = state.scheduledMessages,
            onCancel = { viewModel.cancelScheduledMessage(it) },
            onReschedule = { id ->
                rescheduleTargetId = id
            },
            // 1.168：立即发送
            onSendNow = { viewModel.sendScheduledNow(it) },
            // 1.174：全部取消
            onCancelAll = { viewModel.cancelAllScheduledMessages() },
            onDismiss = { showScheduledList = false }
        )
    }

    rescheduleTargetId?.let { targetId ->
        // 1.43：重排时可编辑文案（初值取当前待发文案）
        var rescheduleTextDraft by remember(targetId) {
            mutableStateOf(state.scheduledMessages.firstOrNull { it.id == targetId }?.text.orEmpty())
        }
        ScheduleSendDialog(
            titleRes = R.string.schedule_reschedule_title,
            initialText = rescheduleTextDraft,
            onTextEdited = { rescheduleTextDraft = it },
            onPickDelay = { delayMs ->
                rescheduleTargetId = null
                // 1.46：清空编辑框时保留原文（null 表示不改文案）
                viewModel.rescheduleScheduledMessage(targetId, delayMs, rescheduleTextDraft.takeIf { it.isNotBlank() })
            },
            onPickAt = { sendAt ->
                rescheduleTargetId = null
                viewModel.rescheduleScheduledMessageAt(targetId, sendAt, rescheduleTextDraft.takeIf { it.isNotBlank() })
            },
            onDismiss = { rescheduleTargetId = null }
        )
    }

    // G81：设置聊天锁对话框（71 行）抽到 ChatDetailSetChatLockDialog.kt，纯搬移不改判断。
    if (showSetChatLock) {
        ChatDetailSetChatLockDialog(
            pinDraft = setLockPinDraft,
            pinConfirmDraft = setLockPinConfirm,
            errorMessage = setLockError,
            contactDisplayName = state.contact.displayName,
            onPinDraftChange = { setLockPinDraft = it },
            onPinConfirmDraftChange = { setLockPinConfirm = it },
            onErrorMessageChange = { setLockError = it },
            onDismiss = { showSetChatLock = false },
            onSaved = { pin -> viewModel.setChatLockPin(pin) },
        )
    }

    // G83：解除聊天锁对话框（35 行）抽到 ChatDetailChatSettingsDialogs.kt，纯搬移不改判断。
    if (showDisableChatLock) {
        ChatDisableChatLockDialog(
            pinDraft = disableLockPinDraft,
            contactDisplayName = state.contact.displayName,
            onPinDraftChange = { disableLockPinDraft = it },
            onDismiss = { showDisableChatLock = false },
            onRemoveLock = { pin -> viewModel.removeChatLock(pin) },
        )
    }

    if (showGifSearch) {
        GifSearchDialog(
            onPickUri = { uri, gifId ->
                if (gifId != null) {
                    com.maodouchat.util.GifSearchPreferences.recordRecent(context, gifId)
                }
                viewModel.sendGif(uri)
                showGifSearch = false
            },
            onBrowseFiles = { gifPickerLauncher.launch(arrayOf("image/gif")) },
            onRequestPermission = {
                val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                gifMediaPermissionLauncher.launch(permission)
            },
            onDismiss = { showGifSearch = false }
        )
    }

    // G83：联系人操作对话框（45 行）抽到 ChatDetailChatSettingsDialogs.kt，纯搬移不改判断。
    if (showContactActions && !state.chatIsGroup) {
        ChatContactActionsDialog(
            contactDisplayName = state.contact.displayName,
            isContactBlocked = state.isContactBlocked,
            isBlockingContact = state.isBlockingContact,
            isGroup = state.chatIsGroup,
            onDismiss = { showContactActions = false },
            onViewProfile = { showContactProfile = true },
            onToggleBlock = {
                if (state.isContactBlocked) viewModel.unblockContact() else viewModel.blockContact()
            },
            onReport = { showReportContactDialog = true },
        )
    }

    if (showContactProfile && !state.chatIsGroup) {
        ContactProfileSheet(
            contact = state.contact,
            isBlocked = state.isContactBlocked,
            isBlocking = state.isBlockingContact,
            hideCalls = state.isSecretChat == true,
            onDismiss = { showContactProfile = false },
            onMessage = { showContactProfile = false },
            onVoiceCall = {
                showContactProfile = false
                requestVoiceCallPermission(context, voiceCallPermissionLauncher::launch, state.contact.id, state.contact.name, onVoiceCall)
            },
            onVideoCall = {
                showContactProfile = false
                requestVideoCallPermissions(context, videoCallPermissionLauncher::launch, state.contact.id, state.contact.name, onVideoCall)
            },
            onToggleBlock = {
                if (state.isContactBlocked) viewModel.unblockContact() else viewModel.blockContact()
            },
            onReport = {
                showContactProfile = false
                showReportContactDialog = true
            }
        )
    }

    if (showReportContactDialog) {
        ReportDialog(
            title = stringResource(R.string.chat_report_user),
            onDismiss = { showReportContactDialog = false },
            onReport = { reason, description ->
                viewModel.reportContact(reason, description)
                showReportContactDialog = false
            }
        )
    }

if (showGroupCallTypeDialog) {
        val groupCallCandidates = state.chat?.participants.orEmpty().filter { it.id != state.currentUserId }
        val needsMemberPick =
            groupCallCandidates.size > com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS - 1
        AlertDialog(
            onDismissRequest = { showGroupCallTypeDialog = false },
            title = { Text(stringResource(R.string.chat_group_call)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.call_group_mesh_limit,
                            com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    if (needsMemberPick) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.call_select_members_needed_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            showGroupCallTypeDialog = false
                            if (!needsMemberPick) {
                                viewModel.startGroupCallFromChat(com.maodouchat.webrtc.CallType.AUDIO)
                            } else {
                                pendingGroupCallType = com.maodouchat.webrtc.CallType.AUDIO
                                selectedGroupCallMemberIds = emptySet()
                                groupCallMemberSearch = ""
                                showGroupCallMemberDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Call, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_voice_call), modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            showGroupCallTypeDialog = false
                            if (!needsMemberPick) {
                                viewModel.startGroupCallFromChat(com.maodouchat.webrtc.CallType.VIDEO)
                            } else {
                                pendingGroupCallType = com.maodouchat.webrtc.CallType.VIDEO
                                selectedGroupCallMemberIds = emptySet()
                                groupCallMemberSearch = ""
                                showGroupCallMemberDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Videocam, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_video_call), modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupCallTypeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // G78：群通话成员选择对话框（123 行）抽到 ChatDetailGroupCallMemberDialog.kt，纯搬移不改判断。
    // 三个 rememberSaveable 开关的所有权留在 Route（打开入口也在这里），以「值 + setter」传入。
    if (showGroupCallMemberDialog) {
        ChatDetailGroupCallMemberDialog(
            chat = state.chat,
            currentUserId = state.currentUserId,
            pendingCallType = pendingGroupCallType,
            selectedMemberIds = selectedGroupCallMemberIds,
            memberQuery = groupCallMemberSearch,
            onDismiss = { showGroupCallMemberDialog = false },
            onPendingCallTypeChange = { pendingGroupCallType = it },
            onSelectedMemberIdsChange = { selectedGroupCallMemberIds = it },
            onMemberQueryChange = { groupCallMemberSearch = it },
            onStartGroupCall = { type, ids -> viewModel.startGroupCallFromChat(type, ids) },
        )
    }

    if (chatLockPending) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (deviceRiskLocked) {
        SecretNewDeviceRiskLocked(onRegisterClick = { showDeviceRiskDialog = true })
    } else if (chatLockBlocking) {
        ChatLockGate(
            chatName = state.contact.displayName.ifBlank {
                state.chat?.groupName.orEmpty().ifBlank { stringResource(R.string.chat_this_chat) }
            },
            onUnlock = { pin, onResult -> viewModel.unlockChatWithPin(pin, onResult) },
            onForgotPin = { showForgotChatLockConfirm = true }
        )
        ForgotChatLockConfirmDialog(
            visible = showForgotChatLockConfirm,
            onDismiss = { showForgotChatLockConfirm = false },
            onConfirm = {
                showForgotChatLockConfirm = false
                viewModel.forgotChatLockAndClearLocal()
            },
        )
    } else CompositionLocalProvider(LocalDensity provides scaledDensity) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    ClearChatHistoryConfirmDialog(
        visible = showClearHistoryConfirm,
        onDismiss = { showClearHistoryConfirm = false },
        onConfirm = {
            showClearHistoryConfirm = false
            SensitiveActionGate.confirm(
                context = context,
                action = SensitiveAction.CLEAR_CHAT_HISTORY,
                title = sensitiveAuthTitle,
                subtitle = sensitiveAuthClearHistory,
                onSuccess = { viewModel.clearLocalChatHistory() },
                onFailure = { msg ->
                    Toast.makeText(
                        context,
                        msg?.takeIf { it.isNotBlank() } ?: sensitiveAuthFailed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        },
    )
    
    LiveLocationDurationDialog(
        visible = showLiveLocationDuration,
        onPick = { ms ->
            showLiveLocationDuration = false
            viewModel.sendLiveLocation(ms)
        },
        onDismiss = { showLiveLocationDuration = false },
    )


    SecretChatConfirmDialog(
        visible = showSecretChatConfirm,
        onConfirm = {
            showSecretChatConfirm = false
            viewModel.startSecretChat()
        },
        onDismiss = { showSecretChatConfirm = false },
    )
    val chatLiquidBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalLiquidGlassBackdrop provides chatLiquidBackdrop) {
    ChatDetailScaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = chatSnackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
            )
        },
        topBar = {
            FloatingGlassTopBar(
                consumeStatusBars = true,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clickable {
                                if (state.chatIsGroup) {
                                    state.chat?.id?.let(onOpenGroupDetail)
                                } else {
                                    val peerId = state.contact.id
                                    if (onOpenProfile != null && peerId.isNotBlank()) {
                                        onOpenProfile(peerId)
                                    }
                                }
                            }
                    ) {
                        Avatar(
                            name = state.contact.displayName,
                            avatarUrl = if (state.isSecretChat == true) null else state.contact.avatar,
                            size = AvatarSize.SM,
                            isOnline = state.isSecretChat != true && state.contact.isOnline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            // maxLines + overflow 防止长昵称/状态把 action 按钮挤出屏幕
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (state.isSecretChat == true) {
                                        stringResource(R.string.secret_chat_indicator)
                                    } else {
                                        state.contact.displayName
                                    },
                                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (state.isSecretChat == true) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Outlined.VisibilityOff,
                                        contentDescription = stringResource(R.string.secret_chat_indicator),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    if (state.sealedSenderReady) {
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(
                                            Icons.Outlined.Security,
                                            contentDescription = stringResource(
                                                R.string.secret_chat_sealed_ready_ttl,
                                                (state.sealedSenderExpiresInSec / 3600L).coerceAtLeast(0L)
                                            ),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                // 1.153：会话已静音 → 顶栏标题旁显示静音图标
                                if (state.chat?.notificationsMuted == true) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Outlined.NotificationsOff,
                                        contentDescription = stringResource(R.string.chat_mute_notifications),
                                        tint = LocalChatPalette.current.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                                                        when (val status = headerStatus) {
                                is ChatHeaderStatus.Typing -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    InlineTypingDots()
                                    Text(
                                        text = participantNamesById[status.userId]
                                            ?.takeIf { state.chatIsGroup }
                                            ?.let { stringResource(R.string.chat_typing_user, it) }
                                            ?: stringResource(R.string.chat_typing),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                ChatHeaderStatus.Online -> Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                is ChatHeaderStatus.LastSeen -> Text(stringResource(R.string.user_last_seen_prefix) + " " + android.text.format.DateUtils.getRelativeTimeSpanString(status.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ChatHeaderStatus.Offline -> Text(stringResource(R.string.chat_offline), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                is ChatHeaderStatus.Custom -> Text(
                                    // 预设状态 wire 值为中文原文，展示前先本地化（自定义文本原样透传）
                                    com.maodouchat.ui.component.localizedCustomStatusLabel(status.text),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ChatHeaderStatus.None -> Unit
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    if (state.chatIsGroup) {
                        IconButton(onClick = { showGroupCallTypeDialog = true }) {
                            Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_group_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        if (RuntimeFlags.isEnabled(context, RuntimeFlags.SAFETY_CODE)) {
                            IconButton(onClick = { viewModel.showSafetyCodeDialog() }) { Icon(Icons.Outlined.Security, contentDescription = stringResource(R.string.chat_safety_code), tint = if (state.identityWarning == null) Primary else UnreadRed, modifier = Modifier.size(26.dp)) }
                        }
                        if (state.isSecretChat != true) {
                            IconButton(onClick = { requestVoiceCallPermission(context, voiceCallPermissionLauncher::launch, state.contact.id, state.contact.name, onVoiceCall) }) { Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.chat_voice_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                            IconButton(onClick = { requestVideoCallPermissions(context, videoCallPermissionLauncher::launch, state.contact.id, state.contact.name, onVideoCall) }) { Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_video_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
                        }
                    }
                    Box {
                        IconButton(onClick = { showChatOverflow = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.chat_more), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                        DropdownMenu(expanded = showChatOverflow, onDismissRequest = { showChatOverflow = false }) {
                            // 1.155：会话内置顶/取消置顶
                            DropdownMenuItem(
                                text = { Text(stringResource(if ((state.chat?.pinnedAt ?: 0L) > 0L) R.string.chat_unpin else R.string.chat_pin)) },
                                onClick = { showChatOverflow = false; viewModel.toggleChatPinned() }
                            )
                            // 1.156：会话内标记未读/已读
                            DropdownMenuItem(
                                text = { Text(stringResource(if (state.chat?.markedUnread == true) R.string.chat_mark_read else R.string.chat_mark_unread)) },
                                onClick = { showChatOverflow = false; viewModel.toggleChatMarkedUnread() }
                            )
                            if (!state.chatIsGroup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_contact_actions)) },
                                    onClick = { showChatOverflow = false; showContactActions = true }
                                )
                                if (state.isSecretChat != true) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.disappear_menu)) },
                                        onClick = { showChatOverflow = false; showDisappearDialog = true }
                                    )
                                }
                            }
                            // 8.46：会话免打扰时段（本地 per-chat 静音窗，单聊/群聊均可用）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_quiet_hours_menu)) },
                                onClick = { showChatOverflow = false; showQuietHoursDialog = true }
                            )
                            // 1.02：临时静音至（1/8/24 小时，本地）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_silent_until_menu)) },
                                onClick = { showChatOverflow = false; showSilentUntilDialog = true }
                            )
                            // 8.48：稍后提醒列表（查看/取消）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.message_reminder_list_menu)) },
                                onClick = { showChatOverflow = false; showReminderList = true }
                            )
                            // 1.29：通话记录（本地 CallLogStore 历史）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.call_log_title)) },
                                onClick = {
                                    showChatOverflow = false
                                    onOpenCallHistory?.invoke()
                                }
                            )
                            if (RuntimeFlags.isEnabled(context, RuntimeFlags.NUDGE) && !state.chatIsGroup && state.chat?.isChannel != true && state.isSecretChat != true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_nudge)) },
                                    onClick = { showChatOverflow = false; viewModel.sendNudge() }
                                )
                            }
                            // Local device PIN gate — works for 1:1 and groups (Room chatId key).
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.isChatLocked == true) R.string.chat_lock_menu_disable
                                            else R.string.chat_lock_menu_enable
                                        )
                                    )
                                },
                                onClick = {
                                    showChatOverflow = false
                                    if (state.isChatLocked == true) {
                                        disableLockPinDraft = ""
                                        showDisableChatLock = true
                                    } else {
                                        setLockPinDraft = ""
                                        setLockPinConfirm = ""
                                        setLockError = null
                                        showSetChatLock = true
                                    }
                                }
                            )
                            // 钉钉式：从普通单聊发起一场独立密聊；群没有密聊。
                            if (
                                state.isSecretChat != true &&
                                com.maodouchat.security.SecretChatPolicy.canStartFromDirect(
                                    isGroup = state.chatIsGroup,
                                    chatType = state.chat?.chatType
                                )
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.secret_chat_menu_start)) },
                                    onClick = {
                                        showChatOverflow = false
                                        showSecretChatConfirm = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_starred_messages)) },
                                onClick = { showChatOverflow = false; state.chat?.id?.let(onOpenStarredMessages) }
                            )
                            if (!state.chatIsGroup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.media_center_title)) },
                                    onClick = { showChatOverflow = false; state.chat?.id?.let(onOpenMediaCenter) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_search_action)) },
                                onClick = { showChatOverflow = false; showSearchBar = !showSearchBar }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_jump_date)) },
                                onClick = { showChatOverflow = false; showDateJumpDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_clear_local_history), color = LocalChatPalette.current.unreadRed) },
                                onClick = {
                                    showChatOverflow = false
                                    showClearHistoryConfirm = true
                                }
                            )
                        }
                    }
                },
            )
            }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(chatLiquidBackdrop).background(chatBackgroundColor)) {
            // 自定义图片壁纸（本地 URI，按账号隔离）：绘制在聊天内容之下，无壁纸时不引入额外层
            customWallpaperUri?.let { uri ->
                coil.compose.AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 9.205：TG 风格涂鸦纹理——叠加在默认与颜色壁纸之上（TG 是颜色+图案叠加），
            // 仅当用户选了自定义图片壁纸时不叠加，避免盖住用户自选图片
            if (customWallpaperUri == null) {
                // 9.254：TG 式背景纵深——单色底上叠一层自上而下的微暗渐变，平面背景立刻有
                // 空间感（从当前背景色派生，自定义主题/深浅模式自动跟随）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(
                                    chatBackgroundColor.copy(alpha = 0f),
                                    chatBackgroundColor.copy(alpha = 0f),
                                    Color.Black.copy(alpha = if (isDarkChat) 0.10f else 0.045f)
                                )
                            )
                        )
                )
                com.maodouchat.ui.component.ChatBackgroundPattern(
                    modifier = Modifier.fillMaxSize(),
                    tint = LocalChatPalette.current.textSecondary.copy(alpha = if (isDarkChat) 0.07f else 0.09f)
                )
            }
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            if (state.pinnedMessages.isNotEmpty()) {
                PinnedMessagesBanner(
                    pins = state.pinnedMessages,
                    messages = state.messages,
                    canManage = MessagePinPolicy.canPin(
                        isGroup = state.chatIsGroup,
                        myRole = state.myMemberRole,
                        messageType = MessageType.TEXT
                    ),
                    onOpen = { viewModel.jumpToPinnedMessage(it) },
                    onUnpin = { viewModel.togglePinMessage(it) },
                    // 1.49：置顶者显示名
                    resolvePinnerName = { uid -> participantNamesById[uid] ?: uid },
                    // 1.53：点击置顶者打开其资料
                    onPinnerClick = { uid ->
                        if (onOpenProfile != null) {
                            onOpenProfile(uid)
                        } else {
                            Toast.makeText(context, context.getString(R.string.chat_contact_card_tap_hint), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            // 8.57：群公告会话顶部横幅（可折叠；点开看全文）
            if (showAnnouncementBanner && state.chatIsGroup) {
                val announcement = state.chat?.groupAnnouncement?.trim()
                if (!announcement.isNullOrBlank()) {
                    GroupAnnouncementBanner(
                        announcement = announcement,
                        onOpen = { showAnnouncementDialog = true },
                        onDismiss = { showAnnouncementBanner = false }
                    )
                }
            }
            if (state.scheduledMessages.isNotEmpty()) {
                ScheduledMessagesBanner(
                    items = state.scheduledMessages,
                    onCancel = { viewModel.cancelScheduledMessage(it) },
                    onReschedule = { id -> rescheduleTargetId = id },
                    onViewAll = { showScheduledList = true }
                )
            }
            if (!state.chatIsGroup && state.disappearingMessageSeconds > 0) {
                DisappearingMessagesBanner(
                    seconds = state.disappearingMessageSeconds,
                    onChange = {
                        if (state.isSecretChat != true) showDisappearDialog = true
                    }
                )
            }
            AnimatedVisibility(
                visible = state.isSecretChat == true && com.maodouchat.util.SecretSessionNoticePrefs.isEnabled(context),
                enter = LocalMotionSettings.current.bannerEnter(),
                exit = fadeOut()
            ) {
                SecretChatBanner(
                    sealedSenderReady = state.sealedSenderReady,
                    sealedSenderExpiresInSec = state.sealedSenderExpiresInSec,
                )
            }
            if (state.activeLiveLocationSessionId != null) {
                LiveLocationSharingBanner(
                    untilMs = state.activeLiveLocationUntil,
                    onStop = { viewModel.stopLiveLocationSharing() }
                )
            }
            state.groupEncryptionWarning?.let { warning ->
                GroupEncryptionWarningBanner(warning = warning)
            }
            state.identityWarning?.let { warning ->
                SecurityWarningBanner(
                    warning = warning,
                    sticky = com.maodouchat.crypto.SafetyCodePolicy.isStickyIdentityWarning(state.trustState),
                    onClick = {
                        if (RuntimeFlags.isEnabled(context, RuntimeFlags.SAFETY_CODE)) viewModel.showSafetyCodeDialog()
                    }
                )
            }
            AnimatedVisibility(
                visible = chatAiSurfacesVisible && (state.isUnreadSummaryLoading || state.unreadAiSummary != null),
                enter = expandVertically() + LocalMotionSettings.current.composerBarEnter(),
                exit = shrinkVertically() + fadeOut()
            ) {
                UnreadSummaryBanner(
                    summary = state.unreadAiSummary,
                    messageCount = state.unreadAiSummaryCount,
                    isLoading = state.isUnreadSummaryLoading,
                    onOpen = { viewModel.openUnreadAiSummary() },
                    onDismiss = { viewModel.clearUnreadAiSummary() },
                    // 1.194：复制未读摘要
                    onCopy = {
                        val textToCopy = state.unreadAiSummary
                        if (!textToCopy.isNullOrBlank()) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_unread_summary), textToCopy))
                            Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            AnimatedVisibility(
                visible = showSearchBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ChatSearchBar(
                    query = searchQuery,
                    mode = searchMode,
                    scope = searchScope,
                    window = searchWindow,
                    resultIndex = searchIndex,
                    resultCount = searchResults.size,
                    semanticCandidateCount = semanticCandidates.size,
                    isSemanticSearching = state.isSemanticSearching,
                    semanticSearchQuery = state.semanticSearchQuery,
                    semanticSearchResultCount = state.semanticSearchResultIds.size,
                    semanticSearchError = state.semanticSearchError,
                    aiEnabled = chatAiSurfacesVisible,
                    onQueryChange = { query ->
                        searchQuery = query
                        searchIndex = 0
                        if (searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
                    },
                    onModeChange = { mode ->
                        searchMode = mode
                        searchIndex = 0
                        viewModel.clearSemanticSearch()
                    },
                    onScopeChange = { scope ->
                        searchScope = scope
                        searchIndex = 0
                        if (searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
                    },
                    onWindowChange = { window ->
                        searchWindow = window
                        searchIndex = 0
                        if (searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
                    },
                    onSemanticSearch = {
                        searchIndex = 0
                        viewModel.requestSemanticSearch(searchQuery, semanticCandidates.map(Message::id))
                    },
                    onNextResult = { searchIndex = (searchIndex + 1) % searchResults.size },
                    onClose = {
                        showSearchBar = false
                        searchQuery = ""
                        searchIndex = 0
                        searchMode = ChatSearchMode.KEYWORD
                        searchScope = ChatSearchScope.ALL
                        searchWindow = ChatSearchWindow.ALL
                        viewModel.clearSemanticSearch()
                    }
                )
            }
            // G84：多选工具条（79 行）抽到 ChatDetailSelectionToolbar.kt，六项派生状态随之内聚。
            AnimatedVisibility(
                visible = messageSelectionMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ChatDetailSelectionToolbar(
                    visible = messageSelectionMode,
                    selectedMessages = selectedMessages,
                    allMessages = state.messages,
                    selectedIds = selectedMessageIds,
                    chatIsGroup = state.chatIsGroup,
                    myMemberRole = state.myMemberRole,
                    pinnedMessageIds = remember(state.pinnedMessages) { state.pinnedMessages.map { it.messageId }.toSet() },
                    isSecretChat = state.isSecretChat == true,
                    preparingAttachmentMessageIds = state.preparingAttachmentMessageIds,
                    onSelectAll = { selectedMessageIds = it },
                    onClearSelection = { selectedMessageIds = emptySet() },
                    onForward = { msgs ->
                        messagesToForward = msgs
                        viewModel.loadForwardTargets()
                    },
                    onToggleStar = { ids, shouldStar -> viewModel.toggleStarMessagesBatch(ids, shouldStar) },
                    onDelete = { showBatchDeleteConfirm = true },
                    onTogglePin = { ids, shouldPin ->
                        viewModel.togglePinMessages(messageIds = ids, shouldPin = shouldPin)
                    },
                    onCopied = { text ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, text))
                        Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                    },
                    onCopyFailed = {
                        Toast.makeText(context, context.getString(R.string.chat_copy_no_text), Toast.LENGTH_SHORT).show()
                    },
                )
            }

            ChatTimelinePane(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    // 9.264：TG 式分组密度基线——组内紧凑 3dp，跨组由消息项额外 padding 补到 8dp
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                itemsIndexed(
                    reversedChatItems,
                    key = { _, item -> item.listKey },
                    contentType = { _, item -> when (item) {
                        is ChatItem.DateSeparator -> "date_separator"
                        is ChatItem.Msg -> "message_${item.message.type.name}"
                        is ChatItem.UnreadSeparator -> "unread_separator"
                    } }
                ) { index, item ->
                    // G85：单条 item 渲染（231 行）抽到 ChatDetailTimelineItems.kt。
                    // 必须是 LazyItemScope 扩展——`Modifier.animateItem` 只在 itemsIndexed 内有效，
                    // 降级成普通 Composable 会让重排时的位移动画静默消失。
                    ChatDetailTimelineItem(
                        index = index,
                        item = item,
                        state = state,
                        listState = listState,
                        motion = motion,
                        allItems = reversedChatItems,
                        selectedMessageIds = selectedMessageIds,
                        messageSelectionMode = messageSelectionMode,
                        animatingMessageId = animatingMessageId,
                        searchResults = searchResults,
                        searchIndex = searchIndex,
                        showSearchBar = showSearchBar,
                        localSafetyEnabled = localSafetyEnabled,
                        navigationHighlightMessageId = navigationHighlightMessageId,
                        dismissedSafetyMessageIds = dismissedSafetyMessageIds,
                        messagesById = messagesById,
                        resolveSenderName = { msg, isOwn -> resolveSenderName(msg, isOwn) },
                        viewModel = viewModel,
                        onBubblePlaced = { id, bounds -> bubbleBounds[id] = bounds },
                        onBubbleRemoved = { id -> bubbleBounds.remove(id) },
                        onShowFullscreenImage = { fullScreenImage = it },
                        onShowFullscreenVideo = { fullScreenVideo = it },
                        onCopyTranscript = { transcript ->
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranscriptLabel, transcript))
                            Toast.makeText(context, context.getString(R.string.chat_transcript_copied), Toast.LENGTH_SHORT).show()
                        },
                        onReplyTo = { msg -> replyTarget = msg },
                        onDismissSafetyForMessage = { id -> dismissSafetyForMessage(id) },
                        onToggleSelection = { selectedMessageIds = it },
                        onRetryMessage = { msg -> messageToRetry = msg },
                        onMessageActions = { msg -> messageToActions = msg },
                        onOpenProfile = { userId -> onOpenProfile?.invoke(userId) },
                        onShowReadReceipts = { msg -> messageForReadReceipts = msg },
                    )
                }
                if (state.isLoadingOlderMessages) {
                    item(key = "older_messages_loading", contentType = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                item(key = "message_list_footer", contentType = "footer") { Spacer(modifier = Modifier.height(8.dp)) }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = !isNearBottom,
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.86f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 12.dp)
                ) {
                    Box {
                        FloatingActionButton(
                            onClick = {
                                pendingNewMessageCount = 0
                                chatListScroller.scrollToItem(listState, 0)
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = Primary,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_scroll_to_latest),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        if (pendingNewMessageCount > 0) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(20.dp)
                                    .background(UnreadRed, CircleShape)
                            ) {
                                Text(
                                    text = if (pendingNewMessageCount > 99) "99+" else pendingNewMessageCount.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onError
                                )
                        }
                    }
                }
                }

                // 8.52 UX：初次加载失败（无本地缓存）→ 错误空态 + 重试，区别于真实空会话
                if (!state.isLoading && state.messages.isEmpty() && state.initialLoadError != null) {
                    EmptyState(
                        title = stringResource(R.string.chat_load_failed_title),
                        subtitle = state.initialLoadError,
                        type = EmptyStateType.NETWORK_ERROR,
                        actionText = stringResource(R.string.chat_load_failed_retry),
                        onAction = { viewModel.reloadChat() },
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    )
                } else if (!state.isLoading && state.messages.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.chat_detail_empty_title),
                        subtitle = stringResource(R.string.chat_detail_empty_subtitle),
                        type = EmptyStateType.CHAT_LIST,
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    )
                }
            }

            // 加载指示器
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }

            // 引用中提示
            replyTarget?.let { target ->
                ReplyTargetBar(
                    senderName = resolveSenderName(target) ?: "",
                    preview = MessagePreviewText.replyOrQuote(
                        message = target,
                        mediaLabel = { type ->
                            when (type) {
                                MessageType.IMAGE -> context.getString(R.string.message_preview_image)
                                MessageType.GIF -> context.getString(R.string.message_preview_gif)
                                MessageType.STICKER -> context.getString(R.string.message_preview_sticker)
                                MessageType.VOICE -> context.getString(R.string.message_preview_voice)
                                MessageType.VIDEO -> context.getString(R.string.message_preview_video)
                                MessageType.FILE -> context.getString(R.string.message_preview_file)
                                MessageType.LOCATION -> context.getString(R.string.message_preview_location)
                                else -> context.getString(R.string.message_preview_encrypted)
                            }
                        },
                        encryptedPlaceholder = context.getString(R.string.message_preview_encrypted),
                    ).take(60),
                    onCancel = { replyTarget = null }
                )
            }

            // 录音指示器（波形 + 时长）
            AnimatedVisibility(
                visible = state.isRecording,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                RecordingIndicator(
                    elapsedMs = state.recordingElapsedMs,
                    waveform = state.recordingWaveform,
                    amplitude = state.recordingAmplitude,
                )
            }

            // 发送前试听条
            AnimatedVisibility(
                visible = state.voicePreviewPath != null && !state.isRecording,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                VoicePreviewBar(
                    durationMs = state.voicePreviewDurationMs,
                    onPlay = { viewModel.playVoicePreview() },
                    onDiscard = { viewModel.discardVoicePreview() },
                    onSend = { viewModel.sendVoicePreview() },
                )
            }

            // 发送中指示器
            if (state.isSending) {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }

            // 8.48：群禁言状态提示——被禁言时输入区上方明确显示，而非仅发送失败时提示
            if (state.chatIsGroup && state.myMutedUntil > 0L) {
                // 8.48 修复：禁言到期后无状态变化时提示条不消失——到期时刻触发一次重组
                LaunchedEffect(state.myMutedUntil) {
                    val until = state.myMutedUntil
                    val wait = until - System.currentTimeMillis()
                    if (wait > 0L) {
                        kotlinx.coroutines.delay(wait + 500L)
                        muteTick = System.currentTimeMillis()
                    }
                }
                // 读取 muteTick 建立重组依赖（到期写入后提示条随重组消失）
                val recomposeOnExpiry = muteTick
                val remaining = state.myMutedUntil - System.currentTimeMillis()
                if (remaining > 0L) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_group_muted_until, formatMuteRemaining(context, remaining)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = state.aiOperations.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                AiOperationStatusBar(
                    operations = state.aiOperations,
                    onRetry = viewModel::retryAiOperation,
                    onCancel = viewModel::cancelAiOperation,
                    onDismiss = viewModel::dismissAiOperation
                )
            }

            AnimatedVisibility(
                visible = state.aiDraftOriginal != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                AiDraftStreamBar(
                    preview = state.aiDraftPreview,
                    isStreaming = state.isAiDraftStreaming,
                    errorCode = state.aiDraftStreamErrorCode,
                    onApply = viewModel::applyAiDraftPreview,
                    onDiscard = viewModel::discardAiDraftPreview,
                    onRetry = viewModel::retryAiDraftStream,
                    onCancel = viewModel::cancelAiDraftStream
                )
            }

            RestoredDraftPanel(
                visible = state.hasSavedDraft && state.inputText.isNotBlank(),
                onClear = {
                    viewModel.onInputChange("")
                    viewModel.clearDraftPersistence()
                },
            )

            // 打字中微动效指示器 (Murexide / Telegram 风格悬浮指示)
            TypingPresence(
                visible = state.typingContact != null,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )

            // 输入区
            ComposerPane(
                value = state.inputText,
                onValueChange = { viewModel.onInputChange(it) },
                onSend = {
                    if (state.isSending) return@ComposerPane
                    viewModel.sendMessage(replyTarget = replyTarget)
                    replyTarget = null
                },
                onScheduleSend = {
                    if (state.inputText.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.schedule_need_text), Toast.LENGTH_SHORT).show()
                    } else {
                        showScheduleDialog = true
                    }
                },
                onOpenConversationProfile = { showConversationProfile = true },
                onOpenWeeklyReport = { showWeeklyReport = true },
                onEmotionReply = { emotionReplyRequested = true },
                onOpenMessageClassify = { showMessageClassify = true },
                isSecretChat = secretActive,
                contactCardTargets = state.forwardTargets,
                onLoadForwardTargets = { viewModel.loadForwardTargets() },
                onSendContactCard = { userId, name -> viewModel.sendContactCard(userId, name) },
                onSendImage = {
                    pendingViewOnce = false
                    pendingSpoiler = false
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { imagePickerLauncher.launch("image/*") }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                onSendViewOnceImage = {
                    if (state.chat?.isGroup == true) {
                        Toast.makeText(context, context.getString(R.string.view_once_direct_only), Toast.LENGTH_SHORT).show()
                    } else {
                        pendingViewOnce = true
                        pendingSpoiler = false
                        listScrollScope.launch {
                            kotlinx.coroutines.yield()
                            runCatching { imagePickerLauncher.launch("image/*") }
                                .onFailure {
                                    Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                },
                onSendSpoilerImage = {
                    pendingSpoiler = true
                    pendingViewOnce = false
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { imagePickerLauncher.launch("image/*") }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                // 8.48：从系统剪贴板粘贴图片直接进入确认发送流程
                onPasteFromClipboard = {
                    // 8.48 修复：重置阅后即焚/剧透意图——否则上一次取消选图器残留的标志
                    // 会泄漏到后续普通视频/图片发送
                    pendingViewOnce = false
                    pendingSpoiler = false
                    listScrollScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val resultUri = runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = cm.primaryClip ?: return@runCatching null
                            if (clip.itemCount <= 0) return@runCatching null
                            val item = clip.getItemAt(0)
                            val uri = item.uri
                            val mime = uri?.let { u ->
                                runCatching { context.contentResolver.getType(u) }.getOrNull()
                            }
                            if (uri != null && mime?.startsWith("image/") == true) {
                                return@runCatching uri
                            }
                            val bmp = uri?.let { source ->
                                context.contentResolver.openInputStream(source)?.use { stream ->
                                    android.graphics.BitmapFactory.decodeStream(stream)
                                }
                            } ?: return@runCatching null
                            val dir = java.io.File(context.cacheDir, "attachment-sources").apply { mkdirs() }
                            val file = java.io.File(dir, "paste_${System.currentTimeMillis()}.png")
                            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                            bmp.recycle()
                            android.net.Uri.fromFile(file)
                        }.getOrNull()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (resultUri != null) {
                                pendingImageConfirm = PendingImageSend(resultUri, false, false)
                            } else {
                                Toast.makeText(context, context.getString(R.string.chat_clipboard_no_image), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onSendVideo = {
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { videoPickerLauncher.launch("video/*") }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_video_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                onSendFile = {
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { filePickerLauncher.launch(arrayOf("*/*")) }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                onSendGif = { showGifSearch = true },
                onSendSticker = { viewModel.sendSticker(it) },
                onSendLocation = {
                    if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) viewModel.sendCurrentLocation()
                    else {
                        pendingLiveLocationPermission = false
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                onSendLiveLocation = {
                    if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) showLiveLocationDuration = true
                    else {
                        pendingLiveLocationPermission = true
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                onRecordStart = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onRecordStop = { viewModel.stopRecordingAndSend() },
                onRecordCancel = { viewModel.cancelRecording() },
                isRecording = state.isRecording,
                isAiWorking = state.isAiWorking,
                isAiDraftStreaming = state.isAiDraftStreaming,
                aiSuggestions = state.aiSuggestions,
                isAiReplyStreaming = state.isAiReplyStreaming,
                aiReplyStreamErrorCode = state.aiReplyStreamErrorCode,
                onAiRewrite = { mode, targetLanguage -> viewModel.requestAiRewrite(mode, targetLanguage) },
                onAiSuggestReplies = { tone -> viewModel.requestAiSuggestions(tone) },
                onAiSummarize = { showAiSummaryScopeDialog = true },
                onOpenAiSummaryHistory = { viewModel.openAiSummaryHistory() },
                onOpenAiTasks = { state.chat?.id?.let(onOpenAiTasks) },
                onAiSuggestionClick = { viewModel.applyAiSuggestion(it) },
                onClearAiSuggestions = { viewModel.clearAiSuggestions() },
                onCancelAiReplyStream = { viewModel.cancelAiReplyStream() },
                onRetryAiReplyStream = viewModel::retryAiReplyStream,
                aiEnabled = state.aiEnabled,
                isUpdatingAiSetting = state.isUpdatingAiSetting,
                onAiEnabledChange = { viewModel.setAiEnabledForChat(it) },
                isGroupChat = state.chatIsGroup,
                isChannelChat = state.chat?.isChannel == true,
                onSendNudge = { viewModel.sendNudge() },
                mentionParticipants = state.chat?.participants.orEmpty(),
                currentUserId = state.currentUserId,
                // 1.37：仅群主/管理员可选「@所有人」（1.45：角色未加载时 fail-open 避免误拦管理员）
                canMentionEveryone = !state.chatIsGroup || run {
                    val role = state.myMemberRole?.uppercase()
                    role == null || role == "OWNER" || role == "ADMIN"
                },
                silentSend = state.silentSend,
                onToggleSilentSend = viewModel::toggleSilentSend,
                isSending = state.isSending,
                readOnly = state.chat?.isChannel == true && state.myMemberRole != "OWNER",
                botCommands = state.botCommands,
            )
            }
        }
    }
    }

    // 8.57：群公告全文弹窗
    if (showAnnouncementDialog) {
        AlertDialog(
            onDismissRequest = { showAnnouncementDialog = false },
            title = { Text(stringResource(R.string.group_announcement_dialog_title)) },
            text = {
                Text(
                    state.chat?.groupAnnouncement?.trim().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showAnnouncementDialog = false }) { Text(stringResource(R.string.common_close)) }
            },
            // 1.301：复制公告全文（转发到别处 / 归档）
            dismissButton = {
                TextButton(onClick = {
                    val text = state.chat?.groupAnnouncement?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.group_announcement_copy), text))
                        Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                    }
                    showAnnouncementDialog = false
                }) { Text(stringResource(R.string.group_announcement_copy), color = MaterialTheme.colorScheme.primary) }
            }
        )
    }

    // G86：批量删除确认对话框（50 行）抽到 ChatDetailBatchDeleteDialog.kt，纯搬移不改判断。
    if (showBatchDeleteConfirm) {
        ChatDetailBatchDeleteDialog(
            selectedMessages = selectedMessages,
            currentUserId = state.currentUserId,
            onDelete = { ids -> viewModel.deleteMessagesBatch(ids) },
            onDismiss = { showBatchDeleteConfirm = false },
            onSelectionCleared = { selectedMessageIds = emptySet() },
        )
    }

    messageToActions?.let { msg ->
        val isOwn = msg.senderId == state.currentUserId
        val withinEditWindow = System.currentTimeMillis() - msg.timestamp < 300_000
        val canForward = isMessageForwardable(msg.type, isSecretChat = state.isSecretChat == true, forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK))
        val meta = msg.parsedMeta()
        val voiceTranscript = meta.voiceTranscript
        val displayedTranslation = meta.displayedTranslation()
        ModalBottomSheet(onDismissRequest = { messageToActions = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                    Text(
                        stringResource(R.string.chat_message_actions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    ReactionPickerRow(
                        onPick = { emoji ->
                            viewModel.setMessageReaction(msg.id, emoji)
                            messageToActions = null
                        }
                    )
                    TextButton(
                        onClick = {
                            selectedMessageIds = setOf(msg.id)
                            messageToActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_select_message), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    if (isMessageReplyable(msg.type)) {
                        TextButton(
                            onClick = {
                                replyTarget = msg
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.message_reply), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    if (isMessageCopyable(msg.type, isSecretChat = state.isSecretChat == true, copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK))) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                    chatClipboardMessageLabel,
                                    com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                        msg.parsedContent(),
                                        context.getString(R.string.chat_decrypt_failed)
                                    )
                                ))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        // 1.73：复制带发送者（引用/记录用，格式「发送者: 内容」）
                        TextButton(
                            onClick = {
                                val sender = resolveSenderName(msg) ?: ""
                                val copied = com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                    msg.parsedContent(),
                                    context.getString(R.string.chat_decrypt_failed)
                                )
                                val label = if (sender.isBlank()) copied else "$sender: $copied"
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, label))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy_with_sender), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        // 1.160：复制带发送者与时间（格式「MM-dd HH:mm 发送者: 内容」）
                        TextButton(
                            onClick = {
                                val sender = resolveSenderName(msg) ?: ""
                                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))
                                val body = com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                    msg.parsedContent(),
                                    context.getString(R.string.chat_decrypt_failed)
                                )
                                val label = when {
                                    body.isBlank() -> time
                                    sender.isBlank() -> "$time $body"
                                    else -> "$time $sender: $body"
                                }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, label))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy_with_sender_time), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        // 1.169：分享消息到系统其他应用（密聊与复制同款门控，防外泄）
                        if (isMessageCopyable(msg.type, isSecretChat = state.isSecretChat == true, copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK))) {
                        val chatShareMessageTitle = stringResource(R.string.chat_share_message_title)
                        val previewImageLabel = stringResource(R.string.message_preview_image)
                        val previewGifLabel = stringResource(R.string.message_preview_gif)
                        val previewStickerLabel = stringResource(R.string.message_preview_sticker)
                        val previewLocationLabel = stringResource(R.string.message_preview_location)
                        val previewFileLabel = stringResource(R.string.message_preview_file)
                        TextButton(
                            onClick = {
                                // 1.197：图片/GIF 且本地可读时直接分享原图；1.198：扩展到视频/文件
                                val contentUri = msg.parsedContent()
                                val fileMime = when (msg.type) {
                                    MessageType.IMAGE, MessageType.GIF -> "image/*"
                                    MessageType.VIDEO -> "video/*"
                                    MessageType.FILE -> "application/octet-stream"
                                    else -> null
                                }
                                val shareFile = fileMime != null &&
                                    runCatching { com.maodouchat.util.MediaCache.isReadableLocalUri(context, contentUri) }.getOrDefault(false)
                                if (shareFile) {
                                    val fileIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = fileMime
                                        putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(contentUri))
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    val fileChooser = android.content.Intent.createChooser(fileIntent, chatShareMessageTitle)
                                    if (context !is android.app.Activity) fileChooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(fileChooser) }
                                } else {
                                    val shareText = com.maodouchat.ui.component.ChatMarkdown.toPlainText(contentUri).ifBlank {
                                        when (msg.type) {
                                            MessageType.IMAGE -> previewImageLabel
                                            MessageType.GIF -> previewGifLabel
                                            MessageType.STICKER -> previewStickerLabel
                                            MessageType.LOCATION -> previewLocationLabel
                                            else -> previewFileLabel
                                        }
                                    }
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    }
                                    val chooser = android.content.Intent.createChooser(shareIntent, chatShareMessageTitle)
                                    if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(chooser)
                                }
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_share_message), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        // 0.71：Markdown 消息提供「复制为纯文本」（剥离 **、# 等标记）
                        if (msg.type == MessageType.MARKDOWN) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            chatClipboardMessageLabel,
                                            com.maodouchat.ui.component.ChatMarkdown.toPlainText(msg.parsedContent())
                                        )
                                    )
                                    Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                    messageToActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.chat_copy_plain), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        // 1.84：名片消息复制干净文本（不含 [contactUser:...] 标记）
                        if (msg.parsedContent().contains("[contactUser:")) {
                            TextButton(
                                onClick = {
                                    val clean = com.maodouchat.ui.component.ChatMarkdown.stripContactCardMarker(msg.parsedContent()).trim()
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, clean))
                                    Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                    messageToActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.chat_copy_contact_card), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        // 0.97：消息分享到系统（ACTION_SEND 文本分享）
                        TextButton(
                            onClick = {
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, msg.parsedContent())
                                }
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(sendIntent, context.getString(R.string.chat_share))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_share), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        if (!displayedTranslation.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranslationLabel, displayedTranslation))
                                    Toast.makeText(context, chatTranslationCopiedMsg, Toast.LENGTH_SHORT).show()
                                    messageToActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.chat_copy_translation), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    if (msg.type == MessageType.VOICE && !voiceTranscript.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranscriptLabel, voiceTranscript))
                                Toast.makeText(context, chatTranscriptCopiedMsg, Toast.LENGTH_SHORT).show()
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy_transcript), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    // 1.299：复制消息 ID（反查排障；ID 本身不涉密，密聊也可用）
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_copy_message_id), msg.id))
                            Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                            messageToActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_copy_message_id), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    // AI 场景入口（与输入栏主入口分区视觉统一）；密聊会话不提供（防解密明文送 AI）
                    val contextAiActions = com.maodouchat.ai.AiEntryPolicy.contextActionsFor(
                        messageType = msg.type.name,
                        hasTranscript = !voiceTranscript.isNullOrBlank()
                    )
                    if (
                        contextAiActions.isNotEmpty() &&
                        state.isSecretChat != true &&
                        chatAiSurfacesVisible
                    ) {
                        Text(
                            stringResource(R.string.chat_ai_section_context),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        contextAiActions.forEach { action ->
                            when (action) {
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.TRANSLATE -> {
                                    val busy = msg.id in state.translatingMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            messageToTranslate = msg
                                            messageToActions = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_translating else R.string.chat_translate),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.TRANSCRIBE -> {
                                    val busy = msg.id in state.transcribingVoiceMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            viewModel.requestVoiceTranscription(msg.id)
                                            messageToActions = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_transcribing else R.string.chat_transcribe),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.ANALYZE_IMAGE -> {
                                    val busy = msg.id in state.analyzingImageMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            messageToAnalyzeImage = msg
                                            messageToActions = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_ai_image_analyzing else R.string.chat_ai_image_action),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.ANALYZE_FILE -> {
                                    val busy = msg.id in state.analyzingFileMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            messageToAnalyzeFile = msg
                                            messageToActions = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_ai_file_analyzing else R.string.chat_ai_file_action),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (canForward) {
                        TextButton(
                            onClick = {
                                messagesToForward = listOf(msg)
                                viewModel.loadForwardTargets()
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_forward), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    TextButton(
                        onClick = {
                            viewModel.toggleStarMessage(msg.id)
                            messageToActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(if (msg.starred) R.string.chat_unstar else R.string.chat_star), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    val canPinMessage = MessagePinPolicy.canPin(
                        isGroup = state.chatIsGroup,
                        myRole = state.myMemberRole,
                        messageType = msg.type
                    )
                    if (canPinMessage) {
                        val isPinned = state.pinnedMessages.any { it.messageId == msg.id }
                        TextButton(
                            enabled = !state.isTogglingPin,
                            onClick = {
                                viewModel.togglePinMessage(msg.id)
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(if (isPinned) R.string.chat_message_unpin else R.string.chat_message_pin),
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // 8.41：消息「稍后提醒」
                    TextButton(
                        onClick = {
                            messageToRemind = msg
                            messageToActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.message_reminder_menu), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    if (isOwn && msg.status == com.maodouchat.data.model.MessageStatus.FAILED) {
                        TextButton(
                            onClick = {
                                viewModel.retrySendMessage(msg.id)
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_retry), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    if (ReadReceiptPolicy.canViewReceipts(
                            viewerId = state.currentUserId,
                            senderId = msg.senderId,
                            isGroup = state.chatIsGroup,
                            viewerRole = state.myMemberRole,
                        )
                    ) {
                        TextButton(
                            onClick = {
                                messageForReadReceipts = msg
                                viewModel.loadReadReceipts(msg.id)
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_read_details), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    if (isOwn && withinEditWindow && (msg.type == MessageType.TEXT || msg.type == MessageType.MARKDOWN)) {
                        TextButton(
                            onClick = {
                                editDraft = msg.parsedContent()
                                messageToEdit = msg
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_edit), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    if (isOwn && withinEditWindow && msg.type != MessageType.REVOKED) {
                        // 1.152：撤回倒计时（5 分钟窗口，向上取整分钟）
                        val revokeRemainingMin = ((300_000L - (System.currentTimeMillis() - msg.timestamp)) / 60_000L).toInt() + 1
                        TextButton(
                            onClick = {
                                messageToRevoke = msg
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_revoke_with_limit, revokeRemainingMin), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
                    }
                    if (!isOwn && msg.type !in setOf(MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED)) {
                        TextButton(
                            onClick = {
                                messageToReport = msg
                                messageToActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_report), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
                    }
                    TextButton(
                        onClick = {
                            messageToDelete = msg
                            messageToActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_delete), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
                    TextButton(onClick = { messageToActions = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.textSecondary)
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }

    messageToAnalyzeImage?.let { message ->
        AiImageAnalysisModeDialog(
            onSelect = { mode ->
                messageToAnalyzeImage = null
                viewModel.requestAiImageAnalysis(message.id, mode)
            },
            onDismiss = { messageToAnalyzeImage = null }
        )
    }

    // 8.41：消息「稍后提醒」时间选择
    messageToRemind?.let { message ->
        MessageReminderTimeDialog(
            onPick = { delayMs ->
                messageToRemind = null
                viewModel.scheduleMessageReminder(message, System.currentTimeMillis() + delayMs)
            },
            onDismiss = { messageToRemind = null }
        )
    }

    // G79：图片发送前预览（47 行）抽到 ChatDetailSendPreviews.kt，纯搬移不改判断。
    pendingImageConfirm?.let { pending ->
        ImageSendPreviewDialog(
            pending = pending,
            onDismiss = { pendingImageConfirm = null },
            onRechoose = { viewOnce, spoiler ->
                pendingViewOnce = viewOnce
                pendingSpoiler = spoiler
                listScrollScope.launch {
                    kotlinx.coroutines.yield()
                    runCatching { imagePickerLauncher.launch("image/*") }
                        .onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.chat_image_picker_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            },
            onSendImage = { p -> viewModel.sendImage(p.uri) },
            onSendSpoilerImage = { p -> viewModel.sendSpoilerImage(p.uri) },
            onSendViewOnceImage = { p -> viewModel.sendViewOnceImage(p.uri) },
        )
    }

    // G79：视频发送前预览（37 行）抽到 ChatDetailSendPreviews.kt，纯搬移不改判断。
    pendingVideoConfirm?.let { pending ->
        VideoSendPreviewDialog(
            pending = pending,
            onDismiss = { pendingVideoConfirm = null },
            onSendVideo = { p -> viewModel.sendVideo(p.uri) },
            onSendSpoilerVideo = { p -> viewModel.sendSpoilerVideo(p.uri) },
            onSendViewOnceVideo = { p -> viewModel.sendViewOnceVideo(p.uri) },
        )
    }

    messageToAnalyzeFile?.let { message ->
        AiFileAnalysisModeDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            onSelect = { mode ->
                messageToAnalyzeFile = null
                if (mode == AiFileAnalysisMode.SUMMARIZE) {
                    viewModel.requestAiFileAnalysis(message.id, mode)
                } else {
                    fileQuestionDraft = ""
                    fileQuestionMessage = message
                }
            },
            onDismiss = { messageToAnalyzeFile = null }
        )
    }

    fileQuestionMessage?.let { message ->
        AiFileQuestionDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            question = fileQuestionDraft,
            onQuestionChange = { fileQuestionDraft = it.take(500) },
            onSubmit = {
                viewModel.requestAiFileAnalysis(message.id, AiFileAnalysisMode.QUESTION, fileQuestionDraft)
                fileQuestionMessage = null
                fileQuestionDraft = ""
            },
            onDismiss = {
                fileQuestionMessage = null
                fileQuestionDraft = ""
            }
        )
    }

    messageToTranslate?.let { message ->
        TranslationLanguageDialog(
            translatedLanguages = message.parsedMeta().translations.keys,
            onDismiss = { messageToTranslate = null },
            onSelect = { language ->
                viewModel.requestMessageTranslation(message.id, language)
                messageToTranslate = null
            }
        )
    }

    messageToReport?.let { msg ->
        ReportDialog(
            title = stringResource(R.string.chat_report_message),
            onDismiss = { messageToReport = null },
            onReport = { reason, description ->
                viewModel.reportMessage(msg.id, reason, description)
                messageToReport = null
            }
        )
    }

    if (showDateJumpDialog) {
        DateJumpDialog(
            onDismiss = { showDateJumpDialog = false },
            onJump = { dayStartMillis ->
                showDateJumpDialog = false
                viewModel.jumpToDate(dayStartMillis)
            }
        )
    }

    messageForReadReceipts?.let { receiptMessage ->
        var readReceiptSearch by remember(receiptMessage.id) { mutableStateOf("") }
        val readCount = state.readReceipts.count { it.readAt != null }
        val totalCount = state.readReceipts.size
        val progress = if (totalCount > 0) readCount.toFloat() / totalCount else 0f
        val q = readReceiptSearch.trim()
        // 1.68：remember 避免每次重组都全量排序
        val filteredReadReceipts = remember(readReceiptSearch, state.readReceipts) {
            val q = readReceiptSearch.trim()
            if (q.isEmpty()) {
                // 1.63：未读成员优先展示（readAt==null 排前），便于发现谁还没读
                state.readReceipts.sortedBy { it.readAt != null }
            } else {
                state.readReceipts.filter { receipt ->
                    receipt.name.contains(q, ignoreCase = true) ||
                        receipt.userId.contains(q, ignoreCase = true)
                }.sortedBy { it.readAt != null }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                messageForReadReceipts = null
                viewModel.clearReadReceipts()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.chat_read_details),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (totalCount > 0 && !state.isLoadingReadReceipts) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.chat_read_details_ratio, readCount, totalCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (totalCount > readCount) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = LocalChatPalette.current.unreadRed.copy(alpha = 0.10f),
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    stringResource(R.string.chat_read_details_unread, totalCount - readCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalChatPalette.current.unreadRed,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (totalCount > 0 && !state.isLoadingReadReceipts) {
                        // 顶部进度条
                        val progressAnim by animateFloatAsState(
                            targetValue = progress,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 220f),
                            label = "readProgress"
                        )
                        LinearProgressIndicator(
                            progress = { progressAnim.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    if (state.readReceipts.size >= 5 && !state.isLoadingReadReceipts) {
                        OutlinedTextField(
                            value = readReceiptSearch,
                            onValueChange = { readReceiptSearch = it.take(100) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_read_details_search_hint)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Outline,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                cursorColor = Primary
                            )
                        )
                    }
                    if (state.isLoadingReadReceipts) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_loading), color = MaterialTheme.colorScheme.secondary)
                        }
                    } else if (state.readReceipts.isEmpty()) {
                        Text(stringResource(R.string.chat_no_read_receipts), color = MaterialTheme.colorScheme.secondary)
                    } else if (filteredReadReceipts.isEmpty()) {
                        Text(stringResource(R.string.chat_read_details_search_empty), color = MaterialTheme.colorScheme.secondary)
                    } else {
                        filteredReadReceipts.forEach { receipt ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // 1.59：点击已读/未读成员打开其资料
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable(enabled = onOpenProfile != null) {
                                        onOpenProfile?.invoke(receipt.userId)
                                    }
                            ) {
                                // 1.60：成员头像（Avatar 组件自带 JWT 认证加载，回退首字母）
                                Avatar(
                                    name = receipt.name.ifBlank { receipt.userId },
                                    avatarUrl = receipt.avatar,
                                    size = AvatarSize.SM
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(receipt.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        // 1.65：在线状态小绿点
                                        if (receipt.isOnline) {
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(OnlineGreen))
                                        }
                                    }
                                    Text(
                                        text = receipt.readAt?.let { stringResource(R.string.chat_read_at, formatDateTime(context, it)) } ?: stringResource(R.string.chat_unread),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (receipt.readAt != null) Primary else TextHint
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(20.dp).clip(CircleShape)
                                        .background(if (receipt.readAt != null) OnlineGreen else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (receipt.readAt != null) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        messageForReadReceipts = null
                        viewModel.clearReadReceipts()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text(stringResource(R.string.common_done)) }
            }
        }
    }

    // 长按撤回消息确认弹窗（带粒子动效）
    messageToRevoke?.let { msg ->
        // 1.152：撤回剩余分钟（向上取整，防显示 0 分钟）
        val revokeConfirmRemainingMin = ((300_000L - (System.currentTimeMillis() - msg.timestamp)) / 60_000L).toInt() + 1
        AlertDialog(
            onDismissRequest = { messageToRevoke = null },
            title = { Text(stringResource(R.string.chat_revoke_title)) },
            text = { Text(stringResource(R.string.chat_revoke_message)) },
            confirmButton = {
                TextButton(onClick = {
                    startParticleEffect(msg, ParticleAction.REVOKE)
                    messageToRevoke = null
                }) { Text(stringResource(R.string.chat_revoke_with_limit, revokeConfirmRemainingMin), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = { TextButton(onClick = { messageToRevoke = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // 长按删除消息确认弹窗
    messageToDelete?.let { msg ->
        val isOwn = msg.senderId == state.currentUserId
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text(stringResource(R.string.chat_delete_message_title)) },
            text = {
                Text(
                    stringResource(if (isOwn) R.string.chat_delete_own_message else R.string.chat_delete_other_message)
                )
            },
            confirmButton = {
                if (isOwn) {
                    TextButton(onClick = {
                        startParticleEffect(msg, ParticleAction.DELETE)
                        messageToDelete = null
                    }) { Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed) }
                } else {
                    TextButton(onClick = { messageToDelete = null }) { Text(stringResource(R.string.chat_acknowledge)) }
                }
            },
            dismissButton = {
                if (isOwn) {
                    Row {
                        if (isMessageForwardable(msg.type, isSecretChat = state.isSecretChat == true, forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK))) {
                            TextButton(onClick = {
                                messagesToForward = listOf(msg)
                                viewModel.loadForwardTargets()
                                messageToDelete = null
                            }) { Text(stringResource(R.string.chat_forward)) }
                        }
                        TextButton(onClick = { messageToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            }
        )
    }

    // G80：消息操作弹窗（58 行）抽到 ChatDetailMessageActionsDialog.kt，纯搬移不改判断。
    messageToCopy?.let { msg ->
        ChatDetailMessageActionsDialog(
            msg = msg,
            currentUserId = state.currentUserId,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { messageToCopy = null },
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, msg.parsedContent()))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onForward = {
                messagesToForward = listOf(msg)
                viewModel.loadForwardTargets()
            },
            onEdit = {
                editDraft = msg.parsedContent()
                messageToEdit = msg
            },
            onRevoke = { messageToRevoke = msg },
            onDelete = { startParticleEffect(msg, ParticleAction.DELETE) },
        )
    }

    messageToEdit?.let { msg ->
        AlertDialog(
            onDismissRequest = { messageToEdit = null },
            title = { Text(stringResource(R.string.chat_edit_message)) },
            text = {
                TextField(
                    value = editDraft,
                    onValueChange = { editDraft = it.take(2000) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedIndicatorColor = Primary,
                        unfocusedIndicatorColor = Outline,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editDraft.trim().isNotBlank(),
                    onClick = {
                        viewModel.editTextMessage(msg.id, editDraft)
                        messageToEdit = null
                    }
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { messageToEdit = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // 转发目标选择弹窗
    // G75：转发目标选择弹窗（235 行）抽到 ChatDetailForwardPicker.kt，纯搬移不改判断。
    if (messagesToForward.isNotEmpty()) {
        ChatDetailForwardPicker(
            messages = messagesToForward,
            forwardTargets = state.forwardTargets,
            currentUserId = state.currentUserId,
            onCancel = { messagesToForward = emptyList() },
            onForwardBatch = { msgs, targets, note ->
                viewModel.forwardMessagesBatch(msgs, targets, note)
            },
            onSendTextToChat = { chatId, body -> viewModel.sendTextToChat(chatId, body) },
            onSelectionCleared = { selectedMessageIds = emptySet() },
            onLoadForwardTargets = { viewModel.loadForwardTargets() },
            secretSource = secretActive,
        )
    }

    // 重发失败消息弹窗
    messageToRetry?.let { msg ->
        AlertDialog(
            onDismissRequest = { messageToRetry = null },
            title = { Text(stringResource(R.string.chat_send_failed)) },
            text = { Text(stringResource(R.string.chat_send_failed_retry)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.retrySendMessage(msg.id)
                    messageToRetry = null
                }) { Text(stringResource(R.string.chat_retry)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    startParticleEffect(msg, ParticleAction.DELETE)
                    messageToRetry = null
                }) { Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed) }
            }
        )
    }

    // G76：全屏图片/视频查看器（207 行）抽到 ChatDetailFullscreenMedia.kt，纯搬移不改判断。
    fullScreenImage?.let { msg ->
        FullscreenImageDialog(
            msg = msg,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { fullScreenImage = null },
        )
    }

    fullScreenVideo?.let { msg ->
        FullscreenVideoDialog(
            msg = msg,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { fullScreenVideo = null },
        )
    }

    // 粒子删除动效：消息泡碎裂为彩色粒子消散（Telegram 风格）
    if (animatingMessageId != null && particleStates.isNotEmpty()) {
        ParticleDeleteEffect(
            particleStates = particleStates,
            onFinished = {
                val targetId = animatingMessageId
                when (particleAction) {
                    ParticleAction.DELETE -> targetId?.let { viewModel.deleteMessage(it) }
                    ParticleAction.REVOKE -> targetId?.let { viewModel.revokeMessage(it) }
                    null -> Unit
                }
                animatingMessageId = null
                particleAction = null
                particleStates = emptyList()
            }
        )
    }
    } // secret watermark Box
    } // CompositionLocalProvider (chat font scale)
}

