package com.maodouchat.network

/**
 * Cold-start 登录态判定：无 userId 或缺 refresh 的过期 access 不能当已登录，
 * 否则 MainActivity 会直进 MAIN，表现为「token 过期进不去 / 恢复后空白」。
 */
internal object SessionPresencePolicy {
    fun isLoggedIn(
        token: String?,
        userId: String?,
        accessTokenExpiresAt: Long,
        refreshToken: String?,
        refreshTokenExpiresAt: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (token.isNullOrBlank()) return false
        if (userId.isNullOrBlank()) return false
        // access 未过期（或未记录过期时间）即视为已登录——refresh 本地记录失准时
        // 不能把仍有效的 access 误踢回登录页。
        if (accessTokenExpiresAt <= 0L || accessTokenExpiresAt > nowMs) return true
        // access 已过期：必须有 refresh，且 refresh 未明确过期。
        if (refreshToken.isNullOrBlank()) return false
        if (refreshTokenExpiresAt > 0L && refreshTokenExpiresAt <= nowMs) return false
        return true
    }
}
