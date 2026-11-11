package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.User
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatModerationControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeModerationGateway : ChatModerationGateway {
        var blocked: List<String> = listOf("u2")
        var blockResult: Result<Unit> = Result.success(Unit)
        var unblockResult: Result<Unit> = Result.success(Unit)
        var reportResult: Result<Unit> = Result.success(Unit)
        val calls = mutableListOf<String>()

        override suspend fun blockedUserIds(token: String): Result<List<String>> {
            calls += "blocked:$token"
            return Result.success(blocked)
        }

        override suspend fun blockUser(token: String, userId: String): Result<Unit> {
            calls += "block:$token:$userId"
            return blockResult
        }

        override suspend fun unblockUser(token: String, userId: String): Result<Unit> {
            calls += "unblock:$token:$userId"
            return unblockResult
        }

        override suspend fun createReport(
            token: String,
            targetType: String,
            targetId: String,
            chatId: String?,
            messageId: String?,
            reason: String,
            description: String?,
        ): Result<Unit> {
            calls += "report:$targetType:$targetId:$chatId:$messageId"
            return reportResult
        }
    }

    private class Harness(
        initial: ChatDetailUiState = ChatDetailUiState(contact = User("u2", "Bob")),
        val dispatcher: TestDispatcher,
    ) {
        var live: ChatDetailUiState = initial
        var ownerId: String = "u1"
        var tokenValue: String = "tok"
        var activeChatId: String = "c1"
        var sessionActiveValue: Boolean = true
        var moderationEnabledValue: Boolean = true
        val gateway = FakeModerationGateway()

        val controller = ChatModerationController(
            moderationGateway = gateway,
            moderationEnabled = { moderationEnabledValue },
            scope = CoroutineScope(dispatcher),
            ownerUserId = { ownerId },
            token = { tokenValue },
            activeChatId = { activeChatId },
            sessionActive = { sessionActiveValue },
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            text = { message, args ->
                "[${message.name}]" + if (args.isEmpty()) "" else " " + args.joinToString(",")
            },
        )
    }

    private fun harness(initial: ChatDetailUiState = ChatDetailUiState(contact = User("u2", "Bob"))) =
        Harness(initial, dispatcher)

    @Test
    fun `refresh sets blocked when id in list`() = runTest(dispatcher) {
        val h = harness()
        h.controller.refreshBlockState()
        advanceUntilIdle()

        assertTrue(h.live.isContactBlocked)
        assertEquals(listOf("blocked:tok"), h.gateway.calls)
    }

    @Test
    fun `refresh leaves unblocked when id absent`() = runTest(dispatcher) {
        val h = harness()
        h.gateway.blocked = listOf("u9")
        h.controller.refreshBlockState()
        advanceUntilIdle()

        assertFalse(h.live.isContactBlocked)
    }

    @Test
    fun `refresh with blank contact does nothing`() = runTest(dispatcher) {
        val h = harness(ChatDetailUiState(contact = User("", "")))
        h.controller.refreshBlockState()
        advanceUntilIdle()

        assertTrue(h.gateway.calls.isEmpty())
        assertFalse(h.live.isContactBlocked)
    }

    @Test
    fun `block success sets blocked and warns`() = runTest(dispatcher) {
        val h = harness()
        h.controller.blockContact()
        advanceUntilIdle()

        assertTrue(h.live.isContactBlocked)
        assertFalse(h.live.isBlockingContact)
        assertEquals("[CHAT_BLOCKED_USER_STATUS] Bob", h.live.groupEncryptionWarning)
        assertEquals(listOf("block:tok:u2"), h.gateway.calls)
    }

    @Test
    fun `block self does nothing`() = runTest(dispatcher) {
        val h = harness(ChatDetailUiState(contact = User("u1", "Me")))
        h.controller.blockContact()
        advanceUntilIdle()

        assertTrue(h.gateway.calls.isEmpty())
        assertNull(h.live.groupEncryptionWarning)
    }

    @Test
    fun `block disabled warns`() = runTest(dispatcher) {
        val h = harness()
        h.moderationEnabledValue = false
        h.controller.blockContact()
        advanceUntilIdle()

        assertEquals("[FEATURE_DISABLED_BY_ADMIN]", h.live.groupEncryptionWarning)
        assertTrue(h.gateway.calls.isEmpty())
    }

    @Test
    fun `block failure warns error message`() = runTest(dispatcher) {
        val h = harness()
        h.gateway.blockResult = Result.failure(RuntimeException("boom"))
        h.controller.blockContact()
        advanceUntilIdle()

        assertFalse(h.live.isContactBlocked)
        assertFalse(h.live.isBlockingContact)
        assertEquals("boom", h.live.groupEncryptionWarning)
    }

    @Test
    fun `unblock success clears blocked and warns`() = runTest(dispatcher) {
        val h = harness(ChatDetailUiState(contact = User("u2", "Bob"), isContactBlocked = true))
        h.controller.unblockContact()
        advanceUntilIdle()

        assertFalse(h.live.isContactBlocked)
        assertFalse(h.live.isBlockingContact)
        assertEquals("[CHAT_UNBLOCKED_USER_STATUS] Bob", h.live.groupEncryptionWarning)
        assertEquals(listOf("unblock:tok:u2"), h.gateway.calls)
    }

    @Test
    fun `unblock failure warns error message`() = runTest(dispatcher) {
        val h = harness()
        h.gateway.unblockResult = Result.failure(RuntimeException("boom"))
        h.controller.unblockContact()
        advanceUntilIdle()

        assertFalse(h.live.isBlockingContact)
        assertEquals("boom", h.live.groupEncryptionWarning)
    }

    @Test
    fun `report contact success warns submitted`() = runTest(dispatcher) {
        val h = harness()
        h.controller.reportContact(reason = "spam")
        advanceUntilIdle()

        assertEquals("[CHAT_REPORT_SUBMITTED]", h.live.groupEncryptionWarning)
        assertEquals(listOf("report:USER:u2:c1:null"), h.gateway.calls)
    }

    @Test
    fun `report message success clears reporting and warns submitted`() = runTest(dispatcher) {
        // isReporting 必须从 false 起：为 true 时 reportMessage 的“已在上报”守卫会直接 return。
        val h = harness()
        h.controller.reportMessage(messageId = "m1", reason = "spam")
        assertTrue(h.live.isReporting)
        advanceUntilIdle()

        assertFalse(h.live.isReporting)
        assertEquals("[CHAT_REPORT_SUBMITTED]", h.live.groupEncryptionWarning)
        assertEquals(listOf("report:MESSAGE:m1:c1:m1"), h.gateway.calls)
    }

    @Test
    fun `report message failure warns error message and clears reporting`() = runTest(dispatcher) {
        // isReporting 必须从 false 起：为 true 时 reportMessage 的“已在上报”守卫会直接 return。
        val h = harness()
        h.gateway.reportResult = Result.failure(RuntimeException("boom"))
        h.controller.reportMessage(messageId = "m1", reason = "spam")
        assertTrue(h.live.isReporting)
        advanceUntilIdle()

        assertFalse(h.live.isReporting)
        assertEquals("boom", h.live.groupEncryptionWarning)
    }
}
