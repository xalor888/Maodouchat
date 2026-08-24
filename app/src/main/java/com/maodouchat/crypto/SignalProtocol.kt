package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.SignalKeyDao
import com.maodouchat.data.local.entity.SignalKeyEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.withLock
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Signal 端到端加密协议封装
 *
 * 使用 libsignal-client 实现端到端加密。
 * 身份密钥、签名预密钥和一次性预密钥会持久化到 Room，进程重启后可恢复。
 */
class SignalProtocol(
    private val signalKeyDao: SignalKeyDao,
    private val identityTrustDao: IdentityTrustDao
) {
    private val initializationMutex = Mutex()
    /**
     * Serializes SessionCipher / GroupCipher / store mutations across ViewModels and workers.
     * Concurrent encrypt/decrypt on the same session corrupts ratchet state permanently.
     */
    private val cryptoLock = java.util.concurrent.locks.ReentrantLock()
    /** Signal 标准安全码生成器（5200 次迭代，产生 60 位数字指纹）。 */
    private val numericFingerprintGenerator = NumericFingerprintGenerator(5200)
    /** Per peer-device: serialize "check session → fetch OTPK → establish" to avoid double-consume. */
    private class SessionSetupLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val sessionSetupLocks = java.util.concurrent.ConcurrentHashMap<String, SessionSetupLock>()

    private lateinit var protocolStore: SignalProtocolStore
    private val decryptRetryTracker = DecryptRetryTracker()

    @Volatile private var currentUserId: String? = null
    /** True only after a successful initialize() for [currentUserId]; never after failed upload/restore. */
    @Volatile private var initializationSucceeded: Boolean = false
    /** True when identity/registration were loaded from SQLCipher (same-device re-login), not minted. */
    @Volatile private var identityRestoredFromStore: Boolean = false
    private var registrationId: Int = 0
    private lateinit var identityKeyPair: IdentityKeyPair
    private var signedPreKey: SignedPreKeyRecord? = null
    private var preKeys: List<PreKeyRecord> = emptyList()
    private var localDeviceId: Int = DEFAULT_DEVICE_ID

    init {
        // Room DAO 是 suspend API，不能在构造阶段读取数据库。
        // 先生成可用的内存密钥；持久化密钥会在 suspend initialize() 中恢复。
        generateIdentityKeys()
    }

    private fun generateIdentityKeys() {
        registrationId = KeyHelper.generateRegistrationId(false)
        identityKeyPair = IdentityKeyPair.generate()
        protocolStore = PersistentSignalProtocolStore(signalKeyDao, identityTrustDao, currentUserId ?: ANONYMOUS_ACCOUNT_ID, identityKeyPair, registrationId)
    }

    private fun restoreIdentityKeys(registrationId: Int, identityKeyPair: IdentityKeyPair) {
        this.registrationId = registrationId
        this.identityKeyPair = identityKeyPair
        protocolStore = PersistentSignalProtocolStore(signalKeyDao, identityTrustDao, currentUserId ?: ANONYMOUS_ACCOUNT_ID, identityKeyPair, registrationId)
    }

    /**
     * 初始化并持久化密钥，上传公钥到服务器。
     *
     * @param token JWT Token（用于上传公钥到服务器）
     */
    suspend fun initialize(token: String? = null, userId: String? = null): Boolean = initializationMutex.withLock {
        try {
            val accountId = userId?.takeIf { it.isNotBlank() }
            // Login and Application cold-start restoration can race to initialize the same
            // account. Once the first call completes, the queued call must not replace the live
            // protocolStore and reload it while encrypt/decrypt are already using that store.
            if (SignalInitializationPolicy.canReuse(currentUserId, initializationSucceeded, accountId)) {
                return@withLock true
            }
            if (currentUserId != accountId) {
                currentUserId = accountId
                initializationSucceeded = false
                identityRestoredFromStore = false
                generateIdentityKeys()
                signedPreKey = null
                preKeys = emptyList()
            }
            identityRestoredFromStore = restoreRegistrationAndIdentity()
            restoreDeviceId()
            // loadPersistedState 修改非线程安全的内存 map。
            // initializationMutex 已防止并发 initialize；与 decrypt/encrypt 的 cryptoLock 互斥
            // 需要 Mutex（非 ReentrantLock）才能安全跨越 suspend 调用，但 Mutex.withLock 是
            // suspend-only，而 encrypt/decrypt 路径的 cryptoLock 是 ReentrantLock.withLock（非 suspend）。
            // 折中：init 期间不持 cryptoLock，依赖 initializationMutex 序列化 + decrypt 路径的
            // isInitializedFor 门闩（未完成 init 时不解密）来降低竞态窗口。
            val droppedCorruptKeys = (protocolStore as? PersistentSignalProtocolStore)?.loadPersistedState() ?: 0
            if (droppedCorruptKeys > 0) {
                Log.w(TAG, "Signal init: $droppedCorruptKeys corrupt key rows dropped during loadPersistedState")
            }
            restoreSignedPreKey()
            // 9.298：SPK 签名必须与当前 identity 匹配——identity 因腐败/缺失被重新生成而旧 SPK
            // 残留时，上传会让所有与该用户建会话的对端永远报「密钥包无效」（签名验证失败）。
            // 实测案例：服务端 bundle 的 signedPreKey 签名对不上 identityKey，导致发图/发消息全挂
            signedPreKey?.let { spk ->
                // 9.312：签名对象是 SPK 公钥字节（与 generateAndStoreSignedPreKey / 服务端验签一致），
                // 此前误用 spk.serialize()（整条 SignedPreKeyRecord protobuf）→ 每次冷启动验签必失败
                // → 强制重生 SPK 并上传 → 对端 SessionBuilder 验签失败、消息全是未解密。
                val signatureValid = signedPreKeySignatureMatchesIdentity(spk)
                val idValid = SignalPreKeyIdPolicy.isValid(spk.id)
                if (!signatureValid || !idValid) {
                    Log.w(
                        TAG,
                        "Signal init: signed pre-key unusable (signatureValid=$signatureValid idValid=$idValid id=${spk.id}); regenerating"
                    )
                    signedPreKey = null
                }
            }
            // 只取 store 中**未被消费**的 pre_key:* 单行记录。不要回退到 KEY_PRE_KEYS blob——
            // blob 含已消费的一次性预密钥，整体复活后 persistPreKeys() + 上传会把已用 OTPK
            // 重新投放，破坏 X3DH 单次使用语义（两次会话共用同一 OTPK）。无剩余时交给
            // ensurePreKeysAvailable() 铸造全新批次。
            // 9.301：历史版本可能已铸造 id > 16_777_215 的 PreKey（服务端拒收整批上传），
            // 残留 store 里会让每次 init 上传永远被拒——加载后先剔除超范围条目
            preKeys = (protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys().orEmpty()
            val outOfRange = preKeys.filter { it.id !in 1..SignalPreKeyIdPolicy.MAX_ID }
            if (outOfRange.isNotEmpty()) {
                Log.w(TAG, "Signal init: dropping ${outOfRange.size} pre-keys with out-of-range ids")
                cryptoLock.withLock {
                    outOfRange.forEach { protocolStore.removePreKey(it.id) }
                }
                preKeys = preKeys - outOfRange.toSet()
            }

            if (signedPreKey == null) {
                generateAndStoreSignedPreKey()
            }

            ensurePreKeysAvailable()
            persistCoreKeys()
            persistPreKeys()

            if (token != null) {
                // Abort key upload if account switched during long init (disk restore + mint).
                if (accountId != null && currentUserId != accountId) {
                    throw kotlinx.coroutines.CancellationException("signal_init_account_changed")
                }
                uploadKeysWithDeviceIdRecovery(token).getOrThrow()

                if (accountId != null && currentUserId != accountId) {
                    throw kotlinx.coroutines.CancellationException("signal_init_account_changed")
                }

                // Prefetch sealed-sender certificate (best-effort; non-fatal).
                if (accountId != null) {
                    SealedSenderSupport.fetchCertificate(token, accountId, localDeviceId)
                        .onFailure { e ->
                            Log.d(TAG, "Sealed sender certificate prefetch skipped: ${e.message}")
                        }
                }
            }
            if (currentUserId != accountId) {
                accountId?.let { SealedSenderSupport.clearCache(it, localDeviceId) }
                throw kotlinx.coroutines.CancellationException("signal_init_account_changed")
            }
            initializationSucceeded = accountId != null
            decryptRetryTracker.clearAll()
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            // Upload/DB suspend cancel must not be logged as init failure or freeze half-init.
            initializationSucceeded = false
            throw error
        } catch (e: Exception) {
            Log.w(TAG, "Signal protocol initialization failed", e)
            // Half-init must not satisfy isInitializedFor — callers would skip re-init forever
            initializationSucceeded = false
            false
        }
    }

    private suspend fun restoreRegistrationAndIdentity(): Boolean {
        val savedRegId = signalKeyDao.getKey(scopedKey(KEY_REGISTRATION_ID))
        val savedIdentity = signalKeyDao.getKey(scopedKey(KEY_IDENTITY_KEY_PAIR))
        // 腐败数据兜底：用 toIntOrNull + runCatching 避免单条腐败记录杀死整个 Signal 栈
        if (savedRegId != null && savedIdentity != null) {
            val regId = savedRegId.keyData.toIntOrNull()
            val identityKeyPair = runCatching { IdentityKeyPair(Base64.decode(savedIdentity.keyData, Base64.NO_WRAP)) }.getOrNull()
            if (regId != null && identityKeyPair != null) {
                restoreIdentityKeys(regId, identityKeyPair)
                return true
            }
        }
        return false
    }

    private suspend fun restoreDeviceId() {
        val savedDeviceId = signalKeyDao.getKey(scopedKey(KEY_DEVICE_ID))?.keyData?.toIntOrNull()
        localDeviceId = savedDeviceId?.takeIf { it in MIN_DEVICE_ID..MAX_DEVICE_ID } ?: generateLocalDeviceId()
    }

    private suspend fun restoreSignedPreKey() {
        val entity = signalKeyDao.getKey(scopedKey(KEY_SIGNED_PRE_KEY)) ?: return
        // 腐败数据兜底：构造 SignedPreKeyRecord 可能抛 InvalidMessageException
        val key = runCatching { SignedPreKeyRecord(Base64.decode(entity.keyData, Base64.NO_WRAP)) }.getOrNull() ?: return
        signedPreKey = key
        (protocolStore as? PersistentSignalProtocolStore)?.putSignedPreKey(key) ?: protocolStore.storeSignedPreKey(key.id, key)
    }

    private fun signedPreKeySignatureMatchesIdentity(spk: SignedPreKeyRecord): Boolean {
        return runCatching {
            Curve.verifySignature(
                identityKeyPair.publicKey.publicKey,
                spk.keyPair.publicKey.serialize(),
                spk.signature
            )
        }.getOrDefault(false)
    }

    private fun generateAndStoreSignedPreKey() {
        val signedPreKeyId = SignalPreKeyIdPolicy.randomSignedPreKeyId(secureRandom::nextInt)
        val spkKeyPair = Curve.generateKeyPair()
        val spkSignature = Curve.calculateSignature(identityKeyPair.privateKey, spkKeyPair.publicKey.serialize())
        val spk = SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), spkKeyPair, spkSignature)
        signedPreKey = spk
        (protocolStore as? PersistentSignalProtocolStore)?.putSignedPreKey(spk) ?: protocolStore.storeSignedPreKey(signedPreKeyId, spk)
    }

    private fun ensurePreKeysAvailable() {
        // PQXDH 保守路径：libsignal 0.41 已提供 KEMKeyType/KEMKeyPair/KyberPreKeyRecord/KyberPreKeyStore，
        // 但本路径**仅生成 ECC PreKey**，未生成 Kyber PreKey。完整 PQXDH 需要：
        //   1) 生成 KyberPreKeyRecord（KEMKeyType.ML_KEM_768）并持久化到 KyberPreKeyStore
        //   2) SignalKeyExchange.uploadKeys 增加 kyberPreKeys 字段
        //   3) 服务端 SignalKeyRepository 加 Kyber 列存储与 PreKeyBundle 响应字段
        //   4) PreKeyBundle 构造改为 6 参版本（含 KyberPreKeyPublic + signature）
        //   5) SessionBuilder.process 自动使用 Kyber（libsignal 内建）
        // 风险大、跨客户端/服务端、且会改变 PreKeyBundle wire 格式（破坏旧客户端兼容），
        // 因此当前走保守路径：仅 ECC X3DH，PQXDH 未实现。详见 SealedSenderSupport.isImplemented() 注释。
        // 已就绪的一次性 PreKey 不足阈值时补齐，确保上传满足服务端 10..100 契约
        // （服务端 RoutingHelpers 校验 preKeys.size in 10..100，否则拒绝上传导致 init 密钥集无效）。
        // 注意：本地 store(map) 才是真源，init 时 preKeys 已 = remainingFromStore，故以 preKeys.size 判断。
        if (preKeys.size >= PRE_KEY_REPLENISH_THRESHOLD) return
        // 全新安装补满一整批；已有但不足阈值则补齐到阈值（不丢弃 store 中仍有效的旧 PreKey）。
        val target = if (preKeys.isEmpty()) PRE_KEY_COUNT else PRE_KEY_REPLENISH_THRESHOLD
        val deficit = target - preKeys.size
        // 8.37：批次起点取 store 现存最大 id + 1（与 replenishPreKeysIfNeeded 一致）——
        // 随机 startId 可能撞上已上传服务端、尚未被对端消费的 PreKey id，覆盖后对端
        // 按旧 id 发来的 PreKeySignalMessage 永久无法解密
        val maxExistingId = (protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys()?.maxOfOrNull { it.id } ?: 0
        val startId = SignalPreKeyIdPolicy.nextBatchStartId(maxExistingId, deficit, secureRandom::nextInt)
        val generated = (0 until deficit).map { offset ->
            PreKeyRecord(startId + offset, Curve.generateKeyPair())
        }
        generated.forEach { key ->
            (protocolStore as? PersistentSignalProtocolStore)?.putPreKey(key) ?: protocolStore.storePreKey(key.id, key)
        }
        preKeys = preKeys + generated
    }

    /**
     * 运行时 PreKey 补充：当本地未消费的 PreKey 低于 [PRE_KEY_REPLENISH_THRESHOLD] 时，
     * 生成新批次并上传到服务端。应在登录后和定期调用。
     * 返回 true 表示生成了新 PreKey 并上传成功。
     */
    suspend fun replenishPreKeysIfNeeded(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val storeCount = cryptoLock.withLock {
            (protocolStore as? PersistentSignalProtocolStore)?.preKeyCount() ?: return false
        }
        if (storeCount >= PRE_KEY_REPLENISH_THRESHOLD) return false
        return initializationMutex.withLock {
            // 双检：锁内再次确认数量（持 cryptoLock 防止与 decrypt 路径的 removePreKey 竞态）
            val currentCount = cryptoLock.withLock {
                (protocolStore as? PersistentSignalProtocolStore)?.preKeyCount() ?: 0
            }
            if (currentCount >= PRE_KEY_REPLENISH_THRESHOLD) return@withLock false
            // BUG 3 fix: 持 cryptoLock 保护 preKeys map 的读写，防止与 decrypt 路径的 removePreKey 竞态
            val (newPreKeys, maxExistingId) = cryptoLock.withLock {
                // 9.301：同样先剔除超范围残留（见 init 路径同名注释），否则 maxId 被脏值抬高且整批上传被拒
                val outOfRange = (protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys().orEmpty().filter { it.id !in 1..SignalPreKeyIdPolicy.MAX_ID }
                outOfRange.forEach { protocolStore.removePreKey(it.id) }
                val maxId = (protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys()?.maxOfOrNull { it.id } ?: 0
                val startId = SignalPreKeyIdPolicy.nextBatchStartId(maxId, PRE_KEY_COUNT, secureRandom::nextInt)
                val generated = (0 until PRE_KEY_COUNT).map { offset ->
                    PreKeyRecord(startId + offset, Curve.generateKeyPair())
                }
                generated.forEach { key ->
                    (protocolStore as? PersistentSignalProtocolStore)?.putPreKey(key) ?: protocolStore.storePreKey(key.id, key)
                }
                generated to maxId
            }
            preKeys = newPreKeys
            persistPreKeys()
            // BUG 2 fix: 上传失败时回滚 preKeys 列表，允许下次重试
            val uploaded = uploadKeysToServer(token).isSuccess
            if (!uploaded) {
                Log.w(TAG, "PreKey upload failed; removing unuploaded preKeys from store")
                // BUG 5 fix: 从 store 中删除已生成但未上传的 PreKey，使 preKeyCount() 下次能触发重试
                // 持 cryptoLock 防止与 decrypt 路径的 removePreKey 竞态
                cryptoLock.withLock {
                    preKeys.forEach { protocolStore.removePreKey(it.id) }
                }
                // 9.141：回滚后从 store 重建存活旧密钥列表并重写 blob——此前直接置空，
                // getPreKeys() 与 preKeyCount() 长期不一致，且 blob 残留已删除的新密钥；
                // 后续 SPK 轮换的 uploadKeysToServer 会拿着空列表上传被服务端拒收
                preKeys = cryptoLock.withLock {
                    (protocolStore as? PersistentSignalProtocolStore)?.remainingPreKeys()?.sortedBy { it.id }.orEmpty()
                }
                persistPreKeys()
            }
            uploaded
        }
    }

    /**
     * Signed PreKey 轮换（C5）：如果当前 SPK 超过 [SIGNED_PRE_KEY_ROTATION_DAYS] 天，
     * 生成新 SPK 并上传。旧 SPK 保留在 store 中以解密在途消息（libsignal 自动处理）。
     * 返回 true 表示轮换了 SPK。
     */
    suspend fun rotateSignedPreKeyIfNeeded(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val spk = signedPreKey ?: return false
        val ageMs = System.currentTimeMillis() - spk.timestamp
        if (ageMs < SIGNED_PRE_KEY_ROTATION_MS) return false
        return initializationMutex.withLock {
            // 双检：锁内再次确认
            val current = signedPreKey ?: return@withLock false
            if (System.currentTimeMillis() - current.timestamp < SIGNED_PRE_KEY_ROTATION_MS) return@withLock false
            cryptoLock.withLock {
                generateAndStoreSignedPreKey()
            }
            persistCoreKeys()
            uploadKeysToServer(token).isSuccess
        }
    }

    /**
     * Signal session 清理（C2）：删除不在 [activeContactIds] 中的远程用户 session，
     * 防止已删除联系人/旧设备的 session 无限累积。
     */
    fun cleanupStaleSessions(activeContactIds: Set<String>) {
        val store = protocolStore as? PersistentSignalProtocolStore ?: return
        cryptoLock.withLock {
            val allAddresses = store.getSessionAddresses()
            val toDelete = allAddresses.filter { it.name !in activeContactIds }
            toDelete.forEach { addr ->
                store.deleteSession(addr)
            }
            if (toDelete.isNotEmpty()) {
                Log.i(TAG, "Cleaned up ${toDelete.size} stale Signal sessions")
            }
        }
    }

    private suspend fun persistCoreKeys() {
        val spk = signedPreKey ?: return
        signalKeyDao.insertKeys(
            listOf(
                SignalKeyEntity(scopedKey(KEY_REGISTRATION_ID), registrationId.toString()),
                SignalKeyEntity(scopedKey(KEY_DEVICE_ID), localDeviceId.toString()),
                SignalKeyEntity(scopedKey(KEY_IDENTITY_KEY_PAIR), Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP)),
                SignalKeyEntity(scopedKey(KEY_SIGNED_PRE_KEY), Base64.encodeToString(spk.serialize(), Base64.NO_WRAP))
            )
        )
    }

    private suspend fun persistPreKeys() {
        val encoded = preKeys.map {
            StoredPreKey(it.id, Base64.encodeToString(it.serialize(), Base64.NO_WRAP))
        }
        signalKeyDao.insertKey(
            SignalKeyEntity(scopedKey(KEY_PRE_KEYS), json.encodeToString(ListSerializer(StoredPreKey.serializer()), encoded))
        )
    }

    suspend fun ensureSession(token: String, recipientId: String, deviceId: Int = DEFAULT_DEVICE_ID): Result<Unit> {
        val resolvedDeviceId = resolveSessionDeviceId(token, recipientId, deviceId)
            ?: return Result.failure(NoRecipientDevicesException())
        if (!SignalSessionPolicy.shouldEstablishSession(recipientId, resolvedDeviceId, currentUserId, getDeviceId())) {
            return Result.success(Unit)
        }
        if (hasSession(recipientId, resolvedDeviceId)) return Result.success(Unit)
        val lockKey = "$recipientId:$resolvedDeviceId"
        val setupLock = sessionSetupLocks.compute(lockKey) { _, existing ->
            val lock = existing ?: SessionSetupLock()
            lock.users++
            lock
        }!!
        try {
            return setupLock.mutex.withLock {
                if (hasSession(recipientId, resolvedDeviceId)) return@withLock Result.success(Unit)
                try {
                    val response = SignalKeyExchange.fetchDevicePreKeyBundle(token, recipientId, resolvedDeviceId)
                        .getOrElse { primaryError ->
                            // Fallback only for definitive fetch failures — never swallow cancellation.
                            // 8.46 修复：回退仅允许目标设备 = 默认设备（单设备用户）——否则会把
                            // 「另一台设备」的默认 bundle 会话建到 recipientId 上，调用方随后仍按
                            // 入参 deviceId 加密抛 NoSessionException，且留下永不会用到的半建会话。
                            if (resolvedDeviceId == DEFAULT_DEVICE_ID) {
                                val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId)
                                    .getOrElse { throw primaryError }
                                    .toDeviceBundle(recipientId)
                                // 9.141：单设备回退端点解析到「编号最小的已确认设备」，未必是 1 号
                                // （如 1 号 PENDING、2 号 CONFIRMED）——设备不符按失败处理，否则
                                // 会话建到错误设备、调用方按入参 deviceId 加密仍 NoSessionException
                                if (fallback.deviceId != resolvedDeviceId) throw primaryError
                                fallback
                            } else {
                                throw primaryError
                            }
                        }
                    // Re-check under setup lock: concurrent waiter may have established already.
                    if (!hasSession(recipientId, response.deviceId)) {
                        establishSession(
                            recipientId,
                            response.deviceId,
                            SignalKeyExchange.run { response.toSignalPreKeyBundle() }
                        )
                    }
                    Result.success(Unit)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        } finally {
            // 带引用计数清理：等待中的协程已先 +1，条目只在最后一个使用者退出时移除，
            // 避免「移除锁后新调用新建 Mutex」与仍在等待旧锁的协程并发建立会话。
            sessionSetupLocks.computeIfPresent(lockKey) { _, current ->
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
            val bundles = SignalKeyExchange.fetchDevicePreKeyBundles(token, recipientId).getOrThrow()
            val deviceIds = if (bundles.isEmpty()) {
                val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId).getOrNull()
                val fallbackId = fallback?.deviceId
                if (fallbackId == null) {
                    throw NoRecipientDevicesException()
                }
                ensureSession(token, recipientId, fallbackId).getOrThrow()
                listOf(fallbackId)
            } else {
                // Discovery list may peek OTPKs — never re-process PreKeyBundle for an
                // existing session (would overwrite ratchet / thrash one-time prekeys).
                bundles.map { bundle ->
                    when {
                        // 9.311：自身当前设备无需建会话（不与自己加密通信）；跳过避免旧服务端
                        // 禁止自取 bundle 时一个 400 拖垮整个自设备 fan-out（第二设备永远收不到密钥）
                        !SignalSessionPolicy.shouldEstablishSession(
                            recipientId, bundle.deviceId, currentUserId, getDeviceId()
                        ) -> Unit
                        !hasSession(recipientId, bundle.deviceId) -> {
                            // Prefer consuming single-device endpoint for real session setup.
                            ensureSession(token, recipientId, bundle.deviceId).getOrThrow()
                        }
                        else -> Unit
                    }
                    bundle.deviceId
                }
            }
            Result.success(deviceIds.distinct().sorted())
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptContentEnvelope(token, recipientId, plaintext, PAYLOAD_TEXT)
    }

    suspend fun encryptTextEnvelopes(token: String, recipientId: String, plaintext: String): Result<List<String>> {
        return encryptContentEnvelopes(token, recipientId, plaintext, PAYLOAD_TEXT)
    }

    suspend fun encryptMultiDeviceTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptMultiDeviceContentEnvelope(token, recipientId, plaintext, PAYLOAD_TEXT)
    }

    suspend fun encryptSyncedTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptSyncedContentEnvelope(token, recipientId, plaintext, PAYLOAD_TEXT)
    }

    suspend fun encryptSyncedContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> {
        val recipients = listOfNotNull(
            recipientId.takeIf { it.isNotBlank() },
            currentUserId?.takeIf { it.isNotBlank() }
        ).distinct()
        return encryptMultiRecipientContentEnvelope(
            token = token,
            recipientIds = recipients,
            plaintext = plaintext,
            payloadType = payloadType,
            includeCurrentUserDevices = true
        )
    }

    suspend fun encryptMultiDeviceContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> {
        return try {
            val deviceIds = ensureSessions(token, recipientId).getOrThrow()
            val entries = deviceIds.map { deviceId ->
                val cipherResult = encryptMessage(recipientId, plaintext, deviceId)
                MultiDeviceMessageEntry(
                    recipientDeviceId = deviceId,
                    ciphertextType = cipherResult.type,
                    ciphertext = Base64.encodeToString(cipherResult.payload, Base64.NO_WRAP)
                )
            }
            Result.success(
                json.encodeToString(
                    MultiDeviceMessageEnvelope(
                        version = MULTI_DEVICE_ENVELOPE_VERSION,
                        algorithm = ALGORITHM_SIGNAL_MULTI_DEVICE,
                        senderDeviceId = getDeviceId(),
                        payloadType = payloadType,
                        entries = entries
                    )
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptMultiRecipientContentEnvelope(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false
    ): Result<String> {
        return encryptMultiRecipientContentEnvelopeWithTargets(
            token = token,
            recipientIds = recipientIds,
            plaintext = plaintext,
            payloadType = payloadType,
            includeCurrentUserDevices = includeCurrentUserDevices
        ).map { it.envelope }
    }

    suspend fun encryptMultiRecipientContentEnvelopeWithTargets(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false
    ): Result<MultiRecipientEnvelopePayload> {
        return try {
            val targets = mutableListOf<MultiRecipientDeviceTarget>()
            // 单成员 session 失败不得整批 abort（SK fan-out 应尽量覆盖可用设备）
            val entries = recipientIds
                .filter { it.isNotBlank() && (includeCurrentUserDevices || it != currentUserId) }
                .distinct()
                .flatMap { recipientId ->
                    val deviceIds = ensureSessions(token, recipientId).getOrElse { emptyList() }
                    deviceIds
                        .filter { deviceId -> recipientId != currentUserId || deviceId != getDeviceId() }
                        .mapNotNull { deviceId ->
                            try {
                                val cipherResult = encryptMessage(recipientId, plaintext, deviceId)
                                targets += MultiRecipientDeviceTarget(recipientId, deviceId)
                                MultiDeviceMessageEntry(
                                    recipientUserId = recipientId,
                                    recipientDeviceId = deviceId,
                                    ciphertextType = cipherResult.type,
                                    ciphertext = Base64.encodeToString(cipherResult.payload, Base64.NO_WRAP)
                                )
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        }
                }
            if (entries.isEmpty()) throw NoRecipientDevicesException()
            val envelope = json.encodeToString(MultiDeviceMessageEnvelope(
                version = MULTI_DEVICE_ENVELOPE_VERSION,
                algorithm = ALGORITHM_SIGNAL_MULTI_DEVICE,
                senderDeviceId = getDeviceId(),
                payloadType = payloadType,
                entries = entries
            ))
            Result.success(MultiRecipientEnvelopePayload(envelope, targets))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptContentEnvelopes(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<List<String>> {
        return try {
            val deviceIds = ensureSessions(token, recipientId).getOrThrow()
            Result.success(
                deviceIds.map { deviceId ->
                    encryptContentEnvelope(token, recipientId, plaintext, payloadType, deviceId).getOrThrow()
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String,
        deviceId: Int = DEFAULT_DEVICE_ID
    ): Result<String> {
        return try {
            val resolvedDeviceId = resolveSessionDeviceId(token, recipientId, deviceId)
                ?: return Result.failure(NoRecipientDevicesException())
            ensureSession(token, recipientId, resolvedDeviceId).getOrThrow()
            val cipherResult = encryptMessage(recipientId, plaintext, resolvedDeviceId)
            Result.success(
                json.encodeToString(
                    EncryptedMessageEnvelope(
                        version = ENVELOPE_VERSION,
                        algorithm = ALGORITHM_SIGNAL,
                        senderDeviceId = getDeviceId(),
                        recipientDeviceId = resolvedDeviceId,
                        ciphertextType = cipherResult.type,
                        payloadType = payloadType,
                        ciphertext = Base64.encodeToString(cipherResult.payload, Base64.NO_WRAP)
                    )
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * AI 同步等队列：终态或达重试上限时应 ACK，避免同一信封死循环重拉。
     * [envelopeId] 用服务端队列 id，与密文 fingerprint 分开计数。
     */
    fun shouldAcknowledgeDecrypt(envelopeId: String, result: DecryptResult): Boolean =
        decryptRetryTracker.shouldAcknowledge(envelopeId, result)

    fun decryptTextEnvelope(senderId: String, content: String): DecryptResult {
        return decryptContentEnvelope(senderId, content)
    }

    fun decryptContentEnvelope(senderId: String, content: String): DecryptResult {
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        if (DecryptFailurePolicy.shouldSkipCryptoAttempt(decryptRetryTracker.failureCount(fingerprint))) {
            return DecryptResult.Failed
        }
        val result = try {
            val parsedMulti = MultiDeviceEnvelopePolicy.parse(content)
            if (parsedMulti != null) {
                decryptParsedMultiDeviceEnvelope(senderId, parsedMulti)
            } else {
                val envelope = json.decodeFromString(EncryptedMessageEnvelope.serializer(), content)
                when (envelope.version) {
                    1 -> DecryptResult.Success(
                        decryptMessage(senderId, Base64.decode(envelope.ciphertext, Base64.NO_WRAP), envelope.senderDeviceId)
                    )
                    ENVELOPE_VERSION -> {
                        if (envelope.algorithm != ALGORITHM_SIGNAL) {
                            DecryptResult.UnsupportedEnvelope
                        } else {
                            val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
                            DecryptResult.Success(
                                decryptMessage(
                                    senderId = senderId,
                                    ciphertext = ciphertext,
                                    deviceId = envelope.senderDeviceId,
                                    ciphertextType = envelope.ciphertextType
                                )
                            )
                        }
                    }
                    else -> DecryptResult.UnsupportedEnvelope
                }
            }
        } catch (e: NoSessionException) {
            DecryptResult.NoSession
        } catch (e: org.signal.libsignal.protocol.UntrustedIdentityException) {
            DecryptResult.UntrustedIdentity
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            // 良性去重异常（重复/乱序投递，libsignal 继承 InvalidMessageException）。
            // 归为 Duplicate，调用方按“已处理”忽略，避免误报为解密失败。
            DecryptResult.Duplicate
        } catch (e: Exception) {
            // 非预期异常（state 损坏、编解码失败等）— 必须 Log 以定位根因，不能静默转 Failed
            Log.w(TAG, "decryptContentEnvelope unexpected failure", e)
            DecryptResult.Failed
        }
        rememberDecryptOutcome(fingerprint, result)
        return result
    }

    private fun decryptParsedMultiDeviceEnvelope(
        senderId: String,
        envelope: MultiDeviceEnvelopePolicy.ParsedEnvelope
    ): DecryptResult {
        val entry = MultiDeviceEnvelopePolicy.selectEntry(envelope, currentUserId, getDeviceId())
            ?: return DecryptResult.NotForThisDevice
        val ciphertext = Base64.decode(entry.ciphertext, Base64.NO_WRAP)
        return DecryptResult.Success(
            decryptMessage(
                senderId = senderId,
                ciphertext = ciphertext,
                deviceId = envelope.senderDeviceId,
                ciphertextType = entry.ciphertextType
            )
        )
    }

    fun envelopePayloadType(content: String): String? {
        return MultiDeviceEnvelopePolicy.parse(content)?.payloadType
            ?: runCatching { json.decodeFromString(EncryptedMessageEnvelope.serializer(), content).payloadType }.getOrNull()
    }

    fun isEncryptedEnvelope(content: String): Boolean {
        if (MultiDeviceEnvelopePolicy.parse(content) != null) return true
        return runCatching { json.decodeFromString(EncryptedMessageEnvelope.serializer(), content) }
            .getOrNull()?.version?.let { it >= 1 } == true
    }

    fun isSenderKeyEnvelope(content: String): Boolean {
        return runCatching { json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content) }
            .getOrNull()?.let { envelope ->
                envelope.version == SENDER_KEY_ENVELOPE_VERSION && envelope.algorithm == ALGORITHM_SENDER_KEY
            } == true
    }

    fun isSenderKeyDistributionEnvelope(content: String): Boolean {
        return runCatching { json.decodeFromString(SenderKeyDistributionEnvelope.serializer(), content) }
            .getOrNull()?.let { envelope ->
                envelope.version == SENDER_KEY_DISTRIBUTION_VERSION && envelope.algorithm == ALGORITHM_SENDER_KEY_DISTRIBUTION
            } == true
    }

    fun buildSenderKeyDistributionEnvelope(groupId: String, distributionId: String, message: SenderKeyDistributionMessage, epoch: Long = 0): String {
        return json.encodeToString(SenderKeyDistributionEnvelope(
            groupId = groupId,
            epoch = epoch,
            senderDeviceId = getDeviceId(),
            distributionId = distributionId,
            distributionMessage = Base64.encodeToString(message.serialize(), Base64.NO_WRAP)
        ))
    }

/**
     * Install a peer's Sender Key distribution.
     * - [SenderKeyDistOutcome.Installed]: installed successfully
     * - [SenderKeyDistOutcome.Skipped]: permanently unusable (stale epoch / wrong group / bad format) — sync may advance
     * - [SenderKeyDistOutcome.Failed]: unexpected crypto/parse failure — treat as retryable for live path
     */
    fun processSenderKeyDistributionEnvelope(
        senderId: String,
        content: String,
        expectedGroupId: String? = null,
        currentEpoch: Long? = null
    ): SenderKeyDistOutcome {
        return cryptoLock.withLock {
            runCatching {
                val envelope = runCatching {
                    json.decodeFromString(SenderKeyDistributionEnvelope.serializer(), content)
                }.getOrElse { return@runCatching SenderKeyDistOutcome.Skipped }
                if (envelope.version != SENDER_KEY_DISTRIBUTION_VERSION || envelope.algorithm != ALGORITHM_SENDER_KEY_DISTRIBUTION) {
                    return@runCatching SenderKeyDistOutcome.Skipped
                }
                if (expectedGroupId != null && envelope.groupId != expectedGroupId) {
                    return@runCatching SenderKeyDistOutcome.Skipped
                }
                // 本地 memberRevision 滞后（缓存/离线）时仍应安装更新的 Sender Key；
                // 拒绝未来 epoch 会导致后续群消息 NoSession，直到全量重分发。
                // 仅拒绝明显回退的过期分发（已知 epoch 且 envelope 更旧）——可安全跳过并推进同步游标。
                if (currentEpoch != null && currentEpoch > 0L && envelope.epoch > 0L && envelope.epoch < currentEpoch) {
                    return@runCatching SenderKeyDistOutcome.Skipped
                }
                val senderAddress = SignalProtocolAddress(senderId, envelope.senderDeviceId)
                val distributionMessage = SenderKeyDistributionMessage(Base64.decode(envelope.distributionMessage, Base64.NO_WRAP))
                GroupSessionBuilder(protocolStore).process(senderAddress, distributionMessage)
                SenderKeyDistOutcome.Installed
            }.getOrDefault(SenderKeyDistOutcome.Failed)
        }
    }

    fun createGroupSenderKeyDistribution(groupId: String, epoch: Long = 0): SenderKeyDistributionPayload {
        return cryptoLock.withLock {
            val distributionId = getOrCreateGroupDistributionId(groupId, epoch)
            val senderAddress = SignalProtocolAddress(currentUserId ?: ANONYMOUS_ACCOUNT_ID, getDeviceId())
            val message = GroupSessionBuilder(protocolStore).create(senderAddress, UUID.fromString(distributionId))
            SenderKeyDistributionPayload(distributionId, message, epoch)
        }
    }

    /**
     * 检查是否已为指定群聊创建过 SenderKey 分发 ID
     * 用于判断是否需要发送 SenderKeyDistributionMessage
     */
    fun hasGroupDistributionId(groupId: String, epoch: Long = 0): Boolean {
        val key = scopedKey("$KEY_GROUP_DISTRIBUTION_PREFIX$groupId")
        val epochKey = scopedKey("$KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX$groupId")
        val savedEpoch = signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull() ?: 0L
        return signalKeyDao.getKeyBlocking(key)?.keyData
            ?.let { runCatching { UUID.fromString(it) }.isSuccess && savedEpoch == epoch } == true
    }

    /**
     * 9.310：群 SenderKey 分发是否真正可用——分发元数据存在但 protocol store 里的真实
     * sender key 状态可能已丢失（identity 重生/损坏密钥自愈清空 store），此时
     * GroupCipher.encrypt 报 "missing sender key state"，群发送静默失败。元数据+密钥
     * 状态都在才算可用；否则调用方应走重新分发（mint 会重建同 distributionId 的密钥记录）。
     */
    fun groupDistributionUsable(groupId: String, epoch: Long = 0): Boolean {
        if (!hasGroupDistributionId(groupId, epoch)) return false
        return cryptoLock.withLock {
            runCatching {
                val key = scopedKey("$KEY_GROUP_DISTRIBUTION_PREFIX$groupId")
                val distributionId = signalKeyDao.getKeyBlocking(key)?.keyData ?: return@withLock false
                val senderAddress = SignalProtocolAddress(currentUserId ?: ANONYMOUS_ACCOUNT_ID, getDeviceId())
                protocolStore.loadSenderKey(senderAddress, UUID.fromString(distributionId)) != null
            }.getOrDefault(false)
        }
    }

    fun shouldRotateGroupSenderKey(groupId: String, epoch: Long = 0): Boolean {
        if (!hasGroupDistributionId(groupId, epoch)) return false
        val metadata = loadGroupDistributionMetadata(groupId)
        val ageMs = System.currentTimeMillis() - metadata.createdAt
        return ageMs >= GROUP_SENDER_KEY_MAX_AGE_MS || metadata.messageCount >= GROUP_SENDER_KEY_MAX_MESSAGES
    }

    private val countedGroupMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val countedGroupMessageOrder = java.util.ArrayDeque<String>()

    /**
     * 记录一次群 SenderKey 消息发送计数。
     * [messageId] 传入时按群/epoch/消息 ID 去重：WS 投递与 REST ack 对同一消息双计
     * 会让 messageCount 提前触达 1000 轮换阈值。
     */
    fun markGroupSenderKeyMessageSent(groupId: String, epoch: Long = 0, messageId: String? = null) {
        if (groupId.isBlank()) return
        if (!messageId.isNullOrBlank()) {
            val dedupeKey = "$groupId:$epoch:$messageId"
            synchronized(countedGroupMessageIds) {
                if (!countedGroupMessageIds.add(dedupeKey)) return
                countedGroupMessageOrder.addLast(dedupeKey)
                while (countedGroupMessageOrder.size > MAX_COUNTED_GROUP_MESSAGE_IDS) {
                    countedGroupMessageIds.remove(countedGroupMessageOrder.removeFirst())
                }
            }
        }
        cryptoLock.withLock {
            val metadata = loadGroupDistributionMetadata(groupId)
            saveGroupDistributionMetadata(groupId, metadata.copy(epoch = epoch, messageCount = metadata.messageCount + 1))
        }
    }

    fun invalidateGroupSenderKey(groupId: String): Boolean {
        if (groupId.isBlank()) return false
        return cryptoLock.withLock {
            val key = scopedKey("$KEY_GROUP_DISTRIBUTION_PREFIX$groupId")
            val epochKey = scopedKey("$KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX$groupId")
            runCatching {
                signalKeyDao.deleteKeyBlocking(key)
                signalKeyDao.deleteKeyBlocking(epochKey)
                signalKeyDao.deleteKeyBlocking(scopedKey("$KEY_GROUP_DISTRIBUTION_METADATA_PREFIX$groupId"))
                true
            }.onFailure {
                Log.w(TAG, "Failed to invalidate group SenderKey for $groupId", it)
            }.getOrDefault(false)
        }
    }

    fun encryptGroupTextEnvelope(groupId: String, plaintext: String, payloadType: String, epoch: Long = 0): Result<String> {
        return encryptGroupContentEnvelope(groupId, plaintext, payloadType, epoch)
    }

    fun encryptGroupContentEnvelope(groupId: String, plaintext: String, payloadType: String, epoch: Long = 0): Result<String> {
        return cryptoLock.withLock {
            runCatching {
                // 禁止在 encrypt 路径 mint 新 distribution：必须先 create+fan-out，否则对端无 SKDM
                require(epoch > 0L) { "group_epoch_unknown" }
                val distributionId = requireExistingGroupDistributionId(groupId, epoch)
                val senderAddress = SignalProtocolAddress(currentUserId ?: ANONYMOUS_ACCOUNT_ID, getDeviceId())
                val cipher = GroupCipher(protocolStore, senderAddress)
                val ciphertext = cipher.encrypt(UUID.fromString(distributionId), plaintext.toByteArray(Charsets.UTF_8))
                json.encodeToString(SenderKeyMessageEnvelope(
                    groupId = groupId,
                    epoch = epoch,
                    senderDeviceId = getDeviceId(),
                    distributionId = distributionId,
                    payloadType = payloadType,
                    ciphertext = Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP)
                ))
            }
        }
    }

    /** Parse SK envelope epoch when reusing stored attachment wireContent. */
    fun parseSenderKeyEnvelopeEpoch(content: String): Long? {
        return runCatching {
            val envelope = json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
            envelope.epoch.takeIf { it > 0L }
        }.getOrNull()
    }

    fun decryptGroupContentEnvelope(senderId: String, content: String, expectedGroupId: String? = null, currentEpoch: Long? = null): DecryptResult {
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        if (DecryptFailurePolicy.shouldSkipCryptoAttempt(decryptRetryTracker.failureCount(fingerprint))) {
            return DecryptResult.Failed
        }
        val result = cryptoLock.withLock {
            try {
                val envelope = json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
                if (envelope.version != SENDER_KEY_ENVELOPE_VERSION || envelope.algorithm != ALGORITHM_SENDER_KEY) {
                    return@withLock DecryptResult.UnsupportedEnvelope
                }
                if (expectedGroupId != null && envelope.groupId != expectedGroupId) {
                    return@withLock DecryptResult.UnsupportedEnvelope
                }
                // currentEpoch 未知（null）或未就绪（<=0）时不得用 0 把合法密文打成 FutureEpoch
                if (currentEpoch != null && currentEpoch > 0L && envelope.epoch > currentEpoch) {
                    return@withLock DecryptResult.FutureEpoch
                }
                val senderAddress = SignalProtocolAddress(senderId, envelope.senderDeviceId)
                val plaintext = GroupCipher(protocolStore, senderAddress)
                    .decrypt(Base64.decode(envelope.ciphertext, Base64.NO_WRAP))
                DecryptResult.Success(String(plaintext, Charsets.UTF_8))
            } catch (e: NoSessionException) {
                DecryptResult.NoSession
            } catch (e: org.signal.libsignal.protocol.UntrustedIdentityException) {
                DecryptResult.UntrustedIdentity
            } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
                // 良性去重异常（重复/乱序投递）。归为 Duplicate，调用方按“已处理”忽略。
                DecryptResult.Duplicate
            } catch (e: Exception) {
                // 同上 — 非预期的群解密异常，记录以便排查
                Log.w(TAG, "decryptGroupContentEnvelope unexpected failure", e)
                DecryptResult.Failed
            }
        }
        rememberDecryptOutcome(fingerprint, result)
        return result
    }

    private fun rememberDecryptOutcome(fingerprint: String, result: DecryptResult) {
        when {
            result is DecryptResult.Success || result == DecryptResult.Duplicate -> decryptRetryTracker.clear(fingerprint)
            DecryptFailurePolicy.disposition(result) == DecryptFailurePolicy.Disposition.RETRY ->
                decryptRetryTracker.recordCryptoFailure(fingerprint)
            else -> decryptRetryTracker.clear(fingerprint)
        }
    }

    fun buildUnsupportedSenderKeyEnvelope(
        groupId: String,
        distributionId: String,
        payloadType: String,
        ciphertext: String,
        epoch: Long = 0,
        senderDeviceId: Int = DEFAULT_DEVICE_ID
    ): String {
        return json.encodeToString(SenderKeyMessageEnvelope(
            groupId = groupId,
            epoch = epoch,
            senderDeviceId = senderDeviceId,
            distributionId = distributionId,
            payloadType = payloadType,
            ciphertext = ciphertext
        ))
    }

    fun establishSession(recipientId: String, deviceId: Int = DEFAULT_DEVICE_ID, preKeyBundle: PreKeyBundle) {
        cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            val builder = SessionBuilder(protocolStore, address)
            builder.process(preKeyBundle)
        }
    }

    fun encryptMessage(recipientId: String, plaintext: String, deviceId: Int = DEFAULT_DEVICE_ID): EncryptedPayload {
        cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            val cipher = SessionCipher(protocolStore, address)
            val ciphertext = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            val type = when (ciphertext.type) {
                CiphertextMessage.PREKEY_TYPE -> CIPHERTEXT_TYPE_PREKEY
                CiphertextMessage.WHISPER_TYPE -> CIPHERTEXT_TYPE_SIGNAL
                else -> CIPHERTEXT_TYPE_UNKNOWN
            }
            return EncryptedPayload(type, ciphertext.serialize())
        }
    }

    fun decryptMessage(
        senderId: String,
        ciphertext: ByteArray,
        deviceId: Int = DEFAULT_DEVICE_ID,
        ciphertextType: String? = null
    ): String {
        cryptoLock.withLock {
            val address = SignalProtocolAddress(senderId, deviceId)
            val cipher = SessionCipher(protocolStore, address)

            return when (ciphertextType) {
                CIPHERTEXT_TYPE_PREKEY -> String(cipher.decrypt(PreKeySignalMessage(ciphertext)), Charsets.UTF_8)
                CIPHERTEXT_TYPE_SIGNAL -> String(cipher.decrypt(SignalMessage(ciphertext)), Charsets.UTF_8)
                else -> try {
                    val preKeyMsg = PreKeySignalMessage(ciphertext)
                    String(cipher.decrypt(preKeyMsg), Charsets.UTF_8)
                } catch (e: InvalidMessageException) {
                    try {
                        val signalMsg = SignalMessage(ciphertext)
                        String(cipher.decrypt(signalMsg), Charsets.UTF_8)
                    } catch (e2: NoSessionException) {
                        throw e2
                    }
                }
            }
        }
    }

    fun hasSession(recipientId: String, deviceId: Int = DEFAULT_DEVICE_ID): Boolean {
        cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            return protocolStore.containsSession(address)
        }
    }

    fun getIdentityPublicKey(): IdentityKey = identityKeyPair.publicKey
    fun getSignedPreKey(): SignedPreKeyRecord? = signedPreKey
    fun getPreKeys(): List<PreKeyRecord> = preKeys
    fun getRegistrationId(): Int = registrationId
    fun getDeviceId(): Int = localDeviceId
    fun signDeviceConfirmation(targetDeviceId: Int, targetIdentityKeyBase64: String): String? = cryptoLock.withLock {
        val userId = currentUserId?.takeIf { it.isNotBlank() } ?: return@withLock null
        if (!initializationSucceeded || localDeviceId !in 1..255 || targetDeviceId !in 1..255) return@withLock null
        val targetIdentity = targetIdentityKeyBase64.trim().takeIf { it.isNotBlank() } ?: return@withLock null
        val payload = buildDeviceConfirmationPayload(userId, localDeviceId, targetDeviceId, targetIdentity)
        val signature = Curve.calculateSignature(identityKeyPair.privateKey, payload)
        Base64.encodeToString(signature, Base64.NO_WRAP)
    }
    fun isInitializedFor(userId: String): Boolean =
        userId.isNotBlank() && initializationSucceeded && currentUserId == userId

    /** Same-device restore vs a newly minted identity after a true wipe. */
    fun wasIdentityRestoredFromStore(): Boolean = identityRestoredFromStore
    fun getLocalIdentityFingerprint(): String = identityFingerprint(identityKeyPair.publicKey.serialize())

    private fun buildDeviceConfirmationPayload(
        userId: String,
        approverDeviceId: Int,
        targetDeviceId: Int,
        targetIdentityKeyBase64: String
    ): ByteArray = buildString {
        append("maodouchat-device-confirm:v1\n")
        append(userId)
        append('\n')
        append(approverDeviceId)
        append('\n')
        append(targetDeviceId)
        append('\n')
        append(targetIdentityKeyBase64)
    }.toByteArray(Charsets.UTF_8)

    fun getRemoteIdentityFingerprint(remoteUserId: String, deviceId: Int = DEFAULT_DEVICE_ID): String? {
        val remoteIdentity = (protocolStore as? PersistentSignalProtocolStore)
            ?.getIdentity(SignalProtocolAddress(remoteUserId, deviceId))
            ?: return null
        return identityFingerprint(remoteIdentity.serialize())
    }

    fun identityFingerprintFromBase64(identityKeyBase64: String): String? {
        return runCatching { identityFingerprint(Base64.decode(identityKeyBase64, Base64.NO_WRAP)) }.getOrNull()
    }

    fun getIdentityTrustState(remoteUserId: String, deviceId: Int = DEFAULT_DEVICE_ID): IdentityTrustState {
        val trust = (protocolStore as? PersistentSignalProtocolStore)?.getIdentityTrust(remoteUserId, deviceId)
            ?: return IdentityTrustState.UNKNOWN
        return when (trust.trustState) {
            TRUST_VERIFIED -> IdentityTrustState.VERIFIED
            TRUST_CHANGED -> IdentityTrustState.CHANGED
            else -> IdentityTrustState.TRUSTED
        }
    }

    fun getSafetyCode(remoteUserId: String, deviceId: Int = DEFAULT_DEVICE_ID): String? {
        val localUserId = currentUserId?.takeIf { it.isNotBlank() } ?: return null
        val remoteIdentity = (protocolStore as? PersistentSignalProtocolStore)
            ?.getIdentity(SignalProtocolAddress(remoteUserId, deviceId))
            ?: return null
        // 使用 libsignal 标准 NumericFingerprintGenerator 生成迭代式安全码，
        // 替代自研 SHA-256 截断 15 字节的弱化实现；二维码与屏幕显示使用同一标准指纹。
        val localStableId = "$localUserId:$DEFAULT_DEVICE_ID".toByteArray(Charsets.UTF_8)
        val remoteStableId = "$remoteUserId:$deviceId".toByteArray(Charsets.UTF_8)
        return runCatching {
            numericFingerprintGenerator.createFor(
                /* version = */ 2,
                localStableId,
                identityKeyPair.publicKey,
                remoteStableId,
                remoteIdentity
            ).displayableFingerprint.displayText
        }.getOrNull()
    }

    fun markIdentityVerified(remoteUserId: String, deviceId: Int = DEFAULT_DEVICE_ID): Boolean {
        return (protocolStore as? PersistentSignalProtocolStore)?.markIdentityVerified(remoteUserId, deviceId) == true
    }

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

    suspend fun getRemoteDeviceSafetyStates(token: String, remoteUserId: String): Result<List<DeviceSafetyState>> {
        return try {
            val devices = SignalKeyExchange.fetchDevices(token, remoteUserId).getOrElse { return Result.failure(it) }
            Result.success(
                devices.map { device ->
                    DeviceSafetyState(
                        deviceId = device.deviceId,
                        identityKey = device.identityKey,
                        identityFingerprint = getRemoteIdentityFingerprint(remoteUserId, device.deviceId)
                            ?: identityFingerprintFromBase64(device.identityKey).orEmpty(),
                        trustState = getIdentityTrustState(remoteUserId, device.deviceId),
                        safetyCode = getSafetyCode(remoteUserId, device.deviceId),
                        isCurrent = device.isCurrent
                    )
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun invalidateInMemoryAccountState() {
        cryptoLock.withLock {
            currentUserId = null
            initializationSucceeded = false
            identityRestoredFromStore = false
            registrationId = KeyHelper.generateRegistrationId(false)
            identityKeyPair = IdentityKeyPair.generate()
            signedPreKey = null
            preKeys = emptyList()
            localDeviceId = DEFAULT_DEVICE_ID
            sessionSetupLocks.clear()
            // BUG 6 fix: 重建 protocolStore 以使用新的 identityKeyPair，避免持有旧密钥对
            generateIdentityKeys()
        }
    }

    suspend fun clearLocalState() {
        // 8.49 修复：只清当前账号作用域——库内键均按 user:<accountId>: 前缀、
        // identity_trust 按 accountId 隔离，全表删除会把同库其他账号的 Signal
        // 身份/会话/信任一并销毁。accountId 必须在内存态失效前捕获。
        val accountId = currentUserId
        invalidateInMemoryAccountState()
        if (accountId != null) {
            // 9.236：与 scopedKey/PersistentSignalProtocolStore.prefix() 同一转义，
            // 保证 LIKE ESCAPE 能精确命中本账号作用域的键
            signalKeyDao.deleteKeysWithPrefix("user:${com.maodouchat.data.local.LikeQueryPolicy.escapeForPrefix(accountId)}:")
            identityTrustDao.deleteForAccount(accountId)
        } else {
            // 无账号上下文（未登录/内存态已失效）时退回匿名作用域
            signalKeyDao.deleteKeysWithPrefix("anonymous:")
            identityTrustDao.deleteAllTrust()
        }
        generateIdentityKeys()
    }

    private fun scopedKey(keyType: String): String {
        // 9.236：与 PersistentSignalProtocolStore.prefix() 同一转义，两条写入路径键字面量必须一致
        return currentUserId?.let { "user:${com.maodouchat.data.local.LikeQueryPolicy.escapeForPrefix(it)}:$keyType" } ?: "anonymous:$keyType"
    }

    private fun getOrCreateGroupDistributionId(groupId: String, epoch: Long): String {
        val key = scopedKey("$KEY_GROUP_DISTRIBUTION_PREFIX$groupId")
        val epochKey = scopedKey("$KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX$groupId")
        val saved = signalKeyDao.getKeyBlocking(key)?.keyData
        val savedEpoch = signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull() ?: 0L
        return saved?.takeIf { runCatching { UUID.fromString(it) }.isSuccess && savedEpoch == epoch }
            ?: UUID.randomUUID().toString().also {
                signalKeyDao.insertKeyBlocking(SignalKeyEntity(key, it))
                signalKeyDao.insertKeyBlocking(SignalKeyEntity(epochKey, epoch.toString()))
                saveGroupDistributionMetadata(groupId, GroupDistributionMetadata(epoch = epoch, createdAt = System.currentTimeMillis(), messageCount = 0))
            }
    }

    /** Encrypt-only: never mint; createGroupSenderKeyDistribution owns minting. */
    private fun requireExistingGroupDistributionId(groupId: String, epoch: Long): String {
        val key = scopedKey("$KEY_GROUP_DISTRIBUTION_PREFIX$groupId")
        val epochKey = scopedKey("$KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX$groupId")
        val saved = signalKeyDao.getKeyBlocking(key)?.keyData
        val savedEpoch = signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull() ?: 0L
        val id = saved?.takeIf { runCatching { UUID.fromString(it) }.isSuccess && savedEpoch == epoch }
        return id ?: error("group_sender_key_not_distributed:epoch=$epoch")
    }

    private fun loadGroupDistributionMetadata(groupId: String): GroupDistributionMetadata {
        val key = scopedKey("$KEY_GROUP_DISTRIBUTION_METADATA_PREFIX$groupId")
        val raw = signalKeyDao.getKeyBlocking(key)?.keyData
        return raw?.let {
            runCatching { json.decodeFromString(GroupDistributionMetadata.serializer(), it) }.getOrNull()
        } ?: GroupDistributionMetadata()
    }

    private fun saveGroupDistributionMetadata(groupId: String, metadata: GroupDistributionMetadata) {
        val key = scopedKey("$KEY_GROUP_DISTRIBUTION_METADATA_PREFIX$groupId")
        signalKeyDao.insertKeyBlocking(SignalKeyEntity(key, json.encodeToString(GroupDistributionMetadata.serializer(), metadata)))
    }

    private fun generateLocalDeviceId(): Int {
        return secureRandom.nextInt(MAX_DEVICE_ID - MIN_GENERATED_DEVICE_ID + 1) + MIN_GENERATED_DEVICE_ID
    }

    /**
     * DEFAULT_DEVICE_ID (1) is a legacy alias, not a real device. Discovery lists are
     * confirmed-only; never consume a PENDING ghost device 1 prekey.
     */
    private suspend fun resolveSessionDeviceId(token: String, recipientId: String, deviceId: Int): Int? {
        if (deviceId != DEFAULT_DEVICE_ID) return deviceId
        val discovered = SignalKeyExchange.fetchDevicePreKeyBundles(token, recipientId)
            .getOrNull()
            ?.map { it.deviceId }
            .orEmpty()
        ConfirmedDevicePolicy.resolve(deviceId, discovered)?.let { return it }
        val fallback = SignalKeyExchange.fetchPreKeyBundle(token, recipientId).getOrNull()?.deviceId
        return ConfirmedDevicePolicy.resolve(deviceId, listOfNotNull(fallback))
    }

    private suspend fun uploadKeysWithDeviceIdRecovery(token: String): Result<Unit> {
        var lastResult: Result<Unit> = Result.failure(IllegalStateException("device registration not attempted"))
        repeat(MAX_DEVICE_ID_ALLOCATION_ATTEMPTS) { attempt ->
            lastResult = uploadKeysToServer(token)
            if (lastResult.isSuccess) return lastResult
            val error = lastResult.exceptionOrNull() as? com.maodouchat.network.ApiException
            if (error?.serverCode != "DEVICE_ID_CONFLICT" || attempt == MAX_DEVICE_ID_ALLOCATION_ATTEMPTS - 1) {
                return lastResult
            }
            val previousDeviceId = localDeviceId
            var replacement = generateLocalDeviceId()
            while (replacement == previousDeviceId) replacement = generateLocalDeviceId()
            localDeviceId = replacement
            persistCoreKeys()
            Log.w(TAG, "Signal device id collision for $previousDeviceId; retrying as $replacement")
        }
        return lastResult
    }

    /**
     * 上传公钥到服务器
     */
    private suspend fun uploadKeysToServer(token: String): Result<Unit> {
        return SignalKeyExchange.uploadKeys(token, this)
    }

    enum class IdentityTrustState {
        UNKNOWN,
        TRUSTED,
        VERIFIED,
        CHANGED
    }

    sealed class DecryptResult {
        data class Success(val plaintext: String) : DecryptResult()
        data object UnsupportedEnvelope : DecryptResult()
        data object NotForThisDevice : DecryptResult()
        data object NoSession : DecryptResult()
        data object UntrustedIdentity : DecryptResult()
        data object FutureEpoch : DecryptResult()
        data object Failed : DecryptResult()
        data object Duplicate : DecryptResult()
    }

    data class EncryptedPayload(val type: String, val payload: ByteArray)

    data class SenderKeyDistributionPayload(
        val distributionId: String,
        val message: SenderKeyDistributionMessage,
        val epoch: Long
    )

    data class MultiRecipientDeviceTarget(
        val userId: String,
        val deviceId: Int
    )

    data class MultiRecipientEnvelopePayload(
        val envelope: String,
        val targets: List<MultiRecipientDeviceTarget>
    )

    data class DeviceSafetyState(
        val deviceId: Int,
        val identityKey: String,
        val identityFingerprint: String,
        val trustState: IdentityTrustState,
        val safetyCode: String?,
        val isCurrent: Boolean = false
    )

    @Serializable
    private data class GroupDistributionMetadata(
        val epoch: Long = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val messageCount: Int = 0
    )

    @Serializable
    private data class StoredPreKey(val id: Int, val recordBase64: String)

    private fun identityFingerprint(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    @Serializable
    private data class EncryptedMessageEnvelope(
        val version: Int = ENVELOPE_VERSION,
        val algorithm: String = "signal-v2",
        val senderDeviceId: Int = DEFAULT_DEVICE_ID,
        val recipientDeviceId: Int? = null,
        val ciphertextType: String? = null,
        val payloadType: String? = null,
        val ciphertext: String
    )

    @Serializable
    private data class MultiDeviceMessageEnvelope(
        val version: Int = MULTI_DEVICE_ENVELOPE_VERSION,
        val algorithm: String = ALGORITHM_SIGNAL_MULTI_DEVICE,
        val senderDeviceId: Int = DEFAULT_DEVICE_ID,
        val payloadType: String? = null,
        val entries: List<MultiDeviceMessageEntry>
    )

    @Serializable
    private data class MultiDeviceMessageEntry(
        val recipientUserId: String? = null,
        val recipientDeviceId: Int,
        val ciphertextType: String? = null,
        val ciphertext: String
    )

    @Serializable
    private data class SenderKeyDistributionEnvelope(
        val version: Int = SENDER_KEY_DISTRIBUTION_VERSION,
        val algorithm: String = ALGORITHM_SENDER_KEY_DISTRIBUTION,
        val groupId: String,
        val epoch: Long = 0,
        val senderDeviceId: Int = DEFAULT_DEVICE_ID,
        val distributionId: String,
        val distributionMessage: String
    )

    @Serializable
    private data class SenderKeyMessageEnvelope(
        val version: Int = SENDER_KEY_ENVELOPE_VERSION,
        val algorithm: String = ALGORITHM_SENDER_KEY,
        val groupId: String,
        val epoch: Long = 0,
        val senderDeviceId: Int = DEFAULT_DEVICE_ID,
        val distributionId: String,
        val payloadType: String,
        val ciphertext: String
    )

    private companion object {
        const val TAG = "SignalProtocol"
        const val ENVELOPE_VERSION = 2
        const val MULTI_DEVICE_ENVELOPE_VERSION = 3
        const val SENDER_KEY_ENVELOPE_VERSION = 1
        const val SENDER_KEY_DISTRIBUTION_VERSION = 1
        const val ALGORITHM_SIGNAL = "signal-v2"
        const val ALGORITHM_SIGNAL_MULTI_DEVICE = "signal-multi-device-v1"
        const val ALGORITHM_SENDER_KEY = "signal-sender-key-v1"
        const val ALGORITHM_SENDER_KEY_DISTRIBUTION = "signal-sender-key-distribution-v1"
        const val PAYLOAD_TEXT = "TEXT"
        const val KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX = "group_distribution_epoch:"
        const val KEY_GROUP_DISTRIBUTION_METADATA_PREFIX = "group_distribution_meta:"
        const val MAX_COUNTED_GROUP_MESSAGE_IDS = 2_000
        const val GROUP_SENDER_KEY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val GROUP_SENDER_KEY_MAX_MESSAGES = 1000
        const val DEFAULT_DEVICE_ID = 1
        const val MIN_DEVICE_ID = 1
        const val MIN_GENERATED_DEVICE_ID = 2
        const val MAX_DEVICE_ID = 255
        const val MAX_DEVICE_ID_ALLOCATION_ATTEMPTS = 4
        const val PRE_KEY_COUNT = 50
        /** PreKey 数量低于此阈值时触发运行时补充 */
        const val PRE_KEY_REPLENISH_THRESHOLD = 10
        /** Signed PreKey 轮换周期（天） */
        const val SIGNED_PRE_KEY_ROTATION_DAYS = 7
        /** Signed PreKey 轮换周期（毫秒） */
        val SIGNED_PRE_KEY_ROTATION_MS = SIGNED_PRE_KEY_ROTATION_DAYS * 24L * 60L * 60L * 1_000L
        const val CIPHERTEXT_TYPE_PREKEY = "prekey"
        const val CIPHERTEXT_TYPE_SIGNAL = "signal"
        const val CIPHERTEXT_TYPE_UNKNOWN = "unknown"
        const val KEY_REGISTRATION_ID = "registration_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_IDENTITY_KEY_PAIR = "identity_key_pair"
        const val KEY_SIGNED_PRE_KEY = "signed_pre_key"
        const val KEY_PRE_KEYS = "pre_keys"
        const val KEY_GROUP_DISTRIBUTION_PREFIX = "group_distribution:"
        const val ANONYMOUS_ACCOUNT_ID = "anonymous"
        const val TRUST_VERIFIED = "VERIFIED"
        const val TRUST_CHANGED = "CHANGED"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val secureRandom = SecureRandom()
    }
}
enum class SenderKeyDistOutcome {
    Installed,
    /** Permanently unusable for this device (stale epoch / wrong group / bad format). */
    Skipped,
    /** Unexpected failure; may be retryable after session repair. */
    Failed
}

class NoRecipientDevicesException : IllegalStateException()
