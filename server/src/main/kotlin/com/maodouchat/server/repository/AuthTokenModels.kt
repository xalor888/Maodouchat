package com.maodouchat.server.repository

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
