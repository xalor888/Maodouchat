package com.maodouchat.chatlist

import com.maodouchat.R
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.screen.chatlist.ChatListLocalProjectionCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListLocalProjectionCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun onSearchQueryChangeDebouncesAndFiltersLockedSecret() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getUserId() } returns "me"
        val coordinator = ChatListLocalProjectionCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            ownerUserId = { "me" },
            observeDraftsForOwner = { flowOf(emptyList()) },
            getRecentMessages = { _, _ -> emptyList() },
            listLockedChatIds = { setOf("locked") },
            searchChatIdsByMessageContent = { listOf("ok", "locked", "secret") },
            listSecretChatIds = { setOf("secret") },
            trustChangedRemoteIds = { _, _ -> emptySet() },
            text = { "err" },
            ioDispatcher = dispatcher,
            searchDebounceMs = ChatListLocalProjectionCoordinator.LIST_MESSAGE_SEARCH_DEBOUNCE_MS,
        )

        coordinator.onSearchQueryChange("hello")
        advanceTimeBy(ChatListLocalProjectionCoordinator.LIST_MESSAGE_SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()

        assertEquals(setOf("ok"), uiState.value.messageMatchedChatIds)
        assertEquals("hello", uiState.value.searchQuery)
    }

    @Test
    fun onSearchQueryChangeClearsWhenTooShort() = runTest(dispatcher) {
        val uiState = MutableStateFlow(
            ChatListUiState(messageMatchedChatIds = setOf("x"), searchQuery = "ab")
        )
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getUserId() } returns "me"
        val coordinator = ChatListLocalProjectionCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            ownerUserId = { "me" },
            observeDraftsForOwner = { flowOf(emptyList()) },
            getRecentMessages = { _, _ -> emptyList() },
            listLockedChatIds = { emptySet() },
            searchChatIdsByMessageContent = { error("should not search") },
            listSecretChatIds = { emptySet() },
            trustChangedRemoteIds = { _, _ -> emptySet() },
            text = { "err" },
            ioDispatcher = dispatcher,
        )

        coordinator.onSearchQueryChange("a")
        advanceUntilIdle()

        assertTrue(uiState.value.messageMatchedChatIds.isEmpty())
        assertEquals("a", uiState.value.searchQuery)
    }

    @Test
    fun refreshSecretChatsProjectsFromUiList() = runTest(dispatcher) {
        val uiState = MutableStateFlow(
            ChatListUiState(
                chats = listOf(
                    Chat(id = "s1", chatType = "SECRET"),
                    Chat(id = "d1"),
                )
            )
        )
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getUserId() } returns "me"
        val coordinator = ChatListLocalProjectionCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            ownerUserId = { "me" },
            observeDraftsForOwner = { flowOf(emptyList()) },
            getRecentMessages = { _, _ -> emptyList() },
            listLockedChatIds = { emptySet() },
            searchChatIdsByMessageContent = { emptyList() },
            listSecretChatIds = { emptySet() },
            trustChangedRemoteIds = { _, _ -> emptySet() },
            text = { "err" },
            ioDispatcher = dispatcher,
        )

        coordinator.refreshSecretChats()
        advanceUntilIdle()

        assertEquals(setOf("s1"), uiState.value.secretChatIds)
    }

    @Test
    fun refreshIdentityWarningsProjectsChangedPeers() = runTest(dispatcher) {
        val peer = com.maodouchat.data.model.User(id = "peer", name = "Peer")
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(Chat(id = "c1", isGroup = false, participants = listOf(peer))))
        )
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getUserId() } returns "owner-1"
        val coordinator = ChatListLocalProjectionCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            ownerUserId = { "owner-1" },
            observeDraftsForOwner = { flowOf(emptyList()) },
            getRecentMessages = { _, _ -> emptyList() },
            listLockedChatIds = { emptySet() },
            searchChatIdsByMessageContent = { emptyList() },
            listSecretChatIds = { emptySet() },
            trustChangedRemoteIds = { _, remotes -> remotes },
            text = { "err" },
            ioDispatcher = dispatcher,
        )

        coordinator.refreshIdentityWarnings()
        advanceUntilIdle()

        assertEquals(setOf("peer"), uiState.value.identityChangedUserIds)
    }
}
