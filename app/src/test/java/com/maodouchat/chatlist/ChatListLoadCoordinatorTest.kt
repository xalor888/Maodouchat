package com.maodouchat.chatlist

import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MissedCall
import com.maodouchat.network.ChatDto
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.screen.chatlist.ChatListLoadCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import io.mockk.every
import io.mockk.mockk
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
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        val cached = mutableListOf<List<Chat>>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
            getAllChats = { flowOf(emptyList()) },
            cacheChats = { cached += it },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "c1", lastMessage = "hello", lastMessageTime = 10)))
            },
        )

        coordinator.requestLoadChats(ChatListReloadPolicy.Trigger.USER_REFRESH)
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
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        var fetchCount = 0
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
            getAllChats = { flowOf(listOf(Chat(id = "keep"))) },
            getChatById = { null },
            fetchRemoteChats = {
                fetchCount += 1
                Result.success(listOf(ChatDto(id = "keep", lastMessage = "n$fetchCount")))
            },
        )

        coordinator.requestLoadChats(ChatListReloadPolicy.Trigger.RECONNECT)
        coordinator.requestLoadChats(ChatListReloadPolicy.Trigger.RECONNECT)
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
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
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
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
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
            tokenManager = tokenManager,
            observeMissedCalls = { flowOf(missed) },
        )

        coordinator.startMissedCallObservation()
        runCurrent()

        assertEquals(missed, uiState.value.missedCalls)
        assertTrue(uiState.value.missedCalls.isNotEmpty())
    }

    private fun buildCoordinator(
        uiState: MutableStateFlow<ChatListUiState>,
        tokenManager: TokenManager,
        deletedChatIds: MutableSet<String> = mutableSetOf(),
        getAllChats: suspend () -> Flow<List<Chat>> = { flowOf(emptyList()) },
        getChatById: suspend (String) -> Chat? = { null },
        cacheChats: suspend (List<Chat>) -> Unit = {},
        fetchRemoteChats: suspend (String) -> Result<List<ChatDto>> = { Result.success(emptyList()) },
        observeMissedCalls: () -> Flow<List<MissedCall>> = { flowOf(emptyList()) },
    ): ChatListLoadCoordinator = ChatListLoadCoordinator(
        scope = CoroutineScope(dispatcher),
        uiState = uiState,
        tokenManager = tokenManager,
        deletedChatIds = deletedChatIds,
        ownerUserId = { "me" },
        getAllChats = getAllChats,
        getChatById = getChatById,
        cacheChats = cacheChats,
        fetchRemoteChats = fetchRemoteChats,
        enrichServerChatPreview = { chat, _ -> chat },
        cleanupLocalChat = { _, _ -> },
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
