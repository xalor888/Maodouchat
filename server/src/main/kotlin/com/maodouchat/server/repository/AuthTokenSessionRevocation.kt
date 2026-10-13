package com.maodouchat.server.repository

// 会话撤销簇：原 AuthTokenRevoke 扩展按域拆出（同包调用，零行为改动）。

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun AuthTokenRepository.revokeSession(userId: String, sessionId: String): Boolean {
    if (userId.isBlank() || sessionId.isBlank()) return false
    val now = System.currentTimeMillis()
    return transaction {
        Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction false
        AuthSessions.selectAll().where {
            (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId)
        }.forUpdate().firstOrNull() ?: return@transaction false
        revokeSessionInCurrentTransaction(userId, sessionId, now) > 0
    }
}

fun AuthTokenRepository.revokeByHashPrefixWithSessions(userId: String, prefix: String): AuthTokenRepository.RevokeByPrefixResult {
    val p = prefix.trim().lowercase()
    // 8.34 修复：8 字符（32bit）SHA-256 前缀可碰撞误吊销无关会话 → 下限提高到 12 字符
    if (p.length < 12) return AuthTokenRepository.RevokeByPrefixResult(0, emptySet())
    val now = System.currentTimeMillis()
    return transaction {
        Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction AuthTokenRepository.RevokeByPrefixResult(0, emptySet())
        val candidates = RefreshTokens.selectAll()
            .where {
                (RefreshTokens.userId eq userId) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }
            .mapNotNull { row ->
                val hash = row[RefreshTokens.tokenHash]
                hash.takeIf { it.startsWith(p) }?.let { it to row[RefreshTokens.sessionId] }
            }
        val matchedSessionIds = candidates.mapNotNull { (_, sessionId) ->
            sessionId.takeIf(String::isNotBlank)
        }.toSortedSet()
        matchedSessionIds.forEach { sessionId ->
            AuthSessions.selectAll().where {
                (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId)
            }.forUpdate().firstOrNull()
        }
        val lockedMatches = candidates.mapNotNull { (tokenHash, sessionId) ->
            RefreshTokens.selectAll().where {
                (RefreshTokens.tokenHash eq tokenHash) and
                    (RefreshTokens.userId eq userId) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.forUpdate().firstOrNull()?.let { tokenHash to sessionId }
        }
        val activeMatchedSessionIds = lockedMatches.mapNotNull { (_, sessionId) ->
            sessionId.takeIf(String::isNotBlank)
        }.toSet()
        var revokedTokenCount = 0
        activeMatchedSessionIds.forEach { sessionId ->
            revokedTokenCount += revokeSessionInCurrentTransaction(userId, sessionId, now)
        }
        val legacyHashes = lockedMatches.mapNotNull { (tokenHash, sessionId) ->
            tokenHash.takeIf { sessionId.isBlank() }
        }
        legacyHashes.forEach { tokenHash ->
            RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) { it[revokedAt] = now }
        }
        AuthTokenRepository.RevokeByPrefixResult(
            // 准确统计：被撤销的 refresh token 总数 = 各会话撤销的 token 数 + 遗留哈希数，
            // 而非会话数（一个会话可能含多条 refresh token，旧算法会低估）。
            count = revokedTokenCount + legacyHashes.size,
            sessionIds = activeMatchedSessionIds
        )
    }
}
