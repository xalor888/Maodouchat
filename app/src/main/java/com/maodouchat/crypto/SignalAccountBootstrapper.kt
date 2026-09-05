package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.CryptoAccountBootstrapper
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import kotlin.concurrent.withLock

/**
 * Signal 账户与本地密钥引导器（M03 解耦）。
 * 遵循 [CryptoAccountBootstrapper] 端口。
 * 负责本地密钥状态初始化、SQLCipher 恢复、多账号切换/清除及启动期密钥上传握手。
 */
class SignalAccountBootstrapper internal constructor(
    private val context: SignalProtocolContext,
    private val deviceIdCoordinator: SignalDeviceIdCoordinator,
    private val preKeyManager: SignalPreKeyManager
) : CryptoAccountBootstrapper {

    override suspend fun initialize(token: String?, userId: String?): Boolean = context.initializationMutex.withLock {
        try {
            val accountId = userId?.takeIf { it.isNotBlank() }
            when (SignalInitializationPolicy.action(context.initializationState(), accountId)) {
                SignalInitializationAction.REUSE -> return@withLock true
                SignalInitializationAction.UPLOAD_ONLY ->
                    return@withLock finishInitializationUpload(token, accountId)
                SignalInitializationAction.FULL_INITIALIZATION -> Unit
            }
            val accountChanged = context.currentUserId != accountId
            context.applyInitializationState(
                SignalInitializationPolicy.selectAccount(context.initializationState(), accountId)
            )
            if (accountChanged) {
                context.devicePendingApproval = false
                context.deviceIdMigrationOccurred = false
                context.sessionsRequiringReestablishment.clear()
                context.identityRestoredFromStore = false
                context.generateIdentityKeys()
                context.signedPreKey = null
                context.preKeys = emptyList()
                context.preKeyPublicationPending = false
                context.signedPreKeyPublicationPending = false
            }
            context.identityRestoredFromStore = restoreRegistrationAndIdentity()
            restoreDeviceId()
            val droppedCorruptKeys = (context.protocolStore as? PersistentSignalProtocolStore)?.loadPersistedState() ?: 0
            context.throwIfSignalStorePersistenceFailed()
            if (droppedCorruptKeys > 0) {
                Log.w(SignalProtocolConstants.TAG, "Signal init: $droppedCorruptKeys corrupt key rows dropped during loadPersistedState")
            }
            deviceIdCoordinator.restoreDeviceIdMigrationMarker()
            if (context.deviceIdMigrationOccurred) {
                deviceIdCoordinator.markSessionsForDeviceIdMigration()
            }
            restoreSignedPreKey()
            context.signedPreKey?.let { spk ->
                val signatureValid = preKeyManager.signedPreKeySignatureMatchesIdentity(spk)
                val idValid = SignalPreKeyIdPolicy.isValid(spk.id)
                if (!signatureValid || !idValid) {
                    Log.w(
                        SignalProtocolConstants.TAG,
                        "Signal init: signed pre-key unusable (signatureValid=$signatureValid idValid=$idValid id=${spk.id}); regenerating"
                    )
                    context.signedPreKey = null
                }
            }
            context.preKeys = (context.protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys().orEmpty()
            val outOfRange = context.preKeys.filter { it.id !in 1..SignalPreKeyIdPolicy.MAX_ID }
            if (outOfRange.isNotEmpty()) {
                Log.w(SignalProtocolConstants.TAG, "Signal init: dropping ${outOfRange.size} pre-keys with out-of-range ids")
                context.cryptoLock.withLock {
                    outOfRange.forEach { context.protocolStore.removePreKey(it.id) }
                }
                context.preKeys = context.preKeys - outOfRange.toSet()
            }

            if (context.signedPreKey == null) {
                preKeyManager.generateAndStoreSignedPreKey()
            }

            preKeyManager.ensurePreKeysAvailable()
            deviceIdCoordinator.persistCoreKeys()
            preKeyManager.persistPreKeys()
            context.applyInitializationState(
                SignalInitializationPolicy.localStoreReady(context.initializationState())
            )
            context.decryptRetryTracker.clearAll()

            finishInitializationUpload(token, accountId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            val invalidateLocalCrypto = SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(error)
            if (invalidateLocalCrypto) {
                context.devicePendingApproval = false
                context.deviceIdMigrationOccurred = false
                context.sessionsRequiringReestablishment.clear()
            }
            context.applyInitializationState(
                SignalInitializationPolicy.publicationFailed(
                    context.initializationState(),
                    invalidateLocalCrypto = invalidateLocalCrypto,
                )
            )
            throw error
        } catch (error: Exception) {
            Log.w(SignalProtocolConstants.TAG, "Signal protocol initialization failed", error)
            val invalidateLocalCrypto = SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(error)
            if (invalidateLocalCrypto) {
                context.devicePendingApproval = false
                context.deviceIdMigrationOccurred = false
                context.sessionsRequiringReestablishment.clear()
            }
            context.applyInitializationState(
                SignalInitializationPolicy.publicationFailed(
                    context.initializationState(),
                    invalidateLocalCrypto = invalidateLocalCrypto,
                )
            )
            context.localCryptoReady
        }
    }

    override suspend fun ensureLocalCryptoReady(token: String?, userId: String): Boolean {
        if (userId.isBlank()) return false
        if (context.isLocalCryptoReadyFor(userId)) return true
        initialize(token?.takeIf(String::isNotBlank), userId)
        return context.isLocalCryptoReadyFor(userId)
    }

    suspend fun finishInitializationUpload(token: String?, accountId: String?): Boolean {
        var uploadSucceeded = token == null && !context.devicePendingApproval
        if (token != null) {
            if (accountId != null && context.currentUserId != accountId) {
                throw kotlinx.coroutines.CancellationException("signal_init_account_changed")
            }
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
            }.getOrThrow()
            context.preKeyPublicationPending = false
            context.signedPreKeyPublicationPending = false
            uploadSucceeded = true

            if (accountId != null && context.currentUserId != accountId) {
                throw kotlinx.coroutines.CancellationException("signal_init_account_changed")
            }

            if (accountId != null) {
                SealedSenderSupport.fetchCertificate(token, accountId, context.localDeviceId)
                    .onFailure { e ->
                        Log.d(SignalProtocolConstants.TAG, "Sealed sender certificate prefetch skipped: ${e.message}")
                    }
            }
        }
        if (context.currentUserId != accountId) {
            accountId?.let { SealedSenderSupport.clearCache(it, context.localDeviceId) }
            throw kotlinx.coroutines.CancellationException("signal_init_account_changed")
        }
        val state = context.initializationState()
        context.applyInitializationState(
            if (uploadSucceeded && !context.devicePendingApproval) {
                SignalInitializationPolicy.publicationSucceeded(state)
            } else {
                SignalInitializationPolicy.publicationFailed(state)
            }
        )
        return context.localCryptoReady
    }

    suspend fun restoreRegistrationAndIdentity(): Boolean {
        val savedRegId = context.signalKeyDao.getKey(context.scopedKey(SignalProtocolConstants.KEY_REGISTRATION_ID))
        val savedIdentity = context.signalKeyDao.getKey(context.scopedKey(SignalProtocolConstants.KEY_IDENTITY_KEY_PAIR))
        if (savedRegId != null && savedIdentity != null) {
            val regId = savedRegId.keyData.toIntOrNull()
            val identityKeyPair = runCatching { IdentityKeyPair(Base64.decode(savedIdentity.keyData, Base64.NO_WRAP)) }.getOrNull()
            if (regId != null && identityKeyPair != null) {
                context.restoreIdentityKeys(regId, identityKeyPair)
                return true
            }
        }
        return false
    }

    suspend fun restoreDeviceId() {
        val savedDeviceId = context.signalKeyDao.getKey(context.scopedKey(SignalProtocolConstants.KEY_DEVICE_ID))?.keyData?.toIntOrNull()
        context.localDeviceId = savedDeviceId?.takeIf { it in SignalProtocolConstants.MIN_DEVICE_ID..SignalProtocolConstants.MAX_DEVICE_ID }
            ?: deviceIdCoordinator.generateLocalDeviceId()
    }

    suspend fun restoreSignedPreKey() {
        val entity = context.signalKeyDao.getKey(context.scopedKey(SignalProtocolConstants.KEY_SIGNED_PRE_KEY)) ?: return
        val key = runCatching { SignedPreKeyRecord(Base64.decode(entity.keyData, Base64.NO_WRAP)) }.getOrNull() ?: return
        context.signedPreKey = key
        (context.protocolStore as? PersistentSignalProtocolStore)?.putSignedPreKey(key) ?: context.protocolStore.storeSignedPreKey(key.id, key)
        context.throwIfSignalStorePersistenceFailed()
    }

    suspend fun invalidateInMemoryAccountState() = context.initializationMutex.withLock {
        invalidateInMemoryAccountStateLocked()
    }

    internal fun invalidateInMemoryAccountStateLocked() {
        context.cryptoLock.withLock {
            context.applyInitializationState(SignalInitializationPolicy.cleared())
            context.identityRestoredFromStore = false
            context.registrationId = KeyHelper.generateRegistrationId(false)
            context.identityKeyPair = IdentityKeyPair.generate()
            context.signedPreKey = null
            context.preKeys = emptyList()
            context.preKeyPublicationPending = false
            context.signedPreKeyPublicationPending = false
            context.localDeviceId = SignalProtocolConstants.DEFAULT_DEVICE_ID
            context.devicePendingApproval = false
            context.deviceIdMigrationOccurred = false
            context.sessionsRequiringReestablishment.clear()
            context.sessionSetupLocks.clear()
            context.generateIdentityKeys()
        }
    }

    suspend fun clearLocalState() = context.initializationMutex.withLock {
        val accountId = context.currentUserId
        invalidateInMemoryAccountStateLocked()
        if (accountId != null) {
            context.signalKeyDao.deleteKeysWithPrefix("user:${com.maodouchat.data.local.LikeQueryPolicy.escapeForPrefix(accountId)}:")
            context.identityTrustDao.deleteForAccount(accountId)
        } else {
            context.signalKeyDao.deleteKeysWithPrefix("anonymous:")
            context.identityTrustDao.deleteAllTrust()
        }
    }
}
