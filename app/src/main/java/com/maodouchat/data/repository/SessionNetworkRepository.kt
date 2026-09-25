package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import kotlinx.coroutines.flow.SharedFlow

/**
 * **会话层**的网络面（G328c）：刷新访问令牌、广播「会话已失效」。
 *
 * 为什么单列一类而不是并进别的端点仓库：这两个操作**有副作用**——
 * `refreshAccessTokenForCurrentSession` 会读写本机令牌存储，`tokenExpiredEvents`
 * 是别人（网络层在 401 时）发出的广播。其余仓库都是「发一个请求、拿一个结果」的纯转发，
 * 把它们混在一起会掩盖「这个调用会改本机会话状态」这件事。
 *
 * ⚠️ `refreshAccessTokenForCurrentSession` 返回 `String?`（不是 `Result`）：
 * 这是 `ApiService` 原有的形状，成功给新令牌、失败给 null 且**不抛**。
 * 搬移不改形状，否则调用方那些 `?: 原来的令牌` 的兜底会全部失效。
 */
internal class SessionNetworkRepository(
    private val refreshApi: suspend () -> String? = { ApiService.refreshAccessTokenForCurrentSession() },
    /** 令牌失效广播：网络层在任何请求拿到「会话作废」时发出，UI 据此回登录页。 */
    val tokenExpiredEvents: SharedFlow<ApiService.TokenExpiredEvent> = ApiService.tokenExpired,
) {
    suspend fun refreshAccessToken(): String? = refreshApi()
}
