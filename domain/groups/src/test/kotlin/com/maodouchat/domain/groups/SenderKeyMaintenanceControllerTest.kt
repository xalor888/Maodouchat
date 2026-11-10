package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupSenderKeyMaintenanceCoordinator
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SenderKeyMaintenanceControllerTest {

    private val dispatcher = StandardTestDispatcher()

    private fun status(epoch: Long = 5L) = SenderKeyDistributionStatusDto(
        chatId = "c1",
        epoch = epoch,
        total = 0,
        sent = 0,
        failed = 0,
        pending = 0,
    )

    private fun coordinator(
        redistribute: suspend (String, Long) -> Result<SenderKeyDistributionStatusDto?> = { _, _ ->
            Result.success(status())
        },
        ensureCoverage: suspend (String, Long) -> Result<SenderKeyDistributionStatusDto?> = { _, _ ->
            Result.success(status())
        },
        hasLocalSenderKey: suspend (String, Long) -> Boolean = { _, _ -> true },
    ) = GroupSenderKeyMaintenanceCoordinator(
        ensureCoverage = ensureCoverage,
        redistribute = redistribute,
        hasLocalSenderKey = hasLocalSenderKey,
        enqueueRetry = { _, _, _ -> },
    )

    private class Harness(
        initial: GroupDetailUiState,
        val dispatcher: TestDispatcher,
        coordinator: GroupSenderKeyMaintenanceCoordinator,
        chatId: () -> String = { "c1" },
        token: () -> String = { "t" },
        sessionActive: (String) -> Boolean = { true },
    ) {
        var live: GroupDetailUiState = initial
        var committed: String? = null
        var pendingRetry: (() -> Unit)? = null
        val controller = SenderKeyMaintenanceController(
            scope = CoroutineScope(dispatcher),
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            text = { message -> "[${message.name}]" },
            chatId = chatId,
            token = token,
            ownerUserId = { "me" },
            sessionActive = sessionActive,
            groupEncryptionHealthController = GroupEncryptionHealthController(
                maintenanceCoordinator = coordinator,
                tokenProvider = { "t" },
                gateway = FakeGroupNetworkGateway(),
                ioDispatcher = dispatcher,
            ),
            onCommitted = { committed = it },
            pendingRetrySet = { pendingRetry = it },
            ioDispatcher = dispatcher,
        )
    }

    private fun harness(
        initial: GroupDetailUiState = GroupDetailUiState(memberRevision = 5L),
        coordinator: GroupSenderKeyMaintenanceCoordinator = coordinator(),
        chatId: () -> String = { "c1" },
        token: () -> String = { "t" },
        sessionActive: (String) -> Boolean = { true },
    ) = Harness(initial, dispatcher, coordinator, chatId, token, sessionActive)

    private fun awaitQuiescence() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `manual redistribute success updates status and commits`() = runTest(dispatcher) {
        val h = harness()

        h.controller.redistributeSenderKey()
        awaitQuiescence()

        assertEquals(status(), h.live.senderKeyStatus)
        assertTrue(h.live.localHasSenderKey == true)
        assertFalse(h.live.isUpdating)
        assertEquals("[KEY_REDISTRIBUTED]", h.committed)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `blank chat writes session expired feedback without launching`() = runTest(dispatcher) {
        val h = harness(chatId = { "" })

        h.controller.redistributeSenderKey()

        assertEquals("[SESSION_EXPIRED]", h.live.message)
        assertNotNull(h.live.feedback)
        assertFalse(h.live.isUpdating)
    }

    @Test
    fun `redistribute failure shows retryable feedback and arms retry`() = runTest(dispatcher) {
        val failing = coordinator(redistribute = { _, _ -> Result.failure(IllegalStateException()) })
        val h = harness(coordinator = failing)

        h.controller.redistributeSenderKey()
        awaitQuiescence()

        assertEquals("[KEY_REDISTRIBUTE_FAILED]", h.live.message)
        assertTrue(h.live.feedback?.canRetry == true)
        assertFalse(h.live.isUpdating)
        assertNotNull(h.pendingRetry)
    }

    @Test
    fun `session inactive writes session expired feedback`() = runTest(dispatcher) {
        val h = harness(sessionActive = { false })

        h.controller.redistributeSenderKey()
        awaitQuiescence()

        assertEquals("[SESSION_EXPIRED]", h.live.message)
        assertNotNull(h.live.feedback)
        assertFalse(h.live.isUpdating)
    }

    @Test
    fun `auto redistribute with full coverage updates status`() = runTest(dispatcher) {
        val h = harness()

        h.controller.maybeAutoRedistributeSenderKey(h.live)
        awaitQuiescence()

        assertEquals(status(), h.live.senderKeyStatus)
        assertTrue(h.live.localHasSenderKey == true)
    }
}
