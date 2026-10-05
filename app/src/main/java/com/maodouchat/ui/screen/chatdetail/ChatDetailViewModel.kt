package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.crypto.DecryptHistoryPolicy
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    internal val chatId: String = savedStateHandle.get<String>("chatId").orEmpty()
    private val navigationMessageId: String? = savedStateHandle.get<String>("messageId")?.takeIf(String::isNotBlank)
    @Volatile internal var activeChatId: String = chatId
    internal val app = application as com.maodouchat.MaodouchatApp
    // G73：AI 能力端口（实现在 data 层，Route 只认端口）
    internal val aiConversationProfileSource get() = deps.aiConversationProfileSource
    internal val aiChatClassificationSource get() = deps.aiChatClassificationSource
    internal val aiEmotionReplySource get() = deps.aiEmotionReplySource
    internal val aiWeeklyReportSource get() = deps.aiWeeklyReportSource
    internal val messageRepo get() = deps.messageRepo
    internal val attachmentDownloadCoordinator get() = deps.attachmentDownloadCoordinator
    private val messageGateway get() = deps.messageGateway
    internal val commandFacade get() = deps.commandFacade
    internal val attachmentIntentController get() = deps.attachmentIntentController
    internal val chatLockRepo get() = deps.chatLockRepo
    internal val secretTtlRepo get() = deps.secretTtlRepo
    private val messageTerminalStore get() = deps.messageTerminalStore
    internal val messagingMutationFacade get() = deps.messagingMutationFacade
    private val conversationMessageMutationCoordinator get() = deps.conversationMessageMutationCoordinator
    private val conversationReactionCoordinator get() = deps.conversationReactionCoordinator
    internal val aiSummaryRepo get() = deps.aiSummaryRepo
    internal val aiTaskRepo get() = deps.aiTaskRepo
    internal val aiOperationRepo get() = deps.aiOperationRepo
    internal val chatRepo get() = deps.chatRepo
    internal val chatDraftDao get() = deps.chatDraftDao
    private val outgoingFacade get() = deps.outgoingFacade
    private val conversationScheduleCoordinator get() = deps.conversationScheduleCoordinator
    private val chatScheduleController get() = deps.chatScheduleController
    internal val conversationLocalStateCoordinator get() = deps.conversationLocalStateCoordinator

    internal val voiceRecorder get() = deps.voiceRecorder
    internal val recordingWaveformBuffer get() = deps.recordingWaveformBuffer
    internal var recordingMeterJob
        get() = deps.recordingMeterJob
        set(value) { deps.recordingMeterJob = value }
    internal val signalProtocol get() = deps.signalProtocol
    private val groupMessagingCoordinator get() = deps.groupMessagingCoordinator
    private val groupLifecycleCoordinator get() = deps.groupLifecycleCoordinator
    private val groupLifecycleService get() = deps.groupLifecycleService
    internal val conversationForwardCoordinator get() = deps.conversationForwardCoordinator
    internal val aiMessageResultStore get() = deps.aiMessageResultStore
    internal fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /** 9.217：复数串助手（ViewModel 非 Compose 路径）。 */
    internal fun quantityText(id: Int, quantity: Int, vararg args: Any): String =
        getApplication<Application>().resources.getQuantityString(id, quantity, *args)

    internal fun occupySessionCipher(
        targetChatId: String = activeChatId,
        peerUserId: String? = null,
        updatePeer: Boolean = false,
    ) = sessionCipherController.occupySessionCipher(targetChatId, peerUserId, updatePeer)

    private var lastMessagesSeen
        get() = deps.lastMessagesSeen
        set(value) { deps.lastMessagesSeen = value }
    internal var pendingAiAction
        get() = deps.pendingAiAction
        set(value) { deps.pendingAiAction = value }
    internal var pendingAiOperationRetryId
        get() = deps.pendingAiOperationRetryId
        set(value) { deps.pendingAiOperationRetryId = value }
    internal var aiSettingsLoaded
        get() = deps.aiSettingsLoaded
        set(value) { deps.aiSettingsLoaded = value }
    internal var unreadSummaryAttemptedForKey
        get() = deps.unreadSummaryAttemptedForKey
        set(value) { deps.unreadSummaryAttemptedForKey = value }
    internal var unreadSummaryInFlightKey
        get() = deps.unreadSummaryInFlightKey
        set(value) { deps.unreadSummaryInFlightKey = value }
    internal val semanticSearchGate get() = deps.semanticSearchGate
    private val attachmentPreparationJobs get() = deps.attachmentPreparationJobs
    internal val aiOperationJobs get() = deps.aiOperationJobs
    internal val aiAutoRetryJobs get() = deps.aiAutoRetryJobs
    internal val aiAutoRetryAt get() = deps.aiAutoRetryAt
    internal val aiOperationQueueMutex get() = deps.aiOperationQueueMutex
    internal val aiOperationJson get() = deps.aiOperationJson
    internal var aiRewriteStreamJob
        get() = deps.aiRewriteStreamJob
        set(value) { deps.aiRewriteStreamJob = value }
    internal var aiReplyStreamJob
        get() = deps.aiReplyStreamJob
        set(value) { deps.aiReplyStreamJob = value }
    internal var semanticSearchJob
        get() = deps.semanticSearchJob
        set(value) { deps.semanticSearchJob = value }
    internal var groupAiJob
        get() = deps.groupAiJob
        set(value) { deps.groupAiJob = value }
    internal var manualSummaryJob
        get() = deps.manualSummaryJob
        set(value) { deps.manualSummaryJob = value }
    internal var unreadSummaryJob
        get() = deps.unreadSummaryJob
        set(value) { deps.unreadSummaryJob = value }
    internal val aiRewriteGate get() = deps.aiRewriteGate
    internal val aiReplyGate get() = deps.aiReplyGate
    internal val groupAiGate get() = deps.groupAiGate
    internal val manualSummaryGate get() = deps.manualSummaryGate
    internal var lastAiRewriteMode
        get() = deps.lastAiRewriteMode
        set(value) { deps.lastAiRewriteMode = value }
    internal var lastAiRewriteTargetLanguage
        get() = deps.lastAiRewriteTargetLanguage
        set(value) { deps.lastAiRewriteTargetLanguage = value }
    internal var lastAiReplyTone
        get() = deps.lastAiReplyTone
        set(value) { deps.lastAiReplyTone = value }
    internal var liveLocationJob
        get() = deps.liveLocationJob
        set(value) { deps.liveLocationJob = value }
    internal var liveLocationCancel
        get() = deps.liveLocationCancel
        set(value) { deps.liveLocationCancel = value }
    internal val liveLocationUpdateMutex get() = deps.liveLocationUpdateMutex
    internal val inlineSendCompletions get() = deps.inlineSendCompletions
    internal var lastLiveLocationPayload
        get() = deps.lastLiveLocationPayload
        set(value) { deps.lastLiveLocationPayload = value }
    internal var draftSaveJob
        get() = deps.draftSaveJob
        set(value) { deps.draftSaveJob = value }
    internal var draftGeneration
        get() = deps.draftGeneration
        set(value) { deps.draftGeneration = value }
    @Volatile internal var hasUserEditedInput = false
    internal val currentUserId: String get() = com.maodouchat.session.CurrentSession.snapshot().userId ?: "me"
    internal val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token ?: ""

    internal fun currentGroupRevision(): Long? =
        _uiState.value.chat?.takeIf { it.isGroup }?.memberRevision

    internal val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()
    /**
     * G328c 装配已搬到 [ChatDetailDeps]。⚠️ **必须声明在 `_uiState` 之后**：Deps 构造要读 `host._uiState`，
     * 声明顺序在前会读到 null → 真机打开任一聊天即崩（NPE: Parameter specified as non-null is null）。
     */
    private val deps = ChatDetailDeps(application, host = this)

    private val timelineStateController get() = deps.timelineStateController
    internal val searchSelectionStateController get() = deps.searchSelectionStateController
    private val groupSecurityStateController get() = deps.groupSecurityStateController
    private val mediaStateController get() = deps.mediaStateController
    private val readReceiptCoordinator get() = deps.readReceiptCoordinator
    private val pinStarController get() = deps.pinStarController
    private val nudgeSender get() = deps.nudgeSender
    private val retrySender get() = deps.retrySender
    private val attachmentSender get() = deps.attachmentSender
    private val revisionHandler get() = deps.groupRevisionHandler
    private val moderationController get() = deps.moderationController
    private val botGroupActionController get() = deps.botGroupActionController
    private val realtimeController get() = deps.realtimeController
    private val voicePlaybackReporter get() = deps.voicePlaybackReporter
    private val messageMutationController get() = deps.messageMutationController
    private val sessionCipherController get() = deps.sessionCipherController
    private val messageLoadingController get() = deps.messageLoadingController
    private val outgoingHydrationController get() = deps.outgoingHydrationController
    private val groupCallController get() = deps.groupCallController
    private val sessionTeardownController get() = deps.sessionTeardownController

    init {
        _uiState.update {
            it.copy(
                currentUserId = currentUserId,
                currentDeviceId = signalProtocol.getDeviceId(),
                currentIdentityFingerprint = signalProtocol.getLocalIdentityFingerprint(),
                navigationTargetMessageId = navigationMessageId
            )
        }
        // launchSingleTop reuses this VM; re-target highlight when search opens another message in same chat.
        viewModelScope.launch {
            savedStateHandle.getStateFlow("messageId", navigationMessageId)
                .collect { raw ->
                    val next = raw?.takeIf(String::isNotBlank)
                    if (next != null && next != _uiState.value.navigationTargetMessageId) {
                        _uiState.update { it.copy(navigationTargetMessageId = next) }
                    }
                }
        }
        if (chatId.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, groupEncryptionWarning = text(R.string.chat_invalid_conversation))
            }
        } else {
            occupySessionCipher(chatId)
            viewModelScope.launch(Dispatchers.IO) {
                pinSessionCipherPeerFromCache(chatId)
            }
            ChatDetailAccess.markActiveChatOpened(System.currentTimeMillis())
            // Open chat: drop tray notification + mark in-app center rows for this chat.
            runCatching {
                com.maodouchat.notification.MessageNotificationService.cancelMessage(getApplication(), chatId)
            }
            try {
                com.maodouchat.notification.NotificationCenterAccess.repository.markChatMessagesRead(chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // 通知中心已读失败不阻塞进入会话
            }
            restoreDraft()
            refreshChatLockState()
            refreshSecretChatState()
            loadChat()
            observeLocalMessages()
            observeAuthoritativeMessageMutations()
            realtimeController.start()
            observeMessageStatus()
            readReceiptCoordinator.observe(activeChatId.ifBlank { chatId })
            observeVoicePlayback()
            observeAttachmentTransfers()
            observeAttachmentFinalizedEvents()
            observeAiOperations()
            loadAiSettings()
            // 阅后即焚：本地周期扫到期消息并删除密文缓存。
            // 30s 粒度足够及时；原先 1s 无条件扫描即使未开启阅后即焚也会常驻唤醒主线程
            // 做 Room 查询（退后台时 ViewModel 存活照跑），浪费电量。App 级 5 分钟清扫兜底。
            viewModelScope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(30_000L)
                    // 9.146：每轮快照账号并过门禁——此前仅查 token 非空与陈旧 uiState，
                    // 换号后本循环会清理新账号的到期消息与媒体
                    val purgeOwnerUserId = currentUserId
                    if (purgeOwnerUserId.isBlank() ||
                        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = purgeOwnerUserId,
                        )
                    ) continue
                    if (com.maodouchat.session.CurrentSession.snapshot().token.isNullOrBlank()) continue
                    if (_uiState.value.disappearingMessageSeconds <= 0) continue
                    purgeExpiredLocalMessages()
                }
            }
        }
    }

    internal fun observeAiOperations() = aiOperationsController.observeAiOperations()

    internal fun observeMessageStatus() = messageStatusObservationController.observeMessageStatus()

    internal fun observeAttachmentTransfers() = attachmentObservationController.observeAttachmentTransfers()

    fun markVoiceMessagePlayed(messageId: String) = voicePlaybackReporter.markVoiceMessagePlayed(messageId)

    internal fun observeVoicePlayback() = voicePlaybackReporter.observeVoicePlayback()

    /** 8.52 UX：初次加载失败后手动重试（UI 错误空态的重试按钮）。 */
    fun reloadChat() = messageLoadingController.reloadChat()

    /**
     * Room is the durable plaintext store after list-side decrypt / own send.
     * Open-chat REST is ciphertext; merge local rows continuously so a readable
     * tail is not lost if history decrypt returns Duplicate/placeholder.
     */
    internal fun observeLocalMessages() = messageObservationController.observeLocalMessages()

    internal fun observeAuthoritativeMessageMutations() =
        messageObservationController.observeAuthoritativeMessageMutations()

    /** Room already knows participants; pin 1:1 peer before getChats returns. */
    internal suspend fun pinSessionCipherPeerFromCache(targetChatId: String) =
        sessionCipherController.pinSessionCipherPeerFromCache(targetChatId)

    internal fun loadChat() = messageLoadingController.loadChat()

    fun loadOlderMessages() = messageLoadingController.loadOlderMessages()

    /**
     * 日历跳转：定位到本机持久时间线中指定日期的第一条消息，
     * 然后滚动定位并高亮。
     */
    fun jumpToDate(dayStartMillis: Long) = chatJumpController.jumpToDate(dayStartMillis)

    internal suspend fun refreshMyMemberRole(expectedUserId: String) =
        messageLoadingController.refreshMyMemberRole(expectedUserId)

    fun togglePinMessage(messageId: String) = pinStarController.togglePinMessage(messageId)

    fun jumpToPinnedMessage(messageId: String) = chatJumpController.jumpToPinnedMessage(messageId)

    fun jumpToMessage(messageId: String) = chatJumpController.jumpToMessage(messageId)

    fun togglePinMessages(messageIds: List<String>, shouldPin: Boolean) =
        pinStarController.togglePinMessages(messageIds, shouldPin)

    // 1.11：发送名片（联系人卡片）——构造卡片文本后复用 sendMessage 完整发送链路（加密/出站/状态）
    fun sendContactCard(targetUserId: String, displayName: String) =
        contactCardController.sendContactCard(targetUserId, displayName)

    fun toggleChatMarkedUnread() = chatSettingToggleController.toggleChatMarkedUnread()

    fun toggleChatPinned() = chatSettingToggleController.toggleChatPinned()

    internal suspend fun handleGroupRevisionChanged(event: WebSocketEvent.GroupRevisionChanged) =
        revisionHandler.handleGroupRevisionChanged(event)

    fun onInputChange(text: String) {
        hasUserEditedInput = true
        // 8.52 UX：对齐服务端 4000 上限本地截断（此前粘贴超长文本会无限进入输入态/被服务端拒绝）
        val clipped = if (text.length > MAX_COMPOSER_TEXT_LENGTH) text.take(MAX_COMPOSER_TEXT_LENGTH) else text
        if (_uiState.value.aiDraftOriginal != null) discardAiDraftPreview()
        if (_uiState.value.isAiReplyStreaming) cancelAiReplyStream(clearSuggestions = true)
        // 1.162：用户编辑后清除「已恢复草稿」标识
        _uiState.update { it.copy(inputText = clipped, hasSavedDraft = false, aiSuggestions = emptyList(), aiReplyStreamErrorCode = null) }
        scheduleDraftPersistence(clipped)
        realtimeController.onComposerTextChanged(text.isNotBlank())
    }

    internal suspend fun commitAiMessageResult(
        operationId: String?,
        message: Message,
        expectedUserId: String,
        expectedChatId: String,
    ): Boolean = aiOperationsController.commitAiMessageResult(
        operationId, message, expectedUserId, expectedChatId
    )

    internal fun observeAttachmentFinalizedEvents() = attachmentObservationController.observeAttachmentFinalizedEvents()


    /** Sends a nudge through the same durable encrypted outbox as every other message. */
    fun sendNudge() = nudgeSender.sendNudge()

    /**
     * 重发失败的消息——编排已抽到 [ChatRetrySender]（G346），纯搬移不改判断。
     */
    fun retrySendMessage(messageId: String) = retrySender.retrySendMessage(messageId)

    /**
     * 一键重试当前聊天所有失败/暂停任务；状态栏展示的中转数减为 0 后会自动收起浮窗。
     */

    /**
     * 一键清掉当前聊天的所有传输（已上传 READY 的不会被清掉）。
     */

    /** Optimistically hides a message after its encrypted delete event is staged. */
    internal fun deleteMessage(messageId: String) = messageMutationController.deleteMessage(messageId)

    fun deleteMessagesBatch(messageIds: List<String>) =
        messageMutationController.deleteMessagesBatch(messageIds)

    fun revokeMessage(messageId: String) = messageMutationController.revokeMessage(messageId)

    fun editTextMessage(messageId: String, newText: String) =
        messageMutationController.editTextMessage(messageId, newText)

    fun setMessageReaction(messageId: String, emoji: String) =
        messageMutationController.setMessageReaction(messageId, emoji)

    // observeAuthoritativeMessageMutations 与消息变更控制器共用此投影入口，故保留在 VM。
    internal fun projectMessageMutation(projection: MessageMutationProjection) {
        _uiState.update { state -> timelineStateController.applyMutation(state, projection) }
    }

    fun toggleStarMessage(messageId: String) = pinStarController.toggleStarMessage(messageId)

    fun toggleStarMessagesBatch(messageIds: List<String>, shouldStar: Boolean) =
        pinStarController.toggleStarMessagesBatch(messageIds, shouldStar)

    fun loadReadReceipts(messageId: String) = readReceiptCoordinator.loadDetails(messageId)

    fun clearReadReceipts() = readReceiptCoordinator.clear()

    private fun refreshBlockState(contactId: String = _uiState.value.contact.id) =
        moderationController.refreshBlockState(contactId)

    fun blockContact() = moderationController.blockContact()

    fun unblockContact() = moderationController.unblockContact()

    fun reportContact(reason: String, description: String? = null) =
        moderationController.reportContact(reason, description)

    fun reportMessage(messageId: String, reason: String, description: String? = null) =
        moderationController.reportMessage(messageId, reason, description)

    fun loadGroupCandidates() = botGroupActionController.loadGroupCandidates()

    fun renameGroup(newName: String) = botGroupActionController.renameGroup(newName)

    fun removeGroupMember(userId: String) = botGroupActionController.removeGroupMember(userId)


    private val scheduledMessageController get() = deps.scheduledMessageController

    private val chatSettingToggleController get() = deps.chatSettingToggleController
    private val chatJumpController get() = deps.chatJumpController
    private val composerSendController get() = deps.composerSendController
    private val inlineSendController get() = deps.inlineSendController
    private val contactCardController get() = deps.contactCardController
    private val aiOperationsController get() = deps.aiOperationsController
    private val attachmentObservationController get() = deps.attachmentObservationController
    private val messageObservationController get() = deps.messageObservationController
    private val messageStatusObservationController get() = deps.messageStatusObservationController
    private val chatCaptureAlertController get() = deps.chatCaptureAlertController

    fun refreshScheduledMessages() = scheduledMessageController.refreshScheduledMessages()
    fun clearScheduledInfo() = scheduledMessageController.clearScheduledInfo()
    fun scheduleMessage(delayMs: Long) = scheduledMessageController.scheduleMessage(delayMs)
    fun scheduleMessageAt(sendAtMillis: Long, repeatIntervalMs: Long = 0L, repeatCount: Int = 0, weekdaysOnly: Boolean = false) = scheduledMessageController.scheduleMessageAt(sendAtMillis, repeatIntervalMs, repeatCount, weekdaysOnly)
    fun scheduleMessageRepeat(intervalMs: Long, repeatCount: Int = 0, weekdaysOnly: Boolean = false) = scheduledMessageController.scheduleMessageRepeat(intervalMs, repeatCount, weekdaysOnly)
    fun cancelScheduledMessage(id: String) = scheduledMessageController.cancelScheduledMessage(id)
    fun sendScheduledNow(id: String) = scheduledMessageController.sendScheduledNow(id)
    fun cancelAllScheduledMessages() = scheduledMessageController.cancelAllScheduledMessages()
    fun rescheduleScheduledMessage(id: String, delayMs: Long, newText: String? = null) = scheduledMessageController.rescheduleScheduledMessage(id, delayMs, newText)
    fun rescheduleScheduledMessageAt(id: String, sendAtMillis: Long, newText: String? = null) = scheduledMessageController.rescheduleScheduledMessageAt(id, sendAtMillis, newText)

    private val chatReminderController get() = deps.chatReminderController

    fun scheduleMessageReminder(message: Message, remindAtMillis: Long) = chatReminderController.scheduleMessageReminder(message, remindAtMillis)
    fun listRemindersForChat(chatId: String): List<com.maodouchat.util.MessageReminderStore.MessageReminder> = chatReminderController.listRemindersForChat(chatId)
    fun cancelReminder(reminderId: String) = chatReminderController.cancelReminder(reminderId)
    fun clearRemindersForChat(chatId: String) = chatReminderController.clearRemindersForChat(chatId)

    private val chatExportController get() = deps.chatExportController

    fun exportChatHistory() = chatExportController.exportChatHistory()
    fun clearExportInfo() = chatExportController.clearExportInfo()

    private suspend fun maybeForwardBotInbox(
        liveToken: String,
        chatId: String,
        plaintext: String,
        isGroup: Boolean,
        peerId: String,
    ) = botGroupActionController.maybeForwardBotInbox(liveToken, chatId, plaintext, isGroup, peerId)

    private fun refreshBotCommands(chatId: String) = botGroupActionController.refreshBotCommands(chatId)

    fun inviteFirstOwnedBot() = botGroupActionController.inviteFirstOwnedBot()

    fun inviteBot(botId: String) = botGroupActionController.inviteBot(botId)

    fun notifyLocalCaptureDetected(message: String) = chatCaptureAlertController.notifyLocalCaptureDetected(message)

    /**
     * Share live location for [durationMs] (default 15 min). Sends an E2EE LOCATION payload
     * with live=true; further updates reuse the same sessionId via [updateLiveLocation].
     */
    internal fun sendInlineContent(content: String, type: MessageType, preview: String): String? =
        inlineSendController.sendInlineContent(content, type, preview)

    internal fun composeContentWithMeta(text: String, meta: com.maodouchat.data.model.MessageMeta): String =
        // 9.144：委托 JsonFormat 权威实现——本地手写清单漏字段（forwardedFrom 等）曾被静默丢弃
        com.maodouchat.util.JsonFormat.composeContentWithMeta(text, meta)

    /** 群通话入口：把当前群除自己外的成员挨个邀请 */
    fun startGroupCallFromChat(
        callType: com.maodouchat.webrtc.CallType,
        selectedMemberIds: Set<String>? = null
    ) = groupCallController.startGroupCallFromChat(callType, selectedMemberIds)

    internal fun sendEncryptedAttachment(
        uri: Uri,
        type: MessageType,
        fixedMessageId: String? = null,
        existingMessage: Message? = null,
        voiceDurationMs: Long? = existingMessage?.parsedMeta()?.voiceDurationMs,
        // 8.49 修复：改读 parsedMeta()——DB round-trip 后瞬态 meta 字段恒为默认值，
        // 旧写法让阅后即焚/剧透遮罩媒体在重发后失去一次性查看保护
        viewOnce: Boolean = existingMessage?.parsedMeta()?.viewOnce == true,
        spoilerMedia: Boolean = existingMessage?.parsedMeta()?.spoilerMedia == true
    ) = attachmentSender.sendEncryptedAttachment(
        uri, type, fixedMessageId, existingMessage, voiceDurationMs, viewOnce, spoilerMedia
    )



    private val identityVerificationController get() = deps.identityVerificationController

    fun showSafetyCodeDialog() = identityVerificationController.showSafetyCodeDialog()
    fun dismissSafetyCodeDialog() = identityVerificationController.dismissSafetyCodeDialog()
    fun verifyAndTrustIdentity(deviceId: Int? = null) = identityVerificationController.verifyAndTrustIdentity(deviceId)
    private val recipientId get() = deps.recipientId

    internal suspend fun hydrateOutgoingChat(
        chat: Chat,
        ownerUserId: String,
        resolvedPeerId: String?,
    ): ResolvedOutgoingChat =
        outgoingHydrationController.hydrateOutgoingChat(chat, ownerUserId, resolvedPeerId)

    fun toggleSilentSend() = composerSendController.toggleSilentSend()

    /**
     * Primary composer send for 1:1 and groups (text / markdown).
     * 编排已抽到 [ChatComposerSendController]，纯搬移不改判断。
     */
    internal fun sendMessage(
        replyTarget: Message? = null,
        silent: Boolean? = null,
        forceText: String? = null,
        forcedMeta: MessageMeta? = null,
        onDurableCommit: (() -> Unit)? = null,
        onDurableFailure: (() -> Unit)? = null,
    ) = composerSendController.sendMessage(
        replyTarget = replyTarget,
        silent = silent,
        forceText = forceText,
        forcedMeta = forcedMeta,
        onDurableCommit = onDurableCommit,
        onDurableFailure = onDurableFailure,
    )

    internal fun maybeShowNewDeviceHistoryBanner(messages: List<Message>) {
        if (!DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(signalProtocol.wasIdentityRestoredFromStore())) {
            return
        }
        val hasUndecryptedHistory = messages.any { isSyncDecryptFailurePlaceholder(it) }
        if (!hasUndecryptedHistory) return
        if (!_uiState.value.groupEncryptionWarning.isNullOrBlank()) return
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_decrypt_new_device)) }
    }

    /**
     * G328c：附件传输族（下载/暂停/续传/取消/进度/错误文案）已抽到
     * [ChatDetailFileTransferController]。这里只保留一行转发，调用点不变。
     */
    private val fileTransferController get() = deps.fileTransferController

    fun requestOpenFile(messageId: String) = fileTransferController.requestOpenFile(messageId)

    fun requestMediaAttachment(messageId: String) = fileTransferController.requestMediaAttachment(messageId)

    fun consumeFileReadyToOpen() = fileTransferController.consumeFileReadyToOpen()

    fun pauseFileTransfer(messageId: String) = fileTransferController.pauseFileTransfer(messageId)

    fun resumeFileTransfer(messageId: String) = fileTransferController.resumeFileTransfer(messageId)

    fun cancelFileTransfer(messageId: String) = fileTransferController.cancelFileTransfer(messageId)

    fun retryAllAttachmentTransfers() = fileTransferController.retryAllAttachmentTransfers()

    fun cancelAllAttachmentTransfers() = fileTransferController.cancelAllAttachmentTransfers()

    internal fun updateFileTransferProgress(
        messageId: String,
        completed: Long,
        total: Long,
        start: Float,
        end: Float
    ) = fileTransferController.updateFileTransferProgress(messageId, completed, total, start, end)

    internal fun attachmentErrorText(error: Throwable, fallbackStringRes: Int): String =
        fileTransferController.attachmentErrorText(error, fallbackStringRes)

    internal suspend fun ensureLocalAttachment(message: Message): Result<Message> {
        return attachmentDownloadCoordinator.ensureLocalAttachment(message)
    }

    fun clearChatLockInfo() {
        _uiState.update { it.copy(chatLockInfoMessage = null) }
    }

    fun clearSecretChatInfo() {
        _uiState.update { it.copy(secretChatInfoMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    companion object {
        /** 试听占用的伪 messageId，避免与真实消息播放冲突。 */
        const val VOICE_PREVIEW_MESSAGE_ID: String = "__voice_preview__"
        /** 8.52 UX：输入框内容上限，与服务端 sendMessage 4000 校验对齐（防超长发送被拒/卡输入）。 */
        const val MAX_COMPOSER_TEXT_LENGTH: Int = 4000
    }

    internal fun mergeMessages(existing: List<Message>, incoming: List<Message>): List<Message> {
        return mergeMessageVersions(existing, incoming)
    }

    /** 删除消息时清理附件传输记录和本地密文文件，防止孤儿行和磁盘泄漏。 */
    internal suspend fun cleanupAttachmentForMessage(messageId: String) {
        try {
            val ownerUserId = currentUserId
            if (ownerUserId.isBlank()) return
            val dao = com.maodouchat.chatdetail.ChatDetailDataAccess.attachmentTransferDao()
            val transfer = dao.get(messageId, ownerUserId = ownerUserId) ?: return
            // 删除本地密文文件
            transfer.encryptedPath.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { java.io.File(path).delete() }
            }
            // 删除传输记录
            dao.delete(messageId, ownerUserId = ownerUserId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChatDetailViewModel", "Attachment cleanup failed for $messageId", e)
        }
    }

    /**
     * WebSocket 重连后同步断连期间遗漏的消息
     */
    override fun onCleared() {
        sessionTeardownController.onCleared()
        super.onCleared()
    }

    internal fun String.isSenderKeyMessage(): Boolean = signalProtocol.isSenderKeyEnvelope(this)

    /**
     * G328c：解密状态判定（失败文案 / 占位符识别 / 能否跳过）已抽到
     * [ChatDetailDecryptStatus]——那里的依赖是显式注入的，因此能在纯 JVM 单测里覆盖；
     * 留在 ViewModel 里时它们要走 `getApplication()` 取文案，本机（无 Robolectric）测不了。
     * 这里只保留同名转发，调用点不变。
     */
    private val decryptStatus get() = deps.decryptStatus

    internal fun Message.mediaDecryptFailedText(): String = mediaDecryptFailedTextForType(type)

    internal fun isSyncDecryptFailurePlaceholder(message: Message): Boolean =
        decryptStatus.isSyncFailurePlaceholder(message)

    internal fun canAdvancePastSyncDecryptFailure(message: Message): Boolean =
        decryptStatus.canAdvancePastSyncFailure(message)

    internal fun mediaDecryptFailedTextForType(type: MessageType): String =
        decryptStatus.failedTextFor(type)

    /** 合并本地备注名（服务端 UserDto 不含 nickname） */
    internal suspend fun withLocalNickname(user: User): User =
        outgoingHydrationController.withLocalNickname(user)
}

/** 可解密的消息类型（G183 从 ChatDetailViewModel 成员扩展抽成顶层纯函数：它不碰实例状态，留着只会无法单测）。 */
internal fun MessageType.isDecryptable(): Boolean = this in setOf(
    MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
    MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
    MessageType.VOICE, MessageType.FILE,
)
