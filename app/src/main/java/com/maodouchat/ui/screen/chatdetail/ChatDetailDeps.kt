package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.core.model.AiConversationProfileSource
import com.maodouchat.attachment.AttachmentDownloadCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository
import com.maodouchat.attachment.DefaultAttachmentIntentController
import com.maodouchat.attachment.DefaultAttachmentPreparationService
import com.maodouchat.attachment.RoomTransferRepository
import com.maodouchat.domain.messaging.DefaultConversationCommandFacade
import com.maodouchat.domain.messaging.ChatOutgoingFacade
import com.maodouchat.core.model.mergeMessageVersions
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.DefaultConversationForwardCoordinator
import com.maodouchat.domain.conversation.ChatGroupSecurityStateController
import com.maodouchat.domain.conversation.ChatMediaStateController
import com.maodouchat.domain.conversation.ChatPinStarController
import com.maodouchat.domain.conversation.ChatReadReceiptCoordinator
import com.maodouchat.domain.conversation.ChatSearchSelectionStateController
import com.maodouchat.domain.conversation.ChatSettingToggleController
import com.maodouchat.domain.conversation.ChatTimelineStateController
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import com.maodouchat.core.model.toDomainChat
// 构造顺序有语义：后面的协调器引用前面构造出来的仓库。
internal class ChatDetailDeps(
    private val application: Application,
    private val host: ChatDetailViewModel,
) {
    internal val aiConversationProfileSource: AiConversationProfileSource =
        com.maodouchat.data.local.RoomAiConversationProfileSource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val aiChatClassificationSource: AiChatClassificationSource =
        com.maodouchat.data.local.RoomAiChatClassificationSource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val aiEmotionReplySource: AiEmotionReplySource =
        com.maodouchat.data.local.RoomAiEmotionReplySource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val aiWeeklyReportSource: AiWeeklyReportSource =
        com.maodouchat.data.local.RoomAiWeeklyReportSource(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase)
    internal val messageRepo = com.maodouchat.chatdetail.ChatDetailDataAccess.messageRepo()
    internal val attachmentDownloadCoordinator = AttachmentDownloadCoordinator(
        context = application,
        messageStore = messageRepo,
        isSecretChat = { message ->
            host._uiState.value.isSecretChat == true ||
                com.maodouchat.security.SecretChatCapabilities.forChat(message.chatId).isSecretChat
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
    internal val messagePreviewController = ChatMessagePreviewController(
        chatProvider = { host._uiState.value.chat },
        contactProvider = { host._uiState.value.contact },
        currentUserId = { host.currentUserId },
        activeChatId = { host.activeChatId },
        textProvider = host::text,
    )
    internal val messageGateway by lazy {
        MessagingV2MessageGateway(
            database = com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase,
            messageStore = messageRepo,
            outbox = com.maodouchat.chatdetail.ChatDetailAccess.messagingOutbox,
            indexMessage = messagePreviewController::indexSearchableMessage,
        )
    }
    internal val commandFacade by lazy {
        DefaultConversationCommandFacade(
            gateway = messageGateway,
            resolveChat = chatRepo::getChatById,
            getMessage = messageRepo::getMessageById,
            ownerUserId = { host.currentUserId },
        )
    }
    internal val attachmentIntentController: AttachmentIntentController by lazy {
        DefaultAttachmentIntentController(
            context = application,
            transferRepository = RoomTransferRepository(application, com.maodouchat.chatdetail.ChatDetailDataAccess.appDatabase.attachmentTransferDao()),
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
            com.maodouchat.chatdetail.ChatDetailAccess.messagingOutbox.enqueueEvent(conversationId, event, groupRevision)
        },
        persistDeleted = messageTerminalStore::persistDeleted,
        persistRevoked = messageTerminalStore::persistRevoked,
        persistEdited = messageRepo::applyEditedMessage,
        persistReaction = { original, reactions, actorUserId, reactionEmoji ->
            val reactedAt = reactions.lastOrNull { it.userId == actorUserId }?.reactedAt
                ?: System.currentTimeMillis()
            messageRepo.mutateMessageReactions(original.id) { existing ->
                ReactionMutationPolicy.apply(
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
            if (com.maodouchat.notification.NotificationCenterAccess.repository.removeMessageReferences(message.id)) {
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
                    chatType = if (secret) com.maodouchat.core.session.SecretChatPolicy.CHAT_TYPE else null,
                ).getOrThrow().toDomainChat()
            },
            cacheConversation = { chatRepo.cacheChats(listOf(it)) },
            ensureLocalCryptoReady = signalProtocol::ensureLocalCryptoReady,
            isBotUserId = com.maodouchat.core.model.BotCommandPolicy::isBotUserId,
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
    internal val conversationLocalStateCoordinator = com.maodouchat.chatdetail.ChatDetailAccess.conversationLocalStateCoordinator(
        scheduleCoordinator = conversationScheduleCoordinator,
    )
    internal val voiceRecorder = VoiceRecorder(application)
    internal val recordingWaveformBuffer = VoiceRecordingWaveform()
    internal var recordingMeterJob: Job? = null
    internal val signalProtocol: SignalProtocol = com.maodouchat.crypto.SignalProtocolAccess.protocol
    internal val groupMessagingCoordinator =
        com.maodouchat.group.GroupDetailAccess.groupMessagingCoordinator(application)
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
                        state.copy(messages = mergeMessageVersions(state.messages, listOf(message)))
                    }
                }
            },
            onMessageSent = { targetChatId, preview, type ->
                com.maodouchat.chatdetail.ChatDetailAccess.emitMessageSent(targetChatId, preview, type.name)
            },
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
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
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
        text = { id, args -> host.text(id, *args) },
    )
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
        chatPinEnabled = { RuntimeFlags.isEnabled(host.getApplication<android.app.Application>(), RuntimeFlags.CHAT_PIN) },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        liveToken = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
        chatSettingsGateway = ChatSettingsNetworkGateway(),
        ownerUserId = { host.currentUserId },
        token = { host.token },
        chatId = { host.chatId },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        featureDisabledText = { host.text(R.string.feature_disabled_by_admin) },
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
        mergeMessages = ::mergeMessageVersions,
        clearDraft = { host.clearDraft() },
        maybeForwardBotInbox = botGroupActionController::maybeForwardBotInbox,
        outgoingFacade = outgoingFacade,
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
    )
    internal val contactCardController = ChatContactCardController(
        getApplication = { host.getApplication<android.app.Application>() },
        textProvider = host::text,
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        sendMessage = { cardContent -> host.sendMessage(forceText = cardContent) },
    )
    internal val aiOperationsController = ChatAiOperationsController(
        scope = host.viewModelScope,
        updateState = { transform -> host._uiState.update(transform) },
        activeChatId = { host.activeChatId },
        aiOperationRepo = aiOperationRepo,
        aiMessageResultStore = aiMessageResultStore,
        aiSummaryRepo = aiSummaryRepo,
        aiTaskRepo = aiTaskRepo,
        aiAutoRetryJobs = aiBookkeeping.aiAutoRetryJobs,
        aiAutoRetryAt = aiBookkeeping.aiAutoRetryAt,
    )
    // G384：`observeAttachmentFinalizedEvents()`/`observeAttachmentTransfers()` 附件观察一族抽到
    // ChatAttachmentObservationController——VM 侧只留同签名委托。
    internal val attachmentObservationController = ChatAttachmentObservationController(
        scope = host.viewModelScope,
        getApplication = { host.getApplication<android.app.Application>() },
        currentState = host._uiState::value,
        updateState = { transform -> host._uiState.update(transform) },
        activeChatId = { host.activeChatId },
        mergeMessages = ::mergeMessageVersions,
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
        getLastMessagesSeen = { readReceiptBookkeeping.lastMessagesSeen },
        setLastMessagesSeen = { readReceiptBookkeeping.lastMessagesSeen = it },
        readSeenMessages = readReceiptBookkeeping.readSeenMessages,
        setPendingReadWatermarkMessageId = { readReceiptBookkeeping.pendingReadWatermarkMessageId = it },
        getMarkReadJob = { readReceiptBookkeeping.markReadJob },
        setMarkReadJob = { readReceiptBookkeeping.markReadJob = it },
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
    // G388：会话水合一族（hydrateOutgoingChat + ownerSession/isOwnerSessionCurrent +
    // withLocalNickname）抽到 ChatOutgoingHydrationController——VM 只留同签名委托。
    internal val outgoingHydrationController = ChatOutgoingHydrationController(
        uiState = host._uiState,
        userRepo = userRepo,
        cipherController = sessionCipherController,
        setActiveChatId = { host.activeChatId = it },
        currentUserId = { host.currentUserId },
        text = { id, args -> host.text(id, *args) },
        quantityText = { id, quantity, args -> host.quantityText(id, quantity, *args) },
    )
    // G388：群通话入口抽到 ChatGroupCallController——VM 只留同签名委托。
    internal val groupCallController = ChatGroupCallController(
        uiState = host._uiState,
        application = application,
        currentUserId = { host.currentUserId },
        text = { id, args -> host.text(id, *args) },
    )
    // G388：onCleared 收尾（媒体/任务清理 + 草稿持久化 + 最终已读水印）抽到
    // ChatSessionTeardownController——VM 只留 override 转发。
    internal val sessionTeardownController = ChatSessionTeardownController(
        uiState = host._uiState,
        application = application,
        voiceRecorder = voiceRecorder,
        realtimeController = realtimeController,
        cipherController = sessionCipherController,
        getActiveChatId = { host.activeChatId },
        stopLiveLocationSharing = host::stopLiveLocationSharing,
        stopRecordingMeter = host::stopRecordingMeter,
        cancelAiAutoRetryJobs = {
            aiBookkeeping.aiAutoRetryJobs.values.forEach { it.cancel() }
            aiBookkeeping.aiAutoRetryJobs.clear()
            aiBookkeeping.aiAutoRetryAt.clear()
        },
        cancelDraftSaveJob = { draftBookkeeping.draftSaveJob?.cancel(); draftBookkeeping.draftSaveJob = null },
        clearReadSeen = { readReceiptBookkeeping.readSeenMessages.clearAll() },
        takePendingReadWatermark = { readReceiptBookkeeping.pendingReadWatermarkMessageId.also { readReceiptBookkeeping.pendingReadWatermarkMessageId = null } },
        cancelMarkReadJob = { readReceiptBookkeeping.markReadJob?.cancel(); readReceiptBookkeeping.markReadJob = null },
        persistDraft = { ownerUserId, chatId, text ->
            if (text.isBlank()) {
                chatDraftDao.delete(ownerUserId, chatId)
            } else {
                chatDraftDao.upsert(
                    ChatDraftEntity(
                        ownerUserId = ownerUserId,
                        chatId = chatId,
                        text = text,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        },
    )
    internal val recipientId: String get() = host._uiState.value.contact.id
    internal val fileTransferController by lazy {
        ChatDetailFileTransferController(
            uiState = host._uiState,
            scope = host.viewModelScope,
            applicationScope = com.maodouchat.chatdetail.ChatDetailAccess.applicationScope,
            context = application,
            activeChatId = { host.activeChatId },
            messageRepo = messageRepo,
            mediaStateController = mediaStateController,
            attachmentIntentController = attachmentIntentController,
            attachmentPreparationJobs = attachmentPreparationJobs,
            text = { res -> host.text(res) },
            ensureLocalAttachment = host::ensureLocalAttachment,
            retryAllTransfers = { _ -> AttachmentTransferSummaryRepository.retryAll(host.chatId) },
            cancelAllTransfers = { _ -> AttachmentTransferSummaryRepository.cancelAll(host.chatId) },
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
