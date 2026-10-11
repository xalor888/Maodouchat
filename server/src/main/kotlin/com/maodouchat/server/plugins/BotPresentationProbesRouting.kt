package com.maodouchat.server.plugins

import io.ktor.server.routing.*

/** Bot 版本/健康探针（自 BotPresentationCardsRouting.kt 拆分）。 */
internal fun Route.configureBotPresentationProbesRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    configureBotPresentationProbesHealthRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotPresentationProbesFlagsRoutes(
        botRateLimiter = botRateLimiter,
    )
}
