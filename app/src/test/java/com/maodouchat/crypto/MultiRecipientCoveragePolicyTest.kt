package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRecipientCoveragePolicyTest {

    @Test
    fun requiredPeerIsTrimmedAndBlankPeerIsRejected() {
        assertEquals("peer", MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("  peer  "))
        assertNull(MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("   "))
    }

    @Test
    fun directPeerCannotBeHiddenByOwnDeviceTarget() {
        val selfOnly = listOf(MultiRecipientCoveragePolicy.Target("self", 2))
        assertFalse(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = setOf("peer"),
                targets = selfOnly,
            )
        )
    }

    @Test
    fun peerTargetSatisfiesRequiredCoverage() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = setOf("peer"),
                targets = listOf(
                    MultiRecipientCoveragePolicy.Target("self", 2),
                    MultiRecipientCoveragePolicy.Target("peer", 7),
                ),
            )
        )
    }

    @Test
    fun oneOfTwoPeerDevicesDoesNotSatisfyConcreteCoverage() {
        val required = listOf(
            MultiRecipientCoveragePolicy.Target("peer", 7),
            MultiRecipientCoveragePolicy.Target("peer", 8),
        )
        assertFalse(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = required,
                targets = listOf(MultiRecipientCoveragePolicy.Target("peer", 7)),
            )
        )
        assertEquals(
            setOf(MultiRecipientCoveragePolicy.Target("peer", 8)),
            MultiRecipientCoveragePolicy.missingRequiredTargets(
                requiredTargets = required,
                targets = listOf(MultiRecipientCoveragePolicy.Target("peer", 7)),
            )
        )
    }

    @Test
    fun transientFailureForOneMissingDeviceIsPreserved() {
        val missing = MultiRecipientCoveragePolicy.Target("peer", 8)
        val transient = SignalExchangeException(SignalExchangeFailure.TIMEOUT)
        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingTargets(
            requiredTargets = listOf(
                MultiRecipientCoveragePolicy.Target("peer", 7),
                missing,
            ),
            targets = listOf(MultiRecipientCoveragePolicy.Target("peer", 7)),
            failuresByTarget = mapOf(missing to transient),
        )
        assertTrue(wrapped is TransientCoverageException)
        assertSame(transient, wrapped?.cause)
    }

    @Test
    fun emptyRequirementsKeepGroupFanoutBestEffort() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = emptySet(),
                targets = emptyList(),
            )
        )
    }

    @Test
    fun networkTimeoutAndRetryableHttpFailuresRemainTransient() {
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                SignalExchangeException(SignalExchangeFailure.NETWORK)
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                SignalExchangeException(SignalExchangeFailure.TIMEOUT)
            )
        )
        assertTrue(MultiRecipientCoveragePolicy.isTransient(java.io.IOException("offline")))
        listOf(408, 429, 500, 503, 599).forEach { status ->
            assertTrue(
                MultiRecipientCoveragePolicy.isTransient(
                    SignalExchangeException(SignalExchangeFailure.HTTP, statusCode = status)
                )
            )
        }
        listOf(400, 401, 404).forEach { status ->
            assertFalse(
                MultiRecipientCoveragePolicy.isTransient(
                    SignalExchangeException(SignalExchangeFailure.HTTP, statusCode = status)
                )
            )
        }
    }

    @Test
    fun uncoveredRequiredPeerPreservesItsTransientFailure() {
        val transient = SignalExchangeException(SignalExchangeFailure.NETWORK)
        val selfOnly = listOf(MultiRecipientCoveragePolicy.Target("self", 2))

        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
            requiredRecipientIds = setOf("peer"),
            targets = selfOnly,
            failuresByRecipient = mapOf("peer" to transient),
        )
        assertTrue(wrapped is TransientCoverageException)
        assertSame(transient, wrapped?.cause)
        val ioFailure = java.io.IOException("offline")
        val wrappedIo = MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
            requiredRecipientIds = setOf("peer"),
            targets = selfOnly,
            failuresByRecipient = mapOf("peer" to ioFailure),
        )
        assertTrue(wrappedIo is TransientCoverageException)
        assertSame(ioFailure, wrappedIo?.cause)
        assertNull(
            MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
                requiredRecipientIds = setOf("peer"),
                targets = selfOnly,
                failuresByRecipient = mapOf(
                    "peer" to SignalExchangeException(
                        SignalExchangeFailure.HTTP,
                        statusCode = 404,
                    )
                ),
            )
        )
    }

    @Test
    fun coveredPeerDoesNotSurfaceAnEarlierTransientFailure() {
        assertNull(
            MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
                requiredRecipientIds = setOf("peer"),
                targets = listOf(MultiRecipientCoveragePolicy.Target("peer", 7)),
                failuresByRecipient = mapOf(
                    "peer" to SignalExchangeException(SignalExchangeFailure.TIMEOUT)
                ),
            )
        )
    }
}
