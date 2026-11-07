package com.maodouchat.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCoordinatorsTest {

    @Test
    fun membershipStoreUpdateAndOnlineStatus() = runTest {
        val store = InMemoryGroupMembershipStore()
        assertNull(store.getSnapshot("c1"))
        store.updateMembers(
            "c1",
            listOf(GroupMemberUi("u1", "A"), GroupMemberUi("u2", "B")),
            revision = 3L,
        )
        val snap = store.getSnapshot("c1")
        assertEquals(3L, snap?.memberRevision)
        assertEquals(listOf("u1", "u2"), snap?.members?.map { it.userId })
        store.updateMemberOnlineStatus("c1", "u2", true)
        val members = store.getSnapshot("c1")?.members.orEmpty()
        assertTrue(members.first { it.userId == "u2" }.isOnline)
        assertFalse(members.first { it.userId == "u1" }.isOnline)
        store.invalidate("c1")
        assertNull(store.getSnapshot("c1"))
    }

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
    fun messagingCoordinatorBlankChatIsNoop() = runTest {
        var calls = 0
        val coordinator = messagingCoordinator { calls++ }
        coordinator.invalidateSenderKey("", "u1")
        assertEquals(0, calls)
        assertFalse(coordinator.hasLocalSenderKey("c1", 0L))
    }

    @Test
    fun messagingCoordinatorSessionMissingFailsCoverage() = runTest {
        val coordinator = messagingCoordinator(session = null)
        val result = coordinator.ensureSenderKeyCoverage("c1", 1L)
        assertTrue(result.isFailure)
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

    private fun messagingCoordinator(
        session: GroupMessagingSession? = GroupMessagingSession("u1", "t", 1),
        onInvalidate: () -> Unit = {},
    ) = GroupMessagingCoordinator(
        currentSession = { session },
        invalidatePreparedEpoch = { _, _, _, _ -> onInvalidate() },
        invalidateLocalSenderKey = { onInvalidate() },
        reconcileAttachments = { _, _ -> },
        ensureCoverageNow = { _, _ -> },
        redistributeCoverageNow = { false },
        fetchCoverageStatus = { _, _, _, _ -> SenderKeyDistributionStatusDto("c1", 1L) },
        hasLocalSenderKeyMaterial = { _, _ -> true },
        enqueueCoverageRetryCommand = { _, _, _ -> },
    )
}
