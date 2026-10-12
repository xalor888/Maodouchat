package com.maodouchat.server.repository

// 会话查询簇：原 AuthTokenRefresh 顶层扩展函数按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AuthTokenRepository.listActiveRefreshSessions(userId: String, includeRevoked: Boolean = false): List<AuthTokenRepository.RefreshSessionRow> = transaction {
    val now = System.currentTimeMillis()
    val activeSessionIds = if (includeRevoked) emptySet() else {
        AuthSessions.select(AuthSessions.id).where {
            (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
        }.map { it[AuthSessions.id] }.toSet()
    }
    RefreshTokens.selectAll()
        .where { RefreshTokens.userId eq userId }
        .orderBy(
            RefreshTokens.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            RefreshTokens.tokenHash to org.jetbrains.exposed.sql.SortOrder.DESC
        )
        .mapNotNull { row ->
            val revoked = row[RefreshTokens.revokedAt]
            if (!includeRevoked && (
                    revoked != null ||
                        row[RefreshTokens.expiresAt] <= now ||
                        row[RefreshTokens.sessionId] !in activeSessionIds
                    )
            ) return@mapNotNull null
            AuthTokenRepository.RefreshSessionRow(
                tokenHashPrefix = row[RefreshTokens.tokenHash].take(12),
                createdAt = row[RefreshTokens.createdAt],
                expiresAt = row[RefreshTokens.expiresAt],
                revokedAt = revoked
            )
        }
}


fun AuthTokenRepository.countActiveRefreshSessions(userId: String): Int = transaction {
    val now = System.currentTimeMillis()
    val activeSessionIds = AuthSessions.select(AuthSessions.id).where {
        (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
    }.map { it[AuthSessions.id] }
    if (activeSessionIds.isEmpty()) return@transaction 0
    RefreshTokens.selectAll()
        .where {
            (RefreshTokens.userId eq userId) and
                RefreshTokens.revokedAt.isNull() and
                (RefreshTokens.sessionId inList activeSessionIds) and
                (RefreshTokens.expiresAt greater now)
        }
        .count()
        .toInt()
}


/**
 * 8.48 修复 M7：批量统计多个用户的活跃 refresh 会话数（/sessions-summary-export
 * 此前逐用户 countActiveRefreshSessions → N+1，limit 2 万 → 2 万次查询）。
 * 一次事务内先取批量活跃 session，再 GROUP BY userId 聚合 refresh token。
 */
fun AuthTokenRepository.countActiveRefreshSessionsBatch(userIds: List<String>): Map<String, Int> = transaction {
    if (userIds.isEmpty()) return@transaction emptyMap()
    val now = System.currentTimeMillis()
    val sessions = AuthSessions.select(AuthSessions.id, AuthSessions.userId).where {
        (AuthSessions.userId inList userIds) and AuthSessions.revokedAt.isNull()
    }.map { it[AuthSessions.id] to it[AuthSessions.userId] }
    if (sessions.isEmpty()) return@transaction userIds.associateWith { 0 }
    val sessionIdToUser = sessions.toMap()
    val counts = RefreshTokens
        .slice(RefreshTokens.userId, RefreshTokens.tokenHash.count())
        .selectAll()
        .where {
            (RefreshTokens.userId inList userIds) and
                RefreshTokens.revokedAt.isNull() and
                (RefreshTokens.sessionId inList sessionIdToUser.keys) and
                (RefreshTokens.expiresAt greater now)
        }
        .groupBy(RefreshTokens.userId)
        .associate { it[RefreshTokens.userId] to it[RefreshTokens.tokenHash.count()].toInt() }
    userIds.associateWith { counts[it] ?: 0 }
}
