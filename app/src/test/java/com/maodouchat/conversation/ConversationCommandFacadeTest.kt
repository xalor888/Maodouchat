package com.maodouchat.conversation

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.DecodedContentPayload
import com.maodouchat.messaging.v2.ConversationMessageStagingGateway
import com.maodouchat.messaging.v2.MessagingV2MessageGatewayOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCommandFacadeTest {
    @Test
    fun `text send stages structured payload without legacy marker`() = runTest {
        val gateway = FakeGateway()
        val facade = facade(gateway)

        val result = facade.sendText(chat(), "owner-1", "hello", MessageMeta(forwardedFrom = "Alice"))

        assertTrue(result is ConversationCommandOutcome.Staged)
        assertEquals("hello", gateway.payloads.single().body)
        assertEquals("Alice", gateway.payloads.single().metadata.forwardedFrom)
        assertFalse(gateway.messages.single().content.contains(Message.META_TAG_PREFIX))
    }

    @Test
    fun `terminal retry becomes explicit command rejection`() = runTest {
        val gateway = FakeGateway(retryOutcome = MessagingV2MessageGatewayOutcome.Rejected.TerminalTombstone("m-1"))
        val facade = facade(gateway)

        val result = facade.retry(chat(), message())

        assertEquals(
            ConversationCommandRejection.TERMINAL_MESSAGE,
            (result as ConversationCommandOutcome.Rejected).reason,
        )
        assertEquals(1, gateway.retryCount)
    }

    @Test
    fun `quick reply and scheduled staging honor centralized privacy restrictions`() = runTest {
        val gateway = FakeGateway()
        val facade = facade(gateway)
        val secret = chat().copy(chatType = "SECRET")

        val quickReply = facade.stageQuickReply(secret, "owner-1", "hello")
        val scheduled = facade.stageScheduledText(secret, "owner-1", "hello", "scheduled-1")

        assertEquals(
            ConversationCommandRejection.SECRET_CONVERSATION,
            (quickReply as ConversationCommandOutcome.Rejected).reason,
        )
        assertEquals(
            ConversationCommandRejection.SECRET_CONVERSATION,
            (scheduled as ConversationCommandOutcome.Rejected).reason,
        )
        assertTrue(gateway.messages.isEmpty())
    }

    @Test
    fun `locked conversation is rejected before forwarding stage`() = runTest {
        val gateway = FakeGateway()
        val facade = facade(gateway)

        val result = facade.forwardText(
            target = chat(),
            ownerUserId = "owner-1",
            source = message(),
            sourceName = "Alice",
            privacy = ConversationPrivacyContext(isLocked = true),
        )

        assertEquals(
            ConversationCommandRejection.LOCKED_CONVERSATION,
            (result as ConversationCommandOutcome.Rejected).reason,
        )
        assertTrue(gateway.messages.isEmpty())
    }

    @Test
    fun `send command with idempotencyKey deduplicates duplicate intents`() = runTest {
        val gateway = FakeGateway()
        var msgSeq = 0
        val facade = facade(
            gateway = gateway,
            resolveChat = { chat() },
            messageId = { "msg-${++msgSeq}" },
        )
        val command = com.maodouchat.domain.messaging.SendMessageCommand(
            conversationId = "chat-1",
            content = com.maodouchat.domain.messaging.ContentPayload.Text("Hello world"),
            idempotencyKey = "key-12345",
        )

        val first = facade.send(command)
        val second = facade.send(command)

        assertTrue(first is com.maodouchat.domain.messaging.SendMessageResult.Success)
        assertTrue(second is com.maodouchat.domain.messaging.SendMessageResult.Success)
        assertEquals((first as com.maodouchat.domain.messaging.SendMessageResult.Success).localMessageId, (second as com.maodouchat.domain.messaging.SendMessageResult.Success).localMessageId)
        assertEquals(1, gateway.messages.size)
    }

    @Test
    fun `send command with empty text fails validation without hitting gateway`() = runTest {
        val gateway = FakeGateway()
        val facade = facade(gateway)
        val command = com.maodouchat.domain.messaging.SendMessageCommand(
            conversationId = "chat-1",
            content = com.maodouchat.domain.messaging.ContentPayload.Text("   "),
            idempotencyKey = "key-empty",
        )

        val result = facade.send(command)

        assertTrue(result is com.maodouchat.domain.messaging.SendMessageResult.Failure)
        assertEquals(com.maodouchat.domain.messaging.SendFailureReason.VALIDATION, (result as com.maodouchat.domain.messaging.SendMessageResult.Failure).reason)
        assertTrue(gateway.messages.isEmpty())
    }

    @Test
    fun `retry local message delegating by id succeeds or returns permanent on terminal`() = runTest {
        val message = message()
        val successGateway = FakeGateway()
        val facadeSuccess = facade(
            gateway = successGateway,
            resolveChat = { chat() },
            getMessage = { if (it == "m-1") message else null },
        )

        val successResult = facadeSuccess.retry("m-1")
        assertTrue(successResult is com.maodouchat.domain.messaging.SendMessageResult.Success)
        assertEquals(1, successGateway.retryCount)

        val terminalGateway = FakeGateway(retryOutcome = MessagingV2MessageGatewayOutcome.Rejected.TerminalTombstone("m-1"))
        val facadeTerminal = facade(
            gateway = terminalGateway,
            resolveChat = { chat() },
            getMessage = { if (it == "m-1") message else null },
        )
        val terminalResult = facadeTerminal.retry("m-1")
        assertTrue(terminalResult is com.maodouchat.domain.messaging.SendMessageResult.Failure)
        assertEquals(com.maodouchat.domain.messaging.SendFailureReason.PERMANENT, (terminalResult as com.maodouchat.domain.messaging.SendMessageResult.Failure).reason)

        val missingResult = facadeSuccess.retry("non-existent")
        assertTrue(missingResult is com.maodouchat.domain.messaging.SendMessageResult.Failure)
        assertEquals(com.maodouchat.domain.messaging.SendFailureReason.NOT_READY, (missingResult as com.maodouchat.domain.messaging.SendMessageResult.Failure).reason)
    }

    @Test
    fun `cancel delegates to gateway and reports cancellation`() = runTest {
        val gateway = FakeGateway()
        val facade = facade(gateway)

        val cancelled = facade.cancel("m-to-cancel")

        assertTrue(cancelled)
        assertEquals(listOf("m-to-cancel"), gateway.cancelledIds)
    }

    @Test
    fun `sendInline stages sticker and deduplicates on idempotencyKey`() = runTest {
        val gateway = FakeGateway()
        val facade = facade(gateway)

        val first = facade.sendInline(
            chat = chat(),
            ownerUserId = "owner-1",
            content = "sticker:duck",
            type = MessageType.STICKER,
            idempotencyKey = "sticker-key-1",
        )
        val second = facade.sendInline(
            chat = chat(),
            ownerUserId = "owner-1",
            content = "sticker:duck",
            type = MessageType.STICKER,
            idempotencyKey = "sticker-key-1",
        )

        assertTrue(first is ConversationCommandOutcome.Staged)
        assertTrue(second is ConversationCommandOutcome.Staged)
        assertEquals((first as ConversationCommandOutcome.Staged).message.id, (second as ConversationCommandOutcome.Staged).message.id)
        assertEquals(1, gateway.messages.size)
        assertEquals(MessageType.STICKER, gateway.messages.single().type)
    }

    private fun facade(
        gateway: FakeGateway,
        resolveChat: (suspend (String) -> Chat?)? = null,
        getMessage: (suspend (String) -> Message?)? = null,
        ownerUserId: () -> String = { "owner-1" },
        messageId: () -> String = { "new-1" },
        now: () -> Long = { 10L },
    ) = ConversationCommandFacade(
        gateway = gateway,
        resolveChat = resolveChat,
        getMessage = getMessage,
        ownerUserId = ownerUserId,
        messageId = messageId,
        now = now,
    )

    private fun chat() = Chat(id = "chat-1")

    private fun message() = Message(
        id = "m-1",
        chatId = "chat-1",
        senderId = "owner-1",
        content = "hello",
        type = MessageType.TEXT,
        meta = MessageMeta(),
    )

    private class FakeGateway(
        private val stageOutcome: MessagingV2MessageGatewayOutcome? = null,
        private val retryOutcome: MessagingV2MessageGatewayOutcome? = null,
    ) : ConversationMessageStagingGateway {
        val messages = mutableListOf<Message>()
        val payloads = mutableListOf<DecodedContentPayload>()
        val cancelledIds = mutableListOf<String>()
        var retryCount = 0
        var cancelResult = true

        override suspend fun stage(
            message: Message,
            payload: DecodedContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            messages += message
            payloads += payload
            return stageOutcome ?: MessagingV2MessageGatewayOutcome.Staged(message)
        }

        override suspend fun retry(
            message: Message,
            payload: DecodedContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            retryCount += 1
            messages += message
            payloads += payload
            return retryOutcome ?: MessagingV2MessageGatewayOutcome.Staged(message)
        }

        override suspend fun cancel(messageId: String): Boolean {
            cancelledIds += messageId
            return cancelResult
        }
    }
}
