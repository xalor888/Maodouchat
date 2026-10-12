package com.maodouchat.server.repository

// 过期清理簇：原 AuthTokenRefresh 顶层扩展函数按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.RevokedAccessTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AuthTokenRepository.deleteExpired(now: Long = System.currentTimeMillis()): Int {
    val expiredSessions = transaction {
        RefreshTokens.select(RefreshTokens.userId, RefreshTokens.sessionId)
            .where {
                (RefreshTokens.expiresAt lessEq now) and
                    (RefreshTokens.sessionId neq "")
            }
            .map { it[RefreshTokens.userId] to it[RefreshTokens.sessionId] }
            .distinct()
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
    }
    expiredSessions.forEach { (userId, sessionId) ->
        transaction {
            Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction
            val session = AuthSessions.selectAll().where {
                (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId)
            }.forUpdate().firstOrNull() ?: return@transaction
            if (session[AuthSessions.revokedAt] != null) return@transaction
            val hasActiveRefresh = RefreshTokens.selectAll().where {
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.firstOrNull() != null
            if (!hasActiveRefresh) revokeSessionInCurrentTransaction(userId, sessionId, now)
        }
    }
    return transaction {
        val refreshDeleted = RefreshTokens.deleteWhere { RefreshTokens.expiresAt lessEq now }
        RevokedAccessTokens.deleteWhere { RevokedAccessTokens.expiresAt lessEq now }
        // 8.34 修复：AuthSessions 行此前只置 revokedAt 从不物理删除，表随登录次数无界增长
        //（每用户每次登录一行，注销才硬删）。超过保留期（90 天）的已吊销会话行硬删，
        // 其 refresh token 已在上面/会话撤销时清理，无外键残留。
        AuthSessions.deleteWhere { AuthSessions.revokedAt less now - AuthTokenRepository.SESSION_ROW_RETENTION_MS }
        refreshDeleted
    }
}
