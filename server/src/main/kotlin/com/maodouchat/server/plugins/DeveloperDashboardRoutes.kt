package com.maodouchat.server.plugins

import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 开发者面板：Bot 概览看板（从 DeveloperBotInsightsRoutes 按域拆出）。 */
internal fun Route.configureDeveloperDashboardRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {

get("/dashboard") {
    val bot = authenticateDeveloperBot(call) ?: return@get
    val dashboard = developerAnalytics.dashboard(bot.id)
    call.respond(dashboard)
}
}
