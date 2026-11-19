package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCoordinatorsTest {

    @Test
    fun lifecycleCoordinatorSessionMissingThrows() = runTest {
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "u1" },
            token = { "t" },
            sessionActive = { false },
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, _, _ -> },
        )
        var thrown: Throwable? = null
        try {
            coordinator.mutate("c1") {}
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is GroupLifecycleSessionException)
    }
    @Test
    fun lifecycleCoordinatorSuccessReturnsCommit() = runTest {
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "u1" },
            token = { "t" },
            sessionActive = { true },
            fetchChat = { _, _ -> Result.success(ChatDto(id = "c1", memberRevision = 7L)) },
            invalidateEpoch = { _, _, _ -> },
        )
        val commit = coordinator.mutate("c1", rotateSenderKey = true) {}
        assertEquals("c1", commit.refreshedChat?.id)
        assertEquals(7L, commit.refreshedChat?.memberRevision)
        assertNull(commit.refreshError)
        assertNull(commit.reconciliationError)
    }
    @Test
    fun senderKeyMaintenanceBlankAndCoverageComplete() = runTest {
        val coordinator = GroupSenderKeyMaintenanceCoordinator(
            ensureCoverage = { _, _ -> Result.success(null) },
            redistribute = { _, _ -> Result.success(null) },
            hasLocalSenderKey = { _, _ -> true },
            enqueueRetry = { _, _, _ -> },
        )
        assertEquals(
            GroupSenderKeyMaintenanceOutcome.Skipped,
            coordinator.runAutomatic("", 1L, null, null),
        )
        assertEquals(
            GroupSenderKeyMaintenanceOutcome.Skipped,
            coordinator.runAutomatic("c1", 0L, null, null),
        )
        val complete = SenderKeyDistributionStatusDto("c1", 2L, total = 2, sent = 2)
        assertTrue(GroupSenderKeyMaintenanceCoordinator.coverageComplete(complete, 2L, true))
        val pending = SenderKeyDistributionStatusDto("c1", 2L, total = 2, sent = 1, pending = 1)
        assertFalse(GroupSenderKeyMaintenanceCoordinator.coverageComplete(pending, 2L, true))
    }
}
