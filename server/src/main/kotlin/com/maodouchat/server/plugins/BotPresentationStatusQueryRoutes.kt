package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** Bot 状态查询路由：按域拆为状态快照、服务器元信息、能力矩阵 3 簇（零行为改动）。 */
internal fun Route.configureBotPresentationStatusQueryRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    configureBotPresentationStatusQueryStatusRoutes(
        botRateLimiter = botRateLimiter,
    )

    configureBotPresentationStatusQueryMetaRoutes(
        botRateLimiter = botRateLimiter,
    )

    configureBotPresentationStatusQueryFeaturesRoutes(
        botRateLimiter = botRateLimiter,
    )
}
