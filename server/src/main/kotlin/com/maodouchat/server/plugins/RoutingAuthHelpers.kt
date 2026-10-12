package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.AuthResponse
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.getAccessTokenVersion
import com.maodouchat.server.repository.issueRefreshToken
import com.maodouchat.server.repository.IssuedRefreshToken
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * 认证/地址/账号 helper 簇（从 RoutingHelpers.kt 按域拆出，零行为改动）。
 */

// ── 地址解析 ──────────────────────────

internal fun ApplicationCall.remoteHost(): String {
    return resolveClientAddress(
        trustProxyHeaders = ServerConfig.trustProxyHeaders,
        xRealIp = request.headers["X-Real-IP"],
        xForwardedFor = request.headers[HttpHeaders.XForwardedFor],
        directAddress = request.local.remoteHost
    )
}

internal fun resolveClientAddress(
    trustProxyHeaders: Boolean,
    xRealIp: String?,
    xForwardedFor: String?,
    directAddress: String?
): String {
    fun proxyIp(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 64 && it.all { char -> char.isDigit() || char in "abcdefABCDEF:." } }

    if (trustProxyHeaders) {
        proxyIp(xRealIp)?.let { return it }
        proxyIp(xForwardedFor?.substringAfterLast(','))?.let { return it }
    }
    return directAddress
        ?.trim()
        ?.take(100)
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"
}

internal fun String?.bearerTokenOrNull(): String? {
    val header = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val separator = header.indexOf(' ')
    if (separator <= 0 || !header.substring(0, separator).equals("Bearer", ignoreCase = true)) return null
    val token = header.substring(separator + 1).trim()
    return token.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
}

// ── 认证辅助 ──────────────────────────

internal fun issueAuthResponse(
    user: UserResponse,
    authTokenRepo: AuthTokenRepository,
    issuedRefreshToken: IssuedRefreshToken? = null
): AuthResponse {
    val refreshToken = issuedRefreshToken ?: authTokenRepo.issueRefreshToken(user.id)
    val access = JwtConfig.generateAccessToken(
        userId = user.id,
        tokenVersion = authTokenRepo.getAccessTokenVersion(user.id),
        authSessionId = refreshToken.sessionId
    )
    return AuthResponse(
        token = access.token,
        userId = user.id,
        name = user.name,
        refreshToken = refreshToken.token,
        expiresAt = access.expiresAtMs,
        refreshExpiresAt = refreshToken.expiresAt
    )
}

// ── 账号辅助 ──────────────────────────

internal fun String.normalizedEmail(): String = trim().lowercase()

/** Returns whether [email]'s normalized domain is forbidden for new registrations. */
internal fun isRegistrationEmailDomainBlocked(
    email: String,
    blocklist: Set<String> = ServerConfig.emailDomainBlocklist,
): Boolean {
    val domain = email.normalizedEmail().substringAfterLast('@', "")
    return domain.isNotBlank() && domain in blocklist
}
