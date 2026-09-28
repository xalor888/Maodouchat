package com.maodouchat.ui.screen.chatdetail

import android.Manifest
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.Message

/**
 * G351：会话详情「上半区」弹窗簇从 `ChatDetailRoute.kt` 抽出（纯搬移不改判断）——
 * 安全码 / 定时族（阅后即焚、免打扰时段、静音至、提醒列表、定时发送、待发列表、重排）/
 * 聊天锁设置与解除 / GIF 搜索 / 联系人操作与资料卡 / 举报 / 群通话类型与选成员，
 * 以及 AI 弹窗簇（ChatDetailAiDialogs）的挂载。
 *
 * 依赖全经参数注入；组合期内不新增状态所有权，开关读写语义逐字一致。
 */
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
