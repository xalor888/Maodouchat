package com.maodouchat.security

import com.maodouchat.session.CurrentSession

/**
 * Shared mid-batch / mid-op gate for background workers that encrypt or call REST.
 * Abort while local purge is active, after logout clears tokens, or when the local user id
 * no longer matches the batch owner.
 */
object BackgroundSessionGate {
    /**
     * 只给「期望的账号」——**实时会话由本方法自己读**。
     *
     * 为什么这样更好：调用点原本要自己写
     * `liveToken = tokenManager.getToken(), liveUserId = tokenManager.getUserId()`，
     * 而这两个值唯一的用途就是交回给这里判断。全项目有 240 处这样的重复
     * （`ui/` 内 220 处），每处都可能写错——漏读一次就变成「用旧令牌判断当前会话还在不在」，
     * 而那正是本门禁要防的事。把读会话的责任收进判断本身，就不会漏。
     *
     * 实时会话的读法只有一处：[com.maodouchat.session.CurrentSession]。
     * 无会话时那里返回空快照，这里按「会话不可用」处理，与读到空令牌同义。
     *
     * `:pushdaemon` 是另一个进程，但它不使用本门禁（`push/` 下零引用）。
     */
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
    ): Boolean {
        if (SecureSessionManager.isPurgeInProgress()) return false
        if (expectedUserId.isBlank()) return false
        if (liveToken.isNullOrBlank() || liveUserId.isNullOrBlank()) return false
        return liveUserId == expectedUserId
    }
}
