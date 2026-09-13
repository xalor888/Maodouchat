package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.signal.libsignal.protocol.ecc.Curve
import java.util.Base64

/**
 * 设备注册与生命周期管理服务 (B03)。
 * 负责设备状态（PENDING / CONFIRMED / REVOKED）、新设备审批、名称更新与会话绑定销毁。
 */
class DeviceRegistry(
    private val identityKeyProvider: (userId: String, deviceId: Int) -> String? = { userId, deviceId ->
        transaction {
            SignalKeys.select(SignalKeys.keyData).where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "identity_key")
            }.firstOrNull()?.get(SignalKeys.keyData)
        }
    }
) : EncryptableDeviceDirectory {

    fun touchDevice(userId: String, deviceId: Int, deviceName: String? = null) {
        val normalizedName = deviceName?.trim()?.take(50)?.takeIf { it.isNotBlank() }
        transaction {
            Users.selectAll()
                .where { Users.id eq userId }
                .forUpdate()
                .firstOrNull()
            val existing = SignalDevices.selectAll().where {
                (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
            }.forUpdate().firstOrNull()
            val now = System.currentTimeMillis()
            if (existing == null) {
                val status = initialDeviceStatus(userId, deviceId)
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
            Users.selectAll()
                .where { Users.id eq userId }
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
            val targetIdentityKey = identityKeyProvider(userId, deviceId)
                ?: return@transaction ConfirmDeviceResult.NOT_FOUND
            val approverIdentityKey = identityKeyProvider(userId, approverDeviceId)
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
            Users.selectAll()
                .where { Users.id eq userId }
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

    override fun isDeviceConfirmed(userId: String, deviceId: Int): Boolean = transaction {
        SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and
                (SignalDevices.deviceId eq deviceId) and
                (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
        }.firstOrNull() != null
    }

    fun isAuthSessionBoundToDevice(userId: String, authSessionId: String, deviceId: Int): Boolean {
        if (userId.isBlank() || authSessionId.isBlank() || deviceId !in 1..255) return false
        return transaction {
            AuthSessions.selectAll().where {
                (AuthSessions.id eq authSessionId) and
                    (AuthSessions.userId eq userId) and
                    (AuthSessions.signalDeviceId eq deviceId) and
                    AuthSessions.revokedAt.isNull()
            }.firstOrNull() != null
        }
    }

    override fun getDeviceIds(userId: String, confirmedOnly: Boolean): List<Int> {
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

    override fun getConfirmedDeviceTargets(userIds: Collection<String>): Set<Pair<String, Int>> {
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
            val identityKey = identityKeyProvider(userId, deviceId) ?: return@mapNotNull null
            // B03：缺元数据行不再合成 PENDING——由 migration v5 backfillMissingSignalDevices 补齐。
            val meta = metadata[deviceId] ?: return@mapNotNull null
            val status = meta.status
            if (!includePending && status != DEVICE_STATUS_CONFIRMED) return@mapNotNull null
            DeviceInfo(
                userId = userId,
                deviceId = deviceId,
                deviceName = meta.deviceName.ifBlank { "设备 #$deviceId" },
                identityKey = identityKey,
                lastSeenAt = meta.lastSeenAt,
                isCurrent = currentDeviceId == deviceId,
                status = status,
                confirmedAt = meta.confirmedAt,
                confirmedByDeviceId = meta.confirmedByDeviceId
            )
        }
    }

    fun deleteDeviceAndRevokeSessionsGuarded(userId: String, deviceId: Int): DeleteDeviceOutcome = transaction {
        Users.selectAll()
            .where { Users.id eq userId }
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
        if (targetConfirmed && confirmedIds.size <= 1) {
            return@transaction DeleteDeviceOutcome(DeleteDeviceResult.LAST_CONFIRMED)
        }

        val now = System.currentTimeMillis()
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
        com.maodouchat.server.messaging.retention.MailboxRetentionService().purgeRetiredDevice(userId, deviceId)
        DeleteDeviceOutcome(DeleteDeviceResult.DELETED, revokedSessionIds)
    }

    fun deleteDeviceGuarded(userId: String, deviceId: Int): DeleteDeviceResult =
        deleteDeviceAndRevokeSessionsGuarded(userId, deviceId).result

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
        return if (existingOtherKeys == null) DEVICE_STATUS_CONFIRMED else DEVICE_STATUS_PENDING
    }

    private data class DeviceMetadata(
        val deviceName: String,
        val status: String,
        val confirmedAt: Long?,
        val confirmedByDeviceId: Int?,
        val lastSeenAt: Long?
    )

    companion object {
        private val REQUIRED_BUNDLE_KEY_TYPES = setOf(
            "identity_key",
            "registration_id",
            "signed_pre_key",
            "signed_pre_key_signature"
        )
    }
}
