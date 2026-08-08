package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.semanticSearchText
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiMessageMetaSyncRepository
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.AiSummarySyncRepository
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiGroupTask
import com.maodouchat.network.AiSemanticSearchCandidate
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.MessageDto
import com.maodouchat.network.PinnedMessageDto
import com.maodouchat.network.UnreadWindowDto
import com.maodouchat.network.MessageMutationDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.network.shouldApplyTypingEvent
import com.maodouchat.network.resolveTypingSignalAction
import com.maodouchat.network.TypingSignalAction
import com.maodouchat.network.REMOTE_TYPING_TIMEOUT_MS
import com.maodouchat.ui.screen.chatlist.ChatListPreviewPolicy
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure
import com.maodouchat.util.EncryptedAttachmentCrypto
import com.maodouchat.util.VoiceCapturePolicy
import com.maodouchat.util.VoicePlayer
import com.maodouchat.util.VoiceRecorder
import com.maodouchat.util.VoiceRecordingWaveform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID

class ChatDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val chatId: String = savedStateHandle.get<String>("chatId").orEmpty()
    private val navigationMessageId: String? = savedStateHandle.get<String>("messageId")?.takeIf(String::isNotBlank)
    @Volatile internal var activeChatId: String = chatId
    internal val app = application as MaodouchatApp
    internal val messageRepo = MessageRepository(app.database.messageDao(), app.database)
    private val chatLockRepo = com.maodouchat.data.repository.ChatLockRepository(app.database.chatLockDao())
    private val secretChatRepo = com.maodouchat.data.repository.SecretChatRepository(app.database.secretChatDao())
    /** Reaction WS can race ahead of MessageReceived while chat is open. */
    @Volatile
    private var pendingReactions: Map<String, com.maodouchat.ui.screen.chatlist.PendingReactionPolicy.Entry> =
        emptyMap()
    private val messageTerminalStore = MessageTerminalStore(
        deleteCachedMedia = { messageId ->
            MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
        },
        deleteSearchDocument = app.database.messageSearchDao()::deleteDocument,
        deleteLocalMessage = messageRepo::deleteMessage,
        upsertLocalMessage = messageRepo::insertMessage
    )
    internal val aiSummaryRepo = AiSummaryRepository(app.database.aiSummaryCacheDao())
    internal val aiTaskRepo = AiTaskRepository(app.database.aiTaskDao(), application)
    internal val aiOperationRepo = AiOperationRepository(app.database.aiOperationDao())
    private val userRepo = UserRepository(app.database.userDao())
    private val chatRepo = ChatRepository(app.database.chatDao(), app.database.userDao())
    private val chatDraftDao = app.database.chatDraftDao()
    internal val tokenManager = TokenManager.getInstance(application)

    private fun ownerSession(ownerUserId: String = currentUserId): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, MaodouchatApp.currentSessionGeneration())

    private fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = tokenManager.getUserId(),
            liveToken = tokenManager.getToken(),
            liveSessionGeneration = MaodouchatApp.currentSessionGeneration(),
            purgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
        )

    private suspend fun withOwnerRoomWrite(
        session: OwnerSessionSnapshot,
        block: suspend () -> Unit,
    ): Boolean = app.database.withTransaction {
        if (!isOwnerSessionCurrent(session)) {
            false
        } else {
            block()
            true
        }
    }
    private val voiceRecorder = VoiceRecorder(application)
    private val recordingWaveformBuffer = VoiceRecordingWaveform()
    private var recordingMeterJob: Job? = null
    internal val signalProtocol: SignalProtocol = app.signalProtocol
    internal val aiSummarySyncRepo = AiSummarySyncRepository(app.database.aiSummaryCacheDao(), signalProtocol)
    internal val aiMessageMetaSyncRepo = AiMessageMetaSyncRepository(messageRepo, signalProtocol, app.database)
    internal val aiMessageResultStore = AiMessageResultStore(app.database)
    internal fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

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
            message.type.name
        )
    }

    /** Best-effort keyword index after local plaintext is durable (IO thread). */
    private suspend fun indexSearchableMessage(message: Message) {
        // 密聊消息不落搜索索引（与 ImageOcrAutoIndexer 一致）：即使本地 SQLCipher 已加密，
        // 密聊明文不应进入可搜索缓存，避免密聊内容在全局搜索中可被检索。
        if (runCatching { secretChatRepo.isSecret(message.chatId) }.getOrDefault(false)) return
        try {
            com.maodouchat.data.repository.MessageSearchRepository(app.database)
                .indexMessage(message)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("ChatDetailViewModel", "indexSearchableMessage failed", error)
        }
    }

    private val readMessagesTracker = mutableSetOf<String>()
    /** 已乐观标读、尚待服务端 ACK 的 id；失败/取消 debounce 时回灌 tracker 重试 */
    private val pendingServerReadIds = mutableSetOf<String>()
    private var lastMessagesSeen: List<Pair<String, MessageStatus>>? = null
    private var markReadJob: kotlinx.coroutines.Job? = null
    internal var pendingAiAction: PendingAiAction? = null
    internal var pendingAiOperationRetryId: String? = null
    internal var aiSettingsLoaded = false
    internal var unreadSummaryAttemptedForKey: String? = null
    /** In-flight auto unread summary key; prevents concurrent duplicate summarizeChat calls. */
    internal var unreadSummaryInFlightKey: String? = null
    internal val semanticSearchGate = AiRequestGenerationGate()
    internal var unreadSummaryWindow: UnreadWindowDto? = null
    private val attachmentPreparationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val attachmentDownloadMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
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
    private var liveLocationJob: kotlinx.coroutines.Job? = null
    private var liveLocationCancel: (() -> Unit)? = null
    private val liveLocationUpdateMutex = Mutex()
    private val inlineSendCompletions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var lastLiveLocationPayload: com.maodouchat.data.model.LocationPayload? = null
    private var draftSaveJob: kotlinx.coroutines.Job? = null
    private var olderMessagesJob: kotlinx.coroutines.Job? = null
    @Volatile private var olderMessagesCursor: TokenManager.SyncCursor? = null
    /** Bumped on every schedule/clear so a late persist cannot resurrect a cleared draft. */
    private var draftGeneration = 0L
    @Volatile internal var hasUserEditedInput = false
    private val messageMutationTracker = MessageMutationTracker()

    internal val currentUserId: String get() = tokenManager.getUserId() ?: "me"
    internal val token: String get() = tokenManager.getToken() ?: ""

    internal val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()
    private val remoteTypingCoordinator = RemoteTypingCoordinator(viewModelScope) { userId ->
        _uiState.update { it.copy(typingContact = userId) }
    }

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
            com.maodouchat.MaodouchatApp.activeChatId = chatId
            // Open chat: drop tray notification + mark in-app center rows for this chat.
            runCatching {
                com.maodouchat.util.AppNotifier.cancelMessage(getApplication(), chatId)
            }
            runCatching {
                app.notificationCenter.markChatMessagesRead(chatId)
            }
            restoreDraft()
            refreshChatLockState()
            refreshSecretChatState()
            loadChat()
            connectWebSocket()
            observeWebSocket()
            observeMessageStatus()
            observeAttachmentTransfers()
            observeAttachmentFinalizedEvents()
            observeAiOperations()
            loadAiSettings()
            pullSyncedAiSummaries()
            // 阅后即焚：本地每秒扫到期消息并删除密文缓存
            viewModelScope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(1_000L)
                    if (tokenManager.getToken().isNullOrBlank()) continue
                    purgeExpiredLocalMessages()
                }
            }
        }
    }

    private fun pullSyncedAiSummaries() {
        val pullOwnerUserId = currentUserId
        if (token.isBlank() || pullOwnerUserId.isBlank() || !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = pullOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            pullSyncedAiMessageMeta()
            aiSummarySyncRepo.pull(token, pullOwnerUserId, liveToken = tokenManager::getToken, liveUserId = tokenManager::getUserId).onSuccess { imported ->
                // Long decrypt/network can outlive switch — drop before painting AI summary.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = pullOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onSuccess
                }
                val now = System.currentTimeMillis()
                val unread = imported
                    .asSequence()
                    .filter { it.scope == AiSummaryScope.UNREAD.name }
                    .map { it.entity }
                    .filter { it.chatId == activeChatId && now - it.createdAt in 0..SYNCED_UNREAD_SUMMARY_DISPLAY_MAX_AGE_MS }
                    .maxByOrNull { it.createdAt }
                if (unread != null) {
                    _uiState.update { state ->
                        if (state.unreadAiSummary != null || state.isUnreadSummaryLoading) state
                        else state.copy(
                            unreadAiSummary = unread.summary,
                            unreadAiSummaryCount = unread.messageCount
                        )
                    }
                }
            }.onFailure { error ->
                Log.d("ChatDetailViewModel", "Encrypted AI summary sync unavailable: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun pullSyncedAiMessageMeta() {
        val pullOwnerUserId = currentUserId
        if (token.isBlank() || pullOwnerUserId.isBlank() || !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = pullOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        aiMessageMetaSyncRepo.pull(token, pullOwnerUserId, liveToken = tokenManager::getToken, liveUserId = tokenManager::getUserId).onSuccess { imported ->
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = pullOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@onSuccess
            }
            val activeImports = imported
                .asSequence()
                .filter { it.chatId == activeChatId }
                .map { it.message }
                .toList()
            if (activeImports.isEmpty()) return@onSuccess
            _uiState.update { state ->
                state.copy(messages = mergeMessages(state.messages, activeImports))
            }
        }.onFailure { error ->
            Log.d("ChatDetailViewModel", "Encrypted AI message meta sync unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun pushAiMessageMeta(message: Message, expectedUserId: String = currentUserId) {
        if (token.isBlank() || expectedUserId.isBlank() || currentUserId != expectedUserId ||
            !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            aiMessageMetaSyncRepo.push(token, expectedUserId, message, liveToken = tokenManager::getToken, liveUserId = tokenManager::getUserId).onFailure { error ->
                Log.d("ChatDetailViewModel", "Encrypted AI message meta push unavailable: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun observeAiOperations() {
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return
        aiOperationRepo.observeActionable(ownerUserId, activeChatId)
            .onEach { operations ->
                // Drop if logout/switch happened while Room Flow was still open.
                if (tokenManager.getUserId().orEmpty() != ownerUserId) return@onEach
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
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return@launch
            aiOperationRepo.recoverInterrupted(ownerUserId, activeChatId)
            aiOperationRepo.pruneTerminal()
            // AI 本地缓存保留期清理：总结缓存 90 天，已完成任务 90 天
            val cutoff = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1_000L
            runCatching { aiSummaryRepo.pruneOlderThan(cutoff) }
            runCatching { aiTaskRepo.pruneCompletedOlderThan(cutoff) }
        }
    }

    /** Per-chat single-flight：open + WS Connected 不得并发消息/mutation 回放。 */
    private val chatSyncMutex = Mutex()

    /** 先消息后 mutation，避免 EDIT 因缺原文被永久跳过。 */
    private fun syncMessagesSinceAndThenMutations() {
        viewModelScope.launch(Dispatchers.IO) {
            chatSyncMutex.withLock {
                syncMessagesSince()
                syncMutationsSince()
            }
        }
    }

    /**
     * 多设备变更收敛：拉取 DELETE/REVOKE/EDIT 日志并应用到本地。
     * 仅靠 WS 会在离线时漏变更；打开聊天时必须回放 mutation log。
     * 游标只推进连续成功前缀：EDIT 解密失败占位时停住，密钥补齐后重试。
     */
    private suspend fun syncMutationsSince() {
        val syncOwnerUserId = currentUserId
        var token = tokenManager.getToken() ?: return
        if (token.isBlank() || syncOwnerUserId.isBlank()) return
        if (_uiState.value.chat == null) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = syncOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        token = tokenManager.getToken().orEmpty().ifBlank { token }
        val initialCursor = tokenManager.getMutationCursor(chatId)
        withContext(Dispatchers.IO) {
            val pageLimit = 200
            val maxPages = 25
            var cursor = initialCursor
            var previewDirty = false
            for (page in 0 until maxPages) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = syncOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    break
                }
                token = tokenManager.getToken().orEmpty().ifBlank { token }
                val pageResult = ApiService.getMessageMutationsSince(
                    token = token,
                    chatId = chatId,
                    sinceMs = cursor.timestampMs,
                    limit = pageLimit,
                    sinceId = cursor.messageId.takeIf { it.isNotBlank() }
                )
                val mutations = pageResult.getOrNull() ?: break
                if (mutations.isEmpty()) break
                var advanced = cursor
                // EDIT 解密失败不得 HOL 挡住同页后续 DELETE/REVOKE；仅记录未决 EDIT 供下轮重试
                var pendingEditBlock: TokenManager.SyncCursor? = null
                mutations.sortedWith(
                    compareBy<MessageMutationDto> { it.createdAt }.thenBy { it.id }
                ).forEach { mut ->
                    // Mid-page decrypt/IO can outlive logout; stop applying without advancing past unapplied.
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = syncOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@forEach
                    }
                    val applied = when (mut.action) {
                        "DELETE" -> {
                            messageMutationTracker.observeAuthoritative(mut.messageId, MessageMutationKind.DELETE)
                            messageTerminalStore.persistDeleted(mut.messageId)
                            cleanupAttachmentForMessage(mut.messageId)
                            _uiState.update { state ->
                                state.copy(
                                    messages = state.messages.filterNot { it.id == mut.messageId },
                                    pinnedMessages = state.pinnedMessages.filterNot { it.messageId == mut.messageId }
                                )
                            }
                            previewDirty = true
                            true
                        }
                        "REVOKE" -> {
                            messageMutationTracker.observeAuthoritative(mut.messageId, MessageMutationKind.REVOKE)
                            val original = _uiState.value.messages.firstOrNull { it.id == mut.messageId }
                                ?: messageRepo.getMessageById(mut.messageId)?.takeIf { it.chatId == chatId }
                            val revoked = original?.toRevokedPlaceholder(text(R.string.chat_message_revoked_placeholder))
                                ?: Message(
                                    id = mut.messageId,
                                    chatId = mut.chatId,
                                    senderId = mut.actorId,
                                    content = mut.content ?: text(R.string.chat_message_revoked_placeholder),
                                    type = MessageType.REVOKED,
                                    timestamp = mut.createdAt,
                                    status = MessageStatus.SENT
                                )
                            _uiState.update { state ->
                                val has = state.messages.any { it.id == mut.messageId }
                                state.copy(
                                    messages = if (has) {
                                        state.messages.map { if (it.id == mut.messageId) revoked else it }
                                    } else {
                                        mergeMessages(state.messages, listOf(revoked))
                                    }
                                )
                            }
                            messageTerminalStore.persistRevoked(mut.messageId, revoked)
                            cleanupAttachmentForMessage(mut.messageId)
                            _uiState.update { state ->
                                state.copy(pinnedMessages = state.pinnedMessages.filterNot { it.messageId == mut.messageId })
                            }
                            previewDirty = true
                            true
                        }
                        "EDIT" -> {
                            val newContent = mut.content
                            if (newContent == null) {
                                // 非法 mutation 不可重试，跳过以免卡死整条日志
                                true
                            } else {
                                val existing = _uiState.value.messages.firstOrNull { it.id == mut.messageId }
                                    ?: messageRepo.getMessageById(mut.messageId)?.takeIf { it.chatId == chatId }
                                when {
                                    existing == null -> {
                                        // 本地尚无该消息：消息同步会拉到已编辑正文，不必卡 mutation 游标
                                        // （否则后续 DELETE 永远到不了，且服务端消息行本身已是新内容）
                                        true
                                    }
                                    existing.type == MessageType.REVOKED -> true
                                    messageMutationTracker.shouldDrop(mut.messageId) ||
                                        messageMutationTracker.shouldRenderRevoked(mut.messageId) -> true
                                    else -> {
                                        val wire = existing.copy(
                                            content = newContent,
                                            editedAt = mut.editedAt ?: mut.createdAt
                                        )
                                        val decrypted = decryptIncomingMessage(existing.senderId, wire) ?: wire
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = syncOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            false
                                        } else if (isSyncDecryptFailurePlaceholder(decrypted)) {
                                            // 不卡整页：记录第一个未决 EDIT 游标，继续处理后续终态 mutation
                                            if (pendingEditBlock == null) {
                                                pendingEditBlock = TokenManager.SyncCursor(mut.createdAt, mut.id)
                                            }
                                            // 仍算「本条未应用」：advanced 不越过它，但 forEach 继续
                                            false
                                        } else {
                                            _uiState.update { state ->
                                                state.copy(
                                                    messages = state.messages.map {
                                                        if (it.id == mut.messageId) decrypted else it
                                                    }
                                                )
                                            }
                                            messageRepo.insertMessage(decrypted)
                                            indexSearchableMessage(decrypted)
                                            previewDirty = true
                                            true
                                        }
                                    }
                                }
                            }
                        }
                        else -> true // 未知 action 跳过，避免卡死
                    }
                    if (applied) {
                        // 未决 EDIT 之前的条目可前进；之后的 DELETE/REVOKE 已应用但不推进游标越过未决 EDIT
                        val block = pendingEditBlock
                        if (block == null) {
                            advanced = TokenManager.SyncCursor(mut.createdAt, mut.id)
                        } else {
                            val mutCursor = TokenManager.SyncCursor(mut.createdAt, mut.id)
                            val beforeBlock = mutCursor.timestampMs < block.timestampMs ||
                                (mutCursor.timestampMs == block.timestampMs && mutCursor.messageId < block.messageId)
                            if (beforeBlock) {
                                advanced = mutCursor
                            }
                        }
                    }
                }
                // 有未决 EDIT：游标最多停在它之前（advanced 已是前缀成功末端）；无则正常前进
                val advancedPast = advanced.timestampMs > cursor.timestampMs ||
                    (advanced.timestampMs == cursor.timestampMs && advanced.messageId > cursor.messageId)
                if (advancedPast) {
                    tokenManager.saveMutationCursor(chatId, advanced)
                    cursor = advanced
                } else if (pendingEditBlock != null) {
                    // 页首就是未决 EDIT：停住，等密钥
                    break
                } else {
                    break
                }
                if (mutations.size < pageLimit || pendingEditBlock != null) break
            }
            if (previewDirty &&
                com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = syncOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(chatId)
            }
        }
    }

    /** 多设备同步：按聊天维度的 lastSyncAtMs 拉取该聊天新增消息并写入本地 DB */
    private suspend fun syncMessagesSince() {
        val syncOwnerUserId = currentUserId
        var token = tokenManager.getToken() ?: return
        if (token.isBlank() || syncOwnerUserId.isBlank()) return
        if (_uiState.value.chat == null) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = syncOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        token = tokenManager.getToken().orEmpty().ifBlank { token }
        // 必须使用按 chatId 隔离的游标，避免打开新聊天推进全局 since 后永远跳过其他聊天积压
        val initialCursor = tokenManager.getSyncCursor(chatId)
        withContext(Dispatchers.IO) {
            // 多页拉满 backlog：单页截断后若只推进一次游标，中间段会永久丢失
            val pageLimit = 200
            val maxPages = 25
            var cursor = initialCursor
            val allDecrypted = mutableListOf<Message>()
            var latestPreviewCandidate: Message? = null
            var blocked = false
            for (page in 0 until maxPages) {
                if (blocked) break
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = syncOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    break
                }
                token = tokenManager.getToken().orEmpty().ifBlank { token }
                val pageResult = ApiService.getMessagesSince(
                    token = token,
                    chatId = chatId,
                    sinceMs = cursor.timestampMs,
                    limit = pageLimit,
                    sinceId = cursor.messageId.takeIf { it.isNotBlank() }
                )
                val messages = pageResult.getOrNull() ?: break
                if (messages.isEmpty()) break
                val decrypted = mutableListOf<Message>()
                // 游标只推进到连续成功处理的前缀末端；SK_DIST / 解密失败时停住，避免永久跳过
                var advanced = cursor
                var prefixOk = true
                val cursorOrdered = messages
                    .sortedWith(compareBy<MessageDto> { it.timestamp }.thenBy { it.id })
                    .map { dto ->
                        dto to Message(
                            id = dto.id,
                            chatId = dto.chatId,
                            senderId = dto.senderId,
                            content = dto.content,
                            type = MessageType.fromWire(dto.type),
                            timestamp = dto.timestamp,
                            status = MessageStatus.fromWire(dto.status),
                            editedAt = dto.editedAt,
                            starred = dto.starred,
                            reactions = dto.reactions,
                            expiresAt = dto.expiresAt,
                            sealedSender = dto.sealedSender
                        )
                    }
                for (sameTimestamp in cursorOrdered.groupBy { it.first.timestamp }.values) {
                    // A ciphertext may sort before its SK_DIST by id. Prime distributions for this
                    // timestamp first, then advance only in canonical (timestamp,id) cursor order.
                    val senderKeyResults = sameTimestamp
                        .filter { (_, wire) ->
                            wire.type == MessageType.SK_DIST || wire.content.isSenderKeyDistribution()
                        }
                        .associate { (dto, wire) ->
                            dto.id to processIncomingSenderKeyDistribution(dto.senderId, wire)
                        }
                    for ((dto, wireMessage) in sameTimestamp) {
                        if (!prefixOk || blocked) break
                        if (wireMessage.type == MessageType.SK_DIST || wireMessage.content.isSenderKeyDistribution()) {
                            if (senderKeyResults[dto.id] == true) {
                                advanced = TokenManager.SyncCursor(dto.timestamp, dto.id)
                            } else {
                                blocked = true
                                prefixOk = false
                            }
                            continue
                        }
                        val decryptedMessage = decryptIncomingMessage(dto.senderId, wireMessage)
                        if (decryptedMessage != null) {
                            // Decrypt-failure placeholders must not advance cursor past recoverable ciphertext
                            if (isSyncDecryptFailurePlaceholder(decryptedMessage)) {
                                decrypted += decryptedMessage
                                blocked = true
                                prefixOk = false
                                continue
                            }
                            decrypted += decryptedMessage
                            advanced = TokenManager.SyncCursor(dto.timestamp, dto.id)
                        } else {
                            // NotForThisDevice 等可丢弃结果：仍推进，避免卡死
                            advanced = TokenManager.SyncCursor(dto.timestamp, dto.id)
                        }
                    }
                    if (!prefixOk || blocked) break
                }
                if (decrypted.isNotEmpty()) {
                    allDecrypted += decrypted
                    latestPreviewCandidate = decrypted.maxByOrNull { it.timestamp } ?: latestPreviewCandidate
                }
                val advancedPast = advanced.timestampMs > cursor.timestampMs ||
                    (advanced.timestampMs == cursor.timestampMs && advanced.messageId > cursor.messageId)
                if (advancedPast) {
                    tokenManager.saveSyncCursor(chatId, advanced)
                    cursor = advanced
                } else {
                    break
                }
                if (messages.size < pageLimit || blocked) break
            }
            if (allDecrypted.isNotEmpty()) {
                // 并发 mutation 已删的行不得被本页快照复活
                val keep = allDecrypted.filterNot { messageMutationTracker.shouldDrop(it.id) }
                if (keep.isNotEmpty()) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = syncOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@withContext
                    }
                    messageRepo.insertMessages(keep)
                    // 8.41：解密失败占位消息不写搜索索引（占位文本是 UI 提示，非真实内容）
                    keep.filterNot { isSyncDecryptFailurePlaceholder(it) }.forEach { indexSearchableMessage(it) }
                    pullSyncedAiMessageMeta()
                    _uiState.update { it.copy(messages = mergeMessages(it.messages, keep)) }
                }
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = syncOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@withContext
            }
            val latest = latestPreviewCandidate
            if (latest != null) {
                emitListPreviewForDecrypted(latest.copy(chatId = latest.chatId.ifBlank { chatId }))
            }
        }
    }

    // 用 onEach + launchIn 替代 collect，把"副作用（DB 写入）"和"状态观测"解耦，避免在 collect lambda 内启动子协程
    private fun observeMessageStatus() {
        _uiState
            .onEach { state ->
                if (state.chat == null) return@onEach
                val currentIds = state.messages.map { it.id to it.status }
                if (currentIds == lastMessagesSeen) return@onEach
                lastMessagesSeen = currentIds
                val unread = state.messages.filter {
                    it.senderId != currentUserId &&
                        it.type != MessageType.SK_DIST &&
                        it.status != MessageStatus.READ &&
                        readMessagesTracker.add(it.id)
                }
                if (unread.isEmpty()) return@onEach
                val markReadOwnerUserId = currentUserId
                if (
                    markReadOwnerUserId.isBlank() ||
                    markReadOwnerUserId == "me" ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = markReadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onEach
                }
                val unreadIds = unread.map { it.id }.toSet()
                pendingServerReadIds.addAll(unreadIds)
                _uiState.update { st ->
                    st.copy(messages = st.messages.map { m -> if (m.id in unreadIds) m.copy(status = MessageStatus.READ) else m })
                }
                unread.forEach { m ->
                    viewModelScope.launch {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = markReadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@launch
                        }
                        messageRepo.updateMessageStatus(m.id, MessageStatus.READ)
                    }
                }
                // 通知服务端批量标记已读：去抖 500ms；仅取消等待，API 用 NonCancellable 完成
                // markAllAsRead 是会话级：失败后退避重试，不依赖本地 status 回退 / tracker 重入
                markReadJob?.cancel()
                markReadJob = viewModelScope.launch {
                    // Surface #68: 密聊 read-receipt 门控 — 不向服务端回报已读
                    if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_READ_RECEIPT_BLOCK)) {
                        return@launch
                    }
                    val markReadUserId = markReadOwnerUserId
                    // Snapshot resolved chat id once at launch (before delay/retry): activeChatId may be
                    // reassigned on create-on-send, so the constructor chatId could mark the wrong chat.
                    val effectiveChatId = activeChatId.ifBlank { chatId }
                    try {
                        kotlinx.coroutines.delay(500)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        return@launch
                    }
                    var attempt = 0
                    while (pendingServerReadIds.isNotEmpty() && attempt < 3) {
                        attempt++
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = markReadUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            pendingServerReadIds.clear()
                            return@launch
                        }
                        val batch = pendingServerReadIds.toSet()
                        val liveTok = tokenManager.getToken().orEmpty().ifBlank { token }
                        val result = withContext(Dispatchers.IO + NonCancellable) {
                            ApiService.markAllAsRead(liveTok, effectiveChatId)
                        }
                        if (result.isSuccess) {
                            pendingServerReadIds.removeAll(batch)
                            break
                        }
                        Log.w(
                            "ChatDetailViewModel",
                            "markAllAsRead attempt $attempt failed for $effectiveChatId: ${result.exceptionOrNull()?.message}"
                        )
                        if (attempt < 3) {
                            try {
                                kotlinx.coroutines.delay(1500L * attempt)
                            } catch (_: kotlinx.coroutines.CancellationException) {
                                return@launch
                            }
                        }
                    }
                    // After short retries still pending: schedule one longer recovery so unread
                    // does not stick server-side when no further messages arrive to re-debounce.
                    if (pendingServerReadIds.isNotEmpty()) {
                        try {
                            kotlinx.coroutines.delay(8_000L)
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            return@launch
                        }
                        if (pendingServerReadIds.isEmpty()) return@launch
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = markReadUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            pendingServerReadIds.clear()
                            return@launch
                        }
                        val batch = pendingServerReadIds.toSet()
                        val liveTok = tokenManager.getToken().orEmpty().ifBlank { token }
                        val result = withContext(Dispatchers.IO + NonCancellable) {
                            ApiService.markAllAsRead(liveTok, effectiveChatId)
                        }
                        if (result.isSuccess) {
                            pendingServerReadIds.removeAll(batch)
                        } else {
                            Log.w(
                                "ChatDetailViewModel",
                                "markAllAsRead recovery failed for $effectiveChatId: ${result.exceptionOrNull()?.message}"
                            )
                        }
                    }
                }
                // 通知 ChatListViewModel 实时归零未读数（乐观更新；用 effectiveChatId——
                // create-on-send 后构造期 chatId 可能已被重赋值，广播错误 id 无法对位）
                emitChatReadForCurrentChat()
            }
            .launchIn(viewModelScope)
    }

    /** 8.45：以当前解析的会话 id 广播本地已读（乐观归零未读数）。 */
    private fun emitChatReadForCurrentChat() {
        val id = activeChatId.ifBlank { chatId }
        if (id.isBlank()) return
        com.maodouchat.MaodouchatApp.emitChatRead(id)
    }

    private fun observeAttachmentTransfers() {
        app.database.attachmentTransferDao().observeAllAccounts()
            .onEach { allTransfers ->
                val liveOwnerUserId = tokenManager.getUserId().orEmpty()
                if (liveOwnerUserId.isBlank()) return@onEach
                val visibleMessageIds = _uiState.value.messages.mapTo(hashSetOf()) { it.id }
                // Only this account's rows — SQLCipher wipe is primary isolation, this is defense-in-depth mid-switch.
                val transfers = allTransfers.filter { transfer ->
                    transfer.ownerUserId == liveOwnerUserId &&
                        (transfer.chatId == activeChatId || transfer.messageId in visibleMessageIds)
                }
                val transfersByMessageId = transfers.associateBy { it.messageId }
                _uiState.update { state ->
                    val oldTransferIds = state.fileTransferStates.keys
                    val progress = (state.fileTransferProgress - oldTransferIds).toMutableMap()
                    val states = mutableMapOf<String, String>()
                    val errors = mutableMapOf<String, String>()
                    transfers.forEach { transfer ->
                        progress[transfer.messageId] = transfer.uiProgress()
                        states[transfer.messageId] = transfer.state
                        transfer.lastErrorCode?.let { errors[transfer.messageId] = it }
                    }
                    state.copy(
                        messages = state.messages.map { message ->
                            when (transfersByMessageId[message.id]?.state) {
                                AttachmentTransferState.FAILED -> message.copy(status = MessageStatus.FAILED)
                                AttachmentTransferState.QUEUED,
                                AttachmentTransferState.UPLOADING,
                                AttachmentTransferState.READY,
                                AttachmentTransferState.SENDING,
                                AttachmentTransferState.PAUSED -> message.copy(status = MessageStatus.SENDING)
                                else -> message
                            }
                        },
                        fileTransferProgress = progress,
                        fileTransferStates = states,
                        fileTransferErrors = errors
                    )
                }
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

    private fun AttachmentTransferEntity.uiProgress(): Float = when (state) {
        AttachmentTransferState.QUEUED -> 0.35f
        AttachmentTransferState.UPLOADING -> 0.35f +
            (uploadedBytes.toDouble() / cipherSize.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * 0.55f
        AttachmentTransferState.READY, AttachmentTransferState.SENDING -> 0.92f
        AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED ->
            0.35f + (uploadedBytes.toDouble() / cipherSize.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * 0.55f
        else -> 0f
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val statusOwnerUserId = currentUserId
        if (
            statusOwnerUserId.isBlank() ||
            statusOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = statusOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id != messageId) message
                    else if (!message.status.canAdvanceTo(status)) message
                    else message.copy(status = status)
                }
            )
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = statusOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            messageRepo.updateMessageStatus(messageId, status)
        }
    }

    /** 8.52 UX：初次加载失败后手动重试（UI 错误空态的重试按钮）。 */
    fun reloadChat() {
        _uiState.update { it.copy(initialLoadError = null, isLoading = true) }
        loadChat()
    }

    private fun loadChat() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val loadOwnerUserId = currentUserId
                // Snapshot resolved chat id once at launch: activeChatId may be reassigned on
                // create-on-send, so the constructor chatId could mark the wrong chat read.
                val effectiveChatId = activeChatId.ifBlank { chatId }
                if (loadOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val cachedChat = chatRepo.getChatById(chatId)
                val chatsResult = ApiService.getChats(liveToken)
                // getChats can outlive logout/switch — do not invalidate SK / cache / paint meta for next owner.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                        invalidateGroupSenderKey(chat.id)
                    }
                    chatRepo.cacheChats(listOf(chat))
                    _uiState.update { it.copy(isLoading = false, initialLoadError = null) }
                    if (chat.isGroup) {
                        val groupContact = User(
                            id = chat.id,
                            name = chat.groupName ?: text(R.string.chat_group),
                            avatar = chat.groupAvatar,
                            status = text(R.string.chat_members_count, chat.participants.size)
                        )
                        val revisionWarning = if (shouldInvalidateSenderKey) text(R.string.chat_group_members_changed_key) else null
                        _uiState.update {
                            it.copy(
                                chat = chat,
                                chatIsGroup = true,
                                contact = groupContact,
                                groupEncryptionWarning = revisionWarning ?: it.groupEncryptionWarning,
                                disappearingMessageSeconds = 0
                            )
                        }
                        loadGroupCandidates()
                    } else {
                        val contactUser = chat.participants.firstOrNull { it.id != currentUserId }
                        if (contactUser != null) {
                            _uiState.update {
                                it.copy(
                                    chat = chat,
                                    chatIsGroup = false,
                                    contact = contactUser,
                                    disappearingMessageSeconds = chat.disappearingMessageSeconds
                                )
                            }
                            refreshBlockState(contactUser.id)
                            refreshIdentitySafetyState(contactUser.id)
                            refreshScheduledMessages()
                        }
                    }
                } else {
                    // API 失败时从本地缓存加载聊天信息
                    if (cachedChat != null) {
                        _uiState.update { it.copy(isLoading = false) }
                        if (cachedChat.isGroup) {
                            val groupContact = User(
                                id = cachedChat.id,
                                name = cachedChat.groupName ?: text(R.string.chat_group),
                                avatar = cachedChat.groupAvatar,
                                status = text(R.string.chat_members_count, cachedChat.participants.size)
                            )
                            _uiState.update {
                                it.copy(
                                    chat = cachedChat,
                                    chatIsGroup = true,
                                    contact = groupContact,
                                    disappearingMessageSeconds = 0
                                )
                            }
                            loadGroupCandidates()
                        } else {
                            val contactUser = cachedChat.participants.firstOrNull { it.id != currentUserId }
                                ?.let { withLocalNickname(it) }
                            if (contactUser != null) {
                                _uiState.update {
                                    it.copy(
                                        chat = cachedChat.copy(
                                            participants = cachedChat.participants.map { p -> withLocalNickname(p) }
                                        ),
                                        chatIsGroup = false,
                                        contact = contactUser,
                                        disappearingMessageSeconds = cachedChat.disappearingMessageSeconds
                                    )
                                }
                                refreshBlockState(contactUser.id)
                                refreshScheduledMessages()
                            }
                        }
                    } else {
                        // 8.52 UX：无本地缓存时记录加载失败，UI 显示错误态 + 重试（区别于真实空会话）
                        _uiState.update { it.copy(isLoading = false, initialLoadError = text(R.string.chat_load_failed_title)) }
                    }
                }
                withContext(Dispatchers.IO) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@withContext
                    }
                    val msgToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
                    unreadSummaryWindow = ApiService.getUnreadWindow(msgToken, chatId, limit = 36).getOrNull()
                    ApiService.getMessages(msgToken, chatId, limit = HISTORY_PAGE_SIZE)
                        .onSuccess { dtos ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = loadOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@onSuccess
                            }
                            olderMessagesCursor = dtos
                                .minWithOrNull(compareBy<MessageDto> { it.timestamp }.thenBy { it.id })
                                ?.let { TokenManager.SyncCursor(it.timestamp, it.id) }
                            _uiState.update {
                                it.copy(hasMoreOlderMessages = dtos.size >= HISTORY_PAGE_SIZE)
                            }
                            val messages = dtos.sortedWith(
                                compareBy<MessageDto> { it.timestamp }
                                    .thenBy { if (it.type == "SK_DIST") 0 else 1 }
                                    .thenBy { it.id }
                            )
                                .mapNotNull { dto ->
                                    decryptIncomingMessage(
                                        dto.senderId,
                                        Message(
                                            id = dto.id,
                                            chatId = dto.chatId,
                                            senderId = dto.senderId,
                                            content = dto.content,
                                            type = MessageType.fromWire(dto.type),
                                            timestamp = dto.timestamp,
                                            status = MessageStatus.fromWire(dto.status),
                                            editedAt = dto.editedAt,
                                            starred = dto.starred,
                                            reactions = dto.reactions,
                                            expiresAt = dto.expiresAt, sealedSender = dto.sealedSender
                                        )
                                    )
                                }
                            // Long open-chat decrypt can outlive logout/switch — drop before UI/Room write.
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = loadOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@onSuccess
                            }
                            // 1.03：未读起点分隔线——未读窗口 totalCount>0 时，未读第一条 = 倒数第 totalCount 条
                            val unreadCount = unreadSummaryWindow?.totalCount ?: 0
                            val unreadSepId = if (unreadCount > 0 && messages.isNotEmpty()) {
                                messages.filter { it.type != MessageType.SK_DIST }
                                    .takeLast(unreadCount)
                                    .firstOrNull()
                                    ?.id
                            } else null
                            _uiState.update {
                                it.copy(
                                    messages = mergeMessages(it.messages, messages),
                                    unreadSeparatorId = unreadSepId
                                )
                            }
                            maybeAutoLoadLastGroupReadCount()
                            maybeGenerateUnreadSummary(messages)
                            // 缓存解密后的消息到本地 DB，确保离线时仍可查看
                            if (messages.isNotEmpty()) {
                                messageRepo.insertMessages(messages)
                                messages.forEach { indexSearchableMessage(it) }
                            }
                            // Replace list ciphertext placeholder with decrypted tail after open-chat fetch.
                            messages
                                .asSequence()
                                .filter { it.type != MessageType.SK_DIST }
                                .maxByOrNull { it.timestamp }
                                ?.let { emitListPreviewForDecrypted(it) }
                            if ((unreadSummaryWindow?.totalCount ?: 0) > 0) {
                                // Surface #68: 密聊 read-receipt 门控
                                if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_READ_RECEIPT_BLOCK)) {
                                    return@onSuccess
                                }
                                // 会话级 mark-read：带退避重试，不 fire-and-forget
                                var attempt = 0
                                while (attempt < 3) {
                                    attempt++
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = loadOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        break
                                    }
                                    val readToken = tokenManager.getToken().orEmpty().ifBlank { msgToken }
                                    val result = withContext(Dispatchers.IO + NonCancellable) {
                                        ApiService.markAllAsRead(readToken, effectiveChatId)
                                    }
                                    if (result.isSuccess) break
                                    if (attempt < 3) {
                                        try {
                                            kotlinx.coroutines.delay(1500L * attempt)
                                        } catch (_: kotlinx.coroutines.CancellationException) {
                                            break
                                        }
                                    }
                                }
                                com.maodouchat.MaodouchatApp.emitChatRead(chatId)
                            }
                        }
                        .onFailure {
                            // API 失败时从本地缓存加载已解密的消息
                            val cached = messageRepo.getRecentMessages(chatId, HISTORY_PAGE_SIZE)
                            if (cached.isNotEmpty()) {
                                olderMessagesCursor = cached
                                    .minWithOrNull(compareBy<Message> { it.timestamp }.thenBy { it.id })
                                    ?.let { TokenManager.SyncCursor(it.timestamp, it.id) }
                                _uiState.update {
                                    it.copy(
                                        messages = mergeMessages(it.messages, cached),
                                        hasMoreOlderMessages = true
                                    )
                                }
                                maybeGenerateUnreadSummary(cached)
                            } else if (_uiState.value.messages.isEmpty()) {
                                // 8.52 UX：消息加载失败且无缓存 → 错误态（区别于真实空会话）
                                _uiState.update { it.copy(initialLoadError = text(R.string.chat_load_failed_title)) }
                            }
                        }
                }
                // 先拉消息再回放 mutation：EDIT 依赖本地原文；并行会因缺消息永久跳过编辑
                syncMessagesSinceAndThenMutations()
                // 进会话即冲 SENDING 文本发件箱（不依赖 WS Connected 事件，避免已连接时无重放）
                withContext(Dispatchers.IO) { flushSendingOutbox() }
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
        val cursor = olderMessagesCursor ?: return
        val state = _uiState.value
        if (!state.hasMoreOlderMessages || state.isLoadingOlderMessages || olderMessagesJob?.isActive == true) return
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank() || ownerUserId == "me" || !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        _uiState.update { it.copy(isLoadingOlderMessages = true) }
        olderMessagesJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                loadOlderPage(ownerUserId, chatId, tokenManager.getToken().orEmpty())
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "loadOlderMessages failed", error)
            } finally {
                if (tokenManager.getUserId().orEmpty() == ownerUserId) {
                    _uiState.update { it.copy(isLoadingOlderMessages = false) }
                }
            }
        }
    }

    /**
     * 加载一页更早的历史消息并合并进列表。返回是否发生了向更早的翻页。
     * 供 [loadOlderMessages] 与 [jumpToDate] 复用；调用方负责 isLoading 标志。
     */
    private suspend fun loadOlderPage(
        ownerUserId: String,
        targetChatId: String,
        liveToken: String
    ): Boolean {
        val cursor = olderMessagesCursor ?: return false
        val dtos = ApiService.getMessagesBefore(
            token = liveToken,
            chatId = targetChatId,
            beforeMs = cursor.timestampMs,
            beforeId = cursor.messageId,
            limit = HISTORY_PAGE_SIZE,
        ).getOrThrow()
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return false
        }
        val nextCursor = dtos
            .minWithOrNull(compareBy<MessageDto> { it.timestamp }.thenBy { it.id })
            ?.let { TokenManager.SyncCursor(it.timestamp, it.id) }
        val movedOlder = nextCursor != null && (
            nextCursor.timestampMs < cursor.timestampMs ||
                (nextCursor.timestampMs == cursor.timestampMs && nextCursor.messageId < cursor.messageId)
            )
        if (!movedOlder) {
            olderMessagesCursor = null
            _uiState.update { it.copy(hasMoreOlderMessages = false) }
            return false
        }
        val messages = dtos
            .sortedWith(
                compareBy<MessageDto> { it.timestamp }
                    .thenBy { if (it.type == "SK_DIST") 0 else 1 }
                    .thenBy { it.id }
            )
            .mapNotNull { dto ->
                decryptIncomingMessage(
                    dto.senderId,
                    Message(
                        id = dto.id,
                        chatId = dto.chatId,
                        senderId = dto.senderId,
                        content = dto.content,
                        type = MessageType.fromWire(dto.type),
                        timestamp = dto.timestamp,
                        status = MessageStatus.fromWire(dto.status),
                        editedAt = dto.editedAt,
                        starred = dto.starred,
                        reactions = dto.reactions,
                        expiresAt = dto.expiresAt,
                        sealedSender = dto.sealedSender
                    )
                )
            }
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return false
        }
        olderMessagesCursor = nextCursor
        if (messages.isNotEmpty()) {
            messageRepo.insertMessages(messages)
            messages.forEach { indexSearchableMessage(it) }
        }
        _uiState.update {
            it.copy(
                messages = mergeMessages(it.messages, messages),
                hasMoreOlderMessages = dtos.size >= HISTORY_PAGE_SIZE
            )
        }
        return true
    }

    /**
     * 日历跳转：定位到指定日期第一条消息（本机已解密优先，缺失时向前翻页补拉），
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                // 1) 本机锚点：该日期及之后第一条消息
                var anchorId = messageRepo.getFirstMessageAtOrAfter(targetChatId, dayStartMillis)?.id
                if (anchorId.isNullOrBlank()) {
                    // 2) 本机没有该日期的消息：从服务端向前补拉（逐页）直到越过该日期
                    val earliestLocal = messageRepo.getEarliestMessageTimestamp(targetChatId)
                    if (earliestLocal == null || earliestLocal > dayStartMillis) {
                        val liveToken = tokenManager.getToken().orEmpty()
                        var guard = 0
                        while (guard++ < 30) {
                            if (messageRepo.getFirstMessageAtOrAfter(targetChatId, dayStartMillis) != null) break
                            val cursor = olderMessagesCursor ?: break
                            if (!loadOlderPage(ownerUserId, targetChatId, liveToken)) break
                        }
                    }
                    anchorId = messageRepo.getFirstMessageAtOrAfter(targetChatId, dayStartMillis)?.id
                }
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

    private suspend fun refreshPinnedMessages(expectedUserId: String) {
        if (expectedUserId.isBlank() || chatId.isBlank()) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        if (liveToken.isBlank()) return
        ApiService.getPinnedMessages(liveToken, chatId).onSuccess { response ->
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = expectedUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@onSuccess
            }
            _uiState.update { it.copy(pinnedMessages = response.pins) }
        }
    }

    private suspend fun refreshMyMemberRole(expectedUserId: String) {
        if (expectedUserId.isBlank() || chatId.isBlank()) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        if (liveToken.isBlank()) return
        ApiService.getGroupMembers(liveToken, chatId).onSuccess { members ->
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = expectedUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@onSuccess
            }
            val selfMember = members.firstOrNull { it.userId == expectedUserId }
            _uiState.update {
                it.copy(
                    myMemberRole = selfMember?.role,
                    // 8.48：群成员接口带 mutedUntil——本机被禁言时输入区明确提示（而非仅发送失败时）
                    myMutedUntil = selfMember?.mutedUntil ?: 0L,
                    // 0.65 新功能：全体成员角色映射，供消息发送者旁渲染群主/管理员徽章
                    memberRoleByUser = members.associate { m -> m.userId to m.role },
                    // 0.69 修复：群内昵称映射——群聊消息发送者显示名优先使用群昵称
                    memberNicknameByUser = members
                        .filter { !it.groupNickname.isNullOrBlank() }
                        .associate { m -> m.userId to m.groupNickname!! }
                )
            }
        }
    }

    fun togglePinMessage(messageId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_PIN)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val state = _uiState.value
        val message = state.messages.find { it.id == messageId } ?: return
        val pinOwnerUserId = currentUserId
        if (token.isBlank() || pinOwnerUserId.isBlank() || chatId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (!MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, message.type)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_pin_forbidden)) }
            return
        }
        val alreadyPinned = state.pinnedMessages.any { it.messageId == messageId }
        if (MessagePinPolicy.wouldExceedLimit(state.pinnedMessages.size, alreadyPinned)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_pin_limit, MessagePinPolicy.MAX_PINS)) }
            return
        }
        if (state.isTogglingPin) return
        _uiState.update { it.copy(isTogglingPin = true) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = pinOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isTogglingPin = false,
                            groupEncryptionWarning = text(R.string.error_session_expired)
                        )
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.togglePinnedMessage(liveToken, chatId, messageId).fold(
                    onSuccess = { response ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = pinOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                isTogglingPin = false,
                                pinnedMessages = response.pins,
                                groupEncryptionWarning = if (response.pinned) {
                                    text(R.string.chat_pin_success)
                                } else {
                                    text(R.string.chat_unpin_success)
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isTogglingPin = false,
                                groupEncryptionWarning = error.message ?: text(R.string.chat_pin_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isTogglingPin = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isTogglingPin = false,
                        groupEncryptionWarning = error.message ?: text(R.string.chat_pin_failed)
                    )
                }
            }
        }
    }

    fun jumpToPinnedMessage(messageId: String) {
        if (messageId.isBlank()) return
        _uiState.update { it.copy(navigationTargetMessageId = messageId) }
    }

    /** 通用跳转定位：点击引用预览/置顶跳转等，滚动到指定消息并高亮。 */
    fun jumpToMessage(messageId: String) {
        if (messageId.isBlank()) return
        _uiState.update { it.copy(navigationTargetMessageId = messageId) }
    }

    // 1.10：多选批量置顶/取消置顶（逐条调用置顶接口，最后统一提示成功/失败数）
    fun togglePinMessages(messageIds: List<String>, shouldPin: Boolean) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_PIN)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val state = _uiState.value
        val pinOwnerUserId = currentUserId
        if (token.isBlank() || pinOwnerUserId.isBlank() || chatId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (state.isTogglingPin) return
        val pinnedIds = state.pinnedMessages.map { it.messageId }.toSet()
        val targetIds = messageIds.filter { (it in pinnedIds) != shouldPin }
        if (targetIds.isEmpty()) return
        val targets = state.messages.filter { it.id in targetIds }
        if (targets.size != targetIds.size) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_pin_failed)) }
            return
        }
        // 1.24：仅处理可置顶类型的消息（系统/撤回等跳过），全部不可置顶才拒绝
        val pinnableTargets = targets.filter {
            MessagePinPolicy.canPin(state.chatIsGroup, state.myMemberRole, it.type)
        }
        if (pinnableTargets.isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_pin_forbidden)) }
            return
        }
        val effectiveTargetIds = pinnableTargets.map { it.id }
        if (shouldPin) {
            val newCount = pinnedIds.size + effectiveTargetIds.count { it !in pinnedIds }
            if (newCount > MessagePinPolicy.MAX_PINS) {
                _uiState.update {
                    it.copy(groupEncryptionWarning = text(R.string.chat_pin_limit, MessagePinPolicy.MAX_PINS))
                }
                return
            }
        }
        _uiState.update { it.copy(isTogglingPin = true) }
        viewModelScope.launch {
            var currentPins = state.pinnedMessages
            var successCount = 0
            var sessionExpired = false
            try {
                for (messageId in effectiveTargetIds) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = pinOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        sessionExpired = true
                        break
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    ApiService.togglePinnedMessage(liveToken, chatId, messageId).fold(
                        onSuccess = { response ->
                            successCount += 1
                            currentPins = response.pins
                        },
                        onFailure = { _ ->
                            // 单条失败继续尝试其余，最终按成败数统计提示
                        }
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isTogglingPin = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isTogglingPin = false,
                        groupEncryptionWarning = error.message ?: text(R.string.chat_pin_failed)
                    )
                }
                return@launch
            }
            val failedCount = effectiveTargetIds.size - successCount
            _uiState.update {
                it.copy(
                    isTogglingPin = false,
                    pinnedMessages = currentPins,
                    groupEncryptionWarning = when {
                        sessionExpired -> text(R.string.error_session_expired)
                        failedCount > 0 && successCount > 0 -> text(
                            if (shouldPin) R.string.chat_batch_pin_partial else R.string.chat_batch_unpin_partial,
                            successCount,
                            failedCount
                        )
                        successCount > 0 -> text(
                            if (shouldPin) R.string.chat_batch_pin_success else R.string.chat_batch_unpin_success,
                            successCount
                        )
                        else -> text(R.string.chat_pin_failed)
                    }
                )
            }
        }
    }

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
        _uiState.update { it.copy(inputText = cardContent) }
        sendMessage()
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.updateChatSettings(
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.updateChatSettings(
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

    fun setDisappearingMessages(seconds: Int) {
        val state = _uiState.value
        val chat = state.chat ?: return        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.DISAPPEARING_MESSAGES)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        if (chat.isGroup) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.disappear_group_unsupported)) }
            return
        }
        val normalized = com.maodouchat.util.DisappearingMessagePolicy.normalizeSeconds(seconds)
        if (!com.maodouchat.util.DisappearingMessagePolicy.isAllowedSeconds(normalized)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.disappear_invalid_timer)) }
            return
        }
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank() || chatId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (state.isUpdatingDisappearing) return
        val previous = state.disappearingMessageSeconds
        _uiState.update {
            it.copy(
                isUpdatingDisappearing = true,
                disappearingMessageSeconds = normalized,
                chat = it.chat?.copy(disappearingMessageSeconds = normalized)
            )
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isUpdatingDisappearing = false,
                            disappearingMessageSeconds = previous,
                            chat = it.chat?.copy(disappearingMessageSeconds = previous),
                            groupEncryptionWarning = text(R.string.error_session_expired)
                        )
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.updateDisappearingMessages(liveToken, chatId, normalized).fold(
                    onSuccess = { response ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val applied = com.maodouchat.util.DisappearingMessagePolicy.normalizeSeconds(response.seconds)
                        _uiState.update {
                            it.copy(
                                isUpdatingDisappearing = false,
                                disappearingMessageSeconds = applied,
                                chat = it.chat?.copy(disappearingMessageSeconds = applied),
                                groupEncryptionWarning = text(R.string.disappear_updated)
                            )
                        }
                        // 同步本地会话缓存
                        withContext(Dispatchers.IO) {
                            val local = chatRepo.getChatById(chatId)
                            if (local != null) {
                                chatRepo.cacheChats(listOf(local.copy(disappearingMessageSeconds = applied)))
                            }
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isUpdatingDisappearing = false,
                                disappearingMessageSeconds = previous,
                                chat = it.chat?.copy(disappearingMessageSeconds = previous),
                                groupEncryptionWarning = error.message ?: text(R.string.disappear_update_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update {
                    it.copy(
                        isUpdatingDisappearing = false,
                        disappearingMessageSeconds = previous,
                        chat = it.chat?.copy(disappearingMessageSeconds = previous)
                    )
                }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isUpdatingDisappearing = false,
                        disappearingMessageSeconds = previous,
                        chat = it.chat?.copy(disappearingMessageSeconds = previous),
                        groupEncryptionWarning = error.message ?: text(R.string.disappear_update_failed)
                    )
                }
            }
        }
    }

    private suspend fun applyMessageExpires(messageId: String, expiresAt: Long) {
        if (messageId.isBlank() || expiresAt <= 0L) return
        val existing = withContext(Dispatchers.IO) { messageRepo.getMessageById(messageId) } ?: return
        if (existing.expiresAt != null && existing.expiresAt > 0L) {
            // 已有截止时间不延长
            if (existing.expiresAt <= expiresAt) return
        }
        val updated = existing.copy(expiresAt = expiresAt)
        withContext(Dispatchers.IO) { messageRepo.insertMessage(updated) }
        _uiState.update { state ->
            if (!state.messages.any { it.id == messageId }) return@update state
            state.copy(
                messages = state.messages.map { if (it.id == messageId) it.copy(expiresAt = expiresAt) else it }
            )
        }
    }

    private suspend fun purgeExpiredLocalMessages(nowMs: Long = System.currentTimeMillis()) {
        // 阅后即焚：基于数据库扫描所有已过期消息（含已滚出内存窗口/分页未加载的），
        // 不再只依赖当前屏幕内存列表，避免私密内容因不在可见窗口而永久留存。
        val expiredIds = withContext(Dispatchers.IO) { messageRepo.deleteExpiredMessages(nowMs) }
        if (expiredIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            expiredIds.forEach { id ->
                MediaCache.deleteCachedMediaForMessage(getApplication(), id)
            }
        }
        // 8.32 修复 F9（隐私）：自毁消息删除后同步清理 tray 预览与通知中心条目，
        // 否则密文已删但通知栏仍展示正文预览。
        runCatching {
            com.maodouchat.data.repository.NotificationCenterRepository(getApplication())
                .deleteItemsForMessages(expiredIds)
        }
        runCatching {
            com.maodouchat.util.AppNotifier.cancelMessage(getApplication(), activeChatId)
        }
        val expiredSet = expiredIds.toSet()
        _uiState.update { s ->
            s.copy(
                messages = s.messages.filterNot { it.id in expiredSet },
                pinnedMessages = s.pinnedMessages.filterNot { it.messageId in expiredSet }
            )
        }
    }

    private fun connectWebSocket() {
        val connectOwnerUserId = currentUserId
        if (
            connectOwnerUserId.isBlank() ||
            connectOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = connectOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        if (liveToken.isBlank()) return
        WebSocketClient.connect(ApiConfig.WS_URL, liveToken)
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                // Buffered WS events after logout must not mutate the next account's UI/DB.
                if (tokenManager.getUserId().isNullOrBlank() || tokenManager.getToken().isNullOrBlank()) {
                    return@collect
                }
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        if (!isActiveChatEvent(activeChatId, event.message.chatId) || messageMutationTracker.shouldDrop(event.message.id)) {
                            return@collect
                        }
                        // 拉黑防穿透：来自被拉黑私聊联系人的消息不渲染、不落库、不回执。
                        if (_uiState.value.isContactBlocked && event.message.senderId == _uiState.value.contact.id) {
                            return@collect
                        }
                        val receiveOwnerUserId = currentUserId
                        if (
                            receiveOwnerUserId.isBlank() ||
                            receiveOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = receiveOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        val msg = withContext(Dispatchers.IO) { decryptIncomingMessage(event.message.senderId, event.message) } ?: return@collect
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = receiveOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        val rendered = if (messageMutationTracker.shouldRenderRevoked(msg.id)) {
                            msg.toRevokedPlaceholder(text(R.string.chat_message_revoked_placeholder))
                        } else msg
                        // Drain reactions that arrived before this row existed.
                        val withPendingReactions = withContext(Dispatchers.IO) {
                            mergePendingReactionsOnto(rendered)
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = receiveOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(withPendingReactions))) }
                        withContext(Dispatchers.IO) {
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = receiveOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@withContext
                            }
                            if (withPendingReactions.type == MessageType.REVOKED) {
                                messageTerminalStore.persistRevoked(withPendingReactions.id, withPendingReactions)
                                cleanupAttachmentForMessage(withPendingReactions.id)
                            } else {
                                messageRepo.insertMessage(withPendingReactions)
                                indexSearchableMessage(withPendingReactions)
                            }
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = receiveOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        // ChatList applied WS ciphertext placeholder first; after local decrypt
                        // push plaintext/media label so list tail matches the open conversation.
                        if (withPendingReactions.type != MessageType.SK_DIST) {
                            emitListPreviewForDecrypted(withPendingReactions)
                        }
                        // 只对他人会话消息发送 DELIVERED；自己的消息 / SK_DIST 控制流量不需要回执
                        if (withPendingReactions.senderId != currentUserId && withPendingReactions.type != MessageType.SK_DIST) {
                            WebSocketClient.sendStatusUpdate(withPendingReactions.id, MessageStatus.DELIVERED)
                        }
                        // 服务端 AUTO_DOWNLOAD 开关：实时到达的媒体消息在非计量网络下自动下载
                        maybeAutoDownloadMedia(withPendingReactions)
                    }
                    is WebSocketEvent.StatusChanged -> {
                        val statusOwnerUserId = currentUserId
                        if (
                            statusOwnerUserId.isBlank() ||
                            statusOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = statusOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        updateMessageStatus(event.messageId, event.status)
                    }
                    is WebSocketEvent.MessageDeleted -> {
                        // 对方删除了消息，从 UI 和本地缓存中移除
                        if (isActiveChatEvent(activeChatId, event.chatId)) {
                            val deleteOwnerUserId = currentUserId
                            if (
                                deleteOwnerUserId.isBlank() ||
                                deleteOwnerUserId == "me" ||
                                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = deleteOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@collect
                            }
                            messageMutationTracker.observeAuthoritative(event.messageId, MessageMutationKind.DELETE)
                            _uiState.update { state ->
                                state.copy(
                                    messages = state.messages.filterNot { it.id == event.messageId },
                                    pinnedMessages = state.pinnedMessages.filterNot { it.messageId == event.messageId }
                                )
                            }
                            withContext(Dispatchers.IO) {
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = deleteOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) {
                                    return@withContext
                                }
                                messageTerminalStore.persistDeleted(event.messageId)
                                cleanupAttachmentForMessage(event.messageId)
                            }
                            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = deleteOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(activeChatId)
                            }
                        }
                    }
                    is WebSocketEvent.MessageRevoked -> {
                        if (isActiveChatEvent(activeChatId, event.chatId)) {
                            val revokeOwnerUserId = currentUserId
                            if (
                                revokeOwnerUserId.isBlank() ||
                                revokeOwnerUserId == "me" ||
                                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = revokeOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@collect
                            }
                            messageMutationTracker.observeAuthoritative(event.messageId, MessageMutationKind.REVOKE)
                            val original = _uiState.value.messages.firstOrNull { it.id == event.messageId }
                                ?: withContext(Dispatchers.IO) { messageRepo.getMessageById(event.messageId) }
                                    ?.takeIf { it.chatId == activeChatId }
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = revokeOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@collect
                            }
                            val revoked = original?.toRevokedPlaceholder(text(R.string.chat_message_revoked_placeholder))
                            if (revoked != null) {
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.map { if (it.id == event.messageId) revoked else it },
                                        pinnedMessages = state.pinnedMessages.filterNot { it.messageId == event.messageId }
                                    )
                                }
                            } else {
                                _uiState.update { state ->
                                    state.copy(
                                        pinnedMessages = state.pinnedMessages.filterNot { it.messageId == event.messageId }
                                    )
                                }
                            }
                            withContext(Dispatchers.IO) {
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = revokeOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) {
                                    return@withContext
                                }
                                messageTerminalStore.persistRevoked(event.messageId, revoked)
                                cleanupAttachmentForMessage(event.messageId)
                            }
                            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = revokeOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(activeChatId)
                            }
                        }
                    }
                    is WebSocketEvent.MessageEdited -> {
                        if (event.chatId.isNotBlank() && event.chatId != activeChatId) return@collect
                        if (messageMutationTracker.shouldDrop(event.messageId) || messageMutationTracker.shouldRenderRevoked(event.messageId)) return@collect
                        val editOwnerUserId = currentUserId
                        if (
                            editOwnerUserId.isBlank() ||
                            editOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = editOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        val existing = _uiState.value.messages.firstOrNull { it.id == event.messageId }
                            ?: withContext(Dispatchers.IO) { messageRepo.getMessageById(event.messageId) }
                                ?.takeIf { it.chatId == activeChatId }
                            ?: return@collect
                        if (!shouldApplyRealtimeEdit(
                                activeChatId = activeChatId,
                                eventChatId = event.chatId,
                                existingMessageChatId = existing.chatId,
                                existingEditedAt = existing.editedAt,
                                eventEditedAt = event.editedAt
                            )
                        ) return@collect
                        messageMutationTracker.observeAuthoritative(event.messageId, MessageMutationKind.EDIT)
                        val edited = withContext(Dispatchers.IO) {
                            decryptIncomingMessage(existing.senderId, existing.copy(content = event.content, type = existing.type, editedAt = event.editedAt ?: System.currentTimeMillis()))
                        } ?: return@collect
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = editOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        _uiState.update { state -> state.copy(messages = mergeMessages(state.messages, listOf(edited))) }
                        withContext(Dispatchers.IO) {
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = editOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@withContext
                            }
                            messageRepo.insertMessage(edited)
                            indexSearchableMessage(edited)
                        }
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = editOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(activeChatId)
                        }
                    }
                    is WebSocketEvent.PinnedMessagesUpdated -> {
                        val pinOwnerUserId = currentUserId
                        if (
                            pinOwnerUserId.isBlank() ||
                            pinOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = pinOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        if (isActiveChatEvent(activeChatId, event.chatId)) {
                            _uiState.update { it.copy(pinnedMessages = event.pins) }
                        }
                    }
                    is WebSocketEvent.DisappearingMessagesUpdated -> {
                        val ownerUserId = currentUserId
                        if (
                            ownerUserId.isBlank() ||
                            ownerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        if (!isActiveChatEvent(activeChatId, event.chatId)) return@collect
                        val seconds = com.maodouchat.util.DisappearingMessagePolicy.normalizeSeconds(event.seconds)
                        _uiState.update {
                            it.copy(
                                disappearingMessageSeconds = seconds,
                                chat = it.chat?.copy(disappearingMessageSeconds = seconds)
                            )
                        }
                        withContext(Dispatchers.IO) {
                            val local = chatRepo.getChatById(event.chatId)
                            if (local != null) {
                                chatRepo.cacheChats(listOf(local.copy(disappearingMessageSeconds = seconds)))
                            }
                        }
                    }
                    is WebSocketEvent.MessageExpires -> {
                        val ownerUserId = currentUserId
                        if (
                            ownerUserId.isBlank() ||
                            ownerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        applyMessageExpires(event.messageId, event.expiresAt)
                    }
                    is WebSocketEvent.MessageReactionUpdated -> {
                        // Always persist when we hold the local row; UI merge only for open chat.
                        // Reactions are live-WS only (not in mutation cursor) so leave-chat must not drop them.
                        // If neither UI nor Room has the message yet, buffer until MessageReceived.
                        val reactionOwnerUserId = currentUserId
                        if (
                            reactionOwnerUserId.isBlank() ||
                            reactionOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = reactionOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        if (isActiveChatEvent(activeChatId, event.chatId)) {
                            val inUi = _uiState.value.messages.any { it.id == event.messageId }
                            if (inUi) {
                                updateMessageReactions(event.messageId, event.reactions)
                            } else {
                                viewModelScope.launch(Dispatchers.IO) {
                                    try {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = reactionOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            return@launch
                                        }
                                        val existing = messageRepo.getMessageById(event.messageId)
                                        if (existing == null) {
                                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                    expectedUserId = reactionOwnerUserId,
                                                    liveToken = tokenManager.getToken(),
                                                    liveUserId = tokenManager.getUserId(),
                                                )
                                            ) {
                                                return@launch
                                            }
                                            pendingReactions =
                                                com.maodouchat.ui.screen.chatlist.PendingReactionPolicy.put(
                                                    pending = pendingReactions,
                                                    chatId = event.chatId,
                                                    messageId = event.messageId,
                                                    reactions = event.reactions,
                                                    nowMs = System.currentTimeMillis()
                                                )
                                            return@launch
                                        }
                                        if (existing.chatId != event.chatId) return@launch
                                        if (existing.type == MessageType.REVOKED) return@launch
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = reactionOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            return@launch
                                        }
                                        messageRepo.updateMessageReactions(event.messageId, event.reactions)
                                        // Surface into open timeline if the row appears mid-flight.
                                        withContext(Dispatchers.Main) {
                                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                    expectedUserId = reactionOwnerUserId,
                                                    liveToken = tokenManager.getToken(),
                                                    liveUserId = tokenManager.getUserId(),
                                                )
                                            ) {
                                                return@withContext
                                            }
                                            if (_uiState.value.messages.any { it.id == event.messageId }) {
                                                updateMessageReactions(event.messageId, event.reactions, persist = false)
                                            }
                                        }
                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        Log.w("ChatDetailViewModel", "Failed to apply remote reaction", error)
                                    }
                                }
                            }
                        } else {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = reactionOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    val existing = messageRepo.getMessageById(event.messageId) ?: return@launch
                                    if (existing.chatId != event.chatId) return@launch
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = reactionOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    messageRepo.updateMessageReactions(event.messageId, event.reactions)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.w("ChatDetailViewModel", "Failed to apply remote reaction", error)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.UserTyping -> {
                        val typingOwnerUserId = currentUserId
                        if (
                            typingOwnerUserId.isBlank() ||
                            typingOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = typingOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        remoteTypingCoordinator.onEvent(
                            activeChatId = activeChatId,
                            eventChatId = event.chatId,
                            userId = event.userId,
                            isTyping = event.isTyping
                        )
                    }
                    is WebSocketEvent.GroupRevisionChanged -> handleGroupRevisionChanged(event)

                    is WebSocketEvent.UserOnline -> {
                        val presenceOwnerUserId = currentUserId
                        if (
                            presenceOwnerUserId.isBlank() ||
                            presenceOwnerUserId == "me" ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = presenceOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        if (event.onlineRevoked || event.statusRevoked) {
                            app.database.userDao().applyRealtimeVisibility(
                                userId = event.userId,
                                isOnline = event.isOnline,
                                onlineRevoked = event.onlineRevoked,
                                statusRevoked = event.statusRevoked,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        if (shouldApplyContactPresence(_uiState.value.chatIsGroup, _uiState.value.contact.id, event.userId)) {
                            if (event.onlineRevoked || event.statusRevoked) {
                                _uiState.update { state ->
                                    val contact = state.contact
                                    val visibility = com.maodouchat.network.resolveUserVisibility(
                                        currentIsOnline = contact.isOnline,
                                        currentStatus = contact.status,
                                        currentLastSeen = contact.lastSeen,
                                        eventIsOnline = event.isOnline,
                                        eventLastSeen = event.lastSeen,
                                        onlineRevoked = event.onlineRevoked,
                                        statusRevoked = event.statusRevoked
                                    )
                                    state.copy(
                                        contact = contact.copy(
                                            isOnline = visibility.isOnline,
                                            status = visibility.status,
                                            lastSeen = visibility.lastSeen
                                        )
                                    )
                                }
                                return@collect
                            }
                            if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.PRESENCE)) return@collect
                            // Surface #69: 密聊 presence 门控 — 防止在线状态侧信道
                            if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_PRESENCE_BLOCK)) return@collect
                            _uiState.update {
                                it.copy(contact = it.contact.copy(isOnline = event.isOnline, lastSeen = event.lastSeen))
                            }
                        }
                    }
                    is WebSocketEvent.Connected -> {
                        // 重连后走按 chat 游标的增量同步，避免仅靠 UI 时间戳窗口漏消息
                        if (event.success) {
                            // Clear soft WS disconnect banner if it was the connection-failed string.
                            _uiState.update { state ->
                                if (state.groupEncryptionWarning == text(R.string.chat_ws_connection_failed)) {
                                    state.copy(groupEncryptionWarning = null)
                                } else state
                            }
                            syncMessagesSinceAndThenMutations()
                            withContext(Dispatchers.IO) { flushSendingOutbox() }
                            // 8.45：重连成功时重试此前 markAllAsRead 失败积压的已读回执，
                            // 避免服务端未读长期保留、本地 UI 与服务端不一致
                            retryPendingServerReads()
                        } else {
                            _uiState.update {
                                it.copy(groupEncryptionWarning = text(R.string.chat_ws_connection_failed))
                            }
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        // Peer typing leases are WS-only; drop them so UI does not stick after drop.
                        remoteTypingCoordinator.clear()
                        // Soft banner only when no higher-priority encryption/AI warning is showing.
                        val connectionMsg = text(R.string.chat_ws_connection_failed)
                        _uiState.update { state ->
                            if (state.groupEncryptionWarning.isNullOrBlank() ||
                                state.groupEncryptionWarning == connectionMsg
                            ) {
                                state.copy(groupEncryptionWarning = connectionMsg)
                            } else state
                        }
                    }
                    is WebSocketEvent.Error -> {
                        Log.w("ChatDetailViewModel", "WS error: ${event.kind}; ${event.debugDetail.orEmpty()}")
                        if (event.kind == com.maodouchat.network.WebSocketErrorKind.CONNECTION) {
                            val connectionMsg = text(R.string.chat_ws_connection_failed)
                            _uiState.update { state ->
                                if (state.groupEncryptionWarning.isNullOrBlank() ||
                                    state.groupEncryptionWarning == connectionMsg
                                ) {
                                    state.copy(groupEncryptionWarning = connectionMsg)
                                } else state
                            }
                        } else {
                            _uiState.update {
                                it.copy(groupEncryptionWarning = text(R.string.chat_ws_data_invalid))
                            }
                        }
                    }
                    is WebSocketEvent.ServerError -> {
                        // 8.63：服务端 WS 明确拒绝（禁言/拉黑/无权限/内容无效/附件未完成/单向广播）。
                        // 错误帧不含 messageId 无法精确对位——把被拒消息标 FAILED 并提示，
                        // 避免被拒消息永久转圈（此前仅靠 REST outbox flush 触发 403 才有反馈）。
                        // 8.45 修复：限流类（频繁/限制/稍后再试）不是永久拒绝——保留 SENDING
                        // 交给 flusher 退避重试，不再误标失败；且只标最新一条在途消息，避免
                        // 一条附件错误把同时发出的其他消息全部误伤为失败。
                        val errText = event.message
                        val lower = errText.lowercase()
                        val isThrottled = lower.contains("频繁") || lower.contains("限制") ||
                            lower.contains("稍后再试") || lower.contains("too many")
                        val isRejection = event.code?.let { it != "MSG_ALREADY_EXISTS" } == true || (
                            lower.contains("禁言") || lower.contains("屏蔽") || lower.contains("无权") ||
                                lower.contains("无效") || lower.contains("尚未上传完成") || lower.contains("单向广播")
                            )
                        if (isRejection && !lower.contains("id 已存在") && !isThrottled) {
                            markWsRejectedSending(errText)
                        } else {
                            Log.w("ChatDetailViewModel", "WS server error: ${event.code.orEmpty()} $errText")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 8.63：服务端 WS 拒绝（禁言/拉黑/无权限/内容无效/附件未完成）——错误帧无 messageId，
     * 只把「最新一条」在途 SENDING 消息标 FAILED 并提示（8.45：不一次误伤全部在途消息，
     * 避免一条附件错误把同时发出的其他消息全部标失败）。
     */
    private suspend fun markWsRejectedSending(message: String) {
        val allSending = _uiState.value.messages.filter { it.status == MessageStatus.SENDING }.map { it.id }
        if (allSending.isEmpty()) return
        val ids = allSending.takeLast(1)
        val banner = message.take(120).ifBlank { text(R.string.chat_send_failed) }
        withContext(Dispatchers.IO) {
            ids.forEach { id -> messageRepo.updateMessageStatus(id, MessageStatus.FAILED) }
        }
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { m -> if (m.id in ids) m.copy(status = MessageStatus.FAILED) else m },
                groupEncryptionWarning = banner
            )
        }
    }

    /**
     * 8.45：重连成功后重试积压的已读回执（markAllAsRead 3 次短重试 + 8s 恢复均失败后，
     * 积压 id 保留在 pendingServerReadIds，由这里在连接恢复时收敛）。
     */
    private suspend fun retryPendingServerReads() {
        if (pendingServerReadIds.isEmpty()) return
        val markReadUserId = currentUserId
        if (
            markReadUserId.isBlank() ||
            markReadUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = markReadUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        val readChatId = activeChatId.ifBlank { chatId }
        if (readChatId.isBlank()) return
        val batch = pendingServerReadIds.toSet()
        val liveTok = tokenManager.getToken().orEmpty().ifBlank { token }
        val result = withContext(Dispatchers.IO + NonCancellable) {
            ApiService.markAllAsRead(liveTok, readChatId)
        }
        if (result.isSuccess) {
            pendingServerReadIds.removeAll(batch)
        } else {
            Log.w(
                "ChatDetailViewModel",
                "retryPendingServerReads failed for $readChatId: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    private suspend fun handleGroupRevisionChanged(event: WebSocketEvent.GroupRevisionChanged) {
        val revisionOwnerUserId = currentUserId
        if (
            revisionOwnerUserId.isBlank() ||
            revisionOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
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
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return
            }
            invalidateGroupSenderKey(event.chatId)
        }
        if (removedFromGroup) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = revisionOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return
            }
            remoteTypingCoordinator.clear()
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
            withContext(Dispatchers.IO) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revisionOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@withContext
                }
                // Match list cleanupLocalChat: leave must not leave media/search/messages behind
                // until the next getChats (user may stay offline).
                val cachedMessageIds = messageRepo.getMessageIdsByChatId(event.chatId)
                com.maodouchat.attachment.AttachmentTransferCoordinator.cancelForChat(
                    getApplication(),
                    event.chatId
                )
                runCatching {
                    val appCtx = getApplication<Application>()
                    val removed = com.maodouchat.util.ScheduledMessageStore.clearForChat(appCtx, event.chatId)
                    removed.forEach { com.maodouchat.util.ScheduledMessageScheduler.cancel(appCtx, it) }
                }
                aiTaskRepo.deleteByChatId(event.chatId)
                aiOperationRepo.deleteByChatId(event.chatId)
                aiSummaryRepo.deleteByChatId(event.chatId)
                app.database.chatDraftDao().deleteForChat(revisionOwnerUserId, event.chatId)
                app.database.chatLockDao().remove(event.chatId)
                com.maodouchat.security.ChatLockSession.clear(event.chatId)
                app.database.secretChatDao().remove(event.chatId)
                com.maodouchat.security.SecretChatSession.markSurfaceInactive(event.chatId, getApplication())
                app.database.senderKeyRetryDao().delete(currentUserId, event.chatId)
                try {
                    app.database.attachmentTransferDao().clearWireContentForChat(
                        event.chatId,
                        ownerUserId = currentUserId
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("ChatDetailViewModel", "clearWireContentForChat failed for ${event.chatId}", error)
                }
                cachedMessageIds.forEach { messageId ->
                    com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
                }
                try {
                    app.database.messageSearchDao().deleteChatIndex(event.chatId)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("ChatDetailViewModel", "deleteChatIndex failed for ${event.chatId}", error)
                }
                tokenManager.clearChatCursors(event.chatId)
                try {
                    app.notificationCenter.removeChatItems(event.chatId)
                    com.maodouchat.util.AppNotifier.cancelMessage(getApplication(), event.chatId)
                    com.maodouchat.util.AppNotifier.cancelAiTaskRemindersForChat(getApplication(), event.chatId)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("ChatDetailViewModel", "notification cleanup failed for ${event.chatId}", error)
                }
                messageRepo.deleteMessagesByChatId(event.chatId)
                chatRepo.deleteChat(event.chatId)
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = revisionOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
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
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
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

    private var typingDebounceJob: kotlinx.coroutines.Job? = null
    private var announcedTypingChatId: String? = null

    fun onInputChange(text: String) {
        hasUserEditedInput = true
        // 8.52 UX：对齐服务端 4000 上限本地截断（此前粘贴超长文本会无限进入输入态/被服务端拒绝）
        val clipped = if (text.length > MAX_COMPOSER_TEXT_LENGTH) text.take(MAX_COMPOSER_TEXT_LENGTH) else text
        if (_uiState.value.aiDraftOriginal != null) discardAiDraftPreview()
        if (_uiState.value.isAiReplyStreaming) cancelAiReplyStream(clearSuggestions = true)
        // 1.162：用户编辑后清除「已恢复草稿」标识
        _uiState.update { it.copy(inputText = clipped, hasSavedDraft = false, aiSuggestions = emptyList(), aiReplyStreamErrorCode = null) }
        scheduleDraftPersistence(clipped)
        typingDebounceJob?.cancel()
        when (resolveTypingSignalAction(announcedTypingChatId != null, text.isNotBlank())) {
            TypingSignalAction.START -> announceTypingStarted()
            TypingSignalAction.STOP -> stopTypingAnnouncement()
            TypingSignalAction.NONE -> Unit
        }
        if (text.isNotBlank()) {
            typingDebounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(REMOTE_TYPING_TIMEOUT_MS)
                stopTypingAnnouncement()
            }
        }
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
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) throw kotlinx.coroutines.CancellationException("ai_result_context_changed")
        val committed = withContext(Dispatchers.IO) {
            aiMessageResultStore.commit(operationId, message)
        }
        if (!committed) return false
        if (activeChatId != expectedChatId ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) return false
        if (operationId != null) {
            aiAutoRetryJobs.remove(operationId)?.cancel()
            aiAutoRetryAt.remove(operationId)
        }
        pushAiMessageMeta(message, expectedUserId)
        return true
    }

    private fun announceTypingStarted() {
        // Surface #67: 密聊 typing 门控 — 防止输入状态侧信道泄露
        if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_TYPING_BLOCK)) {
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.TYPING_INDICATORS)) return
        if (!TypingSessionPolicy.shouldAnnounceStart(activeChatId, announcedTypingChatId)) return
        val chatId = activeChatId
        val typingOwnerUserId = currentUserId
        // Logout/disconnect/switch: do not emit typing under a dead or switched session.
        if (!TypingSessionPolicy.mayEmit(
                ownerUserId = typingOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId()
            )
        ) {
            return
        }
        announcedTypingChatId?.let { WebSocketClient.sendTyping(it, false) }
        if (WebSocketClient.sendTyping(chatId, true)) announcedTypingChatId = chatId
    }

    private fun stopTypingAnnouncement() {
        // Surface #67: 密聊 typing 门控 — 从未发起则无需 stop（避免状态泄漏）
        if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_TYPING_BLOCK)) {
            return
        }
        val chatId = announcedTypingChatId ?: return
        announcedTypingChatId = null
        val typingOwnerUserId = currentUserId
        // Best-effort stop; only if socket still authenticated for this process session.
        if (!TypingSessionPolicy.mayEmit(
                ownerUserId = typingOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId()
            )
        ) {
            return
        }
        WebSocketClient.sendTyping(chatId, false)
    }

    private fun observeAttachmentFinalizedEvents() {
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

    private fun restoreDraft() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (!ChatDraftPolicy.canSchedule(ownerUserId, activeChatId)) return
        viewModelScope.launch(Dispatchers.IO) {
            val draft = chatDraftDao.get(ownerUserId, activeChatId)?.text.orEmpty()
            // Account switch between load and UI apply: never inject previous owner's draft.
            if (!ChatDraftPolicy.shouldWrite(ownerUserId, tokenManager.getUserId())) return@launch
            if (draft.isNotBlank() && !hasUserEditedInput) {
                _uiState.update { state ->
                    if (!ChatDraftPolicy.shouldApplyRestoredDraft(hasUserEditedInput, state.inputText)) state
                    else state.copy(inputText = draft, hasSavedDraft = true)
                }
            }
        }
    }

    /** 1.162：清空已恢复草稿（本地删除 + 清除标识）。 */
    fun clearDraftPersistence() {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank() || activeChatId.isBlank()) return
        draftSaveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatDraftDao.delete(ownerUserId, activeChatId) }
        }
        _uiState.update { it.copy(hasSavedDraft = false) }
    }

    internal fun scheduleDraftPersistence(text: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val targetChatId = activeChatId
        if (!ChatDraftPolicy.canSchedule(ownerUserId, targetChatId)) return
        draftSaveJob?.cancel()
        val generation = ++draftGeneration
        draftSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(ChatDraftPolicy.SAVE_DELAY_MS)
            if (!ChatDraftPolicy.shouldPersistGeneration(generation, draftGeneration)) return@launch
            if (!ChatDraftPolicy.shouldWrite(ownerUserId, tokenManager.getUserId())) return@launch            persistDraft(ownerUserId, targetChatId, text)
        }
    }

    private suspend fun persistDraft(ownerUserId: String, targetChatId: String, text: String) {
        if (!ChatDraftPolicy.shouldWrite(ownerUserId, tokenManager.getUserId())) return
        if (ChatDraftPolicy.isClearRequest(text)) {
            chatDraftDao.delete(ownerUserId, targetChatId)
        } else {
            chatDraftDao.upsert(
                ChatDraftEntity(
                    ownerUserId = ownerUserId,
                    chatId = targetChatId,
                    text = text,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    internal fun clearDraft() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val targetChatId = activeChatId
        draftSaveJob?.cancel()
        draftSaveJob = null
        // Invalidate any in-flight delayed upsert so send cannot resurrect draft after clear.
        draftGeneration++
        if (!ChatDraftPolicy.canSchedule(ownerUserId, targetChatId)) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                chatDraftDao.delete(ownerUserId, targetChatId)
            }
        }
    }

    private fun normalizeAiRewriteMode(mode: String?): String {
        val m = mode?.trim()?.lowercase().orEmpty()
        return when (m) {
            "polish", "shorten", "formal", "gentle", "casual",
            "professional", "expand", "bullet", "translate", "clarify" -> m
            else -> "polish"
        }
    }

    fun requestAiRewrite(mode: String, targetLanguage: String? = null) {
        if (_uiState.value.isAiWorking) return
        // 密聊会话禁止 AI 改写：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_REWRITE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_rewrite_disabled)) }
            return
        }
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        if (_uiState.value.inputText.trim().isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_enter_draft)) }
            return
        }
        runAiWithConsent(PendingAiAction.Rewrite(normalizeAiRewriteMode(mode), targetLanguage))
    }

    fun requestAiSuggestions(tone: String = "friendly") {
        if (_uiState.value.isAiWorking) return
        // 密聊会话禁止 AI 建议回复：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_SUGGEST_REPLIES)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_suggest_replies_disabled)) }
            return
        }
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        if (buildAiContextMessages(limit = 12).isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_no_reply_context)) }
            return
        }
        val safeTone = when (tone.trim().lowercase()) {
            "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
            else -> "friendly"
        }
        lastAiReplyTone = safeTone
        runAiWithConsent(PendingAiAction.SuggestReplies(safeTone))
    }

    fun requestAiSummary(
        scope: AiSummaryScope,
        searchResultIds: List<String> = emptyList(),
        style: String = "brief"
    ) {
        if (_uiState.value.isAiWorking) return
        // 密聊会话禁止 AI 聚合：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_SUMMARY)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_summary_disabled)) }
            return
        }
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        val safeStyle = when (style.trim().lowercase()) {
            "brief", "detailed", "decisions", "tasks", "timeline", "risks" -> style.trim().lowercase()
            else -> "brief"
        }
        viewModelScope.launch {
            val cachedMessages = try {
                withContext(Dispatchers.IO) {
                    messageRepo.getMessagesByChatId(activeChatId).first()
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.value.messages
            }
            val allDecryptedMessages = (cachedMessages + _uiState.value.messages)
                .associateBy(Message::id)
                .values
                .toList()
            val candidates = summaryCandidates(scope, searchResultIds, allDecryptedMessages)
            if (candidates.isEmpty()) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_no_summary_context)) }
                return@launch
            }
            runAiWithConsent(PendingAiAction.Summarize(scope, candidates, safeStyle))
        }
    }

    internal fun requestGroupAiAssistant(query: String, mode: String? = null) {
        if (_uiState.value.chat?.isGroup != true) return
        if (_uiState.value.isAiWorking) return
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        val normalizedQuery = query.trim().take(600)
        if (normalizedQuery.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_empty_query)) }
            return
        }
        if (buildGroupAiContextMessages().isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_no_context)) }
            return
        }
        val safeMode = when (mode?.trim()?.lowercase()) {
            "answer", "summary", "decisions", "tasks", "timeline", "risks" -> mode.trim().lowercase()
            else -> inferGroupAiMode(normalizedQuery)
        }
        runAiWithConsent(PendingAiAction.GroupAssistant(normalizedQuery, safeMode))
    }

    fun requestGroupAiWithMode(query: String, mode: String) {
        requestGroupAiAssistant(query, mode)
    }

    fun requestVoiceTranscription(messageId: String) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        val isTranscribing = messageId in _uiState.value.transcribingVoiceMessageIds
        if (!com.maodouchat.util.VoiceTranscriptPolicy.canRequest(
                isVoiceMessage = message.type == MessageType.VOICE,
                transcript = message.parsedMeta().voiceTranscript,
                isTranscribing = isTranscribing
            )
        ) {
            if (com.maodouchat.util.VoiceTranscriptPolicy.hasTranscript(message.parsedMeta().voiceTranscript)) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_transcript_exists)) }
            }
            return
        }
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        runAiWithConsent(PendingAiAction.TranscribeVoice(messageId))
    }

    fun requestMessageTranslation(messageId: String, targetLanguage: String = DEFAULT_TRANSLATION_LANGUAGE) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.type != MessageType.TEXT && message.type != MessageType.MARKDOWN) return
        if (messageId in _uiState.value.translatingMessageIds) return
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        val text = message.parsedContent().trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_no_translation_text)) }
            return
        }
        val currentMeta = message.parsedMeta()
        if (!currentMeta.translations[targetLanguage].isNullOrBlank()) {
            val updatedMeta = currentMeta.copy(preferredTranslationLanguage = targetLanguage)
            val updated = message.copy(content = composeContentWithMeta(message.parsedContent(), updatedMeta))
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { if (it.id == messageId) updated else it },
                    groupEncryptionWarning = text(R.string.chat_translation_selected)
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                messageRepo.insertMessage(updated)
                pushAiMessageMeta(updated)
            }
            return
        }
        runAiWithConsent(PendingAiAction.TranslateMessage(messageId, targetLanguage))
    }

    fun requestAiImageAnalysis(messageId: String, mode: AiImageAnalysisMode) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.type != MessageType.IMAGE || _uiState.value.isAiWorking) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_ANALYZE_IMAGE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_image_disabled)) }
            return
        }
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        val cached = message.parsedMeta().aiImageAnalyses[mode.wireValue]?.trim()?.takeIf(String::isNotBlank)
        if (cached != null) {
            val updatedMeta = message.parsedMeta().copy(preferredImageAnalysisMode = mode.wireValue)
            val updated = message.copy(
                content = composeContentWithMeta(message.parsedContent(), updatedMeta),
                meta = updatedMeta
            )
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { if (it.id == messageId) updated else it },
                    aiImageAnalysisResult = cached,
                    aiImageAnalysisMode = mode
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                messageRepo.insertMessage(updated)
                pushAiMessageMeta(updated)
            }
            return
        }
        runAiWithConsent(PendingAiAction.AnalyzeImage(messageId, mode))
    }

    fun clearAiImageAnalysis() {
        _uiState.update { it.copy(aiImageAnalysisResult = null, aiImageAnalysisMode = null) }
    }

    fun requestAiFileAnalysis(messageId: String, mode: AiFileAnalysisMode, question: String? = null) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_ANALYZE_FILE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_file_disabled)) }
            return
        }
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.type != MessageType.FILE || _uiState.value.isAiWorking) return
        val normalizedQuestion = question?.trim()?.take(500)
        if (mode == AiFileAnalysisMode.QUESTION && normalizedQuestion.isNullOrBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_file_question_empty)) }
            return
        }
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        val analysisKey = if (mode == AiFileAnalysisMode.QUESTION) {
            "question:" + (normalizedQuestion?.take(120) ?: "default")
        } else {
            mode.wireValue
        }
        val cached = message.parsedMeta().aiFileAnalyses[analysisKey]?.trim()?.takeIf(String::isNotBlank)
        if (cached != null) {
            val updatedMeta = message.parsedMeta().copy(
                preferredFileAnalysisMode = mode.wireValue,
                aiFileLastQuestion = if (mode == AiFileAnalysisMode.QUESTION) normalizedQuestion else message.parsedMeta().aiFileLastQuestion
            )
            val updated = message.copy(
                content = composeContentWithMeta(message.parsedContent(), updatedMeta),
                meta = updatedMeta
            )
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { if (it.id == messageId) updated else it },
                    aiFileAnalysisResult = cached,
                    aiFileAnalysisMode = mode,
                    aiFileAnalysisName = message.parsedMeta().fileName
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                messageRepo.insertMessage(updated)
                pushAiMessageMeta(updated)
            }
            return
        }
        runAiWithConsent(PendingAiAction.AnalyzeFile(messageId, mode, normalizedQuestion))
    }

    fun clearAiFileAnalysis() {
        _uiState.update {
            it.copy(aiFileAnalysisResult = null, aiFileAnalysisMode = null, aiFileAnalysisName = null)
        }
    }

    fun requestOpenFile(messageId: String) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.FILE } ?: return
        if (MediaCache.isReadableLocalUri(getApplication(), message.parsedContent())) {
            _uiState.update { it.copy(fileReadyToOpenUri = message.parsedContent()) }
            return
        }
        if (messageId in _uiState.value.downloadingFileMessageIds) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    downloadingFileMessageIds = it.downloadingFileMessageIds + messageId,
                    mediaDownloadErrorMessageIds = it.mediaDownloadErrorMessageIds - messageId,
                    fileTransferProgress = it.fileTransferProgress + (messageId to 0f),
                    groupEncryptionWarning = null
                )
            }
            try {
                ensureLocalAttachment(message).fold(
                    onSuccess = { localMessage ->
                        _uiState.update {
                            it.copy(
                                downloadingFileMessageIds = it.downloadingFileMessageIds - messageId,
                                fileTransferProgress = it.fileTransferProgress - messageId,
                                fileReadyToOpenUri = localMessage.parsedContent()
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                downloadingFileMessageIds = it.downloadingFileMessageIds - messageId,
                                fileTransferProgress = it.fileTransferProgress - messageId,
                                groupEncryptionWarning = attachmentErrorText(error, R.string.chat_attachment_download_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update {
                    it.copy(
                        downloadingFileMessageIds = it.downloadingFileMessageIds - messageId,
                        fileTransferProgress = it.fileTransferProgress - messageId
                    )
                }
                throw error
            }
        }
    }

    fun requestMediaAttachment(messageId: String) {
        val message = _uiState.value.messages.firstOrNull {
            it.id == messageId && it.type in AUTO_DOWNLOAD_MEDIA_TYPES
        } ?: return
        if (MediaCache.isReadableLocalUri(getApplication(), message.parsedContent())) return
        if (messageId in _uiState.value.downloadingFileMessageIds) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    downloadingFileMessageIds = it.downloadingFileMessageIds + messageId,
                    fileTransferProgress = it.fileTransferProgress + (messageId to 0f),
                    groupEncryptionWarning = null
                )
            }
            try {
                ensureLocalAttachment(message).fold(
                    onSuccess = {
                        _uiState.update { state ->
                            state.copy(
                                downloadingFileMessageIds = state.downloadingFileMessageIds - messageId,
                                mediaDownloadErrorMessageIds = state.mediaDownloadErrorMessageIds - messageId,
                                fileTransferProgress = state.fileTransferProgress - messageId
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update { state ->
                            state.copy(
                                downloadingFileMessageIds = state.downloadingFileMessageIds - messageId,
                                mediaDownloadErrorMessageIds = state.mediaDownloadErrorMessageIds + messageId,
                                fileTransferProgress = state.fileTransferProgress - messageId,
                                groupEncryptionWarning = attachmentErrorText(error, R.string.chat_attachment_download_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { state ->
                    state.copy(
                        downloadingFileMessageIds = state.downloadingFileMessageIds - messageId,
                        fileTransferProgress = state.fileTransferProgress - messageId
                    )
                }
                throw error
            }
        }
    }

    fun consumeFileReadyToOpen() {
        _uiState.update { it.copy(fileReadyToOpenUri = null) }
    }

    /**
     * 实时收到的媒体消息按服务端 AUTO_DOWNLOAD 开关自动下载（此前该开关只写不读）。
     * 约束：仅他人消息、仅非计量网络（Wi-Fi/非计费）、密聊不做（密聊默认最小化本地留存）、
     * 已有本地副本或正在下载时由 [requestMediaAttachment] 幂等短路。
     */
    private fun maybeAutoDownloadMedia(message: Message) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AUTO_DOWNLOAD)) return
        if (message.type !in AUTO_DOWNLOAD_MEDIA_TYPES) return
        if (message.senderId == currentUserId) return
        if (_uiState.value.isSecretChat == true) return
        // 1.89：用户级「媒体自动下载」偏好（仅 Wi-Fi 默认 / 始终 / 关闭）
        val mode = com.maodouchat.util.MediaAutoDownloadPreferences.getMode(getApplication())
        if (mode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_OFF) return
        if (mode != com.maodouchat.util.MediaAutoDownloadPreferences.MODE_ALWAYS) {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val network = cm.activeNetwork ?: return
            val capabilities = cm.getNetworkCapabilities(network) ?: return
            val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (!unmetered) return
        }
        requestMediaAttachment(message.id)
    }

    fun requestSemanticSearch(query: String, candidateMessageIds: List<String>) {
        // 密聊会话禁止语义搜索：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        val normalizedQuery = query.trim().take(300)
        if (!_uiState.value.aiEnabled) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.chat_ai_disabled_warning)) }
            return
        }
        if (normalizedQuery.isBlank()) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.chat_semantic_search_enter_query)) }
            return
        }
        val safeIds = candidateMessageIds.filter(String::isNotBlank).distinct().take(100)
        if (safeIds.isEmpty()) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.chat_semantic_search_no_context)) }
            return
        }
        runAiWithConsent(PendingAiAction.SemanticSearch(normalizedQuery, safeIds))
    }

    fun clearSemanticSearch() {
        semanticSearchGate.invalidate()
        semanticSearchJob?.cancel()
        semanticSearchJob = null
        if (pendingAiAction is PendingAiAction.SemanticSearch) {
            pendingAiAction = null
        }
        _uiState.update {
            it.copy(
                semanticSearchResultIds = emptyList(),
                semanticSearchQuery = "",
                isSemanticSearching = false,
                semanticSearchError = null,
                showAiConsentDialog = if (pendingAiAction == null) false else it.showAiConsentDialog
            )
        }
    }

    fun consumeNavigationTarget() {
        _uiState.update { it.copy(navigationTargetMessageId = null) }
    }

    fun acceptAiConsentAndContinue() {
        com.maodouchat.ai.AiPrivacyPreferences.setConsentAccepted(app, true)
        val action = pendingAiAction
        val retryOperationId = pendingAiOperationRetryId
        pendingAiAction = null
        pendingAiOperationRetryId = null
        _uiState.update { it.copy(showAiConsentDialog = false) }
        when {
            retryOperationId != null -> retryAiOperation(retryOperationId)
            action != null -> executeAiAction(action)
        }
        pullSyncedAiSummaries()
        maybeGenerateUnreadSummary(_uiState.value.messages)
    }

    fun dismissAiConsent() {
        pendingAiAction = null
        pendingAiOperationRetryId = null
        _uiState.update { it.copy(showAiConsentDialog = false) }
    }

    fun applyAiSuggestion(text: String) {
        cancelAiReplyStream(clearSuggestions = true)
        hasUserEditedInput = true
        _uiState.update { it.copy(inputText = text, aiSuggestions = emptyList()) }
        scheduleDraftPersistence(text)
    }

    fun clearAiSuggestions() {
        cancelAiReplyStream(clearSuggestions = true)
    }

    fun clearAiSummary() {
        _uiState.update { it.copy(aiSummary = null, aiSummaryScope = null, aiSummaryMessageCount = 0) }
    }

    fun openAiSummaryHistory() {
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(showAiSummaryHistory = false, isAiSummaryHistoryLoading = false) }
            return
        }
        _uiState.update { it.copy(showAiSummaryHistory = true, isAiSummaryHistoryLoading = true) }
        viewModelScope.launch {
            try {
                val summaries = withContext(Dispatchers.IO) {
                    if (com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) {
                        aiSummarySyncRepo.pull(
                            tokenManager.getToken().orEmpty(),
                            request.userId,
                            liveToken = tokenManager::getToken,
                            liveUserId = tokenManager::getUserId
                        )
                    }
                    aiSummaryRepo.getSummariesForChat(request.chatId, limit = 30)
                }
                requireAiRequestCurrent(request)
                _uiState.update {
                    it.copy(
                        isAiSummaryHistoryLoading = false,
                        aiSummaryHistory = summaries.map { entity ->
                            AiSummaryHistoryUi(
                                cacheKey = entity.cacheKey,
                                summary = entity.summary,
                                scope = summaryScopeFromCacheKey(entity.cacheKey),
                                messageCount = entity.messageCount,
                                createdAt = entity.createdAt
                            )
                        }
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isAiSummaryHistoryLoading = false) }
                }
                throw error
            } catch (_: Exception) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { state -> state.copy(isAiSummaryHistoryLoading = false) }
                }
            }
        }
    }

    fun dismissAiSummaryHistory() {
        _uiState.update { it.copy(showAiSummaryHistory = false) }
    }

    fun openAiSummaryFromHistory(cacheKey: String) {
        val item = _uiState.value.aiSummaryHistory.firstOrNull { it.cacheKey == cacheKey } ?: return
        _uiState.update {
            it.copy(
                showAiSummaryHistory = false,
                aiSummary = item.summary,
                aiSummaryScope = item.scope,
                aiSummaryMessageCount = item.messageCount
            )
        }
    }

    private fun summaryScopeFromCacheKey(cacheKey: String): AiSummaryScope {
        if (!cacheKey.startsWith("manual:")) return AiSummaryScope.UNREAD
        return cacheKey.split(':').getOrNull(2)
            ?.let { value -> AiSummaryScope.entries.firstOrNull { it.name == value } }
            ?: AiSummaryScope.RECENT
    }

    fun clearGroupAiAnswer() {
        _uiState.update {
            it.copy(
                groupAiAnswer = null,
                groupAiQuestion = "",
                groupAiMode = "answer",
                groupAiTasks = emptyList(),
                isSavingGroupAiTasks = false,
                groupAiTasksSaved = false,
                groupAiTaskSaveError = null,
                groupAiAnswerShared = false
            )
        }
    }

    fun saveGroupAiTasks() {
        val state = _uiState.value
        val targetChatId = activeChatId.takeIf(String::isNotBlank) ?: return
        if (state.isSavingGroupAiTasks || state.groupAiTasksSaved) return
        val drafts = com.maodouchat.ai.GroupAiSharePolicy.sanitizeTasks(
            state.groupAiTasks.map {
                com.maodouchat.ai.GroupAiSharePolicy.TaskDraft(
                    title = it.title,
                    owner = it.owner,
                    dueText = it.dueText,
                    dueAt = it.dueAt
                )
            }
        )
        if (drafts.isEmpty()) return

        val sourceQuery = state.groupAiQuestion.trim().take(600)
        val now = System.currentTimeMillis()
        val entities = drafts.map { task ->
            AiTaskEntity(
                id = "ai_task_${UUID.randomUUID()}",
                chatId = targetChatId,
                sourceQuery = sourceQuery,
                title = task.title,
                owner = task.owner,
                dueText = task.dueText,
                dueAt = task.dueAt,
                createdAt = now,
                updatedAt = now
            )
        }

        _uiState.update {
            it.copy(isSavingGroupAiTasks = true, groupAiTaskSaveError = null)
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { aiTaskRepo.saveTasks(entities) }
                _uiState.update {
                    it.copy(
                        isSavingGroupAiTasks = false,
                        groupAiTasksSaved = true,
                        groupAiTaskSaveError = null
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isSavingGroupAiTasks = false) }
                throw error
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingGroupAiTasks = false,
                        groupAiTaskSaveError = text(R.string.ai_tasks_save_failed)
                    )
                }
            }
        }
    }

    fun shareGroupAiAnswer() {
        val state = _uiState.value
        val decision = com.maodouchat.ai.GroupAiSharePolicy.decideShare(
            isGroup = state.chat?.isGroup == true,
            answer = state.groupAiAnswer,
            alreadyShared = state.groupAiAnswerShared
        )
        if (!decision.allowed) return
        // Fail-closed: mark shared before send so double-tap cannot enqueue twice.
        _uiState.update { it.copy(groupAiAnswerShared = true) }
        val meta = com.maodouchat.data.model.MessageMeta(
            aiAssisted = com.maodouchat.ai.GroupAiSharePolicy.shareAiAssistedFlag(),
            aiAssistantMode = com.maodouchat.ai.GroupAiSharePolicy.shareAssistantMode(state.groupAiMode)
        )
        // Always send as the current user with AI-assisted meta — never a system identity.
        sendGroupTextMessage(decision.body, meta)
        clearGroupAiAnswer()
    }

    fun clearUnreadAiSummary() {
        _uiState.update { it.copy(unreadAiSummary = null, unreadAiSummaryCount = 0) }
    }

    fun openUnreadAiSummary() {
        val summary = _uiState.value.unreadAiSummary ?: return
        _uiState.update {
            it.copy(
                aiSummary = summary,
                aiSummaryScope = AiSummaryScope.UNREAD,
                aiSummaryMessageCount = it.unreadAiSummaryCount
            )
        }
    }

    fun setAiEnabledForChat(enabled: Boolean) {
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(
                    isUpdatingAiSetting = false,
                    groupEncryptionWarning = text(R.string.error_session_expired)
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingAiSetting = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(isUpdatingAiSetting = false, groupEncryptionWarning = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.updateAiSettings(liveToken, activeChatId, enabled).fold(
                    onSuccess = { settings ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                aiEnabled = settings.effectiveEnabled,
                                aiSuggestions = if (settings.effectiveEnabled) it.aiSuggestions else emptyList(),
                                isUpdatingAiSetting = false,
                                groupEncryptionWarning = if (settings.effectiveEnabled) {
                                    text(R.string.chat_ai_enabled_status)
                                } else {
                                    text(R.string.chat_ai_disabled_status)
                                }
                            )
                        }
                        if (settings.effectiveEnabled) {
                            maybeGenerateUnreadSummary(_uiState.value.messages)
                        } else {
                            discardAiDraftPreview()
                            cancelAiReplyStream(clearSuggestions = true)
                            _uiState.value.aiOperations
                                .filter { it.state in setOf(AiOperationState.QUEUED, AiOperationState.RUNNING) }
                                .forEach { cancelAiOperation(it.id) }
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isUpdatingAiSetting = false, groupEncryptionWarning = error.message ?: text(R.string.chat_ai_setting_failed))
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdatingAiSetting = false) }
                throw error
            }
        }
    }

    /**
     * 发送拍一拍
     * - 通过 WebSocket 发送 NUDGE 消息
     * - 服务端会向聊天中的其他参与者推送
     */
    fun sendNudge() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.NUDGE)) {
            _uiState.update { it.copy(errorMessage = text(R.string.nudge_disabled)) }
            return
        }
        if (activeChatId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ws_send_failed)) }
            return
        }
        val nudgeOwnerUserId = currentUserId
        if (token.isBlank() || nudgeOwnerUserId.isBlank() || nudgeOwnerUserId == "me") {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        // Logout/account switch: never emit NUDGE on a socket still draining the previous session.
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = nudgeOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        val contactName = _uiState.value.contact.name.ifBlank { text(R.string.chat_other_person) }
        if (!WebSocketClient.sendNudge(activeChatId, contactName)) {
            _uiState.update {
                it.copy(
                    groupEncryptionWarning = if (WebSocketClient.isConnected()) {
                        text(R.string.chat_ws_send_failed)
                    } else {
                        text(R.string.chat_ws_connection_failed)
                    }
                )
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
        val failedMsg = _uiState.value.messages.find { it.id == messageId && it.senderId == currentUserId && it.status == MessageStatus.FAILED } ?: return
        val retryOwnerUserId = currentUserId
        if (token.isBlank() || retryOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (failedMsg.type in RELIABLE_ATTACHMENT_TYPES) {
            sendEncryptedAttachment(Uri.parse(failedMsg.parsedContent()), failedMsg.type, messageId, failedMsg)
            return
        }
        val sendingMsg = failedMsg.copy(status = MessageStatus.SENDING)
        _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) sendingMsg else m }) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { messageRepo.insertMessage(sendingMsg) }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = retryOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("retry_session_changed")
                }
                val (effectiveChatId, viaRest) = withContext(Dispatchers.IO) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = retryOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        throw kotlinx.coroutines.CancellationException("retry_session_changed")
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    val effectiveChatId = resolveOutgoingChatId().getOrThrow()
                    when (failedMsg.type) {
                        MessageType.TEXT, MessageType.MARKDOWN -> {
                            val text = failedMsg.content
                            val groupEpoch = if (_uiState.value.chat?.isGroup == true) {
                                requireGroupEpoch(effectiveChatId).also { ensureGroupSenderKeyDistributed(effectiveChatId, it) }
                            } else null
                            val wireContent = if (groupEpoch != null) {
                                signalProtocol.encryptGroupTextEnvelope(effectiveChatId, text, failedMsg.type.name, groupEpoch).getOrThrow()
                            } else {
                                signalProtocol.encryptSyncedContentEnvelope(liveToken, _uiState.value.contact.id, text, failedMsg.type.name).getOrThrow()
                            }
                            val textViaRest = deliverOutgoing(
                                message = failedMsg.copy(content = wireContent, chatId = effectiveChatId),
                                wireContent = wireContent,
                                typeName = failedMsg.type.name,
                                messageId = messageId,
                                chatId = effectiveChatId
                            )
                            if (groupEpoch != null) markGroupSenderKeyMessageSent(effectiveChatId, groupEpoch)
                            effectiveChatId to textViaRest
                        }
                        MessageType.STICKER, MessageType.LOCATION -> {
                            val groupEpoch = if (_uiState.value.chat?.isGroup == true) {
                                requireGroupEpoch(effectiveChatId).also { ensureGroupSenderKeyDistributed(effectiveChatId, it) }
                            } else null
                            val wireContent = if (groupEpoch != null) {
                                signalProtocol.encryptGroupContentEnvelope(effectiveChatId, failedMsg.content, failedMsg.type.name, groupEpoch).getOrThrow()
                            } else {
                                signalProtocol.encryptSyncedContentEnvelope(liveToken, _uiState.value.contact.id, failedMsg.content, failedMsg.type.name).getOrThrow()
                            }
                            val inlineViaRest = deliverOutgoing(
                                message = failedMsg.copy(content = wireContent, chatId = effectiveChatId),
                                wireContent = wireContent,
                                typeName = failedMsg.type.name,
                                messageId = messageId,
                                chatId = effectiveChatId
                            )
                            if (groupEpoch != null) markGroupSenderKeyMessageSent(effectiveChatId, groupEpoch)
                            effectiveChatId to inlineViaRest
                        }
                        MessageType.IMAGE, MessageType.GIF, MessageType.VIDEO, MessageType.VOICE, MessageType.FILE ->
                            error("Attachment retries use the encrypted object pipeline")
                        else -> throw IllegalStateException(text(R.string.chat_retry_type_unsupported))
                    }
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = retryOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("retry_session_changed")
                }
                val status = if (viaRest) MessageStatus.SENT else MessageStatus.SENDING
                val sent = failedMsg.copy(chatId = effectiveChatId, status = status)
                _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) sent else m }) }
                withContext(Dispatchers.IO) {
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = retryOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        messageRepo.insertMessage(sent)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Leave SENDING so outbox/flush can continue after leave; do not mark FAILED on cancel.
                throw error
            } catch (error: Exception) {
                // Weak-net/5xx stay SENDING for TextOutboxFlusher; only definitive rejects → FAILED.
                if (shouldMarkOutboxFailed(error)) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = retryOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@launch
                    }
                    val failed = failedMsg.copy(status = MessageStatus.FAILED)
                    _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) failed else m }) }
                    withContext(Dispatchers.IO) {
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = retryOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            messageRepo.insertMessage(failed)
                        }
                    }
                } else {
                    Log.w("ChatDetailViewModel", "retrySend transient failure, keep SENDING: " + (error.message ?: "unknown"))
                }
            }
        }
    }

    fun pauseFileTransfer(messageId: String) {
        if (_uiState.value.fileTransferStates[messageId] !in setOf(AttachmentTransferState.QUEUED, AttachmentTransferState.UPLOADING)) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!AttachmentTransferCoordinator.pause(getApplication(), messageId)) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_file_transfer_control_failed)) }
            }
        }
    }

    fun resumeFileTransfer(messageId: String) {
        val message = _uiState.value.messages.firstOrNull {
            it.id == messageId && it.type in RELIABLE_ATTACHMENT_TYPES
        } ?: return
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) it.copy(status = MessageStatus.SENDING) else it },
                fileTransferErrors = state.fileTransferErrors - messageId,
                groupEncryptionWarning = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            messageRepo.updateMessageStatus(messageId, MessageStatus.SENDING)
            if (!AttachmentTransferCoordinator.resume(getApplication(), messageId)) {
                messageRepo.updateMessageStatus(messageId, MessageStatus.FAILED)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == message.id) it.copy(status = MessageStatus.FAILED) else it },
                        groupEncryptionWarning = text(R.string.chat_file_transfer_source_missing)
                    )
                }
            }
        }
    }

    fun cancelFileTransfer(messageId: String) {
        if (messageId in _uiState.value.preparingAttachmentMessageIds) {
            val sourceUri = _uiState.value.messages.firstOrNull { it.id == messageId }?.parsedContent()
            attachmentPreparationJobs[messageId]?.cancel()
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.filterNot { it.id == messageId },
                    fileTransferProgress = state.fileTransferProgress - messageId,
                    preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - messageId,
                    isSending = false
                )
            }
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                AttachmentTransferCoordinator.cancel(getApplication(), messageId)
                sourceUri?.let { MediaCache.releasePersistableReadPermission(getApplication(), it) }
                MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
                messageRepo.deleteMessage(messageId)
            }
            return
        }
        val transferState = _uiState.value.fileTransferStates[messageId] ?: return
        if (transferState in setOf(AttachmentTransferState.READY, AttachmentTransferState.SENDING)) return
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == messageId },
                fileTransferProgress = state.fileTransferProgress - messageId,
                fileTransferStates = state.fileTransferStates - messageId,
                fileTransferErrors = state.fileTransferErrors - messageId
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            AttachmentTransferCoordinator.cancel(getApplication(), messageId)
            MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
            messageRepo.deleteMessage(messageId)
        }
    }

    /**
     * 一键重试当前聊天所有失败/暂停任务；状态栏展示的中转数减为 0 后会自动收起浮窗。
     */
    fun retryAllAttachmentTransfers() {
        val chatId = activeChatId
        if (chatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            AttachmentTransferSummaryRepository.retryAll(app, chatId)
        }
    }

    /**
     * 一键清掉当前聊天的所有传输（已上传 READY 的不会被清掉）。
     */
    fun cancelAllAttachmentTransfers() {
        val chatId = activeChatId
        if (chatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            AttachmentTransferSummaryRepository.cancelAll(app, chatId)
        }
    }

    /**
     * 删除自己发送的消息（服务端 + 本地 + UI）
     * - 调用服务端 DELETE /api/messages/{messageId}
     * - 删除本地缓存
     * - 从 UI 移除
     */
    /** 0.83：清空本机聊天记录（不影响对方设备/服务端）。 */
    fun clearLocalChatHistory() {
        val targetChatId = activeChatId.ifBlank { chatId }
        if (targetChatId.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                messageRepo.deleteMessagesByChatId(targetChatId)
            }
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    navigationTargetMessageId = null,
                    unreadAiSummary = null
                )
            }
        }
    }

    fun deleteMessage(messageId: String) {
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
            val original = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return@launch
            val ticket = messageMutationTracker.begin(messageId, MessageMutationKind.DELETE) ?: return@launch
            // 先从 UI 移除，给用户即时反馈
            _uiState.update { state ->
                state.copy(messages = state.messages.filterNot { it.id == messageId })
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (messageMutationTracker.shouldRollback(ticket)) {
                        _uiState.update { state ->
                            state.copy(
                                messages = mergeMessages(state.messages, listOf(original)),
                                groupEncryptionWarning = text(R.string.error_session_expired)
                            )
                        }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = withContext(Dispatchers.IO) { ApiService.deleteMessage(liveToken, messageId) }
                if (result.isFailure) {
                    val err = result.exceptionOrNull()
                    // 404 = 服务端已无此消息（多设备/竞态），按成功处理，禁止回滚复活幽灵消息
                    val alreadyGone = isAlreadyTerminalMutation(err)
                    // 传输层超时/断网/5xx：服务端可能已删成功，乐观删除不回滚；靠 WS / 重进同步收敛
                    val ambiguousTransport = isAmbiguousTransportFailure(err)
                    if (alreadyGone || ambiguousTransport) {
                        messageMutationTracker.complete(ticket)
                        messageMutationTracker.observeAuthoritative(messageId, MessageMutationKind.DELETE)
                        withContext(Dispatchers.IO) {
                            messageTerminalStore.persistDeleted(messageId)
                            cleanupAttachmentForMessage(messageId)
                        }
                        _uiState.update { state ->
                            state.copy(pinnedMessages = state.pinnedMessages.filterNot { it.messageId == messageId })
                        }
                        com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(original.chatId.ifBlank { activeChatId })
                        if (ambiguousTransport && !alreadyGone) {
                            Log.w("ChatDetailViewModel", "deleteMessage transport ambiguous, keep deleted: " + (err?.message ?: "unknown"))
                        }
                        return@launch
                    }
                    if (!messageMutationTracker.shouldRollback(ticket)) return@launch
                    // 明确业务失败时回滚 UI：把被删的消息按原状重新插入
                    Log.w("ChatDetailViewModel", "deleteMessage failed: " + (err?.message ?: "unknown"))
                    _uiState.update { state ->
                        state.copy(
                            messages = mergeMessages(state.messages, listOf(original)),
                            groupEncryptionWarning = text(R.string.chat_delete_server_failed)
                        )
                    }
                    return@launch
                }
                messageMutationTracker.complete(ticket)
                messageMutationTracker.observeAuthoritative(messageId, MessageMutationKind.DELETE)
                withContext(Dispatchers.IO) {
                    messageTerminalStore.persistDeleted(messageId)
                    // 清理附件：删除传输记录和本地密文文件，防止孤儿行和磁盘泄漏
                    cleanupAttachmentForMessage(messageId)
                }
                _uiState.update { state ->
                    state.copy(pinnedMessages = state.pinnedMessages.filterNot { it.messageId == messageId })
                }
                com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(original.chatId.ifBlank { activeChatId })
            } catch (error: kotlinx.coroutines.CancellationException) {
                // 取消且未确认：清 pending ticket + 恢复消息，避免 shouldDrop 永久挡同步
                if (messageMutationTracker.shouldRollback(ticket)) {
                    _uiState.update { state ->
                        state.copy(messages = mergeMessages(state.messages, listOf(original)))
                    }
                }
                throw error
            }
        }
    }

    fun revokeMessage(messageId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_REVOKE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val revokeOwnerUserId = currentUserId
        if (token.isBlank() || revokeOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            val original = _uiState.value.messages.find { it.id == messageId } ?: return@launch
            val ticket = messageMutationTracker.begin(messageId, MessageMutationKind.REVOKE) ?: return@launch
            val revoked = original.toRevokedPlaceholder(text(R.string.chat_message_revoked_placeholder))
            _uiState.update { it.copy(messages = it.messages.map { m -> if (m.id == messageId) revoked else m }) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revokeOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (messageMutationTracker.shouldRollback(ticket)) {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { if (it.id == messageId) original else it },
                                groupEncryptionWarning = text(R.string.error_session_expired)
                            )
                        }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = withContext(Dispatchers.IO) { ApiService.revokeMessage(liveToken, messageId) }
                if (result.isFailure) {
                    val err = result.exceptionOrNull()
                    // 404 / 已撤回：服务端已是终态，保持撤回
                    val alreadyRevoked = isAlreadyTerminalMutation(err)
                    // 传输层超时/断网/5xx：服务端可能已 Applied，乐观撤回不回滚（与 delete 对称）
                    val ambiguousTransport = isAmbiguousTransportFailure(err)
                    if (alreadyRevoked || ambiguousTransport) {
                        messageMutationTracker.complete(ticket)
                        messageMutationTracker.observeAuthoritative(messageId, MessageMutationKind.REVOKE)
                        withContext(Dispatchers.IO) {
                            messageTerminalStore.persistRevoked(messageId, revoked)
                            cleanupAttachmentForMessage(messageId)
                        }
                        _uiState.update { state ->
                            state.copy(pinnedMessages = state.pinnedMessages.filterNot { it.messageId == messageId })
                        }
                        com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(original.chatId.ifBlank { activeChatId })
                        if (ambiguousTransport && !alreadyRevoked) {
                            Log.w("ChatDetailViewModel", "revokeMessage transport ambiguous, keep revoked: " + (err?.message ?: "unknown"))
                        }
                        return@launch
                    }
                    if (!messageMutationTracker.shouldRollback(ticket)) return@launch
                    Log.w("ChatDetailViewModel", "revokeMessage failed: " + (err?.message ?: "unknown"))
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { if (it.id == messageId) original else it },
                            groupEncryptionWarning = text(R.string.chat_revoke_failed)
                        )
                    }
                    return@launch
                }
                messageMutationTracker.complete(ticket)
                messageMutationTracker.observeAuthoritative(messageId, MessageMutationKind.REVOKE)
                withContext(Dispatchers.IO) {
                    messageTerminalStore.persistRevoked(messageId, revoked)
                    cleanupAttachmentForMessage(messageId)
                }
                _uiState.update { state ->
                    state.copy(pinnedMessages = state.pinnedMessages.filterNot { it.messageId == messageId })
                }
                com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(original.chatId.ifBlank { activeChatId })
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (messageMutationTracker.shouldRollback(ticket)) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map { if (it.id == messageId) original else it })
                    }
                }
                throw error
            }
        }
    }

    fun editTextMessage(messageId: String, newText: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_EDIT)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val trimmed = newText.trim()
        val original = _uiState.value.messages.find { it.id == messageId } ?: return
        val editOwnerUserId = currentUserId
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_edit_empty)) }
            return
        }
        // 明确分组避免 &&/|| 混用被后续修改误读：非本人 或 非文本类 或 超过 5 分钟编辑窗口
        if (
            original.senderId != editOwnerUserId ||
            (original.type != MessageType.TEXT && original.type != MessageType.MARKDOWN) ||
            System.currentTimeMillis() - original.timestamp >= 300_000
        ) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_edit_not_allowed)) }
            return
        }
        if (token.isBlank() || editOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        val ticket = messageMutationTracker.begin(messageId, MessageMutationKind.EDIT) ?: return
        viewModelScope.launch {
            val meta = original.parsedMeta().copy(aiAssisted = false, aiAssistantMode = null)
            val optimistic = original.toOptimisticEdit(composeContentWithMeta(trimmed, meta))
            _uiState.update { state ->
                state.copy(messages = state.messages.map { if (it.id == messageId) optimistic else it }, groupEncryptionWarning = null)
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = editOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (messageMutationTracker.shouldRollback(ticket)) {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { if (it.id == messageId) original else it },
                                groupEncryptionWarning = text(R.string.error_session_expired)
                            )
                        }
                    }
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = editOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            throw kotlinx.coroutines.CancellationException("edit_session_changed")
                        }
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        val wireContent = if (_uiState.value.chat?.isGroup == true) {
                            val epoch = requireGroupEpoch(original.chatId)
                            ensureGroupSenderKeyDistributed(original.chatId, epoch)
                            signalProtocol.encryptGroupTextEnvelope(original.chatId, optimistic.content, original.type.name, epoch).getOrThrow()
                        } else {
                            signalProtocol.encryptSyncedContentEnvelope(liveToken, _uiState.value.contact.id, optimistic.content, original.type.name).getOrNull()
                                ?: throw IllegalStateException(text(R.string.chat_encryption_failed))
                        }
                        ApiService.editMessage(liveToken, original.chatId, messageId, wireContent).getOrThrow()
                        Result.success(Unit)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                if (result.isSuccess) {
                    messageMutationTracker.complete(ticket)
                    withContext(Dispatchers.IO) {
                        messageRepo.insertMessage(optimistic)
                        indexSearchableMessage(optimistic)
                    }
                    // Head edit must refresh list preview (plaintext body may be list tail).
                    com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(original.chatId.ifBlank { activeChatId })
                } else {
                    val err = result.exceptionOrNull()
                    // 与 delete/revoke 对称：传输层模糊时保留乐观编辑，靠 mutation 同步收敛
                    if (isAmbiguousTransportFailure(err)) {
                        messageMutationTracker.complete(ticket)
                        withContext(Dispatchers.IO) {
                            messageRepo.insertMessage(optimistic)
                            indexSearchableMessage(optimistic)
                        }
                        com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(original.chatId.ifBlank { activeChatId })
                        Log.w("ChatDetailViewModel", "editTextMessage transport ambiguous, keep edit: " + (err?.message ?: "unknown"))
                        return@launch
                    }
                    if (!messageMutationTracker.shouldRollback(ticket)) return@launch
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { if (it.id == messageId) original else it },
                            groupEncryptionWarning = err?.message ?: text(R.string.chat_edit_failed)
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                // 取消且未确认成功：回滚 UI 乐观编辑；tracker 不 complete，避免误吞后续 WS
                if (messageMutationTracker.shouldRollback(ticket)) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map { if (it.id == messageId) original else it })
                    }
                }
                throw error
            }
        }
    }

    fun toggleStarMessage(messageId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_STARRING)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.message_starring_disabled)) }
            return
        }
        // Surface #70: 密聊 star 门控 - 防止通过 star 留存密聊内容索引
        if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_STAR_BLOCK)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_star_blocked)) }
            return
        }

        val original = _uiState.value.messages.find { it.id == messageId } ?: return
        val starOwnerUserId = currentUserId
        if (token.isBlank() || starOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        // Optimistic star/unstar so list + bubble flip immediately; cancel/failure roll back.
        val optimistic = original.copy(starred = !original.starred)
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == messageId) optimistic else it })
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = starOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { if (it.id == messageId) original else it },
                            groupEncryptionWarning = text(R.string.error_session_expired)
                        )
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.toggleStarMessage(liveToken, messageId).fold(
                    onSuccess = { response ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = starOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val updated = original.copy(starred = response.starred)
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { if (it.id == messageId) updated else it },
                                groupEncryptionWarning = if (response.starred) text(R.string.chat_starred_status) else text(R.string.chat_unstarred_status)
                            )
                        }
                        withContext(Dispatchers.IO) { messageRepo.insertMessage(updated) }
                    },
                    onFailure = { error ->
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { if (it.id == messageId) original else it },
                                groupEncryptionWarning = error.message ?: text(R.string.chat_star_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { state ->
                    state.copy(messages = state.messages.map { if (it.id == messageId) original else it })
                }
                throw error
            }
        }
    }

    private val reactionMutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    fun setMessageReaction(messageId: String, emoji: String) {
        if (!requireReactions()) return
        val reactionUserId = currentUserId
        if (token.isBlank() || reactionUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        val mutex = reactionMutexes.computeIfAbsent(messageId) { kotlinx.coroutines.sync.Mutex() }
        viewModelScope.launch {
            // 按 messageId 串行化，避免快速双击/加取消竞态导致 UI 与服务端反应状态不一致
            mutex.withLock {
                val original = _uiState.value.messages.find { it.id == messageId } ?: return@withLock
                val currentReactions = original.reactions
                val alreadyReacted = currentReactions.any { it.userId == reactionUserId && it.emoji == emoji }
                val optimisticReactions = if (alreadyReacted) {
                    currentReactions.filterNot { it.userId == reactionUserId && it.emoji == emoji }
                } else {
                    currentReactions.filterNot { it.userId == reactionUserId } + MessageReaction(reactionUserId, emoji)
                }
                updateMessageReactions(messageId, optimisticReactions, persist = false)
                try {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = reactionUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        updateMessageReactions(messageId, original.reactions, persist = false)
                        return@withLock
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    ApiService.setMessageReaction(liveToken, messageId, emoji).fold(
                        onSuccess = { response ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = reactionUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@fold
                            }
                            updateMessageReactions(messageId, response.reactions)
                        },
                        onFailure = { error ->
                            updateMessageReactions(messageId, original.reactions)
                            _uiState.update { it.copy(groupEncryptionWarning = error.message ?: text(R.string.chat_reaction_failed)) }
                        }
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    // 取消时回滚乐观反应，避免 UI 与服务端不一致
                    updateMessageReactions(messageId, original.reactions, persist = false)
                    throw error
                }
            }
        }
    }

    private fun updateMessageReactions(messageId: String, reactions: List<MessageReaction>, persist: Boolean = true) {
        val updated = _uiState.value.messages.firstOrNull { it.id == messageId }?.copy(reactions = reactions) ?: return
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == messageId) updated else it })
        }
        if (persist) viewModelScope.launch(Dispatchers.IO) { messageRepo.insertMessage(updated) }
    }

    /**
     * If a MESSAGE_REACTION_UPDATED was buffered before [message] landed, attach its
     * reactions snapshot and drop the buffer entry.
     */
    private fun mergePendingReactionsOnto(message: Message): Message {
        val result = com.maodouchat.ui.screen.chatlist.PendingReactionPolicy.takeForMessage(
            pending = pendingReactions,
            chatId = message.chatId,
            messageId = message.id,
            nowMs = System.currentTimeMillis()
        )
        pendingReactions = result.pending
        val entry = result.ready.firstOrNull() ?: return message
        if (message.type == MessageType.REVOKED) return message
        return message.copy(reactions = entry.reactions)
    }

    fun loadReadReceipts(messageId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.READ_RECEIPTS)) {
            _uiState.update { it.copy(isLoadingReadReceipts = false, readReceipts = emptyList()) }
            return
        }

        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoadingReadReceipts = false,
                    readReceipts = emptyList(),
                    groupEncryptionWarning = text(R.string.error_session_expired)
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingReadReceipts = true, readReceipts = emptyList()) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isLoadingReadReceipts = false,
                            readReceipts = emptyList(),
                            groupEncryptionWarning = text(R.string.error_session_expired)
                        )
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.getMessageReadReceipts(liveToken, messageId).fold(
                    onSuccess = { receipts ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val readAtByUser = receipts.associate { it.userId to it.readAt }
                        val members = _uiState.value.chat?.participants.orEmpty()
                        val rows = if (members.isNotEmpty()) {
                            members
                                .filter { it.id != currentUserId }
                                .map { user ->
                                    ReadReceiptUi(
                                        userId = user.id,
                                        name = user.displayName,
                                        avatar = user.avatar,
                                        readAt = readAtByUser[user.id],
                                        isOnline = user.isOnline
                                    )
                                }
                        } else {
                            receipts.map { receipt ->
                                ReadReceiptUi(userId = receipt.userId, name = receipt.userId, readAt = receipt.readAt)
                            }
                        }
                        _uiState.update { it.copy(isLoadingReadReceipts = false, readReceipts = rows) }
                        _uiState.update {
                            it.copy(groupReadCounts = it.groupReadCounts + (messageId to ReadCountUi(rows.count { r -> r.readAt != null }, rows.size)))
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoadingReadReceipts = false,
                                readReceipts = emptyList(),
                                groupEncryptionWarning = error.message ?: text(R.string.chat_read_details_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoadingReadReceipts = false) }
                throw error
            }
        }
    }

    fun clearReadReceipts() {
        _uiState.update { it.copy(readReceipts = emptyList(), isLoadingReadReceipts = false) }
    }

    /** 1.163：群聊加载后，自动拉取最后一条自己已送达消息的已读人数（仅单次轻量请求）。 */
    private fun maybeAutoLoadLastGroupReadCount() {
        if (!_uiState.value.chatIsGroup) return
        // 1.167：只保留最近 20 条消息的已读缓存，防止无限增长
        val recentIds = _uiState.value.messages.takeLast(20).map { it.id }.toSet()
        _uiState.update {
            val pruned = it.groupReadCounts.filterKeys { key -> key in recentIds }
            if (pruned.size == it.groupReadCounts.size) it else it.copy(groupReadCounts = pruned)
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.READ_RECEIPTS)) return
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank() || token.isBlank()) return
        val lastOutgoing = _uiState.value.messages.lastOrNull {
            it.senderId == ownerUserId &&
                it.type != com.maodouchat.data.model.MessageType.SK_DIST &&
                it.status != MessageStatus.SENDING &&
                it.status != MessageStatus.FAILED
        } ?: return
        loadGroupReadCount(lastOutgoing.id)
    }

    /** 1.163：拉取指定群消息已读人数并缓存（不打开阅读详情弹窗）。 */
    fun loadGroupReadCount(messageId: String) {
        val ownerUserId = currentUserId
        if (messageId.isBlank() || ownerUserId.isBlank() || token.isBlank()) return
        if (_uiState.value.groupReadCounts.containsKey(messageId)) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.getMessageReadReceipts(liveToken, messageId).fold(
                onSuccess = { receipts ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    val members = _uiState.value.chat?.participants.orEmpty()
                    val total = if (members.isNotEmpty()) members.count { it.id != ownerUserId } else receipts.size
                    val read = receipts.count { it.readAt != null }
                    _uiState.update {
                        it.copy(groupReadCounts = it.groupReadCounts + (messageId to ReadCountUi(read, total)))
                    }
                },
                onFailure = { /* 失败静默，点击状态图标仍可走完整阅读详情 */ }
            )
        }
    }

    private fun refreshBlockState(contactId: String = _uiState.value.contact.id) {
        val ownerUserId = currentUserId
        if (contactId.isBlank() || token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.getBlockedUsers(liveToken).onSuccess { blocked ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onSuccess
                }
                _uiState.update { it.copy(isContactBlocked = contactId in blocked) }
            }
        }
    }

    fun blockContact() {
        val contactId = _uiState.value.contact.id
        val ownerUserId = currentUserId
        if (contactId.isBlank() || contactId == ownerUserId) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.BLOCK_REPORT)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isBlockingContact) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBlockingContact = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isBlockingContact = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.blockUser(liveToken, contactId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { it.copy(isContactBlocked = true, isBlockingContact = false, groupEncryptionWarning = text(R.string.chat_blocked_user_status, it.contact.displayName)) }
                    },
                    onFailure = { error -> _uiState.update { it.copy(isBlockingContact = false, groupEncryptionWarning = error.message ?: text(R.string.chat_block_failed)) } }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isBlockingContact = false) }
                throw error
            }
        }
    }

    fun unblockContact() {
        val contactId = _uiState.value.contact.id
        val ownerUserId = currentUserId
        if (contactId.isBlank()) return
        // Unblock remains available even when block/report feature is toggled off.
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isBlockingContact) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBlockingContact = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isBlockingContact = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.unblockUser(liveToken, contactId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { it.copy(isContactBlocked = false, isBlockingContact = false, groupEncryptionWarning = text(R.string.chat_unblocked_user_status, it.contact.displayName)) }
                    },
                    onFailure = { error -> _uiState.update { it.copy(isBlockingContact = false, groupEncryptionWarning = error.message ?: text(R.string.chat_unblock_failed)) } }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isBlockingContact = false) }
                throw error
            }
        }
    }

    fun reportContact(reason: String, description: String? = null) {
        val contactId = _uiState.value.contact.id
        val ownerUserId = currentUserId
        if (contactId.isBlank() || contactId == ownerUserId) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.BLOCK_REPORT)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.createReport(
                token = liveToken,
                targetType = "USER",
                targetId = contactId,
                chatId = activeChatId.takeIf { it.isNotBlank() },
                reason = reason,
                description = description
            ).fold(
                onSuccess = {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_report_submitted)) }
                },
                onFailure = { error -> _uiState.update { it.copy(groupEncryptionWarning = error.message ?: text(R.string.chat_report_failed)) } }
            )
        }
    }

    fun reportMessage(messageId: String, reason: String, description: String? = null) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.BLOCK_REPORT)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val ownerUserId = currentUserId
        if (messageId.isBlank()) return
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isReporting) return
        _uiState.update { it.copy(isReporting = true) }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.createReport(
                token = liveToken,
                targetType = "MESSAGE",
                targetId = messageId,
                chatId = activeChatId.takeIf { it.isNotBlank() },
                messageId = messageId,
                reason = reason,
                description = description
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isReporting = false) }
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_report_submitted)) }
                },
                onFailure = { error -> _uiState.update { it.copy(groupEncryptionWarning = error.message ?: text(R.string.chat_report_failed), isReporting = false) } }
            )
        }
    }

    fun loadGroupCandidates() {
        val chat = _uiState.value.chat ?: return
        val ownerUserId = currentUserId
        if (!chat.isGroup) return
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.getUsers(liveToken).fold(
                onSuccess = { users ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    val existingIds = _uiState.value.chat?.participants.orEmpty().map { it.id }.toSet()
                    val candidates = users
                        .filter { it.id !in existingIds && it.id != currentUserId }
                        .map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen) }
                    _uiState.update { it.copy(groupCandidates = candidates) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            groupEncryptionWarning = error.message ?: text(R.string.contacts_load_failed)
                        )
                    }
                }
            )
        }
    }

    fun renameGroup(newName: String) {
        if (_uiState.value.isUpdatingGroup) return
        val chat = _uiState.value.chat ?: return
        val ownerUserId = currentUserId
        val trimmed = newName.trim()
        if (!chat.isGroup) return
        if (trimmed.isBlank() || trimmed.length > 50) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_name_length)) }
            return
        }
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingGroup = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.renameGroup(liveToken, chat.id, trimmed).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val updated = chat.copy(groupName = trimmed)
                        _uiState.update { it.copy(chat = updated, contact = it.contact.copy(name = trimmed), isUpdatingGroup = false, groupEncryptionWarning = text(R.string.chat_group_name_updated)) }
                    },
                    onFailure = { error -> _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = error.message ?: text(R.string.chat_group_name_failed)) } }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdatingGroup = false) }
                throw error
            }
        }
    }

    fun addGroupMember(userId: String) {
        if (_uiState.value.isUpdatingGroup) return
        val chat = _uiState.value.chat ?: return
        val ownerUserId = currentUserId
        if (!chat.isGroup || userId.isBlank()) return
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingGroup = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.addGroupMembers(liveToken, chat.id, listOf(userId)).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        invalidateGroupSenderKey(chat.id)
                        refreshChatAfterGroupChange(text(R.string.chat_group_member_added_key))
                    },
                    onFailure = { error -> _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = error.message ?: text(R.string.chat_group_add_failed)) } }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdatingGroup = false) }
                throw error
            }
        }
    }

    fun removeGroupMember(userId: String) {
        if (_uiState.value.isUpdatingGroup) return
        val chat = _uiState.value.chat ?: return
        val ownerUserId = currentUserId
        if (!chat.isGroup || userId.isBlank() || userId == ownerUserId) return
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingGroup = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.removeGroupMember(liveToken, chat.id, userId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        invalidateGroupSenderKey(chat.id)
                        refreshChatAfterGroupChange(text(R.string.chat_group_member_removed_key))
                    },
                    onFailure = { error -> _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = error.message ?: text(R.string.chat_group_remove_failed)) } }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdatingGroup = false) }
                throw error
            }
        }
    }

    private suspend fun invalidateGroupSenderKey(groupId: String) {
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank()) return
        withContext(Dispatchers.IO) {
            signalProtocol.invalidateGroupSenderKey(groupId)
            // 待发附件密文可能仍绑旧 epoch，强制重加密并重新调度 READY
            try {
                val dao = app.database.attachmentTransferDao()
                dao.clearWireContentForChat(groupId, ownerUserId = ownerUserId)
                dao.getByChat(groupId, ownerUserId = ownerUserId)
                    .filter { it.state == AttachmentTransferState.READY && it.hasCompletedUpload() }
                    .forEach {
                        com.maodouchat.attachment.AttachmentTransferScheduler.schedule(
                            app,
                            it.messageId,
                            it.ownerUserId,
                            replace = true
                        )
                    }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ChatDetailViewModel", "clear attachment wire after SK invalidate failed for $groupId", error)
            }
        }
    }

    private suspend fun refreshChatAfterGroupChange(successMessage: String) {
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        ApiService.getChats(liveToken).fold(
            onSuccess = { dtos ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@fold
                }
                val updated = dtos.firstOrNull { it.id == activeChatId }?.toDomainChat()
                if (updated != null) {
                    val groupContact = User(
                        id = updated.id,
                        name = updated.groupName ?: text(R.string.chat_group),
                        avatar = updated.groupAvatar,
                        status = text(R.string.chat_members_count, updated.participants.size)
                    )
                    _uiState.update { it.copy(chat = updated, contact = groupContact, isUpdatingGroup = false, groupEncryptionWarning = successMessage) }
                    loadGroupCandidates()
                } else {
                    _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = successMessage) }
                }
            },
            onFailure = { error -> _uiState.update { it.copy(isUpdatingGroup = false, groupEncryptionWarning = error.message ?: text(R.string.chat_group_refresh_failed)) } }
        )
    }

    fun loadForwardTargets() {
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.getChats(liveToken).fold(
                onSuccess = { dtos ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    val chats = dtos.map { dto -> dto.toDomainChat() }
                        .filter { it.id != activeChatId && !it.archived }
                        .sortedWith(
                            compareByDescending<Chat> { it.pinnedAt > 0 }
                                .thenByDescending { it.pinnedAt }
                                .thenByDescending { it.lastMessageTime }
                        )
                    _uiState.update { it.copy(forwardTargets = chats) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            groupEncryptionWarning = error.message
                                ?: text(R.string.chat_refresh_failed_cached)
                        )
                    }
                }
            )
        }
    }

    fun forwardMessage(message: Message, targetChatId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.message_forwarding_disabled)) }
            return
        }
        if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_FORWARD_BLOCK)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_forward_blocked)) }
            return
        }
        // B2 转发白名单（fwlz）：密聊消息只允许转发到白名单会话；空白名单 = 完全禁止
        if (_uiState.value.isSecretChat == true &&
            !com.maodouchat.util.SecretForwardWhitelistPrefs.isForwardAllowed(getApplication(), targetChatId)
        ) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_forward_whitelist_blocked)) }
            return
        }
        val targetChat = _uiState.value.forwardTargets.firstOrNull { it.id == targetChatId } ?: return
        if (message.type in setOf(MessageType.NUDGE, MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED)) return
        val forwardOwnerUserId = currentUserId
        if (token.isBlank() || forwardOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isForwarding) return
        viewModelScope.launch {
            _uiState.update { it.copy(isForwarding = true, groupEncryptionWarning = null) }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = forwardOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { it.copy(isForwarding = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            val forwardResult = try {
                withContext(Dispatchers.IO) {
                    try {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = forwardOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            throw kotlinx.coroutines.CancellationException("forward_session_changed")
                        }
                        val msgId = "m_${UUID.randomUUID()}"
                        if (message.type in RELIABLE_ATTACHMENT_TYPES) {
                            forwardEncryptedAttachment(targetChat, message, msgId)
                        } else {
                            // Durable outbox for text/sticker/location forwards (same ladder as sendMessage).
                            // WS enqueue alone used to leave no local row — process death dropped the forward.
                            val plainContent = when (message.type) {
                                MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION -> message.parsedContent()
                                else -> throw IllegalStateException(text(R.string.chat_forward_type_unsupported))
                            }
                            val timestamp = System.currentTimeMillis()
                            // 0.67：转发来源标记（密聊转发不记录来源名，仅置「已转发」标记以保护隐私）
                            val sourceName = if (_uiState.value.isSecretChat == true) null
                                else _uiState.value.chat?.participants?.firstOrNull { it.id == message.senderId }?.name
                            val local = Message(
                                id = msgId,
                                chatId = targetChat.id,
                                senderId = currentUserId,
                                content = plainContent,
                                type = message.type,
                                timestamp = timestamp,
                                status = MessageStatus.SENDING,
                                meta = message.meta.copy(forwardedFrom = message.meta.forwardedFrom ?: sourceName)
                            )
                            messageRepo.insertMessage(local)
                            indexSearchableMessage(local)
                            if (targetChat.id == activeChatId) {
                                _uiState.update { st ->
                                    st.copy(messages = mergeMessages(st.messages, listOf(local)))
                                }
                            }
                            try {
                                val (wireContent, encryptEpoch) = encryptForwardedContent(targetChat, message, local.parsedMeta().forwardedFrom)
                                val viaRest = deliverOutgoing(
                                    message = local.copy(content = wireContent),
                                    wireContent = wireContent,
                                    typeName = message.type.name,
                                    messageId = msgId,
                                    chatId = targetChat.id
                                )
                                if (encryptEpoch != null) {
                                    markGroupSenderKeyMessageSent(targetChat.id, encryptEpoch)
                                }
                                val converged = local.copy(
                                    status = if (viaRest) MessageStatus.SENT else MessageStatus.SENDING
                                )
                                messageRepo.insertMessage(converged)
                                if (targetChat.id == activeChatId) {
                                    _uiState.update { st ->
                                        st.copy(messages = mergeMessages(st.messages, listOf(converged)))
                                    }
                                }
                                val preview = when (message.type) {
                                    MessageType.IMAGE -> text(R.string.message_preview_image)
                                    MessageType.GIF -> text(R.string.message_preview_gif)
                                    MessageType.STICKER -> text(R.string.message_preview_sticker)
                                    MessageType.LOCATION -> text(R.string.message_preview_location)
                                    MessageType.VIDEO -> text(R.string.message_preview_video)
                                    MessageType.VOICE -> text(R.string.message_preview_voice)
                                    else -> plainContent.take(40)
                                }
                                com.maodouchat.MaodouchatApp.emitMessageSent(targetChat.id, preview, message.type.name)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                // Keep SENDING for TextOutboxFlusher; cancel is not encrypt failure.
                                throw error
                            } catch (error: Throwable) {
                                if (shouldMarkOutboxFailed(error)) {
                                    val failed = local.copy(status = MessageStatus.FAILED)
                                    messageRepo.insertMessage(failed)
                                    if (targetChat.id == activeChatId) {
                                        _uiState.update { st ->
                                            st.copy(messages = mergeMessages(st.messages, listOf(failed)))
                                        }
                                    }
                                }
                                throw error
                            }
                        }
                        Result.success(Unit)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isForwarding = false) }
                throw error
            }
            _uiState.update {
                it.copy(
                    isForwarding = false,
                    groupEncryptionWarning = forwardResult.fold(
                        onSuccess = { text(R.string.chat_forwarded) },
                        onFailure = { error ->
                            if (message.type in RELIABLE_ATTACHMENT_TYPES) attachmentErrorText(error, R.string.chat_attachment_upload_failed)
                            else error.message
                        }
                    )
                )
            }
        }
    }

    /**
     * 转发附带留言：向指定目标会话发送一条普通文本消息（1:1 Signal / 群 Sender Key）。
     * 复用 [encryptForwardedContent] 的双路径加密封装；失败保留 SENDING 交 TextOutboxFlusher 重试。
     */
    fun sendTextToChat(targetChatId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) return
        val targetChat = _uiState.value.forwardTargets.firstOrNull { it.id == targetChatId } ?: return
        val noteOwnerUserId = currentUserId
        if (token.isBlank() || noteOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = noteOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val msgId = "m_${UUID.randomUUID()}"
            val looksMd = com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(trimmed)
            val type = if (looksMd) MessageType.MARKDOWN else MessageType.TEXT
            val timestamp = System.currentTimeMillis()
            val local = Message(
                id = msgId,
                chatId = targetChat.id,
                senderId = currentUserId,
                content = trimmed,
                type = type,
                timestamp = timestamp,
                status = MessageStatus.SENDING,
                meta = MessageMeta(markdown = looksMd)
            )
            withContext(Dispatchers.IO) {
                messageRepo.insertMessage(local)
                indexSearchableMessage(local)
            }
            if (targetChat.id == activeChatId) {
                _uiState.update { st ->
                    st.copy(messages = mergeMessages(st.messages, listOf(local)))
                }
            }
            try {
                val (wireContent, epoch) = encryptForwardedContent(targetChat, local)
                val viaRest = deliverOutgoing(
                    message = local.copy(content = wireContent),
                    wireContent = wireContent,
                    typeName = type.name,
                    messageId = msgId,
                    chatId = targetChat.id
                )
                if (epoch != null) {
                    markGroupSenderKeyMessageSent(targetChat.id, epoch)
                }
                val converged = local.copy(status = if (viaRest) MessageStatus.SENT else MessageStatus.SENDING)
                withContext(Dispatchers.IO) { messageRepo.insertMessage(converged) }
                if (targetChat.id == activeChatId) {
                    _uiState.update { st ->
                        st.copy(messages = mergeMessages(st.messages, listOf(converged)))
                    }
                }
                com.maodouchat.MaodouchatApp.emitMessageSent(targetChat.id, trimmed.take(40), type.name)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 失败保留 SENDING 待重试，不阻塞转发主流程
                Log.w("ChatDetailViewModel", "forward note send failed", error)
            }
        }
    }

    /** @return wire content paired with encrypt epoch (group only) so mark uses the same revision. */
    private suspend fun encryptForwardedContent(targetChat: Chat, message: Message, forwardedFrom: String?): Pair<String, Long?> {
        val plainContent = when (message.type) {
            MessageType.TEXT, MessageType.MARKDOWN -> message.parsedContent()
            MessageType.STICKER -> message.parsedContent()
            MessageType.LOCATION -> message.parsedContent()
            else -> throw IllegalStateException(text(R.string.chat_forward_type_unsupported))
        }
        // 0.67：转发来源标记随 E2EE 密文传输（meta 标签编码进 content，与 sendMessage 一致）
        val wireContent = if (forwardedFrom != null) {
            message.withEncodedMeta(message.parsedMeta().copy(forwardedFrom = forwardedFrom)).content
        } else {
            plainContent
        }
        return if (targetChat.isGroup) {
            // 转发目标常不是当前会话：优先 live/cache，否则用 API 快照上的 revision（禁止再默认 0）
            val epoch = resolveForwardGroupEpoch(targetChat)
            ensureGroupSenderKeyDistributed(targetChat.id, epoch)
            val wire = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) {
                signalProtocol.encryptGroupTextEnvelope(targetChat.id, wireContent, message.type.name, epoch).getOrThrow()
            } else {
                signalProtocol.encryptGroupContentEnvelope(targetChat.id, wireContent, message.type.name, epoch).getOrThrow()
            }
            wire to epoch
        } else {
            val recipient = targetChat.participants.firstOrNull { it.id != currentUserId }
                ?: throw IllegalStateException(text(R.string.chat_forward_recipient_missing))
            val wire = signalProtocol.encryptSyncedContentEnvelope(token, recipient.id, wireContent, message.type.name).getOrThrow()
            wire to null
        }
    }

    /**
     * Durable attachment forward: same AttachmentTransfer outbox as normal send.
     * Process death after prepareAndEnqueue still uploads/finalizes via WorkManager.
     * (Previously: inline encrypt+upload+send with no local SENDING row.)
     */
    private suspend fun forwardEncryptedAttachment(targetChat: Chat, message: Message, messageId: String) {
        val localMessage = ensureLocalAttachment(message).getOrThrow()
        val localUri = Uri.parse(localMessage.parsedContent())
        val described = MediaCache.describeFile(getApplication(), localUri)
        val existingMeta = localMessage.parsedMeta()
        val metadata = MediaCache.LocalFileMetadata(
            fileName = existingMeta.fileName ?: described.fileName,
            mimeType = existingMeta.fileMimeType ?: described.mimeType,
            sizeBytes = described.sizeBytes.takeIf { it > 0L } ?: existingMeta.fileSizeBytes ?: 0L
        )
        require(metadata.sizeBytes <= MediaCache.MAX_ATTACHMENT_PLAIN_BYTES) {
            text(R.string.chat_attachment_file_too_large)
        }

        val initialMeta = com.maodouchat.data.model.MessageMeta(
            voiceDurationMs = existingMeta.voiceDurationMs,
            fileName = metadata.fileName,
            fileMimeType = metadata.mimeType,
            fileSizeBytes = metadata.sizeBytes.takeIf { it > 0 }
        )
        val optimistic = Message(
            id = messageId,
            chatId = targetChat.id,
            senderId = currentUserId,
            content = composeContentWithMeta(localUri.toString(), initialMeta),
            type = message.type,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            meta = initialMeta
        )
        messageRepo.insertMessage(optimistic)
        if (targetChat.id == activeChatId) {
            _uiState.update { st ->
                st.copy(messages = mergeMessages(st.messages, listOf(optimistic)))
            }
        }

        val preparationLease = AttachmentPreparationLease(
            originalSourceUri = localUri.toString(),
            deleteEncryptedFile = { path -> runCatching { File(path).delete() } },
            deletePreparedSource = { source ->
                MediaCache.deletePreparedAttachmentSource(getApplication(), source)
            },
            releasePersistablePermission = { source ->
                MediaCache.releasePersistableReadPermission(getApplication(), source)
            }
        )
        val preparationOwnerUserId = tokenManager.getUserId().orEmpty()
        if (preparationOwnerUserId.isBlank() || token.isBlank()) {
            throw IllegalStateException(text(R.string.error_session_expired))
        }
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = preparationOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw kotlinx.coroutines.CancellationException("forward_attachment_session_changed")
        }
        val preparationExecutor = AttachmentPreparationExecutor(
            context = getApplication(),
            ownerUserId = tokenManager::getUserId,
            resolveChatId = { Result.success(targetChat.id) },
            onEncryptionProgress = { _, _, _ ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = preparationOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("attachment_encrypt_session_changed")
                }
            }
        )
        try {
            val prepared = preparationExecutor.prepareAndEnqueue(
                request = AttachmentPreparationRequest(
                    messageId = messageId,
                    sourceUri = localUri,
                    type = message.type,
                    initialMetadata = metadata,
                    voiceDurationMs = existingMeta.voiceDurationMs
                ),
                lease = preparationLease
            )
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = preparationOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                throw kotlinx.coroutines.CancellationException("forward_attachment_session_changed")
            }
            val queuedMeta = initialMeta.copy(
                fileName = prepared.fileName,
                fileMimeType = prepared.mimeType,
                fileSizeBytes = prepared.plainSize
            )
            val queued = optimistic.copy(
                chatId = prepared.chatId,
                content = composeContentWithMeta(prepared.sourceUri, queuedMeta),
                meta = queuedMeta,
                status = MessageStatus.SENDING
            )
            messageRepo.insertMessage(queued)
            if (targetChat.id == activeChatId) {
                _uiState.update { st ->
                    st.copy(messages = mergeMessages(st.messages, listOf(queued)))
                }
            }
            // Preview as soon as outbox is durable; finalizer refreshes after SENT.
            val preview = when (message.type) {
                MessageType.IMAGE -> text(R.string.message_preview_image)
                MessageType.GIF -> text(R.string.message_preview_gif)
                MessageType.VIDEO -> text(R.string.message_preview_video)
                MessageType.VOICE -> text(R.string.message_preview_voice)
                else -> text(R.string.message_preview_file)
            }
            com.maodouchat.MaodouchatApp.emitMessageSent(targetChat.id, preview, message.type.name)
        } catch (error: kotlinx.coroutines.CancellationException) {
            // Keep SENDING if transfer already enqueued; lease cleanup only if still owned.
            preparationLease.cleanupIfOwned()
            throw error
        } catch (error: Throwable) {
            val persisted = app.database.attachmentTransferDao().get(
                messageId,
                ownerUserId = preparationOwnerUserId
            ) != null
            if (persisted) {
                preparationLease.handOff()
            } else {
                preparationLease.cleanupIfOwned()
                val failed = optimistic.copy(status = MessageStatus.FAILED)
                messageRepo.insertMessage(failed)
                if (targetChat.id == activeChatId) {
                    _uiState.update { st ->
                        st.copy(messages = mergeMessages(st.messages, listOf(failed)))
                    }
                }
            }
            throw error
        }
    }

    /**
     * 转发目标群 epoch：live/cache 优先；否则转发瞬间再拉列表取最新 revision，
     * 避免 forwardTargets 打开后成员变更仍用旧 epoch 加密。
     * memberRevision <= 0 视为未知，fail closed（与 requireGroupEpoch 一致）。
     */
    private suspend fun resolveForwardGroupEpoch(targetChat: Chat): Long {
        if (!targetChat.isGroup) throw IllegalStateException("forward_epoch_not_group")
        currentGroupEpoch(targetChat.id)?.let { return it }
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw IllegalStateException(text(R.string.error_session_expired))
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val live = ApiService.getChats(liveToken).getOrNull()?.firstOrNull { it.id == targetChat.id }
        val rev = live?.takeIf { it.isGroup }?.memberRevision?.takeIf { it > 0L }
            ?: targetChat.memberRevision.takeIf { it > 0L }
            ?: throw IllegalStateException("group_epoch_unknown")
        return rev
    }

    fun refreshScheduledMessages() {
        val appCtx = getApplication<Application>()
        val chat = activeChatId.ifBlank { chatId }
        val list = com.maodouchat.util.ScheduledMessageStore.listForChat(appCtx, chat)
        _uiState.update { it.copy(scheduledMessages = list) }
    }

    fun clearScheduledInfo() {
        _uiState.update { it.copy(scheduledInfoMessage = null) }
    }

    /**
     * 将当前输入排队为定时发送（1:1 与群聊纯文本）。成功后清空输入框。
     * @param delayMs 相对当前时间的延迟
     */
    fun scheduleMessage(delayMs: Long) {
        scheduleMessageAt(System.currentTimeMillis() + delayMs.coerceAtLeast(com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS))
    }

    /**
     * 8.41：消息「稍后提醒」——本地 WorkManager 到点发通知，点击直达本聊天并高亮原消息。
     * 时间窗口 1 分钟 ~ 30 天；纯本机功能，不触碰服务端。
     */
    fun scheduleMessageReminder(message: Message, remindAtMillis: Long) {
        // 8.51：严格取真实 userId——currentUserId 有 "me" 兜底，未登录时会把 owner="me"
        // 写入 store 且登出清理清不掉（Worker 侧会话门禁虽拦截，但残留脏数据）
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank)
        if (ownerUserId == null) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        val clamped = remindAtMillis.coerceIn(
            System.currentTimeMillis() + com.maodouchat.util.MessageReminderPolicy.MIN_DELAY_MS,
            System.currentTimeMillis() + com.maodouchat.util.MessageReminderPolicy.MAX_DELAY_MS
        )
        val chat = activeChatId.ifBlank { chatId }
        if (chat.isBlank()) return
        val reminder = com.maodouchat.util.MessageReminderStore.MessageReminder(
            id = "mr_${java.util.UUID.randomUUID()}",
            chatId = chat,
            messageId = message.id,
            messagePreview = message.parsedContent().trim().take(80),
            remindAtMillis = clamped,
            createdAtMillis = System.currentTimeMillis(),
            ownerUserId = ownerUserId
        )
        com.maodouchat.util.MessageReminderStore.upsert(getApplication(), reminder)
        com.maodouchat.util.MessageReminderScheduler.schedule(getApplication(), reminder)
        _uiState.update {
            it.copy(
                groupEncryptionWarning = text(
                    R.string.message_reminder_scheduled,
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        clamped,
                        System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS
                    )
                )
            )
        }
    }

    /**
     * 8.48：当前会话的待触发提醒列表（按 chatId 过滤）。
     */
    fun listRemindersForChat(chatId: String): List<com.maodouchat.util.MessageReminderStore.MessageReminder> {
        // 8.53：与 scheduleMessageReminder 一致，严格取真实 userId（"me" 兜底在登出态会读空 key）
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank)
            ?: return emptyList()
        if (chatId.isBlank()) return emptyList()
        return com.maodouchat.util.MessageReminderStore.list(getApplication(), ownerUserId)
            .filter { it.chatId == chatId && it.remindAtMillis > System.currentTimeMillis() }
            .sortedBy { it.remindAtMillis }
    }

    /**
     * 8.48：取消一条待触发提醒（取消 WorkManager 作业 + 清除存储）。
     */
    fun cancelReminder(reminderId: String) {
        if (reminderId.isBlank()) return
        // 8.53：严格取真实 userId——用 "me" 兜底时 remove 是空操作但 Scheduler.cancel 生效，
        // 造成「作业已取消、存储行残留」的半删除
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank)
            ?: return
        com.maodouchat.util.MessageReminderScheduler.cancel(getApplication(), reminderId)
        com.maodouchat.util.MessageReminderStore.remove(getApplication(), reminderId, ownerUserId)
    }

    /** 1.32：清除某会话的全部待触发提醒（取消对应 Worker 作业 + 清空存储）。 */
    fun clearRemindersForChat(chatId: String) {
        if (chatId.isBlank()) return
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return
        val forChat = com.maodouchat.util.MessageReminderStore
            .list(getApplication(), ownerUserId)
            .filter { it.chatId == chatId }
        forChat.forEach {
            com.maodouchat.util.MessageReminderScheduler.cancel(getApplication(), it.id)
        }
        com.maodouchat.util.MessageReminderStore.clearForChat(getApplication(), chatId, ownerUserId)
    }

    /**
     * 8.54：导出本会话聊天记录（本地已解密消息 → 文本 → 系统分享）。
     * 上限 ChatExport.MAX_MESSAGES 条；失败提示，不抛异常。
     */
    fun exportChatHistory() {
        viewModelScope.launch {
            val chat = _uiState.value.chat ?: return@launch
            if (tokenManager.getToken().isNullOrBlank()) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            // 8.55：用带 LIMIT 的 getRecentMessages 从源头限量，避免全量加载后再 takeLast 的 OOM
            val recent = runCatching {
                messageRepo.getRecentMessages(chat.id, com.maodouchat.util.ChatExport.MAX_MESSAGES)
                    .asReversed()
            }.getOrDefault(emptyList())
            if (recent.isEmpty()) {
                _uiState.update { it.copy(infoMessage = text(R.string.chat_export_empty)) }
                return@launch
            }
            val ownerId = tokenManager.getUserId() ?: ""
            val participants = chat.participants.associateBy { it.id }
            val chatName = if (chat.isGroup) {
                chat.groupName?.takeIf { it.isNotBlank() } ?: text(R.string.chat_group)
            } else {
                participants.values.firstOrNull { it.id != ownerId }?.name?.takeIf { it.isNotBlank() }
                    ?: text(R.string.chat_group)
            }
            val exportText = com.maodouchat.util.ChatExport.buildText(
                chatName = chatName,
                ownerId = ownerId,
                resolveSenderName = { id -> participants[id]?.name?.takeIf { it.isNotBlank() } ?: id },
                messages = recent
            )
            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.maodouchat.util.ChatExport.write(getApplication(), chat.id, exportText)
            }
            if (file != null && com.maodouchat.util.ChatExport.share(
                    getApplication(), file, text(R.string.chat_export_share_title)
                )
            ) {
                _uiState.update { it.copy(infoMessage = text(R.string.chat_export_done)) }
            } else {
                _uiState.update { it.copy(infoMessage = text(R.string.chat_export_failed)) }
            }
        }
    }

    /**
     * 按绝对时间排队定时发送（会 clamp 到策略允许窗口）。
     */
    fun scheduleMessageAt(sendAtMillis: Long, repeatIntervalMs: Long = 0L, repeatCount: Int = 0, weekdaysOnly: Boolean = false) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SCHEDULED_MESSAGES)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.scheduled_messages_disabled)) }
            return
        }
        val text = _uiState.value.inputText.trim()
        val sendOwnerUserId = currentUserId
        if (!com.maodouchat.util.ScheduledMessagePolicy.isValidText(text)) return
        if (token.isBlank() || sendOwnerUserId.isBlank()) {
            _uiState.update { it.copy(scheduledInfoMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isContactBlocked) {
            _uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.chat_blocked_user_status, it.contact.displayName))
            }
            return
        }
        val appCtx = getApplication<Application>()
        val chat = activeChatId.ifBlank { chatId }
        val isGroup = _uiState.value.chat?.isGroup == true
        val pending = com.maodouchat.util.ScheduledMessageStore.listForChat(appCtx, chat)
        if (!com.maodouchat.util.ScheduledMessagePolicy.canAddMore(pending.size)) {
            _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_limit_reached)) }
            return
        }
        val item = com.maodouchat.util.ScheduledMessageStore.add(
            context = appCtx,
            chatId = chat,
            peerUserId = if (isGroup) "" else _uiState.value.contact.id,
            text = text,
            sendAtMillis = sendAtMillis,
            isGroup = isGroup,
            repeatIntervalMs = repeatIntervalMs,
            repeatCount = repeatCount,
            weekdaysOnly = weekdaysOnly
        )
        if (item == null) {
            _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_failed)) }
            return
        }
        com.maodouchat.util.ScheduledMessageScheduler.schedule(appCtx, item)
        clearDraft()
        _uiState.update {
            it.copy(
                inputText = "",
                scheduledMessages = com.maodouchat.util.ScheduledMessageStore.listForChat(appCtx, chat),
                scheduledInfoMessage = text(R.string.schedule_queued_at, formatScheduleTimeHint(item.sendAtMillis))
            )
        }
    }

    /** 1.07：重复定时发送（首次在间隔后，此后每间隔自动重发）。1.21：可限制重复次数（0=不限）。1.62：可仅工作日重复。 */
    fun scheduleMessageRepeat(intervalMs: Long, repeatCount: Int = 0, weekdaysOnly: Boolean = false) {
        if (intervalMs <= 0) return
        scheduleMessageAt(
            System.currentTimeMillis() + intervalMs.coerceAtLeast(com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS),
            repeatIntervalMs = intervalMs,
            repeatCount = repeatCount,
            weekdaysOnly = weekdaysOnly
        )
    }

    fun cancelScheduledMessage(id: String) {
        val appCtx = getApplication<Application>()
        com.maodouchat.util.ScheduledMessageScheduler.cancel(appCtx, id)
        com.maodouchat.util.ScheduledMessageStore.remove(appCtx, id)
        refreshScheduledMessages()
        _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_cancelled)) }
    }

    /** 1.168：定时消息立即发送（取消定时，按普通消息全加密路径发送，不扰动输入框草稿）。 */
    fun sendScheduledNow(id: String) {
        val appCtx = getApplication<Application>()
        val item = com.maodouchat.util.ScheduledMessageStore.get(appCtx, id) ?: return
        com.maodouchat.util.ScheduledMessageScheduler.cancel(appCtx, id)
        com.maodouchat.util.ScheduledMessageStore.remove(appCtx, id)
        refreshScheduledMessages()
        val text = item.text
        if (text.isBlank()) {
            _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_failed)) }
            return
        }
        sendMessage(forceText = text)
        _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_sent_now)) }
    }

    /** 1.174：取消本会话全部定时消息。 */
    fun cancelAllScheduledMessages() {
        val appCtx = getApplication<Application>()
        val targetChatId = activeChatId.ifBlank { chatId }
        if (targetChatId.isBlank()) return
        val removed = com.maodouchat.util.ScheduledMessageStore.clearForChat(appCtx, targetChatId)
        removed.forEach { com.maodouchat.util.ScheduledMessageScheduler.cancel(appCtx, it) }
        refreshScheduledMessages()
        _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_cancelled_all)) }
    }

    /**
     * 改期已排队的定时消息（可选改文案）。
     */
    fun rescheduleScheduledMessage(id: String, delayMs: Long, newText: String? = null) {
        rescheduleScheduledMessageAt(
            id = id,
            sendAtMillis = System.currentTimeMillis() + delayMs.coerceAtLeast(com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS),
            newText = newText
        )
    }

    fun rescheduleScheduledMessageAt(id: String, sendAtMillis: Long, newText: String? = null) {
        val appCtx = getApplication<Application>()
        if (com.maodouchat.util.ScheduledMessageStore.get(appCtx, id) == null) {
            _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_failed)) }
            return
        }
        val updated = com.maodouchat.util.ScheduledMessageStore.updateTextAndTime(
            context = appCtx,
            id = id,
            text = newText,
            sendAtMillis = sendAtMillis
        )
        if (updated == null) {
            _uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_failed)) }
            return
        }
        com.maodouchat.util.ScheduledMessageScheduler.reschedule(appCtx, updated)
        refreshScheduledMessages()
        _uiState.update {
            it.copy(scheduledInfoMessage = text(R.string.schedule_rescheduled, formatScheduleTimeHint(updated.sendAtMillis)))
        }
    }

    private fun formatScheduleTimeHint(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }

    internal fun requireGroupPlay(): Boolean {
        if (_uiState.value.chat?.isGroup != true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
            return false
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GROUP_PLAY)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_disabled)) }
            return false
        }
        return true
    }

    fun refreshSealedSenderCertificate() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isSecretChat != true) return@launch
            if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SEALED_SENDER)) {
                _uiState.update { it.copy(sealedSenderReady = false, sealedSenderExpiresInSec = 0L) }
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (liveToken.isBlank() || ownerUserId.isBlank()) return@launch
            val ownerDeviceId = signalProtocol.getDeviceId()
            val cert = try {
                com.maodouchat.crypto.SealedSenderSupport.fetchCertificate(
                    liveToken,
                    ownerUserId,
                    ownerDeviceId
                ).getOrNull()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val ready = cert?.certificate?.isNotBlank() == true
            val ttl = if (ready) {
                com.maodouchat.crypto.SealedSenderSupport.secondsUntilExpiry(ownerUserId, ownerDeviceId)
            } else {
                0L
            }
            _uiState.update { it.copy(sealedSenderReady = ready, sealedSenderExpiresInSec = ttl) }
        }
    }

    fun sendLotteryDraw() {
        if (!requireGroupPlay()) return
        val label = tokenManager.getUserId()?.take(8) ?: "me"
        val members = _uiState.value.chat?.participants.orEmpty()
            .map { it.displayName.ifBlank { it.id }.take(16) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("A", "B", "C") }
        val winner = members.random()
        val content = com.maodouchat.util.GroupPlayPolicy.formatLottery(members, winner, label)
        _uiState.update { it.copy(inputText = content) }
        sendMessage()
    }

    fun sendHotSeat() {
        if (!requireGroupPlay()) return
        val label = tokenManager.getUserId()?.take(8) ?: "me"
        val target = _uiState.value.chat?.participants.orEmpty()
            .filter { it.id != currentUserId }
            .map { it.displayName.ifBlank { it.id }.take(24) }
            .filter { it.isNotBlank() }
            .randomOrNull() ?: "someone"
        val content = com.maodouchat.util.GroupPlayPolicy.formatHotSeat(target, label)
        _uiState.update { it.copy(inputText = content) }
        sendMessage()
    }

fun sendSpinWheel() {
        if (!requireGroupPlay()) return
        val label = tokenManager.getUserId()?.take(8) ?: "me"
        val content = com.maodouchat.util.GroupPlayPolicy.formatSpin(
            com.maodouchat.util.GroupPlayPolicy.spinWheel(),
            label
        )
        _uiState.update { it.copy(inputText = content) }
        sendMessage()
    }

    fun sendStoryChain() {
        if (!requireGroupPlay()) return
        val label = tokenManager.getUserId()?.take(8) ?: "me"
        val content = com.maodouchat.util.GroupPlayPolicy.formatStorySeed(
            "Once upon a time, someone typed a message that changed everything...",
            label
        )
        _uiState.update { it.copy(inputText = content) }
        sendMessage()
    }

    fun sendGroupCountdown(seconds: Int = 30) {
        if (!requireGroupPlay()) return
        val label = tokenManager.getUserId()?.take(8) ?: "me"
        val content = com.maodouchat.util.GroupPlayPolicy.formatCountdown(seconds, label)
        _uiState.update { it.copy(inputText = content) }
        sendMessage()
    }

fun inviteFirstOwnedBot() {
        viewModelScope.launch {
            val liveToken = tokenManager.getToken().orEmpty()
            if (liveToken.isBlank()) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            val listRaw = withContext(Dispatchers.IO) { ApiService.listBots(liveToken) }
            listRaw.onSuccess { raw ->
                val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
                val firstId = if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.optString("id").orEmpty() else ""
                if (firstId.isBlank()) {
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_bot_invite_failed)) }
                } else {
                    inviteBot(firstId)
                }
            }.onFailure {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_bot_invite_failed)) }
            }
        }
    }

fun inviteBot(botId: String) {
        val chatId = activeChatId
        if (chatId.isBlank() || botId.isBlank()) return
        viewModelScope.launch {
            val liveToken = tokenManager.getToken().orEmpty()
            val result = withContext(Dispatchers.IO) {
                ApiService.inviteBotToChat(liveToken, chatId, botId)
            }
            result.onSuccess {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_bot_invited)) }
            }.onFailure {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_bot_invite_failed)) }
            }
        }
    }

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

    private var lastCapturePeerNotifyAt = 0L

    private fun sendCaptureAlertToPeer() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CAPTURE_ALERT)) return
        val now = System.currentTimeMillis()
        if (now - lastCapturePeerNotifyAt < 8_000L) return
        lastCapturePeerNotifyAt = now
        if (activeChatId.isBlank()) return
        if (_uiState.value.chat?.isGroup == true) return
        val label = tokenManager.getUserId()?.take(8) ?: "me"
        val content = com.maodouchat.util.CaptureAlertPolicy.format(label, "screenshot")
        // Reuse sticker/nudge-like inline send pipeline (E2EE TEXT envelope).
        sendInlineContent(content, MessageType.TEXT, content)
    }

    fun revealSpoilerMedia(messageId: String) {
        if (messageId.isBlank()) return
        val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        val currentMeta = current.parsedMeta()
        if (!currentMeta.spoilerMedia || currentMeta.spoilerRevealed) return
        val revealed = current.withEncodedMeta(currentMeta.copy(spoilerRevealed = true))
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id != messageId) msg
                    else {
                        val meta = msg.parsedMeta()
                        if (!meta.spoilerMedia || meta.spoilerRevealed) msg
                        else msg.withEncodedMeta(meta.copy(spoilerRevealed = true))
                    }
                }
            )
        }
        persistLocalMediaMeta(revealed)
    }

    fun markViewOnceOpened(messageId: String) {
        if (messageId.isBlank()) return
        val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        val opened = com.maodouchat.util.ViewOncePolicy.markOpened(current)
        if (opened == current) return
        _uiState.update { state ->
            val updated = state.messages.map { msg ->
                if (msg.id != messageId) msg
                else com.maodouchat.util.ViewOncePolicy.markOpened(msg)
            }
            state.copy(messages = updated)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = try {
                messageRepo.persistLocalMediaMeta(opened)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!persisted) {
                Log.w("ChatDetailViewModel", "Failed to persist view-once opened state for $messageId")
            }
            runCatching {
                com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
            }
        }
    }

    private fun persistLocalMediaMeta(message: Message) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                messageRepo.persistLocalMediaMeta(message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                Log.w("ChatDetailViewModel", "Failed to persist local media metadata for ${message.id}")
            }
        }
    }

fun sendImage(uri: Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.IMAGE_SEND)) {
            _uiState.update { it.copy(errorMessage = text(R.string.image_send_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.IMAGE)
    }
    fun sendViewOnceImage(uri: Uri) {
        if (_uiState.value.chatIsGroup) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_direct_only)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIEW_ONCE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.IMAGE, viewOnce = true)
    }
    fun sendViewOnceVideo(uri: Uri) {
        if (_uiState.value.chatIsGroup) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_direct_only)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIEW_ONCE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.VIDEO, viewOnce = true)
    }
    fun sendSpoilerImage(uri: Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SPOILER_MEDIA)) {
            _uiState.update { it.copy(errorMessage = text(R.string.spoiler_media_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.IMAGE, spoilerMedia = true)
    }
    fun sendSpoilerVideo(uri: Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SPOILER_MEDIA)) {
            _uiState.update { it.copy(errorMessage = text(R.string.spoiler_media_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.VIDEO, spoilerMedia = true)
    }
    fun sendGif(uri: Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GIF_SEND)) {
            _uiState.update { it.copy(errorMessage = text(R.string.gif_send_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.GIF)
    }
    fun sendVideo(uri: Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIDEO_SEND)) {
            _uiState.update { it.copy(errorMessage = text(R.string.video_send_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.VIDEO)
    }
    fun sendFile(uri: Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.FILE_SHARE)) {
            _uiState.update { it.copy(errorMessage = text(R.string.file_share_disabled)) }
            return
        }
        sendEncryptedAttachment(uri, MessageType.FILE)
    }

    fun sendSticker(sticker: String) {
        if (!requireStickers()) return
        val content = sticker.trim().take(32)
        if (content.isBlank()) return
        // 最近使用按账号本地记录，与发送解耦
        runCatching {
            com.maodouchat.util.StickerPreferences.recordRecent(getApplication(), content)
        }
        sendInlineContent(content, MessageType.STICKER, text(R.string.message_preview_sticker))
    }

    /**
     * Share live location for [durationMs] (default 15 min). Sends an E2EE LOCATION payload
     * with live=true; further updates reuse the same sessionId via [updateLiveLocation].
     */
    fun sendLiveLocation(durationMs: Long = com.maodouchat.util.LiveLocationPolicy.DURATION_OPTIONS_MS.first()) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.LIVE_LOCATION)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.live_location_disabled)) }
            return
        }
        viewModelScope.launch {
            val locOwnerUserId = currentUserId
            if (token.isBlank() || locOwnerUserId.isBlank()) {
                _uiState.update {
                    it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
                }
                return@launch
            }
            _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = locOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                    return@launch
                }
                com.maodouchat.util.LocationProvider.currentLocation(getApplication()).fold(
                    onSuccess = { location ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = locOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                            return@fold
                        }
                        val now = System.currentTimeMillis()
                        val sessionId = "live_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                        val until = now + durationMs.coerceIn(
                            com.maodouchat.util.LiveLocationPolicy.DURATION_OPTIONS_MS.first(),
                            com.maodouchat.util.LiveLocationPolicy.DURATION_OPTIONS_MS.last()
                        )
                        val payload = com.maodouchat.data.model.LocationPayload(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                            label = text(R.string.chat_live_location_send),
                            capturedAt = location.time.takeIf { it > 0 } ?: now,
                            live = true,
                            liveUntil = until,
                            sessionId = sessionId
                        )
                        val content = kotlinx.serialization.json.Json.encodeToString(
                            com.maodouchat.data.model.LocationPayload.serializer(),
                            payload
                        )
                        val messageId = sendInlineContent(
                            content,
                            MessageType.LOCATION,
                            text(R.string.message_preview_live_location)
                        ) ?: return@fold
                        lastLiveLocationPayload = payload
                        _uiState.update {
                            it.copy(
                                activeLiveLocationMessageId = messageId,
                                activeLiveLocationSessionId = sessionId,
                                activeLiveLocationUntil = until
                            )
                        }
                        startLiveLocationUpdates(messageId, sessionId, until)
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isSending = false,
                                groupEncryptionWarning = err.message?.take(120)
                                    ?: text(R.string.message_location_unavailable)
                            )
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSending = false, groupEncryptionWarning = e.message?.take(120))
                }
            }
        }
    }

    fun stopLiveLocationSharing(notifyPeer: Boolean = true) {
        // GPS callbacks may run on main looper; null-out first to avoid double-cancel races.
        val cancel = liveLocationCancel
        liveLocationCancel = null
        val job = liveLocationJob
        liveLocationJob = null
        val state = _uiState.value
        val messageId = state.activeLiveLocationMessageId
        val stoppedAt = System.currentTimeMillis()
        val terminalPayload = lastLiveLocationPayload
            ?.takeIf { it.sessionId == state.activeLiveLocationSessionId }
            ?.copy(capturedAt = stoppedAt, liveUntil = stoppedAt)
        lastLiveLocationPayload = null
        runCatching { cancel?.invoke() }
        job?.cancel()
        _uiState.update {
            it.copy(
                activeLiveLocationMessageId = null,
                activeLiveLocationSessionId = null,
                activeLiveLocationUntil = null
            )
        }
        if (notifyPeer && messageId != null && terminalPayload != null) {
            // 8.45：终态更新改走 applicationScope——此前依赖 viewModelScope，在
            // onCleared 路径（viewModelScope 已取消）下终态被丢弃，对端地图持续显示
            // live 位置直到原始过期时间。协程内引用仅用于网络发送，完成后即释放。
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                updateLiveLocationMessage(messageId, terminalPayload)
            }
        }
    }

    private fun startLiveLocationUpdates(messageId: String, sessionId: String, until: Long) {
        liveLocationCancel?.invoke()
        liveLocationCancel = null
        liveLocationJob?.cancel()
        val owner = currentUserId
        if (owner.isBlank() || sessionId.isBlank()) return
        val appCtx = getApplication<Application>()
        liveLocationJob = viewModelScope.launch(Dispatchers.IO) {
            var lastSentAt = 0L
            val cancel = com.maodouchat.util.LocationProvider.requestUpdates(appCtx) { location ->
                val now = System.currentTimeMillis()
                if (now >= until) {
                    viewModelScope.launch(Dispatchers.Main.immediate) { stopLiveLocationSharing(notifyPeer = false) }
                    return@requestUpdates
                }
                if (now - lastSentAt < com.maodouchat.util.LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS) return@requestUpdates
                lastSentAt = now
                viewModelScope.launch {
                    pushLiveLocationUpdate(messageId, sessionId, until, location)
                }
            }
            liveLocationCancel = cancel
            // Also tick periodically even if GPS quiet, and auto-stop at expiry.
            while (isActive) {
                val state = _uiState.value
                if (state.activeLiveLocationSessionId != sessionId) break
                val u = state.activeLiveLocationUntil ?: until
                if (System.currentTimeMillis() >= u) {
                    withContext(Dispatchers.Main) { stopLiveLocationSharing(notifyPeer = false) }
                    break
                }
                kotlinx.coroutines.delay(5_000L)
            }
            cancel()
        }
    }

    private suspend fun pushLiveLocationUpdate(
        messageId: String,
        sessionId: String,
        until: Long,
        location: android.location.Location
    ) {
        val sendOwnerUserId = currentUserId
        if (token.isBlank() || sendOwnerUserId.isBlank()) return
        val state = _uiState.value
        if (state.activeLiveLocationSessionId != sessionId || state.activeLiveLocationMessageId != messageId) return
        val now = System.currentTimeMillis()
        if (now >= until) {
            stopLiveLocationSharing(notifyPeer = false)
            return
        }
        val payload = com.maodouchat.data.model.LocationPayload(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            label = text(R.string.chat_live_location_send),
            capturedAt = location.time.takeIf { it > 0 } ?: now,
            live = true,
            liveUntil = until,
            sessionId = sessionId
        )
        lastLiveLocationPayload = payload
        updateLiveLocationMessage(messageId, payload)
    }

    private suspend fun updateLiveLocationMessage(
        messageId: String,
        payload: com.maodouchat.data.model.LocationPayload
    ) = liveLocationUpdateMutex.withLock {
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) return@withLock
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) return@withLock
        val stagedOriginal = _uiState.value.messages.firstOrNull { it.id == messageId }
            ?.takeIf { it.senderId == ownerUserId && it.type == MessageType.LOCATION }
            ?: return@withLock
        val content = kotlinx.serialization.json.Json.encodeToString(
            com.maodouchat.data.model.LocationPayload.serializer(),
            payload
        )
        val stagedUpdate = stagedOriginal.toOptimisticEdit(content)
        val terminalUpdate = payload.liveUntil?.let { it <= System.currentTimeMillis() + 1_000L } == true
        if (terminalUpdate) {
            _uiState.update { state ->
                state.copy(messages = state.messages.map { if (it.id == messageId) stagedUpdate else it })
            }
            withContext(Dispatchers.IO) { messageRepo.insertMessage(stagedUpdate) }
        }
        if (inlineSendCompletions[messageId]?.await() == false) return@withLock
        val original = _uiState.value.messages.firstOrNull { it.id == messageId }
            ?.takeIf { it.senderId == ownerUserId && it.type == MessageType.LOCATION }
            ?: stagedUpdate
        val updated = if (original.content == content) original else original.toOptimisticEdit(content)
        try {
            val result = withContext(Dispatchers.IO) {
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val wireContent = if (_uiState.value.chat?.isGroup == true) {
                    val epoch = requireGroupEpoch(original.chatId)
                    ensureGroupSenderKeyDistributed(original.chatId, epoch)
                    signalProtocol.encryptGroupTextEnvelope(
                        original.chatId,
                        content,
                        original.type.name,
                        epoch
                    ).getOrThrow()
                } else {
                    signalProtocol.encryptSyncedContentEnvelope(
                        liveToken,
                        _uiState.value.contact.id,
                        content,
                        original.type.name
                    ).getOrThrow()
                }
                var attempt = 0
                var editResult: Result<Unit>
                do {
                    if (attempt > 0) kotlinx.coroutines.delay(300L * attempt)
                    editResult = ApiService.editMessage(liveToken, original.chatId, messageId, wireContent)
                    attempt++
                } while (terminalUpdate && editResult.isFailure && attempt < 3)
                editResult
            }
            if (result.isSuccess && com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { state ->
                    state.copy(messages = state.messages.map { if (it.id == messageId) updated else it })
                }
                withContext(Dispatchers.IO) { messageRepo.insertMessage(updated) }
            } else if (result.isFailure) {
                Log.w(
                    "ChatDetailViewModel",
                    "Live location update failed: ${result.exceptionOrNull()?.message ?: "unknown"}"
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ChatDetailViewModel", "Live location update failed", error)
        }
    }

fun sendCurrentLocation() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.STATIC_LOCATION)) {
            _uiState.update { it.copy(errorMessage = text(R.string.static_location_disabled)) }
            return
        }
        viewModelScope.launch {
            val locOwnerUserId = currentUserId
            if (token.isBlank() || locOwnerUserId.isBlank()) {
                _uiState.update {
                    it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
                }
                return@launch
            }
            _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = locOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                    return@launch
                }
                com.maodouchat.util.LocationProvider.currentLocation(getApplication()).fold(
                    onSuccess = { location ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = locOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                            return@fold
                        }
                        val payload = com.maodouchat.data.model.LocationPayload(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                            capturedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                        )
                        val content = kotlinx.serialization.json.Json.encodeToString(
                            com.maodouchat.data.model.LocationPayload.serializer(),
                            payload
                        )
                        // sendInlineContent owns the isSending lifecycle from here.
                        sendInlineContent(content, MessageType.LOCATION, text(R.string.message_preview_location))
                    },
                    onFailure = { error ->
                        val message = when ((error as? com.maodouchat.util.LocationException)?.failure) {
                            com.maodouchat.util.LocationFailure.PERMISSION_REQUIRED -> text(R.string.location_error_permission)
                            com.maodouchat.util.LocationFailure.SERVICES_DISABLED -> text(R.string.location_error_services_disabled)
                            com.maodouchat.util.LocationFailure.UNAVAILABLE, null -> text(R.string.location_error_unavailable)
                        }
                        _uiState.update { it.copy(isSending = false, groupEncryptionWarning = message) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isSending = false) }
                throw error
            }
        }
    }

    private fun sendInlineContent(content: String, type: MessageType, preview: String): String? {
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
            status = MessageStatus.SENDING
        )
        val sendCompletion = CompletableDeferred<Boolean>()
        inlineSendCompletions[msgId] = sendCompletion
        _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(optimistic)), isSending = true) }
        viewModelScope.launch {
            var initialSendSucceeded = false
            try {
                // 与文本一致：网络前落库，避免杀进程丢 sticker/location
                withContext(Dispatchers.IO) {
                    messageRepo.insertMessage(optimistic)
                    indexSearchableMessage(optimistic)
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("inline_send_session_changed")
                }
                val (resolvedChatId, viaRest) = withContext(Dispatchers.IO) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        throw kotlinx.coroutines.CancellationException("inline_send_session_changed")
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    val effectiveChatId = resolveOutgoingChatId().getOrThrow()
                    val groupEpoch = if (_uiState.value.chat?.isGroup == true) {
                        requireGroupEpoch(effectiveChatId).also { ensureGroupSenderKeyDistributed(effectiveChatId, it) }
                    } else null
                    val wireContent = if (groupEpoch != null) {
                        signalProtocol.encryptGroupContentEnvelope(effectiveChatId, content, type.name, groupEpoch).getOrThrow()
                    } else {
                        signalProtocol.encryptSyncedContentEnvelope(liveToken, _uiState.value.contact.id, content, type.name).getOrThrow()
                    }
                    val delivered = deliverOutgoing(
                        message = optimistic.copy(chatId = effectiveChatId, content = wireContent),
                        wireContent = wireContent,
                        typeName = type.name,
                        messageId = msgId,
                        chatId = effectiveChatId
                    )
                    if (groupEpoch != null) markGroupSenderKeyMessageSent(effectiveChatId, groupEpoch)
                    effectiveChatId to delivered
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("inline_send_session_changed")
                }
                val finalStatus = if (viaRest) MessageStatus.SENT else MessageStatus.SENDING
                val latest = _uiState.value.messages.firstOrNull { it.id == msgId }
                    ?.takeIf { it.senderId == optimistic.senderId && it.type == optimistic.type }
                    ?: optimistic
                val finalMessage = latest.copy(chatId = resolvedChatId, status = finalStatus)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == msgId) finalMessage else it },
                        isSending = false
                    )
                }
                withContext(Dispatchers.IO) {
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        messageRepo.insertMessage(finalMessage)
                    }
                }
                if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    com.maodouchat.MaodouchatApp.emitMessageSent(resolvedChatId, preview, type.name)
                }
                initialSendSucceeded = true
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Keep SENDING outbox row; only clear the in-flight UI spinner.
                _uiState.update { it.copy(isSending = false) }
                throw error
            } catch (error: Exception) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isSending = false) }
                    return@launch
                }
                val terminalFailed = shouldMarkOutboxFailed(error)
                val latest = _uiState.value.messages.firstOrNull { it.id == msgId }
                    ?.takeIf { it.senderId == optimistic.senderId && it.type == optimistic.type }
                    ?: optimistic
                val next = latest.copy(
                    status = if (terminalFailed) MessageStatus.FAILED else MessageStatus.SENDING
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == msgId) next else it },
                        isSending = false
                    )
                }
                withContext(Dispatchers.IO) {
                    try {
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = sendOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            messageRepo.insertMessage(next)
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (persistError: Exception) {
                        Log.w("ChatDetailViewModel", "persist status after sendInline failure", persistError)
                    }
                }
                if (!terminalFailed) {
                    Log.w("ChatDetailViewModel", "sendInline transient failure, keep SENDING: " + (error.message ?: "unknown"))
                }
                if (type == MessageType.LOCATION && _uiState.value.activeLiveLocationMessageId == msgId) {
                    stopLiveLocationSharing(notifyPeer = true)
                }
            } finally {
                sendCompletion.complete(initialSendSucceeded)
                inlineSendCompletions.remove(msgId, sendCompletion)
            }
        }
        return msgId
    }

    internal fun composeContentWithMeta(text: String, meta: com.maodouchat.data.model.MessageMeta): String {
        if (
            meta.mentions.isEmpty() &&
            meta.replyToId == null &&
            meta.voiceTranscript.isNullOrBlank() &&
            meta.voiceDurationMs == null &&
            meta.translations.isEmpty() &&
            meta.aiImageAnalyses.isEmpty() &&
            meta.aiFileAnalyses.isEmpty() &&
            meta.aiFileLastQuestion.isNullOrBlank() &&
            !meta.aiAssisted &&
            meta.fileName.isNullOrBlank() &&
            meta.fileMimeType.isNullOrBlank() &&
            meta.fileSizeBytes == null &&
            meta.attachmentId == null &&
            !meta.markdown &&
            !meta.viewOnce &&
            !meta.viewOnceOpened &&
            !meta.silent &&
            !meta.spoilerMedia &&
            !meta.spoilerRevealed
        ) return text
        val json = com.maodouchat.util.JsonFormat.encodeMessageMeta(meta)
        return text + com.maodouchat.data.model.Message.META_TAG_PREFIX + json + "</meta>"
    }

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

    private fun sendEncryptedAttachment(
        uri: Uri,
        type: MessageType,
        fixedMessageId: String? = null,
        existingMessage: Message? = null,
        voiceDurationMs: Long? = existingMessage?.parsedMeta()?.voiceDurationMs,
        viewOnce: Boolean = existingMessage?.meta?.viewOnce == true,
        spoilerMedia: Boolean = existingMessage?.meta?.spoilerMedia == true
    ) {
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
        val preparationLease = AttachmentPreparationLease(
            originalSourceUri = uri.toString(),
            deleteEncryptedFile = { path -> runCatching { File(path).delete() } },
            deletePreparedSource = { source ->
                MediaCache.deletePreparedAttachmentSource(getApplication(), source)
            },
            releasePersistablePermission = { source ->
                MediaCache.releasePersistableReadPermission(getApplication(), source)
            }
        )
        val preparationJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = attachOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                preparationLease.cleanupIfOwned()
                throw kotlinx.coroutines.CancellationException("attachment_send_session_changed")
            }
            val existingTransfer = app.database.attachmentTransferDao().get(
                messageId,
                ownerUserId = attachOwnerUserId
            )
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = attachOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                preparationLease.cleanupIfOwned()
                throw kotlinx.coroutines.CancellationException("attachment_send_session_changed")
            }
            if (existingTransfer != null) {
                // Caller may have raised isSending (voice path); resume path never touches it.
                _uiState.update { it.copy(isSending = false) }
                resumeFileTransfer(messageId)
                return@launch
            }
            val initialMetadata = withContext(Dispatchers.IO) { MediaCache.describeFile(getApplication(), uri) }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = attachOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                preparationLease.cleanupIfOwned()
                throw kotlinx.coroutines.CancellationException("attachment_send_session_changed")
            }
            if (initialMetadata.sizeBytes > MediaCache.MAX_ATTACHMENT_PLAIN_BYTES) {
                preparationLease.cleanupIfOwned()
                _uiState.update {
                    it.copy(
                        isSending = false,
                        groupEncryptionWarning = text(R.string.chat_attachment_file_too_large)
                    )
                }
                return@launch
            }
            val initialMeta = com.maodouchat.data.model.MessageMeta(
                voiceDurationMs = voiceDurationMs,
                fileName = initialMetadata.fileName,
                fileMimeType = initialMetadata.mimeType,
                fileSizeBytes = initialMetadata.sizeBytes.takeIf { it > 0 },
                viewOnce = viewOnce && com.maodouchat.util.ViewOncePolicy.supports(type) && _uiState.value.chat?.isGroup != true,
                spoilerMedia = spoilerMedia && !viewOnce && type in setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF)
            )
            val optimistic = existingMessage?.copy(
                content = composeContentWithMeta(uri.toString(), initialMeta),
                status = MessageStatus.SENDING,
                meta = initialMeta
            ) ?: Message(
                id = messageId,
                chatId = activeChatId,
                senderId = currentUserId,
                content = composeContentWithMeta(uri.toString(), initialMeta),
                type = type,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                meta = initialMeta
            )
            _uiState.update {
                it.copy(
                    messages = mergeMessages(it.messages, listOf(optimistic)),
                    isSending = true,
                    fileTransferProgress = it.fileTransferProgress + (messageId to 0f),
                    preparingAttachmentMessageIds = it.preparingAttachmentMessageIds + messageId,
                    groupEncryptionWarning = null
                )
            }
            withContext(Dispatchers.IO) { messageRepo.insertMessage(optimistic) }
            val preparationOwnerUserId = attachOwnerUserId
            if (preparationOwnerUserId.isBlank() || token.isBlank()) {
                throw IllegalStateException(text(R.string.error_session_expired))
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = preparationOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                throw kotlinx.coroutines.CancellationException("attachment_send_session_changed")
            }
            val preparationExecutor = AttachmentPreparationExecutor(
                context = getApplication(),
                ownerUserId = tokenManager::getUserId,
                resolveChatId = ::resolveOutgoingChatId,
                onEncryptionProgress = { id, completed, total ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = preparationOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        throw kotlinx.coroutines.CancellationException("attachment_encrypt_session_changed")
                    }
                    updateFileTransferProgress(id, completed, total, 0f, 0.35f)
                }
            )
            try {
                val queuedMessage = withContext(Dispatchers.IO) {
                    val prepared = preparationExecutor.prepareAndEnqueue(
                        request = AttachmentPreparationRequest(
                            messageId = messageId,
                            sourceUri = uri,
                            type = type,
                            initialMetadata = initialMetadata,
                            voiceDurationMs = voiceDurationMs
                        ),
                        lease = preparationLease
                    )
                    val queuedMeta = initialMeta.copy(
                        fileName = prepared.fileName,
                        fileMimeType = prepared.mimeType,
                        fileSizeBytes = prepared.plainSize
                    )
                    val queued = optimistic.copy(
                        chatId = prepared.chatId,
                        content = composeContentWithMeta(prepared.sourceUri, queuedMeta),
                        meta = queuedMeta,
                        status = MessageStatus.SENDING
                    )
                    messageRepo.insertMessage(queued)
                    queued
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages.map { current -> if (current.id == messageId) queuedMessage else current },
                        isSending = false,
                        preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Keep SENDING outbox if already persisted; clear spinner flags here so voice/path
                // cancel does not rely solely on invokeOnCompletion ordering.
                preparationLease.cleanupIfOwned()
                _uiState.update {
                    it.copy(
                        isSending = false,
                        preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId
                    )
                }
                throw error
            } catch (error: Exception) {
                val persisted = withContext(Dispatchers.IO) {
                    app.database.attachmentTransferDao().get(
                        messageId,
                        ownerUserId = attachOwnerUserId
                    ) != null
                }
                if (persisted) preparationLease.handOff() else preparationLease.cleanupIfOwned()
                val failed = optimistic.copy(status = MessageStatus.FAILED)
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
        }
        attachmentPreparationJobs[messageId] = preparationJob
        preparationJob.invokeOnCompletion { error ->
            attachmentPreparationJobs.remove(messageId, preparationJob)
            if (error != null) {
                preparationLease.cleanupIfOwned()
                if (tokenManager.getUserId() != attachOwnerUserId) return@invokeOnCompletion
                // Cancel / crash mid-prepare must not leave the global spinner stuck
                // (voice path sets isSending before enqueue; resume early-exit also relies on this).
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

    private fun updateFileTransferProgress(
        messageId: String,
        completed: Long,
        total: Long,
        start: Float,
        end: Float
    ) {
        _uiState.update { state ->
            val previous = state.fileTransferProgress[messageId] ?: -1f
            val progress = com.maodouchat.attachment.AttachmentProgressPolicy.mapSegmentProgress(
                completed = completed,
                total = total,
                start = start,
                end = end,
                previousPublished = previous
            ) ?: return@update state
            state.copy(fileTransferProgress = state.fileTransferProgress + (messageId to progress))
        }
    }

    private fun attachmentErrorText(error: Throwable, fallbackStringRes: Int): String =
        when (com.maodouchat.attachment.AttachmentErrorUiPolicy.classify(error)) {
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.TOO_LARGE ->
                text(R.string.chat_attachment_file_too_large)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.INVALID_REFERENCE ->
                text(R.string.chat_attachment_reference_invalid)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.INTEGRITY_FAILED ->
                text(R.string.chat_attachment_integrity_failed)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.CONTENT_MISMATCH ->
                text(R.string.chat_attachment_content_mismatch)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.FALLBACK ->
                text(fallbackStringRes)
        }

    private fun refreshIdentitySafetyState(contactId: String) {
        if (_uiState.value.chat?.isGroup == true) {
            _uiState.update {
                it.copy(
                    identityWarning = null,
                    safetyCode = null,
                    trustState = SignalProtocol.IdentityTrustState.TRUSTED,
                    deviceSafetyStates = emptyList(),
                    isLoadingDeviceSafety = false,
                    deviceSafetyWarning = null,
                    canVerifyIdentity = false
                )
            }
            return
        }
        if (contactId.isBlank()) return
        val safetyOwnerUserId = currentUserId
        if (
            safetyOwnerUserId.isBlank() ||
            safetyOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = safetyOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDeviceSafety = true, deviceSafetyWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = safetyOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isLoadingDeviceSafety = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = withContext(Dispatchers.IO) {
                    signalProtocol.getRemoteDeviceSafetyStates(liveToken, contactId)
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = safetyOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isLoadingDeviceSafety = false) }
                    return@launch
                }
                result.fold(
                    onSuccess = { states ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = safetyOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val aggregateTrust = com.maodouchat.crypto.IdentitySafetyPolicy.aggregateTrust(states)
                        val primary = com.maodouchat.crypto.IdentitySafetyPolicy.primaryDevice(states)
                        val warning = when (com.maodouchat.crypto.IdentitySafetyPolicy.warningKind(aggregateTrust)) {
                            com.maodouchat.crypto.IdentitySafetyPolicy.WarningKind.CHANGED ->
                                text(R.string.chat_identity_changed_warning)
                            com.maodouchat.crypto.IdentitySafetyPolicy.WarningKind.UNKNOWN ->
                                text(R.string.chat_identity_unknown_warning)
                            com.maodouchat.crypto.IdentitySafetyPolicy.WarningKind.TRUSTED ->
                                text(R.string.chat_identity_trusted_warning)
                            com.maodouchat.crypto.IdentitySafetyPolicy.WarningKind.NONE -> null
                        }
                        _uiState.update {
                            it.copy(
                                identityWarning = warning,
                                safetyCode = primary?.safetyCode,
                                contactIdentityFingerprint = primary?.identityFingerprint,
                                trustState = aggregateTrust,
                                deviceSafetyStates = states,
                                isLoadingDeviceSafety = false,
                                deviceSafetyWarning = null,
                                canVerifyIdentity = com.maodouchat.crypto.IdentitySafetyPolicy.canVerifyAny(states)
                            )
                        }
                    },
                    onFailure = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = safetyOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { state ->
                            state.copy(
                                isLoadingDeviceSafety = false,
                                deviceSafetyWarning = text(R.string.chat_safety_devices_load_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoadingDeviceSafety = false) }
                throw error
            }
        }
    }

    fun showSafetyCodeDialog() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SAFETY_CODE)) {
            _uiState.update { it.copy(errorMessage = text(R.string.safety_code_disabled)) }
            return
        }
        _uiState.update { it.copy(showSafetyCodeDialog = true) }
        refreshIdentitySafetyState(_uiState.value.contact.id)
    }
    fun dismissSafetyCodeDialog() { _uiState.update { it.copy(showSafetyCodeDialog = false) } }
    fun verifyAndTrustIdentity(deviceId: Int? = null) {
        val contactId = _uiState.value.contact.id
        if (contactId.isBlank()) return
        val verifyOwnerUserId = currentUserId
        if (
            verifyOwnerUserId.isBlank() ||
            verifyOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        val targetDeviceId = deviceId ?: 1
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = verifyOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val success = withContext(Dispatchers.IO) { signalProtocol.markIdentityVerified(contactId, targetDeviceId) }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = verifyOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            if (success) {
                refreshIdentitySafetyState(contactId)
                // B2 设备核验（dvz）：服务端确认后同步本地已核验指纹，避免下次进入再次弹核验页
                if (com.maodouchat.util.SecretDeviceVerifyPrefs.isEnabled(getApplication())) {
                    val fp = _uiState.value.contactIdentityFingerprint?.takeIf { it.isNotBlank() }
                    if (fp != null) com.maodouchat.util.SecretDeviceVerifyPrefs.markFingerprintVerified(getApplication(), fp)
                }
            }
            else _uiState.update { it.copy(deviceSafetyWarning = text(R.string.contacts_safety_trust_failed)) }
        }
    }

    /**
     * 一键验证对方所有设备：逐设备调用 markIdentityVerified，适合 QR 扫码批量核验后使用。
     * 成功后刷新安全码状态，将 CHANGED 警告自动恢复为 VERIFIED。
     */
    fun verifyAllDevices() {
        val contactId = _uiState.value.contact.id
        if (contactId.isBlank()) return
        val devices = _uiState.value.deviceSafetyStates
        if (devices.isEmpty()) return
        val verifyOwnerUserId = currentUserId
        if (
            verifyOwnerUserId.isBlank() ||
            verifyOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        viewModelScope.launch {
            var allSuccess = true
            for (device in devices) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = verifyOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) {
                    signalProtocol.markIdentityVerified(contactId, device.deviceId)
                }
                if (!ok) allSuccess = false
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = verifyOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            if (allSuccess) refreshIdentitySafetyState(contactId)
            else _uiState.update { it.copy(deviceSafetyWarning = text(R.string.contacts_safety_trust_failed)) }
        }
    }

    private val recipientId: String get() = _uiState.value.contact.id

    private suspend fun resolveOutgoingChatId(): Result<String> {
        if (_uiState.value.chat?.isGroup == true) return Result.success(activeChatId)
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) return Result.failure(IllegalStateException(text(R.string.chat_not_logged_in)))
        if (recipientId.isBlank()) return Result.failure(IllegalStateException(text(R.string.chat_recipient_not_ready)))
        if (recipientId == ownerUserId) return Result.failure(IllegalStateException(text(R.string.chat_cannot_send_self)))
        if (activeChatId.isNotBlank() && _uiState.value.chat != null) return Result.success(activeChatId)
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return Result.failure(IllegalStateException(text(R.string.error_session_expired)))
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        return ApiService.createChat(liveToken, listOf(recipientId), isGroup = false, groupName = null).map { chatDto ->
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                throw IllegalStateException(text(R.string.error_session_expired))
            }
            activeChatId = chatDto.id
            chatDto.id
        }
    }

    /**
     * Deliver ciphertext for a client-generated message id.
     * @return true when REST completed (authoritative SENT); false when WS enqueued (await MESSAGE_STATUS).
     */
    private suspend fun deliverOutgoing(
        message: Message,
        wireContent: String,
        typeName: String,
        messageId: String,
        chatId: String,
        silent: Boolean = message.parsedMeta().silent
    ): Boolean {
        val deliverOwnerUserId = currentUserId
        if (deliverOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = deliverOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw kotlinx.coroutines.CancellationException("deliver_session_changed")
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val wantSealed = _uiState.value.isSecretChat == true ||
            runCatching { secretChatRepo.isSecret(chatId) }.getOrDefault(false)
        val sealedOwnerDeviceId = signalProtocol.getDeviceId()
        val sealedCert = if (wantSealed && liveToken.isNotBlank()) {
            withContext(Dispatchers.IO) {
                com.maodouchat.crypto.SealedSenderSupport
                    .fetchCertificate(liveToken, deliverOwnerUserId, sealedOwnerDeviceId)
                    .getOrNull()
                    ?.certificate
            }
        } else null
        val sealed = wantSealed && !sealedCert.isNullOrBlank()
        if (wantSealed && !sealed) {
            // Secret chat preferred sealed metadata for push/webhook redaction; continue send with E2EE body.
            _uiState.update {
                it.copy(
                    sealedSenderReady = false,
                    sealedSenderExpiresInSec = 0L,
                    secretChatInfoMessage = it.secretChatInfoMessage
                        ?: text(R.string.secret_chat_sealed_unavailable)
                )
            }
        } else if (sealed) {
            _uiState.update {
                it.copy(
                    sealedSenderReady = true,
                    sealedSenderExpiresInSec = com.maodouchat.crypto.SealedSenderSupport.secondsUntilExpiry(
                        deliverOwnerUserId,
                        sealedOwnerDeviceId
                    )
                )
            }
        }
        val outgoing = message.copy(content = wireContent, chatId = chatId, sealedSender = sealed)
        if (WebSocketClient.isConnected()) {
            if (!WebSocketClient.sendMessage(outgoing, sealedSenderCertificate = sealedCert, silent = silent)) {
                throw IllegalStateException(text(R.string.chat_ws_send_failed))
            }
            return false
        }
        ApiService.sendMessage(
            token = liveToken,
            chatId = chatId,
            content = wireContent,
            type = typeName,
            id = messageId,
            sealedSender = sealed,
            sealedSenderCertificate = sealedCert,
            silent = silent
        ).getOrThrow()
        return true
    }

    /**
     * Re-send local SENDING text/sticker/location via REST (idempotent client message id).
     * Shared [TextOutboxFlusher] also runs from ChatList on reconnect so leave-chat outbox
     * does not wait for re-open. UI merge only applies to the open chat.
     */
    private suspend fun flushSendingOutbox() {
        val flushOwnerUserId = currentUserId
        if (
            token.isBlank() ||
            flushOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = flushOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        com.maodouchat.data.repository.TextOutboxFlusher.flush(
            app = app,
            activeChatId = activeChatId,
            activeContactId = _uiState.value.contact.id
        ) { updated ->
            // Outbox can finish after logout/switch — do not paint status onto next owner UI.
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = flushOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@flush
            }
            if (updated.chatId == activeChatId || activeChatId.isBlank()) {
                _uiState.update { st ->
                    st.copy(messages = st.messages.map { m -> if (m.id == updated.id) updated else m })
                }
            }
        }
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
    fun sendMessage(replyTarget: Message? = null, silent: Boolean? = null, forceText: String? = null) {
        // 重入保护：快速双击/连点在 isSending 置位前的同帧窗口会各自生成不同 msgId，
        // 服务端按 requestedId 去重无法拦截，导致重复可见气泡。函数开头即拦截。
        if (_uiState.value.isSending) return
        // 8.52 UX：发送前兜底截断（与 onInputChange 一致，防绕过路径）
        // 1.168：forceText 用于「定时消息立即发送」，不读取输入框、不扰动用户草稿
        val rawText = (forceText ?: _uiState.value.inputText).trim()
        val text = if (rawText.length > MAX_COMPOSER_TEXT_LENGTH) rawText.take(MAX_COMPOSER_TEXT_LENGTH) else rawText
        if (text.isBlank()) return
        val sendOwnerUserId = currentUserId
        if (token.isBlank() || sendOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isContactBlocked) {
            _uiState.update {
                it.copy(groupEncryptionWarning = text(R.string.chat_blocked_user_status, it.contact.displayName))
            }
            return
        }
        val isGroup = _uiState.value.chat?.isGroup == true
        val wantSilent = silent ?: _uiState.value.silentSend
        val participants = _uiState.value.chat?.participants.orEmpty()
        // 1.37：仅群主/管理员可 @所有人（其余用户输入 @所有人/@everyone 时阻止发送并提示）
        val extractedMentions = if (isGroup && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MENTIONS)) {
            MentionPolicy.extractMentionIds(text, participants, sendOwnerUserId)
        } else {
            emptyList()
        }
        val canMentionEveryone = !isGroup || run {
            // 1.45：角色未加载（null）时 fail-open，避免刚进群的管理员在成员刷新前被误拦
            val role = _uiState.value.myMemberRole?.uppercase()
            role == null || role == "OWNER" || role == "ADMIN"
        }
        if (isGroup && extractedMentions.contains(MentionPolicy.EVERYONE_ID) && !canMentionEveryone) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_mention_everyone_restricted)) }
            return
        }
        val mentionIds = extractedMentions
        val looksMd = com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(text)
        val meta = MessageMeta(
            mentions = mentionIds,
            replyToId = replyTarget?.id,
            markdown = looksMd,
            silent = wantSilent
        )
        val messageType = if (looksMd) MessageType.MARKDOWN else MessageType.TEXT
        val contentWithMeta = composeContentWithMeta(text, meta)
        val msgId = "m_${java.util.UUID.randomUUID()}"
        val chatIdForOptimistic = activeChatId
        val optimistic = Message(
            id = msgId,
            chatId = chatIdForOptimistic,
            senderId = sendOwnerUserId,
            content = contentWithMeta,
            type = messageType,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            meta = meta
        )
        // 1.168：forceText 发送不动用户草稿/输入框
        if (forceText == null) clearDraft()
        _uiState.update {
            it.copy(
                messages = mergeMessages(it.messages, listOf(optimistic)),
                inputText = if (forceText == null) "" else it.inputText,
                groupEncryptionWarning = null,
                isSending = true,
                silentSend = if (wantSilent) false else it.silentSend
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                messageRepo.insertMessage(optimistic)
                indexSearchableMessage(optimistic)
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("send_session_changed")
                }
                val (resolvedChatId, viaRest) = withContext(Dispatchers.IO) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        throw kotlinx.coroutines.CancellationException("send_session_changed")
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    val effectiveChatId = resolveOutgoingChatId().getOrThrow()
                    val groupEpoch = if (_uiState.value.chat?.isGroup == true) {
                        requireGroupEpoch(effectiveChatId).also { ensureGroupSenderKeyDistributed(effectiveChatId, it) }
                    } else null
                    val wireContent = if (groupEpoch != null) {
                        signalProtocol.encryptGroupTextEnvelope(
                            effectiveChatId,
                            contentWithMeta,
                            messageType.name,
                            groupEpoch
                        ).getOrThrow()
                    } else {
                        signalProtocol.encryptSyncedContentEnvelope(
                            liveToken,
                            _uiState.value.contact.id,
                            contentWithMeta,
                            messageType.name
                        ).getOrThrow()
                    }
                    val delivered = deliverOutgoing(
                        message = optimistic.copy(chatId = effectiveChatId, content = wireContent),
                        wireContent = wireContent,
                        typeName = messageType.name,
                        messageId = msgId,
                        chatId = effectiveChatId,
                        silent = wantSilent
                    )
                    if (groupEpoch != null) markGroupSenderKeyMessageSent(effectiveChatId, groupEpoch)
                    effectiveChatId to delivered
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("send_session_changed")
                }
                val finalStatus = if (viaRest) MessageStatus.SENT else MessageStatus.SENDING
                val finalMessage = optimistic.copy(chatId = resolvedChatId, status = finalStatus)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == msgId) finalMessage else it },
                        isSending = false
                    )
                }
                withContext(Dispatchers.IO) {
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        messageRepo.insertMessage(finalMessage)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isSending = false) }
                throw error
            } catch (error: Exception) {
                val terminalFailed = shouldMarkOutboxFailed(error)
                val next = if (terminalFailed) {
                    optimistic.copy(status = MessageStatus.FAILED)
                } else {
                    optimistic.copy(status = MessageStatus.SENDING)
                }
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == msgId) next else it },
                        isSending = false,
                        groupEncryptionWarning = if (terminalFailed) {
                            error.message?.take(120) ?: text(R.string.chat_send_failed)
                        } else state.groupEncryptionWarning
                    )
                }
                try {
                    withContext(Dispatchers.IO) {
                        messageRepo.insertMessage(next)
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // 持久化失败状态失败不影响 UI 已展示的 FAILED/SENDING
                }
                if (!terminalFailed) {
                    Log.w("ChatDetailViewModel", "sendMessage transient failure keep SENDING: " + (error.message ?: "unknown"))
                }
            }
        }
    }

    private fun sendGroupTextMessage(
        text: String,
        meta: com.maodouchat.data.model.MessageMeta = com.maodouchat.data.model.MessageMeta(),
        messageType: MessageType = MessageType.TEXT
    ) {
        val sendOwnerUserId = currentUserId
        if (token.isBlank() || sendOwnerUserId.isBlank()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        // 与其他路径一致：activeChatId 为空时回退构造期 chatId，避免发出 chatId="" 的孤儿消息
        val chatId = activeChatId.ifBlank { chatId }.takeIf { it.isNotBlank() } ?: run {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        val msgId = "m_${UUID.randomUUID()}"
        val contentWithMeta = composeContentWithMeta(text, meta)
        val newMessage = Message(id = msgId, chatId = chatId, senderId = sendOwnerUserId, content = contentWithMeta, type = messageType, timestamp = System.currentTimeMillis(), status = MessageStatus.SENDING)
        clearDraft()
        _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(newMessage)), inputText = "", groupEncryptionWarning = null) }
        viewModelScope.launch {
            // 与 1:1 一致：网络前先落库 SENDING，避免杀进程丢失可重试消息
            withContext(Dispatchers.IO) {
                messageRepo.insertMessage(newMessage)
                indexSearchableMessage(newMessage)
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("group_send_session_changed")
                }
                val viaRest = withContext(Dispatchers.IO) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        throw kotlinx.coroutines.CancellationException("group_send_session_changed")
                    }
                    // Bug #25: 首次向群聊发送消息时，先分发 SenderKey 给其他成员
                    val epoch = requireGroupEpoch(chatId)
                    ensureGroupSenderKeyDistributed(chatId, epoch)
                    val wireContent = signalProtocol.encryptGroupTextEnvelope(chatId, contentWithMeta, messageType.name, epoch).getOrThrow()
                    val delivered = deliverOutgoing(
                        message = newMessage.copy(content = wireContent, chatId = chatId),
                        wireContent = wireContent,
                        typeName = messageType.name,
                        messageId = msgId,
                        chatId = chatId
                    )
                    markGroupSenderKeyMessageSent(chatId, epoch)
                    delivered
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw kotlinx.coroutines.CancellationException("group_send_session_changed")
                }
                val finalMessage = newMessage.copy(
                    status = if (viaRest) MessageStatus.SENT else MessageStatus.SENDING
                )
                _uiState.update { st ->
                    st.copy(messages = st.messages.map { m -> if (m.id == msgId) finalMessage else m })
                }
                // 1.167：发送成功后立即刷新群聊「已读 X/Y」（新消息不在缓存中）
                maybeAutoLoadLastGroupReadCount()
                withContext(Dispatchers.IO) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@withContext
                    }
                    messageRepo.insertMessage(finalMessage)
                }
                if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = sendOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    // Own group text: list shows plaintext snippet, not generic E2EE placeholder.
                    com.maodouchat.MaodouchatApp.emitMessageSent(
                        chatId,
                        text.take(200),
                        MessageType.TEXT.name
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Keep SENDING so outbox can finish after leave; never treat cancel as encrypt failure.
                throw error
            } catch (error: Exception) {
                if (shouldMarkOutboxFailed(error)) {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@launch
                    }
                    val failed = newMessage.copy(status = MessageStatus.FAILED)
                    _uiState.update { st ->
                        st.copy(
                            messages = st.messages.map { m -> if (m.id == msgId) failed else m },
                            groupEncryptionWarning = text(R.string.chat_group_e2ee_send_failed)
                        )
                    }
                    withContext(Dispatchers.IO) {
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = sendOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            messageRepo.insertMessage(failed)
                        }
                    }
                } else {
                    Log.w("ChatDetailViewModel", "group send transient failure, keep SENDING: " + (error.message ?: "unknown"))
                    // Local encrypt/session errors that are definitive already return true from shouldMarkOutboxFailed;
                    // network/5xx leave SENDING for TextOutboxFlusher.
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = sendOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        _uiState.update { st ->
                            st.copy(groupEncryptionWarning = text(R.string.chat_group_e2ee_send_failed))
                        }
                    }
                }
            }
        }
    }

    /**
     * Bug #25: 确保群聊 SenderKey 已分发给其他成员
     * 首次向群聊发送消息时调用，创建并发送 SenderKeyDistributionMessage
     */
    private suspend fun ensureGroupSenderKeyDistributed(groupId: String, epoch: Long? = null) {
        val resolved = epoch ?: requireGroupEpoch(groupId)
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_key_distributing, resolved)) }
        app.senderKeyRetryManager.ensureCoverageNow(groupId, resolved).fold(
            onSuccess = { _uiState.update { it.copy(groupEncryptionWarning = null) } },
            onFailure = { error ->
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_key_distribution_failed, resolved)) }
                Log.w("ChatDetailViewModel", "Failed to distribute group SenderKey: ${error.message}")
                throw error
            }
        )
    }

    private suspend fun markGroupSenderKeyMessageSent(groupId: String, epoch: Long? = null) {
        signalProtocol.markGroupSenderKeyMessageSent(groupId, epoch ?: requireGroupEpoch(groupId))
    }

    /**
     * Bug #25: 确保群聊 SenderKey 已分发，然后加密内容
     */
    private suspend fun ensureGroupSenderKeyDistributedThenEncrypt(
        groupId: String, plaintext: String, payloadType: String
    ): String {
        val epoch = requireGroupEpoch(groupId)
        ensureGroupSenderKeyDistributed(groupId, epoch)
        return signalProtocol.encryptGroupContentEnvelope(groupId, plaintext, payloadType, epoch).getOrThrow()
    }

    /**
     * UI 状态优先，否则读本地 chat 缓存。
     * 未知时返回 null——绝不能默认 0：0 会把合法 SK 消息全部判成 FutureEpoch，
     * 也会让 SenderKey 按 epoch=0 错误分发。
     */
    private suspend fun currentGroupEpoch(groupId: String): Long? {
        val live = _uiState.value.chat
        // memberRevision <= 0 表示未知（本地默认/未同步），不得当合法 epoch
        if (live?.isGroup == true && live.id == groupId && live.memberRevision > 0L) {
            return live.memberRevision
        }
        val cached = withContext(Dispatchers.IO) { chatRepo.getChatById(groupId) }
        return if (cached?.isGroup == true && cached.memberRevision > 0L) cached.memberRevision else null
    }

    private suspend fun requireGroupEpoch(groupId: String): Long {
        currentGroupEpoch(groupId)?.let { return it }
        // 缓存未知时拉一次会话列表，避免永远卡在 0
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            throw IllegalStateException(text(R.string.error_session_expired))
        }
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val live = withContext(Dispatchers.IO) {
            ApiService.getChats(liveToken).getOrNull()?.firstOrNull { it.id == groupId }
        }
        val rev = live?.takeIf { it.isGroup }?.memberRevision
        if (rev != null && rev > 0L) return rev
        throw IllegalStateException("group_epoch_unknown:$groupId")
    }

    /**
     * @return true when the distribution was installed, intentionally not for this device,
     * or permanently unusable (stale epoch / bad format) so the sync cursor may advance;
     * false when processing failed and the cursor must not advance past this message.
     */
    private suspend fun processIncomingSenderKeyDistribution(senderId: String, message: Message): Boolean {
        val epoch = currentGroupEpoch(message.chatId)
        if (message.content.isSenderKeyDistribution()) {
            return when (
                signalProtocol.processSenderKeyDistributionEnvelope(
                    senderId,
                    message.content,
                    expectedGroupId = message.chatId,
                    currentEpoch = epoch
                )
            ) {
                com.maodouchat.crypto.SenderKeyDistOutcome.Installed -> true
                // 过期/错误群/坏格式：永久不可用，必须推进游标，否则 backlog 卡死
                com.maodouchat.crypto.SenderKeyDistOutcome.Skipped -> true
                com.maodouchat.crypto.SenderKeyDistOutcome.Failed -> {
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_key_unusable)) }
                    false
                }
            }
        }
        if (message.type == MessageType.SK_DIST && signalProtocol.isEncryptedEnvelope(message.content)) {
            return when (val distResult = signalProtocol.decryptContentEnvelope(senderId, message.content)) {
                is SignalProtocol.DecryptResult.Success -> {
                    when (
                        signalProtocol.processSenderKeyDistributionEnvelope(
                            senderId,
                            distResult.plaintext,
                            expectedGroupId = message.chatId,
                            currentEpoch = epoch
                        )
                    ) {
                        com.maodouchat.crypto.SenderKeyDistOutcome.Installed -> true
                        com.maodouchat.crypto.SenderKeyDistOutcome.Skipped -> true
                        com.maodouchat.crypto.SenderKeyDistOutcome.Failed -> {
                            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_key_unusable)) }
                            false
                        }
                    }
                }
                SignalProtocol.DecryptResult.NotForThisDevice -> true
                SignalProtocol.DecryptResult.NoSession -> {
                    signalProtocol.ensureSession(token, senderId).getOrNull()
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_key_session_missing)) }
                    false
                }
                SignalProtocol.DecryptResult.UntrustedIdentity -> {
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_identity_changed)) }
                    false
                }
                SignalProtocol.DecryptResult.Duplicate -> true
                else -> {
                    _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_key_decrypt_failed)) }
                    false
                }
            }
        }
        return true
    }

    internal suspend fun decryptIncomingMessage(senderId: String, message: Message): Message? {
        if (message.type == MessageType.NUDGE) return message
        // Re-open / re-sync: wire content is ciphertext. If we already hold readable
        // plaintext for the same id+revision, skip re-decrypt — double-decrypt advances
        // the ratchet and can permanently corrupt session state.
        if (message.type.isDecryptable() &&
            (signalProtocol.isEncryptedEnvelope(message.content) || message.content.isSenderKeyMessage())
        ) {
            localReadableMessage(message)?.let { return it }
        }
        if (_uiState.value.chat?.isGroup == true) {
            val epoch = currentGroupEpoch(message.chatId)
            if (message.content.isSenderKeyDistribution()) {
                processIncomingSenderKeyDistribution(senderId, message)
                return null
            }
            if (message.type == MessageType.SK_DIST && signalProtocol.isEncryptedEnvelope(message.content)) {
                processIncomingSenderKeyDistribution(senderId, message)
                return null
            }
            if (!message.type.isDecryptable()) return message
            if (message.content.isSenderKeyMessage()) {
                return when (val result = signalProtocol.decryptGroupContentEnvelope(senderId, message.content, expectedGroupId = message.chatId, currentEpoch = epoch)) {
                    is SignalProtocol.DecryptResult.Success -> if (message.type in setOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION)) message.copy(content = result.plaintext) else restoreDecryptedMediaMessage(message, result.plaintext)
                    SignalProtocol.DecryptResult.NotForThisDevice -> localPlainOwnMessage(message)
                    SignalProtocol.DecryptResult.FutureEpoch -> {
                        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_member_state_updated)) }
                        message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_group_newer) else message.mediaDecryptFailedText())
                    }
                    SignalProtocol.DecryptResult.NoSession -> { signalProtocol.ensureSession(token, senderId).getOrNull(); localPlainOwnMessage(message) ?: message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_group_key_missing) else message.mediaDecryptFailedText()) }
                    SignalProtocol.DecryptResult.UntrustedIdentity -> { localPlainOwnMessage(message) ?: message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_group_identity_changed) else message.mediaDecryptFailedText()) }
                    SignalProtocol.DecryptResult.Duplicate -> localPlainOwnMessage(message) ?: message
                    else -> localPlainOwnMessage(message) ?: message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_group_failed) else message.mediaDecryptFailedText())
                }
            }
            return message
        }
        if (message.type.isDecryptable() && signalProtocol.isEncryptedEnvelope(message.content)) {
            return when (val result = signalProtocol.decryptContentEnvelope(senderId, message.content)) {
                is SignalProtocol.DecryptResult.Success -> { if (message.type in setOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION)) message.copy(content = result.plaintext) else restoreDecryptedMediaMessage(message, result.plaintext) }
                SignalProtocol.DecryptResult.NotForThisDevice -> localPlainOwnMessage(message)
                SignalProtocol.DecryptResult.NoSession -> { signalProtocol.ensureSession(token, senderId).getOrNull(); localPlainOwnMessage(message) ?: message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_session_missing) else message.mediaDecryptFailedText()) }
                SignalProtocol.DecryptResult.UntrustedIdentity -> { localPlainOwnMessage(message) ?: message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_identity_changed) else message.mediaDecryptFailedText()) }
                SignalProtocol.DecryptResult.Duplicate -> localPlainOwnMessage(message) ?: message
                else -> localPlainOwnMessage(message) ?: message.copy(content = if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) text(R.string.chat_decrypt_failed) else message.mediaDecryptFailedText())
            }
        }
        return message
    }

    /**
     * Prefer already-decrypted local/UI content for the same message revision.
     * Applies to own and peer messages (unlike [localPlainOwnMessage]).
     */
    private suspend fun localReadableMessage(message: Message): Message? {
        fun Message.isUsablePlaintext(): Boolean {
            if (content.isBlank()) return false
            if (signalProtocol.isEncryptedEnvelope(content) || content.isSenderKeyMessage()) return false
            val lower = content.lowercase()
            if (lower.contains("解密") || lower.contains("decrypt") ||
                (lower.contains("密钥") && (lower.contains("缺失") || lower.contains("失败")))
            ) {
                return false
            }
            return true
        }
        fun Message.sameRevisionAs(wire: Message): Boolean {
            val localEdit = editedAt ?: Long.MIN_VALUE
            val wireEdit = wire.editedAt ?: Long.MIN_VALUE
            return localEdit >= wireEdit
        }
        val current = _uiState.value.messages.firstOrNull { it.id == message.id }
        if (current != null && current.isUsablePlaintext() && current.sameRevisionAs(message)) {
            return current.copy(
                editedAt = message.editedAt ?: current.editedAt,
                // Wire/server snapshot is authoritative for star/reactions (unstar + clear).
                starred = message.starred,
                reactions = message.reactions,
                status = message.status.takeIf { it != MessageStatus.SENDING } ?: current.status
            )
        }
        val cached = messageRepo.getMessageById(message.id) ?: return null
        if (!cached.isUsablePlaintext() || !cached.sameRevisionAs(message)) return null
        return cached.copy(
            editedAt = message.editedAt ?: cached.editedAt,
            starred = message.starred,
            reactions = message.reactions,
            status = message.status.takeIf { it != MessageStatus.SENDING } ?: cached.status
        )
    }

    private fun restoreDecryptedMediaMessage(message: Message, plaintext: String): Message {
        val restored = MediaCache.restoreDecryptedMedia(
            getApplication(),
            plaintext,
            message.id,
            message.type,
            secretChatId = if (_uiState.value.isSecretChat == true) message.chatId else null
        )
            ?: return message.copy(content = message.mediaDecryptFailedText())
        val metadata = restored.fileMetadata ?: return message.copy(content = restored.uri)
        val reference = restored.attachmentReference
        val meta = message.parsedMeta().copy(
            fileName = metadata.fileName,
            fileMimeType = metadata.mimeType,
            fileSizeBytes = metadata.sizeBytes,
            attachmentId = reference?.attachmentId,
            attachmentKeyBase64 = reference?.keyBase64,
            attachmentIvBase64 = reference?.ivBase64,
            attachmentCipherSha256 = reference?.cipherSha256,
            attachmentPlainSha256 = reference?.plainSha256,
            attachmentCipherSize = reference?.cipherSize,
            voiceDurationMs = reference?.durationMs ?: message.parsedMeta().voiceDurationMs
        )
        return message.copy(content = composeContentWithMeta(restored.uri, meta), meta = meta)
    }

    internal suspend fun ensureLocalAttachment(message: Message): Result<Message> {
        val mutex = attachmentDownloadMutexes.computeIfAbsent(message.id) { Mutex() }
        return mutex.withLock { ensureLocalAttachmentLocked(message) }
    }

    private suspend fun ensureLocalAttachmentLocked(message: Message): Result<Message> {
        if (MediaCache.isReadableLocalUri(getApplication(), message.parsedContent())) return Result.success(message)
        val reference = message.toEncryptedAttachmentReference()
            ?: return Result.failure(IllegalStateException(text(R.string.chat_attachment_reference_invalid)))
        return try {
            val target = MediaCache.createAttachmentCacheFile(
                getApplication(),
                message.id,
                reference.fileName,
                secretChatId = if (_uiState.value.isSecretChat == true) message.chatId else null
            )
            val downloadUserId = tokenManager.getUserId().orEmpty()
            if (!EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference)) {
                val encrypted = MediaCache.createEncryptedDownloadFile(getApplication(), reference.attachmentId, message.id)
                if (downloadUserId.isBlank() || token.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = downloadUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    throw IllegalStateException(text(R.string.error_session_expired))
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.downloadEncryptedAttachment(
                    token = liveToken,
                    attachmentId = reference.attachmentId,
                    expectedSha256 = reference.cipherSha256,
                    expectedSize = reference.cipherSize,
                    target = encrypted
                ) { completed, total ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = downloadUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        throw kotlinx.coroutines.CancellationException("attachment_download_session_changed")
                    }
                    updateFileTransferProgress(message.id, completed, total, 0f, 0.7f)
                }
                    .getOrThrow()
                try {
                    EncryptedAttachmentCrypto.decrypt(encrypted, target, reference) { completed, total ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = downloadUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            throw kotlinx.coroutines.CancellationException("attachment_decrypt_session_changed")
                        }
                        updateFileTransferProgress(message.id, completed, total, 0.7f, 1f)
                    }
                } finally {
                    encrypted.delete()
                }
            }
            // Download/decrypt (or cache hit after switch) must not bind path into next owner's UI/Room.
            if (downloadUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = downloadUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                throw kotlinx.coroutines.CancellationException("attachment_apply_session_changed")
            }
            val meta = message.parsedMeta()
            val updated = message.copy(content = composeContentWithMeta(Uri.fromFile(target).toString(), meta), meta = meta)
            _uiState.update { state ->
                state.copy(messages = state.messages.map { current -> if (current.id == message.id) updated else current })
            }
            messageRepo.insertMessage(updated)
            Result.success(updated)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun Message.toEncryptedAttachmentReference(): MediaCache.EncryptedAttachmentReference? {
        val metadata = parsedMeta()
        val reference = MediaCache.EncryptedAttachmentReference(
            attachmentId = metadata.attachmentId ?: return null,
            keyBase64 = metadata.attachmentKeyBase64 ?: return null,
            ivBase64 = metadata.attachmentIvBase64 ?: return null,
            cipherSha256 = metadata.attachmentCipherSha256 ?: return null,
            plainSha256 = metadata.attachmentPlainSha256 ?: return null,
            cipherSize = metadata.attachmentCipherSize ?: return null,
            fileName = metadata.fileName ?: return null,
            mimeType = metadata.fileMimeType ?: "application/octet-stream",
            plainSize = metadata.fileSizeBytes ?: return null,
            durationMs = metadata.voiceDurationMs
        )
        return runCatching {
            MediaCache.decodeEncryptedAttachmentReference(MediaCache.encodeEncryptedAttachmentReference(reference))
        }.getOrNull()
    }

    private suspend fun localPlainOwnMessage(message: Message): Message? {
        if (message.senderId != currentUserId) return null
        val current = _uiState.value.messages.firstOrNull { it.id == message.id }
        if (current != null && !signalProtocol.isEncryptedEnvelope(current.content) && !current.content.isSenderKeyMessage()) {
            return current.copy(
                editedAt = message.editedAt ?: current.editedAt,
                starred = message.starred,
                reactions = message.reactions
            )
        }
        val cached = messageRepo.getMessageById(message.id) ?: return null
        if (signalProtocol.isEncryptedEnvelope(cached.content) || cached.content.isSenderKeyMessage()) return null
        return cached.copy(
            editedAt = message.editedAt ?: cached.editedAt,
            starred = message.starred,
            reactions = message.reactions
        )
    }

    fun startRecording() {
        val recordOwnerUserId = currentUserId
        if (
            token.isBlank() ||
            recordOwnerUserId.isBlank() ||
            recordOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = recordOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            _uiState.update {
                it.copy(
                    isRecording = false,
                    recordingAmplitude = 0f,
                    recordingElapsedMs = 0L,
                    recordingWaveform = emptyList(),
                    groupEncryptionWarning = text(R.string.error_session_expired)
                )
            }
            return
        }
        // 新录音前丢弃未发送试听，避免双文件
        discardVoicePreviewInternal(deleteFile = true)
        runCatching { voiceRecorder.startRecording() }
            .onSuccess {
                recordingWaveformBuffer.clear()
                _uiState.update {
                    it.copy(
                        isRecording = true,
                        recordingAmplitude = 0f,
                        recordingElapsedMs = 0L,
                        recordingWaveform = emptyList(),
                        voicePreviewPath = null,
                        voicePreviewDurationMs = 0L,
                        groupEncryptionWarning = null,
                    )
                }
                startRecordingMeter()
            }
            .onFailure { error ->
                Log.w("ChatDetailViewModel", "startRecording failed", error)
                stopRecordingMeter()
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        recordingAmplitude = 0f,
                        recordingElapsedMs = 0L,
                        recordingWaveform = emptyList(),
                        groupEncryptionWarning = text(R.string.chat_permission_record)
                    )
                }
            }
    }

    private fun startRecordingMeter() {
        recordingMeterJob?.cancel()
        recordingMeterJob = viewModelScope.launch {
            while (isActive && voiceRecorder.isRecording) {
                val amp = voiceRecorder.amplitude()
                val elapsed = voiceRecorder.elapsedMs()
                recordingWaveformBuffer.push(amp)
                val snap = recordingWaveformBuffer.snapshot().toList()
                _uiState.update {
                    it.copy(
                        recordingAmplitude = amp,
                        recordingElapsedMs = elapsed,
                        recordingWaveform = snap,
                    )
                }
                delay(50)
            }
        }
    }

    private fun stopRecordingMeter() {
        recordingMeterJob?.cancel()
        recordingMeterJob = null
    }

    /**
     * 导出当前聊天的全部消息为 JSON 字符串（本地可读明文 content 快照，非服务端密文）。
     */
    fun exportChatAsJson(): String {
        val exportOwnerUserId = currentUserId
        if (
            token.isBlank() ||
            exportOwnerUserId.isBlank() ||
            exportOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = exportOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return "{}"
        }
        val state = _uiState.value
        // Secret chats: refuse plaintext export snapshot (defense-in-depth for any caller).
        if (state.isSecretChat == true) {
            return "{}"
        }
        val chat = state.chat ?: return "{}"
        // 8.34 修复：导出只含当前已加载的分页窗口（pageLimit=200），此前静默宣称完整。
        // 显式标记 partial，避免用户误以为备份完整。
        val partial = state.hasMoreOlderMessages
        val data = mapOf(
            "chatId" to chat.id,
            "isGroup" to chat.isGroup,
            "groupName" to chat.groupName,
            "exportedAt" to System.currentTimeMillis(),
            "messageCount" to state.messages.size,
            "partial" to partial,
            "messages" to state.messages.map {
                mapOf(
                    "id" to it.id,
                    "sender" to it.senderId,
                    "type" to it.type.name,
                    "timestamp" to it.timestamp,
                    "status" to it.status.name,
                    "content" to it.content
                )
            }
        )
        return com.maodouchat.util.JsonFormat.encode(data)
    }

    fun clearExportInfo() {
        _uiState.update { it.copy(exportInfoMessage = null) }
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

    fun refreshSecretChatState() {
        val targetChatId = activeChatId.ifBlank { chatId }
        if (targetChatId.isBlank()) {
            _uiState.update { it.copy(isSecretChat = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val secret = runCatching { secretChatRepo.isSecret(targetChatId) }.getOrDefault(false)
            if (secret) {
                com.maodouchat.security.SecretChatSession.markSurfaceActive(targetChatId)
            } else {
                com.maodouchat.security.SecretChatSession.markSurfaceInactive(targetChatId, getApplication())
            }
            _uiState.update { it.copy(isSecretChat = secret) }
        }
    }

    fun setSecretChatEnabled(enabled: Boolean) {
        if (enabled && !RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_CHAT)) {
            _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_feature_disabled)) }
            return
        }
        val targetChatId = activeChatId.ifBlank { chatId }
        if (targetChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                secretChatRepo.enable(targetChatId)
                com.maodouchat.security.SecretChatSession.markSurfaceActive(targetChatId)
                // Privacy default: if disappearing is off, turn on 24h timer for secret 1:1.
                val chat = _uiState.value.chat
                val currentTimer = chat?.disappearingMessageSeconds
                    ?: com.maodouchat.util.DisappearingMessagePolicy.OFF_SECONDS
                if (
                    RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_AUTO_DISAPPEAR) &&
                    chat != null && !chat.isGroup && currentTimer <= 0
                ) {
                    withContext(Dispatchers.Main) {
                        setDisappearingMessages(24 * 60 * 60)
                    }
                }
                // Prefetch sealed-sender cert for upcoming outbound messages.
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                var sealedReady = false
                var sealedTtl = 0L
                val ownerUserId = tokenManager.getUserId().orEmpty()
                val ownerDeviceId = signalProtocol.getDeviceId()
                if (
                    liveToken.isNotBlank() && ownerUserId.isNotBlank() &&
                    RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SEALED_SENDER)
                ) {
                    sealedReady = runCatching {
                        com.maodouchat.crypto.SealedSenderSupport.fetchCertificate(
                            liveToken,
                            ownerUserId,
                            ownerDeviceId
                        ).getOrNull()?.certificate?.isNotBlank() == true
                    }.getOrDefault(false)
                    if (sealedReady) {
                        sealedTtl = com.maodouchat.crypto.SealedSenderSupport.secondsUntilExpiry(
                            ownerUserId,
                            ownerDeviceId
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        isSecretChat = true,
                        sealedSenderReady = sealedReady,
                        sealedSenderExpiresInSec = sealedTtl,
                        secretChatInfoMessage = text(
                            if (sealedReady) R.string.secret_chat_enabled_sealed
                            else R.string.secret_chat_enabled
                        )
                    )
                }
                return@launch
            } else {
                secretChatRepo.disable(targetChatId)
                com.maodouchat.security.SecretChatSession.markSurfaceInactive(targetChatId, getApplication())
                val ownerUserId = tokenManager.getUserId().orEmpty()
                if (ownerUserId.isNotBlank()) {
                    com.maodouchat.crypto.SealedSenderSupport.clearCache(
                        ownerUserId,
                        signalProtocol.getDeviceId()
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isSecretChat = enabled,
                    sealedSenderReady = if (enabled) it.sealedSenderReady else false,
                    sealedSenderExpiresInSec = if (enabled) it.sealedSenderExpiresInSec else 0L,
                    secretChatInfoMessage = text(
                        if (enabled) R.string.secret_chat_enabled else R.string.secret_chat_disabled
                    )
                )
            }
        }
    }

    fun refreshChatLockState() {
        val lockChatId = activeChatId.ifBlank { chatId }
        if (lockChatId.isBlank()) {
            _uiState.update { it.copy(isChatLocked = false, isChatUnlocked = true) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val locked = try { chatLockRepo.get(lockChatId) != null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { false }
            val processUnlocked = com.maodouchat.security.ChatLockSession.isUnlocked(lockChatId)
            _uiState.update {
                it.copy(
                    isChatLocked = locked,
                    // Process-scoped unlock survives detail→media navigation; no lock → always open.
                    isChatUnlocked = if (locked) (it.isChatUnlocked || processUnlocked) else true
                )
            }
        }
    }

    /**
     * 校验 PIN。成功返回 true 并标记本进程已解锁。
     * 同步读 Room 短路径（单行 + SHA-256），供门闩回调即时反馈。
     *
     * 注意：此函数在主线程调用并使用 runBlocking(Dispatchers.IO) 同步等待 Room 查询。
     * UI 调用方依赖 Boolean 返回值做即时门闩判断，改为 suspend 会破坏调用链。
     * 当前仅单行 SELECT + SHA-256，阻塞窗口极小，保守保留同步实现。
     * 若后续 Room 查询变复杂或出现卡顿，应考虑重构为异步回调。
     */
    fun unlockChatWithPin(pin: String): Boolean {
        val lockChatId = activeChatId.ifBlank { chatId }
        if (lockChatId.isBlank()) return true
        val ok = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                chatLockRepo.verify(lockChatId, pin)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) {
            com.maodouchat.security.ChatLockSession.markUnlocked(lockChatId)
            _uiState.update { it.copy(isChatUnlocked = true) }
        }
        return ok
    }

    fun setChatLockPin(pin: String) {
        val lockChatId = activeChatId.ifBlank { chatId }
        if (lockChatId.isBlank()) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_LOCK)) {
            _uiState.update { it.copy(chatLockInfoMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
            _uiState.update { it.copy(chatLockInfoMessage = text(R.string.chat_lock_pin_length)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = chatLockRepo.setLock(lockChatId, pin)
            if (ok) {
                com.maodouchat.security.ChatLockSession.markUnlocked(lockChatId)
                _uiState.update {
                    it.copy(
                        isChatLocked = true,
                        isChatUnlocked = true,
                        chatLockInfoMessage = text(R.string.chat_lock_enabled)
                    )
                }
            } else {
                _uiState.update { it.copy(chatLockInfoMessage = text(R.string.chat_lock_pin_length)) }
            }
        }
    }

    fun removeChatLock(pin: String) {
        val lockChatId = activeChatId.ifBlank { chatId }
        if (lockChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = chatLockRepo.verify(lockChatId, pin)
            if (!ok) {
                _uiState.update { it.copy(chatLockInfoMessage = text(R.string.chat_lock_wrong_pin)) }
                return@launch
            }
            chatLockRepo.remove(lockChatId)
            com.maodouchat.security.ChatLockSession.clear(lockChatId)
            _uiState.update {
                it.copy(
                    isChatLocked = false,
                    isChatUnlocked = true,
                    chatLockInfoMessage = text(R.string.chat_lock_disabled)
                )
            }
        }
    }

    /**
     * 忘记 PIN：清除本会话本地消息与锁（不删服务端历史；重新拉取可恢复密文）。
     */
    fun forgotChatLockAndClearLocal() {
        val lockChatId = activeChatId.ifBlank { chatId }
        if (lockChatId.isBlank()) return
        val ownerUserId = currentUserId
        viewModelScope.launch(Dispatchers.IO) {
            if (
                ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            clearLocalChatContent(lockChatId, removePin = true)
            tokenManager.clearChatCursors(lockChatId)
            runCatching {
                val local = chatRepo.getChatById(lockChatId)
                if (local != null) {
                    chatRepo.cacheChats(
                        listOf(
                            local.copy(
                                lastMessage = "",
                                lastMessageType = MessageType.TEXT,
                                unreadCount = 0,
                                markedUnread = false
                            )
                        )
                    )
                }
            }
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    pinnedMessages = emptyList(),
                    scheduledMessages = emptyList(),
                    chat = it.chat?.copy(
                        lastMessage = "",
                        lastMessageType = MessageType.TEXT,
                        unreadCount = 0,
                        markedUnread = false
                    ),
                    isChatLocked = false,
                    isChatUnlocked = true,
                    chatLockInfoMessage = text(R.string.chat_lock_cleared_local)
                )
            }
        }
    }

    /**
     * 清除本会话本地消息/索引/媒体缓存与待发定时（保留会话、PIN、草稿）。
     * 服务端密文仍在，重新打开可能再次同步。
     */
    fun clearLocalChatHistory() {
        val targetChatId = activeChatId.ifBlank { chatId }
        if (targetChatId.isBlank()) return
        val ownerUserId = currentUserId
        viewModelScope.launch(Dispatchers.IO) {
            if (
                ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            clearLocalChatContent(targetChatId, removePin = false)
            tokenManager.clearChatCursors(targetChatId)
            runCatching {
                val local = chatRepo.getChatById(targetChatId)
                if (local != null) {
                    chatRepo.cacheChats(
                        listOf(
                            local.copy(
                                lastMessage = "",
                                lastMessageType = MessageType.TEXT,
                                unreadCount = 0,
                                markedUnread = false
                            )
                        )
                    )
                }
            }
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    pinnedMessages = emptyList(),
                    scheduledMessages = emptyList(),
                    chat = it.chat?.copy(
                        lastMessage = "",
                        lastMessageType = MessageType.TEXT,
                        unreadCount = 0,
                        markedUnread = false
                    ),
                    groupEncryptionWarning = text(R.string.chat_clear_history_done)
                )
            }
        }
    }

    private suspend fun clearLocalChatContent(targetChatId: String, removePin: Boolean) {
        val ownerUserId = currentUserId
        val cachedMessageIds = runCatching { messageRepo.getMessageIdsByChatId(targetChatId) }.getOrDefault(emptyList())
        runCatching {
            com.maodouchat.attachment.AttachmentTransferCoordinator.cancelForChat(app, targetChatId)
        }
        runCatching {
            val removed = com.maodouchat.util.ScheduledMessageStore.clearForChat(app, targetChatId)
            removed.forEach { com.maodouchat.util.ScheduledMessageScheduler.cancel(app, it) }
        }
        if (removePin) {
            runCatching { chatLockRepo.remove(targetChatId) }
            com.maodouchat.security.ChatLockSession.clear(targetChatId)
        }
        runCatching { messageRepo.deleteMessagesByChatId(targetChatId) }
        runCatching { app.database.messageSearchDao().deleteChatIndex(targetChatId) }
        if (ownerUserId.isNotBlank()) {
            runCatching {
                app.database.attachmentTransferDao().clearWireContentForChat(
                    targetChatId,
                    ownerUserId = ownerUserId
                )
            }
        }
        cachedMessageIds.forEach { messageId ->
            runCatching {
                com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, messageId)
            }
        }
        runCatching {
            app.notificationCenter.removeChatItems(targetChatId)
            com.maodouchat.util.AppNotifier.cancelMessage(app, targetChatId)
        }
    }

    fun exportToUri(context: android.content.Context, uri: android.net.Uri) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_EXPORT)) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_export_disabled)) }
            return
        }
        val exportOwnerUserId = currentUserId
        if (
            token.isBlank() ||
            exportOwnerUserId.isBlank() ||
            exportOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = exportOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.error_session_expired)) }
            return
        }
        val state = _uiState.value
        if (state.isChatLocked == true && !state.isChatUnlocked) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_lock_list_preview)) }
            return
        }
        if (state.isSecretChat == true && RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_CHAT_EXPORT_BLOCK)) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.secret_chat_export_blocked)) }
            return
        }
        if (state.chat == null) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_export_failed)) }
            return
        }
        if (state.messages.isEmpty()) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_export_empty)) }
            return
        }
        val json = exportChatAsJson()
        if (json == "{}" || json.isBlank()) {
            _uiState.update { it.copy(exportInfoMessage = text(R.string.chat_export_failed)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = exportOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                        it.copy(exportInfoMessage = text(R.string.chat_export_success, state.messages.size))
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
    /**
     * 松手结束录音 → 进入发送前试听（不再直接发送）。
     * 过短则丢弃并提示。兼容旧调用名 [stopRecordingAndSend]。
     */
    fun stopRecordingAndSend() = stopRecordingToPreview()

    fun stopRecordingToPreview() {
        stopRecordingMeter()
        val result = voiceRecorder.stopRecording()
        _uiState.update {
            it.copy(
                isRecording = false,
                recordingAmplitude = 0f,
                recordingElapsedMs = 0L,
            )
        }
        if (result == null) {
            recordingWaveformBuffer.clear()
            _uiState.update { it.copy(recordingWaveform = emptyList()) }
            return
        }
        val (filePath, duration) = result
        val source = File(filePath)
        if (!VoiceCapturePolicy.canEnterPreview(duration) ||
            duration > MediaCache.MAX_VOICE_DURATION_MS
        ) {
            source.delete()
            recordingWaveformBuffer.clear()
            _uiState.update {
                it.copy(
                    recordingWaveform = emptyList(),
                    voicePreviewPath = null,
                    voicePreviewDurationMs = 0L,
                    groupEncryptionWarning = text(R.string.chat_voice_duration_invalid),
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                voicePreviewPath = filePath,
                voicePreviewDurationMs = duration,
                groupEncryptionWarning = null,
            )
        }
    }

    fun playVoicePreview() {
        val path = _uiState.value.voicePreviewPath ?: return
        VoicePlayer.ensureContext(getApplication())
        VoicePlayer.play(VOICE_PREVIEW_MESSAGE_ID, path, getApplication())
    }

    fun discardVoicePreview() {
        if (VoicePlayer.state.value.messageId == VOICE_PREVIEW_MESSAGE_ID) {
            VoicePlayer.stop()
        }
        discardVoicePreviewInternal(deleteFile = true)
    }

    private fun discardVoicePreviewInternal(deleteFile: Boolean) {
        val path = _uiState.value.voicePreviewPath
        if (deleteFile && path != null) {
            runCatching { File(path).delete() }
        }
        recordingWaveformBuffer.clear()
        _uiState.update {
            it.copy(
                voicePreviewPath = null,
                voicePreviewDurationMs = 0L,
                recordingWaveform = emptyList(),
                recordingAmplitude = 0f,
                recordingElapsedMs = 0L,
            )
        }
    }

    /** 试听确认后走加密附件发送。 */
    fun sendVoicePreview() {
        if (!requireVoiceMessages()) return
        val path = _uiState.value.voicePreviewPath
        val duration = _uiState.value.voicePreviewDurationMs
        if (path.isNullOrBlank() || !VoiceCapturePolicy.canSendPreview(duration)) {
            discardVoicePreviewInternal(deleteFile = true)
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_voice_duration_invalid)) }
            return
        }
        if (VoicePlayer.state.value.messageId == VOICE_PREVIEW_MESSAGE_ID) {
            VoicePlayer.stop()
        }
        val source = File(path)
        // 先清 UI 预览引用，文件由发送路径接管或删除
        _uiState.update {
            it.copy(voicePreviewPath = null, voicePreviewDurationMs = 0L)
        }
        val voiceOwnerUserId = currentUserId
        if (
            token.isBlank() ||
            voiceOwnerUserId.isBlank() ||
            voiceOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = voiceOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            source.delete()
            _uiState.update {
                it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
            }
            return
        }
        if (duration !in 500L..MediaCache.MAX_VOICE_DURATION_MS) {
            source.delete()
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_voice_duration_invalid)) }
            return
        }
        val messageId = "m_${UUID.randomUUID()}"
        val target = MediaCache.createPreparedAttachmentSource(getApplication(), messageId, ".m4a")
        _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = voiceOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    withContext(NonCancellable) {
                        source.delete()
                        target.delete()
                    }
                    _uiState.update {
                        it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                val prepared = withContext(Dispatchers.IO) {
                    try {
                        if (!source.renameTo(target)) {
                            source.copyTo(target, overwrite = true)
                            check(source.delete()) { "voice_source_cleanup_failed" }
                        }
                        target
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                if (prepared == null) {
                    withContext(Dispatchers.IO) {
                        source.delete()
                        target.delete()
                    }
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            groupEncryptionWarning = text(R.string.chat_voice_cache_missing)
                        )
                    }
                    return@launch
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = voiceOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    withContext(NonCancellable) {
                        prepared.delete()
                    }
                    _uiState.update {
                        it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                sendEncryptedAttachment(
                    uri = Uri.fromFile(prepared),
                    type = MessageType.VOICE,
                    fixedMessageId = messageId,
                    voiceDurationMs = duration
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    source.delete()
                    target.delete()
                }
                _uiState.update { it.copy(isSending = false) }
                throw error
            }
        }
    }

    fun cancelRecording() {
        stopRecordingMeter()
        voiceRecorder.cancelRecording()
        recordingWaveformBuffer.clear()
        _uiState.update {
            it.copy(
                isRecording = false,
                recordingAmplitude = 0f,
                recordingElapsedMs = 0L,
                recordingWaveform = emptyList(),
            )
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
    private suspend fun cleanupAttachmentForMessage(messageId: String) {
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
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        stopTypingAnnouncement()
        remoteTypingCoordinator.clear()
        val draftOwnerUserId = tokenManager.getUserId().orEmpty()
        val draftChatId = activeChatId
        val draftText = _uiState.value.inputText
        // 捕获 chatDraftDao 到局部变量，避免 lambda 闭包捕获 this（ViewModel），
        // 从而防止 ViewModel 被 applicationScope 中的挂起引用阻止 GC 回收。
        val draftDao = chatDraftDao
        val tokenMgr = tokenManager
        if (draftOwnerUserId.isNotBlank() && draftChatId.isNotBlank()) {
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                withContext(NonCancellable) {
                    // Soft-purge/logout may destroy Room or switch owner before this runs.
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = draftOwnerUserId,
                            liveToken = tokenMgr.getToken(),
                            liveUserId = tokenMgr.getUserId(),
                        )
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
        readMessagesTracker.clear()
        pendingServerReadIds.clear()
        markReadJob?.cancel()
        markReadJob = null
        // 清除全局活跃聊天标记，ChatListViewModel 恢复正常未读数递增。
        // Only clear if we still own the marker — navigating A→B can create B
        // before A.onCleared runs; unconditional null would suppress B's unread skip.
        val currentToken = token
        val currentChatId = activeChatId
        if (com.maodouchat.MaodouchatApp.activeChatId == currentChatId) {
            com.maodouchat.MaodouchatApp.activeChatId = null
        }
        // 不在 onCleared 里 releaseSending：WorkManager 可能仍在 finalize，
        // 释放会让第二 worker 重 claim 并用新密文同 messageId 再发。
        val markReadOwnerUserId = tokenManager.getUserId().orEmpty()
        // 提取局部变量，避免 lambda 闭包捕获 this（ViewModel），防止 GC 被阻止。
        val markReadTokenMgr = tokenManager
        val markReadIsSecret = _uiState.value.isSecretChat == true
        val markReadSecretBlockEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_READ_RECEIPT_BLOCK)
        if (currentToken.isNotBlank() && currentChatId.isNotBlank() && markReadOwnerUserId.isNotBlank()) {
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                try {
                    withContext(NonCancellable) {
                        // Soft account switch: do not mark the prior chat read under the new JWT.
                        // Hard logout: tokens gone — skip (tray/unread cleanup handled elsewhere).
                        val liveUid = markReadTokenMgr.getUserId()
                        val liveTok = markReadTokenMgr.getToken()
                        if (liveTok.isNullOrBlank() || liveUid.isNullOrBlank()) return@withContext
                        if (liveUid != markReadOwnerUserId) return@withContext
                        // Surface #68: 密聊 read-receipt 门控
                        if (markReadIsSecret && markReadSecretBlockEnabled) {
                            return@withContext
                        }
                        ApiService.markAllAsRead(liveTok, currentChatId)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("ChatDetailViewModel", "onCleared markAllAsRead failed", error)
                }
            }
        }
        super.onCleared()
    }

    internal fun String.isSenderKeyDistribution(): Boolean = signalProtocol.isSenderKeyDistributionEnvelope(this)
    internal fun String.isSenderKeyMessage(): Boolean = signalProtocol.isSenderKeyEnvelope(this)
    internal fun MessageType.isDecryptable(): Boolean = this in setOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF, MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO, MessageType.VOICE, MessageType.FILE)
    internal fun Message.mediaDecryptFailedText(): String = when (type) {
        MessageType.IMAGE -> text(R.string.chat_decrypt_image_failed)
        MessageType.GIF -> text(R.string.chat_decrypt_gif_failed)
        MessageType.STICKER -> text(R.string.chat_decrypt_sticker_failed)
        MessageType.LOCATION -> text(R.string.chat_decrypt_location_failed)
        MessageType.VIDEO -> text(R.string.chat_decrypt_video_failed)
        MessageType.VOICE -> text(R.string.chat_decrypt_voice_failed)
        MessageType.FILE -> text(R.string.chat_decrypt_file_failed)
        else -> text(R.string.chat_decrypt_failed)
    }

    /**
     * Sync must not advance past recoverable decrypt failures (NoSession / identity / generic).
     * Placeholders are still shown in UI but the (ts,id) cursor stays so a later retry can re-fetch.
     */
    internal fun isSyncDecryptFailurePlaceholder(message: Message): Boolean {
        val c = message.content
        if (c.isBlank()) return false
        if (signalProtocol.isEncryptedEnvelope(c) || c.isSenderKeyMessage()) return true
        val lower = c.lowercase()
        return lower.contains("解密") ||
            lower.contains("decrypt") ||
            (lower.contains("密钥") && (lower.contains("缺失") || lower.contains("失败"))) ||
            (lower.contains("session") && lower.contains("missing")) ||
            lower.contains("identity") && lower.contains("changed") ||
            c == text(R.string.chat_decrypt_failed) ||
            c == text(R.string.chat_decrypt_session_missing) ||
            c == text(R.string.chat_decrypt_identity_changed) ||
            c == text(R.string.chat_decrypt_group_failed) ||
            c == text(R.string.chat_decrypt_group_key_missing) ||
            c == text(R.string.chat_decrypt_group_identity_changed) ||
            c == text(R.string.chat_decrypt_group_newer) ||
            c == mediaDecryptFailedTextForType(message.type)
    }

    internal fun mediaDecryptFailedTextForType(type: MessageType): String = when (type) {
        MessageType.IMAGE -> text(R.string.chat_decrypt_image_failed)
        MessageType.GIF -> text(R.string.chat_decrypt_gif_failed)
        MessageType.STICKER -> text(R.string.chat_decrypt_sticker_failed)
        MessageType.LOCATION -> text(R.string.chat_decrypt_location_failed)
        MessageType.VIDEO -> text(R.string.chat_decrypt_video_failed)
        MessageType.VOICE -> text(R.string.chat_decrypt_voice_failed)
        MessageType.FILE -> text(R.string.chat_decrypt_file_failed)
        else -> text(R.string.chat_decrypt_failed)
    }

    /** 合并本地备注名（服务端 UserDto 不含 nickname） */
    internal suspend fun withLocalNickname(user: User): User {
        if (!user.nickname.isNullOrBlank()) return user
        val nick = runCatching { userRepo.getUserById(user.id)?.nickname }.getOrNull()
        return if (nick.isNullOrBlank()) user else user.copy(nickname = nick)
    }
}
