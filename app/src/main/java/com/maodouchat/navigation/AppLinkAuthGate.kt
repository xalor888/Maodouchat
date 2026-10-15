package com.maodouchat.navigation

/**
 * P08：深链 / 系统入口在执行业务导航前的认证门闩（纯决策）。
 *
 * Activity 只查询本策略，不在此处写 TokenManager；登录态由调用方注入。
 */
object AppLinkAuthGate {

    fun evaluate(destination: AppLinkDestination, isLoggedIn: Boolean): AppLinkAuthDecision {
        if (!destination.requiresAuth || isLoggedIn) {
            return AppLinkAuthDecision.Proceed(destination)
        }
        return AppLinkAuthDecision.RequireLogin(destination)
    }
}
