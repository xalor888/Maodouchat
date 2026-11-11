package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupInviteBotControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private class Harness(
        private val scope: CoroutineScope,
        initial: GroupDetailUiState = GroupDetailUiState(currentUserId = "owner1"),
        var chatId: String = "chat1",
        var inviteResult: Result<String> = Result.success("ok"),
    ) {
        var live: GroupDetailUiState = initial
        var sessionGate: (String) -> Boolean = { true }
        val inviteCalls = mutableListOf<Pair<String, String>>()
        val reloads = mutableListOf<String?>()
        val controller = GroupInviteBotController(
            scope = scope,
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            chatId = { chatId },
            ownerUserId = { "owner1" },
            sessionActive = { userId -> sessionGate(userId) },
            inviteBot = { targetChatId, botId ->
                inviteCalls += targetChatId to botId
                inviteResult
            },
            text = { message -> "[${message.name}]" },
            reload = { message -> reloads += message },
        )
    }

    @Test
    fun `success clears inviting flag and reloads with invited text`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        h.controller.inviteOwnedBot("bot1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("chat1" to "bot1"), h.inviteCalls)
        assertFalse(h.live.isInvitingBot)
        assertEquals(listOf<String?>("[INVITED]"), h.reloads)
        assertNull(h.live.message)
    }

    @Test
    fun `failure clears inviting flag and shows failed message`() = runTest(dispatcher) {
        val h = Harness(backgroundScope, inviteResult = Result.failure(IllegalStateException("x")))
        h.controller.inviteOwnedBot("bot1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("chat1" to "bot1"), h.inviteCalls)
        assertFalse(h.live.isInvitingBot)
        assertEquals("[INVITE_FAILED]", h.live.message)
        assertTrue(h.reloads.isEmpty())
    }

    @Test
    fun `blank bot id is a no-op`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        h.controller.inviteOwnedBot("  ")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(h.inviteCalls.isEmpty())
        assertFalse(h.live.isInvitingBot)
        assertTrue(h.reloads.isEmpty())
    }

    @Test
    fun `blank chat id is a no-op`() = runTest(dispatcher) {
        val h = Harness(backgroundScope, chatId = "")
        h.controller.inviteOwnedBot("bot1")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(h.inviteCalls.isEmpty())
        assertTrue(h.reloads.isEmpty())
    }

    @Test
    fun `already inviting is a no-op`() = runTest(dispatcher) {
        val h = Harness(
            backgroundScope,
            initial = GroupDetailUiState(currentUserId = "owner1", isInvitingBot = true),
        )
        h.controller.inviteOwnedBot("bot1")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(h.inviteCalls.isEmpty())
        assertTrue(h.reloads.isEmpty())
    }

    @Test
    fun `inactive session does not invite`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        h.sessionGate = { false }
        h.controller.inviteOwnedBot("bot1")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(h.inviteCalls.isEmpty())
        assertTrue(h.reloads.isEmpty())
        // 原实现在门禁失败时直接 return，isInvitingBot 保持置位——逐字保留该行为。
        assertTrue(h.live.isInvitingBot)
    }

    @Test
    fun `session lost after invite skips reload`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        var checks = 0
        h.sessionGate = { ++checks == 1 }
        h.controller.inviteOwnedBot("bot1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("chat1" to "bot1"), h.inviteCalls)
        assertTrue(h.reloads.isEmpty())
        assertTrue(h.live.isInvitingBot)
    }
}
