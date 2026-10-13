package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台二次确认：口令 +（账号启用时）第二因子。
 *
 * 为什么必须一个事务：口令与第二因子要落在**同一行锁**下判定。拆成两次调用时，
 * 两次之间的账号状态可以被改动（启用/停用 TOTP、改密），形成 TOCTOU 窗口。
 *
 * 第二因子的重放语义刻意用 `trackReplay = false`（只校验 30s±1 时限），
 * 与 [com.maodouchat.server.service.MfaService.disableTotp] 同一先例：管理会话的确认
 * 发生在**刚刚登录之后**，而登录已经消费了该时间窗的 counter 并推进了持久化 CAS。
 * 若这里再要求一个未使用过的 counter，主管理员必须干等 30 秒换新码才能进后台——
 * 而被 8.63 修过的正是这个「刚登录就无法立即二次确认」的形态。
 * 登录路径自身的重放保护不受影响，仍然生效。
 */
fun CredentialService.verifyAdminCredentials(
    userId: String,
    password: String,
    totpCode: String?,
): CredentialService.AdminCredentialCheck {
    return transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction CredentialService.AdminCredentialCheck(passwordOk = false, secondFactorOk = false, totpEnabled = false)
        if (row[Users.deletedAt] != null) {
            return@transaction CredentialService.AdminCredentialCheck(passwordOk = false, secondFactorOk = false, totpEnabled = false)
        }
        if (!BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash]).verified) {
            return@transaction CredentialService.AdminCredentialCheck(passwordOk = false, secondFactorOk = false, totpEnabled = false)
        }
        val enabled = row[Users.totpEnabled] && !row[Users.totpSecret].isNullOrBlank()
        if (!enabled) {
            return@transaction CredentialService.AdminCredentialCheck(passwordOk = true, secondFactorOk = true, totpEnabled = false)
        }
        val secret = row[Users.totpSecret].orEmpty()
        val totpAccepted = com.maodouchat.server.service.TotpService.verify(
            secret,
            totpCode.orEmpty(),
            trackReplay = false,
        ) { true }
        // 丢失验证器时的恢复码：单次消费，与 TOTP 在同一行锁内判定。
        val secondFactorOk = totpAccepted || mfaService.consumeBackupCode(row, totpCode.orEmpty())
        CredentialService.AdminCredentialCheck(passwordOk = true, secondFactorOk = secondFactorOk, totpEnabled = true)
    }
}
