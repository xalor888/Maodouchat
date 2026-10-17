package com.maodouchat.network

/**
 * Token 过期事件 — 当 API 返回 401 时发射，UI 层监听后清除 Token 并跳转登录页
 */
data class TokenExpiredEvent(
    val ownerUserId: String,
    val sessionGeneration: Long,
)

internal data class AuthenticatedRequestSession(
    val manager: TokenManager,
    val userId: String,
    val accessToken: String
)

internal sealed class RefreshOutcome {
    data class Success(val token: String) : RefreshOutcome()
    /** 401 / 明确会话作废：应 tokenExpired */
    data object SessionDead : RefreshOutcome()
    /** 429/5xx/网络抖动：不得清库 */
    data object TransientFailure : RefreshOutcome()
    /** 请求所属账号已不再是当前会话：静默丢弃，禁止重放或清理新会话。 */
    data object SessionChanged : RefreshOutcome()
}
