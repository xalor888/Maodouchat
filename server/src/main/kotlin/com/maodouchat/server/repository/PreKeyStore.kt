package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalKeys
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Signal 一次性预密钥仓库 (B03)。
 * 负责 pre_key 的原子消费、查看、批量上传与过期清理。
 */
class PreKeyStore {
    companion object {
        const val PRE_KEY_TYPE = "pre_key"
        const val CONSUMED_PRE_KEY_TYPE = "consumed_pre_key"
    }

    /** 原子消费一个一次性预密钥。若已被消费则转为 consumed_pre_key 状态。 */
    fun consumePreKey(userId: String, deviceId: Int): KeyData? = transaction {
        val row = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq PRE_KEY_TYPE)
        }.orderBy(SignalKeys.createdAt to SortOrder.ASC).forUpdate().limit(1).firstOrNull() ?: return@transaction null

        val keyId = row[SignalKeys.keyId] ?: return@transaction null
        val keyData = row[SignalKeys.keyData]

        SignalKeys.update({
            (SignalKeys.id eq row[SignalKeys.id]) and (SignalKeys.keyType eq PRE_KEY_TYPE)
        }) {
            it[keyType] = CONSUMED_PRE_KEY_TYPE
            it[createdAt] = System.currentTimeMillis()
        }
        KeyData(keyId, keyData)
    }

    fun peekPreKey(userId: String, deviceId: Int): KeyData? = transaction {
        val row = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq PRE_KEY_TYPE)
        }.orderBy(SignalKeys.createdAt to SortOrder.ASC).limit(1).firstOrNull() ?: return@transaction null
        val keyId = row[SignalKeys.keyId] ?: return@transaction null
        KeyData(keyId, row[SignalKeys.keyData])
    }

    /**
     * Must run inside an open transaction that already holds the user row lock.
     *
     * A live `pre_key` has not been handed out yet: `getBundle` changes it to
     * `consumed_pre_key` in the same transaction before returning it.  Re-publishing a
     * different public key for a live id is therefore safe and repairs a local key-store
     * regeneration/ID-wrap collision.  A consumed id, however, may already have an
     * encrypted message in flight and must remain byte-for-byte untouched; the incoming
     * row is simply treated as an idempotent no-op (it will never be served again).
     */
    fun uploadPreKeyInTx(userId: String, deviceId: Int, preKey: PreKeyUpload) {
        val existingRows = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                ((SignalKeys.keyType eq PRE_KEY_TYPE) or (SignalKeys.keyType eq CONSUMED_PRE_KEY_TYPE)) and
                (SignalKeys.keyId eq preKey.keyId)
        }.forUpdate().toList()

        // Once an id has been consumed, never revive it and never overwrite the original
        // public key, even if a restored client presents a different key under the same id.
        if (existingRows.any { it[SignalKeys.keyType] == CONSUMED_PRE_KEY_TYPE }) return

        val liveRows = existingRows.filter { it[SignalKeys.keyType] == PRE_KEY_TYPE }
        if (liveRows.isNotEmpty()) {
            // There should be one live row per id.  Updating all historical duplicate rows
            // keeps the key material coherent without deleting an already-consumed row.
            liveRows.forEach { row ->
                if (row[SignalKeys.keyData] != preKey.publicKeyBase64) {
                    SignalKeys.update({ SignalKeys.id eq row[SignalKeys.id] }) {
                        it[keyData] = preKey.publicKeyBase64
                    }
                }
            }
            return
        }

        SignalKeys.insert {
            it[id] = "pk_${UUID.randomUUID()}"
            it[SignalKeys.userId] = userId
            it[SignalKeys.deviceId] = deviceId
            it[keyType] = PRE_KEY_TYPE
            it[keyData] = preKey.publicKeyBase64
            it[keyId] = preKey.keyId
            it[createdAt] = System.currentTimeMillis()
        }
    }

    fun purgeConsumedPreKeys(retentionDays: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 3600 * 1000)
        return transaction {
            SignalKeys.deleteWhere {
                (keyType eq CONSUMED_PRE_KEY_TYPE) and (createdAt less cutoff)
            }
        }
    }
}
