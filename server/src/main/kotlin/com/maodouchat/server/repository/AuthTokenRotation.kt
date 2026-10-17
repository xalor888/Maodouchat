package com.maodouchat.server.repository

// 轮换簇：原 AuthTokenIssue 扩展按域拆出（同包调用，零行为改动）。

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * 单事务原子轮换：行锁 → 校验用户/封禁 → 通过后才 revoke。
 * 封禁/账号缺失时不吊销 refresh，避免 peek→consume 竞态把有效 token 烧掉。
 */
fun AuthTokenRepository.rotateIfEligible(refreshToken: String): RotateRefreshResult {
    val hash = hashToken(refreshToken)
    val now = System.currentTimeMillis()
    val replacementToken = generateRefreshToken()
    val replacementHash = hashToken(replacementToken)
    val replacementExpiresAt = now + AuthTokenRepository.REFRESH_TOKEN_VALIDITY_MS
    return transaction {
        val initialRow = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .firstOrNull()
            ?: return@transaction InvalidToken
        val userId = initialRow[RefreshTokens.userId]
        val sessionId = initialRow[RefreshTokens.sessionId]
        val user = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
        if (user == null || user[Users.deletedAt] != null) {
            return@transaction UserMissing
        }
        val session = sessionId.takeIf { it.isNotBlank() }?.let { id ->
            AuthSessions.selectAll()
                .where { (AuthSessions.id eq id) and (AuthSessions.userId eq userId) }
                .forUpdate()
                .firstOrNull()
        }
        // 用户 → auth session → refresh 的固定锁顺序与设备删除一致，避免交叉死锁。
        val row = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction InvalidToken
        if (row[RefreshTokens.revokedAt] != null) {
            if (session != null && session[AuthSessions.revokedAt] == null) {
                revokeSessionInCurrentTransaction(userId, sessionId, now)
                return@transaction SessionCompromised(userId, sessionId)
            }
            return@transaction InvalidToken
        }
        if (row[RefreshTokens.expiresAt] <= now) {
            val hasActiveReplacement = sessionId.isNotBlank() && RefreshTokens.selectAll().where {
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    (RefreshTokens.tokenHash neq hash) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.firstOrNull() != null
            if (!hasActiveReplacement && session != null && session[AuthSessions.revokedAt] == null) {
                revokeSessionInCurrentTransaction(userId, sessionId, now)
            }
            return@transaction InvalidToken
        }
        if (user[Users.suspendedUntil] > now) {
            return@transaction UserSuspended
        }
        if (session == null || session[AuthSessions.revokedAt] != null) {
            RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
                it[revokedAt] = now
            }
            return@transaction InvalidToken
        }
        RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
            it[revokedAt] = now
        }
        AuthSessions.update({ AuthSessions.id eq sessionId }) {
            it[updatedAt] = now
        }
        RefreshTokens.insert {
            it[tokenHash] = replacementHash
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.sessionId] = sessionId
            it[createdAt] = now
            it[expiresAt] = replacementExpiresAt
            it[revokedAt] = null
        }
        RotateRefreshResult.Success(userId, sessionId, replacementToken, replacementExpiresAt)
    }
}
