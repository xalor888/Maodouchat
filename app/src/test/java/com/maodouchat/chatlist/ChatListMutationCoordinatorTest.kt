package com.maodouchat.chatlist

import com.maodouchat.R
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.data.model.Chat
import com.maodouchat.network.ChatSettingsResponse
import com.maodouchat.network.TokenManager
import com.maodouchat.network.UpdateChatSettingsRequest
import com.maodouchat.security.SecureSessionManager
import com.maodouchat.ui.screen.chatlist.ChatListMutationCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListMutationCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        mockkObject(SecureSessionManager)
        every { SecureSessionManager.isPurgeInProgress() } returns false
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun updateChatSettingsAppliesOptimisticThenConfirmed() = runTest(dispatcher) {
        val chat = Chat(id = "c1", pinnedAt = 0, settingsUpdatedAt = 1)
        val optimistic = chat.copy(pinnedAt = 10, settingsUpdatedAt = 20)
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(chat)))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        val cached = mutableListOf<List<Chat>>()
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
            cacheChats = { cached += it },
            updateChatSettingsRemote = { _, _, _ ->
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
        assertEquals(10L, uiState.value.chats.single().pinnedAt)
        assertEquals(99L, uiState.value.chats.single().settingsUpdatedAt)
        assertTrue(cached.size >= 2)
    }

    @Test
    fun updateChatSettingsIgnoresReentry() = runTest(dispatcher) {
        val chat = Chat(id = "c1")
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(chat)))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        val inFlight = mutableSetOf("c1")
        var remoteCalls = 0
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
            settingsInFlight = inFlight,
            updateChatSettingsRemote = { _, _, _ ->
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
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(chat)))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        val deleted = mutableSetOf<String>()
        var cleaned = false
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
            deletedChatIds = deleted,
            deleteChatRemote = { _, id ->
                assertEquals("c1", id)
                Result.success(Unit)
            },
            cleanupLocalChat = { _, _ -> cleaned = true },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertTrue(uiState.value.chats.none { it.id == "c1" })
        assertTrue(deleted.contains("c1"))
        assertTrue(cleaned)
        assertFalse(uiState.value.deletingChatIds.contains("c1"))
    }

    @Test
    fun deleteChatRollsBackOnFailure() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessageTime = 50)
        val other = Chat(id = "c0", lastMessageTime = 10)
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(chat, other)))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        val coordinator = buildCoordinator(
            uiState = uiState,
            tokenManager = tokenManager,
            deleteChatRemote = { _, _ -> Result.failure(IllegalStateException("nope")) },
        )

        coordinator.deleteChat("c1")
        advanceUntilIdle()

        assertEquals(setOf("c1", "c0"), uiState.value.chats.map { it.id }.toSet())
        assertEquals("msg-${R.string.chat_leave_failed}", uiState.value.errorMessage)
    }

    private fun kotlinx.coroutines.CoroutineScope.buildCoordinator(
        uiState: MutableStateFlow<ChatListUiState>,
        tokenManager: TokenManager,
        deletedChatIds: MutableSet<String> = mutableSetOf(),
        settingsInFlight: MutableSet<String> = mutableSetOf(),
        cacheChats: suspend (List<Chat>) -> Unit = {},
        updateChatSettingsRemote: suspend (String, String, UpdateChatSettingsRequest) -> Result<ChatSettingsResponse> =
            { _, _, _ -> error("unexpected") },
        deleteChatRemote: suspend (String, String) -> Result<Unit> = { _, _ -> error("unexpected") },
        cleanupLocalChat: suspend (String, ConversationLocalCleanupSession) -> Unit = { _, _ -> },
    ) = ChatListMutationCoordinator(
        scope = this,
        uiState = uiState,
        tokenManager = tokenManager,
        deletedChatIds = deletedChatIds,
        settingsInFlight = settingsInFlight,
        ownerUserId = { tokenManager.getUserId().orEmpty() },
        cacheChats = cacheChats,
        updateChatSettingsRemote = updateChatSettingsRemote,
        deleteChatRemote = deleteChatRemote,
        createChatRemote = { _, _, _, _, _ -> error("unexpected") },
        touchSecretChat = {},
        cleanupLocalChat = cleanupLocalChat,
        cleanupSessionFor = { ConversationLocalCleanupSession(it, 1L) },
        onMuteApplied = {},
        text = { "msg-$it" },
        secretChatFeatureEnabled = { true },
        secretChatDisabledMessage = { "disabled" },
        secretChatStartFailedMessage = { "failed" },
        ioDispatcher = dispatcher,
    )
}