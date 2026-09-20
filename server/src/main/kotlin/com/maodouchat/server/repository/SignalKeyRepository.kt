package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.service.IdentitySecurityEventPolicy
import com.maodouchat.server.service.IdentitySecurityEventRecorder
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.signal.libsignal.protocol.ecc.Curve
import java.util.Base64
import java.util.UUID

/**
 * Signal 密钥仓库
 *
 * 管理用户的加密公钥（身份密钥、签名预密钥、一次性预密钥），按设备隔离。
 */
class SignalKeyRepository(
    val deviceRegistry: DeviceRegistry = DeviceRegistry(),
    val identityKeyStore: IdentityKeyStore = IdentityKeyStore(),
    val signedPreKeyStore: SignedPreKeyStore = SignedPreKeyStore(),
    val preKeyStore: PreKeyStore = PreKeyStore()
) : EncryptableDeviceDirectory by deviceRegistry {

    /** Must run inside an open transaction. */
    private fun lockUserRow(userId: String) {
        com.maodouchat.server.db.Users.selectAll()
            .where { com.maodouchat.server.db.Users.id eq userId }
            .forUpdate()
            .firstOrNull()
    }

    fun touchDevice(userId: String, deviceId: Int, deviceName: String? = null) =
        deviceRegistry.touchDevice(userId, deviceId, deviceName)

    fun confirmDevice(userId: String, deviceId: Int, approverDeviceId: Int, signatureBase64: String): ConfirmDeviceResult =
        deviceRegistry.confirmDevice(userId, deviceId, approverDeviceId, signatureBase64)

    fun updateDeviceName(userId: String, deviceId: Int, deviceName: String): Boolean =
        deviceRegistry.updateDeviceName(userId, deviceId, deviceName)

    fun getIdentityKey(userId: String, deviceId: Int): String? =
        identityKeyStore.getIdentityKey(userId, deviceId)

    fun getDeviceId(userId: String, deviceId: Int): Int? =
        identityKeyStore.getDeviceId(userId, deviceId)

    fun isAuthSessionBoundToDevice(userId: String, authSessionId: String, deviceId: Int): Boolean =
        deviceRegistry.isAuthSessionBoundToDevice(userId, authSessionId, deviceId)

    fun getDeviceIdForAuthSession(authSessionId: String): Int? =
        deviceRegistry.getDeviceIdForAuthSession(authSessionId)

    fun getDeviceInfos(userId: String, currentDeviceId: Int? = null, includePending: Boolean = false): List<DeviceInfo> =
        deviceRegistry.getDeviceInfos(userId, currentDeviceId, includePending)

    fun deleteDeviceAndRevokeSessionsGuarded(userId: String, deviceId: Int): DeleteDeviceOutcome =
        deviceRegistry.deleteDeviceAndRevokeSessionsGuarded(userId, deviceId)

    fun deleteDeviceGuarded(userId: String, deviceId: Int): DeleteDeviceResult =
        deviceRegistry.deleteDeviceGuarded(userId, deviceId)

    fun getBundle(
        userId: String,
        deviceId: Int,
        consumeOneTimePreKey: Boolean = true,
        includeOneTimePreKey: Boolean = true,
    ): DeviceBundle? {
        // 整个 bundle 获取放在单一事务中，避免多次独立事务之间设备密钥被并发更新导致数据不一致。
        // 用户行锁与上传/删设备共用，保证 bundle 来自同一版本，且只向已确认设备发放。
        return transaction {
            lockUserRow(userId)
            val confirmed = SignalDevices.selectAll().where {
                (SignalDevices.userId eq userId) and
                    (SignalDevices.deviceId eq deviceId) and
                    (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
            }.forUpdate().firstOrNull() != null
            if (!confirmed) return@transaction null

            val identityKey = identityKeyStore.getIdentityKey(userId, deviceId) ?: return@transaction null
            val registrationId = getSingleKeyInternal(userId, deviceId, "registration_id")?.toIntOrNull() ?: return@transaction null
            val actualDeviceId = identityKeyStore.getDeviceId(userId, deviceId) ?: deviceId
            val signedPreKey = signedPreKeyStore.getSignedPreKey(userId, deviceId) ?: return@transaction null
            val signedPreKeySignature = getSingleKeyInternal(userId, deviceId, "signed_pre_key_signature") ?: return@transaction null
            val preKey = when {
                !includeOneTimePreKey -> null
                consumeOneTimePreKey -> preKeyStore.consumePreKey(userId, deviceId)
                else -> preKeyStore.peekPreKey(userId, deviceId)
            }
            DeviceBundle(
                userId = userId,
                registrationId = registrationId,
                deviceId = actualDeviceId,
                identityKey = identityKey,
                signedPreKeyId = signedPreKey.keyId,
                signedPreKey = signedPreKey.publicKeyBase64,
                signedPreKeySignature = signedPreKeySignature,
                preKeyId = preKey?.keyId,
                preKey = preKey?.publicKeyBase64
            )
        }
    }

    /** Internal helper — must be called within an existing transaction. */
    private fun getSingleKeyInternal(userId: String, deviceId: Int, type: String): String? {
        return SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq type)
        }.firstOrNull()?.get(SignalKeys.keyData)
    }

    /**
     * Full key package in one transaction so SPK + signature never diverge mid-upload.
     * @return the upload result, including session, device-ID, and identity conflicts.
     */
    enum class UploadKeyPackageResult {
        UPLOADED,
        SESSION_CONFLICT,
        DEVICE_ID_CONFLICT,
        DEVICE_IDENTITY_MISMATCH,
        INVALID_SIGNATURE,
        INVALID_PRE_KEY,
    }

    fun uploadKeyPackage(
        userId: String,
        authSessionId: String,
        deviceId: Int,
        identityKey: String,
        registrationId: Int,
        signedPreKeyId: Int,
        signedPreKey: String,
        signedPreKeySignature: String,
        preKeys: List<PreKeyUpload>,
        deviceName: String? = null,
    ): UploadKeyPackageResult {
        // 9.298：上传前验签——signedPreKey 签名必须用 identityKey 验证通过。
        // 实测事故：旧版客户端 identity 重生后残留旧 SPK 上传，坏 bundle 入库后所有
        // 与该用户建会话的对端永远报「密钥包无效」（发图/发消息全挂）。入库前拦住
        if (!verifyKeyPackageSignature(identityKey, signedPreKey, signedPreKeySignature)) {
            return UploadKeyPackageResult.INVALID_SIGNATURE
        }
        // Validate every one-time public key before opening the write transaction.  The route
        // performs shape checks as well, but repository callers (jobs/tests) must get the same
        // deterministic result and, importantly, must not leave a partially published bundle.
        if (!preKeys.all(::isValidPreKeyUpload) || hasConflictingPreKeyIds(preKeys)) {
            return UploadKeyPackageResult.INVALID_PRE_KEY
        }
        return transaction {
            com.maodouchat.server.db.Users.selectAll()
                .where { com.maodouchat.server.db.Users.id eq userId }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction UploadKeyPackageResult.SESSION_CONFLICT

            val authSession = AuthSessions.selectAll().where {
                (AuthSessions.id eq authSessionId) and
                    (AuthSessions.userId eq userId)
            }.forUpdate().firstOrNull()
                ?: return@transaction UploadKeyPackageResult.SESSION_CONFLICT
            if (authSession[AuthSessions.revokedAt] != null) {
                return@transaction UploadKeyPackageResult.SESSION_CONFLICT
            }
            val boundDeviceId = authSession[AuthSessions.signalDeviceId]
            if (boundDeviceId != null && boundDeviceId != deviceId) {
                return@transaction UploadKeyPackageResult.SESSION_CONFLICT
            }
            val existingIdentity = SignalKeys.selectAll().where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "identity_key")
            }.forUpdate().firstOrNull()?.get(SignalKeys.keyData)
            // An authenticated session is already pinned to this device slot.  A different
            // identity on that slot is a stale/corrupt local store, not a free identity-migration
            // candidate: returning IDENTITY_MISMATCH would make the client switch slots even
            // though every subsequent upload is rejected by the session binding.
            if (boundDeviceId != null && existingIdentity != null && existingIdentity != identityKey) {
                recordIdentityMismatchAndRevokeSessionInTx(
                    userId = userId,
                    deviceId = deviceId,
                    authSessionId = authSessionId,
                )
                return@transaction UploadKeyPackageResult.SESSION_CONFLICT
            }
            if (existingIdentity != null && existingIdentity != identityKey) {
                recordIdentityMismatchAndRevokeSessionInTx(
                    userId = userId,
                    deviceId = deviceId,
                    authSessionId = authSessionId,
                )
                return@transaction UploadKeyPackageResult.DEVICE_IDENTITY_MISMATCH
            }
            if (boundDeviceId == null) {
                val occupiedByAnotherSession = AuthSessions.selectAll().where {
                    (AuthSessions.userId eq userId) and
                        (AuthSessions.signalDeviceId eq deviceId) and
                        (AuthSessions.id neq authSessionId) and
                        AuthSessions.revokedAt.isNull()
                }.forUpdate().firstOrNull() != null
                if (occupiedByAnotherSession) {
                    return@transaction UploadKeyPackageResult.DEVICE_ID_CONFLICT
                }
            }
            if (boundDeviceId == null) {
                AuthSessions.update({ AuthSessions.id eq authSessionId }) {
                    it[signalDeviceId] = deviceId
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            val identityFirstPublish = existingIdentity == null
            upsertSingleKeyInTx(userId, deviceId, "identity_key", identityKey)
            if (identityFirstPublish) {
                IdentitySecurityEventRecorder.recordInTx(
                    userId = userId,
                    action = IdentitySecurityEventPolicy.ACTION_PUBLISHED,
                    detail = IdentitySecurityEventPolicy.publishedDetail(deviceId),
                )
            }
            upsertSingleKeyInTx(userId, deviceId, "registration_id", registrationId.toString())
            upsertSingleKeyInTx(userId, deviceId, "device_id", deviceId.toString())

            SignalKeys.deleteWhere {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "signed_pre_key")
            }
            SignalKeys.insert {
                it[SignalKeys.id] = "sk_${UUID.randomUUID()}"
                it[SignalKeys.userId] = userId
                it[SignalKeys.deviceId] = deviceId
                it[keyType] = "signed_pre_key"
                it[keyData] = signedPreKey
                it[SignalKeys.keyId] = signedPreKeyId
                it[createdAt] = System.currentTimeMillis()
            }
            upsertSingleKeyInTx(userId, deviceId, "signed_pre_key_signature", signedPreKeySignature)

            preKeys.forEach { preKeyStore.uploadPreKeyInTx(userId, deviceId, it) }

            touchDeviceInTx(userId, deviceId, deviceName)
            UploadKeyPackageResult.UPLOADED
        }
    }

    /**
     * B03：同槽身份不一致 → 审计事件 + 吊销当前 auth session（会话风险收敛）。
     * 必须在已持有用户行锁的事务内调用。
     */
    private fun recordIdentityMismatchAndRevokeSessionInTx(
        userId: String,
        deviceId: Int,
        authSessionId: String,
    ) {
        val now = System.currentTimeMillis()
        IdentitySecurityEventRecorder.recordInTx(
            userId = userId,
            action = IdentitySecurityEventPolicy.ACTION_MISMATCH,
            detail = IdentitySecurityEventPolicy.mismatchDetail(deviceId, authSessionId),
            nowMs = now,
        )
        if (IdentitySecurityEventPolicy.shouldRevokeAuthSessionOnMismatch()) {
            AuthSessions.update({ AuthSessions.id eq authSessionId }) {
                it[revokedAt] = now
                it[updatedAt] = now
            }
            RefreshTokens.update({ RefreshTokens.sessionId eq authSessionId }) {
                it[revokedAt] = now
            }
        }
    }

    /** Must run inside an open transaction that already holds a user-scoped lock. */
    private fun upsertSingleKeyInTx(userId: String, deviceId: Int, type: String, data: String) {
        SignalKeys.deleteWhere {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq type)
        }
        SignalKeys.insert {
            it[SignalKeys.id] = "sk_${UUID.randomUUID()}"
            it[SignalKeys.userId] = userId
            it[SignalKeys.deviceId] = deviceId
            it[keyType] = type
            it[keyData] = data
            it[createdAt] = System.currentTimeMillis()
        }
    }

    /** Validate the actual Curve point, not merely that the field looks like Base64. */
    private fun isValidPreKeyUpload(preKey: PreKeyUpload): Boolean =
        preKey.keyId in 1..16_777_215 &&
            runCatching {
                val encoded = Base64.getDecoder().decode(preKey.publicKeyBase64)
                Curve.decodePoint(encoded, 0)
            }.isSuccess

    /** A request containing one id with two different public keys is nondeterministic. */
    private fun hasConflictingPreKeyIds(preKeys: List<PreKeyUpload>): Boolean =
        preKeys.groupBy { it.keyId }.values.any { entries ->
            entries.map { it.publicKeyBase64 }.distinct().size > 1
        }

    /** Must run inside an open transaction that already holds a user-scoped lock. */
    private fun touchDeviceInTx(userId: String, deviceId: Int, deviceName: String? = null) {
        val normalizedName = deviceName?.trim()?.take(50)?.takeIf { it.isNotBlank() }
        val existing = SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
        }.forUpdate().firstOrNull()
        val now = System.currentTimeMillis()
        if (existing == null) {
            val status = initialDeviceStatus(userId, deviceId)
            // 调用方已持用户行锁；禁止 catch 唯一冲突后同事务继续写
            SignalDevices.insert {
                it[SignalDevices.userId] = userId
                it[SignalDevices.deviceId] = deviceId
                it[SignalDevices.deviceName] = normalizedName ?: "我的设备"
                it[SignalDevices.status] = status
                if (status == DEVICE_STATUS_CONFIRMED) {
                    it[SignalDevices.confirmedAt] = now
                    it[SignalDevices.confirmedByDeviceId] = deviceId
                }
                it[SignalDevices.createdAt] = now
                it[SignalDevices.lastSeenAt] = now
            }
        } else {
            SignalDevices.update({
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
            }) {
                normalizedName?.let { name -> it[SignalDevices.deviceName] = name }
                it[SignalDevices.lastSeenAt] = now
            }
        }
    }

    private fun getSingleKey(userId: String, deviceId: Int, type: String): String? {
        return transaction {
            SignalKeys.selectAll().where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq type)
            }.firstOrNull()?.get(SignalKeys.keyData)
        }
    }

    /** 9.298：密钥包上传验签——identity 验 signedPreKey，格式/签名任一异常都拒绝。 */
    private fun verifyKeyPackageSignature(
        identityKeyBase64: String,
        signedPreKeyBase64: String,
        signatureBase64: String
    ): Boolean = runCatching {
        val identityPublic = Curve.decodePoint(Base64.getDecoder().decode(identityKeyBase64), 0)
        val signedPreKeyPublic = Curve.decodePoint(Base64.getDecoder().decode(signedPreKeyBase64), 0)
        val signature = Base64.getDecoder().decode(signatureBase64)
        Curve.verifySignature(identityPublic, signedPreKeyPublic.serialize(), signature)
    }.getOrDefault(false)

    private fun verifyDeviceConfirmationProof(
        userId: String,
        approverDeviceId: Int,
        targetDeviceId: Int,
        targetIdentityKeyBase64: String,
        approverIdentityKeyBase64: String,
        signatureBase64: String
    ): Boolean = runCatching {
        val publicKey = Curve.decodePoint(Base64.getDecoder().decode(approverIdentityKeyBase64), 0)
        val signature = Base64.getDecoder().decode(signatureBase64)
        val payload = buildString {
            append("maodouchat-device-confirm:v1\n")
            append(userId)
            append('\n')
            append(approverDeviceId)
            append('\n')
            append(targetDeviceId)
            append('\n')
            append(targetIdentityKeyBase64)
        }.toByteArray(Charsets.UTF_8)
        Curve.verifySignature(publicKey, payload, signature)
    }.getOrDefault(false)

    /**
     * Must run inside an open transaction that already holds a user-scoped lock
     * (see [touchDevice]) so concurrent first-device registrations cannot both auto-confirm.
     */
    private fun initialDeviceStatus(userId: String, deviceId: Int): String {
        val hasConfirmedDevice = SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
        }.forUpdate().firstOrNull() != null
        if (hasConfirmedDevice) return DEVICE_STATUS_PENDING

        val otherDeviceRow = SignalDevices.selectAll()
            .where { (SignalDevices.userId eq userId) and (SignalDevices.deviceId neq deviceId) }
            .forUpdate()
            .limit(1)
            .firstOrNull()
        if (otherDeviceRow != null) return DEVICE_STATUS_PENDING

        val existingOtherKeys = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and (SignalKeys.deviceId neq deviceId)
        }.limit(1).firstOrNull()
        // 已有其它设备密钥但尚无设备行：不能再 auto-confirm，否则双设备同时注册会双 CONFIRMED
        return if (existingOtherKeys == null) DEVICE_STATUS_CONFIRMED else DEVICE_STATUS_PENDING
    }

    /**
     * 清理已消费 prekey（consumed_pre_key）超过保留期的行。
     * 消费后保留一小段窗口以容忍乱序/重放，超期后删除防止无限累积。
     * 由 Routing.kt 的周期清理循环调用；默认保留 30 天。
     */
    fun purgeConsumedPreKeys(retentionDays: Int = 30): Int =
        preKeyStore.purgeConsumedPreKeys(retentionDays)

    private data class DeviceMetadata(
        val deviceName: String,
        val status: String,
        val confirmedAt: Long?,
        val confirmedByDeviceId: Int?,
        val lastSeenAt: Long?
    )
}
