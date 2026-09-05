package com.maodouchat.server.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** 姓名长度上限（包内共享；原 `UserRepository` companion 私有常量）。 */
internal const val MAX_NAME_LENGTH = 50

/** 邮箱归一化（自 `UserRepository` 私有成员提升为包内共享）。 */
internal fun String.normalizedEmail(): String = trim().lowercase()

/** 用户行 → 私有资料（自 `UserRepository` 私有成员提升为包内共享）。 */
internal fun ResultRow.toPrivateUser(isOnlineOverride: Boolean? = null): UserResponse {
    return UserResponse(
        id = this[Users.id],
        name = this[Users.name],
        email = this[Users.email],
        avatar = this[Users.avatar],
        status = this[Users.status],
        isOnline = isOnlineOverride ?: this[Users.isOnline],
        isModerator = this[Users.isModerator],
        lastSeen = this[Users.lastSeen],
        username = this[Users.username]
    )
}

/** SQL 唯一冲突判定（包内共享；其余各仓储的私有拷贝后续逐步收敛）。 */
internal fun isUniqueViolation(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is java.sql.SQLException && current.sqlState == "23505") return true
        val message = current.message.orEmpty().lowercase()
        if (message.contains("unique") || message.contains("duplicate key")) return true
        current = current.cause
    }
    return false
}

/**
 * 凭证服务：注册 / 登录（含 TOTP 二因子）/ 改密 / 邮箱重置 / 密码校验。
 * 自 `UserRepository` 拆出（B02「拆 CredentialService」），与
 * `AccountLifecycleService` / `PrivacyService` / `BlockService` 同构。
 */
class CredentialService(
    private val mfaService: com.maodouchat.server.service.MfaService =
        com.maodouchat.server.service.MfaService(),
) {
    private val registrationLock = Any()

    data class LoginResult(
        val user: UserResponse?,
        val passwordOk: Boolean,
        val totpEnabled: Boolean,
        val totpOk: Boolean
    )

    fun register(name: String, email: String, password: String): UserResponse? {
        val normalizedEmail = email.normalizedEmail()
        val safeName = name.trim().take(MAX_NAME_LENGTH)
        val registerTx = {
            transaction {
                if (!Users.selectAll().where { Users.email eq normalizedEmail }.empty()) return@transaction null

                // 完整 UUID，避免 take(8)（32-bit 熵）碰撞导致主键唯一冲突被 isUniqueViolation 吞掉、
                // 误报"邮箱已存在"而注册静默失败。
                val id = "u_${UUID.randomUUID()}"
                val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = safeName
                    it[Users.email] = normalizedEmail
                    it[passwordHash] = hash
                    it[isOnline] = false
                    it[status] = "在线"
                    it[showOnline] = true
                    it[searchable] = true
                    it[defaultPostVisibility] = "PUBLIC"
                    it[isModerator] = normalizedEmail in ServerConfig.moderatorEmails
                }
                // 一次性引导：BOOTSTRAP_FIRST_USER_AS_ADMIN=true 时第一个注册账号自动成为
                // 主管理员（调用方在 registrationLock 内串行执行，见 companion 注释）。
                if (ServerConfig.bootstrapFirstUserAsAdmin &&
                    Users.selectAll().where { Users.id neq id }.empty()
                ) {
                    AdminAccess.grantAdmin(id)
                }
                UserResponse(id, safeName, normalizedEmail, status = "在线", isModerator = normalizedEmail in ServerConfig.moderatorEmails)
            }
        }
        return try {
            if (ServerConfig.bootstrapFirstUserAsAdmin) {
                synchronized(registrationLock) { registerTx() }
            } else {
                registerTx()
            }
        } catch (error: Exception) {
            if (isUniqueViolation(error)) null else throw error
        }
    }

    fun login(email: String, password: String): UserResponse? {
        val result = loginWithFactors(email, password, totpCode = null)
        // Legacy path: only succeed when TOTP is not enabled.
        return if (result.passwordOk && !result.totpEnabled) result.user else null
    }

    fun loginWithFactors(email: String, password: String, totpCode: String?): LoginResult {
        return transaction {
            val normalizedEmail = email.normalizedEmail()
            val user = Users.selectAll().where { Users.email eq normalizedEmail }.forUpdate().firstOrNull()
                ?: return@transaction LoginResult(null, false, false, false)
            if (user[Users.deletedAt] != null) return@transaction LoginResult(null, false, false, false)
            if (user[Users.suspendedUntil] > System.currentTimeMillis()) {
                return@transaction LoginResult(null, false, false, false)
            }
            val hash = user[Users.passwordHash]
            val passwordOk = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
            if (!passwordOk) return@transaction LoginResult(null, false, false, false)
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
            LoginResult(
                user = if (passwordOk && totpOk) user.toPrivateUser(isOnlineOverride = false) else null,
                passwordOk = true,
                totpEnabled = enabled,
                totpOk = totpOk
            )
        }
    }

    fun changePassword(userId: String, oldPassword: String, newPassword: String): Boolean {
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
    fun resetPasswordByEmail(email: String, newPassword: String): String? {
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

    fun verifyPassword(userId: String, password: String): Boolean = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction false
        if (row[Users.deletedAt] != null || row[Users.suspendedUntil] > System.currentTimeMillis()) return@transaction false
        BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash]).verified
    }
}
