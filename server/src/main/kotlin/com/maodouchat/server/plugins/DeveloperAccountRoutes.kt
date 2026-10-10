package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 开发者账号路由门面：保留原签名，按域委托给簇。 */
internal fun Route.configureDeveloperAccountRoutes() {
    val userRepo = devUserRepo
    val authTokenRepo = devAuthTokenRepo
    val developerLoginRateLimiter = BoundedRateLimiter()
    /** 8.131：开发者登录按账号限流（防轮换源 IP 爆破，与主登录 loginEmailRateLimiter 同策略）。 */
    val developerLoginEmailRateLimiter = BoundedRateLimiter()
    val developerBotCreateRateLimiter = BoundedRateLimiter()
    val developerBotTokenRateLimiter = BoundedRateLimiter()
    val developerBotSettingsRateLimiter = BoundedRateLimiter()

    configureDeveloperAccountSessionRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        developerLoginRateLimiter = developerLoginRateLimiter,
        developerLoginEmailRateLimiter = developerLoginEmailRateLimiter,
    )

    configureDeveloperAccountBotRoutes(
        devParticipantRepo = devParticipantRepo,
        devJson = devJson,
        developerBotCreateRateLimiter = developerBotCreateRateLimiter,
        developerBotTokenRateLimiter = developerBotTokenRateLimiter,
        developerBotSettingsRateLimiter = developerBotSettingsRateLimiter,
    )

    configureDeveloperAccountCommandRoutes(
        developerBotSettingsRateLimiter = developerBotSettingsRateLimiter,
    )
}
