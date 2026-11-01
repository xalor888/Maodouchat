package com.maodouchat.security

import com.maodouchat.core.session.BackgroundSessionGatePolicy
import com.maodouchat.session.CurrentSession

/**
 * Shared mid-batch / mid-op gate for background workers that encrypt or call REST.
 * Abort while local purge is active, after logout clears tokens, or when the local user id
 * no longer matches the batch owner.
 */
object BackgroundSessionGate {
    // 实时会话由本方法自己读：调用点若先读令牌再传进来，漏读一次就会拿旧令牌做判断。
    fun mayContinue(expectedUserId: String): Boolean {
        val session = CurrentSession.snapshot()
        return mayContinue(expectedUserId, session.token, session.userId)
    }

    /**
     * 显式传实时值的形状。给两类调用方留着：
     * 1. 消息里带的本来就不是 `tokenManager` 的实时值（例如批次开始时捕获的令牌）；
     * 2. 需要在同一处同时判断多次、只想读一次会话的地方。
     */
    fun mayContinue(
        expectedUserId: String,
        liveToken: String?,
        liveUserId: String?,
    ): Boolean = BackgroundSessionGatePolicy.mayContinue(
        expectedUserId = expectedUserId,
        liveToken = liveToken,
        liveUserId = liveUserId,
        purgeInProgress = SecureSessionManager.isPurgeInProgress(),
    )
}
