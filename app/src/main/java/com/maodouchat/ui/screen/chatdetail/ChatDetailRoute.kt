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
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.security.MessageSafetyScanner
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.security.findActivity
import com.maodouchat.ui.component.FloatingGlassTopBar
import com.maodouchat.ui.component.MessageBubble
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

private data class BubbleBounds(
    val offset: androidx.compose.ui.unit.IntOffset,
    val size: androidx.compose.ui.unit.IntSize
)

/** 8.43：图片发送前预览的待确认项（URI + 单次查看/剧透标记，选取时刻捕获）。 */
private data class PendingImageSend(
    val uri: Uri,
    val viewOnce: Boolean,
    val spoiler: Boolean
)

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

internal fun MessageMeta.displayedTranslation(): String? {
    val preferred = preferredTranslationLanguage?.let(translations::get)
    return preferred?.takeIf { it.isNotBlank() }
        ?: translations["中文"]?.takeIf { it.isNotBlank() }
        ?: translations.values.lastOrNull { it.isNotBlank() }
}

@Composable
internal fun AiSummaryScope.localizedLabel(): String = stringResource(when (this) {
    AiSummaryScope.RECENT -> R.string.chat_ai_summary_scope_recent
    AiSummaryScope.TODAY -> R.string.chat_ai_summary_scope_today
    AiSummaryScope.SEVEN_DAYS -> R.string.chat_ai_summary_scope_week
    AiSummaryScope.THIRTY_DAYS -> R.string.chat_ai_summary_scope_month
    AiSummaryScope.SEARCH_RESULTS -> R.string.chat_ai_summary_scope_search
    AiSummaryScope.UNREAD -> R.string.chat_ai_summary_scope_unread
})

@Composable
internal fun AiImageAnalysisMode.localizedLabel(): String = stringResource(when (this) {
    AiImageAnalysisMode.DESCRIBE -> R.string.chat_ai_image_mode_describe
    AiImageAnalysisMode.OCR -> R.string.chat_ai_image_mode_ocr
    AiImageAnalysisMode.SAFETY -> R.string.chat_ai_image_mode_safety
})

@Composable
internal fun AiFileAnalysisMode.localizedLabel(): String = stringResource(when (this) {
    AiFileAnalysisMode.SUMMARIZE -> R.string.chat_ai_file_mode_summarize
    AiFileAnalysisMode.QUESTION -> R.string.chat_ai_file_mode_question
})

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

    var pendingViewOnce by remember { mutableStateOf(false) }
    var pendingSpoiler by remember { mutableStateOf(false) }
    // 8.43：图片发送前预览确认（选图 → 预览 → 发送/取消）
    var pendingImageConfirm by remember { mutableStateOf<PendingImageSend?>(null) }
    // 0.69：视频发送前预览确认（此前点即发，误选无法挽回）
    var pendingVideoConfirm by remember { mutableStateOf<PendingImageSend?>(null) }
    // 8.48：禁言提示到期重组触发器（到期时刻写入以驱动提示条消失）
    var muteTick by remember { mutableLongStateOf(0L) }
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

    // B2 密聊 TTL（ttlz）：会话活跃心跳——进入与驻留期间持续更新 lastActivityAt，避免无活动误销毁
    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        val dao = (context.applicationContext as com.maodouchat.MaodouchatApp).database.secretChatDao()
        // B2 密聊 TTL 进入前即时校验：会话已无活动过期时立即销毁本地解密缓存，
        // 再以本次进入为新的活动起点——不依赖 15 分钟周期清扫的滞后窗口。
        runCatching {
            val entity = dao.get(chatId)
            if (entity != null &&
                com.maodouchat.security.SecretSessionTtl.isExpired(context, chatId, entity.lastActivityAt)
            ) {
                com.maodouchat.security.SecretSessionTtl.destroySession(context, chatId)
            }
        }
        while (true) {
            dao.touchActivity(chatId)
            kotlinx.coroutines.delay(60_000L)
        }
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
        val appContext = context.applicationContext
        val db = (appContext as com.maodouchat.MaodouchatApp).database
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.maodouchat.ai.AiMessageClassifier.classifyChat(appContext, db, chatId)
            }
        }
        classifyLoading = false
        classifyFailed = result.isFailure
        result.getOrNull()?.let { chatClassifications = it }
    }
    LaunchedEffect(emotionReplyRequested, state.chat?.id) {
        if (!emotionReplyRequested) return@LaunchedEffect
        emotionReplyRequested = false
        val chatId = state.chat?.id ?: return@LaunchedEffect
        val appContext = context.applicationContext
        val db = (appContext as com.maodouchat.MaodouchatApp).database
        val reply = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.maodouchat.ai.AiEmotionReply.reply(appContext, db, chatId).getOrNull().orEmpty()
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
        val appContext = context.applicationContext
        val db = (appContext as com.maodouchat.MaodouchatApp).database
        val built = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.maodouchat.ai.AiConversationProfile.build(appContext, db, chatId)
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
        val appContext = context.applicationContext
        val db = (appContext as com.maodouchat.MaodouchatApp).database
        weeklyReport = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.maodouchat.ai.AiWeeklyReport.generate(appContext, db, chatId)
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

    if (showGroupInfo) {
        val chat = state.chat
        var groupNameDraft by remember(chat?.id, chat?.groupName) { mutableStateOf(chat?.groupName.orEmpty()) }
        var groupInfoSearch by remember(chat?.id) { mutableStateOf("") }
        var groupInfoSearchExpanded by remember(chat?.id) { mutableStateOf(false) }
        var groupInfoCandidatesExpanded by remember(chat?.id) { mutableStateOf(false) }
        val groupInfoCandidatePage = 20
        AlertDialog(
            onDismissRequest = {
                showGroupInfo = false
                groupInfoSearch = ""
                groupInfoSearchExpanded = false
                groupInfoCandidatesExpanded = false
            },
            title = { Text(chat?.groupName ?: stringResource(R.string.chat_group)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val members = chat?.participants.orEmpty()
                    val q = groupInfoSearch.trim()
                    val filteredMembers = if (q.isEmpty()) {
                        members
                    } else {
                        members.filter { m ->
                            m.displayName.contains(q, ignoreCase = true) ||
                                m.name.contains(q, ignoreCase = true) ||
                                m.id.contains(q, ignoreCase = true) ||
                                m.status.contains(q, ignoreCase = true)
                        }
                    }
                    val filteredCandidates = if (q.isEmpty()) {
                        state.groupCandidates
                    } else {
                        state.groupCandidates.filter { u ->
                            u.displayName.contains(q, ignoreCase = true) ||
                                u.name.contains(q, ignoreCase = true) ||
                                u.id.contains(q, ignoreCase = true)
                        }
                    }
                    val visibleCandidates = if (groupInfoCandidatesExpanded || filteredCandidates.size <= groupInfoCandidatePage) {
                        filteredCandidates
                    } else {
                        filteredCandidates.take(groupInfoCandidatePage)
                    }
                    TextField(
                        value = groupNameDraft,
                        onValueChange = { groupNameDraft = it.take(50) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.chat_group_name)) },
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
                    TextButton(
                        onClick = { viewModel.renameGroup(groupNameDraft) },
                        enabled = !state.isUpdatingGroup && groupNameDraft.trim().isNotBlank() && groupNameDraft.trim() != chat?.groupName.orEmpty()
                    ) {
                        if (state.isUpdatingGroup) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        else Text(stringResource(R.string.chat_save_group_name))
                    }
                    if (members.size + state.groupCandidates.size >= 4) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(
                                onClick = {
                                    groupInfoSearchExpanded = !groupInfoSearchExpanded
                                    if (!groupInfoSearchExpanded) groupInfoSearch = ""
                                }
                            ) {
                                Icon(
                                    imageVector = if (groupInfoSearchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                    contentDescription = stringResource(
                                        if (groupInfoSearchExpanded) R.string.chat_search_close else R.string.chat_search_action
                                    ),
                                    tint = Primary,
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = groupInfoSearchExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            OutlinedTextField(
                                value = groupInfoSearch,
                                onValueChange = { groupInfoSearch = it.take(100) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.chat_group_info_search_hint)) },
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
                    }
                    Text(pluralStringResource(R.plurals.chat_members_count, members.size, members.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (filteredMembers.isEmpty() && q.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chat_group_info_search_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint
                        )
                    } else {
                        filteredMembers.forEach { member ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Avatar(name = member.name, size = AvatarSize.SM)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(member.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (member.status.isNotBlank()) Text(member.status, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (member.id == state.currentUserId) Text(stringResource(R.string.chat_me), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                else TextButton(
                                    enabled = !state.isUpdatingGroup,
                                    onClick = { viewModel.removeGroupMember(member.id) }
                                ) { Text(stringResource(R.string.chat_remove), color = LocalChatPalette.current.unreadRed) }
                            }
                        }
                    }
                    if (state.groupCandidates.isNotEmpty()) {
                        Text(stringResource(R.string.chat_add_member), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        if (filteredCandidates.isEmpty() && q.isNotEmpty()) {
                            Text(
                                stringResource(R.string.chat_group_info_search_empty),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint
                            )
                        } else {
                            visibleCandidates.forEach { user ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextButton(
                                        enabled = !state.isUpdatingGroup,
                                        onClick = { viewModel.addGroupMember(user.id) }
                                    ) { Text(stringResource(R.string.chat_add)) }
                                }
                            }
                            if (!groupInfoCandidatesExpanded && filteredCandidates.size > groupInfoCandidatePage) {
                                TextButton(
                                    onClick = { groupInfoCandidatesExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.chat_candidates_more,
                                            filteredCandidates.size - groupInfoCandidatePage
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else if (groupInfoCandidatesExpanded && filteredCandidates.size > groupInfoCandidatePage) {
                                Text(
                                    stringResource(R.string.chat_candidates_showing_all, filteredCandidates.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textHint
                                )
                            }
                        }
                    } else {
                        Text(stringResource(R.string.chat_no_candidates), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGroupInfo = false
                    groupInfoSearch = ""
                    groupInfoSearchExpanded = false
                    groupInfoCandidatesExpanded = false
                }) { Text(stringResource(R.string.common_done)) }
            }
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

    if (state.showAiConsentDialog) {
        AiConsentDialog(
            onAccept = { viewModel.acceptAiConsentAndContinue() },
            onDismiss = { viewModel.dismissAiConsent() }
        )
    }

    if (showAiSummaryScopeDialog) {
        AiSummaryScopeDialog(
            searchResultCount = if (showSearchBar) searchResults.size else 0,
            onDismiss = { showAiSummaryScopeDialog = false },
            onSelect = { scope, style ->
                showAiSummaryScopeDialog = false
                viewModel.requestAiSummary(
                    scope = scope,
                    searchResultIds = if (scope == AiSummaryScope.SEARCH_RESULTS) searchResults.map(Message::id) else emptyList(),
                    style = style
                )
            }
        )
    }

    if (state.showAiSummaryHistory) {
        AiSummaryHistoryDialog(
            summaries = state.aiSummaryHistory,
            isLoading = state.isAiSummaryHistoryLoading,
            onSelect = viewModel::openAiSummaryFromHistory,
            onDismiss = viewModel::dismissAiSummaryHistory
        )
    }

    state.aiSummary?.let { summary ->
        AiSummaryDialog(
            summary = summary,
            scope = state.aiSummaryScope ?: AiSummaryScope.RECENT,
            messageCount = state.aiSummaryMessageCount,
            onDismiss = { viewModel.clearAiSummary() }
        )
    }

    // B4：会话画像对话框（本地统计 + 可选叙事摘要）
    if (showConversationProfile) {
        ConversationProfileDialog(
            loading = conversationProfileLoading,
            profile = conversationProfile,
            failed = conversationProfileFailed,
            onDismiss = { showConversationProfile = false },
            // 1.317：复制会话画像
            onCopyProfile = { profileText ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_ai_conversation_profile_title), profileText))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // B4：本周周报对话框（生成并缓存到本地）
    if (showWeeklyReport) {
        WeeklyReportDialog(
            loading = weeklyReportLoading,
            report = weeklyReport,
            failed = weeklyReportFailed,
            onDismiss = { showWeeklyReport = false },
            // 1.310：复制周报全文
            onCopyReport = { reportText ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_ai_weekly_report_title), reportText))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 8.47：消息分类统计对话框（纯本地）
    if (showMessageClassify) {
        MessageClassifyDialog(
            loading = classifyLoading,
            categories = chatClassifications,
            failed = classifyFailed,
            onDismiss = { showMessageClassify = false },
            // 1.349：复制分类结果（与周报/画像复制一致）
            onCopyClassify = { classifyText ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.ai_enhance_classify_title), classifyText))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    state.aiImageAnalysisResult?.let { result ->
        AiImageAnalysisResultDialog(
            result = result,
            mode = state.aiImageAnalysisMode ?: AiImageAnalysisMode.DESCRIBE,
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatAiImageResultTitle, result))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onDismiss = viewModel::clearAiImageAnalysis
        )
    }

    state.aiFileAnalysisResult?.let { result ->
        AiFileAnalysisResultDialog(
            result = result,
            fileName = state.aiFileAnalysisName.orEmpty(),
            mode = state.aiFileAnalysisMode ?: AiFileAnalysisMode.SUMMARIZE,
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatAiFileResultTitle, result))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onDismiss = viewModel::clearAiFileAnalysis
        )
    }

    var confirmShareGroupAi by remember { mutableStateOf(false) }
    state.groupAiAnswer?.let { answer ->
        val canShare = !state.groupAiAnswerShared &&
            com.maodouchat.ai.GroupAiSharePolicy.decideShare(
                isGroup = state.chatIsGroup || state.chat?.isGroup == true,
                answer = answer,
                alreadyShared = state.groupAiAnswerShared
            ).allowed
        GroupAiAssistantDialog(
            question = state.groupAiQuestion,
            answer = answer,
            tasks = state.groupAiTasks,
            isSavingTasks = state.isSavingGroupAiTasks,
            tasksSaved = state.groupAiTasksSaved,
            taskSaveError = state.groupAiTaskSaveError,
            shareEnabled = canShare,
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatGroupAiTitle, answer))
                Toast.makeText(context, chatGroupAiCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onSaveTasks = { viewModel.saveGroupAiTasks() },
            onShare = { if (canShare) confirmShareGroupAi = true },
            onDismiss = {
                confirmShareGroupAi = false
                viewModel.clearGroupAiAnswer()
            }
        )
        if (confirmShareGroupAi && canShare) {
            AlertDialog(
                onDismissRequest = { confirmShareGroupAi = false },
                title = { Text(stringResource(R.string.chat_group_ai_share_confirm_title)) },
                text = { Text(stringResource(R.string.chat_group_ai_share_confirm_body)) },
                confirmButton = {
                    Button(onClick = {
                        confirmShareGroupAi = false
                        viewModel.shareGroupAiAnswer()
                    }) { Text(stringResource(R.string.chat_group_ai_share_confirm_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmShareGroupAi = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }

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
    if (showSilentUntilDialog && state.chat?.id?.isNotBlank() == true) {
        val chatIdForSilent = state.chat?.id ?: return
        val hasActiveSilent = com.maodouchat.notification.ChatQuietHoursStore.silentUntil(context, chatIdForSilent) > System.currentTimeMillis()
        AlertDialog(
            onDismissRequest = { showSilentUntilDialog = false },
            title = { Text(stringResource(R.string.chat_silent_until_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    listOf(
                        1L to R.string.chat_silent_until_1h,
                        8L to R.string.chat_silent_until_8h,
                        24L to R.string.chat_silent_until_24h
                    ).forEach { (hours, labelRes) ->
                        TextButton(
                            onClick = {
                                com.maodouchat.notification.ChatQuietHoursStore.setSilentUntil(
                                    context,
                                    chatIdForSilent,
                                    System.currentTimeMillis() + hours * 3600_000L
                                )
                                showSilentUntilDialog = false
                                Toast.makeText(context, context.getString(R.string.chat_silent_until_set), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    // 1.41：已有生效静音时可一键取消
                    if (hasActiveSilent) {
                        TextButton(
                            onClick = {
                                com.maodouchat.notification.ChatQuietHoursStore.setSilentUntil(context, chatIdForSilent, 0L)
                                showSilentUntilDialog = false
                                Toast.makeText(context, context.getString(R.string.chat_silent_until_cleared), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_silent_until_clear), color = LocalChatPalette.current.unreadRed) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSilentUntilDialog = false }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
            }
        )
    }

    // 8.48：稍后提醒列表（查看/取消）
    if (showReminderList && state.chat?.id?.isNotBlank() == true) {
        // 9.219：捕获局部 chatId（同免打扰段，回调延迟执行防会话删除竞态）
        val reminderChatId = state.chat?.id ?: return
        var reminders by remember(showReminderList, reminderChatId) {
            mutableStateOf(viewModel.listRemindersForChat(reminderChatId))
        }
        AlertDialog(
            onDismissRequest = { showReminderList = false },
            title = { Text(stringResource(R.string.message_reminder_list_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                if (reminders.isEmpty()) {
                    Text(
                        stringResource(R.string.message_reminder_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    ) {
                        reminders.forEach { reminder ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        reminder.messagePreview.ifBlank { stringResource(R.string.message_reminder_list_media) },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        android.text.format.DateUtils.getRelativeTimeSpanString(
                                            reminder.remindAtMillis,
                                            System.currentTimeMillis(),
                                            android.text.format.DateUtils.MINUTE_IN_MILLIS
                                        ).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary
                                    )
                                }
                                TextButton(onClick = {
                                    viewModel.cancelReminder(reminder.id)
                                    reminders = reminders.filterNot { it.id == reminder.id }
                                }) {
                                    Text(stringResource(R.string.common_delete), color = LocalChatPalette.current.unreadRed)
                                }
                            }
                        }
                        // 1.32：清除该会话全部提醒
                        TextButton(
                            onClick = {
                                viewModel.clearRemindersForChat(reminderChatId)
                                reminders = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.message_reminder_clear_all), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReminderList = false }) { Text(stringResource(R.string.common_done)) } }
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

    if (showSetChatLock) {
        AlertDialog(
            onDismissRequest = {
                showSetChatLock = false
                setLockError = null
            },
            title = { Text(stringResource(R.string.chat_lock_set_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = setLockPinDraft,
                        onValueChange = { raw ->
                            setLockPinDraft = raw.filter { it.isDigit() }.take(8)
                            setLockError = null
                        },
                        label = { Text(stringResource(R.string.chat_lock_enter_pin, state.contact.displayName)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = setLockPinConfirm,
                        onValueChange = { raw ->
                            setLockPinConfirm = raw.filter { it.isDigit() }.take(8)
                            setLockError = null
                        },
                        label = { Text(stringResource(R.string.chat_lock_confirm_pin)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    setLockError?.let {
                        Text(it, color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        setLockPinDraft.length !in 4..8 ->
                            setLockError = context.getString(R.string.chat_lock_pin_length)
                        setLockPinDraft != setLockPinConfirm ->
                            setLockError = context.getString(R.string.chat_lock_pin_mismatch)
                        else -> {
                            viewModel.setChatLockPin(setLockPinDraft)
                            showSetChatLock = false
                            setLockPinDraft = ""
                            setLockPinConfirm = ""
                            setLockError = null
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSetChatLock = false
                    setLockError = null
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showDisableChatLock) {
        AlertDialog(
            onDismissRequest = { showDisableChatLock = false },
            title = { Text(stringResource(R.string.chat_lock_disable_title)) },
            text = {
                OutlinedTextField(
                    value = disableLockPinDraft,
                    onValueChange = { raw ->
                        disableLockPinDraft = raw.filter { it.isDigit() }.take(8)
                    },
                    label = { Text(stringResource(R.string.chat_lock_enter_pin, state.contact.displayName)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeChatLock(disableLockPinDraft)
                    showDisableChatLock = false
                    disableLockPinDraft = ""
                }) {
                    Text(stringResource(R.string.chat_lock_menu_disable), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableChatLock = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
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

    if (showContactActions && !state.chatIsGroup) {
        AlertDialog(
            onDismissRequest = { showContactActions = false },
            title = { Text(state.contact.displayName.ifBlank { stringResource(R.string.chat_contact) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(if (state.isContactBlocked) R.string.chat_blocked_description else R.string.chat_block_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    TextButton(
                        onClick = {
                            showContactActions = false
                            showContactProfile = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_view_profile), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(
                        enabled = !state.isBlockingContact,
                        onClick = {
                            if (state.isContactBlocked) viewModel.unblockContact() else viewModel.blockContact()
                            showContactActions = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isBlockingContact) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        else Text(stringResource(if (state.isContactBlocked) R.string.chat_unblock_user else R.string.chat_block_user), color = if (state.isContactBlocked) Primary else UnreadRed)
                    }
                    TextButton(
                        onClick = {
                            showContactActions = false
                            showReportContactDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_report_user), color = LocalChatPalette.current.unreadRed)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showContactActions = false }) { Text(stringResource(R.string.common_done)) } }
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

    if (showGroupCallMemberDialog) {
        val candidates = state.chat?.participants.orEmpty().filter { it.id != state.currentUserId }
        val maxSelected = com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS - 1
        val memberQuery = groupCallMemberSearch.trim()
        val filteredCallCandidates = if (memberQuery.isBlank()) {
            candidates
        } else {
            candidates.filter { user ->
                user.displayName.contains(memberQuery, ignoreCase = true) ||
                    user.id.contains(memberQuery, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = {
                showGroupCallMemberDialog = false
                pendingGroupCallType = null
                selectedGroupCallMemberIds = emptySet()
                groupCallMemberSearch = ""
            },
            title = { Text(stringResource(R.string.call_select_members_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.call_select_members_count, selectedGroupCallMemberIds.size, maxSelected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.call_group_mesh_limit,
                            com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    if (candidates.size >= 4) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = groupCallMemberSearch,
                            onValueChange = { groupCallMemberSearch = it.take(100) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.call_select_members_search_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (filteredCallCandidates.isEmpty()) {
                        Text(
                            stringResource(R.string.call_select_members_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            itemsIndexed(
                                filteredCallCandidates,
                                key = { _, user -> user.id },
                                contentType = { _, _ -> "group_call_candidate" }
                            ) { _, user ->
                                val selected = user.id in selectedGroupCallMemberIds
                                val enabled = selected || selectedGroupCallMemberIds.size < maxSelected
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = enabled) {
                                            selectedGroupCallMemberIds = if (selected) {
                                                selectedGroupCallMemberIds - user.id
                                            } else {
                                                selectedGroupCallMemberIds + user.id
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM)
                                    Spacer(Modifier.width(12.dp))
                                    Text(user.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Checkbox(
                                        checked = selected,
                                        enabled = enabled,
                                        onCheckedChange = {
                                            selectedGroupCallMemberIds = if (selected) {
                                                selectedGroupCallMemberIds - user.id
                                            } else {
                                                selectedGroupCallMemberIds + user.id
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedGroupCallMemberIds.isNotEmpty() && pendingGroupCallType != null,
                    onClick = {
                        val type = pendingGroupCallType ?: return@TextButton
                        viewModel.startGroupCallFromChat(type, selectedGroupCallMemberIds)
                        showGroupCallMemberDialog = false
                        pendingGroupCallType = null
                        selectedGroupCallMemberIds = emptySet()
                        groupCallMemberSearch = ""
                    }
                ) {
                    Text(stringResource(R.string.call_start_selected_members))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGroupCallMemberDialog = false
                    pendingGroupCallType = null
                    selectedGroupCallMemberIds = emptySet()
                    groupCallMemberSearch = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (chatLockPending) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (deviceRiskLocked) {
        // B2 新设备风控（ndz）：设备未登记 → 密聊内容锁定，仅保留重新登记入口
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.secret_new_device_risk_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                TextButton(onClick = { showDeviceRiskDialog = true }) {
                    Text(stringResource(R.string.secret_new_device_risk_register), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    } else if (chatLockBlocking) {
        ChatLockGate(
            chatName = state.contact.displayName.ifBlank {
                state.chat?.groupName.orEmpty().ifBlank { stringResource(R.string.chat_this_chat) }
            },
            onUnlock = { pin, onResult -> viewModel.unlockChatWithPin(pin, onResult) },
            onForgotPin = { showForgotChatLockConfirm = true }
        )
        if (showForgotChatLockConfirm) {
            AlertDialog(
                onDismissRequest = { showForgotChatLockConfirm = false },
                title = { Text(stringResource(R.string.chat_lock_forgot_confirm_title)) },
                text = { Text(stringResource(R.string.chat_lock_forgot_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showForgotChatLockConfirm = false
                        viewModel.forgotChatLockAndClearLocal()
                    }) {
                        Text(stringResource(R.string.common_clear), color = LocalChatPalette.current.unreadRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showForgotChatLockConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    } else CompositionLocalProvider(LocalDensity provides scaledDensity) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_local_history)) },
            text = { Text(stringResource(R.string.chat_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = {
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
                }) {
                    Text(stringResource(R.string.common_clear), color = LocalChatPalette.current.unreadRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    
    if (showLiveLocationDuration) {
        AlertDialog(
            onDismissRequest = { showLiveLocationDuration = false },
            title = { Text(stringResource(R.string.chat_live_location_send)) },
            text = {
                Column {
                    listOf(
                        15L * 60_000L to stringResource(R.string.live_location_duration_15m),
                        60L * 60_000L to stringResource(R.string.live_location_duration_1h),
                        8L * 60L * 60_000L to stringResource(R.string.live_location_duration_8h)
                    ).forEach { (ms, label) ->
                        TextButton(
                            onClick = {
                                showLiveLocationDuration = false
                                viewModel.sendLiveLocation(ms)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLiveLocationDuration = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }


    if (showSecretChatConfirm) {
        AlertDialog(
            onDismissRequest = { showSecretChatConfirm = false },
            title = {
                Text(stringResource(R.string.secret_chat_confirm_enable_title))
            },
            text = {
                Text(stringResource(R.string.secret_chat_confirm_enable_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    showSecretChatConfirm = false
                    viewModel.startSecretChat()
                }) {
                    Text(stringResource(R.string.common_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSecretChatConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
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
                                is ChatHeaderStatus.Custom -> Text(status.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            AnimatedVisibility(
                visible = messageSelectionMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val forwardableMessages = selectedMessages.filter { isMessageForwardable(it.type, isSecretChat = state.isSecretChat == true, forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK)) }
                val shouldStar = selectedMessages.any { !it.starred }
                // 1.10：选中的消息里只要存在未置顶的即可点「置顶」，全部已置顶则显示「取消置顶」
                val pinnedIds = remember(state.pinnedMessages) { state.pinnedMessages.map { it.messageId }.toSet() }
                val shouldPin = selectedMessages.any { it.id !in pinnedIds }
                // 1.20：与单条置顶一致——群聊非群主/管理员不显示批量置顶入口
                val canBatchPin = selectedMessages.any {
                    MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, it.type)
                }
                val selectableIds = remember(state.messages) {
                    state.messages
                        .filter { it.type != MessageType.SYSTEM && it.type != MessageType.SK_DIST }
                        // 8.53：排除在途消息（SENDING）与附件仍在上传准备中的消息——
                        // 删了会与服务端 404 竞态，outbox flusher 仍可能把消息发出去
                        .filter {
                            it.status != MessageStatus.SENDING &&
                                it.id !in state.preparingAttachmentMessageIds
                        }
                        .map { it.id }
                        .toSet()
                }
                ChatSelectionToolbar(
                    selectedCount = selectedMessageIds.size,
                    canForward = forwardableMessages.isNotEmpty(),
                    shouldStar = shouldStar,
                    canSelectAll = selectableIds.isNotEmpty() && !selectedMessageIds.containsAll(selectableIds),
                    onSelectAll = { selectedMessageIds = selectableIds },
                    onClearSelection = { selectedMessageIds = emptySet() },
                    onCancel = { selectedMessageIds = emptySet() },
                    onForward = {
                        messagesToForward = forwardableMessages
                        viewModel.loadForwardTargets()
                    },
                    onToggleStar = {
                        // 9.227：改串行批量，避免逐条并发触发 toggleStarMessage 扇出 N 个 REST
                        viewModel.toggleStarMessagesBatch(selectedMessages.map { it.id }, shouldStar)
                        selectedMessageIds = emptySet()
                    },
                    onDelete = { showBatchDeleteConfirm = true },
                    onCopy = if (state.isSecretChat == true) null else {
                        {
                            val copyable = selectedMessages
                                .filter {
                                    isMessageCopyable(
                                        it.type,
                                        isSecretChat = state.isSecretChat == true,
                                        copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK)
                                    )
                                }
                                .map { it.parsedContent() }
                                .filter { it.isNotBlank() }
                            if (copyable.isNotEmpty()) {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, copyable.joinToString("\n")))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                selectedMessageIds = emptySet()
                            } else {
                                Toast.makeText(context, context.getString(R.string.chat_copy_no_text), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    // 1.10：批量置顶/取消置顶选中消息（1.20：无权限时不显示入口）
                    shouldPin = shouldPin,
                    onTogglePin = if (canBatchPin) {
                        {
                            viewModel.togglePinMessages(
                                messageIds = selectedMessages.map { it.id },
                                shouldPin = shouldPin
                            )
                            selectedMessageIds = emptySet()
                        }
                    } else null
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
                    val itemPlacementSpec = motion.listItemPlacementSpec()
                    when (item) {
                        is ChatItem.DateSeparator -> {
                            Box(modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .then(
                                    if (itemPlacementSpec != null) Modifier.animateItem(placementSpec = itemPlacementSpec)
                                    else Modifier
                                ),
                                contentAlignment = Alignment.Center) {
                                // 9.270：TG 式日期胶囊——全胶囊形 + 加深半透明（悬浮在壁纸上的磨砂感，
                                // 原 12dp 圆角矩形 + 淡半透明），字号收紧更精致
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier
                                        .background(
                                            LocalChatPalette.current.systemMessageBackground.copy(alpha = 0.32f),
                                            RoundedCornerShape(percent = 50)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                        is ChatItem.UnreadSeparator -> {
                            // 1.03：「以下为未读消息」分隔线；1.74：点击跳转到第一条未读消息
                            // 9.271：TG 式未读分隔条——全宽胶囊条 + 白字（TG 观感，原红线夹文字微信式）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .then(if (itemPlacementSpec != null) Modifier.animateItem(placementSpec = itemPlacementSpec) else Modifier)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                                    .clickable { viewModel.jumpToMessage(item.messageId) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.chat_unread_divider),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = Color.White,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                            }
                        }
                        is ChatItem.Msg -> {
                            val message = item.message
                            DisposableEffect(message.id) {
                                onDispose { bubbleBounds.remove(message.id) }
                            }
                            val isOwn = message.senderId == state.currentUserId
                            val isSearchHit = searchResults.getOrNull(searchIndex)?.id == message.id ||
                                navigationHighlightMessageId == message.id
                            val meta = remember(message.id, message.content) { message.parsedMeta() }
                            val displayContent = remember(message.id, message.content) { message.parsedContent() }
                            val displayMessage = remember(message, displayContent, meta) {
                                message.copy(content = displayContent, meta = meta)
                            }
                            val canShowSafety = (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) &&
                                !isOwn &&
                                message.id !in dismissedSafetyMessageIds
                            val groupReadCount = if (
                                ReadReceiptPolicy.shouldShowGroupReadCount(
                                    isGroup = state.chatIsGroup,
                                    isOwnMessage = isOwn,
                                    viewerRole = state.myMemberRole,
                                )
                            ) {
                                state.groupReadCounts[message.id]
                            } else null
                            // 9.264：TG 式分组密度——同一发送者 6 分钟内的连续消息紧凑成组（3dp），
                            // 换人/超时/跨分隔符额外补 5dp（总 8dp）；reverseLayout 下时间上更早的
                            // 消息在 index+1，本项 top padding 即与它的间隙
                            val prevItem = reversedChatItems.getOrNull(index + 1)
                            val groupedWithPrev = prevItem is ChatItem.Msg &&
                                prevItem.message.senderId == message.senderId &&
                                kotlin.math.abs(message.timestamp - prevItem.message.timestamp) < 6L * 60L * 1000L
                            Column(Modifier.fillMaxWidth().padding(top = if (groupedWithPrev) 0.dp else 5.dp)) {
                            ChatMessageRow(
                                state = ChatMessageRowState(
                                    message = displayMessage,
                                    isOwn = isOwn,
                                    showAvatar = item.showAvatar,
                                    showSenderName = item.showSenderName,
                                    isGroupEdge = item.isGroupEdge,
                                    senderName = resolveSenderName(message, isOwn),
                                    replyToPreview = meta.replyToId?.let(messagesById::get)?.let {
                                        ReplyPreview(
                                            senderName = resolveSenderName(it, isOwn = false) ?: "",
                                            preview = MessagePreviewText.replyOrQuote(
                                                message = it,
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
                                            ).take(60)
                                        )
                                    },
                                    isSearchHit = isSearchHit,
                                    animateEntry = index == 0,
                                    isAnimatingRemoval = message.id == animatingMessageId,
                                    isSelected = message.id in selectedMessageIds,
                                    selectionMode = messageSelectionMode,
                                    currentUserId = state.currentUserId,
                                    isVoiceTranscribing = message.id in state.transcribingVoiceMessageIds,
                                    isTranslating = message.id in state.translatingMessageIds,
                                    fileTransferProgress = state.fileTransferProgress[message.id],
                                    fileTransferState = state.fileTransferStates[message.id]
                                        ?: AttachmentTransferState.PREPARING.takeIf { message.id in state.preparingAttachmentMessageIds },
                                    fileTransferError = state.fileTransferErrors[message.id],
                                    mediaDownloadFailed = message.id in state.mediaDownloadErrorMessageIds,
                                    safetyWarning = if (canShowSafety) messageSafetyWarning(displayContent, localSafetyEnabled) else null,
                                    isGroupChat = state.chat?.isGroup == true,
                                    groupReadCount = groupReadCount,
                                    // 0.65 新功能：发送者群内角色（群主/管理员徽章）
                                    memberRole = if (state.chat?.isGroup == true) state.memberRoleByUser[message.senderId] else null,
                                    secretChatId = if (state.isSecretChat == true) state.chat?.id else null,
                                ),
                                onImageClick = { fullScreenImage = it },
                                onVideoClick = { fullScreenVideo = it },
                                onReply = {
                                    replyTarget = message
                                    // 1.47：群聊回复时自动 @ 发送者（输入未包含时前置，对标微信/QQ）
                                    if (state.chatIsGroup && message.senderId != state.currentUserId) {
                                        val senderName = resolveSenderName(message, isOwn = false)
                                        if (!senderName.isNullOrBlank()) {
                                            val mentionToken = "@$senderName"
                                            if (!state.inputText.contains(mentionToken)) {
                                                val base = state.inputText.trimEnd()
                                                viewModel.onInputChange(if (base.isEmpty()) "$mentionToken " else "$base $mentionToken ")
                                            }
                                        }
                                    }
                                },
                                onReplyPreviewClick = { target ->
                                    // 点击引用预览跳转到被回复的消息（id 在 meta 中，而非当前消息自身）
                                    val replyTo = target.parsedMeta().replyToId
                                    if (!replyTo.isNullOrBlank()) viewModel.jumpToMessage(replyTo)
                                },
                                onBoundsMeasured = { offset, size -> bubbleBounds[message.id] = BubbleBounds(offset, size) },
                                onFileClick = { viewModel.requestOpenFile(it.id) },
                                onPauseFileTransfer = viewModel::pauseFileTransfer,
                                onResumeFileTransfer = viewModel::resumeFileTransfer,
                                onCancelFileTransfer = viewModel::cancelFileTransfer,
                                onRequestMediaAttachment = viewModel::requestMediaAttachment,
                                onRequestVoiceTranscript = { id ->
                                    viewModel.requestVoiceTranscription(id)
                                },
                                onCopyVoiceTranscript = { transcript ->
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText(chatClipboardTranscriptLabel, transcript)
                                    )
                                    Toast.makeText(context, context.getString(R.string.chat_transcript_copied), Toast.LENGTH_SHORT).show()
                                },
                                onDismissSafety = if (canShowSafety) {
                                    { dismissSafetyForMessage(message.id) }
                                } else null,
                                onQuickReaction = { viewModel.setMessageReaction(it.id, DEFAULT_QUICK_REACTION) },
                                onReactionClick = { msg, emoji -> viewModel.setMessageReaction(msg.id, emoji) },
                                onPollVote = { pollId, idx -> viewModel.votePoll(pollId, idx) },
                                onRevealSpoiler = { viewModel.revealSpoilerMedia(it) },
                                onViewOnceOpened = { id -> viewModel.markViewOnceOpened(id) },
                                onInlineKeyboardClick = { msgId, data ->
                                    val botId = messagesById[msgId]?.senderId
                                        ?: state.messages.firstOrNull { it.id == msgId }?.senderId
                                    if (!botId.isNullOrBlank()) viewModel.sendBotCallback(msgId, botId, data)
                                },
                                onToggleSelection = {
                                    selectedMessageIds = toggleMessageSelection(selectedMessageIds, it.id)
                                },
                                onLongPress = {
                                    if (it.status == MessageStatus.FAILED && isOwn) messageToRetry = message
                                    else messageToActions = message
                                },
                                // 1.17：点击消息内名片 → 打开对方资料（未接线时提示）
                                onContactCardClick = { userId ->
                                    if (onOpenProfile != null) {
                                        onOpenProfile(userId)
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.chat_contact_card_tap_hint), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                // 1.44：点击消息发送者名称 → 打开其资料
                                onSenderClick = { userId ->
                                    if (onOpenProfile != null) {
                                        onOpenProfile(userId)
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.chat_contact_card_tap_hint), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                // 1.51：点击已读状态图标 → 打开阅读详情（仅自己消息）
                                onStatusClick = { msg ->
                                    if (ReadReceiptPolicy.canViewReceipts(
                                            viewerId = state.currentUserId,
                                            senderId = msg.senderId,
                                            isGroup = state.chatIsGroup,
                                            viewerRole = state.myMemberRole,
                                        )
                                    ) {
                                        messageForReadReceipts = msg
                                        viewModel.loadReadReceipts(msg.id)
                                    }
                                },
                                modifier = if (itemPlacementSpec != null) Modifier.animateItem(placementSpec = itemPlacementSpec) else Modifier
                            )
                            }
                        }
                    }
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

    if (showBatchDeleteConfirm) {
        // 8.53：与单条删除语义一致——仅删除本人消息（服务端 403 只能删自己发送的消息）；
        // 选中他人消息不参与删除，仅作转发/星标用途
        val deletableBatch = remember(selectedMessages, state.currentUserId) {
            selectedMessages.filter { it.senderId == state.currentUserId }
        }
        // 8.55：服务端 mutation 限流 60/min——批量封顶 60 条，超出提示分批，避免 429「删一半剩一半」
        val batchCap = 60
        val cappedBatch = deletableBatch.take(batchCap)
        val cappedOut = deletableBatch.size - cappedBatch.size
        val skippedCount = selectedMessages.size - deletableBatch.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_selected_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_delete_selected_message, cappedBatch.size))
                    if (skippedCount > 0) {
                        Text(
                            stringResource(R.string.chat_batch_delete_skipped_others, skippedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (cappedOut > 0) {
                        Text(
                            stringResource(R.string.chat_batch_delete_capped, cappedOut),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = cappedBatch.isNotEmpty(), onClick = {
                    // 9.229：串行批量删除，避免逐条并发打满服务端 mutation 限流
                    viewModel.deleteMessagesBatch(cappedBatch.map { it.id })
                    selectedMessageIds = emptySet()
                    showBatchDeleteConfirm = false
                    // 1.50：删除完成提示
                    Toast.makeText(context, context.resources.getQuantityString(R.plurals.chat_batch_delete_done, cappedBatch.size, cappedBatch.size), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
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

    // 8.43：图片发送前预览确认
    pendingImageConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingImageConfirm = null },
            title = { Text(stringResource(R.string.chat_image_send_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    coil.compose.AsyncImage(
                        model = pending.uri,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    // 0.71：预览后可选「重新选择」（误选不用取消再进一次相册）
                    TextButton(
                        onClick = {
                            pendingImageConfirm = null
                            pendingViewOnce = pending.viewOnce
                            pendingSpoiler = pending.spoiler
                            listScrollScope.launch {
                                kotlinx.coroutines.yield()
                                runCatching { imagePickerLauncher.launch("image/*") }
                                    .onFailure {
                                        Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                                    }
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text(stringResource(R.string.chat_rechoose), color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImageConfirm = null
                    if (pending.viewOnce) viewModel.sendViewOnceImage(pending.uri)
                    else if (pending.spoiler) viewModel.sendSpoilerImage(pending.uri)
                    else viewModel.sendImage(pending.uri)
                }) { Text(stringResource(R.string.chat_send), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImageConfirm = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
            }
        )
    }

    // 0.69：视频发送前预览确认（文件信息 + 发送/取消，与图片流程一致）
    pendingVideoConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingVideoConfirm = null },
            title = { Text(stringResource(R.string.chat_video_send_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp).padding(bottom = 8.dp)
                    )
                    val fileName = pending.uri.lastPathSegment?.substringAfterLast('/')
                        ?: stringResource(R.string.message_preview_video)
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingVideoConfirm = null
                    if (pending.viewOnce) viewModel.sendViewOnceVideo(pending.uri)
                    else if (pending.spoiler) viewModel.sendSpoilerVideo(pending.uri)
                    else viewModel.sendVideo(pending.uri)
                }) { Text(stringResource(R.string.chat_send), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingVideoConfirm = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
            }
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

    // 复制文本弹窗
    messageToCopy?.let { msg ->
        val isOwn = msg.senderId == state.currentUserId
        val withinEditWindow = System.currentTimeMillis() - msg.timestamp < 300_000
        AlertDialog(
            onDismissRequest = { messageToCopy = null },
            title = { Text(stringResource(R.string.chat_message_actions)) },
            text = { Text(msg.parsedContent()) },
            confirmButton = {
                Row {
                    if (isMessageCopyable(msg.type, isSecretChat = state.isSecretChat == true, copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK))) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, msg.parsedContent()))
                            Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                            messageToCopy = null
                        }) { Text(stringResource(R.string.chat_copy)) }
                    } else {
                        TextButton(onClick = {
                            Toast.makeText(context, context.getString(R.string.secret_chat_copy_blocked), Toast.LENGTH_SHORT).show()
                            messageToCopy = null
                        }) { Text(stringResource(R.string.chat_copy)) }
                    }
                    if (isMessageForwardable(msg.type, isSecretChat = state.isSecretChat == true, forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK))) {
                        TextButton(onClick = {
                            messagesToForward = listOf(msg)
                            viewModel.loadForwardTargets()
                            messageToCopy = null
                        }) { Text(stringResource(R.string.chat_forward)) }
                    }
                    if (isOwn && withinEditWindow && (msg.type == MessageType.TEXT || msg.type == MessageType.MARKDOWN)) {
                        TextButton(onClick = {
                            editDraft = msg.parsedContent()
                            messageToEdit = msg
                            messageToCopy = null
                        }) { Text(stringResource(R.string.chat_edit)) }
                    }
                }
            },
            dismissButton = {
                if (isOwn) {
                    Row {
                        if (withinEditWindow && msg.type != MessageType.REVOKED) {
                            TextButton(onClick = {
                                messageToRevoke = msg
                                messageToCopy = null
                            }) { Text(stringResource(R.string.chat_revoke), color = LocalChatPalette.current.unreadRed) }
                        }
                        TextButton(onClick = {
                            startParticleEffect(msg, ParticleAction.DELETE)
                            messageToCopy = null
                        }) { Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed) }
                    }
                } else {
                    TextButton(onClick = { messageToCopy = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            }
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
    messagesToForward.takeIf { it.isNotEmpty() }?.let { forwardMessages ->
        val forwardMeLabel = stringResource(R.string.chat_sender_me)
        var forwardQuery by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf("") }
        var forwardNote by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf("") }
        var forwardExpanded by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf(false) }
        // 1.34：多选转发目标（勾选多个会话后一次转发）
        var selectedForwardChatIds by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf<Set<String>>(emptySet()) }
        // 1.149：合并转发（多条文本消息合并为一条发送）
        val forwardMergeable = forwardMessages.size > 1 && forwardMessages.all {
            it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN
        }
        var forwardMerged by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf(false) }
        val filteredForwardTargets = remember(state.forwardTargets, forwardQuery) {
            val q = forwardQuery.trim()
            if (q.isEmpty()) state.forwardTargets
            else state.forwardTargets.filter { chat ->
                forwardTargetName(context, chat, state.currentUserId).contains(q, ignoreCase = true)
            }
        }
        val forwardPageSize = 64
        val visibleForwardTargets = if (forwardExpanded) {
            filteredForwardTargets
        } else {
            filteredForwardTargets.take(forwardPageSize)
        }
        AlertDialog(
            onDismissRequest = { messagesToForward = emptyList() },
            title = { Text(if (forwardMessages.size == 1) stringResource(R.string.chat_forward_to) else stringResource(R.string.chat_forward_selected, forwardMessages.size)) },
            text = {
                if (state.forwardTargets.isEmpty()) {
                    Text(stringResource(R.string.chat_no_forward_targets), color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                    ) {
                        // 1.164：转发内容预览（最多显示 3 条，其余折叠为数量提示）
                        forwardMessages.take(3).forEach { fm ->
                            val previewText = when (fm.type) {
                                MessageType.IMAGE -> stringResource(R.string.message_preview_image)
                                MessageType.GIF -> stringResource(R.string.message_preview_gif)
                                MessageType.STICKER -> stringResource(R.string.message_preview_sticker)
                                MessageType.VOICE -> stringResource(R.string.message_preview_voice)
                                MessageType.VIDEO -> stringResource(R.string.message_preview_video)
                                MessageType.FILE -> stringResource(R.string.message_preview_file)
                                MessageType.LOCATION -> stringResource(R.string.message_preview_location)
                                else -> com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                    fm.parsedContent(),
                                    context.getString(R.string.chat_decrypt_failed)
                                ).replace('\n', ' ').take(40)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .background(LocalChatPalette.current.systemMessageBackground.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    previewText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (forwardMessages.size > 3) {
                            Text(
                                pluralStringResource(R.plurals.chat_forward_more_previews, forwardMessages.size - 3, forwardMessages.size - 3),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint
                            )
                        }
                        OutlinedTextField(
                            value = forwardQuery,
                            onValueChange = {
                                forwardQuery = it
                                forwardExpanded = false
                            },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.chat_forward_search_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = forwardNote,
                            onValueChange = { forwardNote = it.take(500) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.chat_forward_note_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        // 1.149：合并转发开关（仅多条文本消息时可用）
                        if (forwardMergeable) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.chat_forward_merge_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text(stringResource(R.string.chat_forward_merge_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                                }
                                Switch(checked = forwardMerged, onCheckedChange = { forwardMerged = it })
                            }
                        }
                        if (filteredForwardTargets.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_forward_search_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textHint
                            )
                        } else {
                            // 1.159：最近会话快捷选择（顶部 6 个，点击勾选/取消）
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                filteredForwardTargets.take(6).forEach { recentChat ->
                                    val recentSelected = selectedForwardChatIds.contains(recentChat.id)
                                    FilterChip(
                                        selected = recentSelected,
                                        onClick = {
                                            if (recentSelected) {
                                                selectedForwardChatIds = selectedForwardChatIds - recentChat.id
                                            } else {
                                                selectedForwardChatIds = selectedForwardChatIds + recentChat.id
                                            }
                                        },
                                        label = { Text(forwardTargetName(context, recentChat, state.currentUserId), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                            // B2 转发白名单（fwlz）：源为密聊且白名单开启时，非白名单目标需先加入白名单
                            val secretSource = secretActive
                            val fwlEnabled = com.maodouchat.util.SecretForwardWhitelistPrefs.isEnabled(context)
                            visibleForwardTargets.forEach { chat ->
                                val whitelisted = !fwlEnabled || com.maodouchat.util.SecretForwardWhitelistPrefs.isForwardAllowed(context, chat.id)
                                TextButton(
                                    onClick = {
                                        // 1.34：点击勾选/取消目标会话（白名单目标可勾选）
                                        if (whitelisted) {
                                            selectedForwardChatIds =
                                                if (selectedForwardChatIds.contains(chat.id)) selectedForwardChatIds - chat.id
                                                else selectedForwardChatIds + chat.id
                                        }
                                    },
                                    enabled = !secretSource || whitelisted,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        if (selectedForwardChatIds.contains(chat.id)) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(forwardTargetName(context, chat, state.currentUserId), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                        if (secretSource && fwlEnabled && !whitelisted) {
                                            TextButton(onClick = {
                                                val whitelist = com.maodouchat.util.SecretForwardWhitelistPrefs.whitelist(context) + chat.id
                                                com.maodouchat.util.SecretForwardWhitelistPrefs.setWhitelist(context, whitelist)
                                                Toast.makeText(context, context.getString(R.string.secret_forward_whitelist_added, forwardTargetName(context, chat, state.currentUserId)), Toast.LENGTH_SHORT).show()
                                            }) {
                                                Text(stringResource(R.string.secret_forward_whitelist_add), color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                            if (!forwardExpanded && filteredForwardTargets.size > forwardPageSize) {
                                TextButton(onClick = { forwardExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(
                                            R.string.chat_forward_targets_more,
                                            filteredForwardTargets.size - forwardPageSize
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else if (forwardExpanded && filteredForwardTargets.size > forwardPageSize) {
                                Text(
                                    stringResource(R.string.chat_forward_targets_showing_all, filteredForwardTargets.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textHint
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.loadForwardTargets() }) { Text(stringResource(R.string.common_refresh)) }
                    // 1.34：确认转发到所选会话（可多选）
                    TextButton(
                        enabled = selectedForwardChatIds.isNotEmpty(),
                        onClick = {
                            // 1.149：合并转发——多条文本合并为一条（带发送者标签）
                            val mergedText = if (forwardMerged && forwardMergeable) {
                                forwardMessages.joinToString("\n") { msg ->
                                    val senderLabel = when {
                                        msg.senderId == state.currentUserId -> forwardMeLabel
                                        else -> state.chat?.participants?.firstOrNull { it.id == msg.senderId }?.displayName?.takeIf { it.isNotBlank() }
                                    }
                                    val content = msg.parsedContent().take(4_000)
                                    (senderLabel?.let { "$it：$content" } ?: content)
                                }.take(4_000)
                            } else null
                            if (mergedText != null) {
                                selectedForwardChatIds.forEach { chatId ->
                                    // 1.157：合并转发时附带留言并入合并文本首行（不单独发第二条）
                                    val finalMerged = if (forwardNote.isNotBlank()) "$forwardNote\n$mergedText" else mergedText
                                    viewModel.sendTextToChat(chatId, finalMerged)
                                }
                            } else {
                                // 9.227：批量串行转发——旧实现逐条并发触发 forwardMessage，
                                // 留言也抢在附件转发前发出；现改为单协程内按顺序串行转发+最后补发留言
                                viewModel.forwardMessagesBatch(forwardMessages, selectedForwardChatIds.toList(), forwardNote)
                            }
                            messagesToForward = emptyList()
                            selectedMessageIds = emptySet()
                        }
                    ) {
                        Text(
                            if (selectedForwardChatIds.size > 1) {
                                stringResource(R.string.chat_forward_to_selected, selectedForwardChatIds.size)
                            } else {
                                stringResource(R.string.chat_forward_confirm)
                            },
                            color = if (selectedForwardChatIds.isNotEmpty()) Primary else TextSecondary
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = { messagesToForward = emptyList() }) { Text(stringResource(R.string.common_cancel)) } }
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

    // 全屏图片查看器（缩放 + 保存/分享）
    fullScreenImage?.let { msg ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreenImage = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val meta = remember(msg.id, msg.content) { msg.parsedMeta() }
            val mime = com.maodouchat.util.MediaViewerPolicy.defaultMime(msg.type.name, meta.fileMimeType)
            val displayName = com.maodouchat.util.MediaViewerPolicy.defaultFileName(
                msg.type.name,
                meta.fileName,
                mime
            )
            val localOk = remember(msg.content) {
                com.maodouchat.util.MediaCache.isReadableLocalUri(context, msg.parsedContent())
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                com.maodouchat.ui.component.ZoomableAsyncImage(
                    model = msg.parsedContent(),
                    contentDescription = stringResource(R.string.chat_fullscreen_image),
                    onSingleTap = { fullScreenImage = null }
                )
                IconButton(
                    onClick = { fullScreenImage = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close), tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.media_viewer_hint),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (state.isSecretChat == true) {
                                    Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (!localOk || !com.maodouchat.util.MediaViewerPolicy.canExportLocal(
                                        localOk,
                                        secretChat = state.isSecretChat == true,
                                        exportBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)
                                    )) {
                                    Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                listScrollScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        com.maodouchat.util.MediaExport.saveToGallery(
                                            context = context,
                                            rawUri = msg.parsedContent(),
                                            mimeType = mime,
                                            displayName = displayName
                                        )
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(if (ok) R.string.media_export_saved else R.string.media_export_save_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.common_save), color = Color.White)
                        }
                        TextButton(
                            onClick = {
                                if (state.isSecretChat == true) {
                                    Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (!localOk || !com.maodouchat.util.MediaViewerPolicy.canShareLocal(
                                        localOk,
                                        secretChat = state.isSecretChat == true,
                                        exportBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)
                                    )) {
                                    Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                val ok = com.maodouchat.util.MediaExport.share(
                                    context = context,
                                    rawUri = msg.parsedContent(),
                                    mimeType = mime,
                                    chooserTitle = context.getString(R.string.common_share)
                                )
                                if (!ok) {
                                    Toast.makeText(context, context.getString(R.string.media_export_share_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.common_share), color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // 全屏视频播放器
    fullScreenVideo?.let { msg ->
        val videoContent = msg.content
        // 内容为空/非法时直接关闭弹窗，避免 Uri.parse 失败或 VideoView 加载异常
        if (videoContent.isNullOrBlank()) { fullScreenVideo = null; return@let }
        // 0.82：视频保存到相册所需 meta
        val videoMeta = remember(msg.id, msg.content) { msg.parsedMeta() }
        val videoMime = com.maodouchat.util.MediaViewerPolicy.defaultMime("VIDEO", videoMeta.fileMimeType)
        val videoName = com.maodouchat.util.MediaViewerPolicy.defaultFileName("VIDEO", videoMeta.fileName, videoMime)
        val videoLocalOk = remember(msg.content) {
            com.maodouchat.util.MediaCache.isReadableLocalUri(context, videoContent)
        }
        // 9.150：引用放入 remember 状态，避免内容重组时被重置为 null 导致 onDispose 跳过 stopPlayback
        val videoViewRef = remember { mutableStateOf<android.widget.VideoView?>(null) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreenVideo = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = {
                        android.widget.VideoView(it).apply {
                            videoViewRef.value = this
                            setVideoURI(android.net.Uri.parse(videoContent))
                            setOnCompletionListener { fullScreenVideo = null }
                            setOnErrorListener { _, _, _ -> fullScreenVideo = null; true }
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose {
                        // 用户关闭/系统退出时，确保 MediaPlayer 释放，避免原生资源泄漏与后台继续出声
                        videoViewRef.value?.stopPlayback()
                        videoViewRef.value = null
                        fullScreenVideo = null
                    }
                }
                IconButton(
                    onClick = { fullScreenVideo = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close), tint = Color.White, modifier = Modifier.size(32.dp))
                }
                // 0.82：视频保存到相册
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(onClick = {
                        if (state.isSecretChat == true) {
                            Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (!videoLocalOk || !com.maodouchat.util.MediaViewerPolicy.canExportLocal(
                                videoLocalOk,
                                secretChat = state.isSecretChat == true,
                                exportBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)
                            )) {
                            Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val saved = com.maodouchat.util.MediaExport.saveToGallery(
                            context = context,
                            rawUri = videoContent,
                            mimeType = videoMime,
                            displayName = videoName
                        )
                        Toast.makeText(
                            context,
                            context.getString(if (saved) R.string.media_saved else R.string.media_save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text(stringResource(R.string.media_save), color = Color.White)
                    }
                }
            }
        }
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

