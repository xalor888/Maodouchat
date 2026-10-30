package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.ChatTimelineStateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineStateControllerTest {
    private val controller = ChatTimelineStateController()

    @Test
    fun `initial history merges state and finishes timeline loading`() {
        val state = ChatDetailUiState(
            messages = listOf(message("existing", 1L)),
            isLoading = true,
            initialLoadError = "old error",
        )

        val updated = controller.initialHistoryLoaded(
            state = state,
            messages = listOf(message("incoming", 2L)),
            unreadSeparatorId = "incoming",
        )

        assertEquals(listOf("existing", "incoming"), updated.messages.map(Message::id))
        assertEquals("incoming", updated.unreadSeparatorId)
        assertTrue(updated.initialTimelineReady)
        assertFalse(updated.hasMoreOlderMessages)
        assertEquals(null, updated.initialLoadError)
    }

    @Test
    fun `mutation updates existing row and removes deleted row`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))
        val edited = message("two", 2L).copy(content = "edited")

        val updated = controller.applyMutation(state, MessageMutationProjection.Set(edited))
        val removed = controller.applyMutation(updated, MessageMutationProjection.Remove("one"))

        assertEquals("edited", updated.messages.single { it.id == "two" }.content)
        assertEquals(listOf("two"), removed.messages.map(Message::id))
    }

    @Test
    fun `status does not regress terminal message`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L).copy(status = MessageStatus.SENT)))

        val updated = controller.updateStatus(state, "one", MessageStatus.SENDING)

        assertEquals(MessageStatus.SENT, updated.messages.single().status)
    }

    @Test
    fun `mergeIncoming appends and sorts by timestamp`() {
        val state = ChatDetailUiState(messages = listOf(message("b", 2L)))

        val updated = controller.mergeIncoming(state, listOf(message("a", 1L), message("c", 3L)))

        assertEquals(listOf("a", "b", "c"), updated.messages.map(Message::id))
    }

    @Test
    fun `replaceMessage transforms only the target row in place`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val updated = controller.replaceMessage(state, "two") { it.copy(content = "changed") }

        assertEquals(listOf("one", "two"), updated.messages.map(Message::id))
        assertEquals("one", updated.messages[0].content)
        assertEquals("changed", updated.messages[1].content)
    }

    @Test
    fun `applyMutation Set inserts unknown id through merge ordering`() {
        val state = ChatDetailUiState(messages = listOf(message("b", 2L)))

        val updated = controller.applyMutation(state, MessageMutationProjection.Set(message("a", 1L)))

        assertEquals(listOf("a", "b"), updated.messages.map(Message::id))
    }

    @Test
    fun `updateStatus advances the ladder and recovers server-authoritative failed`() {
        val state = ChatDetailUiState(
            messages = listOf(
                message("one", 1L),
                message("two", 2L).copy(status = MessageStatus.FAILED),
            ),
        )

        val updated = controller.updateStatus(state, "one", MessageStatus.SENT)
        val recovered = controller.updateStatus(updated, "two", MessageStatus.DELIVERED)

        assertEquals(MessageStatus.SENT, recovered.messages.single { it.id == "one" }.status)
        assertEquals(MessageStatus.DELIVERED, recovered.messages.single { it.id == "two" }.status)
    }

    @Test
    fun `initialHistoryLoaded ready even with empty history`() {
        val state = ChatDetailUiState(
            messages = emptyList(),
            isLoading = true,
            initialLoadError = "old error",
            hasMoreOlderMessages = true,
        )

        val updated = controller.initialHistoryLoaded(state, emptyList(), null)

        assertTrue(updated.messages.isEmpty())
        assertTrue(updated.initialTimelineReady)
        assertFalse(updated.hasMoreOlderMessages)
        assertEquals(null, updated.initialLoadError)
        assertEquals(null, updated.unreadSeparatorId)
    }

    @Test
    fun `updateStatus on unknown id leaves messages untouched`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L)))

        val updated = controller.updateStatus(state, "ghost", MessageStatus.SENT)

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals(MessageStatus.SENDING, updated.messages.single().status)
    }

    @Test
    fun `applyMutation Remove unknown id leaves list unchanged`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val updated = controller.applyMutation(state, MessageMutationProjection.Remove("ghost"))

        assertEquals(listOf("one", "two"), updated.messages.map(Message::id))
    }

    @Test
    fun `applyMutation Set replaces existing row in place preserving order`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val updated = controller.applyMutation(
            state,
            MessageMutationProjection.Set(message("one", 1L).copy(content = "replaced")),
        )

        assertEquals(listOf("one", "two"), updated.messages.map(Message::id))
        assertEquals("replaced", updated.messages[0].content)
        assertEquals("two", updated.messages[1].content)
    }

    @Test
    fun `mergeIncoming with empty incoming keeps existing order`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val updated = controller.mergeIncoming(state, emptyList())

        assertEquals(listOf("one", "two"), updated.messages.map(Message::id))
    }

    @Test
    fun `status SENT rejects FAILED and FAILED recovers to SENDING`() {
        val sent = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(status = MessageStatus.SENT)),
        )
        val failed = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(status = MessageStatus.FAILED)),
        )

        assertEquals(
            MessageStatus.SENT,
            controller.updateStatus(sent, "one", MessageStatus.FAILED).messages.single().status,
        )
        assertEquals(
            MessageStatus.SENDING,
            controller.updateStatus(failed, "one", MessageStatus.SENDING).messages.single().status,
        )
    }

    private fun message(id: String, timestamp: Long) = Message(
        id = id,
        chatId = "chat",
        senderId = "user",
        content = id,
        type = MessageType.TEXT,
        timestamp = timestamp,
        status = MessageStatus.SENDING,
    )

    @Test
    fun `replaceMessage on unknown id leaves messages untouched`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val updated = controller.replaceMessage(state, "ghost") { it.copy(content = "changed") }

        assertEquals(listOf("one", "two"), updated.messages.map(Message::id))
        assertEquals("two", updated.messages[1].content)
    }

    @Test
    fun `mergeIncoming dedupes the same id keeping the newer edit`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L)))

        val updated = controller.mergeIncoming(
            state,
            listOf(message("one", 1L).copy(content = "edited", editedAt = 10L)),
        )

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals("edited", updated.messages.single().content)
        assertEquals(10L, updated.messages.single().editedAt)
    }

    @Test
    fun `mergeIncoming keeps the revoked version over a newer plain version`() {
        val state = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(type = MessageType.REVOKED, content = "revoked")),
        )

        val updated = controller.mergeIncoming(
            state,
            listOf(message("one", 1L).copy(content = "plain", editedAt = 5L)),
        )

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals(MessageType.REVOKED, updated.messages.single().type)
        assertEquals("revoked", updated.messages.single().content)
    }

    @Test
    fun `initialHistoryLoaded keeps non-empty history with a null separator`() {
        val state = ChatDetailUiState(messages = listOf(message("existing", 1L)))

        val updated = controller.initialHistoryLoaded(state, listOf(message("incoming", 2L)), null)

        assertEquals(listOf("existing", "incoming"), updated.messages.map(Message::id))
        assertEquals(null, updated.unreadSeparatorId)
        assertTrue(updated.initialTimelineReady)
        assertFalse(updated.hasMoreOlderMessages)
    }

    @Test
    fun `remove then set reinserts the message through merge ordering`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val removed = controller.applyMutation(state, MessageMutationProjection.Remove("one"))
        val reinserted = controller.applyMutation(
            removed,
            MessageMutationProjection.Set(message("one", 1L).copy(content = "back")),
        )

        assertEquals(listOf("one", "two"), reinserted.messages.map(Message::id))
        assertEquals("back", reinserted.messages[0].content)
    }

}
