package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.core.model.AiConversationProfileSource
import com.maodouchat.attachment.AttachmentDownloadCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository
import com.maodouchat.attachment.DefaultAttachmentIntentController
import com.maodouchat.attachment.DefaultAttachmentPreparationService
import com.maodouchat.attachment.RoomTransferRepository
import com.maodouchat.domain.messaging.DefaultConversationCommandFacade
import com.maodouchat.domain.messaging.AiOperationRecord
import com.maodouchat.domain.messaging.ChatAiOperationsController
import com.maodouchat.domain.messaging.ChatNudgeSender
import com.maodouchat.domain.messaging.ChatOutgoingFacade
import com.maodouchat.domain.messaging.ChatRetrySender
import com.maodouchat.domain.messaging.ScheduledMessageController
import com.maodouchat.domain.messaging.ChatOutgoingHydrationController
import com.maodouchat.domain.messaging.ChatSessionCipherController
import com.maodouchat.domain.messaging.ChatVoicePlaybackReporter
import com.maodouchat.domain.messaging.ChatComposerSendController
import com.maodouchat.domain.messaging.ChatInlineSendController
import com.maodouchat.domain.messaging.ChatGroupRevisionHandler
import com.maodouchat.domain.messaging.ChatMessageLoadingController
import com.maodouchat.domain.messaging.ChatMessageMutationController
import com.maodouchat.domain.messaging.ChatMessagePreviewController
import com.maodouchat.domain.messaging.ChatMessageStatusObservationController
import com.maodouchat.domain.messaging.IdentityVerificationController
import com.maodouchat.domain.messaging.ChatSessionTeardownController
import com.maodouchat.domain.messaging.ChatExportController
import com.maodouchat.domain.messaging.ChatFileTransferController
import com.maodouchat.domain.messaging.ChatRealtimeController
import com.maodouchat.domain.messaging.ChatRealtimeText
import com.maodouchat.domain.messaging.UserPresenceSnapshot
import com.maodouchat.core.model.mergeMessageVersions
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.UserNetworkRepository
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.DefaultConversationForwardCoordinator
import com.maodouchat.domain.conversation.ChatAttachmentObservationController
import com.maodouchat.domain.conversation.ChatCaptureAlertController
import com.maodouchat.domain.conversation.ChatContactCardController
import com.maodouchat.domain.conversation.ChatGroupCallController
import com.maodouchat.domain.conversation.ChatDetailLoadCoordinator
import com.maodouchat.domain.conversation.ChatGroupSecurityStateController
import com.maodouchat.domain.conversation.ChatJumpController
import com.maodouchat.core.model.ChatMediaStateController
import com.maodouchat.domain.conversation.ChatMessageObservationController
import com.maodouchat.domain.conversation.ChatModerationController
import com.maodouchat.domain.conversation.ChatPinStarController
import com.maodouchat.domain.conversation.ChatReadReceiptCoordinator
import com.maodouchat.domain.conversation.ChatReminderController
import com.maodouchat.domain.conversation.ChatSearchSelectionStateController
import com.maodouchat.domain.conversation.ChatSettingToggleController
import com.maodouchat.domain.conversation.ChatTimelineStateController
import com.maodouchat.domain.groups.ChatBotGroupActionController
import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.group.createAndroidGroupLifecycleService
import com.maodouchat.core.model.ConversationMessageMutationCoordinator
import com.maodouchat.core.model.ConversationReactionCoordinator
import com.maodouchat.core.model.MessagingV2EventOutbox
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.core.model.MessagingV2MutationFacade
import com.maodouchat.core.model.OutgoingConversationErrors
import com.maodouchat.core.model.OutgoingConversationRequest
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.core.model.ChatScheduleController
import com.maodouchat.core.model.ConversationScheduleCoordinator
import com.maodouchat.util.ChatExport
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.util.VoiceRecorder
import com.maodouchat.core.model.AiChatClassificationSource
import com.maodouchat.core.model.AiEmotionReplySource
import com.maodouchat.core.model.AiWeeklyReportSource
import com.maodouchat.core.model.ChatDetailAiBookkeeping
import com.maodouchat.core.model.ChatDetailDraftBookkeeping
import com.maodouchat.core.model.ChatDetailLiveLocationBookkeeping
import com.maodouchat.core.model.ChatDetailReadReceiptBookkeeping
import com.maodouchat.core.crypto.ChatDetailDecryptStatus
import com.maodouchat.core.model.DecryptTexts
import com.maodouchat.core.model.MessageTerminalStore
import com.maodouchat.core.model.ReactionMutationPolicy
import com.maodouchat.core.model.VoiceRecordingWaveform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import com.maodouchat.core.model.toDomainChat
// 构造顺序有语义：后面的协调器引用前面构造出来的仓库。
internal class ChatDetailDeps(
    private val application: Application,
    private val host: ChatDetailViewModel,
) {
    internal val aiConversationProfileSource: AiConversationProfileSource = com.maodouchat.data.local.RoomAiConversationProfileSource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val aiChatClassificationSource: AiChatClassificationSource = com.maodouchat.data.local.RoomAiChatClassificationSource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val aiEmotionReplySource: AiEmotionReplySource = com.maodouchat.data.local.RoomAiEmotionReplySource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val aiWeeklyReportSource: AiWeeklyReportSource = com.maodouchat.data.local.RoomAiWeeklyReportSource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val messageRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.messageRepo()
    internal val attachmentDownloadCoordinator = AttachmentDownloadCoordinator(
        context = application,
        messageStore = messageRepo,
        isSecretChat = { message -> host._uiState.value.isSecretChat == true || com.maodouchat.security.SecretChatCapabilities.forChat(message.chatId).isSecretChat },
        onProgress = host::updateFileTransferProgress,
        onMessageUpdated = { updated -> host._uiState.update { state -> state.copy(messages = state.messages.map { current -> if (current.id == updated.id) updated else current }) } },
    )
    internal val messagePreviewController = ChatMessagePreviewController(
        chatProvider = { host._uiState.value.chat }, contactProvider = { host._uiState.value.contact },
        currentUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        textProvider = { kind, args -> host.text(chatMessagePreviewTextResId(kind), *args) },
        emitListPreview = { chatId, preview, typeName, playSendSound -> com.maodouchat.chatdetail.ChatDetailAccess.emitMessageSent(chatId, preview, typeName, playSendSound = playSendSound) },
        allowsSearchIndex = { chatId -> com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(com.maodouchat.security.SecretChatCapabilities.forChat(chatId), com.maodouchat.domain.messaging.PrivacyAction.SEARCH) },
        indexMessage = { message -> com.maodouchat.chatdetail.ChatDetailDataAccess.messageSearchRepository().indexMessage(message) },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val messageGateway by lazy {
        MessagingV2MessageGateway(database = com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase, messageStore = messageRepo, outbox = com.maodouchat.chatdetail.ChatDetailAccess.messagingOutbox, indexMessage = messagePreviewController::indexSearchableMessage)
    }
    internal val commandFacade by lazy {
        DefaultConversationCommandFacade(gateway = messageGateway, resolveChat = chatRepo::getChatById, getMessage = messageRepo::getMessageById, ownerUserId = { host.currentUserId })
    }
    internal val attachmentIntentController: AttachmentIntentController by lazy {
        DefaultAttachmentIntentController(context = application, transferRepository = RoomTransferRepository(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase.attachmentTransferDao()), preparationService = DefaultAttachmentPreparationService(application), messageStore = messageRepo,
            commandFacade = commandFacade, ownerUserId = { host.currentUserId }, onProgress = { id, completed, total -> host.updateFileTransferProgress(id, completed, total, 0f, 0.35f) })
    }
    internal val chatLockRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.chatLockRepo()
    internal val secretTtlRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.secretTtlRepo()
    internal val messageTerminalStore = MessageTerminalStore(
        deleteCachedMedia = { messageId -> MediaCache.deleteCachedMediaForMessage(application, messageId) }, deleteSearchDocument = com.maodouchat.chatdetail.ChatDetailDataAccess.messageSearchDao()::deleteDocument, deleteLocalMessage = messageRepo::deleteMessage, upsertLocalMessage = { message -> messageRepo.applyRevokedMessage(message) }
    )
    internal val messagingMutationFacade = MessagingV2MutationFacade(
        eventOutbox = MessagingV2EventOutbox { conversationId, event, groupRevision -> com.maodouchat.chatdetail.ChatDetailAccess.messagingOutbox.enqueueEvent(conversationId, event, groupRevision) },
        persistDeleted = messageTerminalStore::persistDeleted,
        persistRevoked = messageTerminalStore::persistRevoked,
        persistEdited = messageRepo::applyEditedMessage,
        persistReaction = { original, reactions, actorUserId, reactionEmoji ->
            val reactedAt = reactions.lastOrNull { it.userId == actorUserId }?.reactedAt ?: System.currentTimeMillis()
            messageRepo.mutateMessageReactions(original.id) { existing -> ReactionMutationPolicy.apply(existing = existing, actorUserId = actorUserId, emoji = reactionEmoji, reactedAt = reactedAt) }
        },
        indexMessage = messagePreviewController::indexSearchableMessage,
        cleanupAttachment = host::cleanupAttachmentForMessage,
        refreshConversationPreview = com.maodouchat.chatdetail.ChatDetailAccess::emitChatListPreviewRefresh,
        isOwnerSessionCurrent = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        cleanupTerminalNotification = { message -> if (com.maodouchat.notification.NotificationCenterAccess.repository.removeMessageReferences(message.id)) { com.maodouchat.notification.MessageNotificationService.cancelMessage(application, message.chatId) } },
    )
    internal val conversationMessageMutationCoordinator = ConversationMessageMutationCoordinator(messagingMutationFacade)
    internal val conversationReactionCoordinator = ConversationReactionCoordinator(messagingMutationFacade)
    internal val aiSummaryRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.aiSummaryRepo()
    internal val aiTaskRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.aiTaskRepo()
    internal val aiOperationRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.aiOperationRepo()
    internal val userRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.userRepo()
    internal val chatRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.chatRepo()
    internal val chatDraftDao = com.maodouchat.chatdetail.ChatDetailDataAccess.chatDraftDao()
    internal val outgoingFacade by lazy {
        ChatOutgoingFacade(
            getCachedConversation = chatRepo::getChatById,
            fetchConversations = { liveToken -> ChatNetworkRepository().chats(liveToken).getOrThrow().map { it.toDomainChat() } },
            createDirectConversation = { liveToken, recipientId, secret -> ChatNetworkRepository().createChat(liveToken, listOf(recipientId), isGroup = false, groupName = null, chatType = if (secret) com.maodouchat.core.session.SecretChatPolicy.CHAT_TYPE else null).getOrThrow().toDomainChat() },
            cacheConversation = { chatRepo.cacheChats(listOf(it)) },
            ensureLocalCryptoReady = signalProtocol::ensureLocalCryptoReady,
            isBotUserId = com.maodouchat.core.model.BotCommandPolicy::isBotUserId,
            isOwnerSessionCurrent = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
            errors = OutgoingConversationErrors(
                notLoggedIn = { host.text(R.string.chat_not_logged_in) }, recipientNotReady = { host.text(R.string.chat_recipient_not_ready) }, cannotSendToSelf = { host.text(R.string.chat_cannot_send_self) },
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
            stageDurableMessage = { message, groupRevision, body, type -> messageGateway.stageAndEnqueue(message, groupRevision, body, type) },
            retryDurableMessage = { message, groupRevision, body, type -> messageGateway.retry(message, groupRevision, body, type) },
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
        scheduledMessagesEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.SCHEDULED_MESSAGES) },
    )
    internal val conversationLocalStateCoordinator = com.maodouchat.chatdetail.ChatDetailAccess.conversationLocalStateCoordinator(
        scheduleCoordinator = conversationScheduleCoordinator,
    )
    internal val voiceRecorder = VoiceRecorder(application)
    internal val recordingWaveformBuffer = VoiceRecordingWaveform()
    internal var recordingMeterJob: Job? = null
    internal val signalProtocol: SignalProtocol = com.maodouchat.crypto.SignalProtocolAccess.protocol
    internal val groupMessagingCoordinator = com.maodouchat.group.GroupDetailAccess.groupMessagingCoordinator(application)
    internal val groupLifecycleCoordinator = GroupLifecycleCoordinator(
        ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        token = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        fetchChat = { liveToken, targetChatId -> ChatNetworkRepository().chats(liveToken).map { chats -> chats.firstOrNull { it.id == targetChatId } } },
        invalidateEpoch = groupMessagingCoordinator::invalidateSenderKey,
    )
    internal val groupLifecycleService: GroupLifecycleService by lazy {
        createAndroidGroupLifecycleService(
            coordinator = groupLifecycleCoordinator,
            membershipStore = com.maodouchat.group.GroupDetailAccess.groupMembershipStore,
        )
    }
    internal val conversationForwardCoordinator by lazy {
        DefaultConversationForwardCoordinator(
            ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
            token = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
            sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
            fetchTargets = { _ ->
                val cached = chatRepo.getAllChats().first()
                Result.success(cached)
            },
            resolveTargets = { _, targets -> targets.mapNotNull { chatRepo.getChatById(it.id) ?: it } },
            stageMessage = { message, groupRevision ->
                messageGateway.stageAndEnqueue(message = message, groupRevision = groupRevision,
                    body = message.content, type = message.type)
            },
            attachmentIntentController = attachmentIntentController,
            getMessageById = { id -> messageRepo.getMessageById(id) },
            getChatById = { id -> chatRepo.getChatById(id) },
            isChatLocked = { id -> !com.maodouchat.security.ChatLockSession.isUnlocked(id) && (chatLockRepo.get(id) != null) },
            onDurableMessage = { message ->
                if (message.chatId == host.activeChatId) {
                    host._uiState.update { state ->
                        state.copy(messages = mergeMessageVersions(state.messages, listOf(message)))
                    }
                }
            },
            onMessageSent = { targetChatId, preview, type -> com.maodouchat.chatdetail.ChatDetailAccess.emitMessageSent(targetChatId, preview, type.name) },
            preview = { type, content -> host.forwardPreview(type, content) },
        )
    }
    internal val aiMessageResultStore = AiMessageResultStore(com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val readReceiptBookkeeping = ChatDetailReadReceiptBookkeeping()
    internal val aiBookkeeping = ChatDetailAiBookkeeping()
    internal val liveLocationBookkeeping = ChatDetailLiveLocationBookkeeping()
    internal val draftBookkeeping = ChatDetailDraftBookkeeping()
    internal val attachmentPreparationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    internal val inlineSendCompletions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    internal val timelineStateController = ChatTimelineStateController(::mergeMessageVersions)
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
        messagePinEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.MESSAGE_PIN) },
        messageStarringEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.MESSAGE_STARRING) },
        secretStarBlockEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.SECRET_STAR_BLOCK) },
        pinStarGateway = PinStarNetworkGateway(),
        scope = host.viewModelScope,
        chatId = { host.chatId },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        persistMessage = messageRepo::insertMessage,
        text = { message, args -> host.text(pinStarMessageResId(message), *args) },
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
        mergeMessages = ::mergeMessageVersions,
        emitListPreviewForDecrypted = messagePreviewController::emitListPreviewForDecrypted,
        outgoingFacade = outgoingFacade,
        text = { message, args -> host.text(chatSenderMessageResId(message), *args) },
    )
    internal val retrySender = ChatRetrySender(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        outgoingFacade = outgoingFacade,
        persistMessage = messageRepo::insertMessage,
        sendAttachmentRetry = { uriString, type, messageId, message -> host.sendEncryptedAttachment(uriString.toUri(), type, messageId, message) },
        text = { message, args -> host.text(chatSenderMessageResId(message), *args) },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val attachmentSender = ChatAttachmentSender(
        scope = host.viewModelScope,
        activeChatId = { host.activeChatId },
        ownerUserId = { host.currentUserId },
        token = { host.token },
        mediaUploadEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.MEDIA_UPLOAD) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        mergeMessages = ::mergeMessageVersions,
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
        moderationGateway = ChatModerationNetworkGateway(),
        moderationEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.BLOCK_REPORT) },
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { message, args -> host.text(chatModerationMessageResId(message), *args) },
    )
    internal val botGroupActionController = ChatBotGroupActionController(
        scope = host.viewModelScope,
        groupLifecycleCoordinator = groupLifecycleCoordinator,
        botGroupActionGateway = BotGroupActionNetworkGateway(),
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { message, args -> host.text(botGroupActionMessageResId(message), *args) },
        quantityText = { message, quantity, args -> host.quantityText(botGroupActionMessageResId(message), quantity, *args) },
        logWarning = { android.util.Log.w("ChatDetail", it) },
    )
    internal val realtimeController = ChatRealtimeController(
        sessionContextProvider = com.maodouchat.session.SessionContexts.provider(application),
        scope = host.viewModelScope,
        realtime = com.maodouchat.core.realtime.AccountScopedRealtimeConnectionManager(
            sessionContextProvider = com.maodouchat.session.SessionContexts.provider(application),
            transport = com.maodouchat.network.WebSocketTransport,
            scope = host.viewModelScope,
        ),
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        wsUrl = { com.maodouchat.network.ApiConfig.WS_URL },
        presenceEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.PRESENCE) },
        secretPresenceBlocked = { RuntimeFlags.isEnabled(application, RuntimeFlags.SECRET_PRESENCE_BLOCK) },
        typingIndicatorsEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.TYPING_INDICATORS) },
        secretTypingBlocked = { RuntimeFlags.isEnabled(application, RuntimeFlags.SECRET_TYPING_BLOCK) },
        purgeInProgress = com.maodouchat.security.SecureSessionManager::isPurgeInProgress,
        fetchUserPresence = { liveToken, contactId ->
            UserNetworkRepository().user(liveToken, contactId).map { UserPresenceSnapshot(it.isOnline, it.lastSeen) }
        },
        persistDisappearingMessages = { targetChatId, seconds ->
            chatRepo.getChatById(targetChatId)?.let { local ->
                chatRepo.cacheChats(listOf(local.copy(disappearingMessageSeconds = seconds)))
            }
        },
        applyRealtimeVisibility = { event ->
            com.maodouchat.chatdetail.ChatDetailDataAccess.applyRealtimeVisibility(
                userId = event.userId, isOnline = event.isOnline,
                onlineRevoked = event.onlineRevoked,
                statusRevoked = event.statusRevoked,
                updatedAt = System.currentTimeMillis(),
            )
        },
        onGroupRevisionChanged = host::handleGroupRevisionChanged,
        text = { key: ChatRealtimeText -> host.text(chatRealtimeTextResId(key)) },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val voicePlaybackReporter = ChatVoicePlaybackReporter(
        scope = host.viewModelScope,
        currentUserId = { host.currentUserId },
        currentState = host._uiState::value,
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
        playingMessageIds = com.maodouchat.util.VoicePlayer.state
            .filter { it.isPlaying && it.messageId != null }
            .mapNotNull { it.messageId },
        sessionMayContinue = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)
        },
        enqueuePlayReceipt = { conversationId, messageId, groupRevision ->
            com.maodouchat.chatdetail.ChatDetailAccess.messagingOutbox.enqueuePlayReceipt(
                conversationId = conversationId,
                messageId = messageId,
                groupRevision = groupRevision,
            )
        },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val messageMutationController = ChatMessageMutationController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { message -> host.text(chatMessageMutationTextResId(message)) },
        currentGroupRevision = host::currentGroupRevision,
        composeContentWithMeta = host::composeContentWithMeta,
        requireReactions = host::requireReactions,
        projectMessageMutation = host::projectMessageMutation,
        mutationCoordinator = conversationMessageMutationCoordinator,
        reactionCoordinator = conversationReactionCoordinator,
        messageRevokeEnabled = { RuntimeFlags.isEnabled(host.getApplication(), RuntimeFlags.MESSAGE_REVOKE) },
        messageEditEnabled = { RuntimeFlags.isEnabled(host.getApplication(), RuntimeFlags.MESSAGE_EDIT) },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val chatSettingToggleController = ChatSettingToggleController(
        scope = host.viewModelScope,
        chatPinEnabled = { RuntimeFlags.isEnabled(host.getApplication<android.app.Application>(), RuntimeFlags.CHAT_PIN) },
        sessionActive = { ownerUserId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId) },
        liveToken = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
        chatSettingsGateway = ChatSettingsNetworkGateway(),
        ownerUserId = { host.currentUserId },
        token = { host.token },
        chatId = { host.chatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        featureDisabledText = { host.text(R.string.feature_disabled_by_admin) },
    )
    internal val chatJumpController = ChatJumpController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
        updateState = { transform -> host._uiState.update(transform) },
        text = { message -> host.text(chatJumpMessageResId(message)) },
        sessionActive = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        getFirstMessageAtOrAfter = { targetChatId, dayStart -> messageRepo.getFirstMessageAtOrAfter(targetChatId, dayStart)?.id },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
        searchSelectionStateController = searchSelectionStateController,
    )
    internal val chatCaptureAlertController = ChatCaptureAlertController(
        activeChatId = { host.activeChatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sendInlineContent = host::sendInlineContent,
        getLastPeerNotifyAt = { lastCapturePeerNotifyAt },
        setLastPeerNotifyAt = { lastCapturePeerNotifyAt = it },
        captureAlertEnabled = { RuntimeFlags.isEnabled(host.getApplication(), RuntimeFlags.CAPTURE_ALERT) },
        currentUserLabel = { com.maodouchat.session.CurrentSession.snapshot().userId?.take(8) ?: "me" },
    )
    internal val composerSendController = ChatComposerSendController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        chatId = { host.chatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        textProvider = host::text,
        requireSilentSend = { host.requireSilentSend() },
        composeContentWithMeta = host::composeContentWithMeta,
        mergeMessages = ::mergeMessageVersions,
        clearDraft = { host.clearDraft() },
        maybeForwardBotInbox = botGroupActionController::maybeForwardBotInbox,
        outgoingFacade = outgoingFacade,
        mentionsEnabled = { RuntimeFlags.isEnabled(host.getApplication(), RuntimeFlags.MENTIONS) },
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
        sendInFlightRes = R.string.chat_send_in_flight,
        sendEmptyRes = R.string.chat_send_empty,
        sessionExpiredRes = R.string.error_session_expired,
        blockedUserStatusRes = R.string.chat_blocked_user_status,
        mentionEveryoneRestrictedRes = R.string.chat_mention_everyone_restricted,
        sendFailedRes = R.string.chat_send_failed,
    )
    internal val inlineSendController = ChatInlineSendController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        token = { host.token },
        activeChatId = { host.activeChatId },
        updateState = { transform -> host._uiState.update(transform) },
        textProvider = host::text,
        mergeMessages = ::mergeMessageVersions,
        completions = inlineSendCompletions,
        outgoingFacade = outgoingFacade,
        onMessageSent = { chatId, preview, typeName -> com.maodouchat.chatdetail.ChatDetailAccess.emitMessageSent(chatId, preview, typeName) },
        sessionExpiredRes = R.string.error_session_expired,
        sendFailedRes = R.string.chat_send_failed,
    )
    internal val contactCardController = ChatContactCardController(
        contactCardEnabled = { RuntimeFlags.isEnabled(host.getApplication(), RuntimeFlags.CONTACT_CARD) },
        text = { message -> host.text(chatContactCardMessageResId(message)) },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sendMessage = { cardContent -> host.sendMessage(forceText = cardContent) },
    )
    internal val aiOperationsController = ChatAiOperationsController(
        scope = host.viewModelScope,
        updateState = { transform -> host._uiState.update(transform) },
        activeChatId = { host.activeChatId },
        currentOwnerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        sessionMayContinue = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)
        },
        observeOperations = { ownerUserId, chatId ->
            aiOperationRepo.observeActionable(ownerUserId, chatId).map { operations ->
                operations.map { operation ->
                    AiOperationRecord(
                        id = operation.id,
                        type = operation.type,
                        state = operation.state,
                        attempts = operation.attempts,
                        lastErrorCode = operation.lastErrorCode,
                    )
                }
            }
        },
        recoverInterrupted = { ownerUserId, chatId -> aiOperationRepo.recoverInterrupted(ownerUserId, chatId) },
        pruneTerminalOperations = { aiOperationRepo.pruneTerminal() },
        pruneSummariesOlderThan = { before -> aiSummaryRepo.pruneOlderThan(before) },
        pruneCompletedTasksOlderThan = { before -> aiTaskRepo.pruneCompletedOlderThan(before) },
        commitMessageResult = { operationId, message -> aiMessageResultStore.commit(operationId, message) },
        aiAutoRetryJobs = aiBookkeeping.aiAutoRetryJobs,
        aiAutoRetryAt = aiBookkeeping.aiAutoRetryAt,
    )
    internal val attachmentObservationController = ChatAttachmentObservationController(
        scope = host.viewModelScope,
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        activeChatId = { host.activeChatId },
        mergeMessages = ::mergeMessageVersions,
        mediaStateController = mediaStateController,
        attachmentFinalizedEvents = com.maodouchat.chatdetail.ChatDetailAccess.attachmentFinalizedEvents,
        currentSessionGeneration = com.maodouchat.chatdetail.ChatDetailAccess::currentSessionGeneration,
        attachmentTransfers = com.maodouchat.chatdetail.ChatDetailDataAccess.observeAttachmentTransferObservations(),
        ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        scheduleFinalSend = { messageId, ownerUserId -> com.maodouchat.attachment.AttachmentTransferScheduler.schedule(host.getApplication(), messageId, ownerUserId) },
    )
    internal val messageObservationController = ChatMessageObservationController(
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
        observeLocalMessages = messageRepo::getMessagesByChatId,
        mutationEvents = com.maodouchat.chatdetail.ChatDetailAccess.messagingMutationEvents.events,
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        timelineStateController = timelineStateController,
        mutationCoordinator = conversationMessageMutationCoordinator,
        updateState = { transform -> host._uiState.update(transform) },
        projectMessageMutation = host::projectMessageMutation,
    )
    private val enqueueReadReceipt: suspend (String, String, Long?) -> Unit = { conversationId, throughMessageId, groupRevision ->
        com.maodouchat.chatdetail.ChatDetailAccess.messagingOutbox.enqueueReadReceipt(
            conversationId = conversationId,
            throughMessageId = throughMessageId,
            groupRevision = groupRevision,
        )
    }
    internal val messageStatusObservationController = ChatMessageStatusObservationController(
        uiState = host._uiState,
        scope = host.viewModelScope,
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
        updateState = { transform -> host._uiState.update(transform) },
        getLastMessagesSeen = { readReceiptBookkeeping.lastMessagesSeen },
        setLastMessagesSeen = { readReceiptBookkeeping.lastMessagesSeen = it },
        readSeenMessages = readReceiptBookkeeping.readSeenMessages,
        setPendingReadWatermarkMessageId = { readReceiptBookkeeping.pendingReadWatermarkMessageId = it },
        getMarkReadJob = { readReceiptBookkeeping.markReadJob },
        setMarkReadJob = { readReceiptBookkeeping.markReadJob = it },
        armSecretDisappearing = host::armSecretDisappearing,
        currentActiveChatId = com.maodouchat.chatdetail.ChatDetailAccess::activeChatId,
        markIncomingReadThrough = com.maodouchat.chatdetail.ChatDetailDataAccess::markIncomingReadThrough,
        markAllRead = com.maodouchat.chatdetail.ChatDetailDataAccess::markAllRead,
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        enqueueReadReceipt = enqueueReadReceipt,
        emitChatRead = com.maodouchat.chatdetail.ChatDetailAccess::emitChatRead,
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val groupRevisionHandler: ChatGroupRevisionHandler = ChatGroupRevisionHandler(
        ownerUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        text = { message -> host.text(chatGroupRevisionTextResId(message)) },
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        cleanupSessionFor = { ownerUserId -> com.maodouchat.conversation.conversationLocalCleanupSession(ownerUserId) },
        ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
        conversationLocalStateCoordinator = conversationLocalStateCoordinator,
        clearRemoteTyping = realtimeController::clearRemoteTyping,
        semanticSearchGate = aiBookkeeping.semanticSearchGate,
        aiRewriteGate = aiBookkeeping.aiRewriteGate,
        aiReplyGate = aiBookkeeping.aiReplyGate,
        groupAiGate = aiBookkeeping.groupAiGate,
        manualSummaryGate = aiBookkeeping.manualSummaryGate,
        semanticSearchJob = { aiBookkeeping.semanticSearchJob },
        aiRewriteStreamJob = { aiBookkeeping.aiRewriteStreamJob },
        aiReplyStreamJob = { aiBookkeeping.aiReplyStreamJob },
        groupAiJob = { aiBookkeeping.groupAiJob },
        manualSummaryJob = { aiBookkeeping.manualSummaryJob },
        unreadSummaryJob = { aiBookkeeping.unreadSummaryJob },
        aiOperationJobs = aiBookkeeping.aiOperationJobs,
        aiAutoRetryJobs = aiBookkeeping.aiAutoRetryJobs,
        aiAutoRetryAt = aiBookkeeping.aiAutoRetryAt,
        groupMessagingCoordinator = groupMessagingCoordinator,
        reloadChat = host::loadChat,
        refreshMyMemberRole = host::refreshMyMemberRole,
    )
    internal val scheduledMessageController = ScheduledMessageController(
        chatScheduleController = chatScheduleController,
        uiState = host._uiState,
        textProvider = { message, args -> host.text(scheduledMessageResId(message), *args) },
        session = { com.maodouchat.session.CurrentSession.snapshot() },
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
        clearDraft = { host.clearDraft() },
        sendMessage = { forceText, onDurableCommit, onDurableFailure -> host.sendMessage(forceText = forceText, onDurableCommit = onDurableCommit, onDurableFailure = onDurableFailure) },
    )
    internal val chatReminderController = ChatReminderController(
        chatScheduleController = chatScheduleController,
        uiState = host._uiState,
        text = { message, args -> host.text(chatReminderMessageResId(message), *args) },
        relativeTimeText = ::reminderRelativeTimeText,
        activeChatId = { host.activeChatId },
        chatId = host.chatId,
    )
    internal val chatExportController = ChatExportController(
        getRecentMessages = messageRepo::getRecentMessages,
        uiState = host._uiState,
        textProvider = { key, args -> host.text(chatExportTextResId(key), *args) },
        scope = host.viewModelScope,
        ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
        exportEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.CHAT_EXPORT) },
        currentUserId = { com.maodouchat.session.CurrentSession.snapshot().userId },
        sessionToken = { com.maodouchat.session.CurrentSession.snapshot().token },
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        secretChatCapabilities = com.maodouchat.security.SecretChatCapabilities::forChatOrNull,
        maxMessages = ChatExport.MAX_MESSAGES,
        formatVersion = ChatExport.FORMAT_VERSION,
        writeExportFile = { fileName, chatName, ownerId, messages, resolveSenderName, isCancelled ->
            ChatExport.writeStream(application, fileName, chatName, ownerId, messages, resolveSenderName, isCancelled = isCancelled)
        },
        shareExportFile = { file, title -> ChatExport.share(application, file, title) },
    )
    internal var lastCapturePeerNotifyAt = 0L
    internal val identityVerificationController = IdentityVerificationController(
        scope = host.viewModelScope, resetDirectIdentityForGroup = groupSecurityStateController::resetDirectIdentityForGroup,
        uiState = host._uiState,
        text = { key -> host.text(identityVerificationTextResId(key)) },
        currentUserId = { host.currentUserId }, sessionToken = { com.maodouchat.session.CurrentSession.snapshot().token },
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        safetyCodeEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.SAFETY_CODE) }, deviceVerifyEnabled = { com.maodouchat.util.SecretDeviceVerifyPrefs.isEnabled(application) },
        markFingerprintVerified = { fp -> com.maodouchat.util.SecretDeviceVerifyPrefs.markFingerprintVerified(application, fp) },
        getRemoteDeviceSafetyStates = signalProtocol::getRemoteDeviceSafetyStates, markIdentityVerified = signalProtocol::markIdentityVerified,
        ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
    )
    internal val sessionCipherController = ChatSessionCipherController(
        initialChatId = host.chatId,
        getChatById = chatRepo::getChatById,
        acquireOccupancy = com.maodouchat.crypto.SessionCipherOccupancy::acquire,
        occupyOccupancy = { lease, chatId, peerId, updatePeer -> com.maodouchat.crypto.SessionCipherOccupancy.occupy(lease, chatId, peerId, updatePeer) },
        releaseOccupancy = { lease -> com.maodouchat.crypto.SessionCipherOccupancy.release(lease) },
        currentUserId = { host.currentUserId },
    )
    internal val messageLoadingController = ChatMessageLoadingController(
        uiState = host._uiState,
        scope = host.viewModelScope,
        chatId = host.chatId,
        getActiveChatId = { host.activeChatId },
        getCurrentUserId = { host.currentUserId },
        getChatById = chatRepo::getChatById,
        cacheChats = chatRepo::cacheChats,
        fetchChatById = { token, id -> ChatNetworkRepository().chats(token).getOrNull()?.find { it.id == id }?.toDomainChat() },
        getRecentMessages = messageRepo::getRecentMessages,
        getLatestIncomingMessage = messageRepo::getLatestIncomingMessage,
        chatUnreadCount = com.maodouchat.chatdetail.ChatDetailDataAccess::chatUnreadCount,
        groupLifecycleService = groupLifecycleService,
        planChatLoad = ChatDetailLoadCoordinator::planChatLoad,
        planLoadFailure = ChatDetailLoadCoordinator::planLoadFailure,
        presentMembers = groupSecurityStateController::presentMembers,
        initialHistoryLoaded = timelineStateController::initialHistoryLoaded,
        refreshPinnedMessages = pinStarController::refreshPinnedMessages,
        cipherController = sessionCipherController,
        loadGroupCandidates = botGroupActionController::loadGroupCandidates,
        refreshBotCommands = botGroupActionController::refreshBotCommands,
        refreshBlockState = moderationController::refreshBlockState,
        scheduledMessageController = scheduledMessageController,
        refreshIdentitySafetyState = identityVerificationController::refreshIdentitySafetyState,
        groupMessagingCoordinator = groupMessagingCoordinator,
        prefetchRecentGroupCounts = readReceiptCoordinator::prefetchRecentGroupCounts,
        text = { message, args -> host.text(chatMessageLoadingTextResId(message), *args) },
        quantityText = { message, quantity, args -> host.quantityText(chatMessageLoadingTextResId(message), quantity, *args) },
        withLocalNickname = host::withLocalNickname,
        requestMediaAttachment = host::requestMediaAttachment,
        maybeGenerateUnreadSummary = host::maybeGenerateUnreadSummary,
        armSecretDisappearing = host::armSecretDisappearing,
        showNewDeviceHistoryBanner = host::maybeShowNewDeviceHistoryBanner,
        isLocalUriReadable = { uri -> MediaCache.isReadableLocalUri(application, uri) },
        enqueueReadReceipt = enqueueReadReceipt,
        emitChatRead = com.maodouchat.chatdetail.ChatDetailAccess::emitChatRead,
        currentToken = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
    )
    internal val outgoingHydrationController = ChatOutgoingHydrationController(
        uiState = host._uiState,
        cipherController = sessionCipherController,
        setActiveChatId = { host.activeChatId = it },
        currentUserId = { host.currentUserId },
        getUserById = userRepo::getUserById,
        session = { com.maodouchat.session.CurrentSession.snapshot() },
        sessionGeneration = com.maodouchat.chatdetail.ChatDetailAccess::currentSessionGeneration,
        purgeInProgress = com.maodouchat.security.SecureSessionManager::isPurgeInProgress,
        text = { message, args -> host.text(chatOutgoingHydrationTextResId(message), *args) },
        quantityText = { message, quantity, args -> host.quantityText(chatOutgoingHydrationTextResId(message), quantity, *args) },
    )
    internal val groupCallController = ChatGroupCallController(
        uiState = host._uiState,
        currentUserId = { host.currentUserId },
        callsEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.CALLS) },
        videoCallEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.VIDEO_CALL) },
        voiceCallEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.VOICE_CALL) },
        text = { message -> host.text(chatGroupCallMessageResId(message)) },
    )
    internal val sessionTeardownController = ChatSessionTeardownController(
        uiState = host._uiState,
        scope = com.maodouchat.chatdetail.ChatDetailAccess.applicationScope,
        draftsFeatureEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.CHAT_DRAFTS) },
        cancelRecording = { voiceRecorder.cancelRecording() },
        stopVoicePlayback = { com.maodouchat.util.VoicePlayer.stop() },
        clearRealtime = realtimeController::clear,
        cipherController = sessionCipherController,
        getActiveChatId = { host.activeChatId },
        stopLiveLocationSharing = host::stopLiveLocationSharing,
        stopRecordingMeter = host::stopRecordingMeter,
        cancelAiAutoRetryJobs = { aiBookkeeping.aiAutoRetryJobs.values.forEach { it.cancel() }; aiBookkeeping.aiAutoRetryJobs.clear(); aiBookkeeping.aiAutoRetryAt.clear() },
        cancelDraftSaveJob = { draftBookkeeping.draftSaveJob?.cancel(); draftBookkeeping.draftSaveJob = null },
        clearReadSeen = { readReceiptBookkeeping.readSeenMessages.clearAll() },
        takePendingReadWatermark = { readReceiptBookkeeping.pendingReadWatermarkMessageId.also { readReceiptBookkeeping.pendingReadWatermarkMessageId = null } },
        cancelMarkReadJob = { readReceiptBookkeeping.markReadJob?.cancel(); readReceiptBookkeeping.markReadJob = null },
        persistDraft = { ownerUserId, chatId, text ->
            if (text.isBlank()) {
                chatDraftDao.delete(ownerUserId, chatId)
            } else {
                chatDraftDao.upsert(ChatDraftEntity(ownerUserId = ownerUserId, chatId = chatId, text = text, updatedAt = System.currentTimeMillis()))
            }
        },
        ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        sessionMayContinue = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
        sessionToken = { com.maodouchat.session.CurrentSession.snapshot().token },
        sessionUserId = { com.maodouchat.session.CurrentSession.snapshot().userId },
        enqueueReadReceipt = enqueueReadReceipt,
        logWarning = { message, error -> android.util.Log.w("ChatDetailViewModel", message, error) },
    )
    internal val recipientId: String get() = host._uiState.value.contact.id
    internal val fileTransferController by lazy {
        ChatFileTransferController(
            uiState = host._uiState,
            scope = host.viewModelScope,
            applicationScope = com.maodouchat.chatdetail.ChatDetailAccess.applicationScope,
            activeChatId = { host.activeChatId },
            mediaStateController = mediaStateController,
            attachmentIntentController = attachmentIntentController,
            attachmentPreparationJobs = attachmentPreparationJobs,
            text = { key -> host.text(fileTransferTextResId(key)) },
            ensureLocalAttachment = host::ensureLocalAttachment,
            retryAllTransfers = { _ -> AttachmentTransferSummaryRepository.retryAll(host.chatId) },
            cancelAllTransfers = { _ -> AttachmentTransferSummaryRepository.cancelAll(host.chatId) },
            isReadableLocalUri = { uri -> MediaCache.isReadableLocalUri(application, uri) },
            releasePersistableReadPermission = { uri -> MediaCache.releasePersistableReadPermission(application, uri) },
            deleteCachedMediaForMessage = { messageId -> MediaCache.deleteCachedMediaForMessage(application, messageId) },
            updateMessageStatus = messageRepo::updateMessageStatus,
            deleteMessage = messageRepo::deleteMessage,
            currentUserId = { host.currentUserId },
            cleanupAttachmentTransfer = { messageId, ownerUserId ->
                val dao = com.maodouchat.chatdetail.ChatDetailDataAccess.attachmentTransferDao()
                dao.get(messageId, ownerUserId = ownerUserId)?.let { transfer ->
                    transfer.encryptedPath.takeIf { it.isNotBlank() }?.let { path -> runCatching { java.io.File(path).delete() } }
                    dao.delete(messageId, ownerUserId = ownerUserId)
                }
            },
            logWarning = { message, error -> android.util.Log.w("ChatDetailFileTransferController", message, error) },
        )
    }
    internal val decryptStatus by lazy {
        ChatDetailDecryptStatus(
            gate = signalProtocol,
            texts = DecryptTexts(
                failed = host.text(R.string.chat_decrypt_failed), pending = host.text(R.string.chat_decrypt_pending),
                sessionMissing = host.text(R.string.chat_decrypt_session_missing), identityChanged = host.text(R.string.chat_decrypt_identity_changed),
                groupFailed = host.text(R.string.chat_decrypt_group_failed), groupKeyMissing = host.text(R.string.chat_decrypt_group_key_missing),
                groupIdentityChanged = host.text(R.string.chat_decrypt_group_identity_changed), groupNewer = host.text(R.string.chat_decrypt_group_newer),
                imageFailed = host.text(R.string.chat_decrypt_image_failed), gifFailed = host.text(R.string.chat_decrypt_gif_failed),
                stickerFailed = host.text(R.string.chat_decrypt_sticker_failed), locationFailed = host.text(R.string.chat_decrypt_location_failed),
                videoFailed = host.text(R.string.chat_decrypt_video_failed), voiceFailed = host.text(R.string.chat_decrypt_voice_failed),
                fileFailed = host.text(R.string.chat_decrypt_file_failed),
            ),
        )
    }
}
