package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun CredentialService.register(name: String, email: String, password: String): UserResponse? {
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
