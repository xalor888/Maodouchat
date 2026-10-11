package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 开发者面板：单 Bot 数据（统计分析/结构化日志，从 DeveloperBotInsightsRoutes 拆出）。 */
internal fun Route.configureDeveloperBotDataRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {

get("/bots/{id}/analytics") {
    val bot = authenticateDeveloperBot(call) ?: return@get
    val targetBotId = parseRawOrEmpty(call.parameters, "id")
    if (targetBotId != bot.id) {
        return@get call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("无权访问该机器人数据")
        )
    }
    val days = parseDeveloperAnalyticsDays(call.request.queryParameters)
    val analytics = developerAnalytics.analytics(targetBotId, days)
    call.respond(analytics)
}

get("/bots/{id}/logs") {
    val bot = authenticateDeveloperBot(call) ?: return@get
    val targetBotId = parseRawOrEmpty(call.parameters, "id")
    if (targetBotId != bot.id) {
        return@get call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("无权访问该机器人日志")
        )
    }
    val limit = parseAdminListLimit(call.request.queryParameters)
    val offset = parseAdminListOffset(call.request.queryParameters)
    val commandFilter = parseDeveloperCommandFilter(call.request.queryParameters)
    val sinceMs = parseOptionalLong(call.request.queryParameters, "since")

    val logs = developerAnalytics.commandLogs(
        botId = targetBotId,
        commandFilter = commandFilter,
        sinceMs = sinceMs,
        limit = limit,
        offset = offset,
    )
    call.respond(logs)
}
}
