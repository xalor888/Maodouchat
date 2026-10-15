package com.maodouchat.navigation

/** 通知中心行点击打开结果（原 NotificationCenterOpenController.Outcome，零行为改动搬出）。 */
sealed interface NotificationCenterOpenOutcome {
    /** 导航到该路由（UI 侧加 `launchSingleTop`）。 */
    data class Navigate(val route: String) : NotificationCenterOpenOutcome

    /** 清栈回上一页（未接箱/联系人 Tab）。 */
    data object PopBackStack : NotificationCenterOpenOutcome

    /** 已登出/会话失效：回登录页并清栈（8.49：与 401 路径对齐）。 */
    data object GoLogin : NotificationCenterOpenOutcome

    /** 无动作。 */
    data object None : NotificationCenterOpenOutcome
}
