package com.maodouchat.crypto

import android.util.Base64
import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.SignalKeyDao
import com.maodouchat.data.local.entity.IdentityTrustEntity
import com.maodouchat.data.local.entity.SignalKeyEntity
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * SQLCipher-backed Signal store.
 *
 * libsignal store callbacks are synchronous, so values are preloaded from Room
 * during SignalProtocol.initialize() and synchronously persisted through this DAO.
 */
class PersistentSignalProtocolStore(
    private val signalKeyDao: SignalKeyDao,
    private val identityTrustDao: IdentityTrustDao,
    private val accountId: String,
    private val identityKeyPair: IdentityKeyPair,
    private val registrationId: Int
) : SignalProtocolStore {

    private val identities = mutableMapOf<String, IdentityKey>()
    private val preKeys = mutableMapOf<Int, PreKeyRecord>()
    private val signedPreKeys = mutableMapOf<Int, SignedPreKeyRecord>()
    private val sessions = mutableMapOf<String, SessionRecord>()
    private val senderKeys = mutableMapOf<String, SenderKeyRecord>()
    private val kyberPreKeys = mutableMapOf<Int, KyberPreKeyRecord>()
    private val usedKyberPreKeys = mutableSetOf<Int>()

    suspend fun loadPersistedState(): Int {
        var droppedCorruptKeys = 0
        signalKeyDao.getKeysWithPrefix(prefix()).forEach { entity ->
            val loaded = runCatching {
                val rawKey = entity.keyType.removePrefix(prefix())
                val data = Base64.decode(entity.keyData, Base64.NO_WRAP)
                when {
                    rawKey.startsWith(KEY_IDENTITY_PREFIX) -> {
                        identities[rawKey.removePrefix(KEY_IDENTITY_PREFIX)] = IdentityKey(data)
                    }
                    rawKey.startsWith(KEY_PRE_KEY_PREFIX) -> {
                        PreKeyRecord(data).also { preKeys[it.id] = it }
                    }
                    rawKey.startsWith(KEY_SIGNED_PRE_KEY_PREFIX) -> {
                        SignedPreKeyRecord(data).also { signedPreKeys[it.id] = it }
                    }
                    rawKey.startsWith(KEY_SESSION_PREFIX) -> {
                        sessions[rawKey.removePrefix(KEY_SESSION_PREFIX)] = SessionRecord(data)
                    }
                    rawKey.startsWith(KEY_SENDER_KEY_PREFIX) -> {
                        senderKeys[rawKey.removePrefix(KEY_SENDER_KEY_PREFIX)] = SenderKeyRecord(data)
                    }
                    rawKey.startsWith(KEY_KYBER_PRE_KEY_PREFIX) -> {
                        KyberPreKeyRecord(data).also { kyberPreKeys[it.id] = it }
                    }
                    rawKey.startsWith(KEY_USED_KYBER_PREFIX) -> {
                        rawKey.removePrefix(KEY_USED_KYBER_PREFIX).toIntOrNull()?.let { usedKyberPreKeys += it }
                    }
                    else -> Unit
                }
            }
            if (loaded.isFailure) {
                droppedCorruptKeys++
                signalKeyDao.deleteKey(entity.keyType)
            }
        }
        return droppedCorruptKeys
    }

    fun putPreKey(record: PreKeyRecord) = storePreKey(record.id, record)
    fun putSignedPreKey(record: SignedPreKeyRecord) = storeSignedPreKey(record.id, record)

    /** Live one-time prekeys still present after consume (source of truth vs KEY_PRE_KEYS blob). */
    fun remainingPreKeys(): List<PreKeyRecord> = preKeys.values.sortedBy { it.id }

    /** 当前未消费的一次性 PreKey 数量（供运行时补充判断）。 */
    fun preKeyCount(): Int = preKeys.size

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val key = addressKey(address)
        val previous = identities[key]
        identities[key] = identityKey
        persist(KEY_IDENTITY_PREFIX + key, identityKey.serialize())
        upsertIdentityTrust(address, identityKey, previous)
        return previous == null || previous != identityKey
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val keyBase64 = encodeIdentity(identityKey)
        val trust = identityTrustDao.getTrustBlocking(accountId, address.name, address.deviceId)
        if (trust == null) {
            val trusted = identities[addressKey(address)]
            return trusted == null || trusted == identityKey
        }
        if (trust.identityKeyBase64 != keyBase64) {
            val now = System.currentTimeMillis()
            identities[addressKey(address)] = identityKey
            persist(KEY_IDENTITY_PREFIX + addressKey(address), identityKey.serialize())
            identityTrustDao.upsertTrustBlocking(
                trust.copy(
                    identityKeyBase64 = keyBase64,
                    trustState = TRUST_CHANGED,
                    lastSeenAt = now,
                    verifiedAt = null
                )
            )
            // 身份密钥变更时删除旧 session：旧 ratchet 状态属于上一个身份，
            // 保留会导致后续解密持续失败；删除后对端需重新建立 session。
            deleteSession(address)
            return false
        }
        return trust.trustState != TRUST_CHANGED
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = identities[addressKey(address)]

    fun getIdentityTrust(remoteUserId: String, deviceId: Int): IdentityTrustEntity? {
        return identityTrustDao.getTrustBlocking(accountId, remoteUserId, deviceId)
    }

    fun markIdentityVerified(remoteUserId: String, deviceId: Int): Boolean {
        val trust = identityTrustDao.getTrustBlocking(accountId, remoteUserId, deviceId) ?: return false
        val now = System.currentTimeMillis()
        identityTrustDao.upsertTrustBlocking(
            trust.copy(
                trustState = TRUST_VERIFIED,
                lastSeenAt = now,
                verifiedAt = now
            )
        )
        return true
    }

    private fun upsertIdentityTrust(address: SignalProtocolAddress, identityKey: IdentityKey, previous: IdentityKey?) {
        val now = System.currentTimeMillis()
        val keyBase64 = encodeIdentity(identityKey)
        val existing = identityTrustDao.getTrustBlocking(accountId, address.name, address.deviceId)
        val trustState = when {
            existing == null -> TRUST_TRUSTED
            existing.identityKeyBase64 == keyBase64 -> existing.trustState
            previous == null || previous == identityKey -> TRUST_CHANGED
            else -> TRUST_CHANGED
        }
        identityTrustDao.upsertTrustBlocking(
            IdentityTrustEntity(
                accountId = accountId,
                remoteUserId = address.name,
                deviceId = address.deviceId,
                identityKeyBase64 = keyBase64,
                trustState = trustState,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                verifiedAt = if (trustState == TRUST_VERIFIED) existing?.verifiedAt else null
            )
        )
    }

    private fun encodeIdentity(identityKey: IdentityKey): String = Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys[preKeyId] ?: throw InvalidKeyIdException("No such pre key: $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeys[preKeyId] = record
        persist(KEY_PRE_KEY_PREFIX + preKeyId, record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)

    override fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId)
        delete(KEY_PRE_KEY_PREFIX + preKeyId)
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        sessions[addressKey(address)] ?: SessionRecord()

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        return addresses.map { address ->
            sessions[addressKey(address)] ?: throw NoSessionException("No session for ${address.name}")
        }.toMutableList()
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        return sessions.keys.mapNotNull { key ->
            val sep = key.lastIndexOf(ADDRESS_SEPARATOR)
            if (sep > 0 && key.substring(0, sep) == name) {
                key.substring(sep + 1).toIntOrNull()
            } else null
        }.toMutableList()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val key = addressKey(address)
        sessions[key] = record
        persist(KEY_SESSION_PREFIX + key, record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = sessions.containsKey(addressKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        val key = addressKey(address)
        sessions.remove(key)
        delete(KEY_SESSION_PREFIX + key)
    }

    override fun deleteAllSessions(name: String) {
        sessions.keys.filter { key ->
            val sep = key.lastIndexOf(ADDRESS_SEPARATOR)
            sep > 0 && key.substring(0, sep) == name
        }.forEach { key ->
            sessions.remove(key)
            delete(KEY_SESSION_PREFIX + key)
        }
    }

    /** 返回当前所有 session 的 SignalProtocolAddress（供清理策略使用）。 */
    fun getSessionAddresses(): List<SignalProtocolAddress> {
        return sessions.keys.mapNotNull { key ->
            val sep = key.lastIndexOf(ADDRESS_SEPARATOR)
            if (sep > 0) {
                val deviceId = key.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
                SignalProtocolAddress(key.substring(0, sep), deviceId)
            } else null
        }
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys[signedPreKeyId] ?: throw InvalidKeyIdException("No such signed pre key: $signedPreKeyId")

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = signedPreKeys.values.toMutableList()

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeys[signedPreKeyId] = record
        persist(KEY_SIGNED_PRE_KEY_PREFIX + signedPreKeyId, record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeys.containsKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId)
        delete(KEY_SIGNED_PRE_KEY_PREFIX + signedPreKeyId)
    }

    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        val key = "${addressKey(address)}$ADDRESS_SEPARATOR$distributionId"
        senderKeys[key] = record
        persist(KEY_SENDER_KEY_PREFIX + key, record.serialize())
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val key = "${addressKey(address)}$ADDRESS_SEPARATOR$distributionId"
        return senderKeys[key]
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeys[kyberPreKeyId] ?: throw InvalidKeyIdException("No such kyber pre key: $kyberPreKeyId")

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = kyberPreKeys.values.toMutableList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeys[kyberPreKeyId] = record
        persist(KEY_KYBER_PRE_KEY_PREFIX + kyberPreKeyId, record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeys.containsKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        usedKyberPreKeys += kyberPreKeyId
        persist(KEY_USED_KYBER_PREFIX + kyberPreKeyId, byteArrayOf(1))
    }

    fun hasKyberPreKeyBeenUsed(kyberPreKeyId: Int): Boolean = usedKyberPreKeys.contains(kyberPreKeyId)

    private fun persist(keyType: String, data: ByteArray) {
        signalKeyDao.insertKeyBlocking(
            SignalKeyEntity(
                keyType = prefix() + keyType,
                keyData = Base64.encodeToString(data, Base64.NO_WRAP)
            )
        )
    }

    private fun delete(keyType: String) {
        signalKeyDao.deleteKeyBlocking(prefix() + keyType)
    }

    private fun prefix(): String = "user:$accountId:"

    private fun addressKey(address: SignalProtocolAddress): String = "${address.name}$ADDRESS_SEPARATOR${address.deviceId}"

    private companion object {
        const val ADDRESS_SEPARATOR = "|"
        const val KEY_IDENTITY_PREFIX = "identity:"
        const val KEY_PRE_KEY_PREFIX = "pre_key:"
        const val KEY_SIGNED_PRE_KEY_PREFIX = "signed_pre_key:"
        const val KEY_SESSION_PREFIX = "session:"
        const val KEY_SENDER_KEY_PREFIX = "sender_key:"
        const val KEY_KYBER_PRE_KEY_PREFIX = "kyber_pre_key:"
        const val KEY_USED_KYBER_PREFIX = "used_kyber_pre_key:"
        const val TRUST_TRUSTED = "TRUSTED"
        const val TRUST_VERIFIED = "VERIFIED"
        const val TRUST_CHANGED = "CHANGED"
    }
}
