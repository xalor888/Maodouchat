package com.maodouchat.server.repository

// 签发簇：原 AuthTokenIssue 扩展按域拆出（同包调用，零行为改动）。

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.Users
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun AuthTokenRepository.issueRefreshToken(userId: String, existingSessionId: String? = null): IssuedRefreshToken {
    val token = generateRefreshToken()
    val now = System.currentTimeMillis()
    val expiresAt = now + AuthTokenRepository.REFRESH_TOKEN_VALIDITY_MS
    val hash = hashToken(token)
    val sessionId = existingSessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    transaction {
        val user = Users.selectAll()
            .where { Users.id eq userId }
            .forUpdate()
            .firstOrNull()
        if (user == null || user[Users.deletedAt] != null || user[Users.suspendedUntil] > now) {
            throw InactiveAuthSessionException()
        }
        if (existingSessionId.isNullOrBlank()) {
            AuthSessions.insert {
                it[id] = sessionId
                it[AuthSessions.userId] = userId
                it[signalDeviceId] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[revokedAt] = null
            }
            // 8.34 修复：每用户活跃会话数上限——登录次数无限则会话/refresh token 无限膨胀
            //（每会话 30 天可轮换续命）。超出后按创建时间淘汰最旧会话（用户行 forUpdate 锁
            // 保证同用户并发登录下此判定串行）。
            val activeSessions = AuthSessions.select(AuthSessions.id, AuthSessions.createdAt)
                .where { (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull() }
                .orderBy(AuthSessions.createdAt to SortOrder.ASC)
                .toList()
            val excess = activeSessions.size - AuthTokenRepository.MAX_ACTIVE_SESSIONS
            if (excess > 0) {
                activeSessions.take(excess).forEach { row ->
                    val staleSessionId = row[AuthSessions.id]
                    RefreshTokens.update({
                        (RefreshTokens.sessionId eq staleSessionId) and RefreshTokens.revokedAt.isNull()
                    }) {
                        it[revokedAt] = now
                    }
                    AuthSessions.update({ AuthSessions.id eq staleSessionId }) {
                        it[revokedAt] = now
                    }
                }
            }
        } else {
            val session = AuthSessions.selectAll()
                .where { (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId) }
                .forUpdate()
                .firstOrNull()
            if (session == null || session[AuthSessions.revokedAt] != null) {
                throw InactiveAuthSessionException()
            }
            AuthSessions.update({ AuthSessions.id eq sessionId }) {
                it[updatedAt] = now
            }
        }
        RefreshTokens.insert {
            it[tokenHash] = hash
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.sessionId] = sessionId
            it[createdAt] = now
            it[RefreshTokens.expiresAt] = expiresAt
            it[revokedAt] = null
        }
    }
    return IssuedRefreshToken(token, expiresAt, sessionId)
}
