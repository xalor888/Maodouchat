package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.DisappearingMessagePolicy
import com.maodouchat.core.model.WebSocketErrorKind
import com.maodouchat.core.model.WebSocketEvent
import com.maodouchat.core.model.isActiveChatEvent
import com.maodouchat.core.model.resolveUserVisibility
import com.maodouchat.core.model.shouldApplyContactPresence
import com.maodouchat.core.realtime.REMOTE_TYPING_TIMEOUT_MS
import com.maodouchat.core.realtime.RealtimeConnectionManager
import com.maodouchat.core.realtime.RealtimeDisconnectPolicy
import com.maodouchat.core.realtime.RemoteTypingCoordinator
import com.maodouchat.core.realtime.TypingSignalAction
import com.maodouchat.core.realtime.resolveTypingSignalAction
import com.maodouchat.core.session.SessionContextProvider
import com.maodouchat.core.session.TypingSessionPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 远端用户在线快照（ presence 回退轮询用）；app 侧由 UserNetworkRepository 映射而来。 */
data class UserPresenceSnapshot(
    val isOnline: Boolean,
    val lastSeen: Long,
)

// 实时连接控制器：从 ChatDetailViewModel 纯搬移，WebSocket 连接、事件路由、在线状态回退轮询与输入中宣告。
// Application/RuntimeFlags/Log/R.string/传输层/用户仓库/会话清理门禁走构造端口；其余逻辑逐字平移。
class ChatRealtimeController(
    private val scope: CoroutineScope,
    private val sessionContextProvider: SessionContextProvider,
    private val realtime: RealtimeConnectionManager,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val persistDisappearingMessages: suspend (String, Int) -> Unit,
    private val applyRealtimeVisibility: suspend (WebSocketEvent.UserOnline) -> Unit,
    private val onGroupRevisionChanged: suspend (WebSocketEvent.GroupRevisionChanged) -> Unit,
    private val text: (ChatRealtimeText) -> String,
    private val wsUrl: () -> String,
    private val presenceEnabled: () -> Boolean,
    private val secretPresenceBlocked: () -> Boolean,
    private val typingIndicatorsEnabled: () -> Boolean,
    private val secretTypingBlocked: () -> Boolean,
    private val purgeInProgress: () -> Boolean,
    private val fetchUserPresence: suspend (String, String) -> Result<UserPresenceSnapshot>,
    private val logWarning: (String, Throwable?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val remoteTypingCoordinator = RemoteTypingCoordinator(scope) { userId ->
        updateState { it.copy(typingContact = userId) }
    }
    private var typingDebounceJob: Job? = null
    private var disconnectBannerJob: Job? = null
    private var announcedTypingChatId: String? = null

    fun start() {
        connect()
        observeEvents()
        observePresenceFallbackPolling()
    }

    fun onComposerTextChanged(hasText: Boolean) {
        typingDebounceJob?.cancel()
        when (resolveTypingSignalAction(announcedTypingChatId != null, hasText)) {
            TypingSignalAction.START -> announceTypingStarted()
            TypingSignalAction.STOP -> stopTypingAnnouncement()
            TypingSignalAction.NONE -> Unit
        }
        if (hasText) {
            typingDebounceJob = scope.launch {
                delay(REMOTE_TYPING_TIMEOUT_MS)
                stopTypingAnnouncement()
            }
        }
    }

    fun clearRemoteTyping() {
        remoteTypingCoordinator.clear()
    }

    fun clear() {
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        disconnectBannerJob?.cancel()
        disconnectBannerJob = null
        stopTypingAnnouncement()
        remoteTypingCoordinator.clear()
    }

    private fun connect() {
        val owner = ownerUserId()
        if (owner.isBlank() || owner == "me" || !sessionActive(owner)) return
        val liveToken = token()
        val ownerContext = currentOwner() ?: return
        if (liveToken.isBlank()) return
        realtime.start(wsUrl(), liveToken, ownerContext)
    }

    private fun observePresenceFallbackPolling() {
        scope.launch {
            while (isActive) {
                delay(30_000L)
                val owner = ownerUserId()
                if (!sessionActive(owner) || !presenceEnabled()) continue
                val state = currentState()
                if (state.chatIsGroup || state.isSecretChat == true) continue
                if (secretPresenceBlocked() && state.isSecretChat == true) continue
                val contactId = state.contact.id.takeIf(String::isNotBlank) ?: continue
                val liveToken = token()
                if (liveToken.isBlank()) continue
                fetchUserPresence(liveToken, contactId).onSuccess { snapshot ->
                    updateState { current ->
                        if (current.contact.id != contactId || current.chatIsGroup) current
                        else current.copy(
                            contact = current.contact.copy(isOnline = snapshot.isOnline, lastSeen = snapshot.lastSeen),
                        )
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        scope.launch {
            realtime.events.collect { event ->
                if (ownerUserId().isBlank() || token().isBlank()) return@collect
                when (event) {
                    is WebSocketEvent.PinnedMessagesUpdated -> {
                        val owner = ownerUserId()
                        if (owner.isBlank() || owner == "me" || !sessionActive(owner)) return@collect
                        if (isActiveChatEvent(activeChatId(), event.chatId)) {
                            updateState { it.copy(pinnedMessages = event.pins) }
                        }
                    }
                    is WebSocketEvent.DisappearingMessagesUpdated -> {
                        val owner = ownerUserId()
                        if (owner.isBlank() || owner == "me" || !sessionActive(owner)) return@collect
                        if (!isActiveChatEvent(activeChatId(), event.chatId)) return@collect
                        val seconds = DisappearingMessagePolicy.normalizeSeconds(event.seconds)
                        updateState {
                            it.copy(
                                disappearingMessageSeconds = seconds,
                                chat = it.chat?.copy(disappearingMessageSeconds = seconds),
                            )
                        }
                        withContext(ioDispatcher) {
                            persistDisappearingMessages(event.chatId, seconds)
                        }
                    }
                    is WebSocketEvent.UserTyping -> {
                        val owner = ownerUserId()
                        if (owner.isBlank() || owner == "me" || !sessionActive(owner)) return@collect
                        remoteTypingCoordinator.onEvent(
                            activeChatId = activeChatId(),
                            eventChatId = event.chatId,
                            userId = event.userId,
                            isTyping = event.isTyping,
                        )
                    }
                    is WebSocketEvent.GroupRevisionChanged -> onGroupRevisionChanged(event)
                    is WebSocketEvent.UserOnline -> handlePresence(event)
                    is WebSocketEvent.Connected -> {
                        if (event.success) {
                            disconnectBannerJob?.cancel()
                            disconnectBannerJob = null
                            val connectionMessage = text(ChatRealtimeText.CONNECTION_FAILED)
                            updateState { state ->
                                if (state.groupEncryptionWarning == connectionMessage) {
                                    state.copy(groupEncryptionWarning = null)
                                } else {
                                    state
                                }
                            }
                        } else {
                            scheduleDisconnectBanner()
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        remoteTypingCoordinator.clear()
                        scheduleDisconnectBanner()
                    }
                    is WebSocketEvent.Error -> {
                        logWarning("WS error: ${event.kind}; ${event.debugDetail.orEmpty()}", null)
                        if (event.kind == WebSocketErrorKind.CONNECTION) {
                            scheduleDisconnectBanner()
                        } else {
                            updateState { it.copy(groupEncryptionWarning = text(ChatRealtimeText.DATA_INVALID)) }
                        }
                    }
                    is WebSocketEvent.ServerError -> {
                        logWarning(
                            "WS server error: ${event.code.orEmpty()} ${event.message}",
                            null,
                        )
                        if (event.message.isNotBlank()) {
                            updateState { it.copy(groupEncryptionWarning = event.message.take(120)) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handlePresence(event: WebSocketEvent.UserOnline) {
        val owner = ownerUserId()
        if (owner.isBlank() || owner == "me" || !sessionActive(owner)) return
        if (event.onlineRevoked || event.statusRevoked) applyRealtimeVisibility(event)
        val state = currentState()
        if (!shouldApplyContactPresence(state.chatIsGroup, state.contact.id, event.userId)) return
        if (event.onlineRevoked || event.statusRevoked) {
            updateState { current ->
                val contact = current.contact
                val visibility = resolveUserVisibility(
                    currentIsOnline = contact.isOnline,
                    currentStatus = contact.status,
                    currentLastSeen = contact.lastSeen,
                    eventIsOnline = event.isOnline,
                    eventLastSeen = event.lastSeen,
                    onlineRevoked = event.onlineRevoked,
                    statusRevoked = event.statusRevoked,
                )
                current.copy(
                    contact = contact.copy(
                        isOnline = visibility.isOnline,
                        status = visibility.status,
                        lastSeen = visibility.lastSeen,
                    ),
                )
            }
            return
        }
        if (!presenceEnabled()) return
        if (currentState().isSecretChat == true && secretPresenceBlocked()) return
        updateState {
            it.copy(contact = it.contact.copy(isOnline = event.isOnline, lastSeen = event.lastSeen))
        }
    }

    private fun scheduleDisconnectBanner() {
        if (disconnectBannerJob?.isActive == true) return
        disconnectBannerJob = scope.launch {
            delay(RealtimeDisconnectPolicy.BANNER_DELAY_MS)
            if (currentOwner()?.let(realtime::isConnected) == true) return@launch
            val connectionMessage = text(ChatRealtimeText.CONNECTION_FAILED)
            updateState { state ->
                if (state.groupEncryptionWarning.isNullOrBlank() || state.groupEncryptionWarning == connectionMessage) {
                    state.copy(groupEncryptionWarning = connectionMessage)
                } else {
                    state
                }
            }
        }
    }

    private fun announceTypingStarted() {
        if (currentState().isSecretChat == true && secretTypingBlocked()) return
        if (!typingIndicatorsEnabled()) return
        if (!TypingSessionPolicy.shouldAnnounceStart(activeChatId(), announcedTypingChatId)) return
        val targetChatId = activeChatId()
        val owner = ownerUserId()
        if (!TypingSessionPolicy.mayEmit(owner, token(), ownerUserId(), purgeInProgress = purgeInProgress())) return
        announcedTypingChatId?.let { sendTyping(it, false) }
        if (sendTyping(targetChatId, true)) announcedTypingChatId = targetChatId
    }

    private fun stopTypingAnnouncement() {
        if (currentState().isSecretChat == true && secretTypingBlocked()) return
        val targetChatId = announcedTypingChatId ?: return
        announcedTypingChatId = null
        val owner = ownerUserId()
        if (!TypingSessionPolicy.mayEmit(owner, token(), ownerUserId(), purgeInProgress = purgeInProgress())) return
        sendTyping(targetChatId, false)
    }

    private fun sendTyping(chatId: String, isTyping: Boolean): Boolean {
        val owner = currentOwner() ?: return false
        return realtime.sendTyping(owner, chatId, isTyping)
    }

    private fun currentOwner() = sessionContextProvider.current()
}
