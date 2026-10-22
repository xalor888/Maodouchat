package com.maodouchat.scheduling

import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.conversation.ConversationCommandOutcome
import com.maodouchat.conversation.ConversationCommandRejection
import com.maodouchat.data.model.Chat
import com.maodouchat.messaging.v2.DecodedContentPayload
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

    @Test
    fun `id without sch prefix keeps raw suffix`() = runTest {
        val gateway = RecordingGateway()
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(gateway, now = { 10L }),
            resolveChat = { Chat(id = it) },
        )

        val result = dispatcher.stage(scheduled("abc_123"), "owner-1")

        assertTrue(result is ConversationCommandOutcome.Staged)
        assertEquals("sm_abc_123", gateway.message?.id)
    }

    @Test
    fun `sch prefix is stripped only once`() = runTest {
        val gateway = RecordingGateway()
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(gateway, now = { 10L }),
            resolveChat = { Chat(id = it) },
        )

        val result = dispatcher.stage(scheduled("sch_sch_x"), "owner-1")

        assertTrue(result is ConversationCommandOutcome.Staged)
        assertEquals("sm_sch_x", gateway.message?.id)
    }

    @Test
    fun `blank text is rejected as EMPTY_TEXT without staging`() = runTest {
        val gateway = RecordingGateway()
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(gateway, now = { 10L }),
            resolveChat = { Chat(id = it) },
        )
        val blank = scheduled("sch_t").copy(text = "   ")

        val result = dispatcher.stage(blank, "owner-1")

        assertEquals(
            ConversationCommandRejection.EMPTY_TEXT,
            (result as ConversationCommandOutcome.Rejected).reason,
        )
        // 拒绝发生在网关调用之前
        assertEquals(null, gateway.message)
    }

    @Test
    fun `staged id is stable across repeated staging`() = runTest {
        val gateway = RecordingGateway()
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(gateway, now = { 10L }),
            resolveChat = { Chat(id = it) },
        )

        dispatcher.stage(scheduled("sch_dup"), "owner-1")
        val firstId = gateway.message?.id
        dispatcher.stage(scheduled("sch_dup"), "owner-1")

        assertEquals("sm_dup", firstId)
        assertEquals(firstId, gateway.message?.id)
    }

    @Test
    fun `resolveChat receives the scheduled item chatId`() = runTest {
        var resolvedChatId: String? = null
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = ConversationCommandFacade(RecordingGateway(), now = { 10L }),
            resolveChat = {
                resolvedChatId = it
                Chat(id = it)
            },
        )

        dispatcher.stage(scheduled("sch_x"), "owner-1")

        assertEquals("chat-1", resolvedChatId)
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
        var payload: DecodedContentPayload? = null

        override suspend fun stage(
            message: com.maodouchat.data.model.Message,
            payload: DecodedContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome {
            this.message = message
            this.payload = payload
            return MessagingV2MessageGatewayOutcome.Staged(message)
        }

        override suspend fun retry(
            message: com.maodouchat.data.model.Message,
            payload: DecodedContentPayload,
            groupRevision: Long?,
        ): MessagingV2MessageGatewayOutcome = MessagingV2MessageGatewayOutcome.Staged(message)
    }
}
