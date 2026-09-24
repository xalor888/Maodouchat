package com.maodouchat.navigation

/**
 * P08：通知 / 深链待办目标在 NavHost 就绪前的回放门闩（纯决策）。
 *
 * Activity 只注入登录态、当前路由与会话代际；不在此处读 TokenManager。
 */
object NotificationTargetReplayPolicy {

    sealed interface Decision {
        /** 尚未登录或 Nav 未就绪：继续等待（调用方 delay）。 */
        data object ContinueWaiting : Decision

        /** 会话代际或账号归属不匹配：丢弃待办。 */
        data object Drop : Decision

        /** 可导航到目标路由。 */
        data object Navigate : Decision
    }

    /**
     * PublicProfile / GroupInvite 在登录前到达时 owner 可能为空，
     * 登录后不得因 owner 校验丢弃（与 MainActivity 历史语义一致）。
     */
    fun bypassesOwnerCheck(target: NotificationTarget): Boolean =
        target is NotificationTarget.PublicProfile ||
            target is NotificationTarget.GroupInvite

    fun evaluate(
        target: NotificationTarget,
        isLoggedIn: Boolean,
        currentRoute: String?,
        loginRoute: String,
        liveSessionGeneration: Long,
        liveUserId: String?,
    ): Decision {
        if (target.sessionGeneration != liveSessionGeneration) {
            return Decision.Drop
        }

        val navReady = isLoggedIn &&
            currentRoute != null &&
            currentRoute != loginRoute

        if (!navReady) {
            if (!bypassesOwnerCheck(target)) {
                val uid = liveUserId?.takeIf(String::isNotBlank)
                if (uid != null && target.ownerUserId != uid) {
                    return Decision.Drop
                }
            }
            return Decision.ContinueWaiting
        }

        if (!bypassesOwnerCheck(target) && target.ownerUserId != liveUserId) {
            return Decision.Drop
        }
        return Decision.Navigate
    }
}
