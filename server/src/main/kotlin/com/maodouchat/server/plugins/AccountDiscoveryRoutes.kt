package com.maodouchat.server.plugins

import com.maodouchat.server.repository.NearbyRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route

/** 发现：用户列表/搜索、附近位置读写/查询（按域拆为 2 簇的委托门面）。 */
internal fun Route.configureAccountDiscoveryRoutes(
    userRepo: UserRepository,
    nearbyRepo: NearbyRepository,
    userSearchRateLimiter: BoundedRateLimiter,
    nearbyUpdateRateLimiter: BoundedRateLimiter,
    nearbyQueryRateLimiter: BoundedRateLimiter,
) {
    configureAccountUserSearchRoutes(
        userRepo = userRepo,
        userSearchRateLimiter = userSearchRateLimiter,
    )
    configureAccountNearbyRoutes(
        userRepo = userRepo,
        nearbyRepo = nearbyRepo,
        nearbyUpdateRateLimiter = nearbyUpdateRateLimiter,
        nearbyQueryRateLimiter = nearbyQueryRateLimiter,
    )
}
