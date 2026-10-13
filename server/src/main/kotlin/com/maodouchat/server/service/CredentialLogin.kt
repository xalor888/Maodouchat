package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun CredentialService.login(email: String, password: String): UserResponse? {
    val result = loginWithFactors(email, password, totpCode = null)
    // Legacy path: only succeed when TOTP is not enabled.
    return if (result.passwordOk && !result.totpEnabled) result.user else null
}

fun CredentialService.loginWithFactors(email: String, password: String, totpCode: String?): CredentialService.LoginResult {
    return transaction {
        val normalizedEmail = email.normalizedEmail()
        val user = Users.selectAll().where { Users.email eq normalizedEmail }.forUpdate().firstOrNull()
            ?: return@transaction CredentialService.LoginResult(null, false, false, false)
        if (user[Users.deletedAt] != null) return@transaction CredentialService.LoginResult(null, false, false, false)
        if (user[Users.suspendedUntil] > System.currentTimeMillis()) {
            return@transaction CredentialService.LoginResult(null, false, false, false)
        }
        val hash = user[Users.passwordHash]
        val passwordOk = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        if (!passwordOk) return@transaction CredentialService.LoginResult(null, false, false, false)
        val enabled = user[Users.totpEnabled] && !user[Users.totpSecret].isNullOrBlank()
        val secret = user[Users.totpSecret].orEmpty()
        val totpOk = if (!enabled) true else {
            val totpAccepted = com.maodouchat.server.service.TotpService.verify(secret, totpCode.orEmpty()) { candidate ->
                mfaService.acceptTotpCounter(user, candidate)
            }
            // 0.75：TOTP 校验失败时尝试恢复码（单次使用；丢失验证器可恢复登录）
            if (totpAccepted) true else mfaService.consumeBackupCode(user, totpCode.orEmpty())
        }
        if (passwordOk && totpOk) {
            Users.update({ Users.email eq normalizedEmail }) {
                it[Users.lastSeen] = System.currentTimeMillis()
            }
        }
        CredentialService.LoginResult(
            user = if (passwordOk && totpOk) user.toPrivateUser(isOnlineOverride = false) else null,
            passwordOk = true,
            totpEnabled = enabled,
            totpOk = totpOk
        )
    }
}
