package com.maodouchat.chatlist

import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.ui.screen.chatlist.ChatListPreviewCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListPreviewCoordinatorTest {

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
    fun enrichServerChatPreviewLocalizesMediaPlaceholder() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        val coordinator = buildCoordinator(uiState)
        val server = Chat(
            id = "c1",
            lastMessage = "[图片]",
            lastMessageType = MessageType.IMAGE,
            lastMessageTime = 10L,
        )

        val enriched = coordinator.enrichServerChatPreview(server, ownerUserId = "me")

        assertEquals("preview_image", enriched.lastMessage)
        assertEquals(MessageType.IMAGE, enriched.lastMessageType)
    }

    @Test
    fun applyChatListPreviewUpdatesMemoryAndPersists() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessage = "old", lastMessageTime = 1L, unreadCount = 2)
        val uiState = MutableStateFlow(ChatListUiState(chats = listOf(chat)))
        val persisted = mutableListOf<Chat>()
        val coordinator = buildCoordinator(
            uiState = uiState,
            getCachedChat = { chat },
            cacheChats = { persisted += it },
            withOwnerRoomWrite = { _, block ->
                block()
                true
            },
        )

        coordinator.applyChatListPreview(
            chatId = "c1",
            previewText = "hello",
            messageType = MessageType.TEXT,
            timestamp = 99L,
            unreadDelta = 1,
            ownerUserId = "me",
            sessionGeneration = 1L,
        )
        advanceUntilIdle()

        assertEquals("hello", uiState.value.chats.single().lastMessage)
        assertEquals(3, uiState.value.chats.single().unreadCount)
        assertEquals(1, persisted.size)
        assertEquals("hello", persisted.single().lastMessage)
        assertEquals(99L, persisted.single().lastMessageTime)
    }

    @Test
    fun enrichPrefersLocalPlaintextTailOverEncryptedPlaceholder() = runTest(dispatcher) {
        val uiState = MutableStateFlow(ChatListUiState())
        val local = Message(
            id = "m1",
            chatId = "c1",
            senderId = "me",
            content = "secret ok",
            type = MessageType.TEXT,
            timestamp = 50L,
        )
        val coordinator = buildCoordinator(
            uiState = uiState,
            getRecentMessages = { _, _ -> listOf(local) },
        )
        val server = Chat(
            id = "c1",
            lastMessage = "preview_encrypted",
            lastMessageType = MessageType.TEXT,
            lastMessageTime = 10L,
        )

        val enriched = coordinator.enrichServerChatPreview(server, ownerUserId = "me")

        assertEquals("secret ok", enriched.lastMessage)
        assertTrue(enriched.lastMessageTime >= 50L)
    }

    private fun buildCoordinator(
        uiState: MutableStateFlow<ChatListUiState>,
        getCachedChat: suspend (String) -> Chat? = { null },
        cacheChats: suspend (List<Chat>) -> Unit = {},
        getRecentMessages: suspend (String, Int) -> List<Message> = { _, _ -> emptyList() },
        withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean =
            { _, block ->
                block()
                true
            },
    ): ChatListPreviewCoordinator =
        ChatListPreviewCoordinator(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            uiState = uiState,
            currentUserId = { "me" },
            currentSessionGeneration = { 1L },
            isOwnerSessionCurrent = { true },
            withOwnerRoomWrite = withOwnerRoomWrite,
            getCachedChat = getCachedChat,
            cacheChats = cacheChats,
            getRecentMessages = getRecentMessages,
            text = { id ->
                when (id) {
                    R.string.message_preview_image -> "preview_image"
                    R.string.message_preview_encrypted -> "preview_encrypted"
                    R.string.chat_message_revoked_placeholder -> "revoked"
                    R.string.message_preview_nudge -> "nudge"
                    R.string.message_preview_system -> "system"
                    else -> "str_$id"
                }
            },
            nudgeYouNudged = { "you->$it" },
            nudgeTheyNudgedYou = { "$it->you" },
            nudgeTheyNudgedTarget = { s, t -> "$s->$t" },
        )
}
