package com.maodouchat.chatlist

import com.maodouchat.ai.AiArchiveSuggestion
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MissedCall
import com.maodouchat.data.model.User
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatlist.ChatListArchiveSuggestionCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListMissedCallCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListMissedCallAndArchiveCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        com.maodouchat.session.CurrentSession.override = null
        unmockkAll()
    }

    @Test
    fun markMissedCallsReadClearsTrayAndCenter() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        val uiState = MutableStateFlow(ChatListUiState(missedCalls = listOf(call)))
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        var marked = false
        val cancelled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val coordinator = ChatListMissedCallCoordinator(
            scope = this,
            uiState = uiState,
            ownerUserId = { "me" },
            markAllRead = { marked = true },
            clearAll = {},
            deleteCall = {},
            cancelMissedCallNotification = { cancelled += it },
            removeCenterItem = { removed += it },
        )

        coordinator.markMissedCallsRead()
        advanceUntilIdle()

        assertTrue(marked)
        assertEquals(listOf("m1"), cancelled)
        assertEquals(listOf("missed_m1"), removed)
    }

    @Test
    fun removeMissedCallLocallyDropsUiAndDeletes() = runTest(dispatcher) {
        val call = MissedCall(id = "m1", callerId = "u1", callerName = "A", callType = "audio", receivedAt = 1L)
        val uiState = MutableStateFlow(ChatListUiState(missedCalls = listOf(call)))
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        var deleted: String? = null
        val cancelled = mutableListOf<String>()
        val coordinator = ChatListMissedCallCoordinator(
            scope = this,
            uiState = uiState,
            ownerUserId = { "me" },
            markAllRead = {},
            clearAll = {},
            deleteCall = { deleted = it },
            cancelMissedCallNotification = { cancelled += it },
            removeCenterItem = {},
        )

        coordinator.removeMissedCallLocally("m1")
        advanceUntilIdle()

        assertTrue(uiState.value.missedCalls.isEmpty())
        assertEquals("m1", deleted)
        assertEquals(listOf("m1"), cancelled)
    }

    @Test
    fun findDirectChatIdForUserResolvesPeer() {
        val peer = User(id = "u2", name = "Peer")
        val chat = Chat(id = "c1", isGroup = false, participants = listOf(peer))
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(chat)))
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        val coordinator = ChatListMissedCallCoordinator(
            scope = mockk(relaxed = true),
            uiState = uiState,
            ownerUserId = { "me" },
            markAllRead = {},
            clearAll = {},
            deleteCall = {},
            cancelMissedCallNotification = {},
            removeCenterItem = {},
        )

        assertEquals("c1", coordinator.findDirectChatIdForUser("u2"))
        assertNull(coordinator.findDirectChatIdForUser("missing"))
    }

    @Test
    fun dismissArchiveSuggestionPersistsAndFilters() = runTest(dispatcher) {
        val suggestion = AiArchiveSuggestion.Suggestion("c1", 10, "idle")
        val uiState = MutableStateFlow(ChatListUiState(archiveSuggestions = listOf(suggestion)))
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        val persisted = mutableListOf<Triple<String, String, Long>>()
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        val coordinator = ChatListArchiveSuggestionCoordinator(
            scope = this,
            uiState = uiState,
            ownerUserId = { "me" },
            ownerSession = { OwnerSessionSnapshot(it, 1L) },
            isOwnerSessionCurrent = { true },
            loadDismissedIds = { emptyList() },
            addDismissal = { owner, chatId, at -> persisted += Triple(owner, chatId, at) },
            refreshSuggestions = { emptyList() },
            onArchiveChat = {},
            ioDispatcher = dispatcher,
        )

        coordinator.dismissArchiveSuggestion("c1")
        advanceUntilIdle()

        assertTrue(uiState.value.archiveSuggestions.isEmpty())
        assertEquals(1, persisted.size)
        assertEquals("me", persisted.single().first)
        assertEquals("c1", persisted.single().second)
    }

    @Test
    fun archiveChatFromSuggestionSkipsAlreadyArchived() = runTest(dispatcher) {
        val chat = Chat(id = "c1", archived = true)
        val suggestion = AiArchiveSuggestion.Suggestion("c1", 10, "idle")
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(chat), archiveSuggestions = listOf(suggestion))
        )
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        var archivedCalls = 0
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        val coordinator = ChatListArchiveSuggestionCoordinator(
            scope = this,
            uiState = uiState,
            ownerUserId = { "me" },
            ownerSession = { OwnerSessionSnapshot(it, 1L) },
            isOwnerSessionCurrent = { true },
            loadDismissedIds = { emptyList() },
            addDismissal = { _, _, _ -> },
            refreshSuggestions = { emptyList() },
            onArchiveChat = { archivedCalls += 1 },
            ioDispatcher = dispatcher,
        )

        coordinator.archiveChatFromSuggestion(chat)
        advanceUntilIdle()

        assertEquals(0, archivedCalls)
        assertTrue(uiState.value.archiveSuggestions.isEmpty())
    }

    @Test
    fun loadArchiveSuggestionsFiltersDismissed() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot("tok", "me") }
        val coordinator = ChatListArchiveSuggestionCoordinator(
            scope = this,
            uiState = uiState,
            ownerUserId = { "me" },
            ownerSession = { OwnerSessionSnapshot(it, 1L) },
            isOwnerSessionCurrent = { true },
            loadDismissedIds = { emptyList() },
            addDismissal = { _, _, _ -> },
            refreshSuggestions = {
                listOf(
                    AiArchiveSuggestion.Suggestion("keep", 5, "a"),
                    AiArchiveSuggestion.Suggestion("gone", 9, "b"),
                )
            },
            onArchiveChat = {},
            ioDispatcher = dispatcher,
        )

        coordinator.dismissArchiveSuggestion("gone")
        coordinator.loadArchiveSuggestions()
        advanceUntilIdle()

        assertEquals(listOf("keep"), uiState.value.archiveSuggestions.map { it.chatId })
    }
}
