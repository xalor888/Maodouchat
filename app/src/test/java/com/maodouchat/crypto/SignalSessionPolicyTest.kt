package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalSessionPolicyTest {

    @Test
    fun migrationMarkerForcesReestablishmentEvenWhenSessionExists() {
        assertTrue(
            SignalSessionPolicy.shouldEnsureSession(
                hasSession = true,
                requiresReestablishment = true,
            )
        )
    }

    @Test
    fun missingSessionRequiresSetupWithoutMigrationMarker() {
        assertTrue(
            SignalSessionPolicy.shouldEnsureSession(
                hasSession = false,
                requiresReestablishment = false,
            )
        )
        assertFalse(
            SignalSessionPolicy.shouldEnsureSession(
                hasSession = true,
                requiresReestablishment = false,
            )
        )
    }

    @Test
    fun skipsCurrentDeviceSelfFetch() {
        assertFalse(
            SignalSessionPolicy.shouldEstablishSession(
                recipientId = "me",
                deviceId = 2,
                currentUserId = "me",
                localDeviceId = 2,
            )
        )
    }

    @Test
    fun allowsOtherOwnDeviceAndPeers() {
        assertTrue(
            SignalSessionPolicy.shouldEstablishSession("me", 3, "me", 2)
        )
        assertTrue(
            SignalSessionPolicy.shouldEstablishSession("peer", 2, "me", 2)
        )
        assertTrue(
            SignalSessionPolicy.shouldEstablishSession("peer", 1, null, 1)
        )
    }

    @Test
    fun rejectsBlankRecipientOrInvalidDevice() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("", 1, "me", 1))
        assertFalse(SignalSessionPolicy.shouldEstablishSession("peer", 0, "me", 1))
        assertFalse(SignalSessionPolicy.shouldEstablishSession("peer", -1, "me", 1))
    }

    @Test
    fun keepsDurableSessionsWhenDiscoveryIsUnavailable() {
        assertEquals(
            listOf(2, 4),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = null,
                persistedSessionDeviceIds = listOf(4, 2, 0, 300),
            ),
        )
    }

    @Test
    fun discoveryRemainsAuthoritativeWhenItSucceeds() {
        assertEquals(
            listOf(1, 3),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(3, 1),
                persistedSessionDeviceIds = listOf(2),
            ),
        )
    }

    @Test
    fun noDiscoveryAndNoDurableSessionHasNoCandidates() {
        assertTrue(
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = emptyList(),
                persistedSessionDeviceIds = emptyList(),
            ).isEmpty()
        )
    }

    @Test
    fun emptyDiscoveryKeepsDurableSessions() {
        assertEquals(
            listOf(2),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = emptyList(),
                persistedSessionDeviceIds = listOf(2),
            ),
        )
    }

    @Test
    fun outOfRangeDiscoveredIdsFallBackToPersisted() {
        assertEquals(
            listOf(3),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(0, 256),
                persistedSessionDeviceIds = listOf(3),
            ),
        )
    }

    @Test
    fun discoveredIdsAreDedupedSortedAndTrimmed() {
        assertEquals(
            listOf(2, 3, 5),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(5, 3, 5, 2, 300),
                persistedSessionDeviceIds = listOf(9),
            ),
        )
    }

    @Test
    fun blankCurrentUserIdSkipsSelfCheck() {
        assertTrue(SignalSessionPolicy.shouldEstablishSession("me", 2, "", 2))
        assertTrue(SignalSessionPolicy.shouldEstablishSession("me", 2, "  ", 2))
    }

    @Test
    fun migrationMarkerAndMissingSessionBothRequireSetup() {
        assertTrue(
            SignalSessionPolicy.shouldEnsureSession(
                hasSession = false,
                requiresReestablishment = true,
            )
        )
    }

    @Test
    fun nullDiscoveryFallsBackToPersistedSessionsSorted() {
        assertEquals(
            listOf(1, 3),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = null,
                persistedSessionDeviceIds = listOf(3, 1),
            ),
        )
    }

    @Test
    fun emptyDiscoveryAndOutOfRangePersistedYieldsNoCandidates() {
        assertTrue(
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = emptyList(),
                persistedSessionDeviceIds = listOf(0, 256, -1),
            ).isEmpty()
        )
    }

    @Test
    fun peerMayReuseLocalDeviceSlot() {
        assertTrue(SignalSessionPolicy.shouldEstablishSession("peer", 2, "me", 2))
    }

    @Test
    fun blankRecipientRejectedEvenWithValidDevice() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("", 5, "me", 1))
        assertFalse(SignalSessionPolicy.shouldEstablishSession("   ", 5, null, 1))
    }

    @Test
    fun selfCheckAppliesOnlyAfterRecipientAndDeviceValidation() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("me", 0, "me", 1))
    }

    @Test
    fun persistedDuplicatesAreDedupedSortedAndTrimmed() {
        assertEquals(
            listOf(3, 7),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = null,
                persistedSessionDeviceIds = listOf(7, 7, 3, 0, 256, 3),
            ),
        )
    }

    @Test
    fun nullDiscoveryWithOnlyOutOfRangePersistedYieldsEmpty() {
        assertTrue(
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = null,
                persistedSessionDeviceIds = listOf(0, 256, -5),
            ).isEmpty()
        )
    }

    @Test
    fun recipientCaseDiffersFromSelfIsNotSelf() {
        assertTrue(SignalSessionPolicy.shouldEstablishSession("ME", 2, "me", 2))
    }

    @Test
    fun allDuplicateDiscoveredIdsCollapseToOne() {
        assertEquals(
            listOf(4),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(4, 4, 4),
                persistedSessionDeviceIds = listOf(9),
            ),
        )
    }

    @Test
    fun boundaryDeviceId255IsValid() {
        assertEquals(
            listOf(255),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(255),
                persistedSessionDeviceIds = emptyList(),
            ),
        )
        assertTrue(SignalSessionPolicy.shouldEstablishSession("peer", 255, "me", 1))
    }

    @Test
    fun smallerDiscoveredSetRemainsAuthoritative() {
        assertEquals(
            listOf(5),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(5),
                persistedSessionDeviceIds = listOf(1, 2, 3),
            ),
        )
    }

    @Test
    fun discoveryReplacesPersistedRatherThanMerging() {
        assertEquals(
            listOf(3, 4),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(4, 3),
                persistedSessionDeviceIds = listOf(3, 9),
            ),
        )
    }

    @Test
    fun boundaryDeviceIdOneIsValid() {
        assertEquals(
            listOf(1),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(1),
                persistedSessionDeviceIds = emptyList(),
            ),
        )
        assertTrue(SignalSessionPolicy.shouldEstablishSession("peer", 1, "me", 1))
    }

    @Test
    fun allOutOfRangeDiscoveryWithEmptyPersistedYieldsEmpty() {
        assertTrue(
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(0, 256, -3),
                persistedSessionDeviceIds = emptyList(),
            ).isEmpty()
        )
    }

    @Test
    fun whitespaceOnlyRecipientRejectedBeforeSelfCheckSkip() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("  ", 2, "  ", 2))
    }

    @Test
    fun sessionPresentWithoutMarkerNeedsNothing() {
        assertFalse(
            SignalSessionPolicy.shouldEnsureSession(
                hasSession = true,
                requiresReestablishment = false,
            )
        )
    }

    @Test
    fun whitespaceCurrentUserIdSkipsSelfCheck() {
        // 纯空白 currentUserId 视同缺失：self 判定跳过，直接放行建会话。
        assertTrue(SignalSessionPolicy.shouldEstablishSession("u1", 1, "  ", 1))
        assertTrue(SignalSessionPolicy.shouldEstablishSession("u1", 1, "\t", 1))
    }

    @Test
    fun negativeDeviceIdNeverEstablishesSession() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("u2", -1, "u1", 1))
        assertFalse(SignalSessionPolicy.shouldEstablishSession("u2", Int.MIN_VALUE, "u1", 1))
    }

    @Test
    fun singleDiscoveredIdIsAuthoritativeOverLargerPersistedSet() {
        assertEquals(
            listOf(7),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = listOf(7),
                persistedSessionDeviceIds = listOf(3, 7, 9),
            )
        )
    }

    @Test
    fun negativePersistedIdsAreDroppedBeforeSort() {
        assertEquals(
            listOf(2, 9),
            SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = null,
                persistedSessionDeviceIds = listOf(9, -1, 2, 0),
            )
        )
    }
}
