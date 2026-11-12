package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.core.model.Trigger
import com.maodouchat.core.model.applyPresenceProjection
import com.maodouchat.core.model.buildAdminBroadcastProjection
import com.maodouchat.core.model.zeroChatUnread
import com.maodouchat.core.realtime.ChatMessageSentEvent
import com.maodouchat.core.realtime.ChatReadEvent
import com.maodouchat.core.realtime.RealtimeConnectionState
import com.maodouchat.core.realtime.RealtimeDisconnectPolicy
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.core.realtime.RealtimeEventDispatcher
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

// 会话列表实时事件协调器：已读/发送回执与实时事件收集、断线横幅调度。
class ChatListRealtimeCoordinator(
    private val scope: CoroutineScope,
    private val getChats: () -> List<Chat>,
    private val updateChats: ((List<Chat>) -> List<Chat>) -> Unit,
    private val setRealtimeBanner: (String?) -> Unit,
    private val realtimeEventDispatcher: RealtimeEventDispatcher,
    private val addNotification: (NotificationCenterItem, String?) -> Unit,
    private val chatReadEvents: SharedFlow<ChatReadEvent>,
    private val chatMessageSentEvents: SharedFlow<ChatMessageSentEvent>,
    private val ownerUserId: () -> String,
    private val ownerSession: () -> OwnerSessionSnapshot,
    private val isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean,
    private val withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean,
    private val getCachedChat: suspend (String) -> Chat?,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val applyRealtimeVisibility: suspend (
        userId: String,
        isOnline: Boolean,
        onlineRevoked: Boolean,
        statusRevoked: Boolean,
        updatedAt: Long,
    ) -> Unit,
    private val wsConnectionFailedText: String,
    private val adminBroadcastDefaultTitle: String,
    private val adminBroadcastSenderLabel: String,
    private val sessionActive: (String) -> Boolean,
    private val logWarning: (String, Throwable?) -> Unit,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val onRequestLoadChats: (Trigger) -> Unit,
    private val onApplyChatListPreview: (
        chatId: String,
        previewText: String,
        messageType: MessageType,
        timestamp: Long,
        ownerUserId: String,
        sessionGeneration: Long,
    ) -> Unit,
    private val onRefreshPreviewFromLocal: (
        chatId: String,
        ownerUserId: String,
        sessionGeneration: Long,
    ) -> Unit,
    private val onClearMarkedUnreadAfterOpen: (Chat) -> Unit,
    private val onRequestBacklogSync: () -> Unit,
) {
    private var disconnectBannerJob: Job? = null

    fun start() {
        observeRealtime()
    }

    fun clearRealtimeBanner() {
        disconnectBannerJob?.cancel()
        disconnectBannerJob = null
        setRealtimeBanner(null)
    }

    /** Brief flaps reconnect inside BANNER_DELAY_MS; only then surface the down banner. */
    private fun scheduleDisconnectBanner() {
        if (disconnectBannerJob?.isActive == true) return
        disconnectBannerJob = scope.launch {
            delay(RealtimeDisconnectPolicy.BANNER_DELAY_MS)
            if (realtimeEventDispatcher.connectionState.value == RealtimeConnectionState.CONNECTED) return@launch
            setRealtimeBanner(wsConnectionFailedText)
        }
    }

    private fun observeRealtime() {
        val realtimeOwnerUserId = ownerUserId()
        val realtimeSession = ownerSession()
        scope.launch {
            // 监听 ChatDetailViewModel 发出的已读事件，实时归零未读数（UI + Room）
            chatReadEvents.collect { event ->
                if (event.sessionGeneration != realtimeSession.sessionGeneration ||
                    !isOwnerSessionCurrent(realtimeSession)
                ) {
                    return@collect
                }
                val readChatId = event.chatId
                if (readChatId.isBlank()) return@collect
                val manuallyUnread = getChats().firstOrNull { it.id == readChatId && it.markedUnread }
                updateChats { zeroChatUnread(it, readChatId) }
                // Persist zero unread so process death does not resurrect badge before next getChats.
                scope.launch {
                    try {
                        withOwnerRoomWrite(realtimeSession) {
                            val cached = getCachedChat(readChatId) ?: return@withOwnerRoomWrite
                            if (cached.unreadCount != 0 || cached.markedUnread) {
                                cacheChats(
                                    listOf(cached.copy(unreadCount = 0, markedUnread = false))
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logWarning("Failed to persist chat read", error)
                    }
                }
                // Open-chat must clear server markedUnread without toggle rollback to true.
                manuallyUnread?.let { onClearMarkedUnreadAfterOpen(it) }
            }
        }
        scope.launch {
            // 发送/附件 finalize 用单调时间；delete/revoke 本地重算用 forceTimestamp 绝对写
            chatMessageSentEvents.collect { event ->
                if (event.sessionGeneration != realtimeSession.sessionGeneration ||
                    !isOwnerSessionCurrent(realtimeSession)
                ) {
                    return@collect
                }
                if (event.forceFromLocal) {
                    onRefreshPreviewFromLocal(
                        event.chatId,
                        realtimeOwnerUserId,
                        realtimeSession.sessionGeneration,
                    )
                    return@collect
                }
                onApplyChatListPreview(
                    event.chatId,
                    event.previewText,
                    MessageType.fromWire(event.messageTypeWire),
                    nowMillis(),
                    realtimeOwnerUserId,
                    realtimeSession.sessionGeneration,
                )
            }
        }
        scope.launch {
            realtimeEventDispatcher.allEvents.collect { event ->
                // Logout disconnects WS but buffered events may still drain; drop if session gone.
                if (!isOwnerSessionCurrent(realtimeSession)) return@collect
                val liveUserId = realtimeOwnerUserId
                when (event) {
                    is RealtimeDomainEvent.AdminNotice -> {
                        val title = event.title.ifBlank { adminBroadcastDefaultTitle }
                        val projection = buildAdminBroadcastProjection(
                            title = title,
                            body = event.text,
                            timestamp = event.timestamp,
                            senderLabel = adminBroadcastSenderLabel,
                        )
                        if (projection != null) {
                            try {
                                addNotification(projection.item, liveUserId)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                logWarning("Failed to store admin broadcast", error)
                            }
                            if (!sessionActive(liveUserId)) return@collect
                            setRealtimeBanner(projection.bannerText)
                        }
                    }
                    is RealtimeDomainEvent.GroupRevision -> {
                        // Membership bursts (join/leave/kick) often arrive in clusters.
                        onRequestLoadChats(Trigger.GROUP_REVISION)
                    }
                    is RealtimeDomainEvent.ConnectionStateChanged -> {
                        when (event.state) {
                            RealtimeConnectionState.CONNECTED -> {
                                disconnectBannerJob?.cancel()
                                disconnectBannerJob = null
                                setRealtimeBanner(null)
                                // Immediate silent: keep previous rows, don't flash isLoading.
                                onRequestLoadChats(Trigger.RECONNECT)
                                // 9.3xx：断线窗口补拉（Ideaura 式）——重连后立即同步各会话增量，
                                // 否则断线期间的消息要等 15 分钟周期任务或手动打开聊天才出现。
                                onRequestBacklogSync()
                            }
                            RealtimeConnectionState.DISCONNECTED,
                            RealtimeConnectionState.FAILED -> {
                                scheduleDisconnectBanner()
                            }
                            else -> Unit
                        }
                    }
                    is RealtimeDomainEvent.Presence -> {
                        // Collector already blank-checks token/userId; re-check so buffered events
                        // after switch do not paint previous-owner online dots onto the new list.
                        if (!sessionActive(liveUserId)) {
                            return@collect
                        }
                        if (event.onlineRevoked || event.statusRevoked) {
                            withOwnerRoomWrite(realtimeSession) {
                                applyRealtimeVisibility(
                                    event.userId,
                                    event.isOnline,
                                    event.onlineRevoked,
                                    event.statusRevoked,
                                    nowMillis(),
                                )
                            }
                        }
                        if (!sessionActive(liveUserId)) return@collect
                        updateChats { chats ->
                            applyPresenceProjection(
                                chats = chats,
                                userId = event.userId,
                                eventIsOnline = event.isOnline,
                                eventLastSeen = event.lastSeen,
                                onlineRevoked = event.onlineRevoked,
                                statusRevoked = event.statusRevoked,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
