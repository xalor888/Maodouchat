package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MissedCall
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.ChatListReloadPolicy
import com.maodouchat.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 从 app 侧随迁：`ChatListUiState` 经端口替换为本地 `ChatListLoadState` 持有，
 * `CurrentSession` 与 `BackgroundSessionGate` 经端口注入；判定语义与原测试一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListLoadCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun userRefreshShowsLoadingAndAppliesRemoteSnapshot() = runTest(dispatcher) {
        var state = ChatListLoadState(chats = listOf(Chat(id = "old")))
        val cached = mutableListOf<List<Chat>>()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(emptyList()) },
            cacheChats = { cached += it },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "c1", lastMessage = "hello", lastMessageTime = 10)))
            },
        )

        coordinator.requestLoadChats(Trigger.USER_REFRESH)
        runCurrent()

        assertFalse(state.isLoading)
        assertEquals(listOf("c1"), state.chats.map { it.id })
        assertEquals("hello", state.chats.single().lastMessage)
        assertEquals(1, cached.size)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun reconnectDebouncesSilentReload() = runTest(dispatcher) {
        var state = ChatListLoadState(isLoading = true, chats = listOf(Chat(id = "keep")))
        var fetchCount = 0
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
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
        assertTrue(state.isLoading)

        advanceTimeBy(ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS)
        runCurrent()

        assertEquals(1, fetchCount)
        assertFalse(state.isLoading)
        assertEquals("n1", state.chats.single().lastMessage)
    }

    @Test
    fun deletedChatIdsAreNotReinserted() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
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

        assertEquals(listOf("keep"), state.chats.map { it.id })
    }

    @Test
    fun missedCallsProjectIntoUiState() = runTest(dispatcher) {
        var state = ChatListLoadState()
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
            getState = { state },
            updateState = { state = it(state) },
            observeMissedCalls = { flowOf(missed) },
        )

        coordinator.startMissedCallObservation()
        runCurrent()

        assertEquals(missed, state.missedCalls)
        assertTrue(state.missedCalls.isNotEmpty())
    }

    @Test
    fun remoteFailureFallsBackToCachedChats() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val cached = listOf(Chat(id = "cached"))
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(cached) },
            fetchRemoteChats = { Result.failure(RuntimeException("boom")) },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertFalse(state.isLoading)
        assertEquals(listOf("cached"), state.chats.map { it.id })
        assertEquals("boom", state.errorMessage)
    }

    @Test
    fun rateLimitedFailureKeepsExistingError() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            fetchRemoteChats = {
                Result.failure(ApiException(ApiFailureKind.HTTP, statusCode = 429))
            },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertFalse(state.isLoading)
        assertEquals(null, state.errorMessage)
        assertTrue(state.chats.isEmpty())
    }

    @Test
    fun noSessionWithBlankOwnerShowsExpiredMessage() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            ownerUserId = { "" },
            hasSession = { false },
            currentOwnerUserId = { "" },
            getAllChats = { flowOf(emptyList()) },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertFalse(state.isLoading)
        assertEquals("msg-1", state.errorMessage)
        assertTrue(state.chats.isEmpty())
    }

    @Test
    fun staleLocalChatsAreCleanedUp() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val cleaned = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(listOf(Chat(id = "stale"), Chat(id = "fresh"))) },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "fresh", lastMessage = "hi", lastMessageTime = 1)))
            },
            cleanupLocalChat = { chatId, _ -> cleaned += chatId },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertEquals(listOf("stale"), cleaned)
        assertEquals(listOf("fresh"), state.chats.map { it.id })
        assertFalse(state.isLoading)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun debouncedReconnectIsCancelledByUserRefresh() = runTest(dispatcher) {
        var state = ChatListLoadState()
        var fetchCount = 0
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            fetchRemoteChats = {
                fetchCount += 1
                Result.success(emptyList())
            },
        )

        coordinator.requestLoadChats(Trigger.RECONNECT)
        coordinator.requestLoadChats(Trigger.USER_REFRESH)
        runCurrent()

        assertEquals(1, fetchCount)
        assertFalse(state.isLoading)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun foregroundReloadIsImmediateSilent() = runTest(dispatcher) {
        var state = ChatListLoadState(chats = listOf(Chat(id = "old")))
        val cached = mutableListOf<List<Chat>>()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(emptyList()) },
            cacheChats = { cached += it },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "c1", lastMessage = "hello", lastMessageTime = 10)))
            },
        )

        coordinator.requestLoadChats(Trigger.FOREGROUND)
        runCurrent()

        assertFalse(state.isLoading)
        assertEquals(listOf("c1"), state.chats.map { it.id })
        assertEquals(1, cached.size)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun groupRevisionDebouncesSilentReload() = runTest(dispatcher) {
        var state = ChatListLoadState(chats = listOf(Chat(id = "keep")))
        var fetchCount = 0
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(listOf(Chat(id = "keep"))) },
            fetchRemoteChats = {
                fetchCount += 1
                Result.success(listOf(ChatDto(id = "keep", lastMessage = "n1", lastMessageTime = 20)))
            },
        )

        coordinator.requestLoadChats(Trigger.GROUP_REVISION)
        runCurrent()

        assertEquals(0, fetchCount)
        assertEquals(listOf("keep"), state.chats.map { it.id })
        assertFalse(state.isLoading)

        advanceTimeBy(ChatListReloadPolicy.DEFAULT_DEBOUNCE_MS)
        runCurrent()

        assertEquals(1, fetchCount)
        assertFalse(state.isLoading)
        assertEquals("n1", state.chats.single().lastMessage)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun silentReloadFailureKeepsCurrentError() = runTest(dispatcher) {
        var state = ChatListLoadState(chats = listOf(Chat(id = "cached")))
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(listOf(Chat(id = "cached"))) },
            fetchRemoteChats = { Result.failure(RuntimeException("boom")) },
        )

        coordinator.requestLoadChats(Trigger.FOREGROUND)
        runCurrent()

        assertFalse(state.isLoading)
        assertEquals(listOf("cached"), state.chats.map { it.id })
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun secretChatDtoProjectsSecretChatIds() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(emptyList()) },
            fetchRemoteChats = {
                Result.success(listOf(ChatDto(id = "c1", lastMessage = "hi", lastMessageTime = 5, chatType = "SECRET")))
            },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertEquals(listOf("c1"), state.chats.map { it.id })
        assertEquals(setOf("c1"), state.secretChatIds)
        assertFalse(state.isLoading)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun emptyServerListEmptiesChatsAndCleansStale() = runTest(dispatcher) {
        var state = ChatListLoadState()
        val cleaned = mutableListOf<String>()
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(listOf(Chat(id = "stale"))) },
            fetchRemoteChats = { Result.success(emptyList()) },
            cleanupLocalChat = { chatId, _ -> cleaned += chatId },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertEquals(listOf("stale"), cleaned)
        assertTrue(state.chats.isEmpty())
        assertFalse(state.isLoading)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun sessionInactiveAbortsLoadWithExpiredMessage() = runTest(dispatcher) {
        var state = ChatListLoadState(chats = listOf(Chat(id = "keep")))
        var fetchCount = 0
        val coordinator = buildCoordinator(
            getState = { state },
            updateState = { state = it(state) },
            getAllChats = { flowOf(listOf(Chat(id = "keep"))) },
            fetchRemoteChats = {
                fetchCount += 1
                Result.success(listOf(ChatDto(id = "keep")))
            },
            sessionActive = { false },
        )

        coordinator.loadChats(showLoading = true)
        runCurrent()

        assertEquals(0, fetchCount)
        assertEquals("msg-1", state.errorMessage)
        assertEquals(listOf("keep"), state.chats.map { it.id })
        assertFalse(state.isLoading)
    }

    private fun buildCoordinator(
        getState: () -> ChatListLoadState,
        updateState: ((ChatListLoadState) -> ChatListLoadState) -> Unit,
        deletedChatIds: MutableSet<String> = mutableSetOf(),
        ownerUserId: () -> String = { "me" },
        getAllChats: suspend () -> Flow<List<Chat>> = { flowOf(emptyList()) },
        getChatById: suspend (String) -> Chat? = { null },
        cacheChats: suspend (List<Chat>) -> Unit = {},
        fetchRemoteChats: suspend () -> Result<List<ChatDto>> = { Result.success(emptyList()) },
        observeMissedCalls: () -> Flow<List<MissedCall>> = { flowOf(emptyList()) },
        cleanupLocalChat: suspend (String, ConversationLocalCleanupSession) -> Unit = { _, _ -> },
        hasSession: () -> Boolean = { true },
        currentOwnerUserId: () -> String = { "me" },
        sessionActive: (String) -> Boolean = { true },
        logWarning: (String, Throwable?) -> Unit = { _, _ -> },
    ): ChatListLoadCoordinator = ChatListLoadCoordinator(
        scope = CoroutineScope(dispatcher),
        getState = getState,
        updateState = updateState,
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
        sessionActive = sessionActive,
        hasSession = hasSession,
        currentOwnerUserId = currentOwnerUserId,
        logWarning = logWarning,
        ioDispatcher = dispatcher,
    )
}
