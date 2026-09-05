package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalKeys
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 身份公钥仓库 (B03)。
 */
class IdentityKeyStore {
    fun getIdentityKey(userId: String, deviceId: Int): String? = transaction {
        SignalKeys.select(SignalKeys.keyData).where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "identity_key")
        }.firstOrNull()?.get(SignalKeys.keyData)
    }

    fun getDeviceId(userId: String, deviceId: Int): Int? = transaction {
        SignalKeys.select(SignalKeys.keyData).where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "device_id")
        }.firstOrNull()?.get(SignalKeys.keyData)?.toIntOrNull()
    }
}

/**
 * 签名预密钥仓库 (B03)。
 */
class SignedPreKeyStore {
    fun getSignedPreKey(userId: String, deviceId: Int): KeyData? = transaction {
        val row = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "signed_pre_key")
        }.firstOrNull() ?: return@transaction null
        val keyId = row[SignalKeys.keyId] ?: return@transaction null
        KeyData(keyId, row[SignalKeys.keyData])
    }

    fun getSignedPreKeySignature(userId: String, deviceId: Int): String? = transaction {
        SignalKeys.select(SignalKeys.keyData).where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "signed_pre_key_signature")
        }.firstOrNull()?.get(SignalKeys.keyData)
    }
}
