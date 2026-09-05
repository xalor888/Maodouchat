package com.maodouchat.server.service

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.PushTokenRepository

/**
 * 会话服务：auth session 与推送 token 的绑定收尾（B02「拆 SessionService」）。
 *
 * 收敛 7 处重复的「废会话 + 清推送」组合：改密/重置密码/全设备登出/删号/
 * 临时封禁（×3）。WS 断开仍由各路由在调用后执行（实时面归属 realtime 域）。
 */
class SessionService(
    private val authTokenRepo: AuthTokenRepository = AuthTokenRepository(),
    private val pushTokenRepo: PushTokenRepository = PushTokenRepository(),
) {
    /**
     * 废掉该用户全部会话状态：access token 版本号 + 全量推送 token。
     * 调用方随后断开 WS（否则已签发 token 在 TTL 内仍可用、旧设备仍收推送）。
     */
    fun revokeAllUserSessions(userId: String) {
        authTokenRepo.rotateAccessTokenVersion(userId)
        pushTokenRepo.removeAllForUser(userId)
    }

    /** 结束单个 auth session（登出）：吊销会话 + 清该会话推送 token。 */
    fun endSession(userId: String, sessionId: String) {
        authTokenRepo.revokeSession(userId, sessionId)
        pushTokenRepo.removeForAuthSession(userId, sessionId)
    }
}
