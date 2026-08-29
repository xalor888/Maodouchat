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

    private fun message(id: String, timestamp: Long) = Message(
        id = id,
        chatId = "chat",
        senderId = "user",
        content = id,
        type = MessageType.TEXT,
        timestamp = timestamp,
        status = MessageStatus.SENDING,
    )
}
