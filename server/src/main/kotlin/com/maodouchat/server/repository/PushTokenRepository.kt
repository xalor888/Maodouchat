package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.PushTokens
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class PushTokenRecord(
    val userId: String,
    val deviceId: String,
    val token: String,
    val platform: String,
    val timezoneOffsetMinutes: Int,
    val authSessionId: String?,
    val updatedAt: Long = 0L
)

class PushTokenRepository {
    fun register(
        userId: String,
        deviceId: String,
        token: String,
        platform: String,
        timezoneOffsetMinutes: Int,
        authSessionId: String
    ): Boolean {
        // 8.46 修复：FCM token 长度/格式校验——此前任意字符串直接入库（列宽 512），
        // 攻击者可灌大量垃圾 token 占存储，FCM 侧逐个 400 后才被清理。
        val safeToken = token.trim()
        if (safeToken.isBlank() || safeToken.length > 255) return false
        if (!safeToken.all { it.isLetterOrDigit() || it == ':' || it == '_' || it == '-' || it == '.' }) {
            return false
        }
        // 并发 token 迁移（不同账号抢同一 FCM token）时 delete+insert 交叉可能撞 token 唯一索引。
        // 8.48 的两段式「事务内 catch 后 delete 重试」在 PG 上是死路：唯一冲突已 abort 整事务，
        // 同事务 DELETE 必 25P02。正确姿势：catch 在事务外，Exposed 回滚后在全新事务重放。
        return try {
            transaction { registerInTransaction(userId, deviceId, safeToken, platform, timezoneOffsetMinutes, authSessionId) }
        } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            if (!isUniqueViolation(conflict)) throw conflict
            transaction { registerInTransaction(userId, deviceId, safeToken, platform, timezoneOffsetMinutes, authSessionId) }
        }
    }

    private fun registerInTransaction(
        userId: String,
        deviceId: String,
        safeToken: String,
        platform: String,
        timezoneOffsetMinutes: Int,
        authSessionId: String
    ): Boolean {
        val activeSession = AuthSessions.selectAll().where {
            (AuthSessions.id eq authSessionId) and
                (AuthSessions.userId eq userId) and
                AuthSessions.revokedAt.isNull()
        }.forUpdate().firstOrNull()
        if (activeSession == null) return false
        // A Firebase registration token belongs to one app installation. Moving it to
        // another account must remove the previous account mapping first.
        PushTokens.deleteWhere { PushTokens.token eq safeToken }
        val existing = PushTokens.selectAll().where {
            (PushTokens.userId eq userId) and (PushTokens.deviceId eq deviceId)
        }.firstOrNull()
        val now = System.currentTimeMillis()
        if (existing == null) {
            PushTokens.insert {
                it[PushTokens.userId] = userId
                it[PushTokens.deviceId] = deviceId
                it[PushTokens.authSessionId] = authSessionId
                it[PushTokens.token] = safeToken
                it[PushTokens.platform] = platform
                it[PushTokens.timezoneOffsetMinutes] = timezoneOffsetMinutes
                it[PushTokens.updatedAt] = now
            }
        } else {
            PushTokens.update({ (PushTokens.userId eq userId) and (PushTokens.deviceId eq deviceId) }) {
                it[PushTokens.token] = safeToken
                it[PushTokens.authSessionId] = authSessionId
                it[PushTokens.platform] = platform
                it[PushTokens.timezoneOffsetMinutes] = timezoneOffsetMinutes
                it[PushTokens.updatedAt] = now
            }
        }
        return true
    }

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    fun getForUser(userId: String): List<PushTokenRecord> = transaction {
        val activeSessionIds = AuthSessions.select(AuthSessions.id).where {
            (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
        }.map { it[AuthSessions.id] }.toSet()
        PushTokens.selectAll()
            .where { PushTokens.userId eq userId }
            .filter { row -> row[PushTokens.authSessionId]?.let(activeSessionIds::contains) == true }
            .map { it.toPushTokenRecord() }
    }

    /**
     * 8.48 修复 H8：批量获取多用户的活跃推送 token（FCM 批量投递用）。
     * 一次事务内取全部活跃 session 再过滤 token，替代逐用户 getForUser。
     */
    fun getForUsers(userIds: List<String>): Map<String, List<PushTokenRecord>> = transaction {
        if (userIds.isEmpty()) return@transaction emptyMap()
        val activeSessionIds = AuthSessions.select(AuthSessions.id).where {
            (AuthSessions.userId inList userIds) and AuthSessions.revokedAt.isNull()
        }.map { it[AuthSessions.id] }.toSet()
        PushTokens.selectAll()
            .where { PushTokens.userId inList userIds }
            .filter { row -> row[PushTokens.authSessionId]?.let(activeSessionIds::contains) == true }
            .map { it.toPushTokenRecord() }
            .groupBy { it.userId }
    }

    /** 全部注册过推送 token 的用户 id（去重，供系统公告广播等全量推送）。 */
    fun listUserIds(): List<String> = transaction {
        PushTokens.select(PushTokens.userId).distinct().map { it[PushTokens.userId] }
    }

    fun remove(userId: String, deviceId: String) = transaction {
        PushTokens.deleteWhere { (PushTokens.userId eq userId) and (PushTokens.deviceId eq deviceId) }
    }

    fun removeAllForUser(userId: String) = transaction {
        PushTokens.deleteWhere { PushTokens.userId eq userId }
    }

    fun removeForAuthSession(userId: String, authSessionId: String) = transaction {
        PushTokens.deleteWhere {
            (PushTokens.userId eq userId) and (PushTokens.authSessionId eq authSessionId)
        }
    }

    fun removeToken(token: String) = transaction {
        PushTokens.deleteWhere { PushTokens.token eq token }
    }

    private fun ResultRow.toPushTokenRecord() = PushTokenRecord(
        userId = this[PushTokens.userId],
        deviceId = this[PushTokens.deviceId],
        token = this[PushTokens.token],
        platform = this[PushTokens.platform],
        timezoneOffsetMinutes = this[PushTokens.timezoneOffsetMinutes],
        authSessionId = this[PushTokens.authSessionId],
        updatedAt = this[PushTokens.updatedAt]
    )
}
