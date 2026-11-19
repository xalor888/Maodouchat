package com.maodouchat.domain.conversation

sealed interface NotificationTargetReplayDecision {
    /** 尚未登录或 Nav 未就绪：继续等待（调用方 delay）。 */
    data object ContinueWaiting : NotificationTargetReplayDecision

    /** 会话代际或账号归属不匹配：丢弃待办。 */
    data object Drop : NotificationTargetReplayDecision

    /** 可导航到目标路由。 */
    data object Navigate : NotificationTargetReplayDecision
}
