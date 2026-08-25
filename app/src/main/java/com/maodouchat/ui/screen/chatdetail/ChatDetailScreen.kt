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

private data class TranslationLanguageOption(
    val wireValue: String,
    val labelResource: Int
)

private val translationLanguageOptions = listOf(
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
private fun AiSummaryScope.localizedLabel(): String = stringResource(when (this) {
    AiSummaryScope.RECENT -> R.string.chat_ai_summary_scope_recent
    AiSummaryScope.TODAY -> R.string.chat_ai_summary_scope_today
    AiSummaryScope.SEVEN_DAYS -> R.string.chat_ai_summary_scope_week
    AiSummaryScope.THIRTY_DAYS -> R.string.chat_ai_summary_scope_month
    AiSummaryScope.SEARCH_RESULTS -> R.string.chat_ai_summary_scope_search
    AiSummaryScope.UNREAD -> R.string.chat_ai_summary_scope_unread
})

@Composable
private fun AiImageAnalysisMode.localizedLabel(): String = stringResource(when (this) {
    AiImageAnalysisMode.DESCRIBE -> R.string.chat_ai_image_mode_describe
    AiImageAnalysisMode.OCR -> R.string.chat_ai_image_mode_ocr
    AiImageAnalysisMode.SAFETY -> R.string.chat_ai_image_mode_safety
})

@Composable
private fun AiFileAnalysisMode.localizedLabel(): String = stringResource(when (this) {
    AiFileAnalysisMode.SUMMARIZE -> R.string.chat_ai_file_mode_summarize
    AiFileAnalysisMode.QUESTION -> R.string.chat_ai_file_mode_question
})

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域；lint 无法区分
fun ChatDetailScreen(
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
                com.maodouchat.crypto.SessionCipherOccupancy.occupy(
                    viewModel.activeChatId,
                    peerUserId = null,
                    updatePeer = true
                )
            }
            peer != null -> {
                com.maodouchat.crypto.SessionCipherOccupancy.occupy(
                    viewModel.activeChatId,
                    peer,
                    updatePeer = true
                )
            }
            else -> {
                // Contact not loaded yet — pin chatId only. Never pass updatePeer=true
                // with a blank peer: that clears openPeerUserId and lets list/backlog
                // decrypt the sibling DIRECT/SECRET ratchet.
                com.maodouchat.crypto.SessionCipherOccupancy.occupy(viewModel.activeChatId)
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
                    state.chatIsGroup -> com.maodouchat.crypto.SessionCipherOccupancy.occupy(
                        viewModel.activeChatId,
                        peerUserId = null,
                        updatePeer = true
                    )
                    resumePeer != null -> com.maodouchat.crypto.SessionCipherOccupancy.occupy(
                        viewModel.activeChatId,
                        resumePeer,
                        updatePeer = true
                    )
                    else -> com.maodouchat.crypto.SessionCipherOccupancy.occupy(viewModel.activeChatId)
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
        var groupInfoCandidatesExpanded by remember(chat?.id) { mutableStateOf(false) }
        val groupInfoCandidatePage = 20
        AlertDialog(
            onDismissRequest = {
                showGroupInfo = false
                groupInfoSearch = ""
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
    Scaffold(
        containerColor = Color.Transparent,
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
                        IconButton(onClick = { state.chat?.id?.let(onOpenGroupDetail) ?: run { showGroupInfo = true } }) {
                            Icon(Icons.Outlined.Group, contentDescription = stringResource(R.string.chat_group_info), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
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
                            if (state.chatIsGroup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_group_info)) },
                                    onClick = {
                                        showChatOverflow = false
                                        state.chat?.id?.let(onOpenGroupDetail) ?: run { showGroupInfo = true }
                                    }
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.media_center_title)) },
                                onClick = { showChatOverflow = false; state.chat?.id?.let(onOpenMediaCenter) }
                            )
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

            // 消息列表
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            val groupReadLabel = if (
                                ReadReceiptPolicy.shouldShowGroupReadCount(
                                    isGroup = state.chatIsGroup,
                                    isOwnMessage = isOwn,
                                    viewerRole = state.myMemberRole,
                                )
                            ) {
                                state.groupReadCounts[message.id]?.let { count ->
                                    stringResource(R.string.chat_group_read_status, count.read, count.total)
                                }
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
                            if (groupReadLabel != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(top = 2.dp, end = 14.dp)
                                        // 1.180：点击「已读 X/Y」打开该消息阅读详情
                                        .clickable {
                                            if (ReadReceiptPolicy.canViewReceipts(
                                                    viewerId = state.currentUserId,
                                                    senderId = message.senderId,
                                                    isGroup = state.chatIsGroup,
                                                    viewerRole = state.myMemberRole,
                                                )
                                            ) {
                                                messageForReadReceipts = message
                                                viewModel.loadReadReceipts(message.id)
                                            }
                                        }
                                ) {
                                    Text(groupReadLabel, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textSecondary)
                                }
                            }
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

            // 1.162：已恢复本地草稿 → 输入框上方提示（含一键清空）
            if (state.hasSavedDraft && state.inputText.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(Secondary.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.EditNote, contentDescription = null, tint = Secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.chat_draft_restored),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        viewModel.onInputChange("")
                        viewModel.clearDraftPersistence()
                    }) { Text(stringResource(R.string.chat_clear_draft), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.labelMedium) }
                }
            }

            // 输入区
            ChatInputBar(
                value = state.inputText,
                onValueChange = { viewModel.onInputChange(it) },
                onSend = {
                    if (state.isSending) return@ChatInputBar
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

@Composable
private fun AiOperationStatusBar(
    operations: List<AiOperationUi>,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    val operation = operations.firstOrNull() ?: return
    val isFailed = operation.state == AiOperationState.FAILED
    val context = LocalContext.current
    val title = when (operation.type) {
        AiOperationType.TRANSCRIBE_VOICE -> stringResource(R.string.chat_ai_operation_transcribe)
        AiOperationType.TRANSLATE_MESSAGE -> stringResource(R.string.chat_ai_operation_translate)
        AiOperationType.SUMMARIZE_MESSAGES -> stringResource(R.string.chat_ai_operation_summary)
        AiOperationType.ANALYZE_IMAGE -> stringResource(R.string.chat_ai_operation_image)
        AiOperationType.ANALYZE_FILE -> stringResource(R.string.chat_ai_operation_file)
        else -> stringResource(R.string.chat_ai_consent_title)
    }
    val errorBase = com.maodouchat.ai.AiCostVisibilityPolicy.baseErrorCode(operation.lastErrorCode)
    val waitSeconds = operation.retryAfterSeconds
        ?: com.maodouchat.ai.AiCostVisibilityPolicy.waitSecondsFor(operation.lastErrorCode)
    val status = when {
        errorBase == AiOperationError.INTERRUPTED ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        errorBase == AiOperationError.OUTCOME_UNKNOWN ||
            errorBase == AiOperationError.TIMEOUT ||
            errorBase == AiOperationError.UNKNOWN ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        errorBase == AiOperationError.RATE_LIMITED ->
            stringResource(
                R.string.chat_ai_operation_rate_limited,
                waitSeconds.coerceAtLeast(1L)
            )
        errorBase == AiOperationError.QUOTA_EXCEEDED ->
            stringResource(R.string.chat_ai_operation_quota_exceeded)
        errorBase == AiOperationError.CONNECTION_NOT_ESTABLISHED &&
            operation.nextRetryAtMs != null -> stringResource(
                R.string.chat_ai_operation_retry_scheduled,
                android.text.format.DateFormat.getTimeFormat(context).format(Date(operation.nextRetryAtMs))
            )
        errorBase == AiOperationError.CONTEXT_MISSING ->
            stringResource(R.string.chat_ai_operation_context_missing)
        errorBase in setOf(
            AiOperationError.NETWORK,
            AiOperationError.CONNECTION_NOT_ESTABLISHED
        ) ->
            stringResource(R.string.chat_ai_operation_network_failed)
        errorBase == AiOperationError.SERVER ->
            stringResource(R.string.chat_ai_operation_server_failed)
        errorBase in setOf(AiOperationError.EMPTY_RESULT, AiOperationError.INVALID_RESPONSE) ->
            stringResource(R.string.chat_ai_operation_invalid_result)
        operation.state == AiOperationState.QUEUED -> stringResource(R.string.chat_ai_operation_queued)
        operation.state == AiOperationState.RUNNING ->
            stringResource(R.string.chat_ai_operation_running_billing)
        else -> stringResource(R.string.chat_ai_operation_failed)
    }
    val showRetryBillHint = isFailed &&
        com.maodouchat.ai.AiCostVisibilityPolicy.shouldWarnRetryBills(operation.lastErrorCode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryFixed.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFailed) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(status, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            if (showRetryBillHint) {
                Text(
                    stringResource(R.string.chat_ai_operation_retry_may_bill),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (operation.attempts > 0) {
                Text(
                    stringResource(R.string.chat_ai_operation_attempts, operation.attempts),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (operations.size > 1) {
                Text(
                    stringResource(R.string.chat_ai_operation_more, operations.size - 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        }
        if (isFailed) {
            IconButton(onClick = { onRetry(operation.id) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.chat_ai_operation_retry),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(
            onClick = {
                if (isFailed) onDismiss(operation.id) else onCancel(operation.id)
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    if (isFailed) R.string.chat_ai_operation_dismiss else R.string.chat_ai_operation_cancel
                ),
                tint = LocalChatPalette.current.textSecondary
            )
        }
    }
}

@Composable
private fun AiDraftStreamBar(
    preview: String,
    isStreaming: Boolean,
    errorCode: String?,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val isCancelled = errorCode == "CANCELLED"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_ai_draft_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isStreaming) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (isStreaming || errorCode != null) {
                Text(
                    if (isStreaming) stringResource(R.string.chat_ai_stream_generating)
                    else aiStreamStatusText(errorCode),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errorCode != null && !isCancelled) UnreadRed else TextSecondary
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when {
            isStreaming -> {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_cancel), tint = LocalChatPalette.current.textSecondary)
                }
            }
            errorCode == null -> {
                IconButton(onClick = onApply, enabled = preview.isNotBlank(), modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Check, stringResource(R.string.chat_ai_stream_apply), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_discard), tint = LocalChatPalette.current.textSecondary)
                }
            }
            else -> {
                if (isCancelled && preview.isNotBlank()) {
                    IconButton(onClick = onApply, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Check, stringResource(R.string.chat_ai_stream_apply), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_discard), tint = LocalChatPalette.current.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun aiStreamStatusText(errorCode: String?): String {
    val base = com.maodouchat.ai.AiCostVisibilityPolicy.baseErrorCode(errorCode)
    val wait = com.maodouchat.ai.AiCostVisibilityPolicy.waitSecondsFor(errorCode).coerceAtLeast(1L)
    return when (base) {
        "CANCELLED" -> stringResource(R.string.chat_ai_stream_cancelled)
        AiOperationError.RATE_LIMITED -> stringResource(R.string.chat_ai_stream_rate_limited, wait)
        AiOperationError.QUOTA_EXCEEDED -> stringResource(R.string.chat_ai_stream_quota_exceeded)
        AiOperationError.NETWORK -> stringResource(R.string.chat_ai_operation_network_failed)
        AiOperationError.TIMEOUT, AiOperationError.OUTCOME_UNKNOWN, AiOperationError.UNKNOWN ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        AiOperationError.SERVER -> stringResource(R.string.chat_ai_operation_server_failed)
        AiOperationError.EMPTY_RESULT, AiOperationError.INVALID_RESPONSE ->
            stringResource(R.string.chat_ai_operation_invalid_result)
        else -> stringResource(R.string.chat_ai_operation_failed)
    }
}

private fun openFile(context: android.content.Context, contentUri: String) {
    runCatching {
        val parsed = android.net.Uri.parse(contentUri)
        val uri = if (parsed.scheme == "file") {
            val file = java.io.File(requireNotNull(parsed.path))
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else parsed
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(parsed.toString()).lowercase()
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.chat_no_file_app), android.widget.Toast.LENGTH_SHORT).show()
        }
    }.onFailure { android.widget.Toast.makeText(context, context.getString(R.string.chat_open_file_failed), android.widget.Toast.LENGTH_SHORT).show() }
}

private fun requestVoiceCallPermission(
    context: Context,
    launchPermissionRequest: (String) -> Unit,
    contactId: String,
    contactName: String,
    onVoiceCall: (String, String) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        onVoiceCall(contactId, contactName)
    } else {
        launchPermissionRequest(Manifest.permission.RECORD_AUDIO)
    }
}

/** 单聊联系人资料卡：头像、ID、状态、最后在线、常用操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactProfileSheet(
    contact: com.maodouchat.data.model.User,
    isBlocked: Boolean,
    isBlocking: Boolean,
    hideCalls: Boolean = false,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onToggleBlock: () -> Unit,
    onReport: () -> Unit
) {
    val context = LocalContext.current
    var showAvatarFull by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showAvatarFull && !contact.avatar.isNullOrBlank()) {
                // 头像大图：全屏缩放查看，点按关闭
                Dialog(onDismissRequest = { showAvatarFull = false }) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.92f))
                            .clickable { showAvatarFull = false }
                    ) {
                        com.maodouchat.ui.component.ZoomableAsyncImage(
                            model = contact.avatar,
                            contentDescription = contact.displayName,
                            modifier = Modifier.fillMaxSize(),
                            onSingleTap = { showAvatarFull = false }
                        )
                    }
                }
            }
            com.maodouchat.ui.component.Avatar(
                name = contact.displayName,
                avatarUrl = contact.avatar,
                size = com.maodouchat.ui.component.AvatarSize.LG,
                isOnline = contact.isOnline,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = !contact.avatar.isNullOrBlank()) { showAvatarFull = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                contact.id,
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (contact.status.isNotBlank()) {
                Text(
                    contact.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                when {
                    contact.isOnline -> stringResource(R.string.chat_online)
                    contact.lastSeen > 0 -> stringResource(R.string.user_last_seen_prefix) + " " +
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            contact.lastSeen,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS
                        )
                    else -> stringResource(R.string.chat_offline)
                },
                style = MaterialTheme.typography.labelMedium,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProfileAction(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.chat_send), onClick = onMessage)
                if (!hideCalls) {
                    ProfileAction(Icons.Outlined.Call, stringResource(R.string.chat_voice_call), onClick = onVoiceCall)
                    ProfileAction(Icons.Outlined.Videocam, stringResource(R.string.chat_video_call), onClick = onVideoCall)
                }
                ProfileAction(
                    if (isBlocked) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    stringResource(if (isBlocked) R.string.chat_unblock_user else R.string.chat_block_user),
                    enabled = !isBlocking,
                    onClick = onToggleBlock
                )
                ProfileAction(Icons.Outlined.Warning, stringResource(R.string.chat_report_user), onClick = onReport)
            }
        }
    }
}

@Composable
private fun ProfileAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Primary else TextHint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) OnSurface else TextHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun requestVideoCallPermissions(
    context: Context,
    launchPermissionRequest: (Array<String>) -> Unit,
    contactId: String,
    contactName: String,
    onVideoCall: (String, String) -> Unit
) {
    val missingPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    if (missingPermissions.isEmpty()) {
        onVideoCall(contactId, contactName)
    } else {
        launchPermissionRequest(missingPermissions.toTypedArray())
    }
}

@Composable
private fun GroupEncryptionWarningBanner(warning: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(warning, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GifSearchDialog(
    onPickUri: (Uri, String?) -> Unit,
    onBrowseFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<com.maodouchat.util.LocalGifItem>>(emptyList()) }
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(granted) {
        if (!granted) {
            loading = false
            items = emptyList()
            return@LaunchedEffect
        }
        loading = true
        items = withContext(Dispatchers.IO) {
            com.maodouchat.util.GifLibrary.queryLocalGifs(context)
        }
        loading = false
    }

    val recentIds = remember { com.maodouchat.util.GifSearchPreferences.getRecentIds(context) }
    val filtered = remember(items, query, recentIds) {
        com.maodouchat.util.GifSearchPolicy.filterAndSort(items, query, recentIds)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gif_search_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.gif_search_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!granted) {
                    Text(
                        text = stringResource(R.string.gif_search_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRequestPermission) {
                        Text(stringResource(R.string.gif_search_grant))
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.gif_search_hint)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Outline
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.gif_search_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                            }
                        }
                        filtered.isEmpty() -> {
                            Text(
                                text = stringResource(
                                    if (query.isBlank()) R.string.gif_search_empty
                                    else R.string.gif_search_empty_query
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        else -> {
                            Text(
                                text = pluralStringResource(R.plurals.gif_search_count, filtered.size, filtered.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                filtered.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row.forEach { item ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(88.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                                    .clickable {
                                                        onPickUri(Uri.parse(item.uriString), item.id)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                coil.compose.AsyncImage(
                                                    model = item.uriString,
                                                    contentDescription = item.displayName,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                            }
                                        }
                                        repeat(3 - row.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onBrowseFiles, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gif_search_browse), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
private fun ScheduledMessagesBanner(
    items: List<com.maodouchat.util.ScheduledMessage>,
    onCancel: (String) -> Unit,
    onReschedule: (String) -> Unit = {},
    onViewAll: () -> Unit = {}
) {
    val context = LocalContext.current
    val preview = items.take(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .clickable(onClick = onViewAll)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.schedule_pending_title, items.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (items.size > 3) {
                Text(
                    text = stringResource(R.string.schedule_view_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        preview.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatScheduleTime(item.sendAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    // 1.15：重复定时消息显示重复标识（1.21：含次数上限）
                                scheduleRepeatLabel(context, item.repeatIntervalMs, item.repeatCount, item.occurrencesSent, item.weekdaysOnly)?.let { repeatLabel ->
                        Text(
                            text = repeatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                TextButton(onClick = { onReschedule(item.id) }) {
                    Text(stringResource(R.string.schedule_reschedule), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { onCancel(item.id) }) {
                    Text(stringResource(R.string.schedule_cancel_one), color = LocalChatPalette.current.unreadRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledMessagesListSheet(
    items: List<com.maodouchat.util.ScheduledMessage>,
    onCancel: (String) -> Unit,
    onReschedule: (String) -> Unit,
    onSendNow: (String) -> Unit,
    onCancelAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 1.174：全部取消确认
    var showCancelAllConfirm by rememberSaveable { mutableStateOf(false) }
    val filteredItems = remember(items, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            items
        } else {
            items.filter { it.text.contains(query, ignoreCase = true) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.schedule_pending_title, items.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // 1.174：全部取消
            if (items.isNotEmpty()) {
                TextButton(onClick = { showCancelAllConfirm = true }) {
                    Text(stringResource(R.string.schedule_cancel_all), color = LocalChatPalette.current.unreadRed)
                }
            }
            if (items.size >= 4) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(120) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.schedule_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
            if (filteredItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_search_empty),
                    color = LocalChatPalette.current.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filteredItems.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatScheduleTime(item.sendAtMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                                // 1.15：重复定时消息显示重复标识（1.21：含次数上限）
                    scheduleRepeatLabel(context, item.repeatIntervalMs, item.repeatCount, item.occurrencesSent, item.weekdaysOnly)?.let { repeatLabel ->
                                    Text(
                                        text = repeatLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            TextButton(onClick = { onReschedule(item.id) }) {
                                Text(stringResource(R.string.schedule_reschedule), color = MaterialTheme.colorScheme.primary)
                            }
                            // 1.168：立即发送
                            TextButton(onClick = { onSendNow(item.id) }) {
                                Text(stringResource(R.string.schedule_send_now), color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { onCancel(item.id) }) {
                                Text(stringResource(R.string.schedule_cancel_one), color = LocalChatPalette.current.unreadRed)
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_close))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 1.174：全部取消确认
    if (showCancelAllConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelAllConfirm = false },
            title = { Text(stringResource(R.string.schedule_cancel_all)) },
            text = { Text(stringResource(R.string.schedule_cancel_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAllConfirm = false
                    onCancelAll()
                }) { Text(stringResource(R.string.chat_clear_history_yes), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun ScheduleSendDialog(
    onPickDelay: (Long) -> Unit,
    onPickAt: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    titleRes: Int = R.string.schedule_title,
    // 1.07：重复定时（间隔 + 1.21 可选次数 + 1.62 工作日）
    onPickRepeat: (Long, Int, Boolean) -> Unit = { _, _, _ -> },
    /** 1.43：重排时允许编辑文案（onTextEdited 非空时显示文本输入框）。 */
    initialText: String = "",
    onTextEdited: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    // 1.21：重复次数选择（0=不限）
    var repeatCountChoice by remember { mutableIntStateOf(0) }
    // 1.43：重排文案草稿
    var textDraft by remember(initialText) { mutableStateOf(initialText) }
    val options = com.maodouchat.util.ScheduledMessagePolicy.QUICK_DELAYS_MS.zip(
        listOf(
            R.string.schedule_delay_1m,
            R.string.schedule_delay_5m,
            R.string.schedule_delay_15m,
            R.string.schedule_delay_30m,
            R.string.schedule_delay_1h,
            R.string.schedule_delay_2h,
            R.string.schedule_delay_3h,
            R.string.schedule_delay_4h,
            R.string.schedule_delay_6h,
            R.string.schedule_delay_12h,
            R.string.schedule_delay_24h,
            R.string.schedule_delay_2d,
            R.string.schedule_delay_3d,
            R.string.schedule_delay_7d
        )
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 1.43：重排时编辑文案
                if (onTextEdited != null) {
                    OutlinedTextField(
                        value = textDraft,
                        onValueChange = {
                            textDraft = it.take(com.maodouchat.util.ScheduledMessagePolicy.MAX_TEXT_LENGTH)
                            onTextEdited(textDraft)
                        },
                        placeholder = { Text(stringResource(R.string.schedule_edit_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                options.forEach { (delay, label) ->
                    TextButton(
                        onClick = { onPickDelay(delay) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(label), color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(
                    onClick = {
                        openScheduleDateTimePicker(
                            context = context,
                            onPicked = onPickAt,
                            onTooSoon = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.schedule_custom_too_soon),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onTooLate = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.schedule_custom_too_late),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.schedule_custom), color = MaterialTheme.colorScheme.primary)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.schedule_repeat_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                // 1.07：重复定时（每日/每周）；1.21：应用所选次数；1.62：工作日重复
                TextButton(
                    onClick = { onPickRepeat(24L * 3600_000L, repeatCountChoice, false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_daily), color = MaterialTheme.colorScheme.primary) }
                TextButton(
                    onClick = { onPickRepeat(7L * 24 * 3600_000L, repeatCountChoice, false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_weekly), color = MaterialTheme.colorScheme.primary) }
                TextButton(
                    onClick = { onPickRepeat(24L * 3600_000L, repeatCountChoice, true) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_weekdays), color = MaterialTheme.colorScheme.primary) }
                Text(
                    stringResource(R.string.schedule_repeat_count_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(3, 7, 30, 0).forEach { count ->
                        TextButton(
                            onClick = { repeatCountChoice = count },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (count > 0) context.resources.getQuantityString(R.plurals.schedule_repeat_count, count, count)
                                else context.getString(R.string.schedule_repeat_count_unlimited),
                                color = if (repeatCountChoice == count) Primary else OnSurface,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 消息「稍后提醒」时间选择：复用定时发送档位文案，窗口 1 分钟 ~ 30 天。 */
@Composable
private fun MessageReminderTimeDialog(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_reminder_menu), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            val delays = com.maodouchat.util.MessageReminderPolicy.QUICK_DELAYS_MS
            val labels = listOf(
                R.string.schedule_delay_1m, R.string.schedule_delay_5m, R.string.schedule_delay_15m,
                R.string.schedule_delay_30m, R.string.schedule_delay_1h, R.string.schedule_delay_2h,
                R.string.schedule_delay_3h, R.string.schedule_delay_4h, R.string.schedule_delay_6h,
                R.string.schedule_delay_12h, R.string.schedule_delay_24h, R.string.schedule_delay_2d,
                R.string.schedule_delay_3d, R.string.schedule_delay_7d
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                delays.zip(labels).forEach { (delayMs, labelRes) ->
                    TextButton(
                        onClick = { onPick(delayMs) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * System date → time picker; enforces [ScheduledMessagePolicy] min/max window before [onPicked].
 */
private fun openScheduleDateTimePicker(
    context: Context,
    onPicked: (Long) -> Unit,
    onTooSoon: () -> Unit,
    onTooLate: () -> Unit
) {
    val now = java.util.Calendar.getInstance()
    val minCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS
    }
    val maxCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MAX_DELAY_MS
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            android.app.TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year)
                        set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    when {
                        picked < minCal.timeInMillis -> onTooSoon()
                        picked > maxCal.timeInMillis -> onTooLate()
                        else -> onPicked(picked)
                    }
                },
                minCal.get(java.util.Calendar.HOUR_OF_DAY),
                minCal.get(java.util.Calendar.MINUTE),
                true
            ).show()
        },
        minCal.get(java.util.Calendar.YEAR),
        minCal.get(java.util.Calendar.MONTH),
        minCal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply {
        datePicker.minDate = minCal.timeInMillis
        datePicker.maxDate = maxCal.timeInMillis
    }.show()
}

 private fun formatScheduleTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** 1.15：定时消息重复间隔 → 展示文案（0=一次性返回 null 不显示）。1.21/1.48：含次数与剩余次数。1.62：工作日重复。 */
private fun scheduleRepeatLabel(context: android.content.Context, intervalMs: Long, repeatCount: Int, occurrencesSent: Int, weekdaysOnly: Boolean): String? {
    val base = when {
        weekdaysOnly -> context.getString(R.string.schedule_repeat_weekdays)
        intervalMs == 24L * 3600_000L -> context.getString(R.string.schedule_repeat_daily)
        intervalMs == 7L * 24 * 3600_000L -> context.getString(R.string.schedule_repeat_weekly)
        else -> if (intervalMs <= 0L) return null else context.getString(R.string.schedule_repeat_badge)
    }
    return if (repeatCount > 0) {
        val remaining = (repeatCount - occurrencesSent).coerceAtLeast(0)
        "$base · ${context.resources.getQuantityString(R.plurals.schedule_repeat_remaining, remaining, remaining)}"
    } else base
}

/** 8.48：禁言剩余时长（分钟/小时/天）本地化显示。 */
private fun formatMuteRemaining(context: android.content.Context, remainingMs: Long): String {
    val minutes = remainingMs / 60_000L
    return when {
        minutes <= 0L -> context.getString(R.string.time_just_now)
        minutes < 60L -> context.getString(R.string.chat_mute_minutes, minutes)
        minutes < 24 * 60L -> context.getString(R.string.chat_mute_hours, minutes / 60L)
        else -> context.getString(R.string.chat_mute_days, minutes / (24 * 60L))
    }
}

@Composable
private fun SecretChatBanner(
    onManage: () -> Unit = {},
    sealedSenderReady: Boolean = false,
    sealedSenderExpiresInSec: Long = 0L,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.secret_chat_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.secret_chat_banner_limit),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.secret_chat_safety_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    tint = if (sealedSenderReady) Primary else TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (sealedSenderReady) {
                        stringResource(
                            R.string.secret_chat_sealed_chip,
                            (sealedSenderExpiresInSec / 3600L).coerceAtLeast(0L)
                        )
                    } else {
                        stringResource(R.string.secret_chat_sealed_chip_pending)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sealedSenderReady) Primary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}



@Composable
private fun LiveLocationSharingBanner(
    untilMs: Long?,
    onStop: () -> Unit
) {
    var remaining by remember(untilMs) {
        mutableLongStateOf(com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(untilMs))
    }
    LaunchedEffect(untilMs) {
        while (true) {
            remaining = com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(untilMs)
            if (remaining <= 0L) break
            kotlinx.coroutines.delay(1000L)
        }
    }
    val label = com.maodouchat.util.LiveLocationPolicy.formatRemaining(remaining)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.NearMe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.live_location_sharing_banner, label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.live_location_stop),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onStop)
        )
    }
}

@Composable
private fun DisappearingMessagesBanner(
    seconds: Int,
    onChange: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onChange)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.disappear_banner_on, disappearSecondsLabel(seconds)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.disappear_banner_change),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ChatQuietHoursDialog(
    current: com.maodouchat.notification.ChatQuietHoursStore.QuietWindow,
    onPick: (com.maodouchat.notification.ChatQuietHoursStore.QuietWindow) -> Unit,
    onDismiss: () -> Unit
) {
    // 快捷时段：预置的常用静音窗口（start, end, labelRes）
    val presets = listOf(
        Triple(22 * 60, 7 * 60, R.string.chat_quiet_hours_night),
        Triple(12 * 60, 14 * 60, R.string.chat_quiet_hours_lunch),
        Triple(23 * 60, 8 * 60, R.string.chat_quiet_hours_sleep),
        Triple(9 * 60, 18 * 60, R.string.chat_quiet_hours_workday)
    )
    val enabled = current.enabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quiet_hours_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (enabled) R.string.chat_quiet_hours_active_summary
                        else R.string.chat_quiet_hours_inactive_summary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                presets.forEach { (start, end, labelRes) ->
                    val selected = enabled && current.startMinute == start && current.endMinute == end
                    TextButton(
                        onClick = {
                            onPick(
                                com.maodouchat.notification.ChatQuietHoursStore.QuietWindow(
                                    enabled = true,
                                    startMinute = start,
                                    endMinute = end
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(stringResource(labelRes), color = if (selected) Primary else OnSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (enabled) {
                    TextButton(
                        onClick = {
                            onPick(com.maodouchat.notification.ChatQuietHoursStore.QuietWindow.OFF)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_quiet_hours_clear), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    stringResource(R.string.chat_quiet_hours_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
private fun DisappearingMessagesDialog(
    selectedSeconds: Int,
    isUpdating: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = DisappearingMessagePolicy.ALLOWED_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disappear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.disappear_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Text(
                    text = stringResource(R.string.disappear_limit_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { seconds ->
                    val selected = seconds == selectedSeconds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isUpdating) { onSelect(seconds) }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (seconds == 0) Icons.Outlined.Schedule else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = if (selected) Primary else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = disappearSecondsLabel(seconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Primary else OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun disappearSecondsLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.disappear_off)
    30 -> stringResource(R.string.disappear_30s)
    60 -> stringResource(R.string.disappear_1m)
    2 * 60 -> stringResource(R.string.disappear_2m)
    5 * 60 -> stringResource(R.string.disappear_5m)
    15 * 60 -> stringResource(R.string.disappear_15m)
    60 * 60 -> stringResource(R.string.disappear_1h)
    2 * 60 * 60 -> stringResource(R.string.disappear_2h)
    4 * 60 * 60 -> stringResource(R.string.disappear_4h)
    8 * 60 * 60 -> stringResource(R.string.disappear_8h)
    12 * 60 * 60 -> stringResource(R.string.disappear_12h)
    24 * 60 * 60 -> stringResource(R.string.disappear_24h)
    7 * 24 * 60 * 60 -> stringResource(R.string.disappear_7d)
    30 * 24 * 60 * 60 -> stringResource(R.string.disappear_30d)
    else -> stringResource(R.string.disappear_off)
}

@Composable
private fun PinnedMessagesBanner(
    pins: List<com.maodouchat.network.PinnedMessageDto>,
    messages: List<Message>,
    canManage: Boolean,
    onOpen: (String) -> Unit,
    onUnpin: (String) -> Unit,
    // 1.49：显示置顶者（解析 userId → 显示名）
    resolvePinnerName: (String) -> String = { it },
    // 1.53：点击置顶者名称 → 打开其资料
    onPinnerClick: ((String) -> Unit)? = null
) {
    if (pins.isEmpty()) return
    var pinIndex by remember(pins.map { it.messageId }.joinToString()) { mutableIntStateOf(0) }
    val safeIndex = pinIndex.coerceIn(0, pins.lastIndex)
    val current = pins[safeIndex]
    val message = messages.firstOrNull { it.id == current.messageId }
    val preview = pinnedPreviewText(message)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable {
                onOpen(current.messageId)
                if (pins.size > 1) {
                    pinIndex = (safeIndex + 1) % pins.size
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (pins.size > 1) {
                    stringResource(R.string.chat_pinned_banner_title) +
                        " · ${safeIndex + 1}/${pins.size}"
                } else {
                    stringResource(R.string.chat_pinned_banner_title)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 1.49：置顶者（本地解析显示名，服务端不可见时回退 userId）；1.56：附带置顶时间；1.58：pinnedAt<=0 回退无时间格式
            if (current.pinnedBy.isNotBlank()) {
                Text(
                    text = if (current.pinnedAt > 0) {
                        stringResource(
                            R.string.chat_pinned_by_time,
                            resolvePinnerName(current.pinnedBy),
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                current.pinnedAt,
                                System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        )
                    } else {
                        stringResource(R.string.chat_pinned_by, resolvePinnerName(current.pinnedBy))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 1.53：点击置顶者名称打开其资料
                    modifier = if (onPinnerClick != null) {
                        Modifier.clickable { onPinnerClick(current.pinnedBy) }
                    } else {
                        Modifier
                    }
                )
            }
        }
        if (canManage) {
            TextButton(onClick = { onUnpin(current.messageId) }) {
                Text(stringResource(R.string.chat_message_unpin), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GroupAnnouncementBanner(
    announcement: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.group_announcement_banner_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = announcement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = LocalChatPalette.current.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun pinnedPreviewText(message: Message?): String {
    if (message == null) return stringResource(R.string.chat_pinned_preview_generic)
    return when (MessagePinPolicy.previewKind(message.type)) {
        MessagePinPolicy.PreviewKind.TEXT -> {
            val text = MessagePinPolicy.textPreview(message.content)
            if (text.isBlank()) stringResource(R.string.chat_pinned_preview_generic) else text
        }
        MessagePinPolicy.PreviewKind.IMAGE -> stringResource(R.string.chat_pinned_preview_image)
        MessagePinPolicy.PreviewKind.VOICE -> stringResource(R.string.chat_pinned_preview_voice)
        MessagePinPolicy.PreviewKind.VIDEO -> stringResource(R.string.chat_pinned_preview_video)
        MessagePinPolicy.PreviewKind.FILE -> stringResource(R.string.chat_pinned_preview_file)
        MessagePinPolicy.PreviewKind.LOCATION -> stringResource(R.string.chat_pinned_preview_location)
        MessagePinPolicy.PreviewKind.STICKER -> stringResource(R.string.chat_pinned_preview_sticker)
        MessagePinPolicy.PreviewKind.GENERIC -> stringResource(R.string.chat_pinned_preview_generic)
    }
}

/**
 * Identity / safety warning strip above the timeline.
 * CHANGED (sticky) uses stronger fill so it cannot read as a soft tip.
 */
@Composable
private fun SecurityWarningBanner(
    warning: String,
    sticky: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(UnreadRed.copy(alpha = if (sticky) 0.16f else 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = LocalChatPalette.current.unreadRed, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            warning,
            color = LocalChatPalette.current.unreadRed,
            style = if (sticky) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReactionPickerRow(onPick: (String) -> Unit) {
    val reactions = remember {
        listOf(
            "\uD83D\uDC4D", // 👍
            "\uD83D\uDC4E", // 👎
            "\u2764\uFE0F", // ❤️
            "\uD83D\uDE02", // 😂
            "\uD83D\uDE0D", // 😍
            "\uD83D\uDE2E", // 😮
            "\uD83D\uDE22", // 😢
            "\uD83D\uDE21", // 😠
            "\uD83D\uDD25", // 🔥
            "\uD83C\uDF89", // 🎉
            "\uD83D\uDC4F", // 👏
            "\uD83D\uDE4F", // 🙏
            "\uD83D\uDC40", // 👀
            "\uD83E\uDD14", // 🤔
            "\uD83D\uDCAF", // 💯
            "\u2705",      // ✅
            "\uD83D\uDE80", // 🚀
            "\u2B50",      // ⭐
            "\uD83C\uDF1F", // 🌟
            "\uD83E\uDD73", // 🥳
            "\uD83E\uDD70", // 🥰
            "\uD83D\uDCAA", // 💪
            "\uD83E\uDD1D", // 🤝
            "\uD83D\uDE0A", // 😊
            "\uD83D\uDE4C", // 🙌
            "\uD83E\uDD29", // 🤩
            "\uD83E\uDD72", // 🥲
            "\uD83E\uDD23", // 🤣
            "\uD83D\uDC4C", // 👌
            "\uD83E\uDEF6"  // 🫶
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp)
    ) {
        reactions.forEach { emoji ->
            TextButton(
                onClick = { onPick(emoji) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(emoji, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun UnreadSummaryBanner(
    summary: String?,
    messageCount: Int,
    isLoading: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    // 1.194：复制未读摘要
    onCopy: () -> Unit = {}
) {
    val pulse by rememberMotionPulse(
        initialValue = 0.6f,
        targetValue = 1f,
        durationMillis = 1_200,
        label = "unreadSummaryPulse"
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Primary.copy(alpha = 0.10f),
                        Primary.copy(alpha = 0.04f)
                    )
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape)
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (messageCount > 0) pluralStringResource(R.plurals.chat_unread_summary_count, messageCount, messageCount) else stringResource(R.string.chat_unread_summary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = summary ?: stringResource(R.string.chat_generating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (summary != null) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.chat_view)) }
            // 1.194：复制未读摘要
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.chat_copy), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close_unread_summary), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun SafetyCodeDialog(
    contactName: String,
    contactId: String,
    trustState: SignalProtocol.IdentityTrustState,
    isGroup: Boolean,
    safetyCode: String?,
    warning: String?,
    currentUserId: String,
    currentDeviceId: Int,
    currentIdentityFingerprint: String,
    contactIdentityFingerprint: String?,
    deviceSafetyWarning: String?,
    isLoadingDeviceSafety: Boolean,
    deviceSafetyStates: List<SignalProtocol.DeviceSafetyState>,
    onDismiss: () -> Unit,
    onVerifyDevice: (Int) -> Unit
) {
    val context = LocalContext.current
    val sticky = com.maodouchat.crypto.SafetyCodePolicy.isStickyIdentityWarning(trustState)
    val displayCode = com.maodouchat.crypto.SafetyCodePolicy.formatForDisplay(safetyCode)
    AlertDialog(
        // CHANGED identity: back/outside dismiss still allowed, but primary path forces verify dialog content.
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_safety_title, contactName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.unreadRed) }
                if (sticky) {
                    Text(
                        stringResource(R.string.chat_safety_changed_sticky_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.unreadRed
                    )
                }
                deviceSafetyWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.unreadRed) }
                if (isLoadingDeviceSafety) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_safety_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                val devices = deviceSafetyStates
                var deviceSearch by remember { mutableStateOf("") }
                val filteredDevices = remember(devices, deviceSearch) {
                    val q = deviceSearch.trim()
                    if (q.isEmpty()) {
                        devices
                    } else {
                        devices.filter { device ->
                            device.deviceId.toString().contains(q, ignoreCase = true) ||
                                device.trustState.name.contains(q, ignoreCase = true) ||
                                device.safetyCode.orEmpty().contains(q, ignoreCase = true) ||
                                device.identityKey.orEmpty().contains(q, ignoreCase = true)
                        }
                    }
                }
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.chat_safety_status, if (isGroup) stringResource(R.string.chat_group_sender_key_enabled) else trustState.toLabel()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        displayCode ?: stringResource(R.string.chat_safety_not_ready),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    displayCode?.let { code ->
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        context.getString(R.string.chat_safety_code),
                                        com.maodouchat.crypto.SafetyCodePolicy.formatForCopy(code).orEmpty()
                                    )
                                )
                                Toast.makeText(context, context.getString(R.string.chat_safety_code_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) { Text(stringResource(R.string.chat_safety_copy_code)) }
                        if (!contactIdentityFingerprint.isNullOrBlank() && currentIdentityFingerprint.isNotBlank()) {
                            SafetyQrCard(
                                ownerUserId = currentUserId,
                                ownerDeviceId = currentDeviceId,
                                peerUserId = contactId,
                                peerDeviceId = 1,
                                ownerIdentityFingerprint = currentIdentityFingerprint,
                                peerIdentityFingerprint = contactIdentityFingerprint
                            )
                        }
                    }
                } else {
                    if (devices.size >= 4) {
                        OutlinedTextField(
                            value = deviceSearch,
                            onValueChange = { deviceSearch = it.take(64) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_safety_devices_search_hint)) },
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
                    if (filteredDevices.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_safety_devices_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textHint
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            filteredDevices.forEach { device ->
                                DeviceSafetyRow(
                                    device = device,
                                    ownerUserId = currentUserId,
                                    ownerDeviceId = currentDeviceId,
                                    ownerIdentityFingerprint = currentIdentityFingerprint,
                                    peerUserId = contactId,
                                    onVerify = { onVerifyDevice(device.deviceId) }
                                )
                            }
                        }
                    }
                }
                Text(stringResource(R.string.chat_safety_scan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text(
                    stringResource(R.string.chat_safety_format_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (sticky) R.string.chat_safety_review_later else R.string.common_done
                    )
                )
            }
        },
        dismissButton = {
            if (!sticky) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
            }
        }
    )
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
private fun DeviceSafetyRow(
    device: SignalProtocol.DeviceSafetyState,
    ownerUserId: String,
    ownerDeviceId: Int,
    ownerIdentityFingerprint: String,
    peerUserId: String,
    onVerify: () -> Unit
) {
    val context = LocalContext.current
    val displayCode = com.maodouchat.crypto.SafetyCodePolicy.formatForDisplay(device.safetyCode)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (device.isCurrent) R.string.chat_device_current else R.string.chat_device_number, device.deviceId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(device.trustState.toLabel(), style = MaterialTheme.typography.labelMedium, color = if (device.trustState == SignalProtocol.IdentityTrustState.CHANGED) UnreadRed else Primary)
        }
        Text(
            displayCode ?: stringResource(R.string.chat_device_session_missing),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (displayCode == null) {
            Text(stringResource(R.string.chat_identity_fingerprint, device.identityKey.take(16)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        } else {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            context.getString(R.string.chat_safety_code),
                            com.maodouchat.crypto.SafetyCodePolicy.formatForCopy(displayCode).orEmpty()
                        )
                    )
                    Toast.makeText(context, context.getString(R.string.chat_safety_code_copied), Toast.LENGTH_SHORT).show()
                }
            ) { Text(stringResource(R.string.chat_safety_copy_code)) }
            SafetyQrCard(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = device.deviceId,
                ownerIdentityFingerprint = ownerIdentityFingerprint,
                peerIdentityFingerprint = device.identityFingerprint
            )
        }
        Button(onClick = onVerify, enabled = displayCode != null && device.trustState != SignalProtocol.IdentityTrustState.VERIFIED) {
            Text(stringResource(if (device.trustState == SignalProtocol.IdentityTrustState.VERIFIED) R.string.chat_verified else R.string.chat_mark_verified))
        }
    }
}

@Composable
private fun SafetyQrCard(
    ownerUserId: String,
    ownerDeviceId: Int,
    peerUserId: String,
    peerDeviceId: Int,
    ownerIdentityFingerprint: String,
    peerIdentityFingerprint: String
) {
    val bitmap = remember(ownerUserId, ownerDeviceId, peerUserId, peerDeviceId, ownerIdentityFingerprint, peerIdentityFingerprint) {
        QrCodeGenerator.generateBitmap(
            QrCodeGenerator.encodeSafetyQrPayload(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = peerDeviceId,
                ownerIdentityFingerprint = ownerIdentityFingerprint,
                peerIdentityFingerprint = peerIdentityFingerprint
            ),
            320
        )
    }
    if (bitmap != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_identity_qr),
                    modifier = Modifier.size(96.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.chat_scan_device_hint, peerDeviceId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SignalProtocol.IdentityTrustState.toLabel(): String = stringResource(when (this) {
    SignalProtocol.IdentityTrustState.UNKNOWN -> R.string.chat_trust_unknown
    SignalProtocol.IdentityTrustState.TRUSTED -> R.string.chat_trust_first
    SignalProtocol.IdentityTrustState.VERIFIED -> R.string.chat_verified
    SignalProtocol.IdentityTrustState.CHANGED -> R.string.chat_trust_changed
})

@Composable
private fun AiImageAnalysisModeDialog(
    onSelect: (AiImageAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = AiImageAnalysisMode.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_image_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                modes.forEach { mode ->
                    val icon = when (mode) {
                        AiImageAnalysisMode.DESCRIBE -> Icons.Outlined.Image
                        AiImageAnalysisMode.OCR -> Icons.Outlined.Search
                        AiImageAnalysisMode.SAFETY -> Icons.Outlined.Security
                    }
                    val description = stringResource(when (mode) {
                        AiImageAnalysisMode.DESCRIBE -> R.string.chat_ai_image_mode_describe_description
                        AiImageAnalysisMode.OCR -> R.string.chat_ai_image_mode_ocr_description
                        AiImageAnalysisMode.SAFETY -> R.string.chat_ai_image_mode_safety_description
                    })
                    TextButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.localizedLabel(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_image_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun AiImageAnalysisResultDialog(
    result: String,
    mode: AiImageAnalysisMode,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_image_result_title))
                }
                Text(mode.localizedLabel(), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_image_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_copy)) } }
    )
}

@Composable
private fun AiFileAnalysisModeDialog(
    fileName: String,
    onSelect: (AiFileAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_file_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fileName.isNotBlank()) {
                    Text(fileName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                }
                AiFileAnalysisMode.entries.forEach { mode ->
                    val icon = if (mode == AiFileAnalysisMode.SUMMARIZE) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.HelpOutline
                    val description = stringResource(if (mode == AiFileAnalysisMode.SUMMARIZE) R.string.chat_ai_file_mode_summarize_description else R.string.chat_ai_file_mode_question_description)
                    TextButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.localizedLabel(), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                            Text(description, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_file_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun AiFileQuestionDialog(
    fileName: String,
    question: String,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_ai_file_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fileName.isNotBlank()) {
                    Text(fileName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text(stringResource(R.string.chat_ai_file_question_placeholder)) },
                    supportingText = { Text("${question.length}/500") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = question.isNotBlank()) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun AiFileAnalysisResultDialog(
    result: String,
    fileName: String,
    mode: AiFileAnalysisMode,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_file_result_title))
                }
                Text(
                    listOf(fileName.takeIf(String::isNotBlank), mode.localizedLabel()).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(stringResource(R.string.chat_ai_file_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_copy)) } }
    )
}

@Composable
private fun AiSummaryScopeDialog(
    searchResultCount: Int,
    onDismiss: () -> Unit,
    onSelect: (AiSummaryScope, String) -> Unit
) {
    var selectedStyle by rememberSaveable { mutableStateOf("brief") }
    val scopes = listOf(
        AiSummaryScope.RECENT,
        AiSummaryScope.TODAY,
        AiSummaryScope.SEVEN_DAYS,
        AiSummaryScope.THIRTY_DAYS,
        AiSummaryScope.SEARCH_RESULTS
    )
    val styleOptions = listOf(
        "brief" to R.string.chat_ai_summary_style_brief,
        "detailed" to R.string.chat_ai_summary_style_detailed,
        "decisions" to R.string.chat_ai_summary_style_decisions,
        "tasks" to R.string.chat_ai_summary_style_tasks,
        "timeline" to R.string.chat_ai_summary_style_timeline,
        "risks" to R.string.chat_ai_summary_style_risks
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_summary_scope_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.chat_ai_summary_style_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styleOptions.forEach { (styleId, labelRes) ->
                        FilterChip(
                            selected = selectedStyle == styleId,
                            onClick = { selectedStyle = styleId },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                scopes.forEach { scope ->
                    val enabled = scope != AiSummaryScope.SEARCH_RESULTS || searchResultCount > 0
                    val icon = when (scope) {
                        AiSummaryScope.RECENT -> Icons.Outlined.History
                        AiSummaryScope.TODAY -> Icons.Outlined.Today
                        AiSummaryScope.SEVEN_DAYS -> Icons.Outlined.DateRange
                        AiSummaryScope.THIRTY_DAYS -> Icons.Outlined.DateRange
                        AiSummaryScope.SEARCH_RESULTS -> Icons.Outlined.Search
                        AiSummaryScope.UNREAD -> Icons.Outlined.History
                    }
                    val description = when (scope) {
                        AiSummaryScope.RECENT -> stringResource(R.string.chat_ai_summary_scope_recent_description)
                        AiSummaryScope.TODAY -> stringResource(R.string.chat_ai_summary_scope_today_description)
                        AiSummaryScope.SEVEN_DAYS -> stringResource(R.string.chat_ai_summary_scope_week_description)
                        AiSummaryScope.THIRTY_DAYS -> stringResource(R.string.chat_ai_summary_scope_month_description)
                        AiSummaryScope.SEARCH_RESULTS -> if (searchResultCount > 0) {
                            stringResource(R.string.chat_ai_summary_scope_search_count, searchResultCount)
                        } else {
                            stringResource(R.string.chat_ai_summary_scope_search_empty)
                        }
                        AiSummaryScope.UNREAD -> ""
                    }
                    TextButton(
                        enabled = enabled,
                        onClick = { onSelect(scope, selectedStyle) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (enabled) Primary else TextHint,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                scope.localizedLabel(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (enabled) OnSurface else TextHint
                            )
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_summary_scope_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun AiSummaryHistoryDialog(
    summaries: List<AiSummaryHistoryUi>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var historySearch by rememberSaveable { mutableStateOf("") }
    val filteredSummaries = remember(summaries, historySearch) {
        val q = historySearch.trim()
        if (q.isEmpty()) {
            summaries
        } else {
            summaries.filter { item ->
                item.summary.contains(q, ignoreCase = true) ||
                    item.scope.name.contains(q, ignoreCase = true) ||
                    item.cacheKey.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_summary_history_title))
            }
        },
        text = {
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
                summaries.isEmpty() -> Text(
                    stringResource(R.string.chat_ai_summary_history_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textHint,
                    textAlign = TextAlign.Center
                )
                else -> Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summaries.size >= 4) {
                        OutlinedTextField(
                            value = historySearch,
                            onValueChange = { historySearch = it.take(120) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_ai_summary_history_search_hint)) },
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
                    if (filteredSummaries.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_ai_summary_history_search_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textHint,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        filteredSummaries.forEachIndexed { index, item ->
                            TextButton(
                                onClick = { onSelect(item.cacheKey) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.scope.localizedLabel(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            dateFormat.format(Date(item.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textHint
                                        )
                                    }
                                    Text(
                                        pluralStringResource(R.plurals.chat_ai_summary_messages, item.messageCount, item.messageCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary
                                    )
                                    Text(
                                        item.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (index != filteredSummaries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
private fun AiConsentDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_ai_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.chat_ai_consent_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.chat_ai_consent_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.chat_ai_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun AiSummaryDialog(
    summary: String,
    scope: AiSummaryScope,
    messageCount: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_summary_title))
                }
                if (messageCount > 0) {
                    Text(
                        "${scope.localizedLabel()} · ${pluralStringResource(R.plurals.chat_ai_summary_messages, messageCount, messageCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.chat_ai_summary_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

// B4：会话画像对话框（本地统计 + 可选叙事摘要）
@Composable
private fun ConversationProfileDialog(
    loading: Boolean,
    profile: com.maodouchat.ai.AiConversationProfile.ConversationProfile?,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.317：复制会话画像
    onCopyProfile: (String) -> Unit = {}
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_conversation_profile_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.chat_ai_conversation_profile_loading), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.chat_ai_conversation_profile_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                profile == null -> Text(stringResource(R.string.chat_ai_profile_no_data), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.chat_ai_conversation_profile_stats,
                            profile.local.messageCount,
                            profile.local.activeDays
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    Text(
                        stringResource(
                            R.string.chat_ai_profile_time_distribution,
                            profile.local.morning, profile.local.afternoon,
                            profile.local.evening, profile.local.night
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    if (profile.local.topTerms.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.chat_ai_profile_top_terms,
                                profile.local.topTerms.take(8).joinToString(" · ")
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Text(
                        profile.narrative?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_ai_profile_no_narrative),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.317：复制会话画像（转发/归档）
        dismissButton = {
            if (profile != null && !failed && !loading) {
                TextButton(onClick = { onCopyProfile(profileText(context, profile)) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 1.317：将会话画像序列化为纯文本（复制用）。 */
private fun profileText(context: android.content.Context, profile: com.maodouchat.ai.AiConversationProfile.ConversationProfile): String = buildString {
    val ctx = context
    append(
        ctx.getString(
            R.string.chat_ai_conversation_profile_stats,
            profile.local.messageCount,
            profile.local.activeDays
        )
    )
    append("\n")
    append(
        ctx.getString(
            R.string.chat_ai_profile_time_distribution,
            profile.local.morning, profile.local.afternoon,
            profile.local.evening, profile.local.night
        )
    )
    if (profile.local.topTerms.isNotEmpty()) {
        append("\n")
        append(
            ctx.getString(
                R.string.chat_ai_profile_top_terms,
                profile.local.topTerms.take(8).joinToString(" · ")
            )
        )
    }
    profile.narrative?.takeIf { it.isNotBlank() }?.let {
        append("\n\n").append(it)
    }
}

// B4：本周周报对话框（生成并缓存到本地）
@Composable
private fun WeeklyReportDialog(
    loading: Boolean,
    report: com.maodouchat.ai.AiWeeklyReport.WeeklyReport?,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.310：复制周报全文
    onCopyReport: (String) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_weekly_report_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.chat_ai_weekly_report_loading), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.chat_ai_weekly_report_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                report == null -> Text(stringResource(R.string.chat_ai_weekly_report_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(report.report, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.chat_ai_summary_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.310：复制周报（转发/归档）
        dismissButton = {
            if (report != null && !failed && !loading) {
                TextButton(onClick = { onCopyReport(report.report) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 8.47：消息分类统计对话框（纯本地词典分类，结果仅本机）。 */
@Composable
private fun MessageClassifyDialog(
    loading: Boolean,
    categories: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.349：复制分类结果
    onCopyClassify: (String) -> Unit = {}
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_enhance_classify_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.ai_enhance_classify_hint), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.ai_enhance_classify_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                categories.isEmpty() -> Text(stringResource(R.string.ai_enhance_classify_empty), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.ai_enhance_classify_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                    categories.forEach { row ->
                        val label = when (row.category) {
                            "notice" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_notice)
                            "todo" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_todo)
                            "finance" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_finance)
                            "study" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_study)
                            "tech" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_tech)
                            "social" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_social)
                            else -> stringResource(com.maodouchat.R.string.ai_enhance_classify_other)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.ai_enhance_classify_count, row.count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (row.count.toFloat() / (categories.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        stringResource(R.string.ai_enhance_classify_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.349：复制分类结果（dismissButton 位，仅分类成功时显示）
        dismissButton = {
            if (categories.isNotEmpty() && !failed && !loading) {
                val classifyCopyText = classifyText(context, categories)
                TextButton(onClick = { onCopyClassify(classifyCopyText) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 1.349：将消息分类结果序列化为纯文本（复制用）。 */
@Composable
private fun classifyText(context: android.content.Context, categories: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>): String = buildString {
    val ctx = context
    categories.forEach { row ->
        val label = when (row.category) {
            "notice" -> ctx.getString(R.string.ai_enhance_classify_notice)
            "todo" -> ctx.getString(R.string.ai_enhance_classify_todo)
            "finance" -> ctx.getString(R.string.ai_enhance_classify_finance)
            "study" -> ctx.getString(R.string.ai_enhance_classify_study)
            "tech" -> ctx.getString(R.string.ai_enhance_classify_tech)
            "social" -> ctx.getString(R.string.ai_enhance_classify_social)
            else -> ctx.getString(R.string.ai_enhance_classify_other)
        }
        append(label).append(": ").append(ctx.getString(R.string.ai_enhance_classify_count, row.count)).append('\n')
    }
    append(ctx.getString(R.string.ai_enhance_classify_disclaimer))
}

@Composable
private fun GroupAiAssistantDialog(
    question: String,
    answer: String,
    tasks: List<AiGroupTask>,
    isSavingTasks: Boolean,
    tasksSaved: Boolean,
    taskSaveError: String?,
    shareEnabled: Boolean = true,
    onCopy: () -> Unit,
    onSaveTasks: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val motion = LocalMotionSettings.current
    // 私有预览模式指示
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_group_ai_title))
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_group_ai_private_preview),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.chat_group_ai_question, question), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary)
                // 回答带渐入
                AnimatedContent(
                    targetState = answer,
                    transitionSpec = {
                        (fadeIn(tween(motion.duration(MotionTokens.Emphasized))) +
                            expandVertically(tween(motion.duration(MotionTokens.Emphasized))))
                            .togetherWith(
                                fadeOut(tween(motion.duration(MotionTokens.Standard))) +
                                    shrinkVertically(tween(motion.duration(MotionTokens.Standard)))
                            )
                    },
                    label = "groupAiAnswer"
                ) { animatedAnswer ->
                    Text(animatedAnswer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (tasks.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                    Text(
                        pluralStringResource(R.plurals.ai_tasks_preview_count, tasks.size, tasks.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    tasks.forEachIndexed { index, task ->
                        val animateInitialEntry = MotionPolicy.shouldAnimateInitialListEntry(index, motion)
                        var visible by remember(task.title, animateInitialEntry) { mutableStateOf(!animateInitialEntry) }
                        LaunchedEffect(task.title, animateInitialEntry) {
                            if (animateInitialEntry) kotlinx.coroutines.delay(
                                MotionPolicy.initialListEntryDelay(index, motion).toLong()
                            )
                            visible = true
                        }
                        AnimatedVisibility(
                            visible = visible,
                            enter = if (animateInitialEntry) {
                                fadeIn(tween(motion.duration(MotionTokens.Emphasized))) +
                                    expandVertically(tween(motion.duration(MotionTokens.Emphasized)))
                            } else {
                                androidx.compose.animation.EnterTransition.None
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(task.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    task.owner?.takeIf(String::isNotBlank)?.let { owner ->
                                        Text(
                                            stringResource(R.string.ai_tasks_owner, owner),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textSecondary
                                        )
                                    }
                                    val due = task.dueText?.takeIf(String::isNotBlank)
                                        ?: task.dueAt?.let {
                                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                                        }
                                    due?.let {
                                        Text(
                                            stringResource(R.string.ai_tasks_due, it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    taskSaveError?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = onSaveTasks,
                        enabled = !isSavingTasks && !tasksSaved,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when {
                            isSavingTasks -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            tasksSaved -> {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.ai_tasks_saved))
                            }
                            else -> Text(stringResource(R.string.ai_tasks_save))
                        }
                    }
                    Text(
                        stringResource(R.string.ai_tasks_local_private),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
                Text(stringResource(R.string.chat_group_ai_private_result), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_group_ai_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_group_ai_copy)) }
            }
        },
        confirmButton = {
            Button(onClick = onShare, enabled = shareEnabled) {
                Text(stringResource(R.string.chat_group_ai_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

/**
 * 录音指示器：脉冲点 + 实时时长 + 振幅波形。
 */
@Composable
private fun RecordingIndicator(
    elapsedMs: Long = 0L,
    waveform: List<Float> = emptyList(),
    amplitude: Float = 0f,
) {
    val alpha by rememberMotionPulse(
        initialValue = 0.3f,
        targetValue = 1.0f,
        durationMillis = 800,
        label = "recordingPulse"
    )
    val durationLabel = com.maodouchat.util.VoiceRecorder.formatDuration(elapsedMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UnreadRed.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.background(UnreadRed, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.chat_recording), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(durationLabel, color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        RecordingWaveformRow(waveform = waveform, liveAmplitude = amplitude)
    }
}

@Composable
private fun RecordingWaveformRow(
    waveform: List<Float>,
    liveAmplitude: Float,
    barCount: Int = 32,
) {
    val bars = remember(waveform, liveAmplitude, barCount) {
        val base = if (waveform.isEmpty()) {
            List(barCount) { 0.08f }
        } else {
            val src = waveform
            List(barCount) { i ->
                val idx = ((i.toFloat() / (barCount - 1).coerceAtLeast(1)) * (src.size - 1).coerceAtLeast(0)).toInt()
                    .coerceIn(0, src.lastIndex.coerceAtLeast(0))
                src.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            }
        }
        // 末尾条跟瞬时振幅，增强“正在说话”感
        base.mapIndexed { i, v ->
            if (i >= barCount - 3) maxOf(v, liveAmplitude * (0.7f + 0.1f * (i - (barCount - 3)))) else v
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { h ->
            val heightFrac = (0.12f + h * 0.88f).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height((36 * heightFrac).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(UnreadRed.copy(alpha = 0.35f + h * 0.55f))
            )
        }
    }
}

@Composable
private fun VoicePreviewBar(
    durationMs: Long,
    onPlay: () -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    val playerState by com.maodouchat.util.VoicePlayer.state.collectAsState()
    val isPreviewPlaying =
        playerState.messageId == com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.VOICE_PREVIEW_MESSAGE_ID &&
            playerState.isPlaying
    val durationLabel = com.maodouchat.util.VoiceRecorder.formatDuration(durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_voice_preview_discard),
                tint = LocalChatPalette.current.textHint
            )
        }
        IconButton(
            onClick = {
                if (isPreviewPlaying) {
                    com.maodouchat.util.VoicePlayer.stop()
                } else {
                    onPlay()
                }
            },
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                imageVector = if (isPreviewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPreviewPlaying) R.string.chat_voice_preview_stop else R.string.chat_voice_preview_play
                ),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_voice_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(durationLabel, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textSecondary)
            if (isPreviewPlaying) {
                LinearProgressIndicator(
                    progress = { playerState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Primary.copy(alpha = 0.15f),
                )
            }
        }
        TextButton(onClick = onSend) {
            Text(stringResource(R.string.chat_voice_preview_send), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onScheduleSend: () -> Unit = {},
    onSendImage: () -> Unit,
    onSendViewOnceImage: () -> Unit = {},
    onSendSpoilerImage: () -> Unit = {},
    onPasteFromClipboard: () -> Unit = {},
    onSendVideo: () -> Unit,
    onSendFile: () -> Unit,
    onSendGif: () -> Unit,
    onSendSticker: (String) -> Unit,
    onSendLocation: () -> Unit,
    onSendLiveLocation: () -> Unit = {},
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    isRecording: Boolean,
    isAiWorking: Boolean,
    isAiDraftStreaming: Boolean,
    aiSuggestions: List<String>,
    isAiReplyStreaming: Boolean,
    aiReplyStreamErrorCode: String?,
    onAiRewrite: (mode: String, targetLanguage: String?) -> Unit,
    onAiSuggestReplies: (tone: String) -> Unit,
    onAiSummarize: () -> Unit,
    onOpenAiSummaryHistory: () -> Unit,
    onOpenAiTasks: () -> Unit,
    onOpenConversationProfile: () -> Unit = {},
    onOpenWeeklyReport: () -> Unit = {},
    onEmotionReply: () -> Unit = {},
    onOpenMessageClassify: () -> Unit = {},
    onAiSuggestionClick: (String) -> Unit,
    onClearAiSuggestions: () -> Unit,
    onCancelAiReplyStream: () -> Unit,
    onRetryAiReplyStream: () -> Unit,
    aiEnabled: Boolean,
    isUpdatingAiSetting: Boolean,
    onAiEnabledChange: (Boolean) -> Unit,
    isGroupChat: Boolean,
    isChannelChat: Boolean = false,
    onSendNudge: () -> Unit = {},
    mentionParticipants: List<com.maodouchat.data.model.User> = emptyList(),
    currentUserId: String = "",
    /** 1.37：仅群主/管理员可选「@所有人」候选。 */
    canMentionEveryone: Boolean = true,
    attachmentsEnabled: Boolean = true,
    disabledAttachmentMessage: String? = null,
    silentSend: Boolean = false,
    onToggleSilentSend: () -> Unit = {},
    isSending: Boolean = false,
    /** 只读模式（广播频道订阅者）：隐藏输入区，显示单向广播提示。 */
    readOnly: Boolean = false,
    readOnlyMessage: String? = null,
    /** 密聊会话：禁用会话画像/周报等本地 AI 聚合（结果不应落可搜索缓存）。 */
    isSecretChat: Boolean = false,
    /** 1.11：发送名片——转发目标列表与回调（ChatInputBar 无 viewModel 引用）。 */
    contactCardTargets: List<com.maodouchat.data.model.Chat> = emptyList(),
    onLoadForwardTargets: () -> Unit = {},
    onSendContactCard: (userId: String, displayName: String) -> Unit = { _, _ -> },
    botCommands: List<com.maodouchat.bot.BotCommandPolicy.BotCommandItem> = emptyList()
) {
    // 设备旋转时保留附件菜单展开状态
    var showAttachMenu by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showExpressionPanel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var expressionMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("EMOJI") }
    var showAiMenu by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showDraftTranslationLanguages by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showQuickPhrases by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showContactCardPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val attachmentDisabledText = disabledAttachmentMessage ?: stringResource(R.string.chat_attachment_unsupported)
    val mentionQuery = remember(value, isGroupChat) {
        if (RuntimeFlags.isEnabled(context, RuntimeFlags.MENTIONS) &&
            MentionPolicy.shouldShowPicker(value, isGroupChat)
        ) {
            MentionPolicy.activeQuery(value)
        } else null
    }
    val mentionCandidates = remember(mentionQuery, mentionParticipants, currentUserId) {
        val q = mentionQuery ?: return@remember emptyList()
        MentionPolicy.filterCandidates(
            participants = mentionParticipants,
            currentUserId = currentUserId,
            filter = q.filter,
            includeEveryone = canMentionEveryone,
        )
    }
    val everyoneLabel = stringResource(R.string.chat_mention_everyone)
    BackHandler(enabled = showAttachMenu || showExpressionPanel || showAiMenu || showQuickPhrases) {
        when {
            showAiMenu -> showAiMenu = false
            showQuickPhrases -> showQuickPhrases = false
            showAttachMenu -> showAttachMenu = false
            showExpressionPanel -> showExpressionPanel = false
        }
    }

    if (showDraftTranslationLanguages) {
        TranslationLanguageDialog(
            onDismiss = { showDraftTranslationLanguages = false },
            onSelect = { language ->
                showDraftTranslationLanguages = false
                onAiRewrite("translate", language)
            }
        )
    }

    // 9.4xx：外层 Column 已 imePadding()，这里再去 navigationBarsPadding 会在键盘弹出时叠出
    // 双份空隙（ime inset 已含导航栏高度）；改为仅保留背景色，insets 由外层统一处理
    // ChatInputBar 画在 layerBackdrop 采样层内部。对同一层 drawBackdrop 会让
    // RenderThread prepareTree 无限递归（SIGSEGV stack overflow，打开任意会话即崩）。
    // 输入栏只用不透明表面；顶栏仍可安全采样，因为它在 Scaffold.topBar 外层。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (readOnly) {
            // 广播频道订阅者：单向只读，仅显示提示条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Outlined.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    readOnlyMessage ?: stringResource(R.string.chat_channel_read_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            return@Column
        }
        AnimatedVisibility(
            visible = (isAiWorking && !isAiDraftStreaming) || aiSuggestions.isNotEmpty() || aiReplyStreamErrorCode != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                when {
                    isAiReplyStreaming -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        if (aiSuggestions.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_ai_working),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Row(
                                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                aiSuggestions.forEach { suggestion ->
                                    TextButton(
                                        onClick = { onAiSuggestionClick(suggestion) },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                                    ) {
                                        Text(suggestion, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = onCancelAiReplyStream, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_cancel), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                        }
                    }
                    isAiWorking -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_working), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    aiSuggestions.isNotEmpty() -> {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            aiSuggestions.forEach { suggestion ->
                                TextButton(
                                    onClick = { onAiSuggestionClick(suggestion) },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                                ) {
                                    Text(suggestion, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (aiReplyStreamErrorCode != null) {
                            IconButton(onClick = onRetryAiReplyStream, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = onClearAiSuggestions, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_clear_ai_suggestions), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                        }
                    }
                    else -> {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LocalChatPalette.current.unreadRed, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            aiStreamStatusText(aiReplyStreamErrorCode),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.unreadRed,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onRetryAiReplyStream, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onClearAiSuggestions, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.chat_clear_ai_suggestions), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // 附件菜单（带动画）
        AnimatedVisibility(
            visible = showExpressionPanel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ExpressionPanel(
                mode = expressionMode,
                onModeChange = { expressionMode = it },
                onEmojiClick = { onValueChange(value + it) },
                onStickerClick = onSendSticker,
                onGifClick = {
                    if (attachmentsEnabled) {
                        onSendGif()
                        showExpressionPanel = false
                    } else {
                        Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show()
                    }
                },
                stickersEnabled = attachmentsEnabled
            )
        }

        AnimatedVisibility(
            visible = showAttachMenu,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val attachKinds = AttachMenuPolicy.items(
                isGroup = isGroupChat,
                isChannel = isChannelChat,
                viewOnceEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.VIEW_ONCE),
                contactCardEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.CONTACT_CARD),
                nudgeEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.NUDGE),
                aiEnabled = com.maodouchat.ai.AiEntryPolicy.isComposerEntryActive(context, aiEnabled),
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                attachKinds.forEach { kind ->
                    if ((isGroupChat || isChannelChat) &&
                        (kind == AttachMenuKind.VIEW_ONCE || kind == AttachMenuKind.LIVE_LOCATION || kind == AttachMenuKind.NUDGE)
                    ) {
                        return@forEach
                    }
                    when (kind) {
                        AttachMenuKind.IMAGE -> AttachMenuItem(
                            icon = Icons.Outlined.Image,
                            label = stringResource(R.string.chat_attachment_image),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendImage() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.VIEW_ONCE -> AttachMenuItem(
                            icon = Icons.Outlined.VisibilityOff,
                            label = stringResource(R.string.chat_view_once_send),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendViewOnceImage() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.SPOILER -> AttachMenuItem(
                            icon = Icons.Outlined.Apps,
                            label = stringResource(R.string.chat_spoiler_media_send),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendSpoilerImage() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.PASTE -> AttachMenuItem(
                            icon = Icons.Outlined.ContentCopy,
                            label = stringResource(R.string.chat_attachment_paste),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onPasteFromClipboard() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.VIDEO -> AttachMenuItem(
                            icon = Icons.Outlined.CameraAlt,
                            label = stringResource(R.string.chat_attachment_video),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendVideo() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.FILE -> AttachMenuItem(
                            icon = Icons.Outlined.AttachFile,
                            label = stringResource(R.string.chat_attachment_file),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendFile() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.VOICE -> AttachMenuItem(
                            icon = Icons.Outlined.Mic,
                            label = stringResource(R.string.chat_attachment_voice),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onRecordStart() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.LOCATION -> AttachMenuItem(
                            icon = Icons.Outlined.LocationOn,
                            label = stringResource(R.string.chat_attachment_location),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendLocation() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.LIVE_LOCATION -> AttachMenuItem(
                            icon = Icons.Outlined.NearMe,
                            label = stringResource(R.string.chat_live_location_send),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendLiveLocation() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.SCHEDULE -> AttachMenuItem(
                            icon = Icons.Outlined.Schedule,
                            label = stringResource(R.string.schedule_send),
                            enabled = attachmentsEnabled && value.isNotBlank(),
                            onClick = { showAttachMenu = false; onScheduleSend() },
                            onDisabledClick = {
                                Toast.makeText(
                                    context,
                                    if (value.isBlank()) context.getString(R.string.schedule_need_text) else attachmentDisabledText,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        AttachMenuKind.QUICK_PHRASES -> AttachMenuItem(
                            icon = Icons.Outlined.SentimentSatisfied,
                            label = stringResource(R.string.chat_quick_phrases),
                            enabled = true,
                            onClick = { showAttachMenu = false; showQuickPhrases = true }
                        )
                        AttachMenuKind.CONTACT_CARD -> AttachMenuItem(
                            icon = Icons.Outlined.ContactPage,
                            label = stringResource(R.string.chat_send_contact_card),
                            enabled = true,
                            onClick = {
                                showAttachMenu = false
                                onLoadForwardTargets()
                                showContactCardPicker = true
                            }
                        )
                        AttachMenuKind.AI -> AttachMenuItem(
                            icon = Icons.Outlined.AutoAwesome,
                            label = stringResource(R.string.chat_ai_assistant),
                            enabled = !isAiWorking && !isUpdatingAiSetting,
                            onClick = { showAttachMenu = false; showAiMenu = true }
                        )
                        AttachMenuKind.SILENT -> AttachMenuItem(
                            icon = if (silentSend) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                            label = stringResource(
                                if (silentSend) R.string.chat_silent_send_on else R.string.chat_silent_send_off
                            ),
                            enabled = true,
                            onClick = { onToggleSilentSend() }
                        )
                        AttachMenuKind.NUDGE -> AttachMenuItem(
                            icon = Icons.Outlined.TouchApp,
                            label = stringResource(R.string.chat_nudge),
                            enabled = true,
                            onClick = { showAttachMenu = false; onSendNudge() }
                        )
                    }
                }
            }
        }

        if (showQuickPhrases) {
            QuickPhrasesDialog(
                onDismiss = { showQuickPhrases = false },
                onPick = { phrase ->
                    showQuickPhrases = false
                    val trimmed = phrase.trim()
                    if (trimmed.isNotEmpty()) {
                        onValueChange(if (value.isBlank()) trimmed else value + trimmed)
                    }
                }
            )
        }

        // 1.11：发送名片——联系人选择对话框（单聊会话对端用户）
        if (showContactCardPicker) {
            val pickerContacts = remember(contactCardTargets, currentUserId) {
                contactCardTargets
                    .filter { !it.isGroup }
                    .mapNotNull { chat ->
                        val other = chat.participants.firstOrNull { it.id != currentUserId }
                        if (other == null) null else chat to other
                    }
                    .sortedBy { it.second.displayName.lowercase() }
            }
            ContactCardPickerDialog(
                contacts = pickerContacts,
                onDismiss = { showContactCardPicker = false },
                onPick = { _, user ->
                    showContactCardPicker = false
                    onSendContactCard(user.id, user.displayName)
                }
            )
        }

        // 输入区
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            AnimatedVisibility(
                visible = mentionCandidates.isNotEmpty(),
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_mention_picker_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        mentionCandidates.forEach { candidate ->
                            val label = if (candidate.isEveryone) everyoneLabel else candidate.displayName
                            TextButton(
                                onClick = {
                                    val q = mentionQuery ?: return@TextButton
                                    val insertLabel = if (candidate.isEveryone) everyoneLabel else candidate.displayName
                                    val result = MentionPolicy.insertMention(
                                        text = value,
                                        cursor = value.length,
                                        displayName = insertLabel,
                                        query = q,
                                    )
                                    onValueChange(result.text)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (candidate.isEveryone) "@$everyoneLabel" else "@$label",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (mentionCandidates.size >= 8) {
                        Text(
                            stringResource(R.string.chat_mention_picker_count, mentionCandidates.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            val slashCandidates = remember(value, botCommands) {
                com.maodouchat.bot.BotCommandPolicy.filterCommands(botCommands, value)
            }
            AnimatedVisibility(
                visible = slashCandidates.isNotEmpty(),
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_bot_slash_picker_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    val multiBot = slashCandidates.map { it.botId }.distinct().size > 1
                    slashCandidates.forEach { item ->
                        TextButton(
                            onClick = {
                                onValueChange(com.maodouchat.bot.BotCommandPolicy.insertCommand(item, multiBot))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "/${item.command}" + if (multiBot) " @${item.username}" else "",
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.description.isNotBlank()) {
                                    Text(
                                        text = item.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isGroupChat && value.startsWith("@AI", ignoreCase = true)) {
                val showHint = value.length >= 3
                AnimatedVisibility(
                    visible = showHint,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.chat_group_ai_input_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val modeChips = listOf(
                                "answer" to R.string.chat_group_ai_mode_answer,
                                "summary" to R.string.chat_group_ai_mode_summary,
                                "decisions" to R.string.chat_group_ai_mode_decisions,
                                "tasks" to R.string.chat_group_ai_mode_tasks,
                                "timeline" to R.string.chat_group_ai_mode_timeline,
                                "risks" to R.string.chat_group_ai_mode_risks
                            )
                            val currentQuery = value.trim().removePrefix("@AI").removePrefix("@ai").trimStart()
                            val activeMode = when {
                                currentQuery.startsWith("总结") || currentQuery.startsWith("概括") ||
                                    currentQuery.startsWith("summary", ignoreCase = true) ||
                                    currentQuery.startsWith("summarize", ignoreCase = true) -> "summary"
                                currentQuery.startsWith("决策") || currentQuery.startsWith("决定") ||
                                    currentQuery.startsWith("decisions", ignoreCase = true) -> "decisions"
                                currentQuery.startsWith("待办") || currentQuery.startsWith("任务") ||
                                    currentQuery.startsWith("tasks", ignoreCase = true) ||
                                    currentQuery.startsWith("todo", ignoreCase = true) -> "tasks"
                                currentQuery.startsWith("时间线") || currentQuery.startsWith("时间轴") ||
                                    currentQuery.startsWith("timeline", ignoreCase = true) ||
                                    currentQuery.startsWith("chronology", ignoreCase = true) -> "timeline"
                                currentQuery.startsWith("风险") || currentQuery.startsWith("隐患") ||
                                    currentQuery.startsWith("risk", ignoreCase = true) ||
                                    currentQuery.startsWith("blocker", ignoreCase = true) -> "risks"
                                else -> "answer"
                            }
                            modeChips.forEach { (mode, labelRes) ->
                                val prefix = when (mode) {
                                    "summary" -> "总结 "
                                    "decisions" -> "决策 "
                                    "tasks" -> "待办 "
                                    "timeline" -> "时间线 "
                                    "risks" -> "风险 "
                                    else -> ""
                                }
                                FilterChip(
                                    selected = activeMode == mode,
                                    onClick = {
                                        val body = when {
                                            currentQuery.startsWith("总结") || currentQuery.startsWith("概括") ->
                                                currentQuery.removePrefix("总结").removePrefix("概括").trimStart()
                                            currentQuery.startsWith("summary", ignoreCase = true) ->
                                                currentQuery.drop(7).trimStart()
                                            currentQuery.startsWith("summarize", ignoreCase = true) ->
                                                currentQuery.drop(9).trimStart()
                                            currentQuery.startsWith("决策") || currentQuery.startsWith("决定") ->
                                                currentQuery.removePrefix("决策").removePrefix("决定").trimStart()
                                            currentQuery.startsWith("decisions", ignoreCase = true) ->
                                                currentQuery.drop(9).trimStart()
                                            currentQuery.startsWith("待办") || currentQuery.startsWith("任务") ->
                                                currentQuery.removePrefix("待办").removePrefix("任务").trimStart()
                                            currentQuery.startsWith("tasks", ignoreCase = true) ->
                                                currentQuery.drop(5).trimStart()
                                            currentQuery.startsWith("todo", ignoreCase = true) ->
                                                currentQuery.drop(4).trimStart()
                                            else -> currentQuery
                                        }
                                        onValueChange("@AI $prefix$body".trimEnd())
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                    }
                }
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            IconButton(
                onClick = { showAttachMenu = !showAttachMenu; if (showAttachMenu) showExpressionPanel = false },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.chat_attachment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(LocalChatPalette.current.chatInputBackground)) {
                // 1.175：回车发送偏好（开 → 单行 + IME Send；关 → 多行回车换行）
                val enterToSend = com.maodouchat.util.ComposerPreferences.enterToSend(context)
                TextField(
                    value = value, onValueChange = onValueChange,
                    placeholder = { Text(stringResource(R.string.chat_message_placeholder), style = MaterialTheme.typography.bodyLarge, color = LocalChatPalette.current.textHint) },
                    singleLine = enterToSend,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = if (enterToSend) androidx.compose.ui.text.input.ImeAction.Send else androidx.compose.ui.text.input.ImeAction.Default
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (enterToSend && !isSending) onSend() }
                    ),
                    trailingIcon = if (value.length >= com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH * 8 / 10) {
                        {
                            Text(
                                text = "${value.length}/${com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (value.length >= com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH * 9 / 10) UnreadRed else TextHint
                            )
                        }
                    } else null,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 40.dp)
                )
            }
            IconButton(
                onClick = { showExpressionPanel = !showExpressionPanel; if (showExpressionPanel) showAttachMenu = false },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.SentimentSatisfied,
                    contentDescription = stringResource(R.string.chat_emoji),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Box {
                DropdownMenu(expanded = showAiMenu, onDismissRequest = { showAiMenu = false }) {
                    Text(
                        stringResource(R.string.chat_ai_entry_primary_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    // 草稿改写：只动输入框内容
                    Text(
                        stringResource(R.string.chat_ai_section_draft),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_polish)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("polish", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_shorter)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("shorten", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_formal)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("formal", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_gentle)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("gentle", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_casual)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("casual", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_professional)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("professional", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_expand)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("expand", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_bullet)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("bullet", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_clarify)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("clarify", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_translate_draft)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = {
                            showAiMenu = false
                            showDraftTranslationLanguages = true
                        }
                    )
                    HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
                    // 聊天辅助：读上下文
                    Text(
                        stringResource(R.string.chat_ai_section_chat),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    Text(
                        stringResource(R.string.chat_ai_smart_replies),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    listOf(
                        "friendly" to R.string.chat_ai_reply_tone_friendly,
                        "natural" to R.string.chat_ai_reply_tone_natural,
                        "formal" to R.string.chat_ai_reply_tone_formal,
                        "concise" to R.string.chat_ai_reply_tone_concise,
                        "warm" to R.string.chat_ai_reply_tone_warm,
                        "humorous" to R.string.chat_ai_reply_tone_humorous,
                        "direct" to R.string.chat_ai_reply_tone_direct,
                        "empathetic" to R.string.chat_ai_reply_tone_empathetic,
                        "encouraging" to R.string.chat_ai_reply_tone_encouraging
                    ).forEach { (tone, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            enabled = aiEnabled && !isSecretChat,
                            onClick = { showAiMenu = false; onAiSuggestReplies(tone) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_summarize_recent)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiSummarize() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_conversation_profile)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onOpenConversationProfile() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_weekly_report)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onOpenWeeklyReport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_emotion_reply)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onEmotionReply() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_summary_history_menu)) },
                        onClick = { showAiMenu = false; onOpenAiSummaryHistory() }
                    )
                    if (isGroupChat) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_group_ai_title)) },
                            enabled = aiEnabled && !isSecretChat,
                            onClick = {
                                showAiMenu = false
                                if (!value.trimStart().startsWith("@AI", ignoreCase = true)) {
                                    onValueChange("@AI ${value.trimStart()}")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_tasks_menu)) },
                            onClick = {
                                showAiMenu = false
                                onOpenAiTasks()
                            }
                        )
                    }
                    // 8.47：消息分类（纯本地词典统计，不依赖 AI 开关；密聊不参与）
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_enhance_classify_title)) },
                        enabled = !isSecretChat,
                        onClick = { showAiMenu = false; onOpenMessageClassify() }
                    )
                    HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
                    Text(
                        stringResource(R.string.chat_ai_section_settings),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (aiEnabled) R.string.chat_ai_disable else R.string.chat_ai_enable)) },
                        onClick = { showAiMenu = false; onAiEnabledChange(!aiEnabled) }
                    )
                    Text(
                        stringResource(R.string.chat_ai_entry_context_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    )
                    Text(
                        stringResource(R.string.chat_ai_menu_footer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            val canSend = value.isNotBlank()
            val haptic = LocalHapticFeedback.current
            val hapticContext = LocalContext.current
            val density = LocalDensity.current
            var holdCancelArmed by remember { mutableStateOf(false) }
            if (isRecording) {
                // 按住说话过程中：显示取消/发送提示；也可点按图标
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(
                            if (holdCancelArmed) R.string.chat_slide_up_to_cancel
                            else R.string.chat_release_to_preview
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (holdCancelArmed) UnreadRed else TextSecondary,
                        modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRecordCancel, modifier = Modifier.size(40.dp).background(Secondary, CircleShape)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_cancel_recording), tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onRecordStop, modifier = Modifier.size(40.dp).background(UnreadRed, CircleShape)) {
                            Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.chat_stop_recording), tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            } else if (canSend) {
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val sendScale by animateFloatAsState(
                    targetValue = if (sendPressed) 0.9f else 1f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
                    label = "sendButtonScale"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
                        .shadow(2.dp, CircleShape)
                        .background(if (silentSend || isSending) Outline else Primary, CircleShape)
                        .combinedClickable(
                            enabled = !isSending,
                            interactionSource = sendInteraction,
                            indication = null,
                            onClick = {
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                onSend()
                            },
                            onLongClick = {
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                                onScheduleSend()
                            }
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // 空输入：按住说话（松开发送，上滑取消）
                val cancelThresholdPx = with(density) { 56.dp.toPx() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(1.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f), CircleShape)
                        .pointerInput(attachmentsEnabled) {
                            if (!attachmentsEnabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                holdCancelArmed = false
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                                onRecordStart()
                                var cancelled = false
                                var completed = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null) { completed = true; break }
                                        val dy = change.position.y - down.position.y
                                        val arm = dy < -cancelThresholdPx
                                        if (arm != holdCancelArmed) {
                                            holdCancelArmed = arm
                                            if (arm) com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                        }
                                        if (!change.pressed) {
                                            cancelled = holdCancelArmed
                                            change.consume()
                                            completed = true
                                            break
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    // 协程被取消（旋转/退后台/pointerInput 重启）时也兜底释放麦克风，
                                    // 否则 MediaRecorder 持续持有录音、50ms 电平循环后台空转
                                    if (completed) {
                                        if (cancelled) onRecordCancel() else onRecordStop()
                                    } else {
                                        onRecordCancel()
                                    }
                                    holdCancelArmed = false
                                }
                            }
                        }
                        .then(
                            if (!attachmentsEnabled) {
                                Modifier.clickable {
                                    Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show()
                                }
                            } else Modifier
                        )
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = stringResource(R.string.chat_hold_to_talk),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
}

@Composable
private fun TranslationLanguageDialog(
    translatedLanguages: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var languageSearch by rememberSaveable { mutableStateOf("") }
    val q = languageSearch.trim()
    val labeledOptions = translationLanguageOptions.map { option ->
        option to stringResource(option.labelResource)
    }
    val filteredLanguages = if (q.isEmpty()) {
        labeledOptions
    } else {
        labeledOptions.filter { (option, label) ->
            label.contains(q, ignoreCase = true) ||
                option.wireValue.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_translation_language_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedTextField(
                    value = languageSearch,
                    onValueChange = { languageSearch = it.take(64) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    placeholder = { Text(stringResource(R.string.chat_translation_language_search_hint)) },
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
                if (filteredLanguages.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_translation_language_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    filteredLanguages.forEach { (language, label) ->
                        TextButton(
                            onClick = { onSelect(language.wireValue) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (language.wireValue in translatedLanguages) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.chat_translation_available),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun ReportDialog(
    title: String,
    onDismiss: () -> Unit,
    onReport: (reason: String, description: String?) -> Unit
) {
    val reasons = stringArrayResource(R.array.chat_report_reasons).toList()
    // 8.49 防御：资源数组为空时回退空串（此前 reasons.first() 依赖资源不被清空）
    var selectedReason by rememberSaveable { mutableStateOf(reasons.firstOrNull().orEmpty()) }
    var description by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reasons.forEach { reason ->
                    val selected = reason == selectedReason
                    TextButton(
                        onClick = { selectedReason = reason },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (selected) PrimaryFixed.copy(alpha = 0.42f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(
                            text = reason,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selected) Primary else OnSurface
                        )
                    }
                }
                TextField(
                    value = description,
                    onValueChange = { description = it.take(800) },
                    placeholder = { Text(stringResource(R.string.chat_report_description), color = LocalChatPalette.current.textHint) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onReport(selectedReason, description.trim().takeIf { it.isNotBlank() })
                }
            ) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun QuickPhrasesDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    var phrases by remember {
        mutableStateOf(com.maodouchat.util.QuickPhrasePreferences.getPhrases(context))
    }
    var customIds by remember {
        mutableStateOf(com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet())
    }
    var newPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quick_phrases), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = newPhrase,
                    onValueChange = {
                        newPhrase = it.take(com.maodouchat.util.QuickPhrasePolicy.MAX_PHRASE_LENGTH)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_quick_phrases_add_hint, com.maodouchat.util.QuickPhrasePolicy.MAX_PHRASE_LENGTH)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (error != null) {
                        Text(error.orEmpty(), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        enabled = newPhrase.isNotBlank(),
                        onClick = {
                            if (!com.maodouchat.util.QuickPhrasePolicy.isAddable(
                                    com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context),
                                    newPhrase
                                )
                            ) {
                                error = context.getString(R.string.chat_quick_phrases_add_invalid)
                                return@TextButton
                            }
                            com.maodouchat.util.QuickPhrasePreferences.addPhrase(context, newPhrase.trim())
                            phrases = com.maodouchat.util.QuickPhrasePreferences.getPhrases(context)
                            customIds = com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet()
                            newPhrase = ""
                            Toast.makeText(context, context.getString(R.string.chat_quick_phrases_added), Toast.LENGTH_SHORT).show()
                        }
                    ) { Text(stringResource(R.string.chat_quick_phrases_add)) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    phrases.forEach { phrase ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { onPick(phrase) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    phrase,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (phrase in customIds) {
                                IconButton(onClick = {
                                    com.maodouchat.util.QuickPhrasePreferences.removePhrase(context, phrase)
                                    phrases = com.maodouchat.util.QuickPhrasePreferences.getPhrases(context)
                                    customIds = com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet()
                                }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = LocalChatPalette.current.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.chat_quick_phrases_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } }
    )
}

@Composable
private fun ExpressionPanel(
    mode: String,
    onModeChange: (String) -> Unit,
    onEmojiClick: (String) -> Unit,
    onStickerClick: (String) -> Unit,
    onGifClick: () -> Unit,
    stickersEnabled: Boolean
) {
    val context = LocalContext.current
    var stickerTab by rememberSaveable { mutableStateOf(com.maodouchat.util.StickerCatalog.PACK_RECENT) }
    var stickerQuery by rememberSaveable { mutableStateOf("") }
    var showPackManager by rememberSaveable { mutableStateOf(false) }
    var enabledPackIds by remember {
        mutableStateOf(com.maodouchat.util.StickerPreferences.getEnabledPackIds(context))
    }
    var recentStickers by remember {
        mutableStateOf(com.maodouchat.util.StickerPreferences.getRecent(context))
    }
    var recentEmojis by remember {
        mutableStateOf(com.maodouchat.util.EmojiRecentPreferences.getRecent(context))
    }
    val enabledPacks = remember(enabledPackIds) {
        com.maodouchat.util.StickerPolicy.enabledPacks(enabledPackIds)
    }
    val searchHits = remember(stickerQuery, enabledPackIds) {
        com.maodouchat.util.StickerPolicy.searchStickers(stickerQuery, enabledPacks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mode == "STICKER") 280.dp else 224.dp)
            .background(LocalChatPalette.current.chatInputBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EMOJI" to stringResource(R.string.chat_emoji), "STICKER" to stringResource(R.string.chat_sticker)).forEach { (key, label) ->
                    val selected = mode == key
                    TextButton(
                        onClick = { onModeChange(key) },
                        modifier = Modifier.background(
                            if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                    ) { Text(label, color = if (selected) Primary else TextSecondary) }
                }
            }
            if (mode == "STICKER") {
                IconButton(onClick = { showPackManager = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.sticker_pack_manage), tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onGifClick, enabled = stickersEnabled) {
                Icon(Icons.Outlined.GifBox, contentDescription = stringResource(R.string.chat_select_gif), tint = if (stickersEnabled) Primary else TextHint)
            }
        }

        if (mode == "STICKER") {
            OutlinedTextField(
                value = stickerQuery,
                onValueChange = { stickerQuery = it.take(64) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sticker_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Outline
                )
            )
            if (stickerQuery.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StickerPackChip(
                        label = stringResource(R.string.sticker_pack_recent),
                        selected = stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT,
                        onClick = { stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT }
                    )
                    enabledPacks.forEach { pack ->
                        StickerPackChip(
                            label = stickerPackLabel(pack.nameKey),
                            selected = stickerTab == pack.id,
                            onClick = { stickerTab = pack.id }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            val displayItems = when {
                stickerQuery.isNotBlank() -> searchHits
                stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> recentStickers
                else -> enabledPacks.firstOrNull { it.id == stickerTab }?.stickers
                    ?: enabledPacks.firstOrNull()?.stickers
                    ?: emptyList()
            }
            if (displayItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            when {
                                stickerQuery.isNotBlank() -> R.string.sticker_search_empty
                                stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> R.string.sticker_recent_empty
                                else -> R.string.sticker_search_empty
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    displayItems.chunked(6).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowItems.forEach { item ->
                                // 9.223：贴纸按压回弹（TG 式手感，尊重系统动效开关）
                                PressScaleGlyphItem(
                                    glyph = item,
                                    fontSize = 32.sp,
                                    enabled = stickersEnabled,
                                    onClick = {
                                        onStickerClick(item)
                                        recentStickers = com.maodouchat.util.StickerPolicy.pushRecent(recentStickers, item)
                                    }
                                )
                            }
                            repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                        }
                    }
                }
            }
        } else {
            var emojiSearch by rememberSaveable { mutableStateOf("") }
            val filteredEmojis = remember(emojiSearch) {
                val q = emojiSearch.trim()
                if (q.isEmpty()) {
                    BUILT_IN_EMOJIS
                } else {
                    // emoji itself or common keyword aliases
                    val aliases = EMOJI_SEARCH_ALIASES
                    BUILT_IN_EMOJIS.filter { emoji ->
                        emoji.contains(q) ||
                            aliases[emoji].orEmpty().any { it.contains(q, ignoreCase = true) }
                    }
                }
            }
            OutlinedTextField(
                value = emojiSearch,
                onValueChange = { emojiSearch = it.take(64) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_emoji_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Outline
                )
            )
            if (filteredEmojis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.chat_emoji_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            } else {
                // 点击表情：上屏 + 记录最近使用（去重，本地按账号隔离）
                val pushRecentEmoji: (String) -> Unit = { emoji ->
                    onEmojiClick(emoji)
                    com.maodouchat.util.EmojiRecentPreferences.recordRecent(context, emoji)
                    recentEmojis = com.maodouchat.util.EmojiRecentPreferences.getRecent(context)
                }
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    // 最近使用行：仅无搜索词时展示
                    if (emojiSearch.isBlank() && recentEmojis.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_emoji_recent),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            recentEmojis.forEach { item ->
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { pushRecentEmoji(item) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item, fontSize = 22.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    filteredEmojis.chunked(6).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowItems.forEach { item ->
                                PressScaleGlyphItem(
                                    glyph = item,
                                    fontSize = 24.sp,
                                    enabled = true,
                                    onClick = { pushRecentEmoji(item) }
                                )
                            }
                            repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showPackManager) {
        // 远程贴纸包（OnDemandStickerStore）：按需拉取清单 + 下载，B1 包体瘦身闭环
        var remotePacks by remember { mutableStateOf<List<String>>(emptyList()) }
        var remoteStatus by remember { mutableStateOf<String?>(null) }
        var remoteDownloading by remember { mutableStateOf<String?>(null) }
        val remoteScope = rememberCoroutineScope()
        val remoteContext = context
        fun refreshRemotePacks() {
            remoteScope.launch {
                remoteStatus = null
                com.maodouchat.slim.OnDemandStickerStore.refreshManifest(remoteContext)
                    .onSuccess { remotePacks = it }
                    .onFailure { remoteStatus = remoteContext.getString(R.string.sticker_store_download_failed) }
            }
        }
        fun downloadRemotePack(packId: String) {
            if (remoteDownloading != null) return
            remoteScope.launch {
                remoteDownloading = packId
                remoteStatus = null
                val result = com.maodouchat.slim.OnDemandStickerStore.ensurePack(remoteContext, packId)
                remoteStatus = result.message
                remoteDownloading = null
            }
        }
        AlertDialog(
            onDismissRequest = { showPackManager = false },
            title = { Text(stringResource(R.string.sticker_pack_manage)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                ) {
                    com.maodouchat.util.StickerCatalog.BUILT_IN_PACKS.forEach { pack ->
                        val enabled = pack.id in enabledPackIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = com.maodouchat.util.StickerPolicy.togglePackEnabled(
                                        enabledIds = enabledPackIds,
                                        packId = pack.id,
                                        enable = !enabled
                                    )
                                    enabledPackIds = next
                                    com.maodouchat.util.StickerPreferences.setEnabledPackIds(context, next)
                                    if (!enabled && stickerTab == pack.id) {
                                        stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = stickerPackLabel(pack.nameKey),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(if (enabled) R.string.sticker_pack_enabled else R.string.sticker_pack_disabled),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enabled) Primary else TextSecondary
                            )
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.sticker_store_remote_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { refreshRemotePacks() }) {
                            Text(stringResource(R.string.sticker_store_remote_refresh))
                        }
                    }
                    if (remoteStatus != null) {
                        Text(
                            remoteStatus.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    if (remotePacks.isEmpty() && remoteStatus == null) {
                        Text(
                            stringResource(R.string.sticker_store_remote_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    remotePacks.forEach { packId ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                packId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (remoteDownloading == packId) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                TextButton(onClick = { downloadRemotePack(packId) }) {
                                    Text(stringResource(R.string.sticker_store_remote_download))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPackManager = false }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }
}

@Composable
private fun StickerPackChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Primary else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun stickerPackLabel(nameKey: String): String = when (nameKey) {
    "mood" -> stringResource(R.string.sticker_pack_mood)
    "gesture" -> stringResource(R.string.sticker_pack_gesture)
    "party" -> stringResource(R.string.sticker_pack_party)
    else -> nameKey
}

private val BUILT_IN_EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅",
    "😂", "🤣", "🥹", "😊", "😇", "🙂",
    "😉", "😍", "🥰", "😘", "😗", "😙",
    "😚", "😋", "😜", "🤪", "😝", "🤑",
    "🤗", "🤭", "🤫", "🤔", "🤐", "🤨",
    "😐", "😑", "😶", "😏", "😒", "🙄",
    "😬", "😮‍💨", "🤥", "😌", "😔", "😪",
    "🤤", "😴", "😷", "🤒", "🤕", "🤢",
    "🤮", "🥵", "🥶", "🥴", "😵", "🤯",
    "🤠", "🥳", "😎", "🤓", "🧐", "😕",
    "😟", "🙁", "☹️", "😮", "😯", "😲",
    "😳", "🥺", "😦", "😧", "😨", "😰",
    "😥", "😢", "😭", "😱", "😖", "😣",
    "😞", "😓", "😩", "😫", "🥱", "😤",
    "😡", "😠", "🤬", "😈", "👿", "💀",
    "👍", "👎", "👊", "✊", "🤛", "🤜",
    "👏", "🙌", "👐", "🤲", "🤝", "🙏",
    "💪", "🦾", "🦵", "🦶", "👂", "👃",
    "👀", "👁️", "👅", "👄", "💋", "💘",
    "❤️", "🧡", "💛", "💚", "💙", "💜",
    "🖤", "🤍", "🤎", "💔", "❣️", "💕",
    "💞", "💓", "💗", "💖", "💝", "💟",
    "🔥", "✨", "⭐", "🌟", "💫", "🎉",
    "🎊", "🎈", "🎁", "🏆", "🥇", "💯",
    "✅", "❌", "❗", "❓", "💤", "💢",
    "🫡", "🫠", "🫥", "🫢", "🫣", "🫤",
    "🫶", "🫰", "🫵", "🖖", "🤙", "👋",
    "🚀", "🌈", "🍀", "🌸", "☀️", "🌙",
    "🐱", "🐶", "🐼", "🦊", "🐰", "🐻",
    "🐯", "🦁", "🐨", "🐸", "🐵", "🐔",
    "🍕", "🍔", "☕", "🍺", "🎂", "🍩",
    "⚽", "🏀", "🎮", "🎧", "📚", "✈️",
    "💡", "🎯",
    "🧭",
    "📌",
    "🧩",
    "🛡️",
    "🔔", "📎", "🔗", "📝"

)

/** Lightweight keyword aliases for emoji panel search (zh/en). */
private val EMOJI_SEARCH_ALIASES: Map<String, List<String>> = mapOf<String, List<String>>(
    "😂" to listOf("laugh", "lol", "笑", "哈哈"),
    "🤣" to listOf("rofl", "laugh", "笑"),
    "😍" to listOf("love", "heart eyes", "爱", "喜欢"),
    "🥰" to listOf("love", "cute", "爱", "可爱"),
    "😘" to listOf("kiss", "亲", "么么"),
    "😎" to listOf("cool", "酷"),
    "🤔" to listOf("think", "thinking", "思考", "嗯"),
    "😴" to listOf("sleep", "困", "睡"),
    "😭" to listOf("cry", "sad", "哭", "泪"),
    "😡" to listOf("angry", "怒", "生气"),
    "🤯" to listOf("mind blown", "震惊", "炸"),
    "🥳" to listOf("party", "庆祝", "派对"),
    "👍" to listOf("ok", "yes", "like", "赞", "好"),
    "👎" to listOf("no", "dislike", "踩", "不好"),
    "👏" to listOf("clap", "applaud", "鼓掌"),
    "🙏" to listOf("pray", "please", "thanks", "拜托", "谢谢"),
    "💪" to listOf("strong", "muscle", "加油", "💪"),
    "🤝" to listOf("handshake", "deal", "握手", "合作"),
    "😊" to listOf("smile", "happy", "笑", "开心"),
    "🙌" to listOf("raise hands", "celebration", "举手", "耶"),
    "🤩" to listOf("star eyes", "wow", "惊喜", "崇拜"),
    "🥲" to listOf("tear smile", "bittersweet", "含泪笑", "无奈"),
    "🤣" to listOf("rofl", "laugh hard", "大笑", "笑哭"),
    "👌" to listOf("ok hand", "perfect", "没问题", "好的"),
    "🫶" to listOf("heart hands", "care", "比心", "抱抱"),
    "❤️" to listOf("heart", "love", "心", "爱"),
    "💔" to listOf("broken", "heartbreak", "心碎"),
    "🔥" to listOf("fire", "hot", "火", "热"),
    "✨" to listOf("sparkle", "shine", "亮", "闪光"),
    "🎉" to listOf("party", "tada", "庆祝", "撒花"),
    "💯" to listOf("100", "perfect", "满分"),
    "✅" to listOf("check", "done", "ok", "完成", "对"),
    "❌" to listOf("cross", "no", "错", "叉"),
    "👀" to listOf("eyes", "look", "看", "👀"),
    "💀" to listOf("skull", "dead", "死", "骷髅"),
    "💤" to listOf("zzz", "sleep", "困"),
    "🎁" to listOf("gift", "present", "礼物"),
    "🏆" to listOf("trophy", "win", "冠军", "奖杯"),
    "⭐" to listOf("star", "星"),
    "🌟" to listOf("star", "glow", "星"),
    "❓" to listOf("question", "?", "问"),
    "❗" to listOf("exclaim", "!", "感叹"),
    "🫡" to listOf("salute", "respect", "敬礼", "收到"),
    "🫠" to listOf("melt", "awkward", "融化", "尴尬"),
    "🫶" to listOf("heart hands", "care", "比心", "爱心"),
    "🚀" to listOf("rocket", "launch", "火箭", "起飞"),
    "🍕" to listOf("pizza", "food", "披萨", "吃"),
    "🍔" to listOf("burger", "food", "汉堡"),
    "☕" to listOf("coffee", "tea", "咖啡", "茶"),
    "🍺" to listOf("beer", "drink", "啤酒"),
    "🎂" to listOf("cake", "birthday", "蛋糕", "生日"),
    "⚽" to listOf("soccer", "football", "足球"),
    "🏀" to listOf("basketball", "篮球"),
    "🎮" to listOf("game", "play", "游戏"),
    "🎧" to listOf("music", "headphones", "音乐", "耳机"),
    "📚" to listOf("books", "study", "书", "学习"),
    "✈️" to listOf("plane", "travel", "飞机", "旅行"),
    "🐯" to listOf("tiger", "虎"),
    "🦁" to listOf("lion", "狮"),
    "🐸" to listOf("frog", "蛙"),
    "🐵" to listOf("monkey", "猴"),
    "🌈" to listOf("rainbow", "彩虹"),
    "🐱" to listOf("cat", "猫"),
    "🐶" to listOf("dog", "狗"),
    "☀️" to listOf("sun", "sunny", "太阳", "晴"),
    "🌙" to listOf("moon", "night", "月亮", "夜"),
    "💡" to listOf("idea", "bulb", "灵感", "灯泡"),
    "🧭" to listOf("compass", "navigate", "导航", "指南针"),
    "📌" to listOf("pin", "bookmark", "图钉", "标记"),
    "🧩" to listOf("puzzle", "piece", "拼图"),
    "🛡️" to listOf("shield", "protect", "盾牌", "防护"),
)

/**
 * 日历跳转对话框：选日期后跳到该日第一条消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateJumpDialog(
    onDismiss: () -> Unit,
    onJump: (dayStartMillis: Long) -> Unit
) {
    val context = LocalContext.current
    val today = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_jump_date_title)) },
        text = {
            DatePicker(state = datePickerState, showModeToggle = false)
        },
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val utcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                    // 选中的是 UTC 当天 00:00；换算成本地时区当天 00:00
                    val localStart = java.time.Instant.ofEpochMilli(utcMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    onJump(localStart)
                }
            ) { Text(stringResource(R.string.chat_jump_date_jump)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
        }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ChatDetailScreenPreview() { MaodouchatTheme { ChatDetailScreen() } }

/** 1.11：发送名片——选择要分享的联系人（单聊会话对端用户）。1.27：支持搜索过滤。 */
@Composable
private fun ContactCardPickerDialog(
    contacts: List<Pair<Chat, com.maodouchat.data.model.User>>,
    onDismiss: () -> Unit,
    onPick: (Chat, com.maodouchat.data.model.User) -> Unit
) {
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    val filteredContacts = remember(contacts, pickerQuery) {
        val q = pickerQuery.trim()
        if (q.isEmpty()) {
            contacts
        } else {
            contacts.filter { (_, user) ->
                user.displayName.contains(q, ignoreCase = true) || user.name.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_card_picker_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.contact_card_picker_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(if (contacts.isEmpty()) R.string.contact_card_picker_empty else R.string.contact_card_picker_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredContacts.forEach { (chat, user) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(chat, user) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        user.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "@${user.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalChatPalette.current.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 9.223：表情/贴纸格子项——按压缩放回弹（TG 式手感）。
 * 系统关闭动画时退化为无动效点击；无 indication（缩放即反馈）。
 */
@Composable
private fun PressScaleGlyphItem(
    glyph: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val motion = LocalMotionSettings.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion.animationsEnabled) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "glyphPressScale"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
    }
}
