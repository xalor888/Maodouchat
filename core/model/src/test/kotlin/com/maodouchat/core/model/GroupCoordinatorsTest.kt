package com.maodouchat.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCoordinatorsTest {

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
