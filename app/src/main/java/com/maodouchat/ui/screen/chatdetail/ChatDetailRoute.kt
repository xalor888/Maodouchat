@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.maodouchat.ui.theme.LocalMotionSettings

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
    val listState = rememberLazyListState()
    val listScrollScope = rememberCoroutineScope()
    // 8.47：滚动合并执行器（B7 帧预算）——高频回底/跳转连点合并同帧请求，
    // 超距跳转瞬时 snap，避免长动画占帧（此前 4 处裸 animateScrollToItem）
    val chatListScroller = com.maodouchat.perf.rememberCoalescedScroller()
    // G335（第十三批）：滚动位置族（是否在底部附近 / 新消息徽标计数 / 自动滚动游标 /
    // 滚动到顶部触发加载更早消息）收进持有类（见 ChatDetailScrollState.kt）
    val scroll = rememberChatDetailScrollState(listState)
    val context = LocalContext.current
    ChatDetailSecretChatEffects(state, viewModel, context, onOpenSecretChat)

    // G335（第十二批）：AI 安全提示族收进持有类（见 ChatDetailAiSafetyState.kt）
    val aiSafety = rememberChatDetailAiSafetyState(context)
    // 9.150：壁纸/字号偏好改为可变状态并在 ON_RESUME 刷新——从设置页改完返回
    // 仍存活的聊天页实例不再持有陈旧背景/字号
    // G335：这三项「必须一起刷新」的不变量收进持有类（见 ChatDetailAppearanceStates.kt）
    val appearance = rememberChatDetailAppearanceState(context)
    ChatDetailSessionCipherEffects(
        viewModel = viewModel,
        state = state,
        aiSafety = aiSafety,
        appearance = appearance,
        context = context,
    )
    // G335：这一族「弹层当前操作哪条消息」的状态收进持有类（见 ChatDetailMessageActionState）。
    val messageActions = rememberChatDetailMessageActionState()
    val chatAiSurfacesVisible = rememberChatDetailAiSurfaceVisibility(
        aiEnabled = state.aiEnabled,
        context = context
    )
    // AI 入口关闭时把语义搜索降级回关键词搜索（见 ChatDetailAiSurfaceEffects.kt）。
    ChatDetailAiSurfaceEffects(chatAiSurfacesVisible, search, viewModel)
    // G335（第十四批）：全屏媒体查看状态族收进持有类（见 ChatDetailFullscreenMediaState）。
    val media = rememberChatDetailFullscreenMediaState()
    // G336（第十五批）：会话级弹窗开关族收进持有类（见 ChatDetailDialogState）。
    val dialogs = rememberChatDetailDialogState()
    // G337（第十六批）：消息引用/导航族收进持有类（见 ChatDetailMessageTargetState）。
    val targets = rememberChatDetailMessageTargetState()
    val chatSnackbarHostState = rememberChatDetailSnackbarHostState()
    // 1.11：发送名片——联系人选择对话框
    // 1.02：临时静音至对话框
    // G335：粒子动效三件套收进持有类（见 ChatDetailTransientStates.kt）
    val particles = rememberChatDetailParticleState()
    val bubbleBounds = rememberChatDetailBubbleBoundsState()
    val configuration = LocalConfiguration.current
    // 外观派生量（背景色/字号密度）收进持有类（见 ChatDetailAppearanceStates.kt），纯搬移不改判断。
    val appearanceDerived = rememberChatDetailAppearanceDerived(appearance)
    // 文案标签簇抽到 ChatDetailMessageLabels.kt，纯搬移。
    val labels = rememberChatDetailMessageLabels()
    // 消息列表派生量族收进持有类（见 ChatDetailListDerived.kt），纯搬移不改判断。
    val listDerived = rememberChatDetailListDerived(
        state = state,
        drafts = drafts,
        configuration = configuration,
        context = context,
    )
    ChatDetailMessageArrivalEffects(state, targets, context)
    // G335（第十三批）：滚动到顶部附近触发加载更早消息，派生量收进 scroll 持有类
    val headerStatus = rememberChatHeaderStatus(state, context)
    // 返回键处理抽到同包 ChatDetailBackHandler（溢出菜单/多选/搜索条按优先级关闭），纯搬移。
    ChatDetailBackHandler(dialogs = dialogs, listDerived = listDerived, search = search, drafts = drafts)
    // 搜索派生量族收进持有类（见 ChatDetailSearchDerived.kt），纯搬移不改判断。
    val searchDerived = rememberChatDetailSearchDerived(
        messages = state.messages,
        messagesById = listDerived.messagesById,
        search = search,
        semanticSearchResultIds = state.semanticSearchResultIds,
        semanticSearchQuery = state.semanticSearchQuery,
        currentUserId = state.currentUserId,
    )

    // 8.48：禁言到期重组触发器收进持有类（见 ChatDetailTransientStates.kt）
    val muteExpiry = rememberChatDetailMuteExpiryState()
    // G335：发送前待确认项收进持有类（见 ChatDetailTransientStates.kt）
    val sendPending = rememberChatDetailSendPendingState()
    // G331：9 个 ActivityResult 入口搬进 `ChatDetailPickers.kt`（连带它们各自的
    // 「为什么选这个 contract」注释）。这里只留接线。
    val pickers = rememberChatDetailPickers(
        messages = ChatDetailPermissionMessages(
            record = labels.chatPermissionRecordMsg,
            voiceCall = labels.chatPermissionVoiceCallMsg,
            videoCall = labels.chatPermissionVideoCallMsg,
            location = labels.chatPermissionLocationMsg,
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

    // 粒子动效启动器收进 ChatDetailTransientStates.kt 的 remember 接线（纯搬移不改判断）。
    val particleLauncher = rememberChatDetailParticleLauncher(particles, bubbleBounds, state.currentUserId)

    ChatDetailScrollEffects(state, scroll, chatListScroller, listState, targets, viewModel, listDerived.reversedChatItems)

    ChatDetailInfoEffects(state, viewModel, chatSnackbarHostState, context)

    // 密聊 / 阅后即焚：MediaStore 旁路截屏/录屏检测（FLAG_SECURE 之外的补充）
    // 门禁派生量族收进 ChatDetailGateDerived（见同名文件），纯搬移不改判断。
    val gate = rememberChatDetailGateDerived(state)

    // G335：密聊门禁进度收进持有类（见 ChatDetailSecretGateState.kt）
    val secretGate = rememberChatDetailSecretGateState()
    ChatDetailSecretGateEffects(
        state = state,
        secretActive = gate.secretActive,
        secretGate = secretGate,
        viewModel = viewModel,
        context = context,
    )

    // B4 本地 AI 聚合：会话画像 / 本周周报（仅非密聊会话，密聊不参与避免落可搜索缓存）
    // G335：AI 三块结果的「值/加载中/失败」收进持有类（见 ChatDetailAiResultState.kt）
    val aiResults = rememberChatDetailAiResultState()
    // G389：4 个 AI 结果副作用（消息分类/情感回复/会话画像/本周周报）抽到 ChatDetailAiResultEffects.kt，纯搬移。
    ChatDetailAiResultEffects(
        aiPanels = aiPanels,
        aiResults = aiResults,
        state = state,
        viewModel = viewModel,
    )

    ChatDetailScreenshotGuards(state = state, disappearingActive = gate.disappearingActive, secretActive = gate.secretActive, viewModel = viewModel, context = context)

    ChatDetailStatusInfoEffects(state, viewModel, chatSnackbarHostState)

    ChatDetailWindowPrivacyEffects(state = state, chatLockBlocking = gate.chatLockBlocking, context = context)
    val secretPagePayload = rememberChatDetailSecretPagePayload(state, context)

    ChatDetailNavigationEffects(state = state, search = search, searchResults = searchDerived.searchResults, reversedChatItems = listDerived.reversedChatItems, targets = targets, viewModel = viewModel, chatListScroller = chatListScroller, listState = listState, context = context)

    // G351：上半区弹窗簇（安全码/定时族/聊天锁/GIF/联系人卡片/举报/群通话 + AI 弹窗挂载）
    // 抽到 ChatDetailDialogHosts.kt，纯搬移不改判断。
    ChatDetailUpperDialogHost(
        state = state,
        viewModel = viewModel,
        search = search,
        searchResults = searchDerived.searchResults,
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

    if (gate.chatLockPending) {
        // 聊天锁加载中占位抽到同包 ChatDetailLockPendingIndicator，纯搬移不改判断。
        ChatDetailLockPendingIndicator()
    } else if (secretGate.deviceRiskLocked) {
        SecretNewDeviceRiskLocked(onRegisterClick = { secretGate.showDeviceRiskDialog = true })
    } else if (gate.chatLockBlocking) {
        ChatDetailChatLockGate(
            state = state,
            chatLock = chatLock,
            viewModel = viewModel,
        )
    } else CompositionLocalProvider(LocalDensity provides appearanceDerived.scaledDensity) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    ChatDetailRouteEntryDialogs(
        dialogs = dialogs,
        flows = flows,
        viewModel = viewModel,
        sensitiveAuthTitle = labels.sensitiveAuthTitle,
        sensitiveAuthClearHistory = labels.sensitiveAuthClearHistory,
        sensitiveAuthFailed = labels.sensitiveAuthFailed,
    )
    val chatLiquidBackdrop = rememberChatDetailLiquidBackdrop()
    CompositionLocalProvider(LocalLiquidGlassBackdrop provides chatLiquidBackdrop) {
    ChatDetailScaffold(
        snackbarHost = { ChatDetailSnackbarHost(chatSnackbarHostState) },
        topBar = {
            // G343：顶栏（标题/状态/操作/溢出菜单）抽到 ChatDetailTopBar.kt，纯搬移不改判断。
            ChatDetailTopBar(
                state = state,
                headerStatus = headerStatus,
                participantNamesById = listDerived.participantNamesById,
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
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(chatLiquidBackdrop).background(appearanceDerived.backgroundColor)) {
            // 聊天页壁纸与纹理层抽到同包 ChatDetailBackgroundLayers，纯搬移不改判断。
            ChatDetailBackgroundLayers(
                appearance = appearance,
                chatBackgroundColor = appearanceDerived.backgroundColor,
                // 9.205：用主题真实深浅替代系统深浅/palette 身份比较（TG 主题与强制模式下不再误判）
                isDarkChat = com.maodouchat.ui.theme.LocalDarkTheme.current,
            )
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            // G344：横幅栈组装抽到 ChatDetailBanners.kt 的 ChatDetailBannerStack，纯搬移不改判断。
            ChatDetailBannerStack(
                state = state,
                viewModel = viewModel,
                flows = flows,
                schedule = schedule,
                participantNamesById = listDerived.participantNamesById,
                onOpenProfile = onOpenProfile,
                chatAiSurfacesVisible = chatAiSurfacesVisible,
            )
            // G348：搜索条 + 多选工具条两块抽到 ChatDetailSearchAndSelection.kt，纯搬移不改判断。
            ChatDetailSearchSection(
                state = state,
                viewModel = viewModel,
                search = search,
                searchResults = searchDerived.searchResults,
                semanticCandidates = searchDerived.semanticCandidates,
                chatAiSurfacesVisible = chatAiSurfacesVisible,
            )
            ChatDetailSelectionSection(
                state = state,
                viewModel = viewModel,
                drafts = drafts,
                messageActions = messageActions,
                dialogs = dialogs,
                messageSelectionMode = listDerived.messageSelectionMode,
                selectedMessages = listDerived.selectedMessages,
                chatClipboardMessageLabel = labels.chatClipboardMessageLabel,
                chatCopiedMsg = labels.chatCopiedMsg,
            )

            ChatTimelinePane(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // G350：消息列表 + 回底 FAB + 空态抽到 ChatDetailTimelineSection.kt，纯搬移不改判断。
                ChatDetailTimelineSection(
                    state = state,
                    viewModel = viewModel,
                    listState = listState,
                    reversedChatItems = listDerived.reversedChatItems,
                    motion = LocalMotionSettings.current,
                    drafts = drafts,
                    messageSelectionMode = listDerived.messageSelectionMode,
                    particles = particles,
                    searchResults = searchDerived.searchResults,
                    search = search,
                    aiSafety = aiSafety,
                    targets = targets,
                    messagesById = listDerived.messagesById,
                    resolveSenderName = { msg, isOwn -> resolveChatDetailSenderName(msg, state, listDerived.participantNamesById, labels.unknownSenderLabel, labels.groupMemberLabel, isOwn) },
                    bubbleBounds = bubbleBounds,
                    media = media,
                    messageActions = messageActions,
                    scroll = scroll,
                    chatListScroller = chatListScroller,
                    chatClipboardTranscriptLabel = labels.chatClipboardTranscriptLabel,
                    onOpenProfile = onOpenProfile,
                )
            }

            // G353：输入区上方状态条带抽到 ChatDetailComposerStrips.kt，纯搬移不改判断。
            ChatDetailComposerStrips(
                state = state,
                viewModel = viewModel,
                targets = targets,
                muteExpiry = muteExpiry,
                resolveSenderName = { msg -> resolveChatDetailSenderName(msg, state, listDerived.participantNamesById, labels.unknownSenderLabel, labels.groupMemberLabel) },
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
        selectedMessages = listDerived.selectedMessages,
        resolveSenderName = { msg -> resolveChatDetailSenderName(msg, state, listDerived.participantNamesById, labels.unknownSenderLabel, labels.groupMemberLabel) },
        chatAiSurfacesVisible = chatAiSurfacesVisible,
        chatCopiedMsg = labels.chatCopiedMsg,
        chatTranslationCopiedMsg = labels.chatTranslationCopiedMsg,
        chatTranscriptCopiedMsg = labels.chatTranscriptCopiedMsg,
        onStartParticleEffect = particleLauncher,
        chatClipboardMessageLabel = labels.chatClipboardMessageLabel,
        chatClipboardTranslationLabel = labels.chatClipboardTranslationLabel,
        chatClipboardTranscriptLabel = labels.chatClipboardTranscriptLabel,
        onOpenProfile = onOpenProfile,
    )
    } // secret watermark Box
    } // CompositionLocalProvider (chat font scale)
}
