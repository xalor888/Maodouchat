package com.maodouchat.server.plugins

import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import com.maodouchat.server.repository.health
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 开发者面板：综合健康检查。 */
internal fun Route.configureDeveloperPortalHealthRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {
// ─── Comprehensive health check ───────
get("/health") {
    val bot = authenticateDeveloperBot(call) ?: return@get
    val health = developerAnalytics.health(bot.id)
    call.respond(health)
}
}
