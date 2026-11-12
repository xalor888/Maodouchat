package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AttachmentFinalizedEvent
import com.maodouchat.core.model.AttachmentTransferState
import com.maodouchat.core.model.ChatMediaStateController
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.mergeMessageVersions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAttachmentObservationControllerTest {

    // backgroundScope 里的 collect 须立即订阅：默认调度器下 advanceUntilIdle()
    // 不会跑起它，emit/置值会丢给无人订阅的流，改走 Unconfined（仓内同类测试惯例）。
    private val dispatcher = UnconfinedTestDispatcher()

    private val events = MutableSharedFlow<AttachmentFinalizedEvent>()
    private val transfers = MutableStateFlow<List<AttachmentTransferObservation>>(emptyList())
    private var state = ChatDetailUiState()
    private val scheduled = mutableListOf<Pair<String, String>>()

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        generation: Long = 7L,
        owner: String = "user",
    ) = ChatAttachmentObservationController(
        scope = scope,
        currentState = { state },
        updateState = { transform -> state = transform(state) },
        activeChatId = { "chat" },
        mergeMessages = ::mergeMessageVersions,
        mediaStateController = ChatMediaStateController(),
        attachmentFinalizedEvents = events,
        currentSessionGeneration = { generation },
        attachmentTransfers = transfers,
        ownerUserId = { owner },
        scheduleFinalSend = { messageId, ownerUserId -> scheduled += messageId to ownerUserId },
    )

    private fun message(id: String, timestamp: Long, content: String = id) = Message(
        id = id,
        chatId = "chat",
        senderId = "user",
        content = content,
        type = MessageType.TEXT,
        timestamp = timestamp,
        status = MessageStatus.SENDING,
    )

    private fun transfer(
        messageId: String,
        chatId: String = "chat",
        ownerUserId: String = "user",
        state: String = AttachmentTransferState.UPLOADING,
        uploadedBytes: Long = 500L,
        cipherSize: Long = 1000L,
    ) = AttachmentTransferObservation(
        messageId = messageId,
        chatId = chatId,
        ownerUserId = ownerUserId,
        state = state,
        uploadedBytes = uploadedBytes,
        cipherSize = cipherSize,
        lastErrorCode = null,
    )

    @Test
    fun `finalized event merges message and clears transient transfer state`() = runTest(dispatcher) {
        state = ChatDetailUiState(
            messages = listOf(message("m1", 1L, content = "old")),
            fileTransferProgress = mapOf("m1" to 0.5f),
            fileTransferStates = mapOf("m1" to AttachmentTransferState.UPLOADING),
            fileTransferErrors = mapOf("m1" to "E"),
            preparingAttachmentMessageIds = setOf("m1"),
        )
        controller(backgroundScope).observeAttachmentFinalizedEvents()
        advanceUntilIdle()

        events.emit(AttachmentFinalizedEvent(message("m1", 1L, content = "new"), sessionGeneration = 7L))
        advanceUntilIdle()

        assertEquals(listOf("new"), state.messages.map(Message::content))
        assertTrue(state.fileTransferProgress.isEmpty())
        assertTrue(state.fileTransferStates.isEmpty())
        assertTrue(state.fileTransferErrors.isEmpty())
        assertTrue(state.preparingAttachmentMessageIds.isEmpty())
    }

    @Test
    fun `finalized event with stale generation is ignored`() = runTest(dispatcher) {
        state = ChatDetailUiState(messages = listOf(message("m1", 1L)))
        controller(backgroundScope, generation = 8L).observeAttachmentFinalizedEvents()
        advanceUntilIdle()

        events.emit(AttachmentFinalizedEvent(message("m1", 1L, content = "new"), sessionGeneration = 7L))
        advanceUntilIdle()

        assertEquals(listOf("m1"), state.messages.map(Message::content))
    }

    @Test
    fun `finalized event for another chat is ignored`() = runTest(dispatcher) {
        state = ChatDetailUiState(messages = listOf(message("m1", 1L)))
        controller(backgroundScope).observeAttachmentFinalizedEvents()
        advanceUntilIdle()

        events.emit(
            AttachmentFinalizedEvent(
                message("m1", 1L, content = "new").copy(chatId = "other"),
                sessionGeneration = 7L,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("m1"), state.messages.map(Message::content))
    }

    @Test
    fun `transfers project own rows and schedule final send for ready row`() = runTest(dispatcher) {
        state = ChatDetailUiState(messages = listOf(message("m1", 1L), message("m2", 2L)))
        controller(backgroundScope).observeAttachmentTransfers()
        advanceUntilIdle()

        transfers.value = listOf(
            transfer("m1", state = AttachmentTransferState.READY),
            transfer("m2", state = AttachmentTransferState.UPLOADING),
            transfer("m3", ownerUserId = "someone-else", state = AttachmentTransferState.READY),
            transfer("m4", chatId = "other", state = AttachmentTransferState.READY),
        )
        advanceUntilIdle()

        assertEquals("READY", state.fileTransferStates["m1"])
        assertEquals(0.92f, state.fileTransferProgress.getValue("m1"), 0.001f)
        assertEquals("UPLOADING", state.fileTransferStates["m2"])
        assertEquals(0.625f, state.fileTransferProgress.getValue("m2"), 0.001f)
        assertFalse(state.fileTransferStates.containsKey("m3"))
        assertFalse(state.fileTransferStates.containsKey("m4"))
        assertEquals(listOf("m1" to "user"), scheduled)
    }

    @Test
    fun `transfers with blank owner are ignored`() = runTest(dispatcher) {
        state = ChatDetailUiState(messages = listOf(message("m1", 1L)))
        controller(backgroundScope, owner = "").observeAttachmentTransfers()
        advanceUntilIdle()

        transfers.value = listOf(transfer("m1", state = AttachmentTransferState.READY))
        advanceUntilIdle()

        assertTrue(state.fileTransferStates.isEmpty())
        assertTrue(scheduled.isEmpty())
    }
}
