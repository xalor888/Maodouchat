package com.maodouchat.server.plugins

import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CacheService
import io.ktor.server.routing.Route

/** 账号资料写操作：头像/用户名/资料修改（按域拆为 3 簇的委托门面）。 */
internal fun Route.configureAccountProfileWriteRoutes(
    userRepo: UserRepository,
    cacheService: CacheService,
    avatarRateLimiter: BoundedRateLimiter,
    userSearchRateLimiter: BoundedRateLimiter,
) {
    configureAccountAvatarRoutes(
        userRepo = userRepo,
        avatarRateLimiter = avatarRateLimiter,
    )
    configureAccountProfileUpdateRoutes(
        userRepo = userRepo,
        cacheService = cacheService,
    )
    configureAccountUsernameRoutes(
        userRepo = userRepo,
        cacheService = cacheService,
        userSearchRateLimiter = userSearchRateLimiter,
    )
}
