package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.signal.libsignal.protocol.ecc.Curve
import java.util.Base64

// 新设备审批：批准设备用自身 identity key 对载荷签名，自批/越权/伪造签名一律拒绝。
fun DeviceRegistry.confirmDevice(userId: String, deviceId: Int, approverDeviceId: Int, signatureBase64: String): ConfirmDeviceResult {
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

internal fun DeviceRegistry.verifyDeviceConfirmationProof(
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
