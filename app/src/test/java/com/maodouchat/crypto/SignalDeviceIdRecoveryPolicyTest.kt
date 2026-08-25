package com.maodouchat.crypto

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalDeviceIdRecoveryPolicyTest {

    @Test
    fun mappedApiErrorKeepsDeviceConflictCode() {
        val mapped = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            )
        )

        assertEquals(SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE, mapped.serverCode)
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetry(mapped, attempt = 0, maxAttempts = 4))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(mapped, attempt = 3, maxAttempts = 4))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(mapped))
    }

    @Test
    fun restoredIdentityNeverMovesToAnotherDeviceId() {
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(identityRestoredFromStore = true))
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(identityRestoredFromStore = false))
    }

    @Test
    fun identityMismatchInvalidatesLocalCryptoAndIsNotRetriedAsDeviceCollision() {
        val mapped = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_IDENTITY_MISMATCH_CODE,
            )
        )

        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(mapped))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(mapped, attempt = 0, maxAttempts = 4))
        assertTrue(
            SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(
                mapped,
                identityRestoredFromStore = true,
                attempt = 0,
                maxAttempts = 4,
            )
        )
        assertTrue(
            SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(
                mapped,
                identityRestoredFromStore = false,
                attempt = 0,
                maxAttempts = 4,
            )
        )
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(true, mapped))
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, mapped))
    }

    @Test
    fun migrationFailureMarkerInvalidatesLocalCryptoEvenWhenFollowUpIsSessionConflict() {
        val conflict = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_SESSION_CONFLICT_CODE,
            )
        )

        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(conflict))
        assertTrue(
            SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(
                SignalDeviceIdRecoveryPolicy.IdentityRecoveryFailedException(conflict)
            )
        )
    }

    @Test
    fun candidateMustBeUnusedAndDifferentFromPreviousId() {
        assertEquals(
            9,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 9,
                previousDeviceId = 7,
                occupiedDeviceIds = setOf(2, 3),
                attemptedDeviceIds = setOf(4),
            )
        )
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 7,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
            )
        )
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 9,
                previousDeviceId = 7,
                occupiedDeviceIds = setOf(9),
            )
        )
    }

    @Test
    fun ordinaryDeviceConflictKeepsRestoredIdentityPinned() {
        val mapped = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            )
        )

        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(true, mapped))
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, mapped))
    }

    @Test
    fun migrationOnlyRetriesAnotherOccupiedCandidateAndStopsOnSessionConflict() {
        val collision = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            )
        )
        val sessionConflict = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_SESSION_CONFLICT_CODE,
            )
        )

        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(collision, 1, 4))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(sessionConflict, 1, 4))
    }

    @Test
    fun pendingApprovalIsDistinctFromConfirmedAndDoesNotInvalidateLocalStore() {
        assertTrue(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(" pending "))
        assertTrue(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("confirmed"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("PENDING"))
        assertFalse(
            SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(
                SignalDeviceIdRecoveryPolicy.DevicePendingApprovalException(9),
            ),
        )
    }

    @Test
    fun unavailableStatusIsTransientAndDoesNotPretendToBePending() {
        val error = SignalDeviceIdRecoveryPolicy.DeviceStatusUnavailableException()
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(null))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(error))
    }

    @Test
    fun legacyBundleFallbackRequiresExplicitHttp404() {
        assertTrue(
            SignalKeyExchange.shouldUseLegacyBundleEndpoint(
                ApiException(ApiFailureKind.HTTP, statusCode = 404)
            )
        )
        assertFalse(
            SignalKeyExchange.shouldUseLegacyBundleEndpoint(
                ApiException(ApiFailureKind.HTTP, statusCode = 401)
            )
        )
        assertFalse(
            SignalKeyExchange.shouldUseLegacyBundleEndpoint(
                ApiException(ApiFailureKind.NETWORK)
            )
        )
        assertFalse(
            SignalKeyExchange.shouldUseLegacyBundleEndpoint(
                ApiException(ApiFailureKind.TIMEOUT)
            )
        )
    }

    @Test
    fun migrationMarkerIsFailClosedAcrossProcessRestore() {
        assertTrue(
            SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(
                SignalDeviceIdRecoveryPolicy.DEVICE_ID_MIGRATION_PENDING_MARKER,
            )
        )
        // A damaged row must not silently reactivate the old ratchet after a restart.
        assertTrue(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(""))
        assertFalse(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(null))
    }
}
