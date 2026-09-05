package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.PreKeyInventory
import com.maodouchat.core.crypto.PreKeyPublisher
import com.maodouchat.data.local.entity.SignalKeyEntity
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import kotlin.concurrent.withLock

@Serializable
private data class StoredPreKey(val id: Int, val recordBase64: String)

/**
 * Signal 预密钥管理器（M03 解耦）。
 * 遵循 [PreKeyInventory] 与 [PreKeyPublisher] 端口。
 * 负责一次性 PreKey 的补充与持久化、签名预密钥 (SignedPreKey) 的周期轮换与上传。
 */
internal class SignalPreKeyManager(
    private val context: SignalProtocolContext,
    private val deviceIdCoordinator: SignalDeviceIdCoordinator
) : PreKeyInventory, PreKeyPublisher {

    override suspend fun replenishPreKeysIfNeeded(token: String?, expectedUserId: String): Boolean {
        if (token.isNullOrBlank() || expectedUserId.isBlank()) return false
        return context.initializationMutex.withLock {
            if (context.currentUserId != expectedUserId || !context.localCryptoReady) return@withLock false
            // A timeout/cancellation can happen after the server committed the batch. Keep the
            // exact private keys and resend their stable ids until success; deleting them would
            // make any already-issued PreKeySignalMessage permanently undecryptable.
            if (context.preKeyPublicationPending && context.preKeys.isNotEmpty()) {
                persistPreKeys()
                val uploaded = publishRuntimeKeyPackage(token, expectedUserId)
                if (uploaded) {
                    context.preKeyPublicationPending = false
                } else {
                    Log.w(SignalProtocolConstants.TAG, "Pending PreKey publication retry failed; retaining private keys")
                }
                return@withLock uploaded
            }
            if (context.preKeyPublicationPending) {
                context.preKeyPublicationPending = false
            }

            // 持 cryptoLock 防止与 decrypt 路径的 removePreKey 竞态
            val currentCount = context.cryptoLock.withLock {
                (context.protocolStore as? PersistentSignalProtocolStore)?.preKeyCount() ?: 0
            }
            if (currentCount >= SignalProtocolConstants.PRE_KEY_REPLENISH_THRESHOLD) return@withLock false
            val newPreKeys = context.cryptoLock.withLock {
                val outOfRange = (context.protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys().orEmpty()
                    .filter { it.id !in 1..SignalPreKeyIdPolicy.MAX_ID }
                outOfRange.forEach { context.protocolStore.removePreKey(it.id) }
                val maxId = (context.protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys()?.maxOfOrNull { it.id } ?: 0
                val startId = SignalPreKeyIdPolicy.nextBatchStartId(maxId, SignalProtocolConstants.PRE_KEY_COUNT, SignalProtocolConstants.secureRandom::nextInt)
                val generated = (0 until SignalProtocolConstants.PRE_KEY_COUNT).map { offset ->
                    PreKeyRecord(startId + offset, Curve.generateKeyPair())
                }
                generated.forEach { key ->
                    (context.protocolStore as? PersistentSignalProtocolStore)?.putPreKey(key) ?: context.protocolStore.storePreKey(key.id, key)
                }
                context.throwIfSignalStorePersistenceFailed()
                generated
            }
            context.preKeys = newPreKeys
            context.preKeyPublicationPending = true
            persistPreKeys()
            val uploaded = publishRuntimeKeyPackage(token, expectedUserId)
            if (uploaded) {
                context.preKeyPublicationPending = false
            } else {
                Log.w(SignalProtocolConstants.TAG, "PreKey upload failed; retaining batch for idempotent retry")
            }
            uploaded
        }
    }

    override suspend fun rotateSignedPreKeyIfNeeded(token: String?, expectedUserId: String): Boolean {
        if (token.isNullOrBlank() || expectedUserId.isBlank()) return false
        return context.initializationMutex.withLock {
            if (context.currentUserId != expectedUserId || !context.localCryptoReady) return@withLock false
            if (context.signedPreKeyPublicationPending) {
                deviceIdCoordinator.persistCoreKeys()
                val uploaded = publishRuntimeKeyPackage(token, expectedUserId)
                if (uploaded) context.signedPreKeyPublicationPending = false
                return@withLock uploaded
            }
            val current = context.signedPreKey ?: return@withLock false
            if (System.currentTimeMillis() - current.timestamp < SignalProtocolConstants.SIGNED_PRE_KEY_ROTATION_MS) return@withLock false
            context.cryptoLock.withLock {
                generateAndStoreSignedPreKey()
            }
            context.signedPreKeyPublicationPending = true
            deviceIdCoordinator.persistCoreKeys()
            val uploaded = publishRuntimeKeyPackage(token, expectedUserId)
            if (uploaded) context.signedPreKeyPublicationPending = false
            uploaded
        }
    }

    fun ensurePreKeysAvailable() {
        if (context.preKeys.size >= SignalProtocolConstants.PRE_KEY_REPLENISH_THRESHOLD) return
        val target = if (context.preKeys.isEmpty()) SignalProtocolConstants.PRE_KEY_COUNT else SignalProtocolConstants.PRE_KEY_REPLENISH_THRESHOLD
        val deficit = target - context.preKeys.size
        val maxExistingId = (context.protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys()?.maxOfOrNull { it.id } ?: 0
        val startId = SignalPreKeyIdPolicy.nextBatchStartId(maxExistingId, deficit, SignalProtocolConstants.secureRandom::nextInt)
        val generated = (0 until deficit).map { offset ->
            PreKeyRecord(startId + offset, Curve.generateKeyPair())
        }
        generated.forEach { key ->
            (context.protocolStore as? PersistentSignalProtocolStore)?.putPreKey(key) ?: context.protocolStore.storePreKey(key.id, key)
        }
        context.throwIfSignalStorePersistenceFailed()
        context.preKeys = context.preKeys + generated
    }

    fun generateAndStoreSignedPreKey() {
        val signedPreKeyId = SignalPreKeyIdPolicy.randomSignedPreKeyId(SignalProtocolConstants.secureRandom::nextInt)
        val spkKeyPair = Curve.generateKeyPair()
        val spkSignature = Curve.calculateSignature(context.identityKeyPair.privateKey, spkKeyPair.publicKey.serialize())
        val spk = SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), spkKeyPair, spkSignature)
        context.signedPreKey = spk
        (context.protocolStore as? PersistentSignalProtocolStore)?.putSignedPreKey(spk)
            ?: context.protocolStore.storeSignedPreKey(signedPreKeyId, spk)
        context.throwIfSignalStorePersistenceFailed()
    }

    suspend fun persistPreKeys() {
        val encoded = context.preKeys.map {
            StoredPreKey(it.id, Base64.encodeToString(it.serialize(), Base64.NO_WRAP))
        }
        context.signalKeyDao.insertKey(
            SignalKeyEntity(
                context.scopedKey(SignalProtocolConstants.KEY_PRE_KEYS),
                SignalProtocolConstants.json.encodeToString(ListSerializer(StoredPreKey.serializer()), encoded)
            )
        )
    }

    suspend fun publishRuntimeKeyPackage(token: String, expectedUserId: String): Boolean {
        if (context.currentUserId != expectedUserId || !context.localCryptoReady) return false
        val result = try {
            deviceIdCoordinator.uploadKeysWithDeviceIdRecovery(token) {
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
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        val error = result.exceptionOrNull()
        val invalidateLocalCrypto = SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(error)
        if (invalidateLocalCrypto) {
            context.devicePendingApproval = false
            context.deviceIdMigrationOccurred = false
            context.sessionsRequiringReestablishment.clear()
        }
        context.applyInitializationState(
            if (result.isSuccess) {
                SignalInitializationPolicy.publicationSucceeded(context.initializationState())
            } else {
                SignalInitializationPolicy.publicationFailed(
                    context.initializationState(),
                    invalidateLocalCrypto = invalidateLocalCrypto,
                )
            }
        )
        return result.isSuccess
    }

    fun signedPreKeySignatureMatchesIdentity(spk: SignedPreKeyRecord): Boolean {
        return runCatching {
            Curve.verifySignature(
                context.identityKeyPair.publicKey.publicKey,
                spk.keyPair.publicKey.serialize(),
                spk.signature
            )
        }.getOrDefault(false)
    }
}
