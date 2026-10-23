package com.maodouchat.chatlist

import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MissedCall
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ChatDto
import com.maodouchat.session.Snapshot
import com.maodouchat.ui.screen.chatlist.ChatListLoadCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import com.maodouchat.ui.screen.chatlist.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListLoadCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun userRefreshShowsLoadingAndAppliesRemoteSnapshot() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(Chat(id = "old"))))
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val cached = mutableListOf<List<Chat>>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            getAllChats = { flowOf(emptyList()) },
            cacheChats = { cached += it },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "c1", lastMessage = "hello", lastMessageTime = 10)))
            },
        )

        coordinator.requestLoadChats(Trigger.USER_REFRESH)
        runCurrent()

        assertFalse(uiState.value.isLoading)
        assertEquals(listOf("c1"), uiState.value.chats.map { it.id })
        assertEquals("hello", uiState.value.chats.single().lastMessage)
        assertEquals(1, cached.size)
        assertEquals(null, uiState.value.errorMessage)
    }

    @Test
    fun reconnectDebouncesSilentReload() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState(isLoading = true, chats = listOf(Chat(id = "keep"))))
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        var fetchCount = 0
        val coordinator = buildCoordinator(
            uiState = uiState,
            getAllChats = { flowOf(listOf(Chat(id = "keep"))) },
            getChatById = { null },
            fetchRemoteChats = {
                fetchCount += 1
                Result.success(listOf(ChatDto(id = "keep", lastMessage = "n$fetchCount")))
            },
        )

        coordinator.requestLoadChats(Trigger.RECONNECT)
        coordinator.requestLoadChats(Trigger.RECONNECT)
        runCurrent()
        assertEquals(0, fetchCount)
        assertTrue(uiState.value.isLoading)

        advanceTimeBy(ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS)
        runCurrent()

        assertEquals(1, fetchCount)
        assertFalse(uiState.value.isLoading)
        assertEquals("n1", uiState.value.chats.single().lastMessage)
    }

    @Test
    fun deletedChatIdsAreNotReinserted() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val coordinator = buildCoordinator(
            uiState = uiState,
            deletedChatIds = mutableSetOf("gone"),
            getAllChats = { flowOf(emptyList()) },
            fetchRemoteChats = {
                Result.success(
                    listOf(
                        ChatDto(id = "gone", lastMessage = "x"),
                        ChatDto(id = "keep", lastMessage = "y"),
                    )
                )
            },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertEquals(listOf("keep"), uiState.value.chats.map { it.id })
    }

    @Test
    fun missedCallsProjectIntoUiState() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val missed = listOf(
            MissedCall(
                id = "m1",
                callerId = "u2",
                callerName = "U",
                callType = "AUDIO",
                receivedAt = 1L,
            )
        )
        val coordinator = buildCoordinator(
            uiState = uiState,
            observeMissedCalls = { flowOf(missed) },
        )

        coordinator.startMissedCallObservation()
        runCurrent()

        assertEquals(missed, uiState.value.missedCalls)
        assertTrue(uiState.value.missedCalls.isNotEmpty())
    }

    @Test
    fun remoteFailureFallsBackToCachedChats() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val cached = listOf(Chat(id = "cached"))
        val coordinator = buildCoordinator(
            uiState = uiState,
            getAllChats = { flowOf(cached) },
            fetchRemoteChats = { Result.failure(RuntimeException("boom")) },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertFalse(uiState.value.isLoading)
        assertEquals(listOf("cached"), uiState.value.chats.map { it.id })
        assertEquals("boom", uiState.value.errorMessage)
    }

    @Test
    fun rateLimitedFailureKeepsExistingError() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val coordinator = buildCoordinator(
            uiState = uiState,
            fetchRemoteChats = {
                Result.failure(ApiException(ApiFailureKind.HTTP, statusCode = 429))
            },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertFalse(uiState.value.isLoading)
        assertEquals(null, uiState.value.errorMessage)
        assertTrue(uiState.value.chats.isEmpty())
    }

    @Test
    fun noSessionWithBlankOwnerShowsExpiredMessage() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot(null, null) }
        val coordinator = buildCoordinator(
            uiState = uiState,
            ownerUserId = { "" },
            getAllChats = { flowOf(emptyList()) },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertFalse(uiState.value.isLoading)
        assertEquals("msg-1", uiState.value.errorMessage)
        assertTrue(uiState.value.chats.isEmpty())
    }

    @Test
    fun staleLocalChatsAreCleanedUp() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        val cleaned = mutableListOf<String>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            getAllChats = { flowOf(listOf(Chat(id = "stale"), Chat(id = "fresh"))) },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "fresh", lastMessage = "hi", lastMessageTime = 1)))
            },
            cleanupLocalChat = { chatId, _ -> cleaned += chatId },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertEquals(listOf("stale"), cleaned)
        assertEquals(listOf("fresh"), uiState.value.chats.map { it.id })
        assertFalse(uiState.value.isLoading)
        assertEquals(null, uiState.value.errorMessage)
    }

    @Test
    fun debouncedReconnectIsCancelledByUserRefresh() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { Snapshot("tok", "me") }
        var fetchCount = 0
        val coordinator = buildCoordinator(
            uiState = uiState,
            fetchRemoteChats = {
                fetchCount += 1
                Result.success(emptyList())
            },
        )

        coordinator.requestLoadChats(Trigger.RECONNECT)
        coordinator.requestLoadChats(Trigger.USER_REFRESH)
        runCurrent()

        assertEquals(1, fetchCount)
        assertFalse(uiState.value.isLoading)
        assertEquals(null, uiState.value.errorMessage)
    }

    private fun buildCoordinator(
        uiState: MutableStateFlow<ChatListUiState>,
        deletedChatIds: MutableSet<String> = mutableSetOf(),
        ownerUserId: () -> String = { "me" },
        getAllChats: suspend () -> Flow<List<Chat>> = { flowOf(emptyList()) },
        getChatById: suspend (String) -> Chat? = { null },
        cacheChats: suspend (List<Chat>) -> Unit = {},
        fetchRemoteChats: suspend () -> Result<List<ChatDto>> = { Result.success(emptyList()) },
        observeMissedCalls: () -> Flow<List<MissedCall>> = { flowOf(emptyList()) },
        cleanupLocalChat: suspend (String, ConversationLocalCleanupSession) -> Unit = { _, _ -> },
    ): ChatListLoadCoordinator = ChatListLoadCoordinator(
        scope = CoroutineScope(dispatcher),
        uiState = uiState,
        deletedChatIds = deletedChatIds,
        ownerUserId = ownerUserId,
        getAllChats = getAllChats,
        getChatById = getChatById,
        cacheChats = cacheChats,
        fetchRemoteChats = fetchRemoteChats,
        enrichServerChatPreview = { chat, _ -> chat },
        cleanupLocalChat = cleanupLocalChat,
        cleanupSessionFor = { ConversationLocalCleanupSession(it, 0L) },
        activeChatId = { null },
        observeMissedCalls = observeMissedCalls,
        trimMissedCalls = {},
        text = { "msg-$it" },
        onRefreshIdentityWarnings = {},
        sessionExpiredMessageRes = 1,
        refreshFailedCachedMessageRes = 2,
        ioDispatcher = dispatcher,
    )
}
