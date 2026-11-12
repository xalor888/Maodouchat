package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.SeenSet
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageStatusObservationControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    private fun message(
        id: String = "m1",
        senderId: String = "u2",
        timestamp: Long = 1000L,
        status: MessageStatus = MessageStatus.SENT,
    ) = Message(
        id = id,
        chatId = "c1",
        senderId = senderId,
        content = "body",
        type = MessageType.TEXT,
        timestamp = timestamp,
        status = status,
    )

    private class Harness(
        initial: ChatDetailUiState,
        private val dispatcher: UnconfinedTestDispatcher,
    ) {
        val uiState = MutableStateFlow(initial)
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)

        val seen = SeenSet()
        var lastSeen: List<Pair<String, MessageStatus>>? = null
        var pendingWatermark: String? = null
        var markReadJob: Job? = null
        val markedThrough = mutableListOf<Tuple4<String, String, Long, String>>()
        val markedAllRead = mutableListOf<String>()
        val receipts = mutableListOf<Triple<String, String, Long?>>()
        val emittedReads = mutableListOf<String>()
        val armedDisappearing = mutableListOf<Pair<String, String?>>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        var activeChatId: String? = "c1"
        var sessionMayContinue: Boolean = true
        var enqueueFails: Boolean = false

        // 采集器永不结束：settle 只等其余子协程。
        private var collector: Job? = null

        fun controller(): ChatMessageStatusObservationController {
            val c = ChatMessageStatusObservationController(
                uiState = uiState,
                scope = scope,
                ownerUserId = { "owner-1" },
                activeChatId = { "c1" },
                chatId = "c1",
                updateState = { transform -> uiState.update(transform) },
                getLastMessagesSeen = { lastSeen },
                setLastMessagesSeen = { lastSeen = it },
                readSeenMessages = seen,
                setPendingReadWatermarkMessageId = { pendingWatermark = it },
                getMarkReadJob = { markReadJob },
                setMarkReadJob = { markReadJob = it },
                armSecretDisappearing = { chatId, watermark -> armedDisappearing += chatId to watermark },
                currentActiveChatId = { activeChatId },
                markIncomingReadThrough = { chatId, ownerId, throughTimestamp, throughId ->
                    markedThrough += Tuple4(chatId, ownerId, throughTimestamp, throughId)
                },
                markAllRead = { markedAllRead += it },
                sessionMayContinue = { sessionMayContinue },
                enqueueReadReceipt = { conversationId, throughMessageId, groupRevision ->
                    if (enqueueFails) throw RuntimeException("boom")
                    receipts += Triple(conversationId, throughMessageId, groupRevision)
                },
                emitChatRead = { emittedReads += it },
                logWarning = { message, error -> warnings += message to error },
            )
            c.observeMessageStatus()
            collector = scopeJob.children.first()
            return c
        }

        suspend fun settle() {
            scopeJob.children.filter { it != collector }.forEach { it.join() }
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun state(
        messages: List<Message> = listOf(message()),
        isSecretChat: Boolean? = null,
    ) = ChatDetailUiState(
        contact = peer,
        chat = chat,
        messages = messages,
        isSecretChat = isSecretChat,
    )

    @Test
    fun nonActiveChatIsIgnored() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { activeChatId = "other" }
        harness.controller()
        harness.settle()

        assertTrue(harness.markedThrough.isEmpty())
        assertTrue(harness.markedAllRead.isEmpty())
        assertTrue(harness.receipts.isEmpty())
        assertNull(harness.lastSeen)
    }

    @Test
    fun duplicateTimelineIsIgnored() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller()
        advanceTimeBy(600)
        runCurrent()
        harness.settle()
        assertEquals(1, harness.markedThrough.size)

        // 相同消息 id/状态、仅改无关字段：第二次 onEach 因消息已标已读、无未读计划而跳过，不再持久化。
        harness.uiState.update { it.copy(isLoading = true) }
        harness.settle()
        assertEquals(1, harness.markedThrough.size)
    }

    @Test
    fun unreadPeerMessageMarksReadAndEnqueuesReceipt() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller()
        advanceTimeBy(600)
        runCurrent()
        harness.settle()

        assertEquals(listOf(Tuple4("c1", "owner-1", 1000L, "m1")), harness.markedThrough)
        assertEquals(listOf("c1"), harness.markedAllRead)
        assertEquals(listOf(Triple("c1", "m1", null)), harness.receipts)
        assertEquals(listOf("c1"), harness.emittedReads)
        assertEquals("m1", harness.pendingWatermark)
        assertEquals(MessageStatus.READ, harness.uiState.value.messages.single().status)
        assertTrue(harness.warnings.isEmpty())
    }

    @Test
    fun secretChatArmsDisappearingInsteadOfReceipt() = runTest(dispatcher) {
        val harness = Harness(state(isSecretChat = true), dispatcher)
        harness.controller()
        advanceTimeBy(600)
        runCurrent()
        harness.settle()

        assertEquals(listOf("c1" to "m1"), harness.armedDisappearing)
        assertTrue(harness.receipts.isEmpty())
    }

    @Test
    fun receiptEnqueueFailureRollsBackSeen() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { enqueueFails = true }
        harness.controller()
        advanceTimeBy(600)
        runCurrent()
        harness.settle()

        assertEquals(1, harness.warnings.size)
        assertTrue(harness.warnings.single().first.contains("v2 read receipt enqueue failed"))
        assertNull(harness.lastSeen)
        assertFalse(harness.seen.contains("m1"))
        assertTrue(harness.receipts.isEmpty())
    }

    @Test
    fun alreadyReadTimelineProducesNoPlan() = runTest(dispatcher) {
        val harness = Harness(state(listOf(message(status = MessageStatus.READ))), dispatcher)
        harness.controller()
        harness.settle()

        assertTrue(harness.markedThrough.isEmpty())
        assertTrue(harness.receipts.isEmpty())
        assertTrue(harness.emittedReads.isEmpty())
    }
}
