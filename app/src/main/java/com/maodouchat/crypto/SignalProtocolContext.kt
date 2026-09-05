package com.maodouchat.crypto

import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.SignalKeyDao
import kotlinx.coroutines.sync.Mutex
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal class SessionSetupLock(val mutex: Mutex = Mutex(), var users: Int = 0)

/**
 * Signal 协议共享上下文与并发状态（M03 解耦）。
 * 集中管理密钥对、存储引用、并发互斥锁与初始化标志。
 */
internal class SignalProtocolContext(
    val signalKeyDao: SignalKeyDao,
    val identityTrustDao: IdentityTrustDao
) {
    val initializationMutex = Mutex()
    val cryptoLock = ReentrantLock()
    val numericFingerprintGenerator = NumericFingerprintGenerator(5200)
    val sessionSetupLocks = ConcurrentHashMap<String, SessionSetupLock>()
    val sessionsRequiringReestablishment = ConcurrentHashMap.newKeySet<String>()
    val decryptRetryTracker = DecryptRetryTracker()

    lateinit var protocolStore: SignalProtocolStore

    @Volatile var currentUserId: String? = null
    @Volatile var initializationSucceeded: Boolean = false
    @Volatile var localCryptoReady: Boolean = false
    @Volatile var identityRestoredFromStore: Boolean = false
    @Volatile var deviceIdMigrationOccurred: Boolean = false
    @Volatile var devicePendingApproval: Boolean = false

    var registrationId: Int = 0
    lateinit var identityKeyPair: IdentityKeyPair
    var signedPreKey: SignedPreKeyRecord? = null
    var preKeys: List<PreKeyRecord> = emptyList()
    @Volatile var preKeyPublicationPending: Boolean = false
    @Volatile var signedPreKeyPublicationPending: Boolean = false
    var localDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID

    fun isSignalStoreHealthy(): Boolean =
        (protocolStore as? PersistentSignalProtocolStore)?.persistenceFailure() == null

    fun throwIfSignalStorePersistenceFailed() {
        val failure = (protocolStore as? PersistentSignalProtocolStore)?.persistenceFailure() ?: return
        throw SignalStorePersistenceException(failure)
    }

    fun scopedKey(keyType: String): String {
        return currentUserId?.let {
            "user:${com.maodouchat.data.local.LikeQueryPolicy.escapeForPrefix(it)}:$keyType"
        } ?: "anonymous:$keyType"
    }

    fun initializationState(): SignalInitializationState {
        val storeHealthy = isSignalStoreHealthy()
        return SignalInitializationState(
            accountId = currentUserId,
            localCryptoReady = localCryptoReady && storeHealthy,
            publicationReady = initializationSucceeded && storeHealthy,
        )
    }

    fun applyInitializationState(state: SignalInitializationState) {
        currentUserId = state.accountId
        localCryptoReady = state.localCryptoReady
        initializationSucceeded = state.publicationReady
    }

    fun generateIdentityKeys() {
        registrationId = KeyHelper.generateRegistrationId(false)
        identityKeyPair = IdentityKeyPair.generate()
        protocolStore = PersistentSignalProtocolStore(
            signalKeyDao = signalKeyDao,
            identityTrustDao = identityTrustDao,
            accountId = currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID,
            identityKeyPair = identityKeyPair,
            registrationId = registrationId
        )
    }

    fun restoreIdentityKeys(regId: Int, keyPair: IdentityKeyPair) {
        registrationId = regId
        identityKeyPair = keyPair
        protocolStore = PersistentSignalProtocolStore(
            signalKeyDao = signalKeyDao,
            identityTrustDao = identityTrustDao,
            accountId = currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID,
            identityKeyPair = identityKeyPair,
            registrationId = registrationId
        )
    }

    fun isLocalStoreReadyFor(userId: String): Boolean =
        isSignalStoreHealthy() &&
            SignalInitializationPolicy.canUseLocalCrypto(currentUserId, localCryptoReady, userId)

    fun isLocalCryptoReadyFor(userId: String): Boolean =
        !devicePendingApproval && isLocalStoreReadyFor(userId)

    fun isInitializedFor(userId: String): Boolean =
        userId.isNotBlank() && initializationSucceeded && currentUserId == userId && isSignalStoreHealthy()

    init {
        generateIdentityKeys()
    }
}
