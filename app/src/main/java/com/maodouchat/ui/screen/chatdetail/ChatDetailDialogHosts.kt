package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.Manifest
import com.maodouchat.ui.component.ParticleDeleteEffect
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.Message

@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailUpperDialogHost(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    search: ChatDetailSearchState,
    searchResults: List<Message>,
    aiResults: ChatDetailAiResultState,
    schedule: ChatDetailScheduleState,
    chatLock: ChatDetailChatLockState,
    aiPanels: ChatDetailAiPanelState,
    contactSheets: ChatDetailContactSheetState,
    groupCall: ChatDetailGroupCallState,
    pickers: ChatDetailPickers,
    onVoiceCall: (contactId: String, contactName: String) -> Unit,
    onVideoCall: (contactId: String, contactName: String) -> Unit,
) {
    val context = LocalContext.current
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
        // G22（第二十二批）：列表数据收进 ChatDetailScheduleState。打开瞬间已在点击处同步加载
        // （首帧即有数据，与原 remember 初始化一致）；旋转重建 / 打开期间切会话时按 chatId 重载，
        // 与原 remember(showReminderList, reminderChatId) 的重算语义等价。
        LaunchedEffect(reminderChatId) { schedule.reminderList = viewModel.listRemindersForChat(reminderChatId) }
        ChatDetailReminderListDialog(
            reminders = schedule.reminderList,
            chatId = reminderChatId,
            onDismiss = { schedule.closeReminderList() },
            onCancelReminder = { id ->
                viewModel.cancelReminder(id)
                schedule.reminderList = schedule.reminderList.filterNot { it.id == id }
            },
            onClearAll = { chatId ->
                viewModel.clearRemindersForChat(chatId)
                schedule.reminderList = emptyList()
            },
            onRemindersChange = { schedule.reminderList = it },
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
                schedule.beginReschedule(id, state.scheduledMessages.firstOrNull { it.id == id }?.text.orEmpty())
            },
            // 1.168：立即发送
            onSendNow = { viewModel.sendScheduledNow(it) },
            // 1.174：全部取消
            onCancelAll = { viewModel.cancelAllScheduledMessages() },
            onDismiss = { schedule.showScheduledList = false }
        )
    }

    schedule.rescheduleTargetId?.let { targetId ->
        // 1.43：重排时可编辑文案（初值取当前待发文案；状态收进 ChatDetailScheduleState，第二十二批）
        ScheduleSendDialog(
            titleRes = R.string.schedule_reschedule_title,
            initialText = schedule.rescheduleTextDraft,
            onTextEdited = { schedule.rescheduleTextDraft = it },
            onPickDelay = { delayMs ->
                // 1.46：清空编辑框时保留原文（null 表示不改文案）；先取草稿再清状态。
                val text = schedule.rescheduleTextDraft.takeIf { it.isNotBlank() }
                schedule.clearReschedule()
                viewModel.rescheduleScheduledMessage(targetId, delayMs, text)
            },
            onPickAt = { sendAt ->
                val text = schedule.rescheduleTextDraft.takeIf { it.isNotBlank() }
                schedule.clearReschedule()
                viewModel.rescheduleScheduledMessageAt(targetId, sendAt, text)
            },
            onDismiss = { schedule.clearReschedule() }
        )
    }

    // G81：设置聊天锁对话框（71 行）抽到 ChatDetailSetChatLockDialog.kt，纯搬移不改判断。
    if (chatLock.showSetChatLock) {
        ChatDetailSetChatLockDialog(
            pinDraft = chatLock.setLockPinDraft,
            pinConfirmDraft = chatLock.setLockPinConfirm,
            errorMessage = chatLock.setLockError,
            contactDisplayName = state.contact.displayName,
            onPinDraftChange = { chatLock.setLockPinDraft = it },
            onPinConfirmDraftChange = { chatLock.setLockPinConfirm = it },
            onErrorMessageChange = { chatLock.setLockError = it },
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
}

/**
 * G352：会话详情「下半区」弹窗簇从 `ChatDetailRoute.kt` 抽出（纯搬移不改判断）——
 * 群公告全文、批量删除确认、消息长按操作面板、AI 图像/文件分析、稍后提醒、图片/视频发送预览、
 * 翻译/举报/复制/编辑/重试/撤回/删除确认、跳转日期、已读回执、转发选择、删除粒子动效。
 *
 * 依赖全经参数注入；组合期内不新增状态所有权，开关读写语义逐字一致。
 */
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailLowerDialogHost(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    flows: ChatDetailConversationFlowState,
    dialogs: ChatDetailDialogState,
    drafts: ChatDetailDraftState,
    messageActions: ChatDetailMessageActionState,
    sendPending: ChatDetailSendPendingState,
    particles: ChatDetailParticleState,
    targets: ChatDetailMessageTargetState,
    media: ChatDetailFullscreenMediaState,
    pickers: ChatDetailPickers,
    listScrollScope: kotlinx.coroutines.CoroutineScope,
    selectedMessages: List<Message>,
    resolveSenderName: (Message) -> String?,
    chatAiSurfacesVisible: Boolean,
    chatCopiedMsg: String,
    chatTranslationCopiedMsg: String,
    chatTranscriptCopiedMsg: String,
    onStartParticleEffect: (Message, ParticleAction) -> Unit,
    chatClipboardMessageLabel: String,
    chatClipboardTranslationLabel: String,
    chatClipboardTranscriptLabel: String,
    onOpenProfile: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val secretActive = state.isSecretChat == true
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
    if (dialogs.showBatchDeleteConfirm) {
        ChatDetailBatchDeleteDialog(
            selectedMessages = selectedMessages,
            currentUserId = state.currentUserId,
            onDelete = { ids -> viewModel.deleteMessagesBatch(ids) },
            onDismiss = { dialogs.showBatchDeleteConfirm = false },
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
            onReplyTarget = { targets.replyTarget = it },
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
            messageActions.messageToRevoke?.let { onStartParticleEffect(it, ParticleAction.REVOKE) }
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
            pendingDelete?.let { onStartParticleEffect(it, ParticleAction.DELETE) }
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
            onDelete = { onStartParticleEffect(msg, ParticleAction.DELETE) },
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
            messageActions.messageToRetry?.let { onStartParticleEffect(it, ParticleAction.DELETE) }
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
}
