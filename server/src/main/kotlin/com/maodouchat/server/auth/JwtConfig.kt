package com.maodouchat.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import com.auth0.jwt.interfaces.JWTVerifier
import com.maodouchat.server.config.ServerConfig
import java.util.Date
import java.util.UUID

object JwtConfig {
    private const val ISSUER = "maodouchat"
    private const val CLAIM_TOKEN_VERSION = "token_version"
    private const val CLAIM_TOKEN_USE = "token_use"
    private const val CLAIM_AUTH_SESSION_ID = "auth_session_id"
    private const val TOKEN_USE_ADMIN = "admin_session"
    const val VALIDITY_MS = 15L * 60 * 1000 // 15 分钟，配合 refresh token 轮换
    const val ADMIN_VALIDITY_MS = 5L * 60 * 1000 // 管理后台高权限短会话

    private val algorithm = Algorithm.HMAC256(ServerConfig.jwtSecret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .build()

    fun generateToken(userId: String, tokenVersion: Long = 0): String =
        generateAccessToken(userId, tokenVersion).token

    /** Same clock for JWT exp claim and AuthResponse.expiresAt (avoid dual-now skew). */
    data class AccessToken(val token: String, val expiresAtMs: Long)

    fun generateAccessToken(
        userId: String,
        tokenVersion: Long = 0,
        authSessionId: String? = null
    ): AccessToken {
        val expiresAt = System.currentTimeMillis() + VALIDITY_MS
        val builder = JWT.create()
            .withIssuer(ISSUER)
            .withSubject(userId)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(CLAIM_TOKEN_VERSION, tokenVersion)
            .withIssuedAt(Date())
            .withExpiresAt(Date(expiresAt))
        authSessionId?.takeIf { it.isNotBlank() }?.let {
            builder.withClaim(CLAIM_AUTH_SESSION_ID, it)
        }
        val token = builder.sign(algorithm)
        return AccessToken(token, expiresAt)
    }

    fun accessTokenExpiresAt(): Long = System.currentTimeMillis() + VALIDITY_MS

    fun generateAdminToken(userId: String, tokenVersion: Long = 0, issuedAtMs: Long = System.currentTimeMillis()): String {
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience("maodouchat-admin")
            .withSubject(userId)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(CLAIM_TOKEN_VERSION, tokenVersion)
            .withClaim(CLAIM_TOKEN_USE, TOKEN_USE_ADMIN)
            .withIssuedAt(Date(issuedAtMs))
            .withExpiresAt(Date(issuedAtMs + ADMIN_VALIDITY_MS))
            .sign(algorithm)
    }

    fun adminTokenExpiresAt(issuedAtMs: Long): Long = issuedAtMs + ADMIN_VALIDITY_MS

    fun isAdminSession(payload: Payload): Boolean =
        payload.getClaim(CLAIM_TOKEN_USE).asString() == TOKEN_USE_ADMIN

    fun tokenVersion(payload: Payload): Long = payload.getClaim(CLAIM_TOKEN_VERSION).asLong() ?: 0

    fun authSessionId(payload: Payload): String? =
        payload.getClaim(CLAIM_AUTH_SESSION_ID).asString()?.takeIf { it.isNotBlank() }

    fun verifyToken(token: String): DecodedJWT? {
        return try {
            verifier.verify(token)
        } catch (e: Exception) {
            null
        }
    }

    fun getUserIdFromToken(token: String): String? {
        return verifyToken(token)?.subject
    }
}
