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
            Reason.LOCAL_MISSING,
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
        assertEquals(Reason.PENDING_TARGETS, assessment.reason)
        assertEquals(1, assessment.pendingCount)
    }

    @Test
    fun `failed target and epoch mismatch force redistribution`() {
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("FAILED")))
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(true, 5, 4, listOf("SENT")))
        assertEquals(
            Reason.FAILED_TARGETS,
            SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("FAILED", "SENT")).reason
        )
        assertEquals(
            Reason.EPOCH_MISMATCH,
            SenderKeyCoveragePolicy.assess(true, 5, 4, listOf("SENT")).reason
        )
    }

    @Test
    fun `all expected devices covered can reuse current sender key`() {
        assertFalse(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("SENT", "sent")))
        assertFalse(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, emptyList()))
        assertEquals(
            Reason.COMPLETE,
            SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "sent")).reason
        )
    }

    @Test
    fun `unknown status is not treated as covered`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 2, 2, listOf("SENT", "QUEUED"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.UNKNOWN_TARGETS, assessment.reason)
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
        assertEquals(Reason.NO_SERVER_RECORD, assessment.reason)
    }

    @Test
    fun `local missing outranks epoch mismatch and failed targets`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 5, 4, listOf("FAILED"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.EPOCH_MISMATCH, assessment.reason)
        val localMissing = SenderKeyCoveragePolicy.assess(false, 5, 4, listOf("FAILED"))
        assertTrue(localMissing.requiresDistribution)
        assertEquals(Reason.LOCAL_MISSING, localMissing.reason)
    }

    @Test
    fun `failed reason outranks pending and counts are case insensitive`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            true, 4, 4, listOf("failed", "Pending", "sent")
        )
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.FAILED_TARGETS, assessment.reason)
        assertEquals(1, assessment.failedCount)
        assertEquals(1, assessment.pendingCount)
        assertEquals(1, assessment.sentCount)
    }

    @Test
    fun `pending targets aggregate count`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            true, 4, 4, listOf("PENDING", "pending", "PENDING")
        )
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.PENDING_TARGETS, assessment.reason)
        assertEquals(3, assessment.pendingCount)
    }

    @Test
    fun `reported total larger than status list does not force unknown`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 4,
            statusEpoch = 4,
            targetStatuses = listOf("SENT", "SENT"),
            reportedTotal = 5
        )
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
        assertEquals(2, assessment.sentCount)
    }

    @Test
    fun `explicit zero total with nonzero epoch is complete not no record`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 4,
            statusEpoch = 4,
            targetStatuses = emptyList(),
            reportedTotal = 0
        )
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
    }
}
