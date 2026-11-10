package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupAuditLoadControllerTest {

    private val dispatcher = StandardTestDispatcher()

    private fun log(id: String) = GroupAuditLogDto(
        id = id,
        actorId = "u1",
        actorName = "Ann",
        action = "ADD_MEMBER",
        createdAt = 1L,
    )

    private class Harness(
        initial: GroupDetailUiState,
        val gateway: FakeGroupNetworkGateway,
        val dispatcher: TestDispatcher,
        sessionActive: (String) -> Boolean = { true },
    ) {
        var live: GroupDetailUiState = initial
        var offset = 0
        val controller = GroupAuditLoadController(
            scope = CoroutineScope(dispatcher),
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            chatId = { "c1" },
            ownerUserId = { "me" },
            sessionActive = sessionActive,
            groupAuditController = GroupAuditController(
                tokenProvider = { "t" },
                gateway = gateway,
                ioDispatcher = dispatcher,
            ),
            auditOffsetGet = { offset },
            auditOffsetSet = { offset = it },
        )
    }

    private fun harness(
        initial: GroupDetailUiState = GroupDetailUiState(hasMoreAudit = true),
        sessionActive: (String) -> Boolean = { true },
    ) = Harness(initial, FakeGroupNetworkGateway(), dispatcher, sessionActive)

    private fun awaitQuiescence() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `loads page, appends deduped logs and advances offset`() = runTest(dispatcher) {
        val h = harness()
        h.gateway.getGroupAudit = { _, _, _, _ ->
            Result.success(listOf(log("a1"), log("a2")))
        }

        h.controller.loadMoreAudit()
        awaitQuiescence()

        assertEquals(listOf("a1", "a2"), h.live.auditLogs.map { it.id })
        assertEquals(2, h.offset)
        assertFalse(h.live.isLoadingMoreAudit)
        assertFalse(h.live.hasMoreAudit)
    }

    @Test
    fun `empty page clears hasMoreAudit`() = runTest(dispatcher) {
        val h = harness()
        h.gateway.getGroupAudit = { _, _, _, _ -> Result.success(emptyList()) }

        h.controller.loadMoreAudit()
        awaitQuiescence()

        assertTrue(h.live.auditLogs.isEmpty())
        assertFalse(h.live.hasMoreAudit)
        assertFalse(h.live.isLoadingMoreAudit)
    }

    @Test
    fun `skips load while already loading`() = runTest(dispatcher) {
        val h = harness(initial = GroupDetailUiState(hasMoreAudit = true, isLoadingMoreAudit = true))
        var calls = 0
        h.gateway.getGroupAudit = { _, _, _, _ ->
            calls += 1
            Result.success(emptyList())
        }

        h.controller.loadMoreAudit()
        awaitQuiescence()

        assertEquals(0, calls)
    }

    @Test
    fun `session inactive clears loading flag without fetching`() = runTest(dispatcher) {
        val h = harness(sessionActive = { false })
        var calls = 0
        h.gateway.getGroupAudit = { _, _, _, _ ->
            calls += 1
            Result.success(emptyList())
        }

        h.controller.loadMoreAudit()
        awaitQuiescence()

        assertEquals(0, calls)
        assertFalse(h.live.isLoadingMoreAudit)
    }
}
