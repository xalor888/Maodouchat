package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.layerBackdrop
import com.maodouchat.perf.CoalescedScroller
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalMotionSettings
import kotlinx.coroutines.CoroutineScope

/** 主内容组装：字号缩放 provider + 密聊水印框 + 入口对话框 + 液态背景 provider + Scaffold（顶栏/横幅/搜索/多选/时间线/输入区）+ 下半区对话框。 */
@Composable
internal fun ChatDetailContentPane(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    context: Context,
    appearance: ChatDetailAppearanceState,
    appearanceDerived: ChatDetailAppearanceDerived,
    dialogs: ChatDetailDialogState,
    flows: ChatDetailConversationFlowState,
    schedule: ChatDetailScheduleState,
    chatLock: ChatDetailChatLockState,
    aiPanels: ChatDetailAiPanelState,
    contactSheets: ChatDetailContactSheetState,
    groupCall: ChatDetailGroupCallState,
    pickers: ChatDetailPickers,
    search: ChatDetailSearchState,
    searchDerived: ChatDetailSearchDerived,
    aiResults: ChatDetailAiResultState,
    messageActions: ChatDetailMessageActionState,
    sendPending: ChatDetailSendPendingState,
    particles: ChatDetailParticleState,
    targets: ChatDetailMessageTargetState,
    media: ChatDetailFullscreenMediaState,
    drafts: ChatDetailDraftState,
    listDerived: ChatDetailListDerived,
    listState: LazyListState,
    listScrollScope: CoroutineScope,
    labels: ChatDetailMessageLabels,
    chatAiSurfacesVisible: Boolean,
    chatListScroller: CoalescedScroller,
    bubbleBounds: ChatDetailBubbleBoundsState,
    scroll: ChatDetailScrollState,
    aiSafety: ChatDetailAiSafetyState,
    muteExpiry: ChatDetailMuteExpiryState,
    chatSnackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenGroupDetail: (chatId: String) -> Unit,
    onOpenProfile: ((userId: String) -> Unit)?,
    onOpenStarredMessages: (chatId: String) -> Unit,
    onOpenMediaCenter: (chatId: String) -> Unit,
    onOpenCallHistory: (() -> Unit)?,
    onVoiceCall: (contactId: String, contactName: String) -> Unit,
    onVideoCall: (contactId: String, contactName: String) -> Unit,
    onOpenAiTasks: (chatId: String) -> Unit,
) {
    CompositionLocalProvider(LocalDensity provides appearanceDerived.scaledDensity) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(rememberChatDetailSecretPagePayload(state, context))
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
                // G335（第十三批）：滚动到顶部附近触发加载更早消息，派生量收进 scroll 持有类
                headerStatus = rememberChatHeaderStatus(state, context),
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
        // 粒子动效启动器收进 ChatDetailTransientStates.kt 的 remember 接线（纯搬移不改判断）。
        onStartParticleEffect = rememberChatDetailParticleLauncher(particles, bubbleBounds, state.currentUserId),
        chatClipboardMessageLabel = labels.chatClipboardMessageLabel,
        chatClipboardTranslationLabel = labels.chatClipboardTranslationLabel,
        chatClipboardTranscriptLabel = labels.chatClipboardTranscriptLabel,
        onOpenProfile = onOpenProfile,
    )
    } // secret watermark Box
    } // CompositionLocalProvider (chat font scale)
}
