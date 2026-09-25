package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ai.AiChatClassificationSource
import com.maodouchat.ai.AiConversationProfileSource
import com.maodouchat.ai.AiEmotionReplySource
import com.maodouchat.ai.AiWeeklyReportSource
import com.maodouchat.attachment.AttachmentDownloadCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository
import com.maodouchat.attachment.DefaultAttachmentIntentController
import com.maodouchat.attachment.DefaultAttachmentPreparationService
import com.maodouchat.attachment.RoomTransferRepository
import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator
import com.maodouchat.crypto.DecryptHistoryPolicy
import com.maodouchat.crypto.OwnSentMediaRestorePolicy
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.AttachmentKind
import com.maodouchat.forwarding.ConversationForwardCoordinator
import com.maodouchat.group.GroupLifecycleCoordinator
import com.maodouchat.messaging.v2.ConversationMessageMutationCoordinator
import com.maodouchat.messaging.v2.ConversationMessageMutationOutcome
import com.maodouchat.messaging.v2.ConversationReactionCoordinator
import com.maodouchat.messaging.v2.ConversationReactionOutcome
import com.maodouchat.messaging.v2.MessageMutationKind
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.messaging.v2.MessagingV2EventOutbox
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.messaging.v2.MessagingV2MutationFacade
import com.maodouchat.messaging.v2.OutgoingConversationErrors
import com.maodouchat.messaging.v2.OutgoingConversationRequest
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.scheduling.ChatScheduleController
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.util.VoicePlayer
import com.maodouchat.util.VoiceRecorder
import com.maodouchat.util.VoiceRecordingWaveform
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
import com.maodouchat.data.repository.ChatNetworkRepository

/**
 * 聊天详情页的**装配**（G328c 从 `ChatDetailViewModel` 搬出，纯搬移不改判断）。
 *
 * 搬的是什么：88 个仓库 / 控制器 / 协调器的构造（475 行）——它们与行为无关，却占了 VM 近 1/5。
 * 搬出后 VM 里剩下的几乎都是行为，读起来是连续的；而这些「谁依赖谁、按什么顺序构造」的知识
 * 集中在这一处。
 *
 * 依赖方向：`ChatDetailDeps` 持有 [ChatDetailViewModel] 的引用（构造时传入），
 * 在需要读 VM 状态或调用 VM 方法时用 `host.xxx`。反向调用只发生在 lambda / 方法引用里
 * （构造期不触碰），所以运行期不会形成环。本类是 `internal`，不对外暴露。
 *
 * 搬移只做了四类机械改写：`_uiState`/`chatId`/`token`/`app` 等 VM 成员 → `host.xxx`；
 * `getApplication()` → 构造参数 `application`；`viewModelScope` → `host.viewModelScope`；
 * 隐式 `this` 的方法引用 `::m` → `host::m`。**每一行的构造顺序与内容都与搬出前一致**
 * （顺序有语义：后面的协调器引用前面构造出来的仓库）。
 */
internal class ChatDetailDeps(
    private val application: Application,
    private val host: ChatDetailViewModel,
) {
    internal val aiConversationProfileSource: AiConversationProfileSource =
        com.maodouchat.data.local.RoomAiConversationProfileSource(application, host.app.database)
    internal val aiChatClassificationSource: AiChatClassificationSource =
        com.maodouchat.data.local.RoomAiChatClassificationSource(application, host.app.database)
    internal val aiEmotionReplySource: AiEmotionReplySource =
        com.maodouchat.data.local.RoomAiEmotionReplySource(application, host.app.database)
    internal val aiWeeklyReportSource: AiWeeklyReportSource =
        com.maodouchat.data.local.RoomAiWeeklyReportSource(application, host.app.database)
    internal val messageRepo = LocalMessageStore(host.app.database.messageDao(), host.app.database)
    internal val attachmentDownloadCoordinator = AttachmentDownloadCoordinator(
        context = application,
        messageStore = messageRepo,
        tokenManager = TokenManager.getInstance(application),
        isSecretChat = { message ->
            host._uiState.value.isSecretChat == true || host.app.secretConversationController.capabilities(message.chatId).isSecretChat
        },
        onProgress = host::updateFileTransferProgress,
        onMessageUpdated = { updated ->
            host._uiState.update { state ->
                state.copy(messages = state.messages.map { current ->
                    if (current.id == updated.id) updated else current
                })
            }
        },
    )
    internal val messageGateway by lazy {
        MessagingV2MessageGateway(
            database = host.app.database,
            messageStore = messageRepo,
            outbox = host.app.messagingV2Outbox,
            indexMessage = host::indexSearchableMessage,
        )
    }
    internal val commandFacade by lazy {
        ConversationCommandFacade(
            gateway = messageGateway,
            resolveChat = chatRepo::getChatById,
            getMessage = messageRepo::getMessageById,
            ownerUserId = { host.currentUserId },
        )
    }
    internal val attachmentIntentController: AttachmentIntentController by lazy {
        DefaultAttachmentIntentController(
            context = application,
            transferRepository = RoomTransferRepository(host.app),
            preparationService = DefaultAttachmentPreparationService(application),
            messageStore = messageRepo,
            tokenManager = tokenManager,
            commandFacade = commandFacade,
            ownerUserId = { host.currentUserId },
            onProgress = { id, completed, total ->
                host.updateFileTransferProgress(id, completed, total, 0f, 0.35f)
            },
        )
    }
    internal val chatLockRepo = com.maodouchat.data.repository.ChatLockRepository(host.app.database.chatLockDao())
    internal val secretTtlRepo = com.maodouchat.data.repository.SecretChatRepository(host.app.database.secretChatDao())
    internal val messageTerminalStore = MessageTerminalStore(
        deleteCachedMedia = { messageId ->
            MediaCache.deleteCachedMediaForMessage(application, messageId)
        },
        deleteSearchDocument = host.app.database.messageSearchDao()::deleteDocument,
        deleteLocalMessage = messageRepo::deleteMessage,
        upsertLocalMessage = { message -> messageRepo.applyRevokedMessage(message) }
    )
    internal val messagingMutationFacade = MessagingV2MutationFacade(
        eventOutbox = MessagingV2EventOutbox { conversationId, event, groupRevision ->
            host.app.messagingV2Outbox.enqueueEvent(conversationId, event, groupRevision)
        },
        persistDeleted = messageTerminalStore::persistDeleted,
        persistRevoked = messageTerminalStore::persistRevoked,
        persistEdited = messageRepo::applyEditedMessage,
        persistReaction = { original, reactions, actorUserId, reactionEmoji ->
            val reactedAt = reactions.lastOrNull { it.userId == actorUserId }?.reactedAt
                ?: System.currentTimeMillis()
            messageRepo.mutateMessageReactions(original.id) { existing ->
                com.maodouchat.messaging.v2.ReactionMutationPolicy.apply(
                    existing = existing,
                    actorUserId = actorUserId,
                    emoji = reactionEmoji,
                    reactedAt = reactedAt,
                )
            }
        },
        indexMessage = host::indexSearchableMessage,
        cleanupAttachment = host::cleanupAttachmentForMessage,
        refreshConversationPreview = MaodouchatApp::emitChatListPreviewRefresh,
        isOwnerSessionCurrent = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        cleanupTerminalNotification = { message ->
            if (host.app.notificationCenter.removeMessageReferences(message.id)) {
                com.maodouchat.notification.MessageNotificationService.cancelMessage(application, message.chatId)
            }
        },
    )
    internal val conversationMessageMutationCoordinator =
        ConversationMessageMutationCoordinator(messagingMutationFacade)
    internal val conversationReactionCoordinator =
        ConversationReactionCoordinator(messagingMutationFacade)
    internal val aiSummaryRepo = AiSummaryRepository(host.app.database.aiSummaryCacheDao())
    internal val aiTaskRepo = AiTaskRepository(host.app.database.aiTaskDao(), application)
    internal val aiOperationRepo = AiOperationRepository(host.app.database.aiOperationDao())
    internal val userRepo = UserRepository(host.app.database.userDao())
    internal val chatRepo = ChatRepository(host.app.database.chatDao(), host.app.database.userDao())
    internal val chatDraftDao = host.app.database.chatDraftDao()
    internal val tokenManager = TokenManager.getInstance(application)
    internal val outgoingFacade by lazy {
        ChatOutgoingFacade(
            getCachedConversation = chatRepo::getChatById,
            fetchConversations = { liveToken ->
                ChatNetworkRepository().chats(liveToken).getOrThrow().map { it.toDomainChat() }
            },
            createDirectConversation = { liveToken, recipientId, secret ->
                ChatNetworkRepository().createChat(
                    liveToken,
                    listOf(recipientId),
                    isGroup = false,
                    groupName = null,
                    chatType = if (secret) com.maodouchat.security.SecretChatPolicy.CHAT_TYPE else null,
                ).getOrThrow().toDomainChat()
            },
            cacheConversation = { chatRepo.cacheChats(listOf(it)) },
            ensureLocalCryptoReady = signalProtocol::ensureLocalCryptoReady,
            isBotUserId = com.maodouchat.bot.BotCommandPolicy::isBotUserId,
            isOwnerSessionCurrent = { ownerUserId ->
                com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
            },
            errors = OutgoingConversationErrors(
                notLoggedIn = { host.text(R.string.chat_not_logged_in) },
                recipientNotReady = { host.text(R.string.chat_recipient_not_ready) },
                cannotSendToSelf = { host.text(R.string.chat_cannot_send_self) },
            ),
            currentRequest = {
                val state = host._uiState.value
                OutgoingConversationRequest(
                    ownerUserId = host.currentUserId,
                    authToken = host.token,
                    activeConversationId = host.activeChatId,
                    constructorConversationId = host.chatId,
                    paintedConversation = state.chat,
                    activeContactId = state.contact.id,
                    createSecretConversation = state.chat?.isSecret == true || state.isSecretChat == true,
                )
            },
            hydrateConversation = host::hydrateOutgoingChat,
            stageDurableMessage = { message, groupRevision, body, type ->
                messageGateway.stageAndEnqueue(message, groupRevision, body, type)
            },
            retryDurableMessage = { message, groupRevision, body, type ->
                messageGateway.retry(message, groupRevision, body, type)
            },
            persistFailedMessage = messageRepo::insertMessage,
            currentOwnerUserId = { host.currentUserId },
            currentAuthToken = { host.token },
            findCachedDirectConversation = chatRepo::findCachedDirectChat,
            createOfflineDirectConversation = chatRepo::createOfflineDirectChat,
            commandFacade = commandFacade,
        )
    }
    internal val conversationScheduleCoordinator = ConversationScheduleCoordinator(
        ownerUserId = { tokenManager.getUserId().orEmpty() },
        backend = AndroidConversationScheduleBackend(application),
    )
    internal val chatScheduleController = ChatScheduleController(
        coordinator = conversationScheduleCoordinator,
        scheduledMessagesEnabled = {
            RuntimeFlags.isEnabled(application, RuntimeFlags.SCHEDULED_MESSAGES)
        },
    )
    internal val conversationLocalStateCoordinator = createAndroidConversationLocalStateCoordinator(
        app = host.app,
        tokenManager = tokenManager,
        scheduleCoordinator = conversationScheduleCoordinator,
    )
    internal val voiceRecorder = VoiceRecorder(application)
    internal val recordingWaveformBuffer = VoiceRecordingWaveform()
    internal var recordingMeterJob: Job? = null
    internal val signalProtocol: SignalProtocol = host.app.signalProtocol
    internal val groupMessagingCoordinator = createAndroidGroupMessagingCoordinator(
        app = host.app,
        signalProtocol = signalProtocol,
        tokenManager = tokenManager,
    )
    internal val groupLifecycleCoordinator = GroupLifecycleCoordinator(
        ownerUserId = { tokenManager.getUserId().orEmpty() },
        token = { tokenManager.getToken().orEmpty() },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        fetchChat = { liveToken, targetChatId ->
            ChatNetworkRepository().chats(liveToken).map { chats ->
                chats.firstOrNull { it.id == targetChatId }
            }
        },
        invalidateEpoch = groupMessagingCoordinator::invalidateSenderKey,
    )
    internal val groupLifecycleService: com.maodouchat.group.GroupLifecycleService by lazy {
        com.maodouchat.group.DefaultGroupLifecycleService(
            coordinator = groupLifecycleCoordinator,
            tokenProvider = { tokenManager.getToken().orEmpty().ifBlank { host.token } },
            membershipStore = host.app.groupMembershipStore,
        )
    }
    internal val conversationForwardCoordinator by lazy {
        ConversationForwardCoordinator(
            ownerUserId = { tokenManager.getUserId().orEmpty() },
            token = { tokenManager.getToken().orEmpty() },
            sessionActive = { ownerUserId ->
                com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
            },
            fetchTargets = { _ ->
                val cached = chatRepo.getAllChats().first()
                Result.success(cached)
            },
            resolveTargets = { _, targets ->
                targets.mapNotNull { chatRepo.getChatById(it.id) ?: it }
            },
            stageMessage = { message, groupRevision ->
                messageGateway.stageAndEnqueue(
                    message = message,
                    groupRevision = groupRevision,
                    body = message.content,
                    type = message.type,
                )
            },
            attachmentIntentController = attachmentIntentController,
            getMessageById = { id -> messageRepo.getMessageById(id) },
            getChatById = { id -> chatRepo.getChatById(id) },
            isChatLocked = { id -> !com.maodouchat.security.ChatLockSession.isUnlocked(id) && (chatLockRepo.get(id) != null) },
            onDurableMessage = { message ->
                if (message.chatId == host.activeChatId) {
                    host._uiState.update { state ->
                        state.copy(messages = host.mergeMessages(state.messages, listOf(message)))
                    }
                }
            },
            onMessageSent = { targetChatId, preview, type ->
                MaodouchatApp.emitMessageSent(targetChatId, preview, type.name)
            },
            preview = { type, content -> host.forwardPreview(type, content) },
        )
    }
    /** Senders that already got one ensureSessions this chat open (history must not storm). */
    internal val aiMessageResultStore = AiMessageResultStore(host.app.database)
    internal val readSeenMessages = ChatReadWatermarkPolicy.SeenSet()
    internal var pendingReadWatermarkMessageId: String? = null
    internal var lastMessagesSeen: List<Pair<String, MessageStatus>>? = null
    internal var markReadJob: kotlinx.coroutines.Job? = null
    internal var pendingAiAction: PendingAiAction? = null
    internal var pendingAiOperationRetryId: String? = null
    internal var aiSettingsLoaded = false
    internal var unreadSummaryAttemptedForKey: String? = null
    /** In-flight auto unread summary key; prevents concurrent duplicate summarizeChat calls. */
    internal var unreadSummaryInFlightKey: String? = null
    internal val semanticSearchGate = AiRequestGenerationGate()
    internal val attachmentPreparationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    internal val aiOperationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    internal val aiAutoRetryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    internal val aiAutoRetryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val aiOperationQueueMutex = Mutex()
    internal val aiOperationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    internal var aiRewriteStreamJob: kotlinx.coroutines.Job? = null
    internal var aiReplyStreamJob: kotlinx.coroutines.Job? = null
    internal var semanticSearchJob: kotlinx.coroutines.Job? = null
    internal var groupAiJob: kotlinx.coroutines.Job? = null
    internal var manualSummaryJob: kotlinx.coroutines.Job? = null
    internal var unreadSummaryJob: kotlinx.coroutines.Job? = null
    internal val aiRewriteGate = AiRequestGenerationGate()
    internal val aiReplyGate = AiRequestGenerationGate()
    internal val groupAiGate = AiRequestGenerationGate()
    internal val manualSummaryGate = AiRequestGenerationGate()
    internal var lastAiRewriteMode = "polish"
    internal var lastAiRewriteTargetLanguage: String? = null
    internal var lastAiReplyTone: String = "friendly"
    internal var liveLocationJob: kotlinx.coroutines.Job? = null
    internal var liveLocationCancel: (() -> Unit)? = null
    internal val liveLocationUpdateMutex = Mutex()
    internal val inlineSendCompletions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile internal var lastLiveLocationPayload: com.maodouchat.data.model.LocationPayload? = null
    internal var draftSaveJob: kotlinx.coroutines.Job? = null
    /** Bumped on every schedule/clear so a late persist cannot resurrect a cleared draft. */
    internal var draftGeneration = 0L
    internal val timelineStateController = ChatTimelineStateController(host::mergeMessages)
    internal val searchSelectionStateController = ChatSearchSelectionStateController()
    internal val groupSecurityStateController = ChatGroupSecurityStateController()
    internal val mediaStateController = ChatMediaStateController()
    internal val readReceiptCoordinator = ChatReadReceiptCoordinator(
        scope = host.viewModelScope,
        dao = com.maodouchat.data.local.RoomReadReceiptSource(host.app.database.messagingV2Dao()),
        currentUserId = { host.currentUserId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        receiptsEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.READ_RECEIPTS) },
        errorMessage = { error -> error.message ?: host.text(R.string.chat_read_details_failed) },
    )
    internal val pinStarController = ChatPinStarController(
        application = application,
        scope = host.viewModelScope,
        chatId = { host.chatId },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        persistMessage = messageRepo::insertMessage,
        text = { id, args -> host.text(id, *args) },
    )
    internal val moderationController = ChatModerationController(
        application = application,
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { id, args -> host.text(id, *args) },
    )
    internal val botGroupActionController = ChatBotGroupActionController(
        scope = host.viewModelScope,
        groupLifecycleCoordinator = groupLifecycleCoordinator,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { id, args -> host.text(id, *args) },
        quantityText = { id, quantity, args -> host.quantityText(id, quantity, *args) },
    )
    internal val realtimeController = ChatRealtimeController(
        application = application,
        scope = host.viewModelScope,
        tokenManager = tokenManager,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        persistDisappearingMessages = { targetChatId, seconds ->
            chatRepo.getChatById(targetChatId)?.let { local ->
                chatRepo.cacheChats(listOf(local.copy(disappearingMessageSeconds = seconds)))
            }
        },
        applyRealtimeVisibility = { event ->
            host.app.database.userDao().applyRealtimeVisibility(
                userId = event.userId,
                isOnline = event.isOnline,
                onlineRevoked = event.onlineRevoked,
                statusRevoked = event.statusRevoked,
                updatedAt = System.currentTimeMillis(),
            )
        },
        onGroupRevisionChanged = host::handleGroupRevisionChanged,
        text = { id -> host.text(id) },
    )
    internal val scheduledMessageController = ScheduledMessageController(
        chatScheduleController = chatScheduleController,
        uiState = host._uiState,
        textProvider = host::text,
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
        clearDraft = { host.clearDraft() },
        sendMessage = { forceText, onDurableCommit, onDurableFailure ->
            host.sendMessage(forceText = forceText, onDurableCommit = onDurableCommit, onDurableFailure = onDurableFailure)
        },
    )
    internal val chatReminderController = ChatReminderController(
        chatScheduleController = chatScheduleController,
        uiState = host._uiState,
        textProvider = host::text,
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
    )
    internal val chatExportController = ChatExportController(
        messageRepo = messageRepo,
        uiState = host._uiState,
        textProvider = host::text,
        context = application,
        scope = host.viewModelScope,
    )
    internal var lastCapturePeerNotifyAt = 0L
    internal val identityVerificationController = IdentityVerificationController(
        context = application,
        scope = host.viewModelScope,
        tokenManager = tokenManager,
        signalProtocol = signalProtocol,
        resetDirectIdentityForGroup = groupSecurityStateController::resetDirectIdentityForGroup,
        uiState = host._uiState,
        text = host::text,
    )
    internal val recipientId: String get() = host._uiState.value.contact.id
    internal val fileTransferController by lazy {
        ChatDetailFileTransferController(
            uiState = host._uiState,
            scope = host.viewModelScope,
            applicationScope = host.app.applicationScope,
            context = application,
            activeChatId = { host.activeChatId },
            messageRepo = messageRepo,
            mediaStateController = mediaStateController,
            attachmentIntentController = attachmentIntentController,
            attachmentPreparationJobs = attachmentPreparationJobs,
            text = { res -> host.text(res) },
            ensureLocalAttachment = host::ensureLocalAttachment,
            retryAllTransfers = { chatId -> AttachmentTransferSummaryRepository.retryAll(host.app, host.chatId) },
            cancelAllTransfers = { chatId -> AttachmentTransferSummaryRepository.cancelAll(host.app, host.chatId) },
        )
    }
    internal val decryptStatus by lazy {
        ChatDetailDecryptStatus(
            gate = signalProtocol,
            texts = DecryptTexts(
                failed = host.text(R.string.chat_decrypt_failed),
                pending = host.text(R.string.chat_decrypt_pending),
                sessionMissing = host.text(R.string.chat_decrypt_session_missing),
                identityChanged = host.text(R.string.chat_decrypt_identity_changed),
                groupFailed = host.text(R.string.chat_decrypt_group_failed),
                groupKeyMissing = host.text(R.string.chat_decrypt_group_key_missing),
                groupIdentityChanged = host.text(R.string.chat_decrypt_group_identity_changed),
                groupNewer = host.text(R.string.chat_decrypt_group_newer),
                imageFailed = host.text(R.string.chat_decrypt_image_failed),
                gifFailed = host.text(R.string.chat_decrypt_gif_failed),
                stickerFailed = host.text(R.string.chat_decrypt_sticker_failed),
                locationFailed = host.text(R.string.chat_decrypt_location_failed),
                videoFailed = host.text(R.string.chat_decrypt_video_failed),
                voiceFailed = host.text(R.string.chat_decrypt_voice_failed),
                fileFailed = host.text(R.string.chat_decrypt_file_failed),
            ),
        )
    }
}
