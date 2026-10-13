package com.maodouchat.server.repository

// access token 准入校验簇：版本/封禁/会话/吊销综合判定（原 AuthTokenAccess 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RevokedAccessTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AuthTokenRepository.isAccessTokenAllowed(
    userId: String,
    tokenVersion: Long,
    tokenId: String?,
    authSessionId: String? = null,
    requireAuthSession: Boolean = false
): Boolean {
    return transaction {
        val row = Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.takeIf { it[Users.deletedAt] == null }
            ?: return@transaction false
        // 封禁期内拒绝一切 access token（含封禁前签发、刷新竞态新发的）
        if (row[Users.suspendedUntil] > System.currentTimeMillis()) return@transaction false
        if (row[Users.accessTokenVersion] != tokenVersion) return@transaction false
        if (requireAuthSession && authSessionId.isNullOrBlank()) return@transaction false
        if (!authSessionId.isNullOrBlank()) {
            val sessionActive = AuthSessions.selectAll().where {
                (AuthSessions.id eq authSessionId) and
                    (AuthSessions.userId eq userId) and
                    AuthSessions.revokedAt.isNull()
            }.firstOrNull() != null
            if (!sessionActive) return@transaction false
        }
        if (tokenId.isNullOrBlank()) return@transaction true
        RevokedAccessTokens.selectAll()
            .where { RevokedAccessTokens.tokenId eq tokenId }
            .empty()
    }
}
