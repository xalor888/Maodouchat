package com.maodouchat.navigation

/** 通知/深链待办目标回放门闩的纯决策结果（原 NotificationTargetReplayPolicy.Decision，零行为改动搬出）。 */
sealed interface NotificationTargetReplayDecision {
    /** 尚未登录或 Nav 未就绪：继续等待（调用方 delay）。 */
    data object ContinueWaiting : NotificationTargetReplayDecision

    /** 会话代际或账号归属不匹配：丢弃待办。 */
    data object Drop : NotificationTargetReplayDecision

    /** 可导航到目标路由。 */
    data object Navigate : NotificationTargetReplayDecision
}
