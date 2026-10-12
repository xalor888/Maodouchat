package com.maodouchat.server.repository

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.RefreshTokens
import org.jetbrains.exposed.sql.count
import com.maodouchat.server.db.RevokedAccessTokens
import com.maodouchat.server.db.Users
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class IssuedRefreshToken(val token: String, val expiresAt: Long, val sessionId: String)

// 8.42：改为 IllegalArgumentException——登录/注册签发 refresh 时会话并发失效应回 400
// （此前 IllegalStateException 落全局 Throwable 处理器回 500；refresh 路由对同条件显式 401/403）
class InactiveAuthSessionException : IllegalArgumentException("Authentication session is no longer active")

// MessageDigest 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

class AuthTokenRepository {


    sealed class RotateRefreshResult {
        data class Success(
            val userId: String,
            val sessionId: String,
            val refreshToken: String,
            val refreshExpiresAt: Long
        ) : RotateRefreshResult()
        data class SessionCompromised(val userId: String, val sessionId: String) : RotateRefreshResult()
        data object InvalidToken : RotateRefreshResult()
        data object UserSuspended : RotateRefreshResult()
        data object UserMissing : RotateRefreshResult()
    }


    data class RevokedSession(val userId: String, val sessionId: String?)


    data class RefreshSessionRow(
        val tokenHashPrefix: String,
        val createdAt: Long,
        val expiresAt: Long,
        val revokedAt: Long?
    )


    /**
     * Revoke active refresh sessions whose SHA-256 hex starts with [prefix] (min 8 chars).
     * Used by admin session inspector without exposing full hashes.
     */
    data class RevokeByPrefixResult(
        val count: Int,
        val sessionIds: Set<String>
    )


    internal fun revokeSessionInCurrentTransaction(userId: String, sessionId: String, now: Long): Int {
        val sessionChanged = AuthSessions.update({
            (AuthSessions.id eq sessionId) and
                (AuthSessions.userId eq userId) and
                AuthSessions.revokedAt.isNull()
        }) {
            it[revokedAt] = now
            it[updatedAt] = now
        }
        // 返回实际撤销的 refresh token 数（而非仅会话数），供上层统计准确的吊销条数。
        val tokenChanged = RefreshTokens.update({
            (RefreshTokens.userId eq userId) and
                (RefreshTokens.sessionId eq sessionId) and
                RefreshTokens.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        }
        PushTokens.deleteWhere {
            (PushTokens.userId eq userId) and (PushTokens.authSessionId eq sessionId)
        }
        return sessionChanged + tokenChanged
    }

    internal fun revokeAllForUserInCurrentTransaction(userId: String, now: Long): Int {
        AuthSessions.update({ (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull() }) {
            it[revokedAt] = now
            it[updatedAt] = now
        }
        val revoked = RefreshTokens.update({
            (RefreshTokens.userId eq userId) and RefreshTokens.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        }
        PushTokens.deleteWhere { PushTokens.userId eq userId }
        return revoked
    }


    internal fun generateRefreshToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal fun hashToken(token: String): String {
        val digest = sha256ThreadLocal.get().apply { reset() }.digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REFRESH_TOKEN_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000
        /** 8.34：每用户最大活跃会话数（超出淘汰最旧）。 */
        const val MAX_ACTIVE_SESSIONS = 10
        /** 8.34：已吊销会话行保留期（之后物理删除，防表无界增长）。 */
        const val SESSION_ROW_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        private val secureRandom = SecureRandom()
    }
}
