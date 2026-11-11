package com.maodouchat.chatlist

import com.maodouchat.core.model.ArchiveSuggestion
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.SessionSnapshot
import com.maodouchat.core.session.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatlist.ChatListArchiveSuggestionCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListArchiveSuggestionCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @AfterTest
    fun tearDown() {
        com.maodouchat.session.CurrentSession.override = null
    }

    @Test
    fun dismissArchiveSuggestionPersistsAndFilters() = runTest(dispatcher) {
        val suggestion = ArchiveSuggestion("c1", 10, "idle")
        val uiState = MutableStateFlow(ChatListUiState(archiveSuggestions = listOf(suggestion)))
        com.maodouchat.session.CurrentSession.override = { SessionSnapshot("tok", "me") }
        val persisted = mutableListOf<Triple<String, String, Long>>()
        com.maodouchat.session.CurrentSession.override = { SessionSnapshot("tok", "me") }
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
        val suggestion = ArchiveSuggestion("c1", 10, "idle")
        val uiState = MutableStateFlow(
            ChatListUiState(chats = listOf(chat), archiveSuggestions = listOf(suggestion))
        )
        com.maodouchat.session.CurrentSession.override = { SessionSnapshot("tok", "me") }
        var archivedCalls = 0
        com.maodouchat.session.CurrentSession.override = { SessionSnapshot("tok", "me") }
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
        com.maodouchat.session.CurrentSession.override = { SessionSnapshot("tok", "me") }
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
                    ArchiveSuggestion("keep", 5, "a"),
                    ArchiveSuggestion("gone", 9, "b"),
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
