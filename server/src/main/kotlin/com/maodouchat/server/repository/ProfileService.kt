package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * 资料服务：昵称/状态/头像/用户名。
 * 自 `UserRepository` 拆出（B02「拆 ProfileService」），与
 * `CredentialService` / `BlockService` 同构。
 */
class ProfileService {

    fun updateProfile(userId: String, name: String? = null, status: String? = null) {
        transaction {
            val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction
            if (row[Users.deletedAt] != null) return@transaction
            Users.update({ Users.id eq userId }) {
                name?.trim()?.takeIf { it.isNotBlank() }?.let { value -> it[Users.name] = value.take(MAX_NAME_LENGTH) }
                status?.trim()?.let { value -> it[Users.status] = value.take(MAX_STATUS_LENGTH) }
            }
        }
    }

    fun replaceAvatar(userId: String, avatarUrl: String?): AvatarReplacementResult? = transaction {
        val normalized = avatarUrl?.trim()
        // 8.37：非法头像地址返回 null（由路由层 400），不得 require 抛 IllegalArgumentException 变 500
        if (!(normalized == null || isValidAvatarUrl(normalized))) return@transaction null
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (row[Users.deletedAt] != null) return@transaction null
        val previousUrl = row[Users.avatar]
        if (previousUrl != normalized) {
            Users.update({ Users.id eq userId }) { it[Users.avatar] = normalized }
        }
        AvatarReplacementResult(previousUrl = previousUrl, currentUrl = normalized)
    }

    fun isCurrentAvatarUrl(avatarUrl: String): Boolean = transaction {
        !Users.selectAll().where { (Users.avatar eq avatarUrl) and Users.deletedAt.isNull() }.empty()
    }

    fun findByUsername(username: String): UserResponse? {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return null
        return transaction {
            Users.selectAll().where { Users.username eq clean }.firstOrNull()
                ?.toPublicUser(anonymous = true)
        }
    }

    /** 设置/更新当前用户的用户名（唯一、小写、去 @ 前缀） */
    fun setUsername(userId: String, username: String): String? {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank() || clean.length < 3 || clean.length > 50) return null
        if (!clean.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return try {
            transaction {
                val existing = Users.selectAll().where { Users.username eq clean }.firstOrNull()
                if (existing != null && existing[Users.id] != userId) return@transaction null
                Users.update({ Users.id eq userId }) {
                    it[Users.username] = clean
                }
                clean
            }
        } catch (error: Exception) {
            // 仅唯一冲突（并发抢注同名）映射「已占用」；DB 故障等不得伪装成冲突静默失败
            if (isUniqueViolation(error)) null else throw error
        }
    }

    /** 清除用户名（设为 null） */
    fun clearUsername(userId: String): Boolean = transaction {
        Users.update({ Users.id eq userId }) { it[Users.username] = null }
        true
    }

    private fun isValidAvatarUrl(value: String): Boolean {
        if (value.length > MAX_AVATAR_LENGTH) return false
        val prefix = "/api/files/avatar/"
        // Allow relative path or absolute URL pointing to this server only.
        return value.startsWith(prefix) ||
            value.startsWith(com.maodouchat.server.config.ServerConfig.baseUrl.trimEnd('/') + prefix)
    }

    private companion object {
        const val MAX_STATUS_LENGTH = 80
        const val MAX_AVATAR_LENGTH = 500
    }
}
