package com.maodouchat.ui.navigation

/**
 * P08：深链 / 系统入口在执行业务导航前的认证门闩（纯决策）。
 *
 * Activity 只查询本策略，不在此处写 TokenManager；登录态由调用方注入。
 */
object AppLinkAuthGate {

    sealed interface Decision {
        /** 已登录（或不要求登录），可导航到目标路由。 */
        data class Proceed(val destination: AppLinkDestination) : Decision

        /** 需要登录；保留目标供登录成功后回放。 */
        data class RequireLogin(val pending: AppLinkDestination) : Decision
    }

    fun evaluate(destination: AppLinkDestination, isLoggedIn: Boolean): Decision {
        if (!destination.requiresAuth || isLoggedIn) {
            return Decision.Proceed(destination)
        }
        return Decision.RequireLogin(destination)
    }
}
