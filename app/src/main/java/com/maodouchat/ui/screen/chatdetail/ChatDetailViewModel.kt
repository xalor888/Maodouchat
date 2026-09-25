package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.attachment.AttachmentDownloadCoordinator
import com.maodouchat.attachment.DefaultAttachmentIntentController
import com.maodouchat.attachment.DefaultAttachmentPreparationService
import com.maodouchat.attachment.RoomTransferRepository
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.AttachmentKind
import com.maodouchat.crypto.DecryptHistoryPolicy
import com.maodouchat.crypto.OwnSentMediaRestorePolicy
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator
import com.maodouchat.messaging.v2.MessagingV2EventOutbox
import com.maodouchat.messaging.v2.MessagingV2MutationFacade
import com.maodouchat.messaging.v2.ConversationMessageMutationCoordinator
import com.maodouchat.messaging.v2.ConversationMessageMutationOutcome
import com.maodouchat.messaging.v2.ConversationReactionCoordinator
import com.maodouchat.messaging.v2.ConversationReactionOutcome
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.messaging.v2.MessageMutationKind
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.messaging.v2.OutgoingConversationErrors
import com.maodouchat.messaging.v2.OutgoingConversationRequest
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import com.maodouchat.forwarding.ConversationForwardCoordinator
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.scheduling.ChatScheduleController
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.util.MediaCache
import com.maodouchat.util.VoicePlayer
import com.maodouchat.util.VoiceRecorder
import com.maodouchat.util.VoiceRecordingWaveform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import com.maodouchat.ai.AiConversationProfileSource
import com.maodouchat.ai.AiChatClassificationSource
import com.maodouchat.ai.AiEmotionReplySource
import com.maodouchat.ai.AiWeeklyReportSource
import com.maodouchat.group.GroupLifecycleCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository

class ChatDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    internal val chatId: String = savedStateHandle.get<String>("chatId").orEmpty()
    private val navigationMessageId: String? = savedStateHandle.get<String>("messageId")?.takeIf(String::isNotBlank)
    @Volatile internal var activeChatId: String = chatId
    private val sessionOccupancyLock = Any()
    @Volatile
    private var sessionOccupancyLease = if (chatId.isBlank()) {
        null
    } else {
        com.maodouchat.crypto.SessionCipherOccupancy.acquire(chatId)
    }
    internal val app = application as MaodouchatApp
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
    private val userRepo get() = deps.userRepo
    internal val chatRepo get() = deps.chatRepo
    internal val chatDraftDao get() = deps.chatDraftDao
    private val outgoingFacade get() = deps.outgoingFacade
    private val conversationScheduleCoordinator get() = deps.conversationScheduleCoordinator
    private val chatScheduleController get() = deps.chatScheduleController
    internal val conversationLocalStateCoordinator get() = deps.conversationLocalStateCoordinator

    private fun ownerSession(ownerUserId: String = currentUserId): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, MaodouchatApp.currentSessionGeneration())

    internal fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = com.maodouchat.session.CurrentSession.snapshot().userId,
            liveToken = com.maodouchat.session.CurrentSession.snapshot().token,
            liveSessionGeneration = MaodouchatApp.currentSessionGeneration(),
            purgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
        )

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
    ) {
        if (targetChatId.isBlank()) return
        synchronized(sessionOccupancyLock) {
            val lease = sessionOccupancyLease
                ?: com.maodouchat.crypto.SessionCipherOccupancy.acquire(targetChatId).also {
                    sessionOccupancyLease = it
                }
            com.maodouchat.crypto.SessionCipherOccupancy.occupy(
                lease,
                targetChatId,
                peerUserId,
                updatePeer,
            )
        }
    }

    private fun releaseSessionCipher() {
        val lease = synchronized(sessionOccupancyLock) {
            sessionOccupancyLease.also { sessionOccupancyLease = null }
        } ?: return
        com.maodouchat.crypto.SessionCipherOccupancy.release(lease)
    }

    /** List/sync preview for NUDGE: rewrite sender-centric wire body for local POV. */
    private fun detailNudgePreview(message: Message): String {
        val chat = _uiState.value.chat
        val senderName = chat?.participants?.firstOrNull { it.id == message.senderId }?.name
            ?.takeIf { it.isNotBlank() }
            ?: _uiState.value.contact.name.takeIf { it.isNotBlank() }
            ?: message.senderId
        return NudgeDisplayPolicy.displayText(
            isOwnMessage = message.senderId == currentUserId,
            storedContent = message.content,
            senderDisplayName = senderName,
            isDirectChat = chat?.isGroup != true,
            templates = NudgeDisplayPolicy.Templates(
                youNudged = { target -> text(R.string.chat_nudge_you_nudged, target) },
                theyNudgedYou = { sender -> text(R.string.chat_nudge_they_nudged_you, sender) },
                theyNudgedTarget = { sender, target ->
                    text(R.string.chat_nudge_they_nudged_target, sender, target)
                }
            )
        )
    }

    /**
     * List-tail copy for a locally-readable message (post-decrypt or own send).
     * Never emits wire envelopes as TEXT preview. Delegates media/wire rules to
     * [ChatListPreviewPolicy]; TEXT uses [Message.parsedContent] (strip meta) then take(200).
     */
    private fun listPreviewTextForMessage(message: Message): String {
        if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) {
            val body = message.parsedContent()
            return if (body.isBlank() || ChatListPreviewPolicy.looksLikeWireEnvelope(body)) {
                text(R.string.message_preview_encrypted)
            } else {
                body.take(200)
            }
        }
        val preview = ChatListPreviewPolicy.fromLatestMessage(
            latest = message,
            mediaLabel = { type ->
                when (type) {
                    MessageType.IMAGE -> text(R.string.message_preview_image)
                    MessageType.GIF -> text(R.string.message_preview_gif)
                    MessageType.STICKER -> text(R.string.message_preview_sticker)
                    MessageType.LOCATION -> text(R.string.message_preview_location)
                    MessageType.VOICE -> text(R.string.message_preview_voice)
                    MessageType.VIDEO -> text(R.string.message_preview_video)
                    MessageType.FILE -> text(R.string.message_preview_file)
                    else -> text(R.string.message_preview_encrypted)
                }
            },
            encryptedPlaceholder = text(R.string.message_preview_encrypted),
            revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
            nudgeText = { detailNudgePreview(it) }
        )
        return preview.text.ifBlank { text(R.string.message_preview_encrypted) }
    }

    internal fun emitListPreviewForDecrypted(message: Message) {
        if (message.type == MessageType.SK_DIST) return
        val chatId = message.chatId.ifBlank { activeChatId }
        if (chatId.isBlank()) return
        com.maodouchat.MaodouchatApp.emitMessageSent(
            chatId,
            listPreviewTextForMessage(message),
            message.type.name,
            playSendSound = false,
        )
    }

    /** Best-effort keyword index after local plaintext is durable (IO thread). */
    internal suspend fun indexSearchableMessage(message: Message) {
        // 密聊消息不落搜索索引（与 ImageOcrAutoIndexer 一致）：即使本地 SQLCipher 已加密，
        // 密聊明文不应进入可搜索缓存，避免密聊内容在全局搜索中可被检索。
        val caps = app.secretConversationController.capabilities(message.chatId)
        if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.SEARCH)) {
            return
        }
        try {
            com.maodouchat.data.repository.MessageSearchRepository(app.database)
                .indexMessage(message)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("ChatDetailViewModel", "indexSearchableMessage failed", error)
        }
    }

    // G67：已处理过的消息 id 交给策略自己的 SeenSet（带上界，长会话不会无限增长）
    private val readSeenMessages get() = deps.readSeenMessages
    private var pendingReadWatermarkMessageId
        get() = deps.pendingReadWatermarkMessageId
        set(value) { deps.pendingReadWatermarkMessageId = value }
    private var lastMessagesSeen
        get() = deps.lastMessagesSeen
        set(value) { deps.lastMessagesSeen = value }
    private var markReadJob
        get() = deps.markReadJob
        set(value) { deps.markReadJob = value }
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
    private val moderationController get() = deps.moderationController
    private val botGroupActionController get() = deps.botGroupActionController
    private val realtimeController get() = deps.realtimeController

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
            com.maodouchat.MaodouchatApp.activeChatOpenedAtMs = System.currentTimeMillis()
            // Open chat: drop tray notification + mark in-app center rows for this chat.
            runCatching {
                com.maodouchat.notification.MessageNotificationService.cancelMessage(getApplication(), chatId)
            }
            try {
                app.notificationCenter.markChatMessagesRead(chatId)
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

    internal fun observeAiOperations() {
        val ownerUserId = com.maodouchat.session.CurrentSession.snapshot().userId?.takeIf(String::isNotBlank) ?: return
        aiOperationRepo.observeActionable(ownerUserId, activeChatId)
            .onEach { operations ->
                // Drop if logout/switch happened while Room Flow was still open.
                if (com.maodouchat.session.CurrentSession.ownerUserId() != ownerUserId) return@onEach
                _uiState.update { state ->
                    state.copy(
                        aiOperations = operations.map { operation ->
                            val waitSeconds = com.maodouchat.ai.AiCostVisibilityPolicy
                                .waitSecondsFor(operation.lastErrorCode)
                                .takeIf { it > 0L }
                            AiOperationUi(
                                id = operation.id,
                                type = operation.type,
                                state = operation.state,
                                attempts = operation.attempts,
                                lastErrorCode = operation.lastErrorCode,
                                nextRetryAtMs = aiAutoRetryAt[operation.id],
                                retryAfterSeconds = waitSeconds
                            )
                        }
                    )
                }
            }
            .launchIn(viewModelScope)
        viewModelScope.launch(Dispatchers.IO) {
            if (com.maodouchat.session.CurrentSession.ownerUserId() != ownerUserId) return@launch
            aiOperationRepo.recoverInterrupted(ownerUserId, activeChatId)
            aiOperationRepo.pruneTerminal()
            // AI 本地缓存保留期清理：总结缓存 90 天，已完成任务 90 天
            val cutoff = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1_000L
            try {
                aiSummaryRepo.pruneOlderThan(cutoff)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            try {
                aiTaskRepo.pruneCompletedOlderThan(cutoff)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    internal fun observeMessageStatus() {
        _uiState
            .onEach { state ->
                if (state.chat == null) return@onEach
                val effectiveChatId = activeChatId.ifBlank { chatId }
                if (MaodouchatApp.activeChatId != effectiveChatId) return@onEach
                val currentIds = state.messages.map { it.id to it.status }
                if (currentIds == lastMessagesSeen) return@onEach
                lastMessagesSeen = currentIds
                // G67：「算哪些新未读、水印选哪条」下沉到纯策略（可单测），这里只编排副作用。
                val ownerUserId = currentUserId
                val plan = ChatReadWatermarkPolicy.plan(
                    input = ChatReadWatermarkPolicy.Input(
                        messages = state.messages,
                        hasChat = true,
                        isActiveChat = true,
                        ownerUserId = ownerUserId,
                        sessionMayContinue = com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        ),
                    ),
                    seen = readSeenMessages,
                ) ?: return@onEach
                val unreadIds = plan.unreadIds
                val watermarkId = plan.watermarkMessageId
                val watermarkTimestamp = plan.watermarkTimestamp
                pendingReadWatermarkMessageId = watermarkId
                _uiState.update { current ->
                    current.copy(
                        messages = current.messages.map { message ->
                            if (message.id in unreadIds) message.copy(status = MessageStatus.READ) else message
                        },
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    app.database.messageDao().markIncomingReadThrough(
                        chatId = effectiveChatId,
                        ownerUserId = ownerUserId,
                        throughTimestamp = watermarkTimestamp,
                        throughMessageId = watermarkId,
                    )
                    app.database.chatDao().markAllRead(effectiveChatId)
                }
                markReadJob?.cancel()
                markReadJob = viewModelScope.launch {
                    if (DisappearingMessagePolicy.shouldSkipReadReceipts(state.isSecretChat == true)) {
                        armSecretDisappearing(effectiveChatId, watermarkId)
                        return@launch
                    }
                    delay(500)
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                    ) {
                        return@launch
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            app.messagingV2Outbox.enqueueReadReceipt(
                                conversationId = effectiveChatId,
                                throughMessageId = watermarkId,
                                groupRevision = state.chat.memberRevision.takeIf { state.chat.isGroup },
                            )
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // G67：入队失败必须回滚 seen + 短路标记，否则这条消息永远不会重试
                        ChatReadWatermarkPolicy.rollbackAfterFailure(readSeenMessages, plan)
                        lastMessagesSeen = null
                        Log.w("ChatDetailViewModel", "v2 read receipt enqueue failed: " + error.message, error)
                    }
                }
                emitChatReadForCurrentChat()
            }
            .launchIn(viewModelScope)
    }

    private fun emitChatReadForCurrentChat() {
        val id = activeChatId.ifBlank { chatId }
        if (id.isBlank()) return
        com.maodouchat.MaodouchatApp.emitChatRead(id)
    }

    internal fun observeAttachmentTransfers() {
        app.database.attachmentTransferDao().observeAllAccounts()
            .onEach { allTransfers ->
                val liveOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
                if (liveOwnerUserId.isBlank()) return@onEach
                val visibleMessageIds = _uiState.value.messages.mapTo(hashSetOf()) { it.id }
                // Only this account's rows — SQLCipher wipe is primary isolation, this is defense-in-depth mid-switch.
                val transfers = allTransfers.filter { transfer ->
                    transfer.ownerUserId == liveOwnerUserId &&
                        (transfer.chatId == activeChatId || transfer.messageId in visibleMessageIds)
                }
                _uiState.update { state -> mediaStateController.applyTransfers(state, transfers) }
                transfers.filter { it.chatId == activeChatId && it.state == AttachmentTransferState.READY }.forEach { transfer ->
                    // Final send is owned by WorkManager so it survives navigation/process death.
                    com.maodouchat.attachment.AttachmentTransferScheduler.schedule(
                        getApplication(),
                        transfer.messageId,
                        transfer.ownerUserId
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun markVoiceMessagePlayed(messageId: String) {
        val ownerUserId = currentUserId
        if (
            ownerUserId.isBlank() ||
            ownerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        ) {
            return
        }
        val target = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (target.senderId == ownerUserId) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveChatId = activeChatId.ifBlank { chatId }
                val chat = _uiState.value.chat
                app.messagingV2Outbox.enqueuePlayReceipt(
                    conversationId = effectiveChatId,
                    messageId = messageId,
                    groupRevision = chat?.memberRevision.takeIf { chat?.isGroup == true },
                )
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "v2 play receipt enqueue failed: " + error.message, error)
            }
        }
    }

    internal fun observeVoicePlayback() {
        val playedEnqueued = mutableSetOf<String>()
        VoicePlayer.state
            .filter { it.isPlaying && it.messageId != null }
            .mapNotNull { it.messageId }
            .distinctUntilChanged()
            .onEach { messageId ->
                if (playedEnqueued.add(messageId)) {
                    markVoiceMessagePlayed(messageId)
                }
            }
            .launchIn(viewModelScope)
    }

    /** 8.52 UX：初次加载失败后手动重试（UI 错误空态的重试按钮）。 */
    fun reloadChat() {
        _uiState.update { it.copy(initialLoadError = null, isLoading = true, initialTimelineReady = false) }
        loadChat()
    }

    /**
     * Room is the durable plaintext store after list-side decrypt / own send.
     * Open-chat REST is ciphertext; merge local rows continuously so a readable
     * tail is not lost if history decrypt returns Duplicate/placeholder.
     */
    internal fun observeLocalMessages() {
        val observedChatId = activeChatId.ifBlank { chatId }
        if (observedChatId.isBlank()) return
        val ownerUserId = currentUserId
        viewModelScope.launch {
            messageRepo.getMessagesByChatId(observedChatId).collect { local ->
                if (local.isEmpty()) return@collect
                if (ownerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                ) {
                    return@collect
                }
                val visible = local.filter { it.type != MessageType.SK_DIST }
                if (visible.isEmpty()) return@collect
                _uiState.update { state -> timelineStateController.mergeIncoming(state, visible) }
            }
        }
    }

    internal fun observeAuthoritativeMessageMutations() {
        val observedChatId = activeChatId.ifBlank { chatId }
        if (observedChatId.isBlank()) return
        val ownerUserId = currentUserId
        viewModelScope.launch {
            app.messagingV2MutationEvents.events.collect { mutation ->
                if (
                    mutation.conversationId != observedChatId ||
                    ownerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                ) {
                    return@collect
                }
                conversationMessageMutationCoordinator.observeAuthoritative(
                    messageId = mutation.messageId,
                    kind = mutation.kind,
                )
                when (mutation.kind) {
                    MessageMutationKind.DELETE -> projectMessageMutation(
                        MessageMutationProjection.Remove(mutation.messageId),
                    )
                    MessageMutationKind.REVOKE,
                    MessageMutationKind.EDIT -> mutation.message?.let { message ->
                        projectMessageMutation(MessageMutationProjection.Set(message))
                    }
                }
            }
        }
    }

    /** Room already knows participants; pin 1:1 peer before getChats returns. */
    internal suspend fun pinSessionCipherPeerFromCache(targetChatId: String) {
        val cached = chatRepo.getChatById(targetChatId) ?: return
        if (cached.isGroup) {
            occupySessionCipher(cached.id, peerUserId = null, updatePeer = true)
            return
        }
        val peer = cached.participants.firstOrNull { it.id.isNotBlank() && it.id != currentUserId }?.id
        if (peer != null) {
            occupySessionCipher(cached.id, peer, updatePeer = true)
        }
    }

    /** G66：把守卫拒绝原因映射成用户可见文案（资源字符串只应出现在编排层）。 */
    private fun sendRejectMessage(reason: ChatSendGuard.SendRejectReason): String = when (reason) {
        ChatSendGuard.SendRejectReason.ALREADY_SENDING -> text(R.string.chat_send_in_flight)
        ChatSendGuard.SendRejectReason.BLANK -> text(R.string.chat_send_empty)
        ChatSendGuard.SendRejectReason.NO_SESSION -> text(R.string.error_session_expired)
        ChatSendGuard.SendRejectReason.BLOCKED ->
            text(R.string.chat_blocked_user_status, _uiState.value.contact.displayName)
        ChatSendGuard.SendRejectReason.MENTION_EVERYONE_FORBIDDEN ->
            text(R.string.chat_mention_everyone_restricted)
    }

    /** G65：把纯决策产出的副作用清单真正执行掉（编排留在 ViewModel，判定在 coordinator）。 */
    private suspend fun applyLoadEffects(
        plan: ChatDetailLoadCoordinator.ChatLoadPlan,
        chat: com.maodouchat.data.model.Chat,
    ) {
        plan.effects.forEach { effect ->
            when (effect) {
                is ChatDetailLoadCoordinator.ChatLoadEffect.OccupySessionCipher ->
                    occupySessionCipher(effect.chatId, peerUserId = effect.peerUserId, updatePeer = true)
                ChatDetailLoadCoordinator.ChatLoadEffect.LoadGroupCandidates -> loadGroupCandidates()
                is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBotCommands -> refreshBotCommands(effect.chatId)
                is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBlockState -> refreshBlockState(effect.peerUserId)
                ChatDetailLoadCoordinator.ChatLoadEffect.RefreshScheduledMessages -> refreshScheduledMessages()
                is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshIdentitySafety ->
                    identityVerificationController.refreshIdentitySafetyState(effect.peerUserId)
            }
        }
        if (plan.shouldInvalidateSenderKey) {
            invalidateGroupSenderKey(chat.id, chat.memberRevision)
        }
    }

    internal fun loadChat() {
        _uiState.update { it.copy(isLoading = true, initialTimelineReady = false) }
        viewModelScope.launch {
            try {
                val loadOwnerUserId = currentUserId
                // Snapshot resolved chat id once at launch: activeChatId may be reassigned on
                // create-on-send, so the constructor chatId could mark the wrong chat read.
                val effectiveChatId = activeChatId.ifBlank { chatId }
                if (loadOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                    )
                ) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                val cachedChat = chatRepo.getChatById(chatId)
                val chatsResult = ChatNetworkRepository().chats(liveToken)
                // getChats can outlive logout/switch — do not invalidate SK / cache / paint meta for next owner.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )
                ) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val chatDto = chatsResult.getOrNull()?.find { it.id == chatId }
                if (chatDto != null) {
                    val chat = chatDto.toDomainChat().let { raw ->
                        raw.copy(participants = raw.participants.map { p -> withLocalNickname(p) })
                    }
                    val previousRevision = _uiState.value.chat?.memberRevision ?: cachedChat?.memberRevision
                    val shouldInvalidateSenderKey = chat.isGroup &&
                        previousRevision != null &&
                        chat.memberRevision > previousRevision
                    if (shouldInvalidateSenderKey) {
                        invalidateGroupSenderKey(chat.id, chat.memberRevision)
                    }
                    chatRepo.cacheChats(listOf(chat))
                    _uiState.update { it.copy(isLoading = false, initialLoadError = null) }
                    // G65：纯决策交给 ChatDetailLoadCoordinator（可单测），这里只编排副作用。
                    val plan = ChatDetailLoadCoordinator.planChatLoad(
                        input = ChatDetailLoadCoordinator.ChatLoadInput(
                            chat = chat,
                            previousRevision = previousRevision,
                            currentUserId = currentUserId,
                            fromCache = false,
                        ),
                        currentState = _uiState.value,
                        formatGroupName = { text(R.string.chat_group) },
                        formatMemberCount = { count -> quantityText(R.plurals.chat_members_count, count, count) },
                        formatRevisionWarning = { text(R.string.chat_group_members_changed_key) },
                    )
                    _uiState.value = plan.nextState
                    applyLoadEffects(plan, chat)
                } else {
                    // API 失败时从本地缓存加载聊天信息
                    if (cachedChat != null) {
                        _uiState.update { it.copy(isLoading = false) }
                        // G65：缓存回退同样走纯决策（fromCache = true），行为与 API 成功路径一致。
                        val cached = cachedChat.copy(
                            participants = cachedChat.participants.map { p -> withLocalNickname(p) }
                        )
                        val cachedPlan = ChatDetailLoadCoordinator.planChatLoad(
                            input = ChatDetailLoadCoordinator.ChatLoadInput(
                                chat = cached,
                                // 缓存回退路径没有 API 侧基线，用当前 UI 上的 revision
                                previousRevision = _uiState.value.chat?.memberRevision,
                                currentUserId = currentUserId,
                                fromCache = true,
                            ),
                            currentState = _uiState.value,
                            formatGroupName = { text(R.string.chat_group) },
                            formatMemberCount = { count -> quantityText(R.plurals.chat_members_count, count, count) },
                            formatRevisionWarning = { text(R.string.chat_group_members_changed_key) },
                        )
                        _uiState.value = cachedPlan.nextState
                        applyLoadEffects(cachedPlan, cached)
                    } else {
                        // 8.52 UX：无本地缓存时记录加载失败，UI 显示错误态 + 重试（区别于真实空会话）
                        _uiState.value = ChatDetailLoadCoordinator.planLoadFailure(_uiState.value) {
                            text(R.string.chat_load_failed_title)
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    // G70：这个门禁原先在这里**连着写了两遍**（同参同值，第二遍是纯死代码）——
                    // 复制粘贴遗留，删掉不影响任何行为，只影响阅读。
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                    )
                    ) {
                        return@withContext
                    }
                    val messages = messageRepo.getRecentMessages(effectiveChatId, HISTORY_PAGE_SIZE)
                    val unreadCount = app.database.chatDao().getChatById(effectiveChatId)?.unreadCount ?: 0
                    val readBoundary = messageRepo.getLatestIncomingMessage(effectiveChatId, loadOwnerUserId)?.id
                    // G70：收尾判定（分隔线/阅后即焚/回执）下沉到纯策略，这里只执行
                    val historyPlan = ChatHistoryLoadPolicy.planHistoryLoad(
                        messages = messages,
                        unreadCount = unreadCount,
                        isSecretChat = _uiState.value.isSecretChat == true,
                        readBoundaryMessageId = readBoundary,
                        groupRevision = _uiState.value.chat?.memberRevision
                            ?.takeIf { _uiState.value.chat?.isGroup == true },
                    )
                    _uiState.update { state ->
                        timelineStateController.initialHistoryLoaded(state, messages, historyPlan.unreadSeparatorId)
                    }
                    maybeAutoLoadLastGroupReadCount()
                    hydrateMissingLocalAttachments(messages)
                    maybeGenerateUnreadSummary(messages)
                    maybeShowNewDeviceHistoryBanner(messages)
                    if (historyPlan.armSecretDisappearing) {
                        armSecretDisappearing(effectiveChatId, readBoundary)
                    } else if (historyPlan.enqueueReadReceipt) {
                        // 不变量：两者同源于 shouldReceipt（ChatHistoryLoadPolicy）。用 `?:` 兜底而非 `!!`：破约时宁可少发一次回执，也别在加载历史时崩。
                        app.messagingV2Outbox.enqueueReadReceipt(
                            conversationId = effectiveChatId,
                            throughMessageId = historyPlan.readReceiptThroughMessageId ?: effectiveChatId,
                            groupRevision = historyPlan.readReceiptGroupRevision,
                        )
                        MaodouchatApp.emitChatRead(effectiveChatId)
                    }
                }
                refreshPinnedMessages(loadOwnerUserId)
                if (_uiState.value.chatIsGroup) {
                    refreshMyMemberRole(loadOwnerUserId)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw error
            }
        }
    }

    fun loadOlderMessages() {
        _uiState.update { state ->
            state.copy(hasMoreOlderMessages = false, isLoadingOlderMessages = false)
        }
    }

    /**
     * 日历跳转：定位到本机持久时间线中指定日期的第一条消息，
     * 然后滚动定位并高亮。
     */
    fun jumpToDate(dayStartMillis: Long) {
        val targetChatId = activeChatId.ifBlank { chatId }
        if (targetChatId.isBlank() || dayStartMillis <= 0L) return
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank() || ownerUserId == "me") return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                val anchorId = messageRepo.getFirstMessageAtOrAfter(targetChatId, dayStartMillis)?.id
                if (anchorId.isNullOrBlank()) {
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_jump_date_empty)) }
                    }
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(navigationTargetMessageId = anchorId) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "jumpToDate failed", error)
            }
        }
    }

    private suspend fun refreshPinnedMessages(expectedUserId: String) =
        pinStarController.refreshPinnedMessages(expectedUserId)

    private suspend fun refreshMyMemberRole(expectedUserId: String) {
        if (expectedUserId.isBlank() || chatId.isBlank()) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
        )
        ) {
            return
        }
        val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
        if (liveToken.isBlank()) return
        groupLifecycleService.fetchGroupMembers(chatId).onSuccess { members ->
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
            )
            ) {
                return@onSuccess
            }
            _uiState.update { state -> groupSecurityStateController.presentMembers(state, members, expectedUserId) }
        }
    }

    fun togglePinMessage(messageId: String) = pinStarController.togglePinMessage(messageId)

    fun jumpToPinnedMessage(messageId: String) {
        if (messageId.isBlank()) return
        _uiState.update { state -> searchSelectionStateController.navigateTo(state, messageId) }
    }

    /** 通用跳转定位：点击引用预览/置顶跳转等，滚动到指定消息并高亮。 */
    fun jumpToMessage(messageId: String) {
        if (messageId.isBlank()) return
        _uiState.update { state -> searchSelectionStateController.navigateTo(state, messageId) }
    }

    fun togglePinMessages(messageIds: List<String>, shouldPin: Boolean) =
        pinStarController.togglePinMessages(messageIds, shouldPin)

    // 1.11：发送名片（联系人卡片）——构造卡片文本后复用 sendMessage 完整发送链路（加密/出站/状态）
    fun sendContactCard(targetUserId: String, displayName: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CONTACT_CARD)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.contact_card_disabled)) }
            return
        }
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.contact_card_secret_blocked)) }
            return
        }
        if (targetUserId.isBlank()) return
        val safeName = displayName.trim().take(80).ifBlank { "contact" }
        val cardContent = buildString {
            append("👤 ")
            append(safeName)
            append("\n[contactUser:")
            append(targetUserId)
            append("]")
        }
        // 8.49 修复：改走 forceText——写入 inputText 再 sendMessage() 会把用户未发送的
        // 持久化草稿一并清除（sendMessage 清空输入框并 clearDraft），造成数据丢失
        sendMessage(forceText = cardContent)
    }

    /** 1.156：会话详情内标记未读/已读（乐观更新 + 失败回滚）。 */
    fun toggleChatMarkedUnread() {
        val chat = _uiState.value.chat ?: return
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank() || chatId.isBlank()) return
        val next = !chat.markedUnread
        _uiState.update { it.copy(chat = it.chat?.copy(markedUnread = next)) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                ChatNetworkRepository().updateChatSettings(
                    liveToken,
                    chatId,
                    com.maodouchat.network.UpdateChatSettingsRequest(markedUnread = next)
                ).onFailure {
                    _uiState.update { state -> state.copy(chat = state.chat?.copy(markedUnread = chat.markedUnread)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { state -> state.copy(chat = state.chat?.copy(markedUnread = chat.markedUnread)) }
            }
        }
    }

    /** 1.155：会话详情内置顶/取消置顶会话（乐观更新 + 失败回滚）。 */
    fun toggleChatPinned() {
        val chat = _uiState.value.chat ?: return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_PIN)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank() || chatId.isBlank()) return
        val wasPinned = chat.pinnedAt > 0
        val nextPinnedAt = if (wasPinned) 0L else System.currentTimeMillis()
        _uiState.update { it.copy(chat = it.chat?.copy(pinnedAt = nextPinnedAt)) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                ChatNetworkRepository().updateChatSettings(
                    liveToken,
                    chatId,
                    com.maodouchat.network.UpdateChatSettingsRequest(pinned = !wasPinned)
                ).onFailure {
                    _uiState.update { state -> state.copy(chat = state.chat?.copy(pinnedAt = chat.pinnedAt)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { state -> state.copy(chat = state.chat?.copy(pinnedAt = chat.pinnedAt)) }
            }
        }
    }

    internal suspend fun handleGroupRevisionChanged(event: WebSocketEvent.GroupRevisionChanged) {
        val revisionOwnerUserId = currentUserId
        if (
            revisionOwnerUserId.isBlank() ||
            revisionOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
        ) {
            return
        }
        val cleanupSession = conversationLocalCleanupSession(revisionOwnerUserId)
        val impact = groupRevisionImpact(
            activeChatId = activeChatId,
            currentUserId = revisionOwnerUserId,
            eventChatId = event.chatId,
            targetUserId = event.targetUserId,
            reason = event.reason
        )
        if (impact == GroupRevisionImpact.IGNORE) return
        val currentRevision = _uiState.value.chat?.memberRevision ?: -1L
        val removedFromGroup = impact == GroupRevisionImpact.CURRENT_USER_REMOVED
        if (shouldInvalidateGroupKey(currentRevision, event.memberRevision, impact)) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
            ) {
                return
            }
            invalidateGroupSenderKey(event.chatId, event.memberRevision)
        }
        if (removedFromGroup) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
            ) {
                return
            }
            realtimeController.clearRemoteTyping()
            semanticSearchGate.invalidate()
            aiRewriteGate.invalidate()
            aiReplyGate.invalidate()
            groupAiGate.invalidate()
            manualSummaryGate.invalidate()
            semanticSearchJob?.cancel()
            aiRewriteStreamJob?.cancel()
            aiReplyStreamJob?.cancel()
            groupAiJob?.cancel()
            manualSummaryJob?.cancel()
            unreadSummaryJob?.cancel()
            aiOperationJobs.values.forEach { it.cancel() }
            aiOperationJobs.clear()
            aiAutoRetryJobs.values.forEach { it.cancel() }
            aiAutoRetryJobs.clear()
            aiAutoRetryAt.clear()
            val cleanup = withContext(Dispatchers.IO + NonCancellable) {
                conversationLocalStateCoordinator.cleanup(
                    chatId = event.chatId,
                    expectedSession = cleanupSession,
                    mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
                )
            }
            cleanup.failures.forEach { failure ->
                Log.w(
                    "ChatDetailViewModel",
                    "group removal cleanup failed at ${failure.step} for ${event.chatId}",
                    failure.error,
                )
            }
            if (!cleanup.completed) return
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
            ) {
                return
            }
            _uiState.update {
                it.copy(
                    chat = null,
                    chatIsGroup = false,
                    messages = emptyList(),
                    isAiWorking = false,
                    isAiDraftStreaming = false,
                    isAiReplyStreaming = false,
                    isSemanticSearching = false,
                    isUnreadSummaryLoading = false,
                    groupEncryptionWarning = text(R.string.chat_left_group_key_cleared)
                )
            }
            return
        }
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = revisionOwnerUserId,
        )
        ) {
            return
        }
        val warning = when (event.reason) {
            "MEMBER_ADDED", "MEMBER_REMOVED", "MEMBER_LEFT" -> text(R.string.chat_group_members_changed_key)
            "GROUP_RENAMED" -> text(R.string.chat_group_name_updated)
            "ROLE_UPDATED" -> text(R.string.chat_group_role_updated)
            "TITLE_UPDATED" -> text(R.string.chat_group_title_updated)
            "NICKNAME_UPDATED" -> text(R.string.chat_group_nickname_updated)
            "ANNOUNCEMENT_UPDATED" -> text(R.string.chat_group_announcement_updated)
            "MUTE_UPDATED" -> text(R.string.chat_group_mute_updated)
            else -> text(R.string.chat_group_info_updated)
        }
        _uiState.update { it.copy(groupEncryptionWarning = warning) }
        loadChat()
        // 8.48：群变更（含禁言/解禁 MUTE_UPDATED）后刷新本机禁言状态提示——
        // 否则成员在 GroupDetail 被禁言/解禁后，聊天页提示不会更新直到重新进入
        refreshMyMemberRole(revisionOwnerUserId)
    }

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
    ): Boolean {
        if (message.chatId != expectedChatId || activeChatId != expectedChatId ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
            )
        ) throw kotlinx.coroutines.CancellationException("ai_result_context_changed")
        val committed = withContext(Dispatchers.IO) {
            aiMessageResultStore.commit(operationId, message)
        }
        if (!committed) return false
        if (activeChatId != expectedChatId ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
            )
        ) return false
        if (operationId != null) {
            aiAutoRetryJobs.remove(operationId)?.cancel()
            aiAutoRetryAt.remove(operationId)
        }
        return true
    }

    internal fun observeAttachmentFinalizedEvents() {
        viewModelScope.launch {
            com.maodouchat.MaodouchatApp.attachmentFinalizedEvents.collect { event ->
                if (event.sessionGeneration != com.maodouchat.MaodouchatApp.currentSessionGeneration()) {
                    return@collect
                }
                val message = event.message
                if (message.chatId != activeChatId) return@collect
                _uiState.update { state ->
                    state.copy(
                        messages = mergeMessages(state.messages.filterNot { it.id == message.id }, listOf(message)),
                        fileTransferProgress = state.fileTransferProgress - message.id,
                        fileTransferStates = state.fileTransferStates - message.id,
                        fileTransferErrors = state.fileTransferErrors - message.id,
                        preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - message.id
                    )
                }
            }
        }
    }




    /** Sends a nudge through the same durable encrypted outbox as every other message. */
    fun sendNudge() {
        // G68：nudge 守卫与待发意图构造都下沉到纯工厂（可单测），这里只编排副作用。
        val nudgeOwnerUserId = currentUserId
        val decision = ChatSendIntentFactory.checkNudge(
            state = _uiState.value,
            activeChatId = activeChatId,
            ownerUserId = nudgeOwnerUserId,
            token = token,
            nudgeEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.NUDGE),
        )
        if (decision is ChatSendIntentFactory.NudgeDecision.Reject) {
            // 资源字符串只出现在编排层：DISABLED 走 errorMessage，其余走 groupEncryptionWarning
            val disabled = decision.reason == ChatSendIntentFactory.NudgeRejectReason.DISABLED
            val message = when (decision.reason) {
                ChatSendIntentFactory.NudgeRejectReason.SECRET_CHAT -> text(R.string.secret_chat_forward_blocked)
                ChatSendIntentFactory.NudgeRejectReason.DISABLED -> text(R.string.nudge_disabled)
                ChatSendIntentFactory.NudgeRejectReason.NO_CHAT -> text(R.string.chat_ws_send_failed)
                ChatSendIntentFactory.NudgeRejectReason.NO_SESSION -> text(R.string.error_session_expired)
            }
            _uiState.update {
                it.copy(
                    groupEncryptionWarning = message.takeUnless { disabled } ?: it.groupEncryptionWarning,
                    errorMessage = message.takeIf { disabled } ?: it.errorMessage,
                )
            }
            return
        }
        val contactName = _uiState.value.contact.name.ifBlank { text(R.string.chat_other_person) }
        val optimistic = ChatSendIntentFactory.build(
            chatId = ChatSendIntentFactory.effectiveChatId(activeChatId, chatId),
            senderId = nudgeOwnerUserId,
            messageId = ChatSendIntentFactory.newMessageId(),
            timestamp = System.currentTimeMillis(),
            content = text(R.string.chat_nudge_you_nudged, contactName),
            type = MessageType.NUDGE,
            meta = null,
        )
        _uiState.update { state ->
            state.copy(
                messages = mergeMessages(state.messages, listOf(optimistic)),
                groupEncryptionWarning = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    outgoingFacade.enqueue(
                        OutgoingMessageCommand(
                            ownerUserId = nudgeOwnerUserId,
                            optimisticMessage = optimistic,
                            body = optimistic.content,
                            type = MessageType.NUDGE,
                        ),
                    )
                }
                when (result) {
                    is OutgoingMessageResult.Staged -> {
                        _uiState.update { state ->
                            state.copy(messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            })
                        }
                        emitListPreviewForDecrypted(result.message)
                    }
                    is OutgoingMessageResult.Failed -> _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            groupEncryptionWarning = result.error.message?.take(120)
                                ?: text(R.string.chat_send_failed),
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    /**
     * 重发失败的消息
     * - 将消息状态改回 SENDING，然后重新走发送流程
     * - TEXT: 直接重新加密发送
     * - FILE/IMAGE/GIF/VIDEO/VOICE: 恢复持久化对象传输，或从保留的本机源重新加密
     */
    fun retrySendMessage(messageId: String) {
        // G66：重试准入同样走纯函数（归属、状态、类型三条一起判）
        val retryOwnerUserId = currentUserId
        val decision = ChatSendGuard.checkRetry(_uiState.value, messageId, retryOwnerUserId, token)
        if (decision is ChatSendGuard.RetryDecision.NeedsAttachmentRetry) {
            return sendEncryptedAttachment(
                Uri.parse(decision.message.parsedContent()),
                decision.message.type,
                messageId,
                decision.message,
            )
        }
        if (decision is ChatSendGuard.RetryDecision.Reject) {
            if (decision.reason == ChatSendGuard.RetryRejectReason.NO_SESSION) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            }
            return
        }
        // G328c：原先这里是 `!!`——checkRetry 与这次查找之间，撤销/批量删除/服务端投影都可能把消息移走，用户点「重试」会崩。找不到即返回。
        val failedMsg = _uiState.value.messages.find { it.id == messageId } ?: return
        val sendingMsg = failedMsg.copy(status = MessageStatus.SENDING)
        _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) sendingMsg else m }) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    outgoingFacade.retry(
                        OutgoingMessageCommand(
                            ownerUserId = retryOwnerUserId,
                            optimisticMessage = sendingMsg,
                            body = sendingMsg.content,
                            type = sendingMsg.type,
                        ),
                    )
                }
                _uiState.update { state ->
                    state.copy(messages = state.messages.map { message ->
                        when {
                            message.id != messageId -> message
                            result is OutgoingMessageResult.Staged -> result.message
                            result is OutgoingMessageResult.Failed -> result.message
                            else -> message
                        }
                    })
                }
                if (result is OutgoingMessageResult.Failed) {
                    Log.w(
                        "ChatDetailViewModel",
                        "v2 retry enqueue failed: ${result.error.message}",
                        result.error,
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = retryOwnerUserId,
                )
                ) {
                    return@launch
                }
                _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) failedMsg else m }) }
                withContext(Dispatchers.IO) { messageRepo.insertMessage(failedMsg) }
                Log.w("ChatDetailViewModel", "v2 retry enqueue failed: ${error.message}", error)
            }
        }
    }

    fun cancelSendMessage(messageId: String) {
        viewModelScope.launch {
            commandFacade.cancel(messageId)
        }
    }




    /**
     * 一键重试当前聊天所有失败/暂停任务；状态栏展示的中转数减为 0 后会自动收起浮窗。
     */

    /**
     * 一键清掉当前聊天的所有传输（已上传 READY 的不会被清掉）。
     */

    /** Optimistically hides a message after its encrypted delete event is staged. */
    internal fun deleteMessage(messageId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_REVOKE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val deleteOwnerUserId = currentUserId
        if (token.isBlank() || deleteOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            deleteOneMessage(messageId, deleteOwnerUserId)
        }
    }

    /** Serializes batch mutation commands so UI rollback and outbox order stay deterministic. */
    fun deleteMessagesBatch(messageIds: List<String>) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_REVOKE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val deleteOwnerUserId = currentUserId
        if (token.isBlank() || deleteOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            for (id in messageIds) {
                try {
                    deleteOneMessage(id, deleteOwnerUserId)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // deleteOneMessage 内部已处理回滚与日志，单条失败不中断后续
                    Log.w("ChatDetailViewModel", "batch delete item failed: $id", error)
                }
            }
        }
    }

    /** Single-item core shared by single and batch delete commands. */
    private suspend fun deleteOneMessage(messageId: String, deleteOwnerUserId: String) {
        val original = _uiState.value.messages.find { it.id == messageId } ?: return
        val outcome = conversationMessageMutationCoordinator.delete(
            original = original,
            ownerUserId = deleteOwnerUserId,
            groupRevision = currentGroupRevision(),
            project = ::projectMessageMutation,
        )
        applyMessageMutationOutcome(
            outcome = outcome,
            messageId = messageId,
            failureText = text(R.string.chat_delete_server_failed),
        )
    }

    fun revokeMessage(messageId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_REVOKE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            val original = _uiState.value.messages.find { it.id == messageId } ?: return@launch
            val revoked = original.toRevokedPlaceholder(text(R.string.chat_message_revoked_placeholder))
            val outcome = conversationMessageMutationCoordinator.revoke(
                original = original,
                revoked = revoked,
                ownerUserId = ownerUserId,
                groupRevision = currentGroupRevision(),
                project = ::projectMessageMutation,
            )
            applyMessageMutationOutcome(
                outcome = outcome,
                messageId = messageId,
                failureText = text(R.string.chat_revoke_failed),
            )
        }
    }

    fun editTextMessage(messageId: String, newText: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_EDIT)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val trimmed = newText.trim()
        val original = _uiState.value.messages.find { it.id == messageId } ?: return
        val ownerUserId = currentUserId
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_edit_empty)) }
            return
        }
        if (
            original.senderId != ownerUserId ||
            original.type !in setOf(MessageType.TEXT, MessageType.MARKDOWN) ||
            System.currentTimeMillis() - original.timestamp >= 300_000
        ) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_edit_not_allowed)) }
            return
        }
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            val meta = original.parsedMeta().copy(aiAssisted = false, aiAssistantMode = null)
            val optimistic = original.toOptimisticEdit(composeContentWithMeta(trimmed, meta))
            _uiState.update { it.copy(groupEncryptionWarning = null) }
            val outcome = conversationMessageMutationCoordinator.edit(
                original = original,
                updated = optimistic,
                ownerUserId = ownerUserId,
                groupRevision = currentGroupRevision(),
                project = ::projectMessageMutation,
            )
            applyMessageMutationOutcome(
                outcome = outcome,
                messageId = messageId,
                failureText = text(R.string.chat_edit_failed),
            )
        }
    }

    private fun projectMessageMutation(projection: MessageMutationProjection) {
        _uiState.update { state -> timelineStateController.applyMutation(state, projection) }
    }

    private fun applyMessageMutationOutcome(
        outcome: ConversationMessageMutationOutcome,
        messageId: String,
        failureText: String,
    ) {
        when (outcome) {
            is ConversationMessageMutationOutcome.Applied -> _uiState.update { state ->
                state.copy(
                    pinnedMessages = if (outcome.removePinnedReference) {
                        state.pinnedMessages.filterNot { it.messageId == messageId }
                    } else {
                        state.pinnedMessages
                    },
                    groupEncryptionWarning = if (outcome.localProjectionError != null) {
                        text(R.string.chat_mutation_committed_local_sync_pending)
                    } else {
                        state.groupEncryptionWarning
                    },
                )
            }
            is ConversationMessageMutationOutcome.Failed -> _uiState.update {
                it.copy(groupEncryptionWarning = outcome.error.message ?: failureText)
            }
            ConversationMessageMutationOutcome.Ignored -> Unit
        }
    }

    fun toggleStarMessage(messageId: String) = pinStarController.toggleStarMessage(messageId)

    fun toggleStarMessagesBatch(messageIds: List<String>, shouldStar: Boolean) =
        pinStarController.toggleStarMessagesBatch(messageIds, shouldStar)

    fun setMessageReaction(messageId: String, emoji: String) {
        if (!requireReactions()) return
        val reactionUserId = currentUserId
        if (token.isBlank() || reactionUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            when (
                val outcome = conversationReactionCoordinator.toggle(
                    messageId = messageId,
                    emoji = emoji,
                    ownerUserId = reactionUserId,
                    groupRevision = ::currentGroupRevision,
                    currentMessage = { id -> _uiState.value.messages.find { it.id == id } },
                    project = ::projectReactionMessage,
                )
            ) {
                is ConversationReactionOutcome.Applied -> if (outcome.localProjectionError != null) {
                    _uiState.update {
                        it.copy(
                            groupEncryptionWarning = text(
                                R.string.chat_mutation_committed_local_sync_pending,
                            ),
                        )
                    }
                }
                is ConversationReactionOutcome.Failed -> _uiState.update {
                    it.copy(
                        groupEncryptionWarning = outcome.error.message
                            ?: text(R.string.chat_reaction_failed),
                    )
                }
                ConversationReactionOutcome.Ignored -> Unit
            }
        }
    }

    private fun projectReactionMessage(message: Message) {
        _uiState.update { state ->
            if (state.messages.none { it.id == message.id }) {
                state
            } else {
                state.copy(
                    messages = state.messages.map { current ->
                        if (current.id == message.id) message else current
                    }
                )
            }
        }
    }

    fun loadReadReceipts(messageId: String) = readReceiptCoordinator.loadDetails(messageId)

    fun clearReadReceipts() = readReceiptCoordinator.clear()

    private fun maybeAutoLoadLastGroupReadCount() = readReceiptCoordinator.prefetchRecentGroupCounts()

    private fun hydrateMissingLocalAttachments(messages: List<Message>) {
        if (_uiState.value.isSecretChat == true) return
        messages.asSequence()
            .filter { message ->
                OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                    isSecretChat = false,
                    type = message.type,
                    attachmentId = message.parsedMeta().attachmentId,
                    localUriReadable = MediaCache.isReadableLocalUri(getApplication(), message.parsedContent()),
                    senderIsCurrentUser = message.senderId == currentUserId,
                    autoDownload = message.type in AUTO_DOWNLOAD_MEDIA_TYPES
                )
            }
            .take(12)
            .forEach { requestMediaAttachment(it.id) }
    }

    fun loadGroupReadCount(messageId: String, force: Boolean = false) =
        readReceiptCoordinator.loadCount(messageId, force)

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

    fun addGroupMember(userId: String) = botGroupActionController.addGroupMember(userId)

    fun removeGroupMember(userId: String) = botGroupActionController.removeGroupMember(userId)

    private suspend fun invalidateGroupSenderKey(groupId: String, newRevision: Long? = null) {
        groupMessagingCoordinator.invalidateSenderKey(
            chatId = groupId,
            ownerUserId = currentUserId,
            newRevision = newRevision,
        )
    }


    private val scheduledMessageController get() = deps.scheduledMessageController

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
    fun exportChatAsJson(): String = chatExportController.exportChatAsJson()
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

    fun notifyLocalCaptureDetected(message: String) {
        val msg = message.trim()
        if (msg.isBlank()) return
        val state = _uiState.value
        val disappearOn = (state.chat?.disappearingMessageSeconds ?: 0) > 0
        val shouldWarnPeer = (state.isSecretChat == true) || disappearOn
        _uiState.update {
            it.copy(
                groupEncryptionWarning = msg,
                secretChatInfoMessage = if (it.isSecretChat == true) msg else it.secretChatInfoMessage
            )
        }
        // Best-effort peer notice over E2EE (1:1 only). Debounced inside helper.
        if (shouldWarnPeer && state.chat?.isGroup != true) {
            sendCaptureAlertToPeer()
        }
    }

    private var lastCapturePeerNotifyAt
        get() = deps.lastCapturePeerNotifyAt
        set(value) { deps.lastCapturePeerNotifyAt = value }

    private fun sendCaptureAlertToPeer() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CAPTURE_ALERT)) return
        val now = System.currentTimeMillis()
        if (now - lastCapturePeerNotifyAt < 8_000L) return
        lastCapturePeerNotifyAt = now
        if (activeChatId.isBlank()) return
        if (_uiState.value.chat?.isGroup == true) return
        val label = com.maodouchat.session.CurrentSession.snapshot().userId?.take(8) ?: "me"
        val content = com.maodouchat.util.CaptureAlertPolicy.format(label, "screenshot")
        // Reuse sticker/nudge-like inline send pipeline (E2EE TEXT envelope).
        sendInlineContent(content, MessageType.TEXT, content)
    }

    /**
     * Share live location for [durationMs] (default 15 min). Sends an E2EE LOCATION payload
     * with live=true; further updates reuse the same sessionId via [updateLiveLocation].
     */

    internal fun sendInlineContent(content: String, type: MessageType, preview: String): String? {
        val sendOwnerUserId = currentUserId
        if (token.isBlank() || sendOwnerUserId.isBlank()) {
            _uiState.update {
                it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
            }
            return null
        }
        val msgId = "m_${UUID.randomUUID()}"
        val optimistic = Message(
            id = msgId,
            chatId = activeChatId,
            senderId = sendOwnerUserId,
            content = content,
            type = type,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
        )
        val completion = CompletableDeferred<Boolean>()
        inlineSendCompletions[msgId] = completion
        _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(optimistic)), isSending = true) }
        viewModelScope.launch {
            enqueueInlineViaMessagingV2(
                optimistic = optimistic,
                content = content,
                type = type,
                preview = preview,
                completion = completion,
            )
        }
        return msgId
    }

    private suspend fun enqueueInlineViaMessagingV2(
        optimistic: Message,
        content: String,
        type: MessageType,
        preview: String,
        completion: CompletableDeferred<Boolean>,
    ) {
        try {
            val result = withContext(Dispatchers.IO) {
                outgoingFacade.enqueue(
                    OutgoingMessageCommand(
                        ownerUserId = optimistic.senderId,
                        optimisticMessage = optimistic,
                        body = content,
                        type = type,
                    ),
                )
            }
            when (result) {
                is OutgoingMessageResult.Staged -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            isSending = false,
                        )
                    }
                    MaodouchatApp.emitMessageSent(result.message.chatId, preview, type.name)
                    completion.complete(true)
                }
                is OutgoingMessageResult.Failed -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            isSending = false,
                            groupEncryptionWarning = result.error.message?.take(120)
                                ?: text(R.string.chat_send_failed),
                        )
                    }
                    completion.complete(false)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update { it.copy(isSending = false) }
            completion.cancel(error)
            throw error
        } finally {
            inlineSendCompletions.remove(optimistic.id, completion)
        }
    }

    internal fun composeContentWithMeta(text: String, meta: com.maodouchat.data.model.MessageMeta): String =
        // 9.144：委托 JsonFormat 权威实现——本地手写清单漏字段（forwardedFrom 等）曾被静默丢弃
        com.maodouchat.util.JsonFormat.composeContentWithMeta(text, meta)

    /** 群通话入口：把当前群除自己外的成员挨个邀请 */
    fun startGroupCallFromChat(
        callType: com.maodouchat.webrtc.CallType,
        selectedMemberIds: Set<String>? = null
    ) {
        val chat = _uiState.value.chat ?: return
        if (!chat.isGroup) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CALLS)) {
            _uiState.update { it.copy(errorMessage = text(R.string.calls_disabled)) }
            return
        }
        val fineOk = when (callType) {
            com.maodouchat.webrtc.CallType.VIDEO -> RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIDEO_CALL)
            else -> RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VOICE_CALL)
        }
        if (!fineOk) {
            _uiState.update {
                it.copy(
                    errorMessage = text(
                        if (callType == com.maodouchat.webrtc.CallType.VIDEO) R.string.video_call_disabled
                        else R.string.voice_call_disabled
                    )
                )
            }
            return
        }
        val memberIds = chat.participants.map { it.id }.filter {
            it != currentUserId && (selectedMemberIds == null || it in selectedMemberIds)
        }
        if (memberIds.isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_call_empty)) }
            return
        }
        // 委派给 CallViewModel 处理（CallViewModel 需通过 NavGraph 注入；这里用单例 fallback）
        com.maodouchat.call.CallOrchestrator.requestGroupCall(chat.id, memberIds, callType)
    }

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
    ) {
        if (_uiState.value.isSending && fixedMessageId == null) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MEDIA_UPLOAD)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.media_upload_disabled)) }
            return
        }

        require(type in RELIABLE_ATTACHMENT_TYPES)
        val attachOwnerUserId = currentUserId
        if (token.isBlank() || attachOwnerUserId.isBlank()) {
            _uiState.update {
                it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
            }
            return
        }
        val messageId = fixedMessageId ?: "m_${UUID.randomUUID()}"
        val kind = when (type) {
            MessageType.IMAGE -> AttachmentKind.IMAGE
            MessageType.VIDEO -> AttachmentKind.VIDEO
            MessageType.VOICE -> AttachmentKind.VOICE
            MessageType.FILE -> AttachmentKind.FILE
            MessageType.GIF -> AttachmentKind.GIF
            else -> AttachmentKind.FILE
        }
        val isGroup = _uiState.value.chat?.isGroup == true
        val intent = AttachmentIntent(
            conversationId = activeChatId,
            kind = kind,
            uri = uri.toString(),
            idempotencyKey = messageId,
            durationMs = voiceDurationMs,
            viewOnce = viewOnce && !isGroup,
            spoilerMedia = spoilerMedia
        )

        val optimistic = existingMessage?.copy(
            chatId = activeChatId,
            content = uri.toString(),
            status = MessageStatus.SENDING,
            meta = MessageMeta(
                voiceDurationMs = voiceDurationMs,
                viewOnce = viewOnce && !isGroup,
                spoilerMedia = spoilerMedia
            )
        ) ?: Message(
            id = messageId,
            chatId = activeChatId,
            senderId = attachOwnerUserId,
            content = uri.toString(),
            type = type,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            meta = MessageMeta(
                voiceDurationMs = voiceDurationMs,
                viewOnce = viewOnce && !isGroup,
                spoilerMedia = spoilerMedia
            )
        )

        _uiState.update {
            it.copy(
                messages = mergeMessages(it.messages, listOf(optimistic)),
                isSending = true,
                fileTransferProgress = it.fileTransferProgress + (messageId to 0f),
                preparingAttachmentMessageIds = it.preparingAttachmentMessageIds + messageId,
                groupEncryptionWarning = null,
            )
        }

        val preparationJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val result = attachmentIntentController.submit(intent)
            result.fold(
                onSuccess = {
                    val queued = withContext(Dispatchers.IO) { messageRepo.getMessageById(messageId) }
                    _uiState.update { state ->
                        state.copy(
                            messages = if (queued != null) {
                                state.messages.map { current -> if (current.id == messageId) queued else current }
                            } else state.messages,
                            isSending = false,
                            preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - messageId,
                        )
                    }
                    if (existingMessage != null) {
                        resumeFileTransfer(messageId)
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) {
                        _uiState.update {
                            it.copy(
                                isSending = false,
                                preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId
                            )
                        }
                        throw error
                    }
                    android.util.Log.w("ChatDetailViewModel", "Attachment preparation failed: $messageId", error)
                    val failed = (withContext(Dispatchers.IO) { messageRepo.getMessageById(messageId) }
                        ?: existingMessage
                        ?: optimistic).copy(status = MessageStatus.FAILED)
                    _uiState.update {
                        it.copy(
                            messages = it.messages.map { current -> if (current.id == messageId) failed else current },
                            isSending = false,
                            fileTransferProgress = it.fileTransferProgress - messageId,
                            preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId,
                            groupEncryptionWarning = attachmentErrorText(error, R.string.chat_attachment_upload_failed)
                        )
                    }
                    withContext(Dispatchers.IO) { messageRepo.insertMessage(failed) }
                }
            )
        }
        attachmentPreparationJobs[messageId] = preparationJob
        preparationJob.invokeOnCompletion { error ->
            attachmentPreparationJobs.remove(messageId, preparationJob)
            if (error != null) {
                if (com.maodouchat.session.CurrentSession.snapshot().userId != attachOwnerUserId) return@invokeOnCompletion
                _uiState.update {
                    it.copy(
                        isSending = false,
                        preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId
                    )
                }
            }
        }
        preparationJob.start()
    }



    private val identityVerificationController get() = deps.identityVerificationController

    fun showSafetyCodeDialog() = identityVerificationController.showSafetyCodeDialog()
    fun dismissSafetyCodeDialog() = identityVerificationController.dismissSafetyCodeDialog()
    fun verifyAndTrustIdentity(deviceId: Int? = null) = identityVerificationController.verifyAndTrustIdentity(deviceId)
    fun verifyAllDevices() = identityVerificationController.verifyAllDevices()
    private val recipientId get() = deps.recipientId

    internal suspend fun hydrateOutgoingChat(
        chat: Chat,
        ownerUserId: String,
        resolvedPeerId: String?,
    ): ResolvedOutgoingChat {
        val session = ownerSession(ownerUserId)
        val localized = chat.copy(participants = chat.participants.map { withLocalNickname(it) })
        if (!isOwnerSessionCurrent(session)) {
            throw kotlinx.coroutines.CancellationException("hydrate_outgoing_session_changed")
        }
        val previousContact = _uiState.value.contact
        val peerId = resolvedPeerId
        if (!localized.isGroup && peerId == null) {
            throw IllegalStateException(text(R.string.chat_recipient_not_ready))
        }
        val contact = if (localized.isGroup) {
            User(
                id = localized.id,
                name = localized.groupName ?: text(R.string.chat_group),
                avatar = localized.groupAvatar,
                status = quantityText(
                    R.plurals.chat_members_count,
                    localized.participants.size,
                    localized.participants.size
                )
            )
        } else {
            localized.participants.firstOrNull { it.id == peerId }
                ?.takeUnless { it.name.isBlank() && previousContact.id == peerId }
                ?: previousContact.takeIf { it.id == peerId }
                ?: User(id = peerId.orEmpty(), name = "")
        }

        activeChatId = localized.id
        _uiState.update {
            it.copy(
                chat = localized,
                chatIsGroup = localized.isGroup,
                isSecretChat = localized.isSecret,
                contact = contact,
                disappearingMessageSeconds = if (localized.isGroup) 0 else localized.disappearingMessageSeconds
            )
        }
        occupySessionCipher(
            localized.id,
            peerUserId = peerId,
            updatePeer = true
        )
        return ResolvedOutgoingChat(localized.id, localized, peerId)
    }

    fun setSilentSend(enabled: Boolean) {
        if (enabled && !requireSilentSend()) return
        _uiState.update { it.copy(silentSend = enabled) }
    }

    fun toggleSilentSend() {
        val next = !_uiState.value.silentSend
        if (next && !requireSilentSend()) return
        _uiState.update { it.copy(silentSend = next) }
    }

    /**
     * Primary composer send for 1:1 and groups (text / markdown).
     * [replyTarget] embeds replyToId into E2EE MessageMeta.
     * [silent] overrides composer silentSend when non-null.
     */
    internal fun sendMessage(
        replyTarget: Message? = null,
        silent: Boolean? = null,
        forceText: String? = null,
        forcedMeta: MessageMeta? = null,
        onDurableCommit: (() -> Unit)? = null,
        onDurableFailure: (() -> Unit)? = null,
    ): Boolean {
        // G66：准入判定与 meta 组装全部下沉到纯函数 ChatSendGuard（可单测），这里只做副作用。
        val sendOwnerUserId = currentUserId
        val decision = ChatSendGuard.checkSend(
            state = _uiState.value,
            ownerUserId = sendOwnerUserId,
            token = token,
            silent = silent,
            forceText = forceText,
            mentionsEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MENTIONS),
            replyTarget = replyTarget,
            forcedMeta = forcedMeta,
        )
        if (decision is ChatSendGuard.SendDecision.Reject) {
            _uiState.update { it.copy(groupEncryptionWarning = sendRejectMessage(decision.reason)) }
            return false
        }
        val allowed = decision as ChatSendGuard.SendDecision.Allow
        // G68：待发意图统一走工厂（chatId 回落、id 生成、SENDING 初态都在那里）
        val optimistic = ChatSendIntentFactory.build(
            chatId = ChatSendIntentFactory.effectiveChatId(activeChatId, chatId),
            senderId = sendOwnerUserId,
            messageId = ChatSendIntentFactory.newMessageId(),
            timestamp = System.currentTimeMillis(),
            content = composeContentWithMeta(allowed.text, allowed.meta),
            type = allowed.messageType,
            meta = allowed.meta,
        )
        if (allowed.clearDraft) clearDraft()
        _uiState.update {
            it.copy(
                messages = mergeMessages(it.messages, listOf(optimistic)),
                inputText = if (allowed.clearDraft) "" else it.inputText,
                groupEncryptionWarning = null,
                isSending = true,
                silentSend = if (allowed.consumeSilent) false else it.silentSend
            )
        }
        viewModelScope.launch {
            enqueueTextViaMessagingV2(
                optimistic = optimistic,
                contentWithMeta = optimistic.content,
                plaintext = allowed.text,
                onDurableCommit = onDurableCommit,
                onDurableFailure = onDurableFailure,
            )
        }
        return true
    }

    private suspend fun enqueueTextViaMessagingV2(
        optimistic: Message,
        contentWithMeta: String,
        plaintext: String,
        onDurableCommit: (() -> Unit)?,
        onDurableFailure: (() -> Unit)?,
    ) {
        try {
            val result = withContext(Dispatchers.IO) {
                outgoingFacade.enqueue(
                    command = OutgoingMessageCommand(
                        ownerUserId = optimistic.senderId,
                        optimisticMessage = optimistic,
                        body = contentWithMeta,
                        type = optimistic.type,
                    ),
                    onDurableCommit = { onDurableCommit?.invoke() },
                    onDurableFailure = { onDurableFailure?.invoke() },
                    afterDurableCommit = { conversation, _ ->
                        try {
                            maybeForwardBotInbox(
                                liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty(),
                                chatId = conversation.conversationId,
                                plaintext = plaintext,
                                isGroup = conversation.isGroup,
                                peerId = conversation.peerUserId.orEmpty(),
                            )
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w("ChatDetailViewModel", "bot inbox follow-up failed", error)
                        }
                    },
                )
            }
            when (result) {
                is OutgoingMessageResult.Staged -> _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.id == result.message.id) result.message else it
                        },
                        isSending = false,
                    )
                }
                is OutgoingMessageResult.Failed -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            isSending = false,
                            groupEncryptionWarning = result.error.message?.take(120)
                                ?: text(R.string.chat_send_failed),
                        )
                    }
                    Log.w(
                        "ChatDetailViewModel",
                        "v2 text enqueue failed: ${result.error.message}",
                        result.error,
                    )
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update { it.copy(isSending = false) }
            throw error
        }
    }

    private fun maybeShowNewDeviceHistoryBanner(messages: List<Message>) {
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

    /** G69：导出拒绝原因 → 用户可见文案（资源字符串只应出现在编排层）。 */
    private fun exportRejectMessage(reason: ChatExportGuard.RejectReason): String = when (reason) {
        ChatExportGuard.RejectReason.DISABLED -> text(R.string.chat_export_disabled)
        ChatExportGuard.RejectReason.NO_SESSION -> text(R.string.error_session_expired)
        ChatExportGuard.RejectReason.STALE_SESSION -> text(R.string.error_session_expired)
        ChatExportGuard.RejectReason.LOCKED -> text(R.string.chat_lock_list_preview)
        ChatExportGuard.RejectReason.SECRET_CHAT -> text(R.string.secret_chat_export_blocked)
        ChatExportGuard.RejectReason.NO_CHAT -> text(R.string.chat_export_failed)
        ChatExportGuard.RejectReason.EMPTY -> text(R.string.chat_export_empty)
        ChatExportGuard.RejectReason.SERIALIZATION_EMPTY -> text(R.string.chat_export_failed)
    }

    fun exportToUri(context: android.content.Context, uri: android.net.Uri) {
        // G69：七条导出准入下沉到纯判定（可单测），这里只保留 IO 编排。
        val exportOwnerUserId = currentUserId
        val decision = ChatExportGuard.check(
            state = _uiState.value,
            exportEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_EXPORT),
            ownerUserId = exportOwnerUserId,
            token = token,
            sessionMayContinue = com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = exportOwnerUserId,
            ),
            serializedJson = exportChatAsJson(),
        )
        if (decision is ChatExportGuard.Decision.Reject) {
            _uiState.update { it.copy(exportInfoMessage = exportRejectMessage(decision.reason)) }
            return
        }
        val allowed = decision as ChatExportGuard.Decision.Allow
        val state = _uiState.value
        val json = exportChatAsJson()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = exportOwnerUserId,
                )
                ) {
                    return@launch
                }
                val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    true
                } ?: false
                if (written) {
                    _uiState.update {
                        it.copy(exportInfoMessage = quantityText(R.plurals.chat_export_success, state.messages.size, state.messages.size))
                    }
                } else {
                    Log.w("ChatDetailViewModel", "exportToUri: openOutputStream returned null")
                    _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_export_failed)) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "exportToUri failed", error)
                _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_export_failed)) }
            }
        }
    }

    companion object {
        /** 试听占用的伪 messageId，避免与真实消息播放冲突。 */
        const val VOICE_PREVIEW_MESSAGE_ID: String = "__voice_preview__"
        private const val HISTORY_PAGE_SIZE = 100
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
            val dao = app.database.attachmentTransferDao()
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
        // 8.45：离开会话仍向对端发送实时位置终态（内部经 applicationScope 发送，
        // 不依赖即将取消的 viewModelScope），避免对端残留 live 位置标记
        stopLiveLocationSharing(notifyPeer = true)
        // 离开聊天页必须释放麦克风与未完成的语音临时文件，避免幽灵录音占用 MIC
        stopRecordingMeter()
        runCatching { voiceRecorder.cancelRecording() }
        val previewPath = _uiState.value.voicePreviewPath
        if (previewPath != null) {
            runCatching { File(previewPath).delete() }
        }
        // 全局 VoicePlayer 不随 ViewModel 销毁；离开会话应停播，避免跨页串音
        runCatching { VoicePlayer.stop() }
        aiAutoRetryJobs.values.forEach { it.cancel() }
        aiAutoRetryJobs.clear()
        aiAutoRetryAt.clear()
        draftSaveJob?.cancel()
        draftSaveJob = null
        realtimeController.clear()
        val draftOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val draftChatId = activeChatId
        val draftText = _uiState.value.inputText
        // 8.49 修复：与 scheduleDraftPersistence/clearDraft/restoreDraft 一致检查 CHAT_DRAFTS
        // 运行时开关——管理员关闭草稿功能后，残余输入不应被持久化为明文草稿
        val draftsFeatureEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)
        // 捕获 chatDraftDao 到局部变量，避免 lambda 闭包捕获 this（ViewModel），
        // 从而防止 ViewModel 被 applicationScope 中的挂起引用阻止 GC 回收。
        val draftDao = chatDraftDao
        if (draftsFeatureEnabled && draftOwnerUserId.isNotBlank() && draftChatId.isNotBlank()) {
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                withContext(NonCancellable) {
                    // Soft-purge/logout may destroy Room or switch owner before this runs.
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(draftOwnerUserId)
                    ) {
                        return@withContext
                    }
                    if (draftText.isBlank()) {
                        draftDao.delete(draftOwnerUserId, draftChatId)
                    } else {
                        draftDao.upsert(
                            ChatDraftEntity(
                                ownerUserId = draftOwnerUserId,
                                chatId = draftChatId,
                                text = draftText,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
        val finalReadWatermark = pendingReadWatermarkMessageId
        readSeenMessages.clearAll()
        pendingReadWatermarkMessageId = null
        markReadJob?.cancel()
        markReadJob = null
        val currentChatId = activeChatId
        val readOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val readIsSecret = _uiState.value.isSecretChat == true
        val readGroupRevision = _uiState.value.chat?.memberRevision
            ?.takeIf { _uiState.value.chat?.isGroup == true }
        val messagingOutbox = app.messagingV2Outbox
        releaseSessionCipher()
        if (currentChatId.isNotBlank() && readOwnerUserId.isNotBlank() && finalReadWatermark != null) {
            MaodouchatApp.instance.applicationScope.launch {
                try {
                    withContext(NonCancellable) {
                        val liveToken = com.maodouchat.session.CurrentSession.snapshot().token
                        val liveUserId = com.maodouchat.session.CurrentSession.snapshot().userId
                        if (liveToken.isNullOrBlank() || liveUserId != readOwnerUserId) return@withContext
                        if (!DisappearingMessagePolicy.shouldSkipReadReceipts(readIsSecret)) {
                            messagingOutbox.enqueueReadReceipt(
                                conversationId = currentChatId,
                                throughMessageId = finalReadWatermark,
                                groupRevision = readGroupRevision,
                            )
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("ChatDetailViewModel", "final v2 read receipt enqueue failed", error)
                }
            }
        }
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
    internal suspend fun withLocalNickname(user: User): User {
        if (!user.nickname.isNullOrBlank()) return user
        val nick = try {
            userRepo.getUserById(user.id)?.nickname
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return if (nick.isNullOrBlank()) user else user.copy(nickname = nick)
    }
}

/** 可解密的消息类型（G183 从 ChatDetailViewModel 成员扩展抽成顶层纯函数：它不碰实例状态，留着只会无法单测）。 */
internal fun MessageType.isDecryptable(): Boolean = this in setOf(
    MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
    MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
    MessageType.VOICE, MessageType.FILE,
)
