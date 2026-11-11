package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.UpdateChatSettingsRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListMutationCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    /** 替代 app 侧 ChatListUiState 的最小状态：列表与四个被协调器写的字段。 */
    private class State(initialChats: List<Chat>) {
        val chats = MutableStateFlow(initialChats)
        var errorMessage: String? = null
        var deletingChatIds: Set<String> = emptySet()
        var createdSecretChatId: String? = null
        var ownerTransferRequiredChatId: String? = null
    }

    private fun CoroutineScope.buildCoordinator(
        state: State,
        ownerId: String = "me",
        hasSession: Boolean = true,
        mayContinue: Boolean = true,
        secretChatFeatureEnabled: () -> Boolean = { true },
        deletedChatIds: MutableSet<String> = mutableSetOf(),
        settingsInFlight: MutableSet<String> = mutableSetOf(),
        cacheChats: suspend (List<Chat>) -> Unit = {},
        updateChatSettingsRemote: suspend (String, UpdateChatSettingsRequest) -> Result<ChatSettingsResponse> =
            { _, _ -> error("unexpected") },
        deleteChatRemote: suspend (String) -> Result<Unit> = { _ -> error("unexpected") },
        createChatRemote: suspend (List<String>, Boolean, String?, String?) -> Result<ChatDto> =
            { _, _, _, _ -> error("unexpected") },
        cleanupLocalChat: suspend (String, ConversationLocalCleanupSession) -> Unit = { _, _ -> },
    ) = ChatListMutationCoordinator(
        scope = this,
        getChats = { state.chats.value },
        updateChats = { transform -> state.chats.update(transform) },
        showError = { state.errorMessage = it },
        isDeletingChat = { state.deletingChatIds.contains(it) },
        addDeletingChat = { state.deletingChatIds += it },
        removeDeletingChat = { state.deletingChatIds -= it },
        setCreatedSecretChatId = { state.createdSecretChatId = it },
        showOwnerTransferRequired = {
            state.ownerTransferRequiredChatId = it
            state.errorMessage = null
        },
        deletedChatIds = deletedChatIds,
        settingsInFlight = settingsInFlight,
        ownerUserId = { ownerId },
        hasSession = { hasSession },
        sessionMayContinue = { mayContinue },
        cacheChats = cacheChats,
        updateChatSettingsRemote = updateChatSettingsRemote,
        deleteChatRemote = deleteChatRemote,
        createChatRemote = createChatRemote,
        touchSecretChat = {},
        cleanupLocalChat = cleanupLocalChat,
        cleanupSessionFor = { ConversationLocalCleanupSession(it, 1L) },
        onMuteApplied = {},
        sessionExpiredText = { "session-expired" },
        settingsSyncFailedText = { "sync-failed" },
        chatLeaveFailedText = { "leave-failed" },
        secretChatFeatureEnabled = secretChatFeatureEnabled,
        secretChatDisabledMessage = { "disabled" },
        secretChatStartFailedMessage = { "failed" },
        logWarning = { _, _ -> },
        ioDispatcher = dispatcher,
    )

    @Test
    fun updateChatSettingsAppliesOptimisticThenConfirmed() = runTest(dispatcher) {
        val chat = Chat(id = "c1", pinnedAt = 0, settingsUpdatedAt = 1)
        val optimistic = chat.copy(pinnedAt = 10, settingsUpdatedAt = 20)
        val state = State(listOf(chat))
        val cached = mutableListOf<List<Chat>>()
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            state = state,
            cacheChats = { cached += it },
            updateChatSettingsRemote = { _, _ ->
                remoteCalls += 1
                Result.success(
                    ChatSettingsResponse(
                        chatId = "c1",
                        pinnedAt = 10,
                        notificationsMuted = false,
                        archived = false,
                        markedUnread = false,
                        updatedAt = 99,
                    )
                )
            },
        )

        coordinator.updateChatSettings(
            chat = chat,
            optimistic = optimistic,
            request = UpdateChatSettingsRequest(pinned = true),
        )
        advanceUntilIdle()

        assertEquals(1, remoteCalls)
        assertEquals(10L, state.chats.value.single().pinnedAt)
        assertEquals(99L, state.chats.value.single().settingsUpdatedAt)
        assertTrue(cached.size >= 2)
    }

    @Test
    fun updateChatSettingsIgnoresReentry() = runTest(dispatcher) {
        val chat = Chat(id = "c1")
        val state = State(listOf(chat))
        val inFlight = mutableSetOf("c1")
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            state = state,
            settingsInFlight = inFlight,
            updateChatSettingsRemote = { _, _ ->
                remoteCalls += 1
                Result.success(
                    ChatSettingsResponse("c1", 0, false, false, false, 1)
                )
            },
        )

        coordinator.updateChatSettings(chat, chat.copy(pinnedAt = 1), UpdateChatSettingsRequest(pinned = true))
        advanceUntilIdle()
        assertEquals(0, remoteCalls)
    }

    @Test
    fun deleteChatRemovesThenConfirmsLeave() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessage = "hi")
        val state = State(listOf(chat))
        val deleted = mutableSetOf<String>()
        var cleaned = false
        val coordinator = buildCoordinator(
            state = state,
            deletedChatIds = deleted,
            deleteChatRemote = { id ->
                assertEquals("c1", id)
                Result.success(Unit)
            },
            cleanupLocalChat = { _, _ -> cleaned = true },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertTrue(state.chats.value.none { it.id == "c1" })
        assertTrue(deleted.contains("c1"))
        assertTrue(cleaned)
        assertFalse(state.deletingChatIds.contains("c1"))
    }

    @Test
    fun deleteChatRollsBackOnFailure() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessageTime = 50)
        val other = Chat(id = "c0", lastMessageTime = 10)
        val state = State(listOf(chat, other))
        val coordinator = buildCoordinator(
            state = state,
            deleteChatRemote = { _ -> Result.failure(IllegalStateException("nope")) },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertEquals(setOf("c1", "c0"), state.chats.value.map { it.id }.toSet())
        assertEquals("leave-failed", state.errorMessage)
    }

    @Test
    fun updateChatSettingsRemoteFailureRollsBackAndReportsSyncFailed() = runTest(dispatcher) {
        val chat = Chat(id = "c1", pinnedAt = 0, settingsUpdatedAt = 1)
        val optimistic = chat.copy(pinnedAt = 10, settingsUpdatedAt = 20)
        val state = State(listOf(chat))
        val inFlight = mutableSetOf<String>()
        val coordinator = buildCoordinator(
            state = state,
            settingsInFlight = inFlight,
            updateChatSettingsRemote = { _, _ -> Result.failure(IllegalStateException("down")) },
        )

        coordinator.updateChatSettings(chat, optimistic, UpdateChatSettingsRequest(pinned = true))
        advanceUntilIdle()

        val restored = state.chats.value.single()
        assertEquals(0L, restored.pinnedAt)
        assertEquals(1L, restored.settingsUpdatedAt)
        assertEquals("sync-failed", state.errorMessage)
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun blankOwnerRejectsUpdateChatSettingsWithSessionExpired() = runTest(dispatcher) {
        val chat = Chat(id = "c1", pinnedAt = 0)
        val state = State(listOf(chat))
        var remoteCalls = 0
        val inFlight = mutableSetOf<String>()
        val coordinator = buildCoordinator(
            state = state,
            ownerId = "",
            settingsInFlight = inFlight,
            updateChatSettingsRemote = { _, _ ->
                remoteCalls += 1
                Result.success(ChatSettingsResponse("c1", 0, false, false, false, 1))
            },
        )

        coordinator.updateChatSettings(chat, chat.copy(pinnedAt = 9), UpdateChatSettingsRequest(pinned = true))
        advanceUntilIdle()

        assertEquals(0, remoteCalls)
        assertEquals(0L, state.chats.value.single().pinnedAt)
        assertEquals("session-expired", state.errorMessage)
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun blankOwnerRejectsDeleteChatWithSessionExpired() = runTest(dispatcher) {
        val chat = Chat(id = "c1")
        val state = State(listOf(chat))
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            state = state,
            ownerId = "",
            deleteChatRemote = { _ ->
                remoteCalls += 1
                Result.success(Unit)
            },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertEquals(0, remoteCalls)
        assertEquals(listOf("c1"), state.chats.value.map { it.id })
        assertEquals("session-expired", state.errorMessage)
        assertTrue(state.deletingChatIds.isEmpty())
    }

    @Test
    fun deleteChatIgnoresReentrantCallWhileInFlight() = runTest(dispatcher) {
        val chat = Chat(id = "c1")
        val state = State(listOf(chat)).also { it.deletingChatIds = setOf("c1") }
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            state = state,
            deleteChatRemote = { _ ->
                remoteCalls += 1
                Result.success(Unit)
            },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertEquals(0, remoteCalls)
        assertEquals(listOf("c1"), state.chats.value.map { it.id })
    }

    @Test
    fun deleteChatCancellationRestoresChatAndClearsInFlight() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessageTime = 50)
        val state = State(listOf(chat))
        val coordinator = buildCoordinator(
            state = state,
            deleteChatRemote = { _ -> throw CancellationException("gone") },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertEquals(listOf("c1"), state.chats.value.map { it.id })
        assertTrue(state.deletingChatIds.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun clearMarkedUnreadSkipsUnmarkedChat() = runTest(dispatcher) {
        val chat = Chat(id = "c1", markedUnread = false, unreadCount = 0)
        val state = State(listOf(chat))
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            state = state,
            updateChatSettingsRemote = { _, _ ->
                remoteCalls += 1
                Result.success(ChatSettingsResponse("c1", 0, false, false, false, 1))
            },
        )

        coordinator.clearMarkedUnreadAfterOpen(chat)
        advanceUntilIdle()

        assertEquals(0, remoteCalls)
        assertEquals(chat, state.chats.value.single())
    }

    @Test
    fun clearMarkedUnreadAppliesOptimisticThenConfirmed() = runTest(dispatcher) {
        val chat = Chat(id = "c1", markedUnread = true, unreadCount = 3)
        val state = State(listOf(chat))
        val coordinator = buildCoordinator(
            state = state,
            updateChatSettingsRemote = { _, _ ->
                Result.success(
                    ChatSettingsResponse("c1", 0, false, false, false, 50)
                )
            },
        )

        coordinator.clearMarkedUnreadAfterOpen(chat)
        advanceUntilIdle()

        val updated = state.chats.value.single()
        assertFalse(updated.markedUnread)
        assertEquals(0, updated.unreadCount)
        assertEquals(50L, updated.settingsUpdatedAt)
    }

    @Test
    fun startSecretChatDisabledReportsError() = runTest(dispatcher) {
        val state = State(emptyList())
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            state = state,
            secretChatFeatureEnabled = { false },
            createChatRemote = { _, _, _, _ ->
                remoteCalls += 1
                Result.success(ChatDto(id = "s1"))
            },
        )

        coordinator.startSecretChatWithPeer("peer")
        advanceUntilIdle()

        assertEquals(0, remoteCalls)
        assertEquals("disabled", state.errorMessage)
        assertNull(state.createdSecretChatId)
    }

    @Test
    fun startSecretChatSuccessSetsCreatedSecretChatId() = runTest(dispatcher) {
        val state = State(emptyList())
        val cached = mutableListOf<List<Chat>>()
        val coordinator = buildCoordinator(
            state = state,
            cacheChats = { cached += it },
            createChatRemote = { _, _, _, _ -> Result.success(ChatDto(id = "s1")) },
        )

        coordinator.startSecretChatWithPeer("peer")
        advanceUntilIdle()

        assertEquals("s1", state.createdSecretChatId)
        assertEquals(listOf("s1"), cached.single().map { it.id })
    }

    @Test
    fun clearCreatedSecretChatClearsMarker() = runTest(dispatcher) {
        val state = State(emptyList()).also { it.createdSecretChatId = "s1" }
        val coordinator = buildCoordinator(state = state)

        coordinator.clearCreatedSecretChat()

        assertNull(state.createdSecretChatId)
    }
}
