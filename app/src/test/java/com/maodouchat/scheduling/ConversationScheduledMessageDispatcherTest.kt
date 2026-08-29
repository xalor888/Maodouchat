package com.maodouchat.scheduling

import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.conversation.ConversationCommandOutcome
import com.maodouchat.conversation.ConversationCommandRejection
import com.maodouchat.data.model.Chat
import com.maodouchat.messaging.v2.ContentPayload
import com.maodouchat.messaging.v2.ConversationMessageStagingGateway
import com.maodouchat.messaging.v2.MessagingV2MessageGatewayOutcome
import com.maodouchat.util.ScheduledMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScheduledMessageDispatcherTest {
    @Test
    fun `dispatcher uses deterministic scheduled id through facade`() = runTest {
        val gateway = RecordingGateway()
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(gateway, now = { 10L }),
            resolveChat = { Chat(id = it) },
        )

        val result = dispatcher.stage(scheduled("sch_abc"), "owner-1")

        assertTrue(result is ConversationCommandOutcome.Staged)
        assertEquals("sm_abc", gateway.message?.id)
        assertEquals("hello", gateway.payload?.body)
    }

    @Test
    fun `missing chat is rejected without staging`() = runTest {
        val gateway = RecordingGateway()
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(gateway),
            resolveChat = { null },
        )

        val result = dispatcher.stage(scheduled("sch_abc"), "owner-1")

        assertEquals(
            ConversationCommandRejection.CHAT_UNAVAILABLE,
            (result as ConversationCommandOutcome.Rejected).reason,
        )
        assertEquals(null, gateway.message)
    }

    private fun scheduled(id: String) = ScheduledMessage(
        id = id,
        chatId = "chat-1",
        peerUserId = "peer-1",
        text = "hello",
        sendAtMillis = 100L,
        createdAtMillis = 1L,
        ownerUserId = "owner-1",
    )

    private class RecordingGateway : ConversationMessageStagingGateway {
        var message: com.maodouchat.data.model.Message? = null
        var payload: ContentPayload? = null

        override suspend fun stage(
            message: com.maodouchat.data.model.Message,
            payload: ContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            this.message = message
            this.payload = payload
            return MessagingV2MessageGatewayOutcome.Staged(message)
        }

        override suspend fun retry(
            message: com.maodouchat.data.model.Message,
            payload: ContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome = MessagingV2MessageGatewayOutcome.Staged(message)
    }
}
