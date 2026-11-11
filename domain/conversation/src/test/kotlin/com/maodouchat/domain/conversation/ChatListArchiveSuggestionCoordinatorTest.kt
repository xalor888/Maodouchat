package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ArchiveSuggestion
import com.maodouchat.core.model.Chat
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListArchiveSuggestionCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    private class State(
        var suggestions: List<ArchiveSuggestion> = emptyList(),
        var chats: List<Chat> = emptyList(),
    )

    private fun buildCoordinator(
        state: State,
        scope: kotlinx.coroutines.CoroutineScope,
        ownerId: String = "me",
        loadDismissedIds: suspend (String) -> List<String> = { emptyList() },
        addDismissal: suspend (String, String, Long) -> Unit = { _, _, _ -> },
        refreshSuggestions: suspend () -> List<ArchiveSuggestion> = { emptyList() },
        onArchiveChat: (Chat) -> Unit = {},
    ) = ChatListArchiveSuggestionCoordinator(
        scope = scope,
        getArchiveSuggestions = { state.suggestions },
        updateArchiveSuggestions = { transform -> state.suggestions = transform(state.suggestions) },
        getChats = { state.chats },
        logWarning = { _, _ -> },
        ownerUserId = { ownerId },
        ownerSession = { OwnerSessionSnapshot(it, 1L) },
        isOwnerSessionCurrent = { true },
        loadDismissedIds = loadDismissedIds,
        addDismissal = addDismissal,
        refreshSuggestions = refreshSuggestions,
        onArchiveChat = onArchiveChat,
        ioDispatcher = dispatcher,
    )

    @Test
    fun dismissArchiveSuggestionPersistsAndFilters() = runTest(dispatcher) {
        val suggestion = ArchiveSuggestion("c1", 10, "idle")
        val state = State(listOf(suggestion))
        val persisted = mutableListOf<Triple<String, String, Long>>()
        val coordinator = buildCoordinator(
            state, this,
            addDismissal = { owner, chatId, at -> persisted += Triple(owner, chatId, at) },
        )

        coordinator.dismissArchiveSuggestion("c1")
        advanceUntilIdle()

        assertTrue(state.suggestions.isEmpty())
        assertEquals(1, persisted.size)
        assertEquals("me", persisted.single().first)
        assertEquals("c1", persisted.single().second)
    }

    @Test
    fun archiveChatFromSuggestionSkipsAlreadyArchived() = runTest(dispatcher) {
        val chat = Chat(id = "c1", archived = true)
        val suggestion = ArchiveSuggestion("c1", 10, "idle")
        val state = State(listOf(suggestion), listOf(chat))
        var archivedCalls = 0
        val coordinator = buildCoordinator(
            state, this,
            onArchiveChat = { archivedCalls += 1 },
        )

        coordinator.archiveChatFromSuggestion(chat)
        advanceUntilIdle()

        assertEquals(0, archivedCalls)
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun loadArchiveSuggestionsFiltersDismissed() = runTest(dispatcher) {
        val state = State()
        val coordinator = buildCoordinator(
            state, this,
            refreshSuggestions = {
                listOf(
                    ArchiveSuggestion("keep", 5, "a"),
                    ArchiveSuggestion("gone", 9, "b"),
                )
            },
        )

        coordinator.dismissArchiveSuggestion("gone")
        coordinator.loadArchiveSuggestions()
        advanceUntilIdle()

        assertEquals(listOf("keep"), state.suggestions.map { it.chatId })
    }

    @Test
    fun dismissAllArchiveSuggestionsClearsAndPersists() = runTest(dispatcher) {
        val state = State(
            listOf(ArchiveSuggestion("c1", 5, "a"), ArchiveSuggestion("c2", 6, "b")),
        )
        val persisted = mutableListOf<String>()
        val coordinator = buildCoordinator(
            state, this,
            addDismissal = { _, chatId, _ -> persisted += chatId },
        )

        coordinator.dismissAllArchiveSuggestions()
        advanceUntilIdle()

        assertTrue(state.suggestions.isEmpty())
        assertEquals(listOf("c1", "c2"), persisted.sorted())
    }

    @Test
    fun startLoadsDismissedIdsAndFiltersShown() = runTest(dispatcher) {
        val state = State(
            listOf(ArchiveSuggestion("keep", 5, "a"), ArchiveSuggestion("gone", 9, "b")),
        )
        val coordinator = buildCoordinator(
            state, this,
            loadDismissedIds = { listOf("gone") },
        )

        coordinator.start()
        advanceUntilIdle()

        assertEquals(listOf("keep"), state.suggestions.map { it.chatId })
    }
}
