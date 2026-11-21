package com.maodouchat.core.realtime

/**
 * WS 1008 关闭原因的分类：服务端用 reason 文案区分"登录已死"与"token 过期"。
 * 前者不得再用旧 token 重连（须走登录失效流程），后者刷新 token 后重连即可。
 */
object WebSocketCloseReasonPolicy {
    fun isAuthDeathReason(reason: String?): Boolean {
        val r = reason.orEmpty().lowercase()
        // "expired" / "会话已过期" 是可恢复的（token 刷新后重连即可），不视为永久 auth death
        return r.contains("会话已失效") ||
            r.contains("已退出") ||
            r.contains("密码已修改") ||
            r.contains("账号已注销") ||
            r.contains("封禁") ||
            r.contains("已被移除") ||
            r.contains("被移除") ||
            r.contains("unauth") ||
            r.contains("revoked")
    }

    /** 1008/认证类关闭原因中可恢复的「访问令牌到期」子集：刷新 token 后重连即可，不得 purge。 */
    fun isRecoverableExpiryReason(reason: String?): Boolean {
        val r = reason.orEmpty().lowercase()
        return r.contains("过期") ||
            r.contains("expired") ||
            r.contains("expires") ||
            r.contains("token")
    }
}
