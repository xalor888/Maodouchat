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

fun DeviceRegistry.updateDeviceName(userId: String, deviceId: Int, deviceName: String): Boolean {
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

// 删设备同时吊销其 auth/refresh 会话与推送 token；最后一个已确认设备不允许删。
fun DeviceRegistry.deleteDeviceAndRevokeSessionsGuarded(userId: String, deviceId: Int): DeleteDeviceOutcome = transaction {
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

fun DeviceRegistry.deleteDeviceGuarded(userId: String, deviceId: Int): DeleteDeviceResult =
    deleteDeviceAndRevokeSessionsGuarded(userId, deviceId).result
