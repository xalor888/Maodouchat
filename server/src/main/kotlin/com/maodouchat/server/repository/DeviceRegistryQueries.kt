package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.SignalDevices
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

fun DeviceRegistry.getDeviceInfos(userId: String, currentDeviceId: Int? = null, includePending: Boolean = false): List<DeviceInfo> {
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

fun DeviceRegistry.isAuthSessionBoundToDevice(userId: String, authSessionId: String, deviceId: Int): Boolean {
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

fun DeviceRegistry.getDeviceIdForAuthSession(authSessionId: String): Int? = transaction {
    AuthSessions.selectAll()
        .where { AuthSessions.id eq authSessionId }
        .firstOrNull()
        ?.get(AuthSessions.signalDeviceId)
}

internal data class DeviceMetadata(
    val deviceName: String,
    val status: String,
    val confirmedAt: Long?,
    val confirmedByDeviceId: Int?,
    val lastSeenAt: Long?
)
