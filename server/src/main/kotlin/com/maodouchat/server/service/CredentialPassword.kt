package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun CredentialService.changePassword(userId: String, oldPassword: String, newPassword: String): Boolean {
    return transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction false
        if (row[Users.deletedAt] != null) return@transaction false
        if (!BCrypt.verifyer().verify(oldPassword.toCharArray(), row[Users.passwordHash]).verified) return@transaction false
        val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
        Users.update({ Users.id eq userId }) { it[passwordHash] = newHash }
        true
    }
}

/**
 * 邮箱验证码重置密码（调用方已校验验证码）。
 * @return 成功时返回 userId；邮箱不存在或已注销时返回 null（路由应对外统一文案，防枚举）
 */
fun CredentialService.resetPasswordByEmail(email: String, newPassword: String): String? {
    return transaction {
        val normalizedEmail = email.normalizedEmail()
        val row = Users.selectAll().where { Users.email eq normalizedEmail }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (row[Users.deletedAt] != null) return@transaction null
        val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
        Users.update({ Users.id eq row[Users.id] }) { it[passwordHash] = newHash }
        row[Users.id]
    }
}

fun CredentialService.verifyPassword(userId: String, password: String): Boolean = transaction {
    val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction false
    if (row[Users.deletedAt] != null || row[Users.suspendedUntil] > System.currentTimeMillis()) return@transaction false
    BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash]).verified
}
