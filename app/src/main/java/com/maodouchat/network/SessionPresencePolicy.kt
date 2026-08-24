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
        // access 已过期：只要本机还有 refresh 就视为仍登录。本地 refreshExpiresAt
        // 可能因时钟/落盘失准提前到期；真正作废只能由服务端 refresh 401 判定。
        // 否则断网 / 模拟器 network=0 会把用户踢回登录页。
        return !refreshToken.isNullOrBlank()
    }
}
