package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route

// 账号注册与找回：按域拆为注册（注册/验证码发送/验证码注册）/ 找回（密码重置）两簇。
internal fun Route.configureAuthAccountRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
    sessionService: com.maodouchat.server.service.SessionService,
) {
    configureAuthRegisterRoutes(
        userRepo, authTokenRepo,
        loginIpRateLimiter, loginEmailRateLimiter, sendCodeRateLimiter, sendCodeIpRateLimiter,
    )
    configureAuthRecoveryRoutes(userRepo, loginIpRateLimiter, loginEmailRateLimiter, sessionService)
}
