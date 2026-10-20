package com.maodouchat.crypto

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
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
        val selfOnly = listOf(Target("self", 2))
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
                    Target("self", 2),
                    Target("peer", 7),
                ),
            )
        )
    }

    @Test
    fun oneOfTwoPeerDevicesDoesNotSatisfyConcreteCoverage() {
        val required = listOf(
            Target("peer", 7),
            Target("peer", 8),
        )
        assertFalse(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = required,
                targets = listOf(Target("peer", 7)),
            )
        )
        assertEquals(
            setOf(Target("peer", 8)),
            MultiRecipientCoveragePolicy.missingRequiredTargets(
                requiredTargets = required,
                targets = listOf(Target("peer", 7)),
            )
        )
    }

    @Test
    fun transientFailureForOneMissingDeviceIsPreserved() {
        val missing = Target("peer", 8)
        val transient = SignalExchangeException(SignalExchangeFailure.TIMEOUT)
        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingTargets(
            requiredTargets = listOf(
                Target("peer", 7),
                missing,
            ),
            targets = listOf(Target("peer", 7)),
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
        val selfOnly = listOf(Target("self", 2))

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
                targets = listOf(Target("peer", 7)),
                failuresByRecipient = mapOf(
                    "peer" to SignalExchangeException(SignalExchangeFailure.TIMEOUT)
                ),
            )
        )
    }

    @Test
    fun missingRequiredRecipientsListsOnlyUncovered() {
        assertEquals(
            setOf("b"),
            MultiRecipientCoveragePolicy.missingRequiredRecipients(
                requiredRecipientIds = setOf("a", "b"),
                targets = listOf(Target("a", 1)),
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.missingRequiredRecipients(
                requiredRecipientIds = setOf("a"),
                targets = listOf(Target("a", 1)),
            ).isEmpty()
        )
    }

    @Test
    fun blankUserIdTargetAndZeroDeviceIdDoNotCountAsCoverage() {
        assertFalse(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = setOf("peer"),
                targets = listOf(Target("   ", 7)),
            )
        )
        assertFalse(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = listOf(Target("peer", 7)),
                targets = listOf(Target("peer", 0)),
            )
        )
    }

    @Test
    fun whitespacePaddedRequiredIdMatchesTrimmedTarget() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = setOf("  peer "),
                targets = listOf(Target("peer", 7)),
            )
        )
    }

    @Test
    fun apiExceptionNetworkAndRetryableHttpAreTransient() {
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                ApiException(ApiFailureKind.NETWORK)
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                ApiException(ApiFailureKind.TIMEOUT)
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                ApiException(ApiFailureKind.HTTP, statusCode = 503)
            )
        )
        assertFalse(
            MultiRecipientCoveragePolicy.isTransient(
                ApiException(ApiFailureKind.HTTP, statusCode = 404)
            )
        )
        assertFalse(
            MultiRecipientCoveragePolicy.isTransient(
                ApiException(ApiFailureKind.UNEXPECTED)
            )
        )
    }

    @Test
    fun signalExchangeExceptionDoesNotUnwrapNestedTransientCause() {
        assertFalse(
            MultiRecipientCoveragePolicy.isTransient(
                SignalExchangeException(
                    SignalExchangeFailure.UNEXPECTED,
                    cause = java.io.IOException("offline"),
                )
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                RuntimeException(java.net.SocketTimeoutException("t"))
            )
        )
    }

    @Test
    fun tabAndNewlineOnlyRecipientIdNormalizesToNull() {
        assertNull(MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("\t \n"))
    }

    @Test
    fun duplicateConcreteTargetsCollapseToOne() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = listOf(Target("peer", 7), Target("peer", 7)),
                targets = listOf(Target("peer", 7)),
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.missingRequiredTargets(
                requiredTargets = listOf(Target("peer", 7), Target("peer", 7)),
                targets = listOf(Target("peer", 7)),
            ).isEmpty()
        )
    }

    @Test
    fun failureKeysNormalizeLikeCoverageTargets() {
        val transient = SignalExchangeException(SignalExchangeFailure.TIMEOUT)
        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingTargets(
            requiredTargets = listOf(Target("peer", 8)),
            targets = emptyList(),
            failuresByTarget = mapOf(Target(" peer ", 8) to transient),
        )
        assertTrue(wrapped is TransientCoverageException)
        assertSame(transient, wrapped?.cause)
    }

    @Test
    fun nullErrorIsNotTransientButWrappedIoCauseIs() {
        assertFalse(MultiRecipientCoveragePolicy.isTransient(null))
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                RuntimeException(java.io.IOException("offline"))
            )
        )
    }

    @Test
    fun httpStatusBoundaryAndNullStatusAreNotTransient() {
        assertFalse(
            MultiRecipientCoveragePolicy.isTransient(
                SignalExchangeException(SignalExchangeFailure.HTTP, statusCode = 600)
            )
        )
        assertFalse(
            MultiRecipientCoveragePolicy.isTransient(
                SignalExchangeException(SignalExchangeFailure.HTTP, statusCode = null)
            )
        )
    }

    @Test
    fun paddedAndDuplicateRequiredTargetsStillCovered() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = listOf(
                    Target(" peer ", 7),
                    Target("peer", 8),
                    Target("peer", 7),
                ),
                targets = listOf(
                    Target("peer", 8),
                    Target("peer", 7),
                ),
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.missingRequiredTargets(
                requiredTargets = listOf(Target(" peer ", 7), Target("peer", 7)),
                targets = listOf(Target("peer", 7)),
            ).isEmpty()
        )
    }

    @Test
    fun nonTransientHttpForMissingDeviceYieldsNull() {
        assertNull(
            MultiRecipientCoveragePolicy.transientFailureForMissingTargets(
                requiredTargets = listOf(Target("peer", 8)),
                targets = emptyList(),
                failuresByTarget = mapOf(
                    Target("peer", 8) to SignalExchangeException(
                        SignalExchangeFailure.HTTP,
                        statusCode = 404,
                    )
                ),
            )
        )
    }

    @Test
    fun onlyTransientSurfacesAmongSeveralMissingRecipients() {
        val timeout = SignalExchangeException(SignalExchangeFailure.TIMEOUT)
        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
            requiredRecipientIds = setOf("a", "b"),
            targets = emptyList(),
            failuresByRecipient = mapOf(
                "a" to SignalExchangeException(
                    SignalExchangeFailure.HTTP,
                    statusCode = 404,
                ),
                "b" to timeout,
            ),
        )
        assertTrue(wrapped is TransientCoverageException)
        assertSame(timeout, wrapped?.cause)
    }

    @Test
    fun blankRequiredRecipientIdsAreIgnored() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = setOf(" ", "", "peer"),
                targets = listOf(Target("peer", 7)),
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.missingRequiredRecipients(
                requiredRecipientIds = setOf(" ", "peer"),
                targets = listOf(Target("peer", 7)),
            ).isEmpty()
        )
    }

    @Test
    fun emptyRequiredConcreteTargetsAreAlwaysCovered() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = emptyList(),
                targets = emptyList(),
            )
        )
        assertTrue(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = emptyList(),
                targets = listOf(Target("peer", 7)),
            )
        )
    }

    @Test
    fun zeroDeviceIdStillCountsForUserLevelCoverage() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = setOf("peer"),
                targets = listOf(Target("peer", 0)),
            )
        )
    }

    @Test
    fun paddedFailureKeysMatchTrimmedRecipientIds() {
        val transient = SignalExchangeException(SignalExchangeFailure.TIMEOUT)
        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
            requiredRecipientIds = setOf("peer"),
            targets = emptyList(),
            failuresByRecipient = mapOf("  peer  " to transient),
        )
        assertTrue(wrapped is TransientCoverageException)
        assertSame(transient, wrapped?.cause)
    }

    @Test
    fun firstTransientInRequiredOrderSurfaces() {
        val first = SignalExchangeException(SignalExchangeFailure.NETWORK)
        val second = SignalExchangeException(SignalExchangeFailure.TIMEOUT)
        val wrapped = MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
            requiredRecipientIds = setOf("a", "b"),
            targets = emptyList(),
            failuresByRecipient = mapOf("a" to first, "b" to second),
        )
        assertTrue(wrapped is TransientCoverageException)
        assertSame(first, wrapped?.cause)
    }

    @Test
    fun signalExchangeWrappedInRuntimeExceptionStillTransient() {
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                RuntimeException(SignalExchangeException(SignalExchangeFailure.NETWORK))
            )
        )
    }

    @Test
    fun coveredDeviceNeverSurfacesItsTransientFailure() {
        assertNull(
            MultiRecipientCoveragePolicy.transientFailureForMissingTargets(
                requiredTargets = listOf(Target("peer", 7)),
                targets = listOf(Target("peer", 7)),
                failuresByTarget = mapOf(
                    Target("peer", 7) to SignalExchangeException(SignalExchangeFailure.TIMEOUT)
                ),
            )
        )
    }

    @Test
    fun normalizeRequiredRecipientIdTrimsFullWidthSpaces() {
        // trim() 按 Char.isWhitespace 裁剪，全角空格同样去掉
        assertEquals(
            "peer",
            MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("\u3000peer\u3000"),
        )
    }

    @Test
    fun isTransientSeesThroughTwoLayersOfWrapping() {
        assertTrue(
            MultiRecipientCoveragePolicy.isTransient(
                RuntimeException(RuntimeException(java.io.IOException("offline")))
            )
        )
    }

    @Test
    fun zeroDeviceIdRequiredTargetIsDroppedFromRequiredSet() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = listOf(Target("peer", 0)),
                targets = emptyList(),
            )
        )
    }

    @Test
    fun blankUserIdRequiredTargetIsDroppedFromRequiredSet() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredTargetsCovered(
                requiredTargets = listOf(Target("   ", 7)),
                targets = emptyList(),
            )
        )
    }

    @Test
    fun duplicatePaddedRequiredRecipientIdsCollapse() {
        assertTrue(
            MultiRecipientCoveragePolicy.requiredRecipientsCovered(
                requiredRecipientIds = listOf("peer", " peer ", "peer"),
                targets = listOf(Target("peer", 2)),
            )
        )
    }
}
