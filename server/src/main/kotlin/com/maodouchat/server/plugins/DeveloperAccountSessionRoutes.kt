package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route

/** 开发者账号·会话：登录（IP+账号双限流）与当前用户。 */
internal fun Route.configureDeveloperAccountSessionRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    developerLoginRateLimiter: BoundedRateLimiter,
    developerLoginEmailRateLimiter: BoundedRateLimiter,
) {
    configureDeveloperAccountSessionLoginRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        developerLoginRateLimiter = developerLoginRateLimiter,
        developerLoginEmailRateLimiter = developerLoginEmailRateLimiter,
    )
    configureDeveloperAccountSessionMeRoutes(
        userRepo = userRepo,
    )
}
