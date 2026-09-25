package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.core.realtime.RealtimeConnectionState
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.core.realtime.RealtimeEventDispatcher
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.network.RealtimeDisconnectPolicy
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.ui.OwnerSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话列表实时事件协调器（ChatList 瘦身：collector 编排切片）。
 *
 * 承接 `chatReadEvents` / `chatMessageSentEvents` / `RealtimeEventDispatcher` 的收集与
 * 断线横幅调度；预览写入、列表重载、已读水位等副作用经回调回到 ViewModel，
 * 避免协调器再叠一层业务真相源。
 */
internal class ChatListRealtimeCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val realtimeEventDispatcher: RealtimeEventDispatcher,
    private val notificationCenter: NotificationCenterRepository,
    private val chatReadEvents: SharedFlow<MaodouchatApp.Companion.ChatReadEvent>,
    private val chatMessageSentEvents: SharedFlow<MaodouchatApp.Companion.ChatMessageSentEvent>,
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
    private val text: (Int) -> String,
    private val onRequestLoadChats: (ChatListReloadPolicy.Trigger) -> Unit,
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
        uiState.update { it.copy(realtimeBanner = null) }
    }

    /** Brief flaps reconnect inside BANNER_DELAY_MS; only then surface the down banner. */
    private fun scheduleDisconnectBanner() {
        if (disconnectBannerJob?.isActive == true) return
        disconnectBannerJob = scope.launch {
            delay(RealtimeDisconnectPolicy.BANNER_DELAY_MS)
            if (realtimeEventDispatcher.connectionState.value == RealtimeConnectionState.CONNECTED) return@launch
            uiState.update { it.copy(realtimeBanner = text(R.string.chat_ws_connection_failed)) }
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
                val manuallyUnread = uiState.value.chats.firstOrNull { it.id == readChatId && it.markedUnread }
                uiState.update { state ->
                    state.copy(chats = zeroChatUnread(state.chats, readChatId))
                }
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
                        Log.w(TAG, "Failed to persist chat read", error)
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
                    System.currentTimeMillis(),
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
                        val title = event.title.ifBlank { text(R.string.notification_admin_broadcast_default_title) }
                        val projection = buildAdminBroadcastProjection(
                            title = title,
                            body = event.text,
                            timestamp = event.timestamp,
                            senderLabel = text(R.string.notification_admin_broadcast_sender),
                        )
                        if (projection != null) {
                            try {
                                notificationCenter.add(
                                    projection.item,
                                    expectedUserId = liveUserId,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(TAG, "Failed to store admin broadcast", error)
                            }
                            if (!BackgroundSessionGate.mayContinue(
                                expectedUserId = liveUserId,
                            )
                            ) return@collect
                            uiState.update {
                                it.copy(realtimeBanner = projection.bannerText)
                            }
                        }
                    }
                    is RealtimeDomainEvent.GroupRevision -> {
                        // Membership bursts (join/leave/kick) often arrive in clusters.
                        onRequestLoadChats(ChatListReloadPolicy.Trigger.GROUP_REVISION)
                    }
                    is RealtimeDomainEvent.ConnectionStateChanged -> {
                        when (event.state) {
                            RealtimeConnectionState.CONNECTED -> {
                                disconnectBannerJob?.cancel()
                                disconnectBannerJob = null
                                uiState.update { it.copy(realtimeBanner = null) }
                                // Immediate silent: keep previous rows, don't flash isLoading.
                                onRequestLoadChats(ChatListReloadPolicy.Trigger.RECONNECT)
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
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = liveUserId,
                        )
                        ) {
                            return@collect
                        }
                        if (event.onlineRevoked || event.statusRevoked) {
                            withOwnerRoomWrite(realtimeSession) {
                                applyRealtimeVisibility(
                                    event.userId,
                                    event.isOnline,
                                    event.onlineRevoked,
                                    event.statusRevoked,
                                    System.currentTimeMillis(),
                                )
                            }
                        }
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = liveUserId,
                        )
                        ) return@collect
                        uiState.update { state ->
                            state.copy(
                                chats = applyPresenceProjection(
                                    chats = state.chats,
                                    userId = event.userId,
                                    eventIsOnline = event.isOnline,
                                    eventLastSeen = event.lastSeen,
                                    onlineRevoked = event.onlineRevoked,
                                    statusRevoked = event.statusRevoked,
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChatListRealtime"
    }
}
