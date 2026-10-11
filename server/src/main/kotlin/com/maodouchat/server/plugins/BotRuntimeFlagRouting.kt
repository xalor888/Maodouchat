package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 运行时 flag 投影端点（getRuntimeFlags 等，见簇文件）。 */
internal fun Route.configureBotRuntimeFlagRoutes(botRateLimiter: BoundedRateLimiter) {
    configureBotRuntimeFlagProjectionRoutes(botRateLimiter)
}
