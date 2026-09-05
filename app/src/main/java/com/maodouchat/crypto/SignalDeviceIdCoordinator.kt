package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.DeviceIdMigrationCoordinator
import com.maodouchat.data.local.entity.SignalKeyEntity
import kotlin.concurrent.withLock

/**
 * Signal 设备 ID 冲突检测与迁移协调器（M03 解耦）。
 * 遵循 [DeviceIdMigrationCoordinator] 端口。
 */
internal class SignalDeviceIdCoordinator(
    private val context: SignalProtocolContext,
    private val keyUploader: (suspend (token: String) -> Result<Unit>)? = null
) : DeviceIdMigrationCoordinator {

    override suspend fun recoverFromConflict(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return uploadKeysWithDeviceIdRecovery(token) {
            if (keyUploader != null) {
                keyUploader.invoke(token)
            } else {
                val store = context.protocolStore as? PersistentSignalProtocolStore
                SignalKeyExchange.uploadKeys(
                    token = token,
                    identityKey = context.identityKeyPair.publicKey,
                    signedPreKey = context.signedPreKey,
                    preKeys = store?.remainingPreKeys().orEmpty(),
                    registrationId = context.registrationId,
                    deviceId = context.localDeviceId
                )
            }
        }.isSuccess
    }

    override fun isMigrationPending(): Boolean {
        return context.deviceIdMigrationOccurred || context.sessionsRequiringReestablishment.isNotEmpty()
    }

    suspend fun restoreDeviceIdMigrationMarker() {
        val key = context.scopedKey(SignalProtocolConstants.KEY_DEVICE_ID_MIGRATION_PENDING)
        context.deviceIdMigrationOccurred = SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(
            context.signalKeyDao.getKey(key)?.keyData
        )
    }

    fun markSessionsForDeviceIdMigration() {
        context.cryptoLock.withLock {
            markSessionsForDeviceIdMigrationLocked()
        }
    }

    fun markSessionsForDeviceIdMigrationLocked() {
        val store = context.protocolStore as? PersistentSignalProtocolStore ?: return
        store.getSessionAddresses().forEach { address ->
            context.sessionsRequiringReestablishment += sessionSetupKey(address.name, address.deviceId)
        }
    }

    suspend fun clearDeviceIdMigrationMarker() {
        context.signalKeyDao.deleteKey(context.scopedKey(SignalProtocolConstants.KEY_DEVICE_ID_MIGRATION_PENDING))
    }

    suspend fun maybeClearDeviceIdMigrationMarker() {
        if (!context.deviceIdMigrationOccurred || context.sessionsRequiringReestablishment.isNotEmpty()) return
        runCatching {
            clearDeviceIdMigrationMarker()
            context.deviceIdMigrationOccurred = false
        }.onFailure { error ->
            Log.w(SignalProtocolConstants.TAG, "Signal device-id migration marker cleanup deferred", error)
        }
    }

    fun generateLocalDeviceId(): Int {
        return SignalProtocolConstants.secureRandom.nextInt(
            SignalProtocolConstants.MAX_DEVICE_ID - SignalProtocolConstants.MIN_GENERATED_DEVICE_ID + 1
        ) + SignalProtocolConstants.MIN_GENERATED_DEVICE_ID
    }

    suspend fun uploadKeysWithDeviceIdRecovery(
        token: String,
        uploadAction: suspend () -> Result<Unit>
    ): Result<Unit> {
        var lastResult: Result<Unit> = Result.failure(IllegalStateException("device registration not attempted"))
        var migrationStarted = false
        val attemptedDeviceIds = linkedSetOf(context.localDeviceId)
        var occupiedDeviceIds: Set<Int> = emptySet()

        for (attempt in 0 until SignalProtocolConstants.MAX_DEVICE_ID_ALLOCATION_ATTEMPTS) {
            lastResult = uploadAction()
            if (lastResult.isSuccess) {
                verifyCurrentDevicePublication(token).getOrThrow()
                if (context.deviceIdMigrationOccurred) {
                    markSessionsForDeviceIdMigration()
                    maybeClearDeviceIdMigrationMarker()
                }
                return lastResult
            }

            val error = lastResult.exceptionOrNull()
            if (!migrationStarted) {
                if (SignalDeviceIdRecoveryPolicy.shouldRetry(error, attempt, SignalProtocolConstants.MAX_DEVICE_ID_ALLOCATION_ATTEMPTS) &&
                    SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(context.identityRestoredFromStore, error)
                ) {
                    val previousDeviceId = context.localDeviceId
                    val replacement = allocateRecoveryDeviceId(previousDeviceId, attemptedDeviceIds, occupiedDeviceIds)
                    if (replacement == null) return lastResult
                    switchLocalDeviceIdForRecovery(previousDeviceId, replacement)
                    attemptedDeviceIds += replacement
                    Log.w(SignalProtocolConstants.TAG, "Signal device id collision for $previousDeviceId; retrying as $replacement")
                    continue
                }

                if (!SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(
                        error,
                        context.identityRestoredFromStore,
                        attempt,
                        SignalProtocolConstants.MAX_DEVICE_ID_ALLOCATION_ATTEMPTS,
                    )
                ) {
                    return lastResult
                }
                migrationStarted = true
                occupiedDeviceIds = fetchOccupiedDeviceIds(token).getOrElse { fetchError ->
                    return Result.failure(
                        SignalDeviceIdRecoveryPolicy.IdentityRecoveryFailedException(fetchError)
                    )
                }
            } else if (!SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(
                    error,
                    attempt,
                    SignalProtocolConstants.MAX_DEVICE_ID_ALLOCATION_ATTEMPTS,
                )
            ) {
                return Result.failure(
                    SignalDeviceIdRecoveryPolicy.IdentityRecoveryFailedException(error)
                )
            }

            val previousDeviceId = context.localDeviceId
            val replacement = allocateRecoveryDeviceId(previousDeviceId, attemptedDeviceIds, occupiedDeviceIds)
                ?: return Result.failure(
                    SignalDeviceIdRecoveryPolicy.IdentityRecoveryFailedException(error)
                )
            switchLocalDeviceIdForRecovery(previousDeviceId, replacement)
            attemptedDeviceIds += replacement
            Log.w(SignalProtocolConstants.TAG, "Signal identity mismatch at $previousDeviceId; retrying unchanged identity as $replacement")
        }

        return if (migrationStarted) {
            Result.failure(
                SignalDeviceIdRecoveryPolicy.IdentityRecoveryFailedException(lastResult.exceptionOrNull())
            )
        } else {
            lastResult
        }
    }

    suspend fun verifyCurrentDevicePublication(token: String): Result<Unit> {
        val accountId = context.currentUserId
            ?: return Result.failure(
                SignalDeviceIdRecoveryPolicy.DeviceStatusUnavailableException(
                    IllegalStateException("signal_account_missing"),
                )
            )
        return SignalKeyExchange.fetchDevices(token, accountId, context.localDeviceId).fold(
            onSuccess = { devices ->
                val current = devices.firstOrNull { it.deviceId == context.localDeviceId }
                    ?: return@fold Result.failure(
                        SignalDeviceIdRecoveryPolicy.DeviceStatusUnavailableException(
                            IllegalStateException("signal_device_missing"),
                        )
                    )
                val expectedIdentity = Base64.encodeToString(
                    context.identityKeyPair.publicKey.serialize(),
                    Base64.NO_WRAP,
                )
                if (current.identityKey.trim() != expectedIdentity) {
                    return@fold Result.failure(
                        SignalDeviceIdRecoveryPolicy.DeviceStatusUnavailableException(
                            IllegalStateException("signal_device_identity_changed"),
                        )
                    )
                }
                when {
                    SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(current.status) -> {
                        context.devicePendingApproval = false
                        Result.success(Unit)
                    }
                    SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(current.status) -> {
                        context.devicePendingApproval = true
                        Result.failure(
                            SignalDeviceIdRecoveryPolicy.DevicePendingApprovalException(context.localDeviceId)
                        )
                    }
                    else -> Result.failure(
                        SignalDeviceIdRecoveryPolicy.DeviceStatusUnavailableException(
                            IllegalStateException("signal_device_status_unknown:${current.status}"),
                        )
                    )
                }
            },
            onFailure = { error ->
                Result.failure(SignalDeviceIdRecoveryPolicy.DeviceStatusUnavailableException(error))
            },
        )
    }

    private suspend fun fetchOccupiedDeviceIds(token: String): Result<Set<Int>> {
        val accountId = context.currentUserId
            ?: return Result.failure(IllegalStateException("signal_account_missing"))
        return SignalKeyExchange.fetchDevices(token, accountId).map { devices ->
            devices.map { it.deviceId }
                .filter { it in SignalProtocolConstants.MIN_DEVICE_ID..SignalProtocolConstants.MAX_DEVICE_ID }
                .toSet()
        }
    }

    private fun allocateRecoveryDeviceId(
        previousDeviceId: Int,
        attemptedDeviceIds: Set<Int>,
        occupiedDeviceIds: Set<Int>,
    ): Int? {
        var candidate: Int?
        repeat(SignalProtocolConstants.MAX_DEVICE_ID_ALLOCATION_ATTEMPTS * 8) {
            candidate = SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = generateLocalDeviceId(),
                previousDeviceId = previousDeviceId,
                occupiedDeviceIds = occupiedDeviceIds,
                attemptedDeviceIds = attemptedDeviceIds,
                minDeviceId = SignalProtocolConstants.MIN_GENERATED_DEVICE_ID,
                maxDeviceId = SignalProtocolConstants.MAX_DEVICE_ID,
            )
            if (candidate != null) return candidate
        }
        return (SignalProtocolConstants.MIN_GENERATED_DEVICE_ID..SignalProtocolConstants.MAX_DEVICE_ID).firstNotNullOfOrNull { id ->
            SignalDeviceIdRecoveryPolicy.availableCandidate(
                candidate = id,
                previousDeviceId = previousDeviceId,
                occupiedDeviceIds = occupiedDeviceIds,
                attemptedDeviceIds = attemptedDeviceIds,
                minDeviceId = SignalProtocolConstants.MIN_GENERATED_DEVICE_ID,
                maxDeviceId = SignalProtocolConstants.MAX_DEVICE_ID,
            )
        }
    }

    private suspend fun switchLocalDeviceIdForRecovery(
        previousDeviceId: Int,
        replacement: Int,
    ) {
        context.cryptoLock.withLock {
            context.deviceIdMigrationOccurred = true
            markSessionsForDeviceIdMigrationLocked()
            context.localDeviceId = replacement
        }
        try {
            persistCoreKeys(deviceIdMigrationPending = true)
        } catch (error: kotlinx.coroutines.CancellationException) {
            context.cryptoLock.withLock { context.localDeviceId = previousDeviceId }
            throw error
        } catch (error: Exception) {
            context.cryptoLock.withLock { context.localDeviceId = previousDeviceId }
            runCatching { persistCoreKeys(deviceIdMigrationPending = true) }
            throw SignalDeviceIdRecoveryPolicy.IdentityRecoveryFailedException(error)
        }
        context.currentUserId?.let { SealedSenderSupport.clearCache(it, previousDeviceId) }
    }

    suspend fun persistCoreKeys(deviceIdMigrationPending: Boolean? = null) {
        context.signalKeyDao.insertKey(
            SignalKeyEntity(context.scopedKey(SignalProtocolConstants.KEY_REGISTRATION_ID), context.registrationId.toString())
        )
        context.signalKeyDao.insertKey(
            SignalKeyEntity(context.scopedKey(SignalProtocolConstants.KEY_DEVICE_ID), context.localDeviceId.toString())
        )
        val pendingMarker = deviceIdMigrationPending ?: context.deviceIdMigrationOccurred
        if (pendingMarker) {
            context.signalKeyDao.insertKey(
                SignalKeyEntity(context.scopedKey(SignalProtocolConstants.KEY_DEVICE_ID_MIGRATION_PENDING), "true")
            )
        } else {
            clearDeviceIdMigrationMarker()
        }
        context.signalKeyDao.insertKey(
            SignalKeyEntity(
                context.scopedKey(SignalProtocolConstants.KEY_IDENTITY_KEY_PAIR),
                Base64.encodeToString(context.identityKeyPair.serialize(), Base64.NO_WRAP)
            )
        )
        context.signedPreKey?.let { spk ->
            context.signalKeyDao.insertKey(
                SignalKeyEntity(
                    context.scopedKey(SignalProtocolConstants.KEY_SIGNED_PRE_KEY),
                    Base64.encodeToString(spk.serialize(), Base64.NO_WRAP)
                )
            )
        }
    }

    private fun sessionSetupKey(recipientId: String, deviceId: Int): String = "$recipientId|$deviceId"
}
