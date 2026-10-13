package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** TOTP 校验原语：开关查询、防重放计数器、恢复码消费。 */

fun MfaService.isTotpEnabled(userId: String): Boolean = transaction {
    val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction false
    row[Users.totpEnabled] && !row[Users.totpSecret].isNullOrBlank()
}

/**
 * 8.51 修复 M2：TOTP counter DB 原子 CAS——仅当候选 counter 严格大于已持久化值才接受并落库。
 * 调用方须在同一事务内持该用户行锁（forUpdate），杜绝重启/多实例后重放同一步 code。
 */
fun MfaService.acceptTotpCounter(row: org.jetbrains.exposed.sql.ResultRow, candidate: Long): Boolean {
    val persisted = row[Users.totpLastCounter]
    if (candidate <= persisted) return false
    Users.update({ Users.id eq row[Users.id] }) {
        it[Users.totpLastCounter] = candidate
    }
    return true
}

/** 0.75：校验并单次消费恢复码（匹配即删除该码）。调用方须持用户行锁在同一事务内。 */
fun MfaService.consumeBackupCode(row: org.jetbrains.exposed.sql.ResultRow, code: String): Boolean {
    val raw = row[Users.totpBackupCodes] ?: return false
    if (raw.isBlank()) return false
    val hashes = raw.split(',')
    val normalized = code.trim()
    val idx = hashes.indexOfFirst { hash ->
        hash.isNotBlank() && BCrypt.verifyer().verify(normalized.toCharArray(), hash).verified
    }
    if (idx < 0) return false
    val remaining = hashes.filterIndexed { i, _ -> i != idx }.filter { it.isNotBlank() }
    Users.update({ Users.id eq row[Users.id] }) {
        it[Users.totpBackupCodes] = if (remaining.isEmpty()) null else remaining.joinToString(",")
    }
    return true
}

/** 0.75：生成 8 位数字恢复码（SecureRandom，杜绝可预测序列）。 */
internal fun MfaService.generateBackupCode(): String {
    val sb = StringBuilder(8)
    repeat(8) { sb.append('0' + secureRandom.nextInt(10)) }
    return sb.toString()
}
