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
import androidx.compose.runtime.getValue
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
internal enum class ParticleAction { DELETE, REVOKE }

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
    // G335：会话级流程开关 / 草稿与选择集收进持有类（各带 Saver，见 ChatDetailConversationFlowStates.kt）
    val flows = rememberChatDetailConversationFlowState()
    val drafts = rememberChatDetailDraftState()
    // G335：AI 面板与杂项弹层开关收进持有类（带 Saver，见 ChatDetailAiPanelState.kt）
    val aiPanels = rememberChatDetailAiPanelState()
    // G335：聊天锁流程 / 联系人入口链收进持有类（各带 Saver，见 ChatDetailChatLockAndContactStates.kt）
    val chatLock = rememberChatDetailChatLockState()
    val contactSheets = rememberChatDetailContactSheetState()
    // G335：会话级设置/提醒/定时这一组弹窗收进持有类（带 Saver，见 ChatDetailScheduleState.kt）
    val schedule = rememberChatDetailScheduleState()
    // G335：群通话「类型 → 选成员」流程收进持有类（带 Saver，见 ChatDetailGroupCallState.kt）
    val groupCall = rememberChatDetailGroupCallState()
    // G335：搜索状态族收进持有类（带 Saver，保存语义不变——见 ChatDetailSearchState.kt）
    val search = rememberChatDetailSearchState()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    val listState = rememberLazyListState()
    val listScrollScope = rememberCoroutineScope()
    // 8.47：滚动合并执行器（B7 帧预算）——高频回底/跳转连点合并同帧请求，
    // 超距跳转瞬时 snap，避免长动画占帧（此前 4 处裸 animateScrollToItem）
    val chatListScroller = com.maodouchat.perf.rememberCoalescedScroller()
    // G335（第十三批）：滚动位置族（是否在底部附近 / 新消息徽标计数 / 自动滚动游标 /
    // 滚动到顶部触发加载更早消息）收进持有类（见 ChatDetailScrollState.kt）
    val scroll = rememberChatDetailScrollState(listState)
    val context = LocalContext.current
    LaunchedEffect(state.openedSecretChatId) {
        val secretId = state.openedSecretChatId ?: return@LaunchedEffect
        viewModel.clearOpenedSecretChat()
        onOpenSecretChat(secretId)
    }

    // G335（第十二批）：AI 安全提示族收进持有类（见 ChatDetailAiSafetyState.kt）
    val aiSafety = rememberChatDetailAiSafetyState(context)
    // 9.150：壁纸/字号偏好改为可变状态并在 ON_RESUME 刷新——从设置页改完返回
    // 仍存活的聊天页实例不再持有陈旧背景/字号
    // G335：这三项「必须一起刷新」的不变量收进持有类（见 ChatDetailAppearanceStates.kt）
    val appearance = rememberChatDetailAppearanceState(context)
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
                aiSafety.refreshFrom(context)
                // 9.150：刷新外观偏好（设置页修改后返回即时生效）
                appearance.refreshFrom(context)
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
    // G335：这一族「弹层当前操作哪条消息」的状态收进持有类（见 ChatDetailMessageActionState）。
    val messageActions = remember { ChatDetailMessageActionState() }
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
        if (!chatAiSurfacesVisible && search.searchMode == ChatSearchMode.SEMANTIC) {
            search.searchMode = ChatSearchMode.KEYWORD
            viewModel.clearSemanticSearch()
        }
    }
    // G335（第十四批）：全屏媒体查看状态族收进持有类（见 ChatDetailFullscreenMediaState）。
    val media = rememberChatDetailFullscreenMediaState()
    // 0.83：清空本机聊天记录确认
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    val chatSnackbarHostState = remember { SnackbarHostState() }
    var replyTarget by remember { mutableStateOf<Message?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showGroupInfo by rememberSaveable { mutableStateOf(false) }
    // 1.11：发送名片——联系人选择对话框
    var showChatOverflow by remember { mutableStateOf(false) }
    // 1.02：临时静音至对话框

    var setLockError by remember { mutableStateOf<String?>(null) }
    // G335：粒子动效三件套收进持有类（见 ChatDetailTransientStates.kt）
    val particles = remember { ChatDetailParticleState() }
    var navigationHighlightMessageId by remember { mutableStateOf<String?>(null) }
    val bubbleBounds = remember { mutableMapOf<String, BubbleBounds>() }
    val configuration = LocalConfiguration.current
    // 9.205：用主题真实深浅替代系统深浅/palette 身份比较（TG 主题与强制模式下不再误判）
    val isDarkChat = com.maodouchat.ui.theme.LocalDarkTheme.current
    val themeChatPalette = LocalChatPalette.current
    val chatBackgroundColor = remember(appearance.wallpaperPreset, isDarkChat, themeChatPalette) {
        com.maodouchat.util.ChatAppearancePolicy.resolveBackground(
            preset = appearance.wallpaperPreset,
            isDark = isDarkChat,
            fallback = themeChatPalette.chatBackground
        )
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, appearance.fontScale) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density,
            fontScale = (baseDensity.fontScale * appearance.fontScale.multiplier).coerceIn(0.85f, 1.6f)
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
    // G335（第十三批）：滚动到顶部附近触发加载更早消息，派生量收进 scroll 持有类
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
    val selectedMessages = remember(state.messages, drafts.selectedMessageIds) {
        state.messages.filter { it.id in drafts.selectedMessageIds }
    }
    val messageSelectionMode = drafts.selectedMessageIds.isNotEmpty()
    BackHandler(enabled = showChatOverflow || messageSelectionMode || search.showSearchBar) {
        when {
            showChatOverflow -> showChatOverflow = false
            messageSelectionMode -> drafts.selectedMessageIds = emptySet()
            search.showSearchBar -> search.showSearchBar = false
        }
    }
    val searchDocuments = remember(state.messages) { buildChatSearchDocuments(state.messages) }
    val localSearchResults = remember(search.searchQuery, search.searchScope, search.searchWindow, searchDocuments) {
        searchChatDocuments(
            documents = searchDocuments,
            query = search.searchQuery,
            scope = search.searchScope,
            window = search.searchWindow,
            currentUserId = state.currentUserId
        )
    }
    val semanticCandidates = remember(search.showSearchBar, search.searchMode, search.searchScope, search.searchWindow, searchDocuments) {
        if (search.showSearchBar && search.searchMode == ChatSearchMode.SEMANTIC) {
            semanticSearchCandidates(searchDocuments, search.searchScope, search.searchWindow, currentUserId = state.currentUserId)
        } else {
            emptyList()
        }
    }
    val semanticSearchResults = remember(
        state.semanticSearchResultIds,
        state.semanticSearchQuery,
        search.searchQuery,
        state.messages
    ) {
        if (state.semanticSearchQuery != search.searchQuery.trim()) {
            emptyList()
        } else {
            state.semanticSearchResultIds.mapNotNull(messagesById::get)
        }
    }
    val searchResults = if (search.searchMode == ChatSearchMode.SEMANTIC) semanticSearchResults else localSearchResults

    // 8.48：禁言到期重组触发器（到期写入后提示条随重组消失）
    var muteTick by remember { mutableLongStateOf(0L) }
    // G335：发送前待确认项收进持有类（见 ChatDetailTransientStates.kt）
    val sendPending = remember { ChatDetailSendPendingState() }
    // G331：9 个 ActivityResult 入口搬进 `ChatDetailPickers.kt`（连带它们各自的
    // 「为什么选这个 contract」注释）。这里只留接线。
    val pickers = rememberChatDetailPickers(
        messages = ChatDetailPermissionMessages(
            record = chatPermissionRecordMsg,
            voiceCall = chatPermissionVoiceCallMsg,
            videoCall = chatPermissionVideoCallMsg,
            location = chatPermissionLocationMsg,
        ),
        onImagePicked = { uri -> sendPending.onPicked(uri, isVideo = false) },
        onVideoPicked = { uri -> sendPending.onPicked(uri, isVideo = true) },
        onFilePicked = { viewModel.sendFile(it) },
        onGifPicked = {
            viewModel.sendGif(it)
            aiPanels.showGifSearch = false
        },
        onRecordPermissionGranted = { viewModel.startRecording() },
        onVoiceCallGranted = { onVoiceCall(state.contact.id, state.contact.name) },
        onVideoCallGranted = { onVideoCall(state.contact.id, state.contact.name) },
        onLocationGranted = {
            if (flows.pendingLiveLocationPermission) flows.showLiveLocationDuration = true
            else viewModel.sendCurrentLocation()
            flows.pendingLiveLocationPermission = false
        },
    )

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
        particles.start(
            messageId = message.id,
            action = action,
            states = listOf(ParticleState(message.id, bounds.offset, bounds.size, bubbleColor)),
        )
    }

    // G335（第十三批）：新消息到达时的自动滚动决策收进 scroll 持有类（见 ChatDetailScrollState.onLatestMessage）
    LaunchedEffect(
        state.messages.lastOrNull()?.id,
        state.currentUserId,
        state.initialTimelineReady,
        reversedChatItems.size
    ) {
        val latestMessage = state.messages.lastOrNull() ?: return@LaunchedEffect
        scroll.onLatestMessage(
            latestId = latestMessage.id,
            latestSenderId = latestMessage.senderId,
            currentUserId = state.currentUserId,
            initialTimelineReady = state.initialTimelineReady,
            navigationTargetMessageId = state.navigationTargetMessageId,
            navigationHighlightMessageId = navigationHighlightMessageId,
            scrollToItem = { animated -> chatListScroller.scrollToItem(listState, 0, animated = animated) },
        )
    }

    LaunchedEffect(scroll.isNearBottom) {
        scroll.clearPendingOnNearBottom()
    }

    LaunchedEffect(scroll.shouldLoadOlderMessages) {
        if (scroll.shouldLoadOlderMessages) viewModel.loadOlderMessages()
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
    // G335：密聊门禁进度收进持有类（见 ChatDetailSecretGateState.kt）
    val secretGate = remember { ChatDetailSecretGateState() }
    LaunchedEffect(secretActive, state.chat?.id, secretGate.secretGateDismissed) {
        if (!secretActive) {
            secretGate.secretGateBlocked = false
            return@LaunchedEffect
        }
        secretGate.secretGateDismissed = false
        if (com.maodouchat.util.Secret2faGatePrefs.isGateOpen(context)) {
            secretGate.secretGateBlocked = false
            return@LaunchedEffect
        }
        secretGate.secretGateBlocked = true
        com.maodouchat.security.SensitiveActionGate.confirmSystemAuth(
            context = context,
            title = context.getString(R.string.secret_2fa_gate_title),
            subtitle = context.getString(R.string.secret_2fa_gate_subtitle),
            onSuccess = {
                secretGate.secretGateBlocked = false
                com.maodouchat.util.Secret2faGatePrefs.markVerified(context)
            },
            onFailure = {
                secretGate.secretGateDismissed = true
                Toast.makeText(context, context.getString(R.string.secret_2fa_gate_verify_hint), Toast.LENGTH_LONG).show()
            }
        )
    }

    // B2 设备核验（dvz）：进入密聊时若开关开启且对端指纹未核验 → 自动弹出安全码页；用户验证后不再弹
    LaunchedEffect(secretActive, state.chat?.id, state.contactIdentityFingerprint, secretGate.secretGateBlocked) {
        if (!secretActive) {
            secretGate.deviceVerifyPrompted = false
            return@LaunchedEffect
        }
        if (secretGate.secretGateBlocked) return@LaunchedEffect
        if (secretGate.deviceVerifyPrompted) return@LaunchedEffect
        if (!com.maodouchat.util.SecretDeviceVerifyPrefs.isEnabled(context)) return@LaunchedEffect
        val fp = state.contactIdentityFingerprint?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (com.maodouchat.util.SecretDeviceVerifyPrefs.isFingerprintVerified(context, fp)) return@LaunchedEffect
        secretGate.deviceVerifyPrompted = true
        viewModel.showSafetyCodeDialog()
    }

    // B2 新设备风控（ndz）：首次进入密聊需登记本机设备指纹；未登记提示并保持锁定
    // 设备指纹 = 安装级 UUID（跨重启稳定；本应用关闭系统备份，重装后 SharedPreferences
    // 清空 → 重新生成 → 视为新设备）。此前用「当前日期窗」导致每天变化，已登记设备
    // 次日被误判为新设备，改为稳定的安装标识。
    val deviceRiskId = remember(context) {
        com.maodouchat.push.PushRegistrationManager.currentDeviceId(context)
    }
    LaunchedEffect(secretActive, state.chat?.id, secretGate.deviceRiskPrompted, secretGate.deviceRiskLocked, secretGate.secretGateBlocked) {
        if (!secretActive) {
            secretGate.deviceRiskPrompted = false
            return@LaunchedEffect
        }
        if (secretGate.secretGateBlocked) return@LaunchedEffect
        if (secretGate.deviceRiskPrompted) return@LaunchedEffect
        if (!com.maodouchat.util.SecretNewDeviceRiskPrefs.isEnabled(context)) return@LaunchedEffect
        if (deviceRiskId.isBlank() || com.maodouchat.util.SecretNewDeviceRiskPrefs.isDeviceTrusted(context, deviceRiskId)) return@LaunchedEffect
        secretGate.deviceRiskPrompted = true
        secretGate.showDeviceRiskDialog = true
    }
    NewDeviceRiskPromptDialog(
        onRegister = {
            secretGate.showDeviceRiskDialog = false
            secretGate.deviceRiskLocked = false
            if (deviceRiskId.isNotBlank()) {
                com.maodouchat.util.SecretNewDeviceRiskPrefs.registerDevice(context, deviceRiskId)
                Toast.makeText(context, context.getString(R.string.secret_new_device_risk_registered), Toast.LENGTH_SHORT).show()
            }
        },
        onKeepLocked = {
            secretGate.showDeviceRiskDialog = false
            secretGate.deviceRiskLocked = true
            Toast.makeText(context, context.getString(R.string.secret_new_device_risk_locked), Toast.LENGTH_LONG).show()
        },
    )

    // B4 本地 AI 聚合：会话画像 / 本周周报（仅非密聊会话，密聊不参与避免落可搜索缓存）
    // G335：AI 三块结果的「值/加载中/失败」收进持有类（见 ChatDetailAiResultState.kt）
    val aiResults = remember { ChatDetailAiResultState() }
    // 8.47：消息分类（纯本地词典统计）
    LaunchedEffect(aiPanels.showMessageClassify, state.chat?.id) {
        if (!aiPanels.showMessageClassify) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        aiResults.classifyLoading = true
        aiResults.classifyFailed = false
        aiResults.chatClassifications = emptyList()
        // G73：经端口调用，不再自己抓 app 数据库单例
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { viewModel.aiChatClassificationSource.classify(chatId) }
        }
        aiResults.classifyLoading = false
        aiResults.classifyFailed = result.isFailure
        result.getOrNull()?.let { aiResults.chatClassifications = it }
    }
    LaunchedEffect(aiResults.emotionReplyRequested, state.chat?.id) {
        if (!aiResults.emotionReplyRequested) return@LaunchedEffect
        aiResults.emotionReplyRequested = false
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
    LaunchedEffect(aiPanels.showConversationProfile, state.chat?.id) {
        if (!aiPanels.showConversationProfile) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        aiResults.conversationProfileLoading = true
        aiResults.conversationProfileFailed = false
        // G73：经 ViewModel 暴露的端口调用，不再自己抓 app 数据库单例
        val built = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiConversationProfileSource.build(chatId)
        }
        if (built.local.messageCount > 0 || !built.narrative.isNullOrBlank()) {
            aiResults.conversationProfile = built
        } else {
            aiResults.conversationProfileFailed = true
        }
        aiResults.conversationProfileLoading = false
    }
    LaunchedEffect(aiPanels.showWeeklyReport, state.chat?.id) {
        if (!aiPanels.showWeeklyReport) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        aiResults.weeklyReportLoading = true
        aiResults.weeklyReportFailed = false
        // G73：经端口调用，不再自己抓 app 数据库单例
        aiResults.weeklyReport = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiWeeklyReportSource.generate(chatId)
        }
        aiResults.weeklyReportFailed = aiResults.weeklyReport == null
        aiResults.weeklyReportLoading = false
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
        userId = com.maodouchat.session.CurrentSession.ownerUserId(),
        chatId = state.chat?.id,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    LaunchedEffect(searchResults, search.searchIndex) {
        val target = searchResults.getOrNull(search.searchIndex) ?: return@LaunchedEffect
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

    LaunchedEffect(search.searchMode, search.searchScope, search.searchWindow) {
        search.searchIndex = 0
    }

    LaunchedEffect(searchResults.size) {
        if (searchResults.isEmpty()) search.searchIndex = 0
        else if (search.searchIndex >= searchResults.size) search.searchIndex = searchResults.lastIndex
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
        showSearchBar = search.showSearchBar,
        showAiSummaryScopeDialog = aiPanels.showAiSummaryScopeDialog,
        onDismissAiSummaryScope = { aiPanels.showAiSummaryScopeDialog = false },
        showConversationProfile = aiPanels.showConversationProfile,
        onDismissConversationProfile = { aiPanels.showConversationProfile = false },
        conversationProfile = aiResults.conversationProfile,
        conversationProfileLoading = aiResults.conversationProfileLoading,
        conversationProfileFailed = aiResults.conversationProfileFailed,
        showWeeklyReport = aiPanels.showWeeklyReport,
        onDismissWeeklyReport = { aiPanels.showWeeklyReport = false },
        weeklyReport = aiResults.weeklyReport,
        weeklyReportLoading = aiResults.weeklyReportLoading,
        weeklyReportFailed = aiResults.weeklyReportFailed,
        showMessageClassify = aiPanels.showMessageClassify,
        onDismissMessageClassify = { aiPanels.showMessageClassify = false },
        chatClassifications = aiResults.chatClassifications,
        classifyLoading = aiResults.classifyLoading,
        classifyFailed = aiResults.classifyFailed,
    )

    if (schedule.showDisappearDialog && !state.chatIsGroup && state.isSecretChat != true) {
        DisappearingMessagesDialog(
            selectedSeconds = state.disappearingMessageSeconds,
            isUpdating = state.isUpdatingDisappearing,
            onSelect = { seconds ->
                schedule.showDisappearDialog = false
                viewModel.setDisappearingMessages(seconds)
            },
            onDismiss = { schedule.showDisappearDialog = false }
        )
    }

    // 8.46：会话免打扰时段（本地 per-chat 静音窗）
    if (schedule.showQuietHoursDialog && state.chat?.id?.isNotBlank() == true) {
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
                schedule.showQuietHoursDialog = false
            },
            onDismiss = { schedule.showQuietHoursDialog = false }
        )
    }

    // 1.02：临时静音至（本地，1/8/24 小时）
    // G83：静音至对话框（45 行）抽到 ChatDetailChatSettingsDialogs.kt，纯搬移不改判断。
    if (schedule.showSilentUntilDialog && state.chat?.id?.isNotBlank() == true) {
        // 9.219：捕获局部 chatId（同免打扰段，回调延迟执行防会话删除竞态）
        val chatIdForSilent = state.chat?.id ?: return
        ChatSilentUntilDialog(
            chatId = chatIdForSilent,
            onDismiss = { schedule.showSilentUntilDialog = false },
        )
    }

    // 8.48：稍后提醒列表（查看/取消）
    // G82：稍后提醒列表对话框（65 行）抽到 ChatDetailReminderListDialog.kt，纯搬移不改判断。
    if (schedule.showReminderList && state.chat?.id?.isNotBlank() == true) {
        // 9.219：捕获局部 chatId（同免打扰段，回调延迟执行防会话删除竞态）
        val reminderChatId = state.chat?.id ?: return
        var reminderList by remember(schedule.showReminderList, reminderChatId) {
            mutableStateOf(viewModel.listRemindersForChat(reminderChatId))
        }
        ChatDetailReminderListDialog(
            reminders = reminderList,
            chatId = reminderChatId,
            onDismiss = { schedule.showReminderList = false },
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

    if (schedule.showScheduleDialog) {
        ScheduleSendDialog(
            onPickDelay = { delayMs ->
                schedule.showScheduleDialog = false
                viewModel.scheduleMessage(delayMs)
            },
            onPickAt = { sendAt ->
                schedule.showScheduleDialog = false
                viewModel.scheduleMessageAt(sendAt)
            },
            onDismiss = { schedule.showScheduleDialog = false },
            // 1.07：重复定时发送（1.21：支持次数上限；1.62：工作日重复）
            onPickRepeat = { intervalMs, repeatCount, weekdaysOnly ->
                schedule.showScheduleDialog = false
                viewModel.scheduleMessageRepeat(intervalMs, repeatCount, weekdaysOnly)
            }
        )
    }

    if (schedule.showScheduledList && state.scheduledMessages.isNotEmpty()) {
        ScheduledMessagesListSheet(
            items = state.scheduledMessages,
            onCancel = { viewModel.cancelScheduledMessage(it) },
            onReschedule = { id ->
                schedule.rescheduleTargetId = id
            },
            // 1.168：立即发送
            onSendNow = { viewModel.sendScheduledNow(it) },
            // 1.174：全部取消
            onCancelAll = { viewModel.cancelAllScheduledMessages() },
            onDismiss = { schedule.showScheduledList = false }
        )
    }

    schedule.rescheduleTargetId?.let { targetId ->
        // 1.43：重排时可编辑文案（初值取当前待发文案）
        var rescheduleTextDraft by remember(targetId) {
            mutableStateOf(state.scheduledMessages.firstOrNull { it.id == targetId }?.text.orEmpty())
        }
        ScheduleSendDialog(
            titleRes = R.string.schedule_reschedule_title,
            initialText = rescheduleTextDraft,
            onTextEdited = { rescheduleTextDraft = it },
            onPickDelay = { delayMs ->
                schedule.rescheduleTargetId = null
                // 1.46：清空编辑框时保留原文（null 表示不改文案）
                viewModel.rescheduleScheduledMessage(targetId, delayMs, rescheduleTextDraft.takeIf { it.isNotBlank() })
            },
            onPickAt = { sendAt ->
                schedule.rescheduleTargetId = null
                viewModel.rescheduleScheduledMessageAt(targetId, sendAt, rescheduleTextDraft.takeIf { it.isNotBlank() })
            },
            onDismiss = { schedule.rescheduleTargetId = null }
        )
    }

    // G81：设置聊天锁对话框（71 行）抽到 ChatDetailSetChatLockDialog.kt，纯搬移不改判断。
    if (chatLock.showSetChatLock) {
        ChatDetailSetChatLockDialog(
            pinDraft = chatLock.setLockPinDraft,
            pinConfirmDraft = chatLock.setLockPinConfirm,
            errorMessage = setLockError,
            contactDisplayName = state.contact.displayName,
            onPinDraftChange = { chatLock.setLockPinDraft = it },
            onPinConfirmDraftChange = { chatLock.setLockPinConfirm = it },
            onErrorMessageChange = { setLockError = it },
            onDismiss = { chatLock.showSetChatLock = false },
            onSaved = { pin -> viewModel.setChatLockPin(pin) },
        )
    }

    // G83：解除聊天锁对话框（35 行）抽到 ChatDetailChatSettingsDialogs.kt，纯搬移不改判断。
    if (chatLock.showDisableChatLock) {
        ChatDisableChatLockDialog(
            pinDraft = chatLock.disableLockPinDraft,
            contactDisplayName = state.contact.displayName,
            onPinDraftChange = { chatLock.disableLockPinDraft = it },
            onDismiss = { chatLock.showDisableChatLock = false },
            onRemoveLock = { pin -> viewModel.removeChatLock(pin) },
        )
    }

    if (aiPanels.showGifSearch) {
        GifSearchDialog(
            onPickUri = { uri, gifId ->
                if (gifId != null) {
                    com.maodouchat.util.GifSearchPreferences.recordRecent(context, gifId)
                }
                viewModel.sendGif(uri)
                aiPanels.showGifSearch = false
            },
            onBrowseFiles = { pickers.gif.launch(arrayOf("image/gif")) },
            onRequestPermission = {
                val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                pickers.gifMediaPermission.launch(permission)
            },
            onDismiss = { aiPanels.showGifSearch = false }
        )
    }

    // G83：联系人操作对话框（45 行）抽到 ChatDetailChatSettingsDialogs.kt，纯搬移不改判断。
    if (contactSheets.showContactActions && !state.chatIsGroup) {
        ChatContactActionsDialog(
            contactDisplayName = state.contact.displayName,
            isContactBlocked = state.isContactBlocked,
            isBlockingContact = state.isBlockingContact,
            isGroup = state.chatIsGroup,
            onDismiss = { contactSheets.showContactActions = false },
            onViewProfile = { contactSheets.showContactProfile = true },
            onToggleBlock = {
                if (state.isContactBlocked) viewModel.unblockContact() else viewModel.blockContact()
            },
            onReport = { aiPanels.showReportContactDialog = true },
        )
    }

    if (contactSheets.showContactProfile && !state.chatIsGroup) {
        ContactProfileSheet(
            contact = state.contact,
            isBlocked = state.isContactBlocked,
            isBlocking = state.isBlockingContact,
            hideCalls = state.isSecretChat == true,
            onDismiss = { contactSheets.showContactProfile = false },
            onMessage = { contactSheets.showContactProfile = false },
            onVoiceCall = {
                contactSheets.showContactProfile = false
                requestVoiceCallPermission(context, pickers.voiceCallPermission::launch, state.contact.id, state.contact.name, onVoiceCall)
            },
            onVideoCall = {
                contactSheets.showContactProfile = false
                requestVideoCallPermissions(context, pickers.videoCallPermission::launch, state.contact.id, state.contact.name, onVideoCall)
            },
            onToggleBlock = {
                if (state.isContactBlocked) viewModel.unblockContact() else viewModel.blockContact()
            },
            onReport = {
                contactSheets.showContactProfile = false
                aiPanels.showReportContactDialog = true
            }
        )
    }

    if (aiPanels.showReportContactDialog) {
        ReportDialog(
            title = stringResource(R.string.chat_report_user),
            onDismiss = { aiPanels.showReportContactDialog = false },
            onReport = { reason, description ->
                viewModel.reportContact(reason, description)
                aiPanels.showReportContactDialog = false
            }
        )
    }

    GroupCallTypeDialog(
        visible = groupCall.showGroupCallTypeDialog,
        candidateCount = state.chat?.participants.orEmpty().count { it.id != state.currentUserId },
        onPick = { type ->
            groupCall.showGroupCallTypeDialog = false
            val candidates = state.chat?.participants.orEmpty().filter { it.id != state.currentUserId }
            if (candidates.size <= com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS - 1) {
                viewModel.startGroupCallFromChat(type)
            } else {
                // 超过 mesh 上限：进入选成员（第二步）。五步复位收在持有类里，避免只清一半。
                groupCall.chooseType(type)
            }
        },
        onDismiss = { groupCall.showGroupCallTypeDialog = false },
    )

    // G78：群通话成员选择对话框（123 行）抽到 ChatDetailGroupCallMemberDialog.kt，纯搬移不改判断。
    // 三个 rememberSaveable 开关的所有权留在 Route（打开入口也在这里），以「值 + setter」传入。
    if (groupCall.showGroupCallMemberDialog) {
        ChatDetailGroupCallMemberDialog(
            chat = state.chat,
            currentUserId = state.currentUserId,
            pendingCallType = groupCall.pendingGroupCallType,
            selectedMemberIds = groupCall.selectedGroupCallMemberIds,
            memberQuery = groupCall.groupCallMemberSearch,
            onDismiss = { groupCall.showGroupCallMemberDialog = false },
            onPendingCallTypeChange = { groupCall.pendingGroupCallType = it },
            onSelectedMemberIdsChange = { groupCall.selectedGroupCallMemberIds = it },
            onMemberQueryChange = { groupCall.groupCallMemberSearch = it },
            onStartGroupCall = { type, ids -> viewModel.startGroupCallFromChat(type, ids) },
        )
    }

    if (chatLockPending) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (secretGate.deviceRiskLocked) {
        SecretNewDeviceRiskLocked(onRegisterClick = { secretGate.showDeviceRiskDialog = true })
    } else if (chatLockBlocking) {
        ChatLockGate(
            chatName = state.contact.displayName.ifBlank {
                state.chat?.groupName.orEmpty().ifBlank { stringResource(R.string.chat_this_chat) }
            },
            onUnlock = { pin, onResult -> viewModel.unlockChatWithPin(pin, onResult) },
            onForgotPin = { chatLock.showForgotChatLockConfirm = true }
        )
        ForgotChatLockConfirmDialog(
            visible = chatLock.showForgotChatLockConfirm,
            onDismiss = { chatLock.showForgotChatLockConfirm = false },
            onConfirm = {
                chatLock.showForgotChatLockConfirm = false
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
        visible = flows.showLiveLocationDuration,
        onPick = { ms ->
            flows.showLiveLocationDuration = false
            viewModel.sendLiveLocation(ms)
        },
        onDismiss = { flows.showLiveLocationDuration = false },
    )


    SecretChatConfirmDialog(
        visible = flows.showSecretChatConfirm,
        onConfirm = {
            flows.showSecretChatConfirm = false
            viewModel.startSecretChat()
        },
        onDismiss = { flows.showSecretChatConfirm = false },
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
                        IconButton(onClick = { groupCall.showGroupCallTypeDialog = true }) {
                            Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_group_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        if (RuntimeFlags.isEnabled(context, RuntimeFlags.SAFETY_CODE)) {
                            IconButton(onClick = { viewModel.showSafetyCodeDialog() }) { Icon(Icons.Outlined.Security, contentDescription = stringResource(R.string.chat_safety_code), tint = if (state.identityWarning == null) Primary else UnreadRed, modifier = Modifier.size(26.dp)) }
                        }
                        if (state.isSecretChat != true) {
                            IconButton(onClick = { requestVoiceCallPermission(context, pickers.voiceCallPermission::launch, state.contact.id, state.contact.name, onVoiceCall) }) { Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.chat_voice_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                            IconButton(onClick = { requestVideoCallPermissions(context, pickers.videoCallPermission::launch, state.contact.id, state.contact.name, onVideoCall) }) { Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_video_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
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
                                    onClick = { showChatOverflow = false; contactSheets.showContactActions = true }
                                )
                                if (state.isSecretChat != true) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.disappear_menu)) },
                                        onClick = { showChatOverflow = false; schedule.showDisappearDialog = true }
                                    )
                                }
                            }
                            // 8.46：会话免打扰时段（本地 per-chat 静音窗，单聊/群聊均可用）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_quiet_hours_menu)) },
                                onClick = { showChatOverflow = false; schedule.showQuietHoursDialog = true }
                            )
                            // 1.02：临时静音至（1/8/24 小时，本地）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_silent_until_menu)) },
                                onClick = { showChatOverflow = false; schedule.showSilentUntilDialog = true }
                            )
                            // 8.48：稍后提醒列表（查看/取消）
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.message_reminder_list_menu)) },
                                onClick = { showChatOverflow = false; schedule.showReminderList = true }
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
                                        chatLock.disableLockPinDraft = ""
                                        chatLock.showDisableChatLock = true
                                    } else {
                                        chatLock.setLockPinDraft = ""
                                        chatLock.setLockPinConfirm = ""
                                        setLockError = null
                                        chatLock.showSetChatLock = true
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
                                        flows.showSecretChatConfirm = true
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
                                onClick = { showChatOverflow = false; search.showSearchBar = !search.showSearchBar }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_jump_date)) },
                                onClick = { showChatOverflow = false; drafts.showDateJumpDialog = true }
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
            appearance.customWallpaperUri?.let { uri ->
                coil.compose.AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 9.205：TG 风格涂鸦纹理——叠加在默认与颜色壁纸之上（TG 是颜色+图案叠加），
            // 仅当用户选了自定义图片壁纸时不叠加，避免盖住用户自选图片
            if (appearance.customWallpaperUri == null) {
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
            if (flows.showAnnouncementBanner && state.chatIsGroup) {
                val announcement = state.chat?.groupAnnouncement?.trim()
                if (!announcement.isNullOrBlank()) {
                    GroupAnnouncementBanner(
                        announcement = announcement,
                        onOpen = { flows.showAnnouncementDialog = true },
                        onDismiss = { flows.showAnnouncementBanner = false }
                    )
                }
            }
            if (state.scheduledMessages.isNotEmpty()) {
                ScheduledMessagesBanner(
                    items = state.scheduledMessages,
                    onCancel = { viewModel.cancelScheduledMessage(it) },
                    onReschedule = { id -> schedule.rescheduleTargetId = id },
                    onViewAll = { schedule.showScheduledList = true }
                )
            }
            if (!state.chatIsGroup && state.disappearingMessageSeconds > 0) {
                DisappearingMessagesBanner(
                    seconds = state.disappearingMessageSeconds,
                    onChange = {
                        if (state.isSecretChat != true) schedule.showDisappearDialog = true
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
                visible = search.showSearchBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ChatSearchBar(
                    query = search.searchQuery,
                    mode = search.searchMode,
                    scope = search.searchScope,
                    window = search.searchWindow,
                    resultIndex = search.searchIndex,
                    resultCount = searchResults.size,
                    semanticCandidateCount = semanticCandidates.size,
                    isSemanticSearching = state.isSemanticSearching,
                    semanticSearchQuery = state.semanticSearchQuery,
                    semanticSearchResultCount = state.semanticSearchResultIds.size,
                    semanticSearchError = state.semanticSearchError,
                    aiEnabled = chatAiSurfacesVisible,
                    onQueryChange = { query ->
                        search.searchQuery = query
                        search.searchIndex = 0
                        if (search.searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
                    },
                    onModeChange = { mode ->
                        search.searchMode = mode
                        search.searchIndex = 0
                        viewModel.clearSemanticSearch()
                    },
                    onScopeChange = { scope ->
                        search.searchScope = scope
                        search.searchIndex = 0
                        if (search.searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
                    },
                    onWindowChange = { window ->
                        search.searchWindow = window
                        search.searchIndex = 0
                        if (search.searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
                    },
                    onSemanticSearch = {
                        search.searchIndex = 0
                        viewModel.requestSemanticSearch(search.searchQuery, semanticCandidates.map(Message::id))
                    },
                    onNextResult = { search.searchIndex = (search.searchIndex + 1) % searchResults.size },
                    onClose = {
                        search.showSearchBar = false
                        search.searchQuery = ""
                        search.searchIndex = 0
                        search.searchMode = ChatSearchMode.KEYWORD
                        search.searchScope = ChatSearchScope.ALL
                        search.searchWindow = ChatSearchWindow.ALL
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
                    selectedIds = drafts.selectedMessageIds,
                    chatIsGroup = state.chatIsGroup,
                    myMemberRole = state.myMemberRole,
                    pinnedMessageIds = remember(state.pinnedMessages) { state.pinnedMessages.map { it.messageId }.toSet() },
                    isSecretChat = state.isSecretChat == true,
                    preparingAttachmentMessageIds = state.preparingAttachmentMessageIds,
                    onSelectAll = { drafts.selectedMessageIds = it },
                    onClearSelection = { drafts.selectedMessageIds = emptySet() },
                    onForward = { msgs ->
                        messageActions.messagesToForward = msgs
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
                        selectedMessageIds = drafts.selectedMessageIds,
                        messageSelectionMode = messageSelectionMode,
                        animatingMessageId = particles.animatingMessageId,
                        searchResults = searchResults,
                        searchIndex = search.searchIndex,
                        showSearchBar = search.showSearchBar,
                        localSafetyEnabled = aiSafety.localSafetyEnabled,
                        navigationHighlightMessageId = navigationHighlightMessageId,
                        dismissedSafetyMessageIds = aiSafety.dismissedSafetyMessageIds,
                        messagesById = messagesById,
                        resolveSenderName = { msg, isOwn -> resolveSenderName(msg, isOwn) },
                        viewModel = viewModel,
                        onBubblePlaced = { id, bounds -> bubbleBounds[id] = bounds },
                        onBubbleRemoved = { id -> bubbleBounds.remove(id) },
                        onShowFullscreenImage = { media.fullScreenImage = it },
                        onShowFullscreenVideo = { media.fullScreenVideo = it },
                        onCopyTranscript = { transcript ->
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranscriptLabel, transcript))
                            Toast.makeText(context, context.getString(R.string.chat_transcript_copied), Toast.LENGTH_SHORT).show()
                        },
                        onReplyTo = { msg -> replyTarget = msg },
                        onDismissSafetyForMessage = { id -> aiSafety.dismissSafetyForMessage(context, id) },
                        onToggleSelection = { drafts.selectedMessageIds = it },
                        onRetryMessage = { msg -> messageActions.messageToRetry = msg },
                        onMessageActions = { msg -> messageActions.messageToActions = msg },
                        onOpenProfile = { userId -> onOpenProfile?.invoke(userId) },
                        onShowReadReceipts = { msg -> messageActions.messageForReadReceipts = msg },
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
                    visible = !scroll.isNearBottom,
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.86f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 12.dp)
                ) {
                    Box {
                        FloatingActionButton(
                            onClick = {
                                scroll.pendingNewMessageCount = 0
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
                        if (scroll.pendingNewMessageCount > 0) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(20.dp)
                                    .background(UnreadRed, CircleShape)
                            ) {
                                Text(
                                    text = if (scroll.pendingNewMessageCount > 99) "99+" else scroll.pendingNewMessageCount.toString(),
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
                        schedule.showScheduleDialog = true
                    }
                },
                onOpenConversationProfile = { aiPanels.showConversationProfile = true },
                onOpenWeeklyReport = { aiPanels.showWeeklyReport = true },
                onEmotionReply = { aiResults.emotionReplyRequested = true },
                onOpenMessageClassify = { aiPanels.showMessageClassify = true },
                isSecretChat = secretActive,
                contactCardTargets = state.forwardTargets,
                onLoadForwardTargets = { viewModel.loadForwardTargets() },
                onSendContactCard = { userId, name -> viewModel.sendContactCard(userId, name) },
                onSendImage = {
                    sendPending.viewOnce = false
                    sendPending.spoiler = false
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { pickers.image.launch("image/*") }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                onSendViewOnceImage = {
                    if (state.chat?.isGroup == true) {
                        Toast.makeText(context, context.getString(R.string.view_once_direct_only), Toast.LENGTH_SHORT).show()
                    } else {
                        sendPending.viewOnce = true
                        sendPending.spoiler = false
                        listScrollScope.launch {
                            kotlinx.coroutines.yield()
                            runCatching { pickers.image.launch("image/*") }
                                .onFailure {
                                    Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                },
                onSendSpoilerImage = {
                    sendPending.spoiler = true
                    sendPending.viewOnce = false
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { pickers.image.launch("image/*") }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                // 8.48：从系统剪贴板粘贴图片直接进入确认发送流程
                onPasteFromClipboard = {
                    // 8.48 修复：重置阅后即焚/剧透意图——否则上一次取消选图器残留的标志
                    // 会泄漏到后续普通视频/图片发送
                    sendPending.viewOnce = false
                    sendPending.spoiler = false
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
                                sendPending.imageConfirm = PendingImageSend(resultUri, false, false)
                            } else {
                                Toast.makeText(context, context.getString(R.string.chat_clipboard_no_image), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onSendVideo = {
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { pickers.video.launch("video/*") }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_video_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                onSendFile = {
                    listScrollScope.launch {
                        kotlinx.coroutines.yield()
                        runCatching { pickers.file.launch(arrayOf("*/*")) }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                onSendGif = { aiPanels.showGifSearch = true },
                onSendSticker = { viewModel.sendSticker(it) },
                onSendLocation = {
                    if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) viewModel.sendCurrentLocation()
                    else {
                        flows.pendingLiveLocationPermission = false
                        pickers.locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                onSendLiveLocation = {
                    if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) flows.showLiveLocationDuration = true
                    else {
                        flows.pendingLiveLocationPermission = true
                        pickers.locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                onRecordStart = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        pickers.recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
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
                onAiSummarize = { aiPanels.showAiSummaryScopeDialog = true },
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
    val groupAnnouncementText = state.chat?.groupAnnouncement?.trim().orEmpty()
    GroupAnnouncementDialog(
        visible = flows.showAnnouncementDialog, announcement = groupAnnouncementText,
        onCopy = {
            if (groupAnnouncementText.isNotBlank()) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.group_announcement_copy), groupAnnouncementText))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            }
            flows.showAnnouncementDialog = false
        },
        onDismiss = { flows.showAnnouncementDialog = false },
    )

    // G86：批量删除确认对话框（50 行）抽到 ChatDetailBatchDeleteDialog.kt，纯搬移不改判断。
    if (showBatchDeleteConfirm) {
        ChatDetailBatchDeleteDialog(
            selectedMessages = selectedMessages,
            currentUserId = state.currentUserId,
            onDelete = { ids -> viewModel.deleteMessagesBatch(ids) },
            onDismiss = { showBatchDeleteConfirm = false },
            onSelectionCleared = { drafts.selectedMessageIds = emptySet() },
        )
    }

    messageActions.messageToActions?.let { message ->
        ChatDetailMessageActionsSheet(
            onMessageToActionsDismiss = { messageActions.messageToActions = null },
            chatCopiedMsg = chatCopiedMsg,
            chatTranslationCopiedMsg = chatTranslationCopiedMsg,
            chatTranscriptCopiedMsg = chatTranscriptCopiedMsg,
            chatClipboardMessageLabel = chatClipboardMessageLabel,
            chatClipboardTranslationLabel = chatClipboardTranslationLabel,
            chatClipboardTranscriptLabel = chatClipboardTranscriptLabel,
            chatAiSurfacesVisible = chatAiSurfacesVisible,
            message = message,
            state = state,
            viewModel = viewModel,
            context = context,
            senderName = resolveSenderName(message),
            onMessageToActions = { messageActions.messageToActions = it },
            onMessageToDelete = { messageActions.messageToDelete = it },
            onMessageToRevoke = { messageActions.messageToRevoke = it },
            onMessagesToForward = { messageActions.messagesToForward = it },
            onMessageToEdit = { messageActions.messageToEdit = it },
            onMessageToRemind = { messageActions.messageToRemind = it },
            onMessageToTranslate = { messageActions.messageToTranslate = it },
            onMessageToReport = { messageActions.messageToReport = it },
            onMessageForReadReceipts = { messageActions.messageForReadReceipts = it },
            onMessageToAnalyzeImage = { messageActions.messageToAnalyzeImage = it },
            onMessageToAnalyzeFile = { messageActions.messageToAnalyzeFile = it },
            onEditDraft = { drafts.editDraft = it },
            onReplyTarget = { replyTarget = it },
            onSelectedMessageIds = { drafts.selectedMessageIds = it },
        )
    }

    messageActions.messageToAnalyzeImage?.let { message ->
        AiImageAnalysisModeDialog(
            onSelect = { mode ->
                messageActions.messageToAnalyzeImage = null
                viewModel.requestAiImageAnalysis(message.id, mode)
            },
            onDismiss = { messageActions.messageToAnalyzeImage = null }
        )
    }

    // 8.41：消息「稍后提醒」时间选择
    messageActions.messageToRemind?.let { message ->
        MessageReminderTimeDialog(
            onPick = { delayMs ->
                messageActions.messageToRemind = null
                viewModel.scheduleMessageReminder(message, System.currentTimeMillis() + delayMs)
            },
            onDismiss = { messageActions.messageToRemind = null }
        )
    }

    // G79：图片发送前预览（47 行）抽到 ChatDetailSendPreviews.kt，纯搬移不改判断。
    sendPending.imageConfirm?.let { pending ->
        ImageSendPreviewDialog(
            pending = pending,
            onDismiss = { sendPending.imageConfirm = null },
            onRechoose = { viewOnce, spoiler ->
                sendPending.viewOnce = viewOnce
                sendPending.spoiler = spoiler
                listScrollScope.launch {
                    kotlinx.coroutines.yield()
                    runCatching { pickers.image.launch("image/*") }
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
    sendPending.videoConfirm?.let { pending ->
        VideoSendPreviewDialog(
            pending = pending,
            onDismiss = { sendPending.videoConfirm = null },
            onSendVideo = { p -> viewModel.sendVideo(p.uri) },
            onSendSpoilerVideo = { p -> viewModel.sendSpoilerVideo(p.uri) },
            onSendViewOnceVideo = { p -> viewModel.sendViewOnceVideo(p.uri) },
        )
    }

    messageActions.messageToAnalyzeFile?.let { message ->
        AiFileAnalysisModeDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            onSelect = { mode ->
                messageActions.messageToAnalyzeFile = null
                if (mode == AiFileAnalysisMode.SUMMARIZE) {
                    viewModel.requestAiFileAnalysis(message.id, mode)
                } else {
                    drafts.fileQuestionDraft = ""
                    messageActions.fileQuestionMessage = message
                }
            },
            onDismiss = { messageActions.messageToAnalyzeFile = null }
        )
    }

    messageActions.fileQuestionMessage?.let { message ->
        AiFileQuestionDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            question = drafts.fileQuestionDraft,
            onQuestionChange = { drafts.fileQuestionDraft = it.take(500) },
            onSubmit = {
                viewModel.requestAiFileAnalysis(message.id, AiFileAnalysisMode.QUESTION, drafts.fileQuestionDraft)
                messageActions.fileQuestionMessage = null
                drafts.fileQuestionDraft = ""
            },
            onDismiss = {
                messageActions.fileQuestionMessage = null
                drafts.fileQuestionDraft = ""
            }
        )
    }

    messageActions.messageToTranslate?.let { message ->
        TranslationLanguageDialog(
            translatedLanguages = message.parsedMeta().translations.keys,
            onDismiss = { messageActions.messageToTranslate = null },
            onSelect = { language ->
                viewModel.requestMessageTranslation(message.id, language)
                messageActions.messageToTranslate = null
            }
        )
    }

    messageActions.messageToReport?.let { msg ->
        ReportDialog(
            title = stringResource(R.string.chat_report_message),
            onDismiss = { messageActions.messageToReport = null },
            onReport = { reason, description ->
                viewModel.reportMessage(msg.id, reason, description)
                messageActions.messageToReport = null
            }
        )
    }

    if (drafts.showDateJumpDialog) {
        DateJumpDialog(
            onDismiss = { drafts.showDateJumpDialog = false },
            onJump = { dayStartMillis ->
                drafts.showDateJumpDialog = false
                viewModel.jumpToDate(dayStartMillis)
            }
        )
    }

    // G332：已读回执面板 176 行搬进 `ChatDetailReadReceiptsSheet.kt`。
    messageActions.messageForReadReceipts?.let { receiptMessage ->
        ChatDetailReadReceiptsSheet(
            messageId = receiptMessage.id,
            receipts = state.readReceipts,
            isLoading = state.isLoadingReadReceipts,
            onOpenProfile = onOpenProfile,
            onDismiss = {
                messageActions.messageForReadReceipts = null
                viewModel.clearReadReceipts()
            },
        )
    }

    // 长按撤回消息确认弹窗（带粒子动效）
    RevokeMessageConfirmDialog(
        visible = messageActions.messageToRevoke != null,
        sentAtMillis = messageActions.messageToRevoke?.timestamp ?: 0L,
        onRevoke = {
            messageActions.messageToRevoke?.let { startParticleEffect(it, ParticleAction.REVOKE) }
            messageActions.messageToRevoke = null
        },
        onDismiss = { messageActions.messageToRevoke = null },
    )

    // 长按删除消息确认弹窗
    // messageActions.messageToDelete 是 by remember 委托属性，不能智能转换——先取局部值（G159b）
    val pendingDelete = messageActions.messageToDelete
    DeleteMessageConfirmDialog(
        visible = pendingDelete != null,
        isOwn = pendingDelete?.senderId == state.currentUserId,
        isForwardable = pendingDelete != null && isMessageForwardable(
            pendingDelete.type,
            isSecretChat = state.isSecretChat == true,
            forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK)
        ),
        onDelete = {
            pendingDelete?.let { startParticleEffect(it, ParticleAction.DELETE) }
            messageActions.messageToDelete = null
        },
        onForward = {
            pendingDelete?.let {
                messageActions.messagesToForward = listOf(it)
                viewModel.loadForwardTargets()
            }
            messageActions.messageToDelete = null
        },
        onDismiss = { messageActions.messageToDelete = null },
    )

    // G80：消息操作弹窗（58 行）抽到 ChatDetailMessageActionsDialog.kt，纯搬移不改判断。
    messageActions.messageToCopy?.let { msg ->
        ChatDetailMessageActionsDialog(
            msg = msg,
            currentUserId = state.currentUserId,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { messageActions.messageToCopy = null },
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, msg.parsedContent()))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onForward = {
                messageActions.messagesToForward = listOf(msg)
                viewModel.loadForwardTargets()
            },
            onEdit = {
                drafts.editDraft = msg.parsedContent()
                messageActions.messageToEdit = msg
            },
            onRevoke = { messageActions.messageToRevoke = msg },
            onDelete = { startParticleEffect(msg, ParticleAction.DELETE) },
        )
    }

    EditMessageDialog(
        visible = messageActions.messageToEdit != null,
        draft = drafts.editDraft,
        onDraftChange = { drafts.editDraft = it.take(2000) },
        onSave = {
            messageActions.messageToEdit?.let { viewModel.editTextMessage(it.id, drafts.editDraft) }
            messageActions.messageToEdit = null
        },
        onDismiss = { messageActions.messageToEdit = null },
    )

    // 转发目标选择弹窗
    // G75：转发目标选择弹窗（235 行）抽到 ChatDetailForwardPicker.kt，纯搬移不改判断。
    if (messageActions.messagesToForward.isNotEmpty()) {
        ChatDetailForwardPicker(
            messages = messageActions.messagesToForward,
            forwardTargets = state.forwardTargets,
            currentUserId = state.currentUserId,
            onCancel = { messageActions.messagesToForward = emptyList() },
            onForwardBatch = { msgs, targets, note ->
                viewModel.forwardMessagesBatch(msgs, targets, note)
            },
            onSendTextToChat = { chatId, body -> viewModel.sendTextToChat(chatId, body) },
            onSelectionCleared = { drafts.selectedMessageIds = emptySet() },
            onLoadForwardTargets = { viewModel.loadForwardTargets() },
            secretSource = secretActive,
        )
    }

    // 重发失败消息弹窗
    RetryMessageDialog(
        visible = messageActions.messageToRetry != null,
        onRetry = {
            messageActions.messageToRetry?.let { viewModel.retrySendMessage(it.id) }
            messageActions.messageToRetry = null
        },
        onDelete = {
            messageActions.messageToRetry?.let { startParticleEffect(it, ParticleAction.DELETE) }
            messageActions.messageToRetry = null
        },
        onDismiss = { messageActions.messageToRetry = null },
    )

    // G76：全屏图片/视频查看器（207 行）抽到 ChatDetailFullscreenMedia.kt，纯搬移不改判断。
    media.fullScreenImage?.let { msg ->
        FullscreenImageDialog(
            msg = msg,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { media.fullScreenImage = null },
        )
    }

    media.fullScreenVideo?.let { msg ->
        FullscreenVideoDialog(
            msg = msg,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { media.fullScreenVideo = null },
        )
    }

    // 粒子删除动效：消息泡碎裂为彩色粒子消散（Telegram 风格）
    if (particles.animatingMessageId != null && particles.states.isNotEmpty()) {
        ParticleDeleteEffect(
            particleStates = particles.states,
            onFinished = {
                val targetId = particles.animatingMessageId
                when (particles.action) {
                    ParticleAction.DELETE -> targetId?.let { viewModel.deleteMessage(it) }
                    ParticleAction.REVOKE -> targetId?.let { viewModel.revokeMessage(it) }
                    null -> Unit
                }
                particles.clear()
            }
        )
    }
    } // secret watermark Box
    } // CompositionLocalProvider (chat font scale)
}

