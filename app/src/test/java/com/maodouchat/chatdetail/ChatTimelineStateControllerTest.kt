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

    @Test
    fun `updateStatus to the same status leaves the row unchanged`() {
        val sending = ChatDetailUiState(messages = listOf(message("one", 1L)))
        val sent = ChatDetailUiState(messages = listOf(message("one", 1L).copy(status = MessageStatus.SENT)))

        assertEquals(MessageStatus.SENDING, controller.updateStatus(sending, "one", MessageStatus.SENDING).messages.single().status)
        assertEquals(MessageStatus.SENT, controller.updateStatus(sent, "one", MessageStatus.SENT).messages.single().status)
    }

    @Test
    fun `failed recovers to sent and read`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L).copy(status = MessageStatus.FAILED)))

        assertEquals(MessageStatus.SENT, controller.updateStatus(state, "one", MessageStatus.SENT).messages.single().status)
        assertEquals(MessageStatus.READ, controller.updateStatus(state, "one", MessageStatus.READ).messages.single().status)
    }

    @Test
    fun `mergeIncoming keeps unrelated state fields`() {
        val state = ChatDetailUiState(
            messages = listOf(message("b", 2L)),
            isLoading = true,
            unreadSeparatorId = "keep",
            initialTimelineReady = false,
        )

        val updated = controller.mergeIncoming(state, listOf(message("a", 1L), message("c", 3L)))

        assertEquals(listOf("a", "b", "c"), updated.messages.map(Message::id))
        assertTrue(updated.isLoading)
        assertEquals("keep", updated.unreadSeparatorId)
        assertFalse(updated.initialTimelineReady)
    }

    @Test
    fun `initialHistoryLoaded dedupes the same id keeping the newer edit`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L)))

        val updated = controller.initialHistoryLoaded(
            state,
            listOf(message("one", 1L).copy(content = "edited", editedAt = 10L)),
            null,
        )

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals("edited", updated.messages.single().content)
        assertEquals(10L, updated.messages.single().editedAt)
        assertTrue(updated.initialTimelineReady)
    }

    @Test
    fun `replaceMessage on the middle row keeps order when the timestamp changes`() {
        val state = ChatDetailUiState(
            messages = listOf(message("one", 1L), message("two", 2L), message("three", 3L)),
        )

        val updated = controller.replaceMessage(state, "two") { it.copy(content = "changed", timestamp = 99L) }

        assertEquals(listOf("one", "two", "three"), updated.messages.map(Message::id))
        assertEquals(listOf("one", "changed", "three"), updated.messages.map(Message::content))
    }

    @Test
    fun `initialHistoryLoaded merges out-of-order history sorted with separator`() {
        val state = ChatDetailUiState(messages = listOf(message("b", 2L)))

        val updated = controller.initialHistoryLoaded(
            state,
            listOf(message("c", 3L), message("a", 1L)),
            "a",
        )

        assertEquals(listOf("a", "b", "c"), updated.messages.map(Message::id))
        assertEquals("a", updated.unreadSeparatorId)
        assertTrue(updated.initialTimelineReady)
        assertFalse(updated.hasMoreOlderMessages)
    }

    @Test
    fun `delivered rejects sent regression while sending jumps to delivered`() {
        val delivered = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(status = MessageStatus.DELIVERED)),
        )
        val sending = ChatDetailUiState(messages = listOf(message("one", 1L)))

        assertEquals(
            MessageStatus.DELIVERED,
            controller.updateStatus(delivered, "one", MessageStatus.SENT).messages.single().status,
        )
        assertEquals(
            MessageStatus.DELIVERED,
            controller.updateStatus(sending, "one", MessageStatus.DELIVERED).messages.single().status,
        )
    }

    @Test
    fun `mergeIncoming orders equal timestamps by id`() {
        val state = ChatDetailUiState(messages = listOf(message("b", 2L)))

        val updated = controller.mergeIncoming(state, listOf(message("a", 2L)))

        assertEquals(listOf("a", "b"), updated.messages.map(Message::id))
    }

    @Test
    fun `applyMutation Set replaces in place even when the timestamp moves`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L), message("two", 2L)))

        val updated = controller.applyMutation(
            state,
            MessageMutationProjection.Set(message("one", 1L).copy(content = "moved", timestamp = 99L)),
        )

        assertEquals(listOf("one", "two"), updated.messages.map(Message::id))
        assertEquals("moved", updated.messages[0].content)
        assertEquals(99L, updated.messages[0].timestamp)
    }

    @Test
    fun `mergeIncoming the same edit twice stays a single row`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L)))
        val edited = message("one", 1L).copy(content = "edited", editedAt = 10L)

        val once = controller.mergeIncoming(state, listOf(edited))
        assertEquals(listOf("one"), once.messages.map(Message::id))
        assertEquals("edited", once.messages.single().content)
        assertEquals(10L, once.messages.single().editedAt)

        val twice = controller.mergeIncoming(once, listOf(edited))
        assertEquals(listOf("one"), twice.messages.map(Message::id))
        assertEquals("edited", twice.messages.single().content)
    }

    @Test
    fun `read rejects sent regression while sending accepts failed`() {
        val read = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(status = MessageStatus.READ)),
        )
        val sending = ChatDetailUiState(messages = listOf(message("one", 1L)))

        assertEquals(
            MessageStatus.READ,
            controller.updateStatus(read, "one", MessageStatus.SENT).messages.single().status,
        )
        assertEquals(
            MessageStatus.FAILED,
            controller.updateStatus(sending, "one", MessageStatus.FAILED).messages.single().status,
        )
    }

    @Test
    fun `mergeIncoming equal revision takes candidate content but keeps higher status`() {
        val state = ChatDetailUiState(messages = listOf(message("one", 1L)))
        val incoming = message("one", 1L).copy(content = "new", status = MessageStatus.DELIVERED)

        val updated = controller.mergeIncoming(state, listOf(incoming))

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals("new", updated.messages.single().content)
        assertEquals(MessageStatus.DELIVERED, updated.messages.single().status)
    }

    @Test
    fun `mergeIncoming older edit loses to the current version`() {
        val state = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(content = "current", editedAt = 10L)),
        )

        val updated = controller.mergeIncoming(
            state,
            listOf(message("one", 1L).copy(content = "stale", editedAt = 5L)),
        )

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals("current", updated.messages.single().content)
        assertEquals(10L, updated.messages.single().editedAt)
    }

    @Test
    fun `mergeIncoming revoked frame beats a plain version with a newer edit`() {
        val state = ChatDetailUiState(
            messages = listOf(message("one", 1L).copy(content = "plain", editedAt = 10L)),
        )

        val updated = controller.mergeIncoming(
            state,
            listOf(message("one", 1L).copy(content = "revoked", type = MessageType.REVOKED)),
        )

        assertEquals(listOf("one"), updated.messages.map(Message::id))
        assertEquals(MessageType.REVOKED, updated.messages.single().type)
        assertEquals("revoked", updated.messages.single().content)
    }

    @Test
    fun `mergeIncoming sorts an incoming only list by timestamp then id`() {
        val state = ChatDetailUiState(messages = emptyList())

        val updated = controller.mergeIncoming(
            state,
            listOf(message("b", 2L), message("a", 2L), message("c", 1L)),
        )

        assertEquals(listOf("c", "a", "b"), updated.messages.map(Message::id))
    }
}
