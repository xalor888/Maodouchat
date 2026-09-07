package com.maodouchat.chatlist

import com.maodouchat.MaodouchatApp.Companion.ChatMessageSentEvent
import com.maodouchat.MaodouchatApp.Companion.ChatReadEvent
import com.maodouchat.core.realtime.DefaultRealtimeEventDispatcher
import com.maodouchat.core.realtime.RealtimeConnectionState
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatlist.ChatListRealtimeCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListRealtimeCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun readEventZerosUnreadAndClearsMarkedUnread() = runTest(dispatcher) {
        val uiState = MutableStateFlow(
            ChatListUiState(
                chats = listOf(
                    Chat(id = "c1", unreadCount = 3, markedUnread = true),
                    Chat(id = "c2", unreadCount = 1),
                )
            )
        )
        val chatReadEvents = MutableSharedFlow<ChatReadEvent>(extraBufferCapacity = 8)
        val cleared = mutableListOf<Chat>()
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            chatReadEvents = chatReadEvents,
            onClearMarkedUnreadAfterOpen = { cleared += it },
        )
        coordinator.start()

        chatReadEvents.emit(ChatReadEvent(chatId = "c1", sessionGeneration = 1L))

        assertEquals(0, uiState.value.chats[0].unreadCount)
        assertEquals(false, uiState.value.chats[0].markedUnread)
        assertEquals(1, uiState.value.chats[1].unreadCount)
        assertEquals(listOf("c1"), cleared.map { it.id })
    }

    @Test
    fun staleSessionGenerationDropsReadEvent() = runTest(dispatcher) {
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(Chat(id = "c1", unreadCount = 3)))
        )
        val chatReadEvents = MutableSharedFlow<ChatReadEvent>(extraBufferCapacity = 8)
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            chatReadEvents = chatReadEvents,
            sessionGeneration = 2L,
        )
        coordinator.start()

        chatReadEvents.emit(ChatReadEvent(chatId = "c1", sessionGeneration = 1L))

        assertEquals(3, uiState.value.chats[0].unreadCount)
    }

    @Test
    fun messageSentAppliesPreviewCallback() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        val sentEvents = MutableSharedFlow<ChatMessageSentEvent>(extraBufferCapacity = 8)
        val previews = mutableListOf<Triple<String, String, MessageType>>()
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            chatMessageSentEvents = sentEvents,
            onApplyChatListPreview = { chatId, preview, type, _, _, _ ->
                previews += Triple(chatId, preview, type)
            },
        )
        coordinator.start()

        sentEvents.emit(
            ChatMessageSentEvent(
                chatId = "c1",
                previewText = "hello",
                messageTypeWire = "TEXT",
                sessionGeneration = 1L,
            )
        )

        assertEquals(listOf(Triple("c1", "hello", MessageType.TEXT)), previews)
    }

    @Test
    fun forceFromLocalRefreshesPreviewFromRoom() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        val sentEvents = MutableSharedFlow<ChatMessageSentEvent>(extraBufferCapacity = 8)
        val refreshed = mutableListOf<String>()
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            chatMessageSentEvents = sentEvents,
            onRefreshPreviewFromLocal = { chatId, _, _ -> refreshed += chatId },
        )
        coordinator.start()

        sentEvents.emit(
            ChatMessageSentEvent(
                chatId = "c9",
                forceFromLocal = true,
                sessionGeneration = 1L,
            )
        )

        assertEquals(listOf("c9"), refreshed)
    }

    @Test
    fun groupRevisionRequestsDebouncedReload() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        val realtime = DefaultRealtimeEventDispatcher()
        val loads = mutableListOf<ChatListReloadPolicy.Trigger>()
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            realtime = realtime,
            onRequestLoadChats = { loads += it },
        )
        coordinator.start()

        realtime.dispatch(
            RealtimeDomainEvent.GroupRevision(
                chatId = "g1",
                memberRevision = 3,
                reason = "member_joined",
            )
        )

        assertEquals(listOf(ChatListReloadPolicy.Trigger.GROUP_REVISION), loads)
    }

    @Test
    fun reconnectClearsBannerRequestsLoadAndBacklogSync() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState(realtimeBanner = "down"))
        val realtime = DefaultRealtimeEventDispatcher()
        val loads = mutableListOf<ChatListReloadPolicy.Trigger>()
        var backlog = 0
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            realtime = realtime,
            onRequestLoadChats = { loads += it },
            onRequestBacklogSync = { backlog += 1 },
        )
        coordinator.start()

        realtime.dispatch(
            RealtimeDomainEvent.ConnectionStateChanged(RealtimeConnectionState.CONNECTED)
        )

        assertNull(uiState.value.realtimeBanner)
        assertEquals(listOf(ChatListReloadPolicy.Trigger.RECONNECT), loads)
        assertEquals(1, backlog)
    }

    @Test
    fun presenceProjectsOnlineOntoMatchingParticipant() = runTest(dispatcher) {
        val peer = User(id = "u2", name = "Peer", isOnline = false, lastSeen = 1L)
        val uiState = MutableStateFlow(
            ChatListUiState(
                chats = listOf(Chat(id = "c1", participants = listOf(peer)))
            )
        )
        val realtime = DefaultRealtimeEventDispatcher()
        val coordinator = buildCoordinator(
            scope = backgroundScope,
            uiState = uiState,
            realtime = realtime,
            tokenUserId = "u1",
            tokenValue = "tok",
        )
        coordinator.start()

        realtime.dispatch(
            RealtimeDomainEvent.Presence(
                userId = "u2",
                isOnline = true,
                lastSeen = 99L,
            )
        )

        val updated = uiState.value.chats[0].participants[0]
        assertTrue(updated.isOnline)
        assertEquals(99L, updated.lastSeen)
    }

    private fun buildCoordinator(
        scope: CoroutineScope,
        uiState: MutableStateFlow<ChatListUiState>,
        chatReadEvents: MutableSharedFlow<ChatReadEvent> =
            MutableSharedFlow(extraBufferCapacity = 8),
        chatMessageSentEvents: MutableSharedFlow<ChatMessageSentEvent> =
            MutableSharedFlow(extraBufferCapacity = 8),
        realtime: DefaultRealtimeEventDispatcher = DefaultRealtimeEventDispatcher(),
        sessionGeneration: Long = 1L,
        tokenUserId: String = "u1",
        tokenValue: String = "tok",
        onRequestLoadChats: (ChatListReloadPolicy.Trigger) -> Unit = {},
        onApplyChatListPreview: (
            String,
            String,
            MessageType,
            Long,
            String,
            Long,
        ) -> Unit = { _, _, _, _, _, _ -> },
        onRefreshPreviewFromLocal: (String, String, Long) -> Unit = { _, _, _ -> },
        onClearMarkedUnreadAfterOpen: (Chat) -> Unit = {},
        onRequestBacklogSync: () -> Unit = {},
    ): ChatListRealtimeCoordinator {
        val tokenManager = mockk<TokenManager>(relaxed = true)
        every { tokenManager.getToken() } returns tokenValue
        every { tokenManager.getUserId() } returns tokenUserId
        val notificationCenter = mockk<NotificationCenterRepository>(relaxed = true)
        return ChatListRealtimeCoordinator(
            scope = scope,
            uiState = uiState,
            tokenManager = tokenManager,
            realtimeEventDispatcher = realtime,
            notificationCenter = notificationCenter,
            chatReadEvents = chatReadEvents,
            chatMessageSentEvents = chatMessageSentEvents,
            ownerUserId = { tokenUserId },
            ownerSession = { OwnerSessionSnapshot(tokenUserId, sessionGeneration) },
            isOwnerSessionCurrent = { true },
            withOwnerRoomWrite = { _, block ->
                block()
                true
            },
            getCachedChat = { null },
            cacheChats = {},
            applyRealtimeVisibility = { _, _, _, _, _ -> },
            text = { key -> "str-$key" },
            onRequestLoadChats = onRequestLoadChats,
            onApplyChatListPreview = onApplyChatListPreview,
            onRefreshPreviewFromLocal = onRefreshPreviewFromLocal,
            onClearMarkedUnreadAfterOpen = onClearMarkedUnreadAfterOpen,
            onRequestBacklogSync = onRequestBacklogSync,
        )
    }
}
