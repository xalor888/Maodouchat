package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 多因子认证（TOTP + 一次性恢复码），自 UserRepository 拆出（B02「拆 MfaService」）。 */
class MfaService {

    fun isTotpEnabled(userId: String): Boolean = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction false
        row[Users.totpEnabled] && !row[Users.totpSecret].isNullOrBlank()
    }

    fun beginTotpSetup(userId: String): Pair<String, String>? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction null
        if (row[Users.deletedAt] != null) return@transaction null
        // 已启用的 2FA 不得被 setup 静默关闭：此前无条件写 totpEnabled=false 并替换 secret，
        // 未 confirm 也会立刻关掉登录第二因子。重新绑定须先走 disable。
        if (row[Users.totpEnabled] && !row[Users.totpSecret].isNullOrBlank()) {
            throw IllegalArgumentException("TOTP already enabled")
        }
        val secret = com.maodouchat.server.service.TotpService.generateSecret()
        Users.update({ Users.id eq userId }) {
            it[totpSecret] = secret
            it[totpEnabled] = false
        }
        val email = row[Users.email]
        val uri = com.maodouchat.server.service.TotpService.provisioningUri(secret, email)
        secret to uri
    }

    fun confirmTotpSetup(userId: String, code: String): List<String>? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction null
        val secret = row[Users.totpSecret] ?: return@transaction null
        if (!com.maodouchat.server.service.TotpService.verify(secret, code) { candidate ->
                acceptTotpCounter(row, candidate)
            }
        ) return@transaction null
        // 0.75：生成 8 个恢复码（明文仅本次返回，落库存 BCrypt 哈希，单次使用）
        val codes = (1..8).map { generateBackupCode() }
        val hashes = codes.map { code -> BCrypt.withDefaults().hashToString(10, code.toCharArray()) }
        Users.update({ Users.id eq userId }) {
            it[totpEnabled] = true
            it[Users.totpBackupCodes] = hashes.joinToString(",")
        }
        codes
    }

    fun disableTotp(userId: String, code: String): Boolean = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction false
        val secret = row[Users.totpSecret]
        val enabled = row[Users.totpEnabled]
        if (enabled && !secret.isNullOrBlank()) {
            // 8.63 修复：关闭 TOTP 是「已认证（持有效 token）+ 验证码有时限（30s±1）」的双因子确认，
            // 不应受登录防重放计数器（acceptTotpCounter）约束——否则刚登录后无法立即关闭 TOTP
            //（登录已把 totpLastCounter 推进，当前窗口内任何新验证码都会被当作重放拒绝）。
            // 仅校验验证码在当前时限窗口内有效即可。
            if (!com.maodouchat.server.service.TotpService.verify(secret, code, trackReplay = false) { true }
            ) return@transaction false
        }
        Users.update({ Users.id eq userId }) {
            it[totpSecret] = null
            it[totpEnabled] = false
            it[Users.totpBackupCodes] = null
        }
        true
    }

    /** 0.77：验证当前 TOTP 后重新生成恢复码（旧码全部作废）。返回新恢复码明文，失败返回 null。 */
    fun regenerateBackupCodes(userId: String, code: String): List<String>? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction null
        if (!row[Users.totpEnabled]) return@transaction null
        val secret = row[Users.totpSecret] ?: return@transaction null
        if (!com.maodouchat.server.service.TotpService.verify(secret, code) { candidate ->
                acceptTotpCounter(row, candidate)
            }
        ) return@transaction null
        val codes = (1..8).map { generateBackupCode() }
        val hashes = codes.map { c -> BCrypt.withDefaults().hashToString(10, c.toCharArray()) }
        Users.update({ Users.id eq userId }) {
            it[Users.totpBackupCodes] = hashes.joinToString(",")
        }
        codes
    }

    /**
     * 8.51 修复 M2：TOTP counter DB 原子 CAS——仅当候选 counter 严格大于已持久化值才接受并落库。
     * 调用方须在同一事务内持该用户行锁（forUpdate），杜绝重启/多实例后重放同一步 code。
     */
    private fun acceptTotpCounter(row: org.jetbrains.exposed.sql.ResultRow, candidate: Long): Boolean {
        val persisted = row[Users.totpLastCounter]
        if (candidate <= persisted) return false
        Users.update({ Users.id eq row[Users.id] }) {
            it[Users.totpLastCounter] = candidate
        }
        return true
    }

    /** 0.75：校验并单次消费恢复码（匹配即删除该码）。调用方须持用户行锁在同一事务内。 */
    private fun consumeBackupCode(row: org.jetbrains.exposed.sql.ResultRow, code: String): Boolean {
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
    private fun generateBackupCode(): String {
        val rand = java.security.SecureRandom()
        val sb = StringBuilder(8)
        repeat(8) { sb.append('0' + rand.nextInt(10)) }
        return sb.toString()
    }
}
