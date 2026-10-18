package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.CacheService
import com.maodouchat.server.service.LoginAttemptGate
import com.maodouchat.server.service.SessionService
import io.ktor.server.routing.Route

// 认证与公共身份路由的装配入口：公开更新/公开主页/账号认证/登录四组，按名透传。
internal fun Route.configureAuthRoutes(
    cacheService: CacheService,
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
    publicProfileRateLimiter: BoundedRateLimiter,
    loginGate: LoginAttemptGate,
    sessionService: SessionService,
) {
    configurePublicUpdateRoutes(cacheService)
    configurePublicProfileRoutes(
        userRepository = userRepo,
        cacheService = cacheService,
        rateLimiter = publicProfileRateLimiter,
    )
    configureAuthAccountRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        loginIpRateLimiter = loginIpRateLimiter,
        loginEmailRateLimiter = loginEmailRateLimiter,
        sendCodeRateLimiter = sendCodeRateLimiter,
        sendCodeIpRateLimiter = sendCodeIpRateLimiter,
        sessionService = sessionService,
    )
    configureAuthLoginRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        pushTokenRepo = pushTokenRepo,
        loginIpRateLimiter = loginIpRateLimiter,
        loginEmailRateLimiter = loginEmailRateLimiter,
        loginGate = loginGate,
        sessionService = sessionService,
    )
}
