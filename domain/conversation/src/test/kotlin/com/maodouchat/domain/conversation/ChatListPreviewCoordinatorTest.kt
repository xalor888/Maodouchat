package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
class ChatListPreviewCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun enrichServerChatPreviewLocalizesMediaPlaceholder() = runTest(dispatcher) {
        val chats = MutableStateFlow<List<Chat>>(emptyList())
        val coordinator = buildCoordinator(chats)
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
        val chats = MutableStateFlow(listOf(chat))
        val persisted = mutableListOf<Chat>()
        val coordinator = buildCoordinator(
            chats = chats,
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

        assertEquals("hello", chats.value.single().lastMessage)
        assertEquals(3, chats.value.single().unreadCount)
        assertEquals(1, persisted.size)
        assertEquals("hello", persisted.single().lastMessage)
        assertEquals(99L, persisted.single().lastMessageTime)
    }

    @Test
    fun enrichPrefersLocalPlaintextTailOverEncryptedPlaceholder() = runTest(dispatcher) {
        val chats = MutableStateFlow<List<Chat>>(emptyList())
        val local = Message(
            id = "m1",
            chatId = "c1",
            senderId = "me",
            content = "secret ok",
            type = MessageType.TEXT,
            timestamp = 50L,
        )
        val coordinator = buildCoordinator(
            chats = chats,
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

    @Test
    fun applyChatListPreviewStaleSessionReturnsEarly() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessage = "old", lastMessageTime = 1L)
        val chats = MutableStateFlow(listOf(chat))
        val persisted = mutableListOf<Chat>()
        val coordinator = buildCoordinator(
            chats = chats,
            isOwnerSessionCurrent = { false },
            getCachedChat = { chat },
            cacheChats = { persisted += it },
        )

        coordinator.applyChatListPreview(
            chatId = "c1",
            previewText = "hello",
            messageType = MessageType.TEXT,
            timestamp = 99L,
            ownerUserId = "me",
            sessionGeneration = 1L,
        )
        advanceUntilIdle()

        assertEquals("old", chats.value.single().lastMessage)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun applyChatListPreviewRoomWriteFailureKeepsMemoryProjection() = runTest(dispatcher) {
        // Room 回写失败只打日志：内存投影已完成，不崩。
        val chat = Chat(id = "c1", lastMessage = "old", lastMessageTime = 1L)
        val chats = MutableStateFlow(listOf(chat))
        val persisted = mutableListOf<Chat>()
        val coordinator = buildCoordinator(
            chats = chats,
            getCachedChat = { chat },
            cacheChats = { persisted += it },
            withOwnerRoomWrite = { _, _ -> throw RuntimeException("room down") },
        )

        coordinator.applyChatListPreview(
            chatId = "c1",
            previewText = "hello",
            messageType = MessageType.TEXT,
            timestamp = 99L,
            ownerUserId = "me",
            sessionGeneration = 1L,
        )
        advanceUntilIdle()

        assertEquals("hello", chats.value.single().lastMessage)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun enrichServerChatPreviewCancellationPropagates() = runTest(dispatcher) {
        val chats = MutableStateFlow<List<Chat>>(emptyList())
        val coordinator = buildCoordinator(
            chats = chats,
            getRecentMessages = { _, _ -> throw CancellationException("gone") },
        )
        val server = Chat(
            id = "c1",
            lastMessage = "preview_encrypted",
            lastMessageType = MessageType.TEXT,
            lastMessageTime = 10L,
        )

        assertFailsWith<CancellationException> {
            coordinator.enrichServerChatPreview(server, ownerUserId = "me")
        }
    }

    @Test
    fun applyChatListPreviewStaleTimestampDoesNotRewindPersistedSortKey() =
        runTest(dispatcher) {
            val chat = Chat(id = "c1", lastMessage = "old", lastMessageTime = 100L)
            val chats = MutableStateFlow(listOf(chat))
            val persisted = mutableListOf<Chat>()
            val coordinator = buildCoordinator(
                chats = chats,
                getCachedChat = { chat },
                cacheChats = { persisted += it },
            )

            coordinator.applyChatListPreview(
                chatId = "c1",
                previewText = "late",
                messageType = MessageType.TEXT,
                timestamp = 50L,
                ownerUserId = "me",
                sessionGeneration = 1L,
            )
            advanceUntilIdle()

            assertEquals(1, persisted.size)
            assertEquals(100L, persisted.single().lastMessageTime)
        }

    @Test
    fun refreshChatListPreviewFromLocalFailureIsSwallowed() = runTest(dispatcher) {
        val chat = Chat(id = "c1", lastMessage = "old", lastMessageTime = 1L)
        val chats = MutableStateFlow(listOf(chat))
        val coordinator = buildCoordinator(
            chats = chats,
            getRecentMessages = { _, _ -> throw RuntimeException("db gone") },
        )

        coordinator.refreshChatListPreviewFromLocal(chatId = "c1")
        advanceUntilIdle()

        assertEquals("old", chats.value.single().lastMessage)
        assertEquals(1L, chats.value.single().lastMessageTime)
    }

    private fun buildCoordinator(
        chats: MutableStateFlow<List<Chat>>,
        isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean = { true },
        getCachedChat: suspend (String) -> Chat? = { null },
        cacheChats: suspend (List<Chat>) -> Unit = {},
        getRecentMessages: suspend (String, Int) -> List<Message> = { _, _ -> emptyList() },
        withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean =
            { _, block ->
                block()
                true
            },
        warnings: MutableList<String> = mutableListOf(),
    ): ChatListPreviewCoordinator =
        ChatListPreviewCoordinator(
            scope = CoroutineScope(dispatcher),
            getChats = { chats.value },
            updateChats = { transform -> chats.update { transform(it) } },
            currentUserId = { "me" },
            currentSessionGeneration = { 1L },
            isOwnerSessionCurrent = isOwnerSessionCurrent,
            withOwnerRoomWrite = withOwnerRoomWrite,
            getCachedChat = getCachedChat,
            cacheChats = cacheChats,
            getRecentMessages = getRecentMessages,
            mediaPreviewText = { type ->
                when (type) {
                    MessageType.IMAGE -> "preview_image"
                    MessageType.GIF -> "preview_gif"
                    MessageType.STICKER -> "preview_sticker"
                    MessageType.LOCATION -> "preview_location"
                    MessageType.VOICE -> "preview_voice"
                    MessageType.VIDEO -> "preview_video"
                    MessageType.FILE -> "preview_file"
                    MessageType.NUDGE -> "nudge"
                    MessageType.SYSTEM -> "system"
                    else -> "preview_encrypted"
                }
            },
            encryptedPlaceholder = "preview_encrypted",
            revokedPlaceholder = "revoked",
            nudgePlaceholder = "nudge",
            systemPlaceholder = "system",
            logWarning = { message, _ -> warnings += message },
            nudgeYouNudged = { "you->$it" },
            nudgeTheyNudgedYou = { "$it->you" },
            nudgeTheyNudgedTarget = { s, t -> "$s->$t" },
        )
}
