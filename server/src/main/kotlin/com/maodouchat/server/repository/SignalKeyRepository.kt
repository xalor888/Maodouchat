package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
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
class SignalKeyRepository {


    /** Must run inside an open transaction. */
    private fun lockUserRow(userId: String) {
        com.maodouchat.server.db.Users.selectAll()
            .where { com.maodouchat.server.db.Users.id eq userId }
            .forUpdate()
            .firstOrNull()
    }

    fun touchDevice(userId: String, deviceId: Int, deviceName: String? = null) {
        val normalizedName = deviceName?.trim()?.take(50)?.takeIf { it.isNotBlank() }
        transaction {
            // 串行化同一用户的设备注册，避免双设备同时 initialDeviceStatus → 双 CONFIRMED
            com.maodouchat.server.db.Users.selectAll()
                .where { com.maodouchat.server.db.Users.id eq userId }
                .forUpdate()
                .firstOrNull()
            val existing = SignalDevices.selectAll().where {
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
            }.forUpdate().firstOrNull()
            val now = System.currentTimeMillis()
            if (existing == null) {
                val status = initialDeviceStatus(userId, deviceId)
                // 已持有用户行锁，同 user 设备注册串行；禁止 catch 唯一冲突后同事务继续写（PG abort）
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
    }

    fun confirmDevice(userId: String, deviceId: Int, approverDeviceId: Int, signatureBase64: String): ConfirmDeviceResult {
        val normalizedSignature = signatureBase64.trim()
        if (deviceId !in 1..255 || approverDeviceId !in 1..255) return ConfirmDeviceResult.INVALID
        if (normalizedSignature.isBlank() || normalizedSignature.length > 256) return ConfirmDeviceResult.INVALID_PROOF
        return transaction {
            // Serialize with touchDevice / deleteDeviceGuarded on the same user row
            com.maodouchat.server.db.Users.selectAll()
                .where { com.maodouchat.server.db.Users.id eq userId }
                .forUpdate()
                .firstOrNull()

            val target = SignalDevices.selectAll().where {
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
            }.forUpdate().firstOrNull() ?: return@transaction ConfirmDeviceResult.NOT_FOUND

            if (target[SignalDevices.status] == DEVICE_STATUS_CONFIRMED) {
                return@transaction ConfirmDeviceResult.ALREADY_CONFIRMED
            }

            val approver = SignalDevices.selectAll().where {
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq approverDeviceId)
            }.forUpdate().firstOrNull() ?: return@transaction ConfirmDeviceResult.APPROVER_NOT_TRUSTED
            if (approver[SignalDevices.status] != DEVICE_STATUS_CONFIRMED) {
                return@transaction ConfirmDeviceResult.APPROVER_NOT_TRUSTED
            }
            if (deviceId == approverDeviceId) {
                return@transaction ConfirmDeviceResult.APPROVER_NOT_TRUSTED
            }
            val targetIdentityKey = getSingleKeyInternal(userId, deviceId, "identity_key")
                ?: return@transaction ConfirmDeviceResult.NOT_FOUND
            val approverIdentityKey = getSingleKeyInternal(userId, approverDeviceId, "identity_key")
                ?: return@transaction ConfirmDeviceResult.APPROVER_NOT_TRUSTED
            if (!verifyDeviceConfirmationProof(
                    userId = userId,
                    approverDeviceId = approverDeviceId,
                    targetDeviceId = deviceId,
                    targetIdentityKeyBase64 = targetIdentityKey,
                    approverIdentityKeyBase64 = approverIdentityKey,
                    signatureBase64 = normalizedSignature
                )
            ) {
                return@transaction ConfirmDeviceResult.INVALID_PROOF
            }

            val now = System.currentTimeMillis()
            SignalDevices.update({
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
            }) {
                it[SignalDevices.status] = DEVICE_STATUS_CONFIRMED
                it[SignalDevices.confirmedAt] = now
                it[SignalDevices.confirmedByDeviceId] = approverDeviceId
                it[SignalDevices.lastSeenAt] = now
            }
            ConfirmDeviceResult.CONFIRMED
        }
    }

    fun updateDeviceName(userId: String, deviceId: Int, deviceName: String): Boolean {
        val normalizedName = deviceName.trim().take(50).takeIf { it.isNotBlank() } ?: return false
        return transaction {
            com.maodouchat.server.db.Users.selectAll()
                .where { com.maodouchat.server.db.Users.id eq userId }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction false
            val hasIdentityKey = SignalKeys.selectAll().where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "identity_key")
            }.firstOrNull() != null
            if (!hasIdentityKey) return@transaction false
            SignalDevices.update({
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
            }) {
                it[SignalDevices.deviceName] = normalizedName
                it[SignalDevices.lastSeenAt] = System.currentTimeMillis()
            } > 0
        }
    }

    fun getIdentityKey(userId: String, deviceId: Int): String? = getSingleKey(userId, deviceId, "identity_key")


    fun getDeviceId(userId: String, deviceId: Int): Int? = getSingleKey(userId, deviceId, "device_id")?.toIntOrNull()



    fun isDeviceConfirmed(userId: String, deviceId: Int): Boolean = transaction {
        SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and
                (SignalDevices.deviceId eq deviceId) and
                (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
        }.firstOrNull() != null
    }

    fun getDeviceIds(userId: String, confirmedOnly: Boolean = true): List<Int> {
        val keyDeviceIds = transaction {
            SignalKeys.select(SignalKeys.deviceId)
            .where { SignalKeys.userId eq userId }
            .withDistinct()
            .map { it[SignalKeys.deviceId] }
            .distinct()
            .sorted()
        }
        if (!confirmedOnly) return keyDeviceIds
        val confirmed = transaction {
            SignalDevices.selectAll().where {
                (SignalDevices.userId eq userId) and (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
            }.map { it[SignalDevices.deviceId] }.toSet()
        }
        return keyDeviceIds.filter { it in confirmed }
    }

    /** Confirmed devices that have uploaded key material, fetched in one transaction for group fanout. */
    fun getConfirmedDeviceTargets(userIds: Collection<String>): Set<Pair<String, Int>> {
        val normalizedIds = userIds.filter(String::isNotBlank).distinct()
        if (normalizedIds.isEmpty()) return emptySet()
        return transaction {
            val confirmed = SignalDevices.select(SignalDevices.userId, SignalDevices.deviceId)
                .where {
                    (SignalDevices.userId inList normalizedIds) and
                        (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
                }
                .map { it[SignalDevices.userId] to it[SignalDevices.deviceId] }
                .toSet()
            val devicesWithKeys = SignalKeys.select(SignalKeys.userId, SignalKeys.deviceId, SignalKeys.keyType)
                .where { SignalKeys.userId inList normalizedIds }
                .groupBy { it[SignalKeys.userId] to it[SignalKeys.deviceId] }
                .filterValues { rows ->
                    val types = rows.map { it[SignalKeys.keyType] }.toSet()
                    REQUIRED_BUNDLE_KEY_TYPES.all(types::contains)
                }
                .keys
                .toSet()
            confirmed intersect devicesWithKeys
        }
    }

    fun getDeviceInfos(userId: String, currentDeviceId: Int? = null, includePending: Boolean = false): List<DeviceInfo> {
        val metadata = transaction {
            SignalDevices.selectAll()
                .where { SignalDevices.userId eq userId }
                .associate { row ->
                    row[SignalDevices.deviceId] to DeviceMetadata(
                        deviceName = row[SignalDevices.deviceName],
                        status = row[SignalDevices.status],
                        confirmedAt = row[SignalDevices.confirmedAt],
                        confirmedByDeviceId = row[SignalDevices.confirmedByDeviceId],
                        lastSeenAt = row[SignalDevices.lastSeenAt]
                    )
                }
        }
        return getDeviceIds(userId, confirmedOnly = false).mapNotNull { deviceId ->
            val identityKey = getIdentityKey(userId, deviceId) ?: return@mapNotNull null
            val meta = metadata[deviceId]
            // 无 SignalDevices 行的设备视为 PENDING，避免对端向未确认设备 fan-out
            val status = meta?.status ?: DEVICE_STATUS_PENDING
            if (!includePending && status != DEVICE_STATUS_CONFIRMED) return@mapNotNull null
            DeviceInfo(
                userId = userId,
                deviceId = deviceId,
                deviceName = meta?.deviceName ?: "设备 #$deviceId",
                identityKey = identityKey,
                lastSeenAt = meta?.lastSeenAt,
                isCurrent = currentDeviceId == deviceId,
                status = status,
                confirmedAt = meta?.confirmedAt,
                confirmedByDeviceId = meta?.confirmedByDeviceId
            )
        }
    }

    /**
     * 删除设备。与「至少保留一个已确认设备」在同一事务内、用户行锁下判定，
     * 避免双设备并发删除把最后一个 CONFIRMED 清光。
     */
    enum class DeleteDeviceResult { DELETED, NOT_FOUND, LAST_CONFIRMED }

    data class DeleteDeviceOutcome(
        val result: DeleteDeviceResult,
        val revokedSessionIds: Set<String> = emptySet()
    )

    fun deleteDeviceAndRevokeSessionsGuarded(userId: String, deviceId: Int): DeleteDeviceOutcome = transaction {
        com.maodouchat.server.db.Users.selectAll()
            .where { com.maodouchat.server.db.Users.id eq userId }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction DeleteDeviceOutcome(DeleteDeviceResult.NOT_FOUND)

        val hasKeys = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and (SignalKeys.deviceId eq deviceId)
        }.firstOrNull() != null
        val hasDeviceRow = SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
        }.firstOrNull() != null
        if (!hasKeys && !hasDeviceRow) return@transaction DeleteDeviceOutcome(DeleteDeviceResult.NOT_FOUND)

        val confirmedIds = SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
        }.map { it[SignalDevices.deviceId] }.toSet()
        val targetConfirmed = deviceId in confirmedIds
        // 只保护最后一个已确认设备；无密钥的 PENDING 残行不能因此变得不可删除。
        if (targetConfirmed && confirmedIds.size <= 1) {
            return@transaction DeleteDeviceOutcome(DeleteDeviceResult.LAST_CONFIRMED)
        }

        val now = System.currentTimeMillis()
        // 8.39：只吊销「明确绑定该设备」的会话——此前 `signalDeviceId IS NULL` 分支会把
        // 该用户所有未绑定设备（新登录尚未上传密钥包 / 历史遗留）的会话一起吊销，连带登出其他设备
        val revokedSessionIds = AuthSessions.selectAll().where {
            (AuthSessions.userId eq userId) and
                (AuthSessions.signalDeviceId eq deviceId) and
                AuthSessions.revokedAt.isNull()
        }.forUpdate().map { it[AuthSessions.id] }.toSet()
        revokedSessionIds.forEach { sessionId ->
            AuthSessions.update({
                (AuthSessions.id eq sessionId) and AuthSessions.revokedAt.isNull()
            }) {
                it[revokedAt] = now
                it[updatedAt] = now
            }
            RefreshTokens.update({
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    RefreshTokens.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
            PushTokens.deleteWhere {
                (PushTokens.userId eq userId) and (PushTokens.authSessionId eq sessionId)
            }
        }
        SignalDevices.deleteWhere {
            (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
        }
        SignalKeys.deleteWhere {
            (SignalKeys.userId eq userId) and (SignalKeys.deviceId eq deviceId)
        }
        DeleteDeviceOutcome(DeleteDeviceResult.DELETED, revokedSessionIds)
    }

    fun deleteDeviceGuarded(userId: String, deviceId: Int): DeleteDeviceResult =
        deleteDeviceAndRevokeSessionsGuarded(userId, deviceId).result

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

            val identityKey = getSingleKeyInternal(userId, deviceId, "identity_key") ?: return@transaction null
            val registrationId = getSingleKeyInternal(userId, deviceId, "registration_id")?.toIntOrNull() ?: return@transaction null
            val actualDeviceId = getSingleKeyInternal(userId, deviceId, "device_id")?.toIntOrNull() ?: deviceId
            val signedPreKey = getSignedPreKeyInternal(userId, deviceId) ?: return@transaction null
            val signedPreKeySignature = getSingleKeyInternal(userId, deviceId, "signed_pre_key_signature") ?: return@transaction null
            val preKey = when {
                !includeOneTimePreKey -> null
                consumeOneTimePreKey -> consumePreKeyInternal(userId, deviceId)
                else -> peekPreKeyInternal(userId, deviceId)
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

    /** Internal helper — must be called within an existing transaction. */
    private fun getSignedPreKeyInternal(userId: String, deviceId: Int): KeyData? {
        return SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "signed_pre_key")
        }.firstOrNull()?.let {
            val keyId = it[SignalKeys.keyId] ?: return@let null
            KeyData(keyId, it[SignalKeys.keyData])
        }
    }

    /** Internal helper — must be called within an existing transaction. */
    private fun consumePreKeyInternal(userId: String, deviceId: Int): KeyData? {
        val key = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "pre_key")
        }
            .orderBy(SignalKeys.createdAt to SortOrder.ASC)
            .limit(1)
            .forUpdate()
            .firstOrNull() ?: return null

        val keyId = key[SignalKeys.keyId] ?: return null
        val keyData = key[SignalKeys.keyData]
        SignalKeys.update({
            (SignalKeys.id eq key[SignalKeys.id]) and
                (SignalKeys.keyType eq PRE_KEY_TYPE)
        }) {
            it[keyType] = CONSUMED_PRE_KEY_TYPE
        }
        return KeyData(keyId, keyData)
    }

    /** Internal helper — must be called within an existing transaction. */
    private fun peekPreKeyInternal(userId: String, deviceId: Int): KeyData? {
        return SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "pre_key")
        }
            .orderBy(SignalKeys.createdAt to SortOrder.ASC)
            .limit(1)
            .firstOrNull()?.let {
                val keyId = it[SignalKeys.keyId] ?: return@let null
                KeyData(keyId, it[SignalKeys.keyData])
            }
    }

    private fun peekPreKey(userId: String, deviceId: Int): KeyData? {
        return transaction {
            SignalKeys.selectAll().where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "pre_key")
            }
                .orderBy(SignalKeys.createdAt to SortOrder.ASC)
                .limit(1)
                .firstOrNull()?.let {
                    val keyId = it[SignalKeys.keyId] ?: return@let null
                    KeyData(keyId, it[SignalKeys.keyData])
                }
        }
    }

    /**
     * Full key package in one transaction so SPK + signature never diverge mid-upload.
     * @return the upload result, including session and device-ID conflicts.
     */
    enum class UploadKeyPackageResult { UPLOADED, SESSION_CONFLICT, DEVICE_ID_CONFLICT }

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

            val existingIdentity = SignalKeys.selectAll().where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "identity_key")
            }.forUpdate().firstOrNull()?.get(SignalKeys.keyData)
            if (existingIdentity != null && existingIdentity != identityKey) {
                return@transaction UploadKeyPackageResult.DEVICE_ID_CONFLICT
            }
            if (boundDeviceId == null) {
                AuthSessions.update({ AuthSessions.id eq authSessionId }) {
                    it[signalDeviceId] = deviceId
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            upsertSingleKeyInTx(userId, deviceId, "identity_key", identityKey)
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

            preKeys.forEach { uploadPreKeyInTx(userId, deviceId, it) }

            touchDeviceInTx(userId, deviceId, deviceName)
            UploadKeyPackageResult.UPLOADED
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

    /** Must run inside an open transaction that already holds the user row lock. */
    private fun uploadPreKeyInTx(userId: String, deviceId: Int, preKey: PreKeyUpload) {
        val existing = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyId eq preKey.keyId) and
                (SignalKeys.keyType inList PRE_KEY_STATES)
        }.forUpdate().firstOrNull()
        if (existing != null) return

        SignalKeys.insert {
            it[SignalKeys.id] = "sk_${UUID.randomUUID()}"
            it[SignalKeys.userId] = userId
            it[SignalKeys.deviceId] = deviceId
            it[keyType] = PRE_KEY_TYPE
            it[keyData] = preKey.publicKeyBase64
            it[SignalKeys.keyId] = preKey.keyId
            it[createdAt] = System.currentTimeMillis()
        }
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
    fun purgeConsumedPreKeys(retentionDays: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            SignalKeys.deleteWhere {
                (SignalKeys.keyType eq CONSUMED_PRE_KEY_TYPE) and (SignalKeys.createdAt less cutoff)
            }
        }
    }

    data class PreKeyUpload(val keyId: Int, val publicKeyBase64: String)
    data class KeyData(val keyId: Int, val publicKeyBase64: String)
    private data class DeviceMetadata(
        val deviceName: String,
        val status: String,
        val confirmedAt: Long?,
        val confirmedByDeviceId: Int?,
        val lastSeenAt: Long?
    )
    data class DeviceInfo(
        val userId: String,
        val deviceId: Int,
        val deviceName: String,
        val identityKey: String,
        val lastSeenAt: Long? = null,
        val isCurrent: Boolean = false,
        val status: String = DEVICE_STATUS_CONFIRMED,
        val confirmedAt: Long? = null,
        val confirmedByDeviceId: Int? = null
    )
    data class DeviceBundle(
        val userId: String,
        val registrationId: Int,
        val deviceId: Int,
        val identityKey: String,
        val signedPreKeyId: Int,
        val signedPreKey: String,
        val signedPreKeySignature: String,
        val preKeyId: Int?,
        val preKey: String?
    )

    enum class ConfirmDeviceResult {
        CONFIRMED,
        ALREADY_CONFIRMED,
        NOT_FOUND,
        APPROVER_NOT_TRUSTED,
        INVALID_PROOF,
        INVALID
    }

    companion object {
        const val DEVICE_STATUS_CONFIRMED = "CONFIRMED"
        const val DEVICE_STATUS_PENDING = "PENDING"
        private const val PRE_KEY_TYPE = "pre_key"
        private const val CONSUMED_PRE_KEY_TYPE = "consumed_pre_key"
        private val PRE_KEY_STATES = listOf(PRE_KEY_TYPE, CONSUMED_PRE_KEY_TYPE)
        private val REQUIRED_BUNDLE_KEY_TYPES = setOf(
            "identity_key",
            "registration_id",
            "signed_pre_key",
            "signed_pre_key_signature"
        )
    }
}
