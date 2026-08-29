package com.maodouchat.conversation

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.ContentPayload
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

    private fun facade(gateway: FakeGateway) = ConversationCommandFacade(
        gateway = gateway,
        messageId = { "new-1" },
        now = { 10L },
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
        val payloads = mutableListOf<ContentPayload>()
        var retryCount = 0

        override suspend fun stage(
            message: Message,
            payload: ContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            messages += message
            payloads += payload
            return stageOutcome ?: MessagingV2MessageGatewayOutcome.Staged(message)
        }

        override suspend fun retry(
            message: Message,
            payload: ContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            retryCount += 1
            messages += message
            payloads += payload
            return retryOutcome ?: MessagingV2MessageGatewayOutcome.Staged(message)
        }
    }
}
