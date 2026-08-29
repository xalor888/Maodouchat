package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.util.Log
import com.maodouchat.R
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.REMOTE_TYPING_TIMEOUT_MS
import com.maodouchat.network.RealtimeDisconnectPolicy
import com.maodouchat.network.TypingSignalAction
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketErrorKind
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.network.WebSocketTransport
import com.maodouchat.network.resolveTypingSignalAction
import com.maodouchat.network.resolveUserVisibility
import com.maodouchat.session.AccountScopedRealtimeConnectionManager
import com.maodouchat.session.RealtimeConnectionManager
import com.maodouchat.session.TokenManagerSessionContextProvider
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatRealtimeController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val tokenManager: TokenManager,
    private val realtime: RealtimeConnectionManager = AccountScopedRealtimeConnectionManager(
        sessionContextProvider = TokenManagerSessionContextProvider(tokenManager),
        transport = WebSocketTransport,
        scope = scope,
    ),
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val persistDisappearingMessages: suspend (String, Int) -> Unit,
    private val applyRealtimeVisibility: suspend (WebSocketEvent.UserOnline) -> Unit,
    private val onGroupRevisionChanged: suspend (WebSocketEvent.GroupRevisionChanged) -> Unit,
    private val text: (Int) -> String,
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
        realtime.start(ApiConfig.WS_URL, liveToken, ownerContext)
    }

    private fun observePresenceFallbackPolling() {
        scope.launch {
            while (isActive) {
                delay(30_000L)
                val owner = ownerUserId()
                if (!sessionActive(owner) || !enabled(RuntimeFlags.PRESENCE)) continue
                val state = currentState()
                if (state.chatIsGroup || state.isSecretChat == true) continue
                if (enabled(RuntimeFlags.SECRET_PRESENCE_BLOCK) && state.isSecretChat == true) continue
                val contactId = state.contact.id.takeIf(String::isNotBlank) ?: continue
                val liveToken = token()
                if (liveToken.isBlank()) continue
                ApiService.getUser(liveToken, contactId).onSuccess { dto ->
                    updateState { current ->
                        if (current.contact.id != contactId || current.chatIsGroup) current
                        else current.copy(
                            contact = current.contact.copy(isOnline = dto.isOnline, lastSeen = dto.lastSeen),
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
                        withContext(Dispatchers.IO) {
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
                            val connectionMessage = text(R.string.chat_ws_connection_failed)
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
                        Log.w("ChatDetailViewModel", "WS error: ${event.kind}; ${event.debugDetail.orEmpty()}")
                        if (event.kind == WebSocketErrorKind.CONNECTION) {
                            scheduleDisconnectBanner()
                        } else {
                            updateState { it.copy(groupEncryptionWarning = text(R.string.chat_ws_data_invalid)) }
                        }
                    }
                    is WebSocketEvent.ServerError -> {
                        Log.w(
                            "ChatDetailViewModel",
                            "WS server error: ${event.code.orEmpty()} ${event.message}",
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
        if (!enabled(RuntimeFlags.PRESENCE)) return
        if (currentState().isSecretChat == true && enabled(RuntimeFlags.SECRET_PRESENCE_BLOCK)) return
        updateState {
            it.copy(contact = it.contact.copy(isOnline = event.isOnline, lastSeen = event.lastSeen))
        }
    }

    private fun scheduleDisconnectBanner() {
        if (disconnectBannerJob?.isActive == true) return
        disconnectBannerJob = scope.launch {
            delay(RealtimeDisconnectPolicy.BANNER_DELAY_MS)
            if (currentOwner()?.let(realtime::isConnected) == true) return@launch
            val connectionMessage = text(R.string.chat_ws_connection_failed)
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
        if (currentState().isSecretChat == true && enabled(RuntimeFlags.SECRET_TYPING_BLOCK)) return
        if (!enabled(RuntimeFlags.TYPING_INDICATORS)) return
        if (!TypingSessionPolicy.shouldAnnounceStart(activeChatId(), announcedTypingChatId)) return
        val targetChatId = activeChatId()
        val owner = ownerUserId()
        if (!TypingSessionPolicy.mayEmit(owner, token(), ownerUserId())) return
        announcedTypingChatId?.let { sendTyping(it, false) }
        if (sendTyping(targetChatId, true)) announcedTypingChatId = targetChatId
    }

    private fun stopTypingAnnouncement() {
        if (currentState().isSecretChat == true && enabled(RuntimeFlags.SECRET_TYPING_BLOCK)) return
        val targetChatId = announcedTypingChatId ?: return
        announcedTypingChatId = null
        val owner = ownerUserId()
        if (!TypingSessionPolicy.mayEmit(owner, token(), ownerUserId())) return
        sendTyping(targetChatId, false)
    }

    private fun sendTyping(chatId: String, isTyping: Boolean): Boolean {
        val owner = currentOwner() ?: return false
        return realtime.sendTyping(owner, chatId, isTyping)
    }

    private fun currentOwner() = tokenManager.currentSessionContext()

    private fun enabled(flag: RuntimeFlags.Flag): Boolean = RuntimeFlags.isEnabled(application, flag)
}
