package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route


/** 验证码注册：发送验证码 / 验证码注册。 */
internal fun Route.configureAuthRegisterCodeRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
)
{
    configureAuthRegisterSendCodeRoutes(
        userRepo = userRepo,
        sendCodeRateLimiter = sendCodeRateLimiter,
        sendCodeIpRateLimiter = sendCodeIpRateLimiter,
    )
    configureAuthRegisterWithCodeRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        loginIpRateLimiter = loginIpRateLimiter,
        loginEmailRateLimiter = loginEmailRateLimiter,
    )
}
