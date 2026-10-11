package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.LoginAttemptGate
import com.maodouchat.server.service.SessionService
import io.ktor.server.routing.Route

// 登录会话（登录/刷新/登出）：按端点拆为三簇，本文件只做委托。
internal fun Route.configureAuthLoginRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    loginGate: LoginAttemptGate,
    sessionService: SessionService,
) {
    configureAuthLoginLoginRoutes(userRepo, authTokenRepo, loginIpRateLimiter, loginEmailRateLimiter, loginGate)
    configureAuthLoginRefreshRoutes(userRepo, authTokenRepo, loginIpRateLimiter)
    configureAuthLoginLogoutRoutes(authTokenRepo, pushTokenRepo, loginIpRateLimiter, sessionService)
}
