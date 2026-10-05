package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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
    internal val messageRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.messageRepo()
    internal val attachmentDownloadCoordinator = AttachmentDownloadCoordinator(
        context = application,
        messageStore = messageRepo,
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
    // G380：消息展示与搜索索引（列表预览文案、解密后回写预览、本地全文索引）
    // 抽到 ChatMessagePreviewController——VM 侧四个函数直接搬出，无内部调用者留守。
    internal val messagePreviewController = ChatMessagePreviewController(
        chatProvider = { host._uiState.value.chat },
        contactProvider = { host._uiState.value.contact },
        currentUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        textProvider = host::text,
    )
    internal val messageGateway by lazy {
        MessagingV2MessageGateway(
            database = host.app.database,
            messageStore = messageRepo,
            outbox = host.app.messagingV2Outbox,
            indexMessage = messagePreviewController::indexSearchableMessage,
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
                commandFacade = commandFacade,
            ownerUserId = { host.currentUserId },
            onProgress = { id, completed, total ->
                host.updateFileTransferProgress(id, completed, total, 0f, 0.35f)
            },
        )
    }
    internal val chatLockRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.chatLockRepo()
    internal val secretTtlRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.secretTtlRepo()
    internal val messageTerminalStore = MessageTerminalStore(
        deleteCachedMedia = { messageId ->
            MediaCache.deleteCachedMediaForMessage(application, messageId)
        },
        deleteSearchDocument = com.maodouchat.chatdetail.ChatDetailDataAccess.messageSearchDao()::deleteDocument,
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
        indexMessage = messagePreviewController::indexSearchableMessage,
        cleanupAttachment = host::cleanupAttachmentForMessage,
        refreshConversationPreview = com.maodouchat.chatdetail.ChatDetailAccess::emitChatListPreviewRefresh,
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
    internal val aiSummaryRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.aiSummaryRepo()
    internal val aiTaskRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.aiTaskRepo()
    internal val aiOperationRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.aiOperationRepo()
    internal val userRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.userRepo()
    internal val chatRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.chatRepo()
    internal val chatDraftDao = com.maodouchat.chatdetail.ChatDetailDataAccess.chatDraftDao()
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
        ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
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
        scheduleCoordinator = conversationScheduleCoordinator,
    )
    internal val voiceRecorder = VoiceRecorder(application)
    internal val recordingWaveformBuffer = VoiceRecordingWaveform()
    internal var recordingMeterJob: Job? = null
    internal val signalProtocol: SignalProtocol = host.app.signalProtocol
    internal val groupMessagingCoordinator = createAndroidGroupMessagingCoordinator(
        app = host.app,
        signalProtocol = signalProtocol,
    )
    internal val groupLifecycleCoordinator = GroupLifecycleCoordinator(
        ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        token = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
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
            tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
            membershipStore = host.app.groupMembershipStore,
        )
    }
    internal val conversationForwardCoordinator by lazy {
        ConversationForwardCoordinator(
            ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
            token = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
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
                com.maodouchat.chatdetail.ChatDetailAccess.emitMessageSent(targetChatId, preview, type.name)
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
        dao = com.maodouchat.chatdetail.ChatDetailDataAccess.readReceiptSource(),
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
    internal val nudgeSender = ChatNudgeSender(
        scope = host.viewModelScope,
        nudgeEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.NUDGE) },
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        mergeMessages = host::mergeMessages,
        emitListPreviewForDecrypted = messagePreviewController::emitListPreviewForDecrypted,
        outgoingFacade = outgoingFacade,
        text = { id, args -> host.text(id, *args) },
    )
    // G346：`retrySendMessage()` 的重发编排抽到 ChatRetrySender，附件重发分流回 VM
    // 的 `sendEncryptedAttachment`（仍在 VM 内的下一个待切片）。
    internal val retrySender = ChatRetrySender(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        outgoingFacade = outgoingFacade,
        persistMessage = messageRepo::insertMessage,
        sendAttachmentRetry = { uri, type, messageId, message ->
            host.sendEncryptedAttachment(uri, type, messageId, message)
        },
        text = { id, args -> host.text(id, *args) },
    )
    // G349：`sendEncryptedAttachment()` 的附件发送编排抽到 ChatAttachmentSender——
    // VM 侧只留同签名委托（默认参数保留在 VM）。
    internal val attachmentSender = ChatAttachmentSender(
        scope = host.viewModelScope,
        activeChatId = { host.activeChatId },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        mediaUploadEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.MEDIA_UPLOAD) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        mergeMessages = host::mergeMessages,
        attachmentIntentController = attachmentIntentController,
        getMessageById = messageRepo::getMessageById,
        persistMessage = messageRepo::insertMessage,
        resumeFileTransfer = host::resumeFileTransfer,
        preparationJobs = attachmentPreparationJobs,
        currentSessionUserId = { com.maodouchat.session.CurrentSession.snapshot().userId.orEmpty() },
        attachmentErrorText = host::attachmentErrorText,
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
        sessionContextProvider = com.maodouchat.session.SessionContexts.provider(application),
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
        persistDisappearingMessages = { targetChatId, seconds ->
            chatRepo.getChatById(targetChatId)?.let { local ->
                chatRepo.cacheChats(listOf(local.copy(disappearingMessageSeconds = seconds)))
            }
        },
        applyRealtimeVisibility = { event ->
            com.maodouchat.chatdetail.ChatDetailDataAccess.applyRealtimeVisibility(
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
    internal val voicePlaybackReporter = ChatVoicePlaybackReporter(
        scope = host.viewModelScope,
        currentUserId = { host.currentUserId },
        currentState = host._uiState::value,
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
    )
    internal val messageMutationController = ChatMessageMutationController(
        scope = host.viewModelScope,
        getApplication = { host.getApplication<android.app.Application>() },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { id -> host.text(id) },
        currentGroupRevision = host::currentGroupRevision,
        composeContentWithMeta = host::composeContentWithMeta,
        requireReactions = host::requireReactions,
        projectMessageMutation = host::projectMessageMutation,
        mutationCoordinator = conversationMessageMutationCoordinator,
        reactionCoordinator = conversationReactionCoordinator,
    )
    internal val chatSettingToggleController = ChatSettingToggleController(
        scope = host.viewModelScope,
        getApplication = { host.getApplication<android.app.Application>() },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        chatId = { host.chatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { id -> host.text(id) },
    )
    // G378：`jumpToDate()`/`jumpToMessage()`/`jumpToPinnedMessage()` 跳转定位抽到 ChatJumpController，
    // `notifyLocalCaptureDetected()`/`sendCaptureAlertToPeer()` 截屏告警抽到 ChatCaptureAlertController——
    // VM 侧只留同签名委托。
    internal val chatJumpController = ChatJumpController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
        updateState = { transform -> host._uiState.update(transform) },
        text = { id -> host.text(id) },
        messageRepo = messageRepo,
        searchSelectionStateController = searchSelectionStateController,
    )
    internal val chatCaptureAlertController = ChatCaptureAlertController(
        getApplication = { host.getApplication<android.app.Application>() },
        activeChatId = { host.activeChatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sendInlineContent = host::sendInlineContent,
        getLastPeerNotifyAt = { lastCapturePeerNotifyAt },
        setLastPeerNotifyAt = { lastCapturePeerNotifyAt = it },
    )
    // G379：`sendMessage()`/`enqueueTextViaMessagingV2()`/`sendRejectMessage()`/`toggleSilentSend()`
    // 发送流水线抽到 ChatComposerSendController——VM 侧只留同签名委托。
    internal val composerSendController = ChatComposerSendController(
        scope = host.viewModelScope,
        getApplication = { host.getApplication<android.app.Application>() },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        textProvider = host::text,
        requireSilentSend = { host.requireSilentSend() },
        composeContentWithMeta = host::composeContentWithMeta,
        mergeMessages = host::mergeMessages,
        clearDraft = { host.clearDraft() },
        maybeForwardBotInbox = botGroupActionController::maybeForwardBotInbox,
        outgoingFacade = outgoingFacade,
    )
    // G381：`sendInlineContent()`/`enqueueInlineViaMessagingV2()` 内联发送抽到 ChatInlineSendController——
    // VM 侧只留同签名委托（扩展函数与截屏告警控制器仍走 VM 委托）。
    internal val inlineSendController = ChatInlineSendController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        updateState = { transform -> host._uiState.update(transform) },
        textProvider = host::text,
        mergeMessages = host::mergeMessages,
        completions = inlineSendCompletions,
        outgoingFacade = outgoingFacade,
    )
    // G382：`sendContactCard()` 名片发送抽到 ChatContactCardController——VM 侧只留同签名委托。
    internal val contactCardController = ChatContactCardController(
        getApplication = { host.getApplication<android.app.Application>() },
        textProvider = host::text,
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sendMessage = { cardContent -> host.sendMessage(forceText = cardContent) },
    )
    // G383：`observeAiOperations()`/`commitAiMessageResult()` AI 操作一族抽到
    // ChatAiOperationsController——VM 侧只留同签名委托。
    internal val aiOperationsController = ChatAiOperationsController(
        scope = host.viewModelScope,
        updateState = { transform -> host._uiState.update(transform) },
        activeChatId = { host.activeChatId },
        aiOperationRepo = aiOperationRepo,
        aiMessageResultStore = aiMessageResultStore,
        aiSummaryRepo = aiSummaryRepo,
        aiTaskRepo = aiTaskRepo,
        aiAutoRetryJobs = aiAutoRetryJobs,
        aiAutoRetryAt = aiAutoRetryAt,
    )
    // G384：`observeAttachmentFinalizedEvents()`/`observeAttachmentTransfers()` 附件观察一族抽到
    // ChatAttachmentObservationController——VM 侧只留同签名委托。
    internal val attachmentObservationController = ChatAttachmentObservationController(
        scope = host.viewModelScope,
        getApplication = { host.getApplication<android.app.Application>() },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        activeChatId = { host.activeChatId },
        mergeMessages = host::mergeMessages,
        mediaStateController = mediaStateController,
    )
    // G385：`observeLocalMessages()`/`observeAuthoritativeMessageMutations()` 本地消息观察一族抽到
    // ChatMessageObservationController——VM 侧只留同签名委托。
    internal val messageObservationController = ChatMessageObservationController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
        messageRepo = messageRepo,
        timelineStateController = timelineStateController,
        mutationCoordinator = conversationMessageMutationCoordinator,
        updateState = { transform -> host._uiState.update(transform) },
        projectMessageMutation = host::projectMessageMutation,
    )
    // G386：`observeMessageStatus()` 消息状态观察一族抽到
    // ChatMessageStatusObservationController——VM 侧只留同签名委托。
    internal val messageStatusObservationController = ChatMessageStatusObservationController(
        uiState = host._uiState,
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
        updateState = { transform -> host._uiState.update(transform) },
        getLastMessagesSeen = { lastMessagesSeen },
        setLastMessagesSeen = { lastMessagesSeen = it },
        readSeenMessages = readSeenMessages,
        setPendingReadWatermarkMessageId = { pendingReadWatermarkMessageId = it },
        getMarkReadJob = { markReadJob },
        setMarkReadJob = { markReadJob = it },
        armSecretDisappearing = host::armSecretDisappearing,
    )
    // G350：`handleGroupRevisionChanged()` 的群修订编排抽到 ChatGroupRevisionHandler——
    // VM 侧只留同签名委托。
    internal val groupRevisionHandler: ChatGroupRevisionHandler = ChatGroupRevisionHandler(
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { id -> host.text(id) },
        conversationLocalStateCoordinator = conversationLocalStateCoordinator,
        realtimeController = realtimeController,
        semanticSearchGate = semanticSearchGate,
        aiRewriteGate = aiRewriteGate,
        aiReplyGate = aiReplyGate,
        groupAiGate = groupAiGate,
        manualSummaryGate = manualSummaryGate,
        semanticSearchJob = { semanticSearchJob },
        aiRewriteStreamJob = { aiRewriteStreamJob },
        aiReplyStreamJob = { aiReplyStreamJob },
        groupAiJob = { groupAiJob },
        manualSummaryJob = { manualSummaryJob },
        unreadSummaryJob = { unreadSummaryJob },
        aiOperationJobs = aiOperationJobs,
        aiAutoRetryJobs = aiAutoRetryJobs,
        aiAutoRetryAt = aiAutoRetryAt,
        groupMessagingCoordinator = groupMessagingCoordinator,
        reloadChat = host::loadChat,
        refreshMyMemberRole = host::refreshMyMemberRole,
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
        signalProtocol = signalProtocol,
        resetDirectIdentityForGroup = groupSecurityStateController::resetDirectIdentityForGroup,
        uiState = host._uiState,
        text = host::text,
    )

    // G387：session cipher 占用一族抽到 ChatSessionCipherController——VM 侧只留同签名委托。
    internal val sessionCipherController = ChatSessionCipherController(
        initialChatId = host.chatId,
        chatRepo = chatRepo,
        currentUserId = { host.currentUserId },
    )
    // G387：消息加载/分页/刷新一族抽到 ChatMessageLoadingController——VM 侧只留同签名委托。
    internal val messageLoadingController = ChatMessageLoadingController(
        uiState = host._uiState,
        scope = host.viewModelScope,
        application = application,
        chatId = host.chatId,
        getActiveChatId = { host.activeChatId },
        getCurrentUserId = { host.currentUserId },
        chatRepo = chatRepo,
        messageRepo = messageRepo,
        groupLifecycleService = groupLifecycleService,
        groupSecurityStateController = groupSecurityStateController,
        timelineStateController = timelineStateController,
        pinStarController = pinStarController,
        cipherController = sessionCipherController,
        botGroupActionController = botGroupActionController,
        moderationController = moderationController,
        scheduledMessageController = scheduledMessageController,
        identityVerificationController = identityVerificationController,
        groupMessagingCoordinator = groupMessagingCoordinator,
        readReceiptCoordinator = readReceiptCoordinator,
        text = { id, args -> host.text(id, *args) },
        quantityText = { id, quantity, args -> host.quantityText(id, quantity, *args) },
        withLocalNickname = host::withLocalNickname,
        requestMediaAttachment = host::requestMediaAttachment,
        maybeGenerateUnreadSummary = host::maybeGenerateUnreadSummary,
        armSecretDisappearing = host::armSecretDisappearing,
        showNewDeviceHistoryBanner = host::maybeShowNewDeviceHistoryBanner,
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
