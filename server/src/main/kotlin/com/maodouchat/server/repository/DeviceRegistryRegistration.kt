package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

// 设备首次上线或心跳：无行则按首设备自确认规则建行，否则只刷新名称与 lastSeen。
fun DeviceRegistry.touchDevice(userId: String, deviceId: Int, deviceName: String? = null) {
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

// 首设备自确认：该用户此前无任何已确认设备、设备行与其他密钥行时才自动 CONFIRMED。
internal fun DeviceRegistry.initialDeviceStatus(userId: String, deviceId: Int): String {
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
