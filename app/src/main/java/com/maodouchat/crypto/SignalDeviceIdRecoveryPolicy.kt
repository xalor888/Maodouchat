package com.maodouchat.crypto

/**
 * Decides whether a failed key upload is retryable with a newly allocated device id.
 *
 * The transport layer wraps [com.maodouchat.network.ApiException] as
 * [SignalExchangeException], so callers must inspect the preserved server code rather
 * than rely on the exception class alone.
 */
internal object SignalDeviceIdRecoveryPolicy {
    const val DEVICE_ID_CONFLICT_CODE = "DEVICE_ID_CONFLICT"
    const val DEVICE_IDENTITY_MISMATCH_CODE = "DEVICE_IDENTITY_MISMATCH"
    const val DEVICE_SESSION_CONFLICT_CODE = "DEVICE_SESSION_CONFLICT"
    const val DEVICE_ID_MIGRATION_PENDING_MARKER = "pending"
    const val DEVICE_STATUS_CONFIRMED = "CONFIRMED"
    const val DEVICE_STATUS_PENDING = "PENDING"

    /** The key package was accepted, but this device cannot receive fan-out until approval. */
    class DevicePendingApprovalException(val deviceId: Int) :
        Exception("signal_device_pending_approval:$deviceId")

    /** The server accepted the package but its authoritative device status could not be read. */
    class DeviceStatusUnavailableException(cause: Throwable? = null) :
        Exception("signal_device_status_unavailable", cause)

    fun isConfirmedDeviceStatus(status: String?): Boolean =
        status?.trim()?.equals(DEVICE_STATUS_CONFIRMED, ignoreCase = true) == true

    fun isPendingDeviceStatus(status: String?): Boolean =
        status?.trim()?.equals(DEVICE_STATUS_PENDING, ignoreCase = true) == true

    /** A present marker is fail-closed, including a damaged/blank value. */
    fun isMigrationPendingMarkerPresent(value: String?): Boolean = value != null

    /** Marks a failed migration so an ambiguous follow-up error cannot re-enable crypto. */
    class IdentityRecoveryFailedException(cause: Throwable?) :
        Exception("signal_identity_recovery_failed", cause)

    fun serverCode(error: Throwable?): String? {
        var current = error
        repeat(4) {
            when (current) {
                is SignalExchangeException -> return current.serverCode
                is com.maodouchat.network.ApiException -> return current.serverCode
            }
            current = current?.cause
        }
        return null
    }

    fun shouldRetry(error: Throwable?, attempt: Int, maxAttempts: Int): Boolean =
        attempt >= 0 && maxAttempts > 0 && attempt < maxAttempts - 1 &&
            serverCode(error) == DEVICE_ID_CONFLICT_CODE

    fun invalidatesLocalCrypto(error: Throwable?): Boolean =
        error is IdentityRecoveryFailedException ||
            serverCode(error) in setOf(DEVICE_IDENTITY_MISMATCH_CODE, DEVICE_SESSION_CONFLICT_CODE)

    /** A proven identity mismatch may move either a restored or freshly generated identity. */
    fun shouldReallocateForIdentityMismatch(
        error: Throwable?,
        identityRestoredFromStore: Boolean,
        attempt: Int,
        maxAttempts: Int,
    ): Boolean =
        mayReallocateDeviceId(identityRestoredFromStore, error) &&
            serverCode(error) == DEVICE_IDENTITY_MISMATCH_CODE &&
            attempt >= 0 &&
            maxAttempts > 0 &&
            attempt < maxAttempts - 1

    /** Once migration has started, another occupied candidate can be skipped safely. */
    fun shouldRetryMigrationCandidate(
        error: Throwable?,
        attempt: Int,
        maxAttempts: Int,
    ): Boolean =
        attempt >= 0 &&
            maxAttempts > 0 &&
            attempt < maxAttempts - 1 &&
            serverCode(error) in setOf(DEVICE_ID_CONFLICT_CODE, DEVICE_IDENTITY_MISMATCH_CODE)

    fun availableCandidate(
        candidate: Int,
        previousDeviceId: Int,
        occupiedDeviceIds: Set<Int>,
        attemptedDeviceIds: Set<Int> = emptySet(),
        minDeviceId: Int = 2,
        maxDeviceId: Int = 255,
    ): Int? = candidate.takeIf {
        it in minDeviceId..maxDeviceId &&
            it != previousDeviceId &&
            it !in occupiedDeviceIds &&
            it !in attemptedDeviceIds
    }

    /**
     * Device-ID collisions keep the historical restored-identity behavior. An explicit
     * identity mismatch is different: retaining the local identity on a fresh slot never
     * overwrites the server's existing key material, regardless of whether the pair was
     * restored or freshly generated.
     */
    fun mayReallocateDeviceId(identityRestoredFromStore: Boolean, error: Throwable?): Boolean =
        when (serverCode(error)) {
            DEVICE_IDENTITY_MISMATCH_CODE -> true
            DEVICE_ID_CONFLICT_CODE -> !identityRestoredFromStore
            else -> false
        }

    /** Plain device-ID collisions keep a restored identity pinned to its persisted slot. */
    fun mayReallocateDeviceId(identityRestoredFromStore: Boolean): Boolean =
        !identityRestoredFromStore
}
