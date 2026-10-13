package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** TOTP 生命周期：启用、确认、关闭、恢复码轮换。 */

fun MfaService.beginTotpSetup(userId: String): Pair<String, String>? = transaction {
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

fun MfaService.confirmTotpSetup(userId: String, code: String): List<String>? = transaction {
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

fun MfaService.disableTotp(userId: String, code: String): Boolean = transaction {
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
fun MfaService.regenerateBackupCodes(userId: String, code: String): List<String>? = transaction {
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
