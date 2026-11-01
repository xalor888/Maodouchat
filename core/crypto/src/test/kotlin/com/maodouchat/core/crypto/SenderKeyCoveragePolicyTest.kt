package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderKeyCoveragePolicyTest {

    @Test
    fun missingLocalDistributionRequiresRedistribution() {
        assertTrue(SenderKeyCoveragePolicy.requiresDistribution(false, 4, 4, emptyList()))
        assertEquals(
            Reason.LOCAL_MISSING,
            SenderKeyCoveragePolicy.assess(false, 4, 4, emptyList()).reason
        )
    }

    @Test
    fun epochMismatchRequiresRedistribution() {
        assertEquals(
            Reason.EPOCH_MISMATCH,
            SenderKeyCoveragePolicy.assess(true, 5, 4, listOf("SENT")).reason
        )
    }

    @Test
    fun allSentTargetsAreComplete() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "sent"))
        assertFalse(assessment.requiresDistribution)
        assertEquals(Reason.COMPLETE, assessment.reason)
        assertEquals(2, assessment.sentCount)
    }

    @Test
    fun pendingTargetsRequireRedistribution() {
        val assessment = SenderKeyCoveragePolicy.assess(true, 4, 4, listOf("SENT", "PENDING"))
        assertTrue(assessment.requiresDistribution)
        assertEquals(Reason.PENDING_TARGETS, assessment.reason)
    }
}
