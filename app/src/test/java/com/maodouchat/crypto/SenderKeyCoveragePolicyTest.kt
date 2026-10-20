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

    @Test
    fun `blank status string counts as unknown not sent`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", ""))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.UNKNOWN_TARGETS, assessment.reason)
        assertEquals(1, assessment.unknownCount)
        assertEquals(1, assessment.sentCount)
    }

    @Test
    fun `status match is whitespace sensitive`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT "))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.UNKNOWN_TARGETS, assessment.reason)
        assertEquals(1, assessment.unknownCount)
    }

    @Test
    fun `pending outranks unknown in reason priority`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("PENDING", "QUEUED"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.PENDING_TARGETS, assessment.reason)
        assertEquals(1, assessment.pendingCount)
        assertEquals(1, assessment.unknownCount)
    }

    @Test
    fun `null reported total falls back to target list size`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 4,
            statusEpoch = 4,
            targetStatuses = listOf("SENT"),
            reportedTotal = null
        )
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
    }

    @Test
    fun `negative requested epoch still forces distribution`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, -1, 4, listOf("SENT"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.EPOCH_MISMATCH, assessment.reason)
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(true, -1, 4, listOf("SENT")))
    }

    @Test
    fun `reported total equal to sent list is complete`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 4,
            statusEpoch = 4,
            targetStatuses = listOf("SENT", "SENT", "SENT"),
            reportedTotal = 3
        )
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
        assertEquals(3, assessment.sentCount)
        assertFalse(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("SENT", "SENT", "SENT")))
    }

    @Test
    fun `local missing outranks no server record edge`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = false,
            requestedEpoch = 0,
            statusEpoch = 0,
            targetStatuses = emptyList(),
            reportedTotal = 0
        )
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.LOCAL_MISSING, assessment.reason)
    }

    @Test
    fun `epoch mismatch outranks unknown targets`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 5, 4, listOf("QUEUED"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.EPOCH_MISMATCH, assessment.reason)
    }

    @Test
    fun `repeated assessment of same input is stable`() {
        val first = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "FAILED"))
        val second = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "FAILED"))
        assertEquals(first, second)
        assertEquals(
            SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("SENT", "FAILED")),
            SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, listOf("SENT", "FAILED"))
        )
    }

    @Test
    fun `negative reported total with empty list is complete`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 4,
            statusEpoch = 4,
            targetStatuses = emptyList(),
            reportedTotal = -1
        )
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
    }

    @Test
    fun `epoch mismatch outranks failed targets`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 5, 4, listOf("FAILED", "PENDING"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.EPOCH_MISMATCH, assessment.reason)
    }

    @Test
    fun `reported total zero with non-empty list still completes`() {
        val assessment = SenderKeyCoveragePolicy.assess(
            hasLocalDistribution = true,
            requestedEpoch = 4,
            statusEpoch = 4,
            targetStatuses = listOf("SENT"),
            reportedTotal = 0
        )
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
    }

    @Test
    fun `unrecognized status variants all count as unknown`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("QUEUED", "DELIVERED", "ACKED"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.UNKNOWN_TARGETS, assessment.reason)
        assertEquals(3, assessment.unknownCount)
    }

    @Test
    fun `negative status epoch mismatches zero requested epoch`() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 0, -1, listOf("SENT"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.EPOCH_MISMATCH, assessment.reason)
    }

    @Test
    fun `convenience overload matches assess on mixed failed list`() {
        val statuses = listOf("SENT", "FAILED")
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, statuses))
        assertEquals(
            SenderKeyCoveragePolicy.assess(true, 4, 4, statuses).requiresDistribution,
            SenderKeyCoveragePolicy.requiresDistribution(true, 4, 4, statuses)
        )
    }
}
