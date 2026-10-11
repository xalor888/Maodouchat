package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** Bot 命令集（查询/写拆为独立簇，本函数只做委托）。 */
internal fun Route.configureBotCommandRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {
    configureBotCommandQueryRoutes(botSendRateLimiter)
    configureBotCommandWriteRoutes(botSendRateLimiter)
}
