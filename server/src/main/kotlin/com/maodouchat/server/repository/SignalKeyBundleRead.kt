package com.maodouchat.server.repository

// bundle 发放簇：原 SignalKeyBundles 顶层声明按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 密钥包簇：bundle 发放与 key package 上传，SQL 逐字搬迁、零行为改动。

fun SignalKeyRepository.getBundle(
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
