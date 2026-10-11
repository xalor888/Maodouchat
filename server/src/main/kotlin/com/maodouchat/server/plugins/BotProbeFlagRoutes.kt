package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

internal fun Route.configureBotProbeFlagRoutes(botRateLimiter: BoundedRateLimiter) {
    configureBotProbeFlagCallAppearanceRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotProbeFlagNotifyRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotProbeFlagFeelMotionRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotProbeFlagSecretLeakRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotProbeFlagSecretPrivacyRoutes(
        botRateLimiter = botRateLimiter,
    )
}

internal data class BotFlagProbe(
    val path: String,
    val command: String,
    val surface: Int,
    val flags: List<BotBooleanFlag>,
)

internal data class BotBooleanFlag(
    val name: String,
    val read: () -> Boolean,
)

internal fun flag(name: String, read: () -> Boolean) = BotBooleanFlag(name, read)
