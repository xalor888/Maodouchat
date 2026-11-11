package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatJumpControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Harness(
        initial: ChatDetailUiState,
        val dispatcher: TestDispatcher,
    ) {
        var live: ChatDetailUiState = initial
        var ownerId: String = "u1"
        var activeChatId: String = "c1"
        var chatId: String = "c1"
        var sessionActiveValue: Boolean = true
        var anchorId: String? = "m1"
        var throwError: Boolean = false
        val anchorCalls = mutableListOf<Pair<String, Long>>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()

        val controller = ChatJumpController(
            scope = CoroutineScope(dispatcher),
            ownerUserId = { ownerId },
            activeChatId = { activeChatId },
            chatId = { chatId },
            updateState = { transform -> live = transform(live) },
            text = { message -> "[${message.name}]" },
            sessionActive = { sessionActiveValue },
            getFirstMessageAtOrAfter = { targetChatId, dayStart ->
                anchorCalls += targetChatId to dayStart
                if (throwError) throw IllegalStateException("boom")
                anchorId
            },
            logWarning = { message, error -> warnings += message to error },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            searchSelectionStateController = ChatSearchSelectionStateController(),
        )
    }

    private fun harness(initial: ChatDetailUiState = ChatDetailUiState()) =
        Harness(initial, dispatcher)

    @Test
    fun `non-positive day start does nothing`() = runTest(dispatcher) {
        val h = harness()
        h.controller.jumpToDate(0L)
        advanceUntilIdle()

        assertTrue(h.anchorCalls.isEmpty())
        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `blank chat does nothing`() = runTest(dispatcher) {
        val h = harness()
        h.activeChatId = ""
        h.chatId = ""
        h.controller.jumpToDate(1000L)
        advanceUntilIdle()

        assertTrue(h.anchorCalls.isEmpty())
        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `owner me does nothing`() = runTest(dispatcher) {
        val h = harness()
        h.ownerId = "me"
        h.controller.jumpToDate(1000L)
        advanceUntilIdle()

        assertTrue(h.anchorCalls.isEmpty())
        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `inactive session does not query`() = runTest(dispatcher) {
        val h = harness()
        h.sessionActiveValue = false
        h.controller.jumpToDate(1000L)
        advanceUntilIdle()

        assertTrue(h.anchorCalls.isEmpty())
        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `no anchor warns date empty`() = runTest(dispatcher) {
        val h = harness()
        h.anchorId = null
        h.controller.jumpToDate(1000L)
        advanceUntilIdle()

        assertEquals(listOf("c1" to 1000L), h.anchorCalls)
        assertEquals("[DATE_EMPTY]", h.live.groupEncryptionWarning)
        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `anchor navigates`() = runTest(dispatcher) {
        val h = harness()
        h.controller.jumpToDate(1000L)
        advanceUntilIdle()

        assertEquals("m1", h.live.navigationTargetMessageId)
        assertNull(h.live.groupEncryptionWarning)
    }

    @Test
    fun `repository failure is logged not thrown`() = runTest(dispatcher) {
        val h = harness()
        h.throwError = true
        h.controller.jumpToDate(1000L)
        advanceUntilIdle()

        assertEquals(1, h.warnings.size)
        assertEquals("jumpToDate failed", h.warnings.single().first)
        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `jump to pinned message navigates`() {
        val h = harness()
        h.controller.jumpToPinnedMessage("m9")

        assertEquals("m9", h.live.navigationTargetMessageId)
    }

    @Test
    fun `jump to pinned message blank is no-op`() {
        val h = harness()
        h.controller.jumpToPinnedMessage("")

        assertNull(h.live.navigationTargetMessageId)
    }

    @Test
    fun `jump to message navigates`() {
        val h = harness()
        h.controller.jumpToMessage("m8")

        assertEquals("m8", h.live.navigationTargetMessageId)
    }

    @Test
    fun `jump to message blank is no-op`() {
        val h = harness()
        h.controller.jumpToMessage("")

        assertNull(h.live.navigationTargetMessageId)
    }
}
