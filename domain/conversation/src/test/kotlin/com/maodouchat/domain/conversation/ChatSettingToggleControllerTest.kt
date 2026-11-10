package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest
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
class ChatSettingToggleControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeChatSettingsGateway : ChatSettingsGateway {
        var result: Result<ChatSettingsResponse> = Result.success(
            ChatSettingsResponse("c1", 0L, false, false, false, 0L)
        )
        val calls = mutableListOf<UpdateChatSettingsRequest>()

        override suspend fun updateChatSettings(
            token: String,
            chatId: String,
            request: UpdateChatSettingsRequest,
        ): Result<ChatSettingsResponse> {
            calls += request
            return result
        }
    }

    private fun harness(
        chat: Chat = Chat(id = "c1"),
        chatPinEnabled: Boolean = true,
        token: String = "token",
    ): Triple<ChatSettingToggleController, FakeChatSettingsGateway, () -> ChatDetailUiState> {
        var live = ChatDetailUiState(chat = chat)
        val gateway = FakeChatSettingsGateway()
        val controller = ChatSettingToggleController(
            scope = CoroutineScope(dispatcher),
            chatPinEnabled = { chatPinEnabled },
            sessionActive = { true },
            liveToken = { "live-token" },
            chatSettingsGateway = gateway,
            ownerUserId = { "me" },
            token = { token },
            chatId = { "c1" },
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            featureDisabledText = { "disabled by admin" },
        )
        return Triple(controller, gateway) { live }
    }

    @Test
    fun `toggle pinned sets timestamp optimistically and keeps it on success`() = runTest(dispatcher) {
        val (controller, gateway, state) = harness()
        controller.toggleChatPinned()
        advanceUntilIdle()

        assertEquals(1, gateway.calls.size)
        assertEquals(true, gateway.calls.single().pinned)
        assertTrue((state().chat?.pinnedAt ?: 0L) > 0L)
    }

    @Test
    fun `toggle pinned rolls back on failure`() = runTest(dispatcher) {
        val (controller, gateway, state) = harness()
        gateway.result = Result.failure(RuntimeException("boom"))
        controller.toggleChatPinned()
        advanceUntilIdle()

        assertEquals(0L, state().chat?.pinnedAt)
    }

    @Test
    fun `toggle pinned warns when flag disabled`() = runTest(dispatcher) {
        val (controller, gateway, state) = harness(chatPinEnabled = false)
        controller.toggleChatPinned()
        advanceUntilIdle()

        assertTrue(gateway.calls.isEmpty())
        assertEquals("disabled by admin", state().groupEncryptionWarning)
        assertEquals(0L, state().chat?.pinnedAt)
    }

    @Test
    fun `toggle marked unread flips and rolls back on failure`() = runTest(dispatcher) {
        val (controller, gateway, state) = harness()
        gateway.result = Result.failure(RuntimeException("boom"))
        controller.toggleChatMarkedUnread()
        advanceUntilIdle()

        assertFalse(state().chat?.markedUnread ?: true)
        assertEquals(true, gateway.calls.single().markedUnread)
    }

    @Test
    fun `toggle marked unread does nothing without session`() = runTest(dispatcher) {
        val (controller, gateway, state) = harness(token = "")
        controller.toggleChatMarkedUnread()
        advanceUntilIdle()

        assertTrue(gateway.calls.isEmpty())
        assertFalse(state().chat?.markedUnread ?: true)
    }
}
