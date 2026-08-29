package com.maodouchat.messaging.v2

import com.maodouchat.network.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupSenderKeyMaintenanceCoordinatorTest {
    @Test
    fun `single member zero-target coverage is complete when local key exists`() {
        assertTrue(
            GroupSenderKeyMaintenanceCoordinator.coverageComplete(
                status = status(epoch = 7, total = 0, sent = 0),
                expectedEpoch = 7,
                localHasSenderKey = true,
            ),
        )
    }

    @Test
    fun `complete single member group skips automatic redistribution`() = runTest {
        var ensured = 0
        val coordinator = coordinator(
            ensure = {
                ensured += 1
                Result.success(status())
            },
        )

        val outcome = coordinator.runAutomatic("group", 7, status(total = 0, sent = 0), true)

        assertIs<GroupSenderKeyMaintenanceOutcome.Skipped>(outcome)
        assertEquals(0, ensured)
    }

    @Test
    fun `zero target group mints local key once and becomes ready`() = runTest {
        var localKey = false
        var ensured = 0
        val coordinator = coordinator(
            ensure = {
                ensured += 1
                localKey = true
                Result.success(status(total = 0, sent = 0))
            },
            hasLocal = { localKey },
        )

        val outcome = coordinator.runAutomatic("group", 7, status(total = 0, sent = 0), false)

        assertIs<GroupSenderKeyMaintenanceOutcome.Ready>(outcome)
        assertTrue(outcome.localHasSenderKey)
        assertEquals(1, ensured)
    }

    @Test
    fun `incomplete automatic coverage queues retry and may run again for same epoch`() = runTest {
        var ensured = 0
        val retries = mutableListOf<String>()
        val coordinator = coordinator(
            ensure = {
                ensured += 1
                Result.success(status(total = 2, sent = 1, pending = 1))
            },
            retries = retries,
        )

        val first = coordinator.runAutomatic("group", 7, null, false)
        val second = coordinator.runAutomatic("group", 7, null, false)

        assertIs<GroupSenderKeyMaintenanceOutcome.Pending>(first)
        assertIs<GroupSenderKeyMaintenanceOutcome.Pending>(second)
        assertEquals(2, ensured)
        assertEquals(listOf("group:7:auto_incomplete", "group:7:auto_incomplete"), retries)
    }

    @Test
    fun `manual incomplete coverage is never reported as success`() = runTest {
        val retries = mutableListOf<String>()
        val coordinator = coordinator(
            redistribute = { Result.success(status(total = 3, sent = 2, failed = 1)) },
            retries = retries,
        )

        val outcome = coordinator.runManual("group", 7)

        assertIs<GroupSenderKeyMaintenanceOutcome.Pending>(outcome)
        assertEquals("sender_key_coverage_incomplete", outcome.error.message)
        assertEquals(listOf("group:7:manual_incomplete"), retries)
    }

    @Test
    fun `manual failure keeps primary error and queues durable retry`() = runTest {
        val offline = IllegalStateException("offline")
        val retries = mutableListOf<String>()
        val coordinator = coordinator(
            redistribute = { Result.failure(offline) },
            retries = retries,
        )

        val outcome = coordinator.runManual("group", 7)

        val failed = assertIs<GroupSenderKeyMaintenanceOutcome.Failed>(outcome)
        assertEquals(offline, failed.error)
        assertEquals(listOf("group:7:manual_failed:offline"), retries)
    }

    @Test
    fun `same epoch automatic maintenance is single flight only while running`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            ensure = {
                entered.complete(Unit)
                release.await()
                Result.success(status())
            },
        )

        val first = async { coordinator.runAutomatic("group", 7, null, false) }
        entered.await()
        val duplicate = coordinator.runAutomatic("group", 7, null, false)
        release.complete(Unit)

        assertIs<GroupSenderKeyMaintenanceOutcome.Skipped>(duplicate)
        assertIs<GroupSenderKeyMaintenanceOutcome.Ready>(first.await())
    }

    private fun coordinator(
        ensure: suspend () -> Result<SenderKeyDistributionStatusDto?> = { Result.success(status()) },
        redistribute: suspend () -> Result<SenderKeyDistributionStatusDto?> = { Result.success(status()) },
        hasLocal: suspend () -> Boolean = { true },
        retries: MutableList<String> = mutableListOf(),
    ) = GroupSenderKeyMaintenanceCoordinator(
        ensureCoverage = { _, _ -> ensure() },
        redistribute = { _, _ -> redistribute() },
        hasLocalSenderKey = { _, _ -> hasLocal() },
        enqueueRetry = { chat, epoch, reason -> retries += "$chat:$epoch:$reason" },
    )

    private companion object {
        fun status(
            epoch: Long = 7,
            total: Int = 2,
            sent: Int = 2,
            failed: Int = 0,
            pending: Int = 0,
        ) = SenderKeyDistributionStatusDto(
            chatId = "group",
            epoch = epoch,
            total = total,
            sent = sent,
            failed = failed,
            pending = pending,
        )
    }
}
