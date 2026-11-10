package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.PinnedMessageDto
import com.maodouchat.core.model.PinnedMessagesListResponse
import com.maodouchat.core.model.StarMessageResponse
import com.maodouchat.core.model.TogglePinResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPinStarControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakePinStarGateway : PinStarGateway {
        var pinsResult: Result<PinnedMessagesListResponse> =
            Result.success(PinnedMessagesListResponse(chatId = "c1"))
        var togglePinResult: Result<TogglePinResponse> =
            Result.success(TogglePinResponse(pinned = true))
        var toggleStarResult: Result<StarMessageResponse> =
            Result.success(StarMessageResponse(starred = true))
        val togglePinCalls = mutableListOf<String>()
        val toggleStarCalls = mutableListOf<String>()
        var pinnedCalls = 0

        override suspend fun pinned(token: String, chatId: String): Result<PinnedMessagesListResponse> {
            pinnedCalls += 1
            return pinsResult
        }

        override suspend fun togglePin(token: String, chatId: String, messageId: String): Result<TogglePinResponse> {
            togglePinCalls += messageId
            return togglePinResult
        }

        override suspend fun toggleStar(token: String, messageId: String): Result<StarMessageResponse> {
            toggleStarCalls += messageId
            return toggleStarResult
        }
    }

    private fun message(id: String, type: MessageType = MessageType.TEXT, starred: Boolean = false) =
        Message(id = id, chatId = "c1", senderId = "me", content = "hi", type = type, starred = starred)

    private fun pin(id: String) = PinnedMessageDto(chatId = "c1", messageId = id, pinnedBy = "me", pinnedAt = 1L)

    private class Harness(
        initial: ChatDetailUiState,
        val gateway: FakePinStarGateway,
        val dispatcher: StandardTestDispatcher,
    ) {
        var live: ChatDetailUiState = initial
        val controller = ChatPinStarController(
            messagePinEnabled = { true },
            messageStarringEnabled = { true },
            secretStarBlockEnabled = { true },
            pinStarGateway = gateway,
            scope = CoroutineScope(dispatcher),
            chatId = { "c1" },
            ownerUserId = { "me" },
            token = { "token" },
            sessionActive = { true },
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            persistMessage = { },
            text = { message, args -> "[${message.name}:${args.joinToString(",")}]" },
        )
    }

    private fun harness(initial: ChatDetailUiState = ChatDetailUiState()) =
        Harness(initial, FakePinStarGateway(), dispatcher)

    @Test
    fun `group member without admin role cannot pin`() = runTest(dispatcher) {
        val h = harness(
            ChatDetailUiState(
                chatIsGroup = true,
                myMemberRole = "MEMBER",
                messages = listOf(message("m1")),
            )
        )
        h.controller.togglePinMessage("m1")
        advanceUntilIdle()

        assertTrue(h.gateway.togglePinCalls.isEmpty())
        assertEquals("[PIN_FORBIDDEN:]", h.live.groupEncryptionWarning)
    }

    @Test
    fun `pin at limit warns instead of calling network`() = runTest(dispatcher) {
        val h = harness(
            ChatDetailUiState(
                pinnedMessages = (1..20).map { pin("p$it") },
                messages = listOf(message("m1")),
            )
        )
        h.controller.togglePinMessage("m1")
        advanceUntilIdle()

        assertTrue(h.gateway.togglePinCalls.isEmpty())
        assertEquals("[PIN_LIMIT:20]", h.live.groupEncryptionWarning)
    }

    @Test
    fun `pin success applies server pins`() = runTest(dispatcher) {
        val h = harness(ChatDetailUiState(messages = listOf(message("m1"))))
        h.gateway.togglePinResult = Result.success(
            TogglePinResponse(pinned = true, pins = listOf(pin("m1")))
        )
        h.controller.togglePinMessage("m1")
        advanceUntilIdle()

        assertEquals(listOf("m1"), h.gateway.togglePinCalls)
        assertEquals(listOf("m1"), h.live.pinnedMessages.map { it.messageId })
        assertEquals("[PIN_SUCCESS:]", h.live.groupEncryptionWarning)
        assertFalse(h.live.isTogglingPin)
    }

    @Test
    fun `batch pin only toggles messages that need it and skips unpinnable`() = runTest(dispatcher) {
        val h = harness(
            ChatDetailUiState(
                pinnedMessages = listOf(pin("already")),
                messages = listOf(message("already"), message("todo"), message("key", MessageType.SK_DIST)),
            )
        )
        h.gateway.togglePinResult = Result.success(TogglePinResponse(pinned = true, pins = listOf(pin("todo"))))
        h.controller.togglePinMessages(listOf("already", "todo", "key"), shouldPin = true)
        advanceUntilIdle()

        assertEquals(listOf("todo"), h.gateway.togglePinCalls)
        assertEquals("[BATCH_PIN_SUCCESS:1]", h.live.groupEncryptionWarning)
    }

    @Test
    fun `star flips optimistically and confirms server value`() = runTest(dispatcher) {
        val h = harness(ChatDetailUiState(messages = listOf(message("m1"))))
        h.controller.toggleStarMessage("m1")
        advanceUntilIdle()

        assertEquals(listOf("m1"), h.gateway.toggleStarCalls)
        assertTrue(h.live.messages.single { it.id == "m1" }.starred)
        assertEquals("[STARRED:]", h.live.groupEncryptionWarning)
    }

    @Test
    fun `star restores original on failure`() = runTest(dispatcher) {
        val h = harness(ChatDetailUiState(messages = listOf(message("m1", starred = true))))
        h.gateway.toggleStarResult = Result.failure(RuntimeException("boom"))
        h.controller.toggleStarMessage("m1")
        advanceUntilIdle()

        assertTrue(h.live.messages.single { it.id == "m1" }.starred)
        assertEquals("boom", h.live.groupEncryptionWarning)
    }

    @Test
    fun `star blocked in secret chat when flag on`() = runTest(dispatcher) {
        val h = harness(
            ChatDetailUiState(isSecretChat = true, messages = listOf(message("m1")))
        )
        h.controller.toggleStarMessage("m1")
        advanceUntilIdle()

        assertTrue(h.gateway.toggleStarCalls.isEmpty())
        assertEquals("[SECRET_STAR_BLOCKED:]", h.live.groupEncryptionWarning)
    }

    @Test
    fun `refresh loads pins when session active`() = runTest(dispatcher) {
        val h = harness()
        h.gateway.pinsResult = Result.success(
            PinnedMessagesListResponse(chatId = "c1", pins = listOf(pin("m1"), pin("m2")))
        )
        h.controller.refreshPinnedMessages("me")

        assertEquals(1, h.gateway.pinnedCalls)
        assertEquals(listOf("m1", "m2"), h.live.pinnedMessages.map { it.messageId })
    }
}
