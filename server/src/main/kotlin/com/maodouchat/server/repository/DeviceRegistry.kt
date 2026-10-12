package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 设备注册与生命周期管理服务 (B03)。
 * 负责设备状态（PENDING / CONFIRMED / REVOKED）、新设备审批、名称更新与会话绑定销毁。
 * 注册 / 审批 / 查询 / 生命周期实现已按域拆到同目录簇文件（零行为改动）。
 */
class DeviceRegistry(
    internal val identityKeyProvider: (userId: String, deviceId: Int) -> String? = { userId, deviceId ->
        transaction {
            SignalKeys.select(SignalKeys.keyData).where {
                (SignalKeys.userId eq userId) and
                    (SignalKeys.deviceId eq deviceId) and
                    (SignalKeys.keyType eq "identity_key")
            }.firstOrNull()?.get(SignalKeys.keyData)
        }
    }
) : EncryptableDeviceDirectory {

    override fun isDeviceConfirmed(userId: String, deviceId: Int): Boolean = transaction {
        SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and
                (SignalDevices.deviceId eq deviceId) and
                (SignalDevices.status eq DEVICE_STATUS_CONFIRMED)
        }.firstOrNull() != null
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
}

// 确认设备可被加密投递的前提：四个密钥槽位齐全。
internal val REQUIRED_BUNDLE_KEY_TYPES = setOf(
    "identity_key",
    "registration_id",
    "signed_pre_key",
    "signed_pre_key_signature"
)
