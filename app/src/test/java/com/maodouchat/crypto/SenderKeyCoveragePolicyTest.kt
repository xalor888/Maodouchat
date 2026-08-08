package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderKeyCoveragePolicyTest {
    @Test
    fun `local key without server coverage must be distributed`() {
        assertTrue(
            SenderKeyCoveragePolicy.requiresDistribution(
                hasLocalDistribution = false,
                requestedEpoch = 4,
                statusEpoch = 4,
                targetStatuses = emptyList()
            )
        )
        assertEquals(
            SenderKeyCoveragePolicy.Reason.LOCAL_MISSING,
            SenderKeyCoveragePolicy.assess(false, 4, 4, emptyList()).reason
        )
    }

    @Test
    fun `new pending device forces redistribution in same epoch`() {
        assertTrue(
            SenderKeyCoveragePolicy.requiresDistribution(
                hasLocalDistribution = true,
                requestedEpoch = 4,
                statusEpoch = 4,
                targetStatuses = listOf("SENT", "PENDING")
            )
        )
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "PENDING"))
        assertEquals(SenderKeyCoveragePolicy.Reason.PENDING_TARGETS, assessment.reason)
        assertEquals(1, assessment.pendingCount)
    }

    @Test
    fun `failed target and epoch mismatch force redistribution`() {
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("FAILED")))
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(true, 5, 4, listOf("SENT")))
        assertEquals(
            SenderKeyCoveragePolicy.Reason.FAILED_TARGETS,
            SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("FAILED", "SENT")).reason
        )
        assertEquals(
            SenderKeyCoveragePolicy.Reason.EPOCH_MISMATCH,
            SenderKeyCoveragePolicy.assess(true, 5, 4, listOf("SENT")).reason
        )
    }

    @Test
    fun `all expected devices covered can reuse current sender key`() {
        assertFalse(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("SENT", "sent")))
        assertFalse(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, emptyList()))
        assertEquals(
            SenderKeyCoveragePolicy.Reason.COMPLETE,
            SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "sent")).reason
        )
    }

    @Test
    fun `unknown status is not treated as covered`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 2, 2, listOf("SENT", "QUEUED"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(SenderKeyCoveragePolicy.Reason.UNKNOWN_TARGETS, assessment.reason)
        assertEquals(1, assessment.unknownCount)
    }

    @Test
    fun `explicit zero server record with no epoch needs distribution`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 0,
            statusEpoch = 0,
            targetStatuses = emptyList(),
            reportedTotal = 0
        )
        assertTrue(assessment.requiresDistribution)
        assertEquals(SenderKeyCoveragePolicy.Reason.NO_SERVER_RECORD, assessment.reason)
    }
}
