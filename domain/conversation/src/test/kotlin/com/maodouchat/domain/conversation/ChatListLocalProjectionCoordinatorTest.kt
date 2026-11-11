package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.Receipt
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListLocalProjectionCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    /** 替代 app 侧 ChatListUiState 的最小状态：协调器读写的十三个字段。 */
    private class State(initialChats: List<Chat> = emptyList()) {
        val chatsFlow = MutableStateFlow(initialChats)
        var identityChangedUserIds: Set<String> = emptySet()
        var lockedChatIds: Set<String> = emptySet()
        var secretChatIds: Set<String> = emptySet()
        var scheduledByChat: Map<String, Int> = emptyMap()
        var selectedTab: Int = 0
        var showArchived: Boolean = false
        var errorMessage: String? = null
        var ownerTransferRequiredChatId: String? = null
        var searchQuery: String = ""
        var messageMatchedChatIds: Set<String> = emptySet()
        var drafts: Map<String, ChatDraft> = emptyMap()
        var receiptsByChat: Map<String, Receipt> = emptyMap()
    }

    private fun CoroutineScope.buildCoordinator(
        state: State,
        ownerId: String = "me",
        drafts: Flow<List<ChatDraft>> = flowOf(emptyList()),
        getRecentMessages: suspend (String, Int) -> List<Message> = { _, _ -> emptyList() },
        listLockedChatIds: suspend () -> Set<String> = { emptySet() },
        searchChatIdsByMessageContent: suspend (String) -> List<String> = { emptyList() },
        listSecretChatIds: suspend () -> Set<String> = { emptySet() },
        trustChangedRemoteIds: suspend (String, Set<String>) -> Set<String> = { _, _ -> emptySet() },
        listScheduled: () -> List<ScheduledMessage> = { emptyList() },
        warnings: MutableList<Pair<String, Throwable?>> = mutableListOf(),
        searchDebounceMs: Long = 250L,
    ) = ChatListLocalProjectionCoordinator(
        scope = this,
        getChats = { state.chatsFlow.value },
        chatsFlow = state.chatsFlow,
        getIdentityChangedUserIds = { state.identityChangedUserIds },
        updateIdentityChangedUserIds = { state.identityChangedUserIds = it },
        updateLockedChatIds = { state.lockedChatIds = it },
        updateSecretChatIds = { state.secretChatIds = it },
        updateScheduledByChat = { state.scheduledByChat = it },
        updateSelectedTab = { state.selectedTab = it },
        updateShowArchived = { state.showArchived = it },
        updateErrorMessage = { state.errorMessage = it },
        updateOwnerTransferRequiredChatId = { state.ownerTransferRequiredChatId = it },
        getSearchQuery = { state.searchQuery },
        updateSearchQuery = { state.searchQuery = it },
        updateMessageMatchedChatIds = { state.messageMatchedChatIds = it },
        updateDrafts = { state.drafts = it },
        updateReceiptsByChat = { state.receiptsByChat = it },
        ownerUserId = { ownerId },
        observeDraftsForOwner = { drafts },
        getRecentMessages = getRecentMessages,
        listLockedChatIds = listLockedChatIds,
        searchChatIdsByMessageContent = searchChatIdsByMessageContent,
        listSecretChatIds = listSecretChatIds,
        trustChangedRemoteIds = trustChangedRemoteIds,
        listScheduled = listScheduled,
        searchFailedText = { "search-failed" },
        logWarning = { message, error -> warnings += message to error },
        ioDispatcher = dispatcher,
        searchDebounceMs = searchDebounceMs,
    )

    @Test
    fun refreshIdentityWarningsSkipsBlankOwner() = runTest(dispatcher) {
        val state = State()
        var calls = 0
        val coordinator = buildCoordinator(
            state,
            ownerId = "",
            trustChangedRemoteIds = { _, _ -> calls++; setOf("u2") },
        )
        coordinator.refreshIdentityWarnings()
        advanceUntilIdle()
        assertEquals(0, calls)
        assertTrue(state.identityChangedUserIds.isEmpty())
    }

    @Test
    fun refreshIdentityWarningsSkipsMeOwner() = runTest(dispatcher) {
        val state = State()
        var calls = 0
        val coordinator = buildCoordinator(
            state,
            ownerId = "me",
            trustChangedRemoteIds = { _, _ -> calls++; setOf("u2") },
        )
        coordinator.refreshIdentityWarnings()
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun refreshIdentityWarningsMarksChangedRemoteIds() = runTest(dispatcher) {
        val chat = Chat(
            id = "c1",
            participants = listOf(User(id = "owner1", name = "Owner"), User(id = "u2", name = "U2")),
        )
        val state = State(listOf(chat))
        val coordinator = buildCoordinator(
            state,
            trustChangedRemoteIds = { owner, remoteIds ->
                assertEquals("owner1", owner)
                assertEquals(setOf("u2"), remoteIds)
                setOf("u2")
            },
            ownerId = "owner1",
        )
        coordinator.refreshIdentityWarnings()
        advanceUntilIdle()
        assertEquals(setOf("u2"), state.identityChangedUserIds)
    }

    @Test
    fun refreshLockedChatsWritesIds() = runTest(dispatcher) {
        val state = State()
        val coordinator = buildCoordinator(state, listLockedChatIds = { setOf("c1", "c2") })
        coordinator.refreshLockedChats()
        advanceUntilIdle()
        assertEquals(setOf("c1", "c2"), state.lockedChatIds)
    }

    @Test
    fun refreshSecretChatsDerivesFromChats() = runTest(dispatcher) {
        val secret = Chat(id = "c2", chatType = "SECRET")
        val direct = Chat(id = "c1")
        val state = State(listOf(direct, secret))
        val coordinator = buildCoordinator(state)
        coordinator.refreshSecretChats()
        advanceUntilIdle()
        assertEquals(setOf("c2"), state.secretChatIds)
    }

    @Test
    fun refreshScheduledCountsGroupsByChat() = runTest(dispatcher) {
        val state = State()
        val coordinator = buildCoordinator(
            state,
            listScheduled = {
                listOf(
                    ScheduledMessage(id = "s1", chatId = "c1", peerUserId = "u2", text = "a", sendAtMillis = 1L, createdAtMillis = 1L),
                    ScheduledMessage(id = "s2", chatId = "c1", peerUserId = "u2", text = "b", sendAtMillis = 1L, createdAtMillis = 1L),
                    ScheduledMessage(id = "s3", chatId = "c2", peerUserId = "u3", text = "c", sendAtMillis = 1L, createdAtMillis = 1L),
                )
            },
        )
        coordinator.refreshScheduledCounts()
        assertEquals(mapOf("c1" to 2, "c2" to 1), state.scheduledByChat)
    }

    @Test
    fun tabArchiveAndClearWriteThrough() = runTest(dispatcher) {
        val state = State().apply {
            errorMessage = "boom"
            ownerTransferRequiredChatId = "c9"
        }
        val coordinator = buildCoordinator(state)
        coordinator.onTabSelected(2)
        coordinator.setShowArchived(true)
        coordinator.clearError()
        coordinator.clearOwnerTransferRequired()
        assertEquals(2, state.selectedTab)
        assertTrue(state.showArchived)
        assertNull(state.errorMessage)
        assertNull(state.ownerTransferRequiredChatId)
    }

    @Test
    fun onSearchQueryChangeClipsLongQuery() = runTest(dispatcher) {
        val state = State()
        val coordinator = buildCoordinator(state, searchDebounceMs = 100L)
        coordinator.onSearchQueryChange("x".repeat(250))
        assertEquals(200, state.searchQuery.length)
        advanceUntilIdle()
        assertTrue(state.messageMatchedChatIds.isEmpty())
    }

    @Test
    fun onSearchQueryChangeShortQueryClearsMatched() = runTest(dispatcher) {
        val state = State().apply { messageMatchedChatIds = setOf("c1") }
        val coordinator = buildCoordinator(state)
        coordinator.onSearchQueryChange("x")
        assertEquals(setOf<String>(), state.messageMatchedChatIds)
    }

    @Test
    fun onSearchQueryChangeDebouncesAndFiltersLockedAndSecret() = runTest(dispatcher) {
        val state = State()
        val coordinator = buildCoordinator(
            state,
            searchChatIdsByMessageContent = { listOf("c1", "c2", "c3") },
            listLockedChatIds = { setOf("c2") },
            listSecretChatIds = { setOf("c3") },
            searchDebounceMs = 100L,
        )
        coordinator.onSearchQueryChange("hello")
        advanceTimeBy(99L)
        assertTrue(state.messageMatchedChatIds.isEmpty())
        advanceTimeBy(1L)
        advanceUntilIdle()
        assertEquals(setOf("c1"), state.messageMatchedChatIds)
    }

    @Test
    fun onSearchQueryChangeSetsErrorOnFailure() = runTest(dispatcher) {
        val state = State()
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        val coordinator = buildCoordinator(
            state,
            searchChatIdsByMessageContent = { error("db down") },
            warnings = warnings,
            searchDebounceMs = 100L,
        )
        coordinator.onSearchQueryChange("hello")
        advanceTimeBy(100L)
        advanceUntilIdle()
        assertTrue(state.messageMatchedChatIds.isEmpty())
        assertEquals("search-failed", state.errorMessage)
        assertEquals(1, warnings.size)
    }

    @Test
    fun refreshScheduledCountsSwallowsErrors() = runTest(dispatcher) {
        val state = State().apply { scheduledByChat = mapOf("old" to 9) }
        val coordinator = buildCoordinator(state, listScheduled = { error("prefs broken") })
        coordinator.refreshScheduledCounts()
        assertEquals(emptyMap(), state.scheduledByChat)
    }

    @Test
    fun observeDraftsMapsToChatDrafts() = runTest(dispatcher) {
        val state = State()
        val coordinator = backgroundScope.buildCoordinator(
            state,
            drafts = flowOf(listOf(ChatDraft(chatId = "c1", text = "hi", updatedAt = 5L))),
        )
        coordinator.start()
        runCurrent()
        assertEquals(
            mapOf("c1" to ChatDraft(chatId = "c1", text = "hi", updatedAt = 5L)),
            state.drafts,
        )
    }

    @Test
    fun observeReceiptsWritesLatestReceipt() = runTest(dispatcher) {
        val chat = Chat(id = "c1")
        val state = State(listOf(chat))
        val coordinator = backgroundScope.buildCoordinator(
            state,
            getRecentMessages = { _, _ ->
                listOf(Message(id = "m1", chatId = "c1", senderId = "me", content = "hi"))
            },
        )
        coordinator.start()
        runCurrent()
        assertEquals(
            mapOf("c1" to Receipt(fromMe = true, status = MessageStatus.SENT)),
            state.receiptsByChat,
        )
    }
}
