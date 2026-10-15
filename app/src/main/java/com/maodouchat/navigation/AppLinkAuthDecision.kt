package com.maodouchat.navigation

/** 深链/系统入口认证门闩的纯决策结果（原 AppLinkAuthGate.Decision，零行为改动搬出）。 */
sealed interface AppLinkAuthDecision {
    /** 已登录（或不要求登录），可导航到目标路由。 */
    data class Proceed(val destination: AppLinkDestination) : AppLinkAuthDecision

    /** 需要登录；保留目标供登录成功后回放。 */
    data class RequireLogin(val pending: AppLinkDestination) : AppLinkAuthDecision
}
