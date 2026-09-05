package com.maodouchat.crypto

import android.util.Log
import com.maodouchat.core.crypto.DirectSessionManager
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.state.PreKeyBundle
import kotlin.concurrent.withLock

/**
 * 发现候选设备与独立单设备会话建立结果。
 */
internal data class SessionCoverage(
    val candidateDeviceIds: List<Int>,
    val establishedDeviceIds: List<Int>,
    val failuresByDevice: Map<Int, Throwable>,
)

/**
 * Signal 直接会话管理器（M03 解耦）。
 * 遵循 [DirectSessionManager] 端口。
 * 负责 1:1 双向棘轮会话建立、多设备并发锁、过期会话清理与设备路由解析。
 */
internal class SignalSessionManager(
    private val context: SignalProtocolContext,
    private val deviceIdCoordinator: SignalDeviceIdCoordinator
) : DirectSessionManager {

    override suspend fun ensureSession(token: String, recipientId: String, deviceId: Int): Result<Unit> {
        val resolvedDeviceId = resolveSessionDeviceId(token, recipientId, deviceId)
            ?: return Result.failure(NoRecipientDevicesException())
        if (!SignalSessionPolicy.shouldEstablishSession(recipientId, resolvedDeviceId, context.currentUserId, context.localDeviceId)) {
            return Result.success(Unit)
        }
        val sessionKey = sessionSetupKey(recipientId, resolvedDeviceId)
        if (!sessionNeedsSetup(recipientId, resolvedDeviceId)) return Result.success(Unit)
        val lockKey = "$recipientId:$resolvedDeviceId"
        val setupLock = context.sessionSetupLocks.compute(lockKey) { _, existing ->
            val lock = existing ?: SessionSetupLock()
            lock.users++
            lock
        }!!
        try {
            return setupLock.mutex.withLock {
                if (!sessionNeedsSetup(recipientId, resolvedDeviceId)) return@withLock Result.success(Unit)
                try {
                    val response = SignalKeyExchange.fetchDevicePreKeyBundle(token, recipientId, resolvedDeviceId)
                        .getOrElse { primaryError ->
                            // Old servers do not expose the per-device endpoint. Only a 404 may
                            // use the legacy bundle, and its concrete device id must still match.
                            if (SignalKeyExchange.shouldUseLegacyBundleEndpoint(primaryError)) {
                                val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId)
                                    .getOrElse { throw primaryError }
                                    .toDeviceBundle(recipientId)
                                if (fallback.deviceId != resolvedDeviceId) throw primaryError
                                fallback
                            } else {
                                throw primaryError
                            }
                        }
                    // Re-check under setup lock: concurrent waiter may have established already.
                    if (context.sessionsRequiringReestablishment.contains(sessionSetupKey(recipientId, response.deviceId)) ||
                        !hasSession(recipientId, response.deviceId)
                    ) {
                        establishSession(
                            recipientId,
                            response.deviceId,
                            SignalKeyExchange.run { response.toSignalPreKeyBundle() }
                        )
                    }
                    context.sessionsRequiringReestablishment.remove(sessionSetupKey(recipientId, response.deviceId))
                    deviceIdCoordinator.maybeClearDeviceIdMigrationMarker()
                    Result.success(Unit)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        } finally {
            // 带引用计数清理：等待中的协程已先 +1，条目只在最后一个使用者退出时移除
            context.sessionSetupLocks.computeIfPresent(lockKey) { _, current ->
                if (current === setupLock) {
                    if (current.users > 1) {
                        current.users--
                        current
                    } else {
                        null
                    }
                } else {
                    current
                }
            }
        }
    }

    suspend fun ensureSessions(token: String, recipientId: String): Result<List<Int>> {
        return try {
            val bundles = SignalKeyExchange.fetchCompatibleDevicePreKeyBundles(token, recipientId).getOrThrow()
            val deviceIds = if (bundles.isEmpty()) {
                val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId).getOrElse { throw it }
                val fallbackId = fallback.deviceId
                if (fallbackId <= 0) {
                    throw NoRecipientDevicesException()
                }
                ensureSession(token, recipientId, fallbackId).getOrThrow()
                listOf(fallbackId)
            } else {
                bundles.map { bundle ->
                    when {
                        !SignalSessionPolicy.shouldEstablishSession(
                            recipientId, bundle.deviceId, context.currentUserId, context.localDeviceId
                        ) -> Unit
                        SignalSessionPolicy.shouldEnsureSession(
                            hasSession = hasSession(recipientId, bundle.deviceId),
                            requiresReestablishment = context.sessionsRequiringReestablishment.contains(
                                sessionSetupKey(recipientId, bundle.deviceId)
                            ),
                        ) -> {
                            ensureSession(token, recipientId, bundle.deviceId).getOrThrow()
                        }
                        else -> Unit
                    }
                    bundle.deviceId
                }
            }
            val eligibleDeviceIds = deviceIds
                .filter {
                    SignalSessionPolicy.shouldEstablishSession(
                        recipientId,
                        it,
                        context.currentUserId,
                        context.localDeviceId,
                    )
                }
                .distinct()
                .sorted()
            if (eligibleDeviceIds.isEmpty()) throw NoRecipientDevicesException()
            Result.success(eligibleDeviceIds)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun ensureSessionsDetailed(
        token: String,
        recipientId: String,
    ): Result<SessionCoverage> {
        return try {
            val knownSessionDeviceIds = getKnownSessionDeviceIds(recipientId)
                .filter {
                    SignalSessionPolicy.shouldEstablishSession(
                        recipientId,
                        it,
                        context.currentUserId,
                        context.localDeviceId,
                    )
                }
            val bundlesResult = SignalKeyExchange.fetchCompatibleDevicePreKeyBundles(token, recipientId)
            val bundles = bundlesResult.getOrNull()
            val authoritativeBundles = bundles ?: emptyList()
            val failuresByDevice = linkedMapOf<Int, Throwable>()
            val establishedDeviceIds = linkedSetOf<Int>()

            suspend fun attempt(deviceId: Int) {
                ensureSession(token, recipientId, deviceId).fold(
                    onSuccess = {
                        if (hasSession(recipientId, deviceId)) {
                            establishedDeviceIds += deviceId
                        } else {
                            failuresByDevice[deviceId] = IllegalStateException(
                                "Signal session was not available after setup"
                            )
                        }
                    },
                    onFailure = { error -> failuresByDevice[deviceId] = error },
                )
            }

            val discoveredDeviceIds = authoritativeBundles
                .map { it.deviceId }
                .filter {
                    SignalSessionPolicy.shouldEstablishSession(
                        recipientId,
                        it,
                        context.currentUserId,
                        context.localDeviceId,
                    )
                }
            val persistedCandidates = SignalSessionPolicy.candidateDeviceIds(
                discoveredDeviceIds = if (bundles == null) null else discoveredDeviceIds,
                persistedSessionDeviceIds = knownSessionDeviceIds,
            )
            val candidateDeviceIds = if (persistedCandidates.isNotEmpty()) {
                persistedCandidates
            } else if (bundles == null) {
                throw (bundlesResult.exceptionOrNull() ?: NoRecipientDevicesException())
            } else if (authoritativeBundles.isEmpty()) {
                val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId).getOrElse { throw it }
                val fallbackId = fallback.deviceId.takeIf { it > 0 }
                    ?: throw NoRecipientDevicesException()
                listOf(fallbackId)
                    .filter {
                        SignalSessionPolicy.shouldEstablishSession(
                            recipientId,
                            it,
                            context.currentUserId,
                            context.localDeviceId,
                        )
                    }
            } else {
                discoveredDeviceIds.distinct().sorted()
            }
            if (candidateDeviceIds.isEmpty()) throw NoRecipientDevicesException()

            candidateDeviceIds.forEach { deviceId ->
                val shouldEnsure = sessionNeedsSetup(recipientId, deviceId)
                if (shouldEnsure) {
                    attempt(deviceId)
                } else {
                    establishedDeviceIds += deviceId
                }
            }

            Result.success(
                SessionCoverage(
                    candidateDeviceIds = candidateDeviceIds,
                    establishedDeviceIds = establishedDeviceIds.toList().sorted(),
                    failuresByDevice = failuresByDevice.toMap(),
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun cleanupStaleSessions(activeContactIds: Set<String>) {
        val store = context.protocolStore as? PersistentSignalProtocolStore ?: return
        context.cryptoLock.withLock {
            val allAddresses = store.getSessionAddresses()
            val toDelete = allAddresses.filter { it.name !in activeContactIds }
            toDelete.forEach { addr ->
                store.deleteSession(addr)
            }
            context.throwIfSignalStorePersistenceFailed()
            if (toDelete.isNotEmpty()) {
                Log.i(SignalProtocolConstants.TAG, "Cleaned up ${toDelete.size} stale Signal sessions")
            }
        }
    }

    fun hasSession(recipientId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): Boolean {
        context.cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            return context.protocolStore.containsSession(address)
        }
    }

    fun getKnownSessionDeviceIds(recipientId: String): List<Int> = context.cryptoLock.withLock {
        if (recipientId.isBlank()) return@withLock emptyList()
        (context.protocolStore as? PersistentSignalProtocolStore)
            ?.getSessionAddresses()
            .orEmpty()
            .asSequence()
            .filter { it.name == recipientId }
            .map { it.deviceId }
            .filter { it in 1..255 }
            .distinct()
            .sorted()
            .toList()
    }

    fun sessionNeedsSetup(recipientId: String, deviceId: Int): Boolean = context.cryptoLock.withLock {
        val address = SignalProtocolAddress(recipientId, deviceId)
        (context.protocolStore as? PersistentSignalProtocolStore)?.persistenceFailure() != null ||
            context.sessionsRequiringReestablishment.contains(sessionSetupKey(recipientId, deviceId)) ||
            !context.protocolStore.containsSession(address)
    }

    fun establishSession(recipientId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID, preKeyBundle: PreKeyBundle) {
        context.cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            val builder = SessionBuilder(context.protocolStore, address)
            builder.process(preKeyBundle)
            context.throwIfSignalStorePersistenceFailed()
        }
    }

    fun deleteSession(recipientId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID) {
        context.cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            context.protocolStore.deleteSession(address)
        }
    }

    suspend fun resolveSessionDeviceId(token: String, recipientId: String, deviceId: Int): Int? {
        if (deviceId != SignalProtocolConstants.DEFAULT_DEVICE_ID) return deviceId
        val discovered = SignalKeyExchange.fetchCompatibleDevicePreKeyBundles(token, recipientId)
            .getOrElse { throw it }
            .map { it.deviceId }
        ConfirmedDevicePolicy.resolve(deviceId, discovered)?.let { return it }
        val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId).getOrElse { throw it }.deviceId
        return ConfirmedDevicePolicy.resolve(deviceId, listOfNotNull(fallback))
    }

    private fun sessionSetupKey(recipientId: String, deviceId: Int): String = "$recipientId|$deviceId"

    private fun SignalKeyExchange.PreKeyBundleResponse.toDeviceBundle(userId: String): SignalKeyExchange.DevicePreKeyBundleResponse {
        return SignalKeyExchange.DevicePreKeyBundleResponse(
            userId = userId,
            deviceId = deviceId,
            registrationId = registrationId,
            identityKey = identityKey,
            signedPreKeyId = signedPreKeyId,
            signedPreKey = signedPreKey,
            signedPreKeySignature = signedPreKeySignature,
            preKeyId = preKeyId,
            preKey = preKey
        )
    }
}
