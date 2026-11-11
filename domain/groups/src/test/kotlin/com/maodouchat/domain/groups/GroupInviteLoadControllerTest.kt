package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupInviteResponse
import com.maodouchat.core.model.GroupMutationAction
import com.maodouchat.core.model.GroupMutationFeedbackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupInviteLoadControllerTest {

    private val dispatcher = StandardTestDispatcher()

    private class Harness(
        initial: GroupDetailUiState = GroupDetailUiState(),
        val gateway: FakeGroupNetworkGateway = FakeGroupNetworkGateway(),
        val dispatcher: TestDispatcher,
        sessionActive: (String) -> Boolean = { true },
        invitesEnabled: () -> Boolean = { true },
        chatId: String = "c1",
        token: String = "t",
        ownerUserId: String = "me",
    ) {
        var live: GroupDetailUiState = initial
        var pendingRetry: (() -> Unit)? = null
        val controller = GroupInviteLoadController(
            scope = CoroutineScope(dispatcher),
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            text = { it.name },
            sessionActive = sessionActive,
            invitesEnabled = invitesEnabled,
            groupInviteController = GroupInviteController(tokenProvider = { "t" }, gateway = gateway),
            chatId = { chatId },
            token = { token },
            ownerUserId = { ownerUserId },
            pendingRetrySet = { pendingRetry = it },
        )

        // GroupInviteController fetches on Dispatchers.IO (real thread), so drain
        // the test dispatcher, let the IO hop land, and drain again.
        suspend fun awaitSettled() {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (true) {
                        dispatcher.scheduler.advanceUntilIdle()
                        if (!live.isLoadingInvite) break
                        delay(10)
                    }
                }
            }
        }
    }

    private fun harness(
        sessionActive: (String) -> Boolean = { true },
        invitesEnabled: () -> Boolean = { true },
        chatId: String = "c1",
    ) = Harness(GroupDetailUiState(), FakeGroupNetworkGateway(), dispatcher, sessionActive, invitesEnabled, chatId)

    private fun inviteResponse() = GroupInviteResponse(
        token = "t",
        payload = "PAY",
        expiresAt = 99,
        maxUses = 5,
        usedCount = 1,
        remainingUses = 4,
    )

    @Test
    fun `disabled invites do nothing`() = runTest {
        val h = harness(invitesEnabled = { false })
        h.controller.loadGroupInvite()
        h.awaitSettled()

        assertFalse(h.live.isLoadingInvite)
        assertNull(h.live.message)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `blank chat shows session expired`() = runTest {
        val h = harness(chatId = "")
        h.controller.loadGroupInvite()
        h.awaitSettled()

        assertFalse(h.live.isLoadingInvite)
        assertEquals("SESSION_EXPIRED", h.live.message)
        assertEquals(GroupMutationAction.INVITE, h.live.feedback?.action)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `inactive session shows session expired`() = runTest {
        val h = harness(sessionActive = { false })
        h.controller.loadGroupInvite()
        h.awaitSettled()

        assertFalse(h.live.isLoadingInvite)
        assertEquals("SESSION_EXPIRED", h.live.message)
        assertEquals(GroupMutationAction.INVITE, h.live.feedback?.action)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `fetch success writes invite fields`() = runTest {
        val h = harness()
        h.gateway.getOrCreateGroupInvite = { _, _, _, _, _ -> Result.success(inviteResponse()) }
        h.controller.loadGroupInvite()
        h.awaitSettled()

        assertFalse(h.live.isLoadingInvite)
        assertEquals("PAY", h.live.groupInvitePayload)
        assertEquals(99L, h.live.inviteExpiresAt)
        assertEquals(5, h.live.inviteMaxUses)
        assertEquals(1, h.live.inviteUsedCount)
        assertEquals(4, h.live.inviteRemainingUses)
        assertNull(h.live.message)
        assertNull(h.live.feedback)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `rotate success writes refreshed message and feedback`() = runTest {
        var sawRotate = false
        val h = harness()
        h.gateway.getOrCreateGroupInvite = { _, _, rotate, _, _ ->
            sawRotate = rotate
            Result.success(inviteResponse())
        }
        h.controller.loadGroupInvite(rotate = true)
        h.awaitSettled()

        assertTrue(sawRotate)
        assertEquals("PAY", h.live.groupInvitePayload)
        assertEquals("INVITE_REFRESHED", h.live.message)
        assertEquals(GroupMutationFeedbackKind.SUCCESS, h.live.feedback?.kind)
        assertEquals(GroupMutationAction.INVITE, h.live.feedback?.action)
    }

    @Test
    fun `fetch failure shows invite failed and retry reloads`() = runTest {
        val h = harness()
        h.gateway.getOrCreateGroupInvite = { _, _, _, _, _ -> Result.failure(IllegalStateException()) }
        h.controller.loadGroupInvite()
        h.awaitSettled()

        assertFalse(h.live.isLoadingInvite)
        assertEquals("INVITE_FAILED", h.live.message)
        assertTrue(h.live.feedback?.canRetry == true)
        assertNotNull(h.pendingRetry)

        h.gateway.getOrCreateGroupInvite = { _, _, _, _, _ -> Result.success(inviteResponse()) }
        h.pendingRetry?.invoke()
        h.awaitSettled()

        assertEquals("PAY", h.live.groupInvitePayload)
        assertNull(h.live.message)
    }
}
