package com.maodouchat.core.session

import kotlinx.coroutines.flow.Flow

/**
 * 会话协调器（A02）：认证会话生命周期与账号世代的唯一入口。
 * 领域层只依赖此接口，不直接读 TokenManager / WebSocket / Application。
 */
interface SessionCoordinator {
    /** 当前会话快照（未登录返回 [SessionContext.EMPTY]）。 */
    val current: SessionContext

    /** 会话变化流，通知消息 / Push / Widget / AI 等模块。 */
    val changes: Flow<SessionContext>

    /** 并发安全地刷新 token（single-flight）。 */
    suspend fun refreshToken(): Boolean

    /** 登出当前账号。 */
    suspend fun logout()
}
