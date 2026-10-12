package com.maodouchat.server.repository

// 键行存储簇：原 SignalKeyBundles 顶层声明按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

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


/** Must run inside an open transaction. */
private fun lockUserRow(userId: String) {
    com.maodouchat.server.db.Users.selectAll()
        .where { com.maodouchat.server.db.Users.id eq userId }
        .forUpdate()
        .firstOrNull()
}


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
