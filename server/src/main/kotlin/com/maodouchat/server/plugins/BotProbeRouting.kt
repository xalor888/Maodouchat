package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** Registers compatibility probes from data instead of one route implementation per name. */
internal fun Route.configureBotProbeRoutes(botRateLimiter: BoundedRateLimiter) {
    configureBotProbePingRoutes(botRateLimiter)
    configureBotProbeBooleanRoutes(botRateLimiter)
    configureBotProbeFlagRoutes(botRateLimiter)
}
