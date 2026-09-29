@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.security.findActivity
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.maodouchat.ui.component.ParticleState
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings

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
                // 8.32 修复 F2 的时间戳写入挪到 SessionCipherOccupancy（if-置值语义逐字等价）；Route 不再直连全局单例。
                com.maodouchat.crypto.SessionCipherOccupancy.refreshActiveChatOpenedAtIfUnset()
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
    // G336（第十五批）：会话级弹窗开关族收进持有类（见 ChatDetailDialogState）。
    val dialogs = rememberChatDetailDialogState()
    // G337（第十六批）：消息引用/导航族收进持有类（见 ChatDetailMessageTargetState）。
    val targets = rememberChatDetailMessageTargetState()
    val chatSnackbarHostState = remember { SnackbarHostState() }
    // 1.11：发送名片——联系人选择对话框
    // 1.02：临时静音至对话框
    // G335：粒子动效三件套收进持有类（见 ChatDetailTransientStates.kt）
    val particles = remember { ChatDetailParticleState() }
    val bubbleBounds = remember { ChatDetailBubbleBoundsState() }
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
        if (meta.forceReply && targets.replyTarget?.id != last.id) {
            targets.replyTarget = last
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
    BackHandler(enabled = dialogs.showChatOverflow || messageSelectionMode || search.showSearchBar) {
        when {
            dialogs.showChatOverflow -> dialogs.showChatOverflow = false
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

    // 8.48：禁言到期重组触发器收进持有类（见 ChatDetailTransientStates.kt）
    val muteExpiry = remember { ChatDetailMuteExpiryState() }
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
            navigationHighlightMessageId = targets.navigationHighlightMessageId,
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
        deviceHint = com.maodouchat.watermark.DeviceHint.androidId(context)
    )

    LaunchedEffect(searchResults, search.searchIndex) {
        val target = searchResults.getOrNull(search.searchIndex) ?: return@LaunchedEffect
        val targetIndex = reversedChatItems.indexOfFirst { it is ChatItem.Msg && it.message.id == target.id }
        if (targetIndex >= 0) {
            chatListScroller.scrollToItem(listState, targetIndex)
            // 1.343：搜索当前结果消息闪烁高亮（复用导航高亮机制，便于定位）
            targets.navigationHighlightMessageId = target.id
            try {
                kotlinx.coroutines.delay(1_800)
            } finally {
                if (targets.navigationHighlightMessageId == target.id) targets.navigationHighlightMessageId = null
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
       targets.navigationHighlightMessageId = targetId
        try {
            kotlinx.coroutines.delay(1_800)
        } finally {
            if (targets.navigationHighlightMessageId == targetId) targets.navigationHighlightMessageId = null
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

    // G351：上半区弹窗簇（安全码/定时族/聊天锁/GIF/联系人卡片/举报/群通话 + AI 弹窗挂载）
    // 抽到 ChatDetailDialogHosts.kt，纯搬移不改判断。
    ChatDetailUpperDialogHost(
        state = state,
        viewModel = viewModel,
        search = search,
        searchResults = searchResults,
        aiResults = aiResults,
        schedule = schedule,
        chatLock = chatLock,
        aiPanels = aiPanels,
        contactSheets = contactSheets,
        groupCall = groupCall,
        pickers = pickers,
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
    )

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
        visible = dialogs.showClearHistoryConfirm,
        onDismiss = { dialogs.showClearHistoryConfirm = false },
        onConfirm = {
            dialogs.showClearHistoryConfirm = false
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
            // G343：顶栏（标题/状态/操作/溢出菜单）抽到 ChatDetailTopBar.kt，纯搬移不改判断。
            ChatDetailTopBar(
                state = state,
                headerStatus = headerStatus,
                participantNamesById = participantNamesById,
                viewModel = viewModel,
                dialogs = dialogs,
                schedule = schedule,
                chatLock = chatLock,
                flows = flows,
                search = search,
                drafts = drafts,
                contactSheets = contactSheets,
                groupCall = groupCall,
                pickers = pickers,
                onBack = onBack,
                onOpenGroupDetail = onOpenGroupDetail,
                onOpenProfile = onOpenProfile,
                onOpenStarredMessages = onOpenStarredMessages,
                onOpenMediaCenter = onOpenMediaCenter,
                onOpenCallHistory = onOpenCallHistory,
                onVoiceCall = onVoiceCall,
                onVideoCall = onVideoCall,
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
            // G344：横幅栈组装抽到 ChatDetailBanners.kt 的 ChatDetailBannerStack，纯搬移不改判断。
            ChatDetailBannerStack(
                state = state,
                viewModel = viewModel,
                flows = flows,
                schedule = schedule,
                participantNamesById = participantNamesById,
                onOpenProfile = onOpenProfile,
                chatAiSurfacesVisible = chatAiSurfacesVisible,
            )
            // G348：搜索条 + 多选工具条两块抽到 ChatDetailSearchAndSelection.kt，纯搬移不改判断。
            ChatDetailSearchSection(
                state = state,
                viewModel = viewModel,
                search = search,
                searchResults = searchResults,
                semanticCandidates = semanticCandidates,
                chatAiSurfacesVisible = chatAiSurfacesVisible,
            )
            ChatDetailSelectionSection(
                state = state,
                viewModel = viewModel,
                drafts = drafts,
                messageActions = messageActions,
                dialogs = dialogs,
                messageSelectionMode = messageSelectionMode,
                selectedMessages = selectedMessages,
                chatClipboardMessageLabel = chatClipboardMessageLabel,
                chatCopiedMsg = chatCopiedMsg,
            )

            ChatTimelinePane(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // G350：消息列表 + 回底 FAB + 空态抽到 ChatDetailTimelineSection.kt，纯搬移不改判断。
                ChatDetailTimelineSection(
                    state = state,
                    viewModel = viewModel,
                    listState = listState,
                    reversedChatItems = reversedChatItems,
                    motion = motion,
                    drafts = drafts,
                    messageSelectionMode = messageSelectionMode,
                    particles = particles,
                    searchResults = searchResults,
                    search = search,
                    aiSafety = aiSafety,
                    targets = targets,
                    messagesById = messagesById,
                    resolveSenderName = { msg, isOwn -> resolveSenderName(msg, isOwn) },
                    bubbleBounds = bubbleBounds,
                    media = media,
                    messageActions = messageActions,
                    scroll = scroll,
                    chatListScroller = chatListScroller,
                    chatClipboardTranscriptLabel = chatClipboardTranscriptLabel,
                    onOpenProfile = onOpenProfile,
                )
            }

            // G353：输入区上方状态条带抽到 ChatDetailComposerStrips.kt，纯搬移不改判断。
            ChatDetailComposerStrips(
                state = state,
                viewModel = viewModel,
                targets = targets,
                muteExpiry = muteExpiry,
                resolveSenderName = { msg -> resolveSenderName(msg) },
            )

            // 输入区
            // G355：输入区接线抽到 ChatDetailComposerSection.kt，纯搬移不改判断。
            ChatDetailComposerSection(
                state = state,
                viewModel = viewModel,
                aiPanels = aiPanels,
                aiResults = aiResults,
                schedule = schedule,
                targets = targets,
                flows = flows,
                pickers = pickers,
                sendPending = sendPending,
                listScrollScope = listScrollScope,
                onOpenAiTasks = onOpenAiTasks,
            )
            }
        }
    }
    }

    // G352：下半区弹窗簇（公告/消息操作/AI/预览/确认/粒子等）抽到 ChatDetailDialogHosts.kt，
    // 纯搬移不改判断。
    ChatDetailLowerDialogHost(
        state = state,
        viewModel = viewModel,
        flows = flows,
        dialogs = dialogs,
        drafts = drafts,
        messageActions = messageActions,
        sendPending = sendPending,
        particles = particles,
        targets = targets,
        media = media,
        pickers = pickers,
        listScrollScope = listScrollScope,
        selectedMessages = selectedMessages,
        resolveSenderName = { msg -> resolveSenderName(msg) },
        chatAiSurfacesVisible = chatAiSurfacesVisible,
        chatCopiedMsg = chatCopiedMsg,
        chatTranslationCopiedMsg = chatTranslationCopiedMsg,
        chatTranscriptCopiedMsg = chatTranscriptCopiedMsg,
        onStartParticleEffect = { msg, action -> startParticleEffect(msg, action) },
        chatClipboardMessageLabel = chatClipboardMessageLabel,
        chatClipboardTranslationLabel = chatClipboardTranslationLabel,
        chatClipboardTranscriptLabel = chatClipboardTranscriptLabel,
        onOpenProfile = onOpenProfile,
    )
    } // secret watermark Box
    } // CompositionLocalProvider (chat font scale)
}

