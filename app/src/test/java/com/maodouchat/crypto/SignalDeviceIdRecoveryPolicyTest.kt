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
                IdentityRecoveryFailedException(conflict)
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
                DevicePendingApprovalException(9),
            ),
        )
    }

    @Test
    fun unavailableStatusIsTransientAndDoesNotPretendToBePending() {
        val error = DeviceStatusUnavailableException()
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

    @Test
    fun serverCodeUnwrapsWrappedTransportErrorsAndStopsAtPlainFailures() {
        val api = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            )
        )
        val wrapped = SignalExchangeException(
            failure = SignalExchangeFailure.HTTP,
            serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_IDENTITY_MISMATCH_CODE,
            cause = api,
        )

        assertEquals(
            SignalDeviceIdRecoveryPolicy.DEVICE_IDENTITY_MISMATCH_CODE,
            SignalDeviceIdRecoveryPolicy.serverCode(wrapped),
        )
        assertEquals(null, SignalDeviceIdRecoveryPolicy.serverCode(null))
        assertEquals(null, SignalDeviceIdRecoveryPolicy.serverCode(RuntimeException("boom")))
    }

    @Test
    fun retryBudgetBoundsRejectOutOfRangeAttemptsAndExhaustedCounters() {
        val conflict = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            )
        )

        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, attempt = -1, maxAttempts = 4))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, attempt = 0, maxAttempts = 0))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, attempt = 0, maxAttempts = 1))

        val transient = SignalKeyExchange.mapFailure(ApiException(ApiFailureKind.TIMEOUT))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(transient, attempt = 0, maxAttempts = 4))
    }

    @Test
    fun availableCandidateRejectsAttemptedIdsAndEnforcesRangeBounds() {
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 9,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
                attemptedDeviceIds = setOf(9),
            ),
        )
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 1,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
            ),
        )
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 256,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
            ),
        )
        assertEquals(
            255,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 255,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
            ),
        )
    }

    @Test
    fun identityMismatchReallocationStopsWhenAttemptBudgetIsExhausted() {
        val mapped = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_IDENTITY_MISMATCH_CODE,
            )
        )

        assertFalse(
            SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(
                mapped, true, attempt = 3, maxAttempts = 4,
            )
        )
        assertFalse(
            SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(
                mapped, false, attempt = -1, maxAttempts = 4,
            )
        )
        assertFalse(
            SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(
                mapped, true, attempt = 0, maxAttempts = 1,
            )
        )
    }

    @Test
    fun unknownDeviceStatusIsNeitherConfirmedNorPending() {
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("unknown"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("unknown"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(""))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(""))
    }

    @Test
    fun shouldRetryMigrationCandidateAcceptsIdentityMismatchToo() {
        val mismatch = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_IDENTITY_MISMATCH_CODE,
            )
        )

        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(mismatch, 0, 4))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(mismatch, 3, 4))
        // Identity mismatch is a different code from ordinary conflict: plain retry stays off.
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(mismatch, 0, 4))
    }

    @Test
    fun availableCandidateHonorsCustomRangeBounds() {
        assertEquals(
            1,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 1,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
                minDeviceId = 1,
            ),
        )
        assertEquals(
            300,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 300,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
                maxDeviceId = 300,
            ),
        )
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 0,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
                minDeviceId = 1,
            ),
        )
        assertEquals(
            null,
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = 301,
                previousDeviceId = 7,
                occupiedDeviceIds = emptySet(),
                maxDeviceId = 300,
            ),
        )
    }

    @Test
    fun sessionConflictNeverReallocatesDeviceId() {
        val conflict = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_SESSION_CONFLICT_CODE,
            )
        )

        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(true, conflict))
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, conflict))
    }

    @Test
    fun serverCodePrefersOutermostWrapperCode() {
        val inner = SignalKeyExchange.mapFailure(
            ApiException(
                kind = ApiFailureKind.HTTP,
                statusCode = 409,
                serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_IDENTITY_MISMATCH_CODE,
            )
        )
        val outer = SignalExchangeException(
            failure = SignalExchangeFailure.HTTP,
            serverCode = SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            cause = inner,
        )

        assertEquals(
            SignalDeviceIdRecoveryPolicy.DEVICE_ID_CONFLICT_CODE,
            SignalDeviceIdRecoveryPolicy.serverCode(outer),
        )
        // The outermost wrapper's code decides: an outer conflict is retried even over a mismatch.
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetry(outer, 0, 4))
    }

    @Test
    fun confirmedStatusTrimsAndIgnoresCaseButPendingIsDistinct() {
        assertTrue(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(" Confirmed "))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("confirmed"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("pending"))
    }
}
