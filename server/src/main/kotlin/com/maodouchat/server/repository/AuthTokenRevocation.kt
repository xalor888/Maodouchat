package com.maodouchat.server.repository

// 撤销簇：原 AuthTokenRevoke 扩展按域拆出（同包调用，零行为改动）。

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun AuthTokenRepository.revoke(refreshToken: String): Boolean {
    return revokeAndGetSession(refreshToken) != null
}

/**
 * 吊销 refresh 所属的整个登录会话，并返回其身份供 WebSocket / 推送清理。
 * 旧版 refresh 没有 sessionId 时只吊销该 token，且不能再用于轮换。
 */
fun AuthTokenRepository.revokeAndGetSession(refreshToken: String): AuthTokenRepository.RevokedSession? {
    val hash = hashToken(refreshToken)
    val now = System.currentTimeMillis()
    return transaction {
        val initialRow = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .firstOrNull()
            ?: return@transaction null
        val userId = initialRow[RefreshTokens.userId]
        val sessionId = initialRow[RefreshTokens.sessionId].takeIf { it.isNotBlank() }
        Users.selectAll()
            .where { Users.id eq userId }
            .forUpdate()
            .firstOrNull()
        if (sessionId != null) {
            AuthSessions.selectAll()
                .where { (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId) }
                .forUpdate()
                .firstOrNull()
        }
        val row = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction null
        if (sessionId == null) {
            RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
                it[revokedAt] = now
            }
        } else {
            AuthSessions.update({
                (AuthSessions.id eq sessionId) and
                    (AuthSessions.userId eq userId) and
                    AuthSessions.revokedAt.isNull()
            }) {
                it[revokedAt] = now
                it[updatedAt] = now
            }
            RefreshTokens.update({
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    RefreshTokens.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
        }
        AuthTokenRepository.RevokedSession(userId, sessionId)
    }
}
