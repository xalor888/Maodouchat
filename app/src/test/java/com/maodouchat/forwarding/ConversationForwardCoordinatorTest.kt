package com.maodouchat.forwarding

import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.messaging.v2.DecodedContentPayload
import com.maodouchat.messaging.v2.ConversationMessageStagingGateway
import com.maodouchat.messaging.v2.MessagingV2MessageGatewayOutcome
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.AttachmentTransfer
import com.maodouchat.domain.messaging.ForwardFailureReason
import com.maodouchat.domain.messaging.ForwardRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `facade forward stages typed content without legacy marker`() = runTest {
        val gateway = RecordingGateway()
        val coordinator = ConversationForwardCoordinator(
            ownerUserId = { "owner-1" },
            token = { "token-1" },
            sessionActive = { true },
            fetchTargets = { Result.success(emptyList()) },
            commandFacade = ConversationCommandFacade(gateway, messageId = { "forward-1" }, now = { 10L }),
            forwardAttachment = { _, _, _, _, _ -> },
        )

        val result = coordinator.forward(chat(), message(), sourceName = "Alice")

        assertEquals("hello", result?.content)
        assertEquals("Alice", gateway.payloads.single().metadata.forwardedFrom)
        assertTrue(!gateway.messages.single().content.contains(Message.META_TAG_PREFIX))
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

    @Test
    fun `forward request blocks secret chat with FORBIDDEN`() = runTest {
        val coordinator = coordinator(
            getChatById = { chat("secret-chat").copy(chatType = "SECRET") },
            getMessageById = { message() },
        )
        val request = ForwardRequest(
            sourceConversationId = "secret-chat",
            sourceMessageId = "source-1",
            targetConversationIds = listOf("target-1"),
        )

        val results = coordinator.forward(request)

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertEquals(ForwardFailureReason.FORBIDDEN, results.single().reason)
    }

    @Test
    fun `forward request blocks PIN locked source chat with PIN_LOCKED`() = runTest {
        val coordinator = coordinator(
            getChatById = { chat("locked-chat") },
            getMessageById = { message() },
            isChatLocked = { it == "locked-chat" },
        )
        val request = ForwardRequest(
            sourceConversationId = "locked-chat",
            sourceMessageId = "source-1",
            targetConversationIds = listOf("target-1"),
        )

        val results = coordinator.forward(request)

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertEquals(ForwardFailureReason.PIN_LOCKED, results.single().reason)
    }

    @Test
    fun `forward request blocks terminal message with PERMANENT`() = runTest {
        val coordinator = coordinator(
            getChatById = { chat() },
            getMessageById = { message().copy(status = MessageStatus.FAILED) },
        )
        val request = ForwardRequest(
            sourceConversationId = "chat-1",
            sourceMessageId = "source-1",
            targetConversationIds = listOf("target-1"),
        )

        val results = coordinator.forward(request)

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertEquals(ForwardFailureReason.PERMANENT, results.single().reason)
    }

    @Test
    fun `forward request supports partial multi target failure`() = runTest {
        val staged = mutableListOf<Message>()
        val coordinator = coordinator(
            getChatById = { chat() },
            getMessageById = { message() },
            resolveTargets = { _, targets -> targets },
            stageMessage = { msg, _ ->
                if (msg.chatId == "target-bad") error("network unreachable")
                staged += msg
            },
        )
        val request = ForwardRequest(
            sourceConversationId = "chat-1",
            sourceMessageId = "source-1",
            targetConversationIds = listOf("target-ok", "target-bad"),
        )

        val results = coordinator.forward(request)

        assertEquals(2, results.size)
        assertTrue(results[0].success)
        assertEquals("target-ok", results[0].conversationId)
        assertFalse(results[1].success)
        assertEquals("target-bad", results[1].conversationId)
        assertEquals(ForwardFailureReason.PERMANENT, results[1].reason)
        assertEquals(1, staged.size)
        assertEquals("target-ok", staged.single().chatId)
    }

    @Test
    fun `forward request delegates media to AttachmentIntentController`() = runTest {
        val controller = FakeAttachmentIntentController()
        val coordinator = coordinator(
            getChatById = { chat() },
            getMessageById = { message(content = "https://cdn.example.com/photo.jpg", type = MessageType.IMAGE) },
            attachmentIntentController = controller,
        )
        val request = ForwardRequest(
            sourceConversationId = "chat-1",
            sourceMessageId = "source-1",
            targetConversationIds = listOf("target-1"),
        )

        val results = coordinator.forward(request)

        assertEquals(1, results.size)
        assertTrue(results.single().success)
        assertEquals(1, controller.intents.size)
        assertEquals("target-1", controller.intents.single().conversationId)
        assertEquals("https://cdn.example.com/photo.jpg", controller.intents.single().uri)
    }

    @Test
    fun `forwardBatch forwards multiple requests with caption on last`() = runTest {
        val staged = mutableListOf<Message>()
        val coordinator = coordinator(
            getChatById = { chat() },
            getMessageById = { id -> message(content = "content_$id").copy(id = id) },
            resolveTargets = { _, targets -> targets },
            stageMessage = { msg, _ -> staged += msg },
        )
        val requests = listOf(
            ForwardRequest(
                sourceConversationId = "chat-1",
                sourceMessageId = "m-1",
                targetConversationIds = listOf("target-1"),
                caption = null,
            ),
            ForwardRequest(
                sourceConversationId = "chat-1",
                sourceMessageId = "m-2",
                targetConversationIds = listOf("target-1"),
                caption = "Check this out",
            ),
        )

        val batchResult = coordinator.forwardBatch(requests)

        assertEquals(2, batchResult.size)
        assertTrue(batchResult["m-1"]!!.single().success)
        assertTrue(batchResult["m-2"]!!.single().success)
        // 2 forwarded messages + 1 note
        assertEquals(3, staged.size)
        assertEquals("Check this out", staged.last().content)
    }

    private class FakeAttachmentIntentController : AttachmentIntentController {
        val intents = mutableListOf<AttachmentIntent>()
        var failure: Throwable? = null

        override suspend fun submit(intent: AttachmentIntent): Result<String> {
            failure?.let { return Result.failure(it) }
            intents += intent
            return Result.success("transfer-1")
        }

        override fun observe(transferId: String): Flow<AttachmentTransfer> = emptyFlow()
        override suspend fun pause(transferId: String): Boolean = true
        override suspend fun resume(transferId: String): Boolean = true
        override suspend fun cancel(transferId: String): Boolean = true
    }

    private class RecordingGateway : ConversationMessageStagingGateway {
        val messages = mutableListOf<Message>()
        val payloads = mutableListOf<DecodedContentPayload>()

        override suspend fun stage(
            message: Message,
            payload: DecodedContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            messages += message
            payloads += payload
            return MessagingV2MessageGatewayOutcome.Staged(message)
        }

        override suspend fun retry(
            message: Message,
            payload: DecodedContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome = MessagingV2MessageGatewayOutcome.Staged(message)
    }

    private fun coordinator(
        sessionActive: (String) -> Boolean = { true },
        fetchTargets: suspend (String) -> Result<List<Chat>> = { Result.success(emptyList()) },
        resolveTargets: suspend (String, List<Chat>) -> List<Chat> = { _, targets -> targets },
        stageMessage: suspend (Message, Long?) -> Unit = { _, _ -> },
        forwardAttachment: (suspend (Chat, Message, String, String?, String) -> Unit)? = null,
        attachmentIntentController: AttachmentIntentController? = null,
        getMessageById: (suspend (String) -> Message?)? = null,
        getChatById: (suspend (String) -> Chat?)? = null,
        isChatLocked: (suspend (String) -> Boolean)? = null,
        onMessageSent: (String, String, MessageType) -> Unit = { _, _, _ -> },
    ) = ConversationForwardCoordinator(
        ownerUserId = { "owner-1" },
        token = { "token-1" },
        sessionActive = sessionActive,
        fetchTargets = fetchTargets,
        resolveTargets = resolveTargets,
        stageMessage = stageMessage,
        forwardAttachment = forwardAttachment,
        attachmentIntentController = attachmentIntentController,
        getMessageById = getMessageById,
        getChatById = getChatById,
        isChatLocked = isChatLocked,
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
