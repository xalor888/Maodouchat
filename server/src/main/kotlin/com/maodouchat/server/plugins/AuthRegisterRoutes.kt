package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route


/** 账号注册（注册/验证码发送/验证码注册）。 */
internal fun Route.configureAuthRegisterRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
) {
    configureAuthRegisterDirectRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        loginIpRateLimiter = loginIpRateLimiter,
    )
    configureAuthRegisterCodeRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        loginIpRateLimiter = loginIpRateLimiter,
        loginEmailRateLimiter = loginEmailRateLimiter,
        sendCodeRateLimiter = sendCodeRateLimiter,
        sendCodeIpRateLimiter = sendCodeIpRateLimiter,
    )
}
