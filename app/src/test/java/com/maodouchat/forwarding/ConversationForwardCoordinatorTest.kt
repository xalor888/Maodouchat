package com.maodouchat.forwarding

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationForwardCoordinatorTest {
    @Test
    fun `durable text forward is not failed by post commit notification`() = runTest {
        val staged = mutableListOf<Message>()
        val coordinator = coordinator(
            stageMessage = { message, _ -> staged += message },
            onMessageSent = { _, _, _ -> error("sound unavailable") },
        )

        val result = coordinator.forward(chat(), message(), sourceName = "Alice")

        assertEquals(staged.single(), result)
        assertEquals(MessageStatus.SENDING, staged.single().status)
        assertEquals("Alice", staged.single().parsedMeta().forwardedFrom)
    }

    @Test
    fun `existing forward source wins over current sender name`() = runTest {
        val staged = mutableListOf<Message>()
        val coordinator = coordinator(stageMessage = { message, _ -> staged += message })
        val source = message().withEncodedMeta(MessageMeta(forwardedFrom = "Original"))

        coordinator.forward(chat(), source, sourceName = "Alice")

        assertEquals("Original", staged.single().parsedMeta().forwardedFrom)
    }

    @Test
    fun `attachment delegates without staging a second text message`() = runTest {
        var attachmentId: String? = null
        var stageCount = 0
        val coordinator = coordinator(
            stageMessage = { _, _ -> stageCount += 1 },
            forwardAttachment = { _, _, id, _, _ -> attachmentId = id },
        )

        val result = coordinator.forward(
            chat(),
            message(type = MessageType.IMAGE),
            sourceName = "Alice",
        )

        assertNull(result)
        assertEquals("m-1", attachmentId)
        assertEquals(0, stageCount)
    }

    @Test
    fun `batch is serialized by target and skips note for failed target`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = coordinator(
            stageMessage = { message, _ ->
                calls += "message:${message.chatId}:${message.parsedContent()}"
                if (message.chatId == "chat-1" && message.parsedContent() == "two") {
                    error("failed")
                }
            },
        )

        val result = coordinator.forwardBatch(
            targets = listOf(chat("chat-1"), chat("chat-2")),
            messages = listOf(message("one"), message("two")),
            note = "note",
            sourceName = { "Alice" },
        )

        assertEquals(3, result.forwardedCount)
        assertEquals(1, result.failedCount)
        assertEquals(
            listOf(
                "message:chat-1:one",
                "message:chat-1:two",
                "message:chat-2:one",
                "message:chat-2:two",
                "message:chat-2:note",
            ),
            calls,
        )
    }

    @Test
    fun `target load filters active and archived chats and sorts pinned first`() = runTest {
        val coordinator = coordinator(
            fetchTargets = {
                Result.success(
                    listOf(
                        chat("active"),
                        chat("archived").copy(archived = true),
                        chat("recent").copy(lastMessageTime = 20L),
                        chat("pinned").copy(pinnedAt = 5L, lastMessageTime = 1L),
                    )
                )
            },
        )

        val targets = coordinator.loadTargets("active")

        assertEquals(listOf("pinned", "recent"), targets.map { it.id })
    }

    @Test
    fun `session switch prevents durable stage`() = runTest {
        var active = true
        var stageCount = 0
        val coordinator = coordinator(
            sessionActive = { active },
            stageMessage = { _, _ -> stageCount += 1 },
        )
        active = false

        val error = runCatching {
            coordinator.forward(chat(), message(), "Alice")
        }.exceptionOrNull()

        assertTrue(error is ConversationForwardSessionException)
        assertEquals(0, stageCount)
    }

    @Test
    fun `forward resolves current group revision immediately before staging`() = runTest {
        var stagedRevision: Long? = null
        val cached = chat("group").copy(isGroup = true, chatType = "GROUP", memberRevision = 2L)
        val live = cached.copy(memberRevision = 7L)
        val coordinator = coordinator(
            fetchTargets = { Result.success(listOf(cached)) },
            resolveTargets = { _, _ -> listOf(live) },
            stageMessage = { _, revision -> stagedRevision = revision },
        )

        coordinator.forward(cached, message(), sourceName = "Alice")

        assertEquals(7L, stagedRevision)
    }

    private fun coordinator(
        sessionActive: (String) -> Boolean = { true },
        fetchTargets: suspend (String) -> Result<List<Chat>> = { Result.success(emptyList()) },
        resolveTargets: suspend (String, List<Chat>) -> List<Chat> = { _, targets -> targets },
        stageMessage: suspend (Message, Long?) -> Unit = { _, _ -> },
        forwardAttachment: suspend (Chat, Message, String, String?, String) -> Unit = { _, _, _, _, _ -> },
        onMessageSent: (String, String, MessageType) -> Unit = { _, _, _ -> },
    ) = ConversationForwardCoordinator(
        ownerUserId = { "owner-1" },
        token = { "token-1" },
        sessionActive = sessionActive,
        fetchTargets = fetchTargets,
        resolveTargets = resolveTargets,
        stageMessage = stageMessage,
        forwardAttachment = forwardAttachment,
        onMessageSent = onMessageSent,
        messageId = { "m-1" },
        now = { 10L },
    )

    private fun chat(id: String = "chat-1") = Chat(id = id)

    private fun message(
        content: String = "hello",
        type: MessageType = MessageType.TEXT,
    ) = Message(
        id = "source-1",
        chatId = "source-chat",
        senderId = "alice",
        content = content,
        type = type,
        timestamp = 1L,
    )
}
