package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.*

/** Bot 管理：命令日志查询（从 AdminOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminBotCommandLogRoutes(
) {

get("/bots/{id}/command-logs") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val id = call.requirePathParamOr400("id", "missing bot id") ?: return@get
    val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 500)
    val offset = parseAdminListIntOffset(call.request.queryParameters)
    val bot = com.maodouchat.server.repository.BotRepository.get(id)
        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
    val logs = com.maodouchat.server.repository.BotRepository.listCommandLogs(id, limit, offset)
    call.respond(
    buildJsonObject {
put("botId", bot.id)
put("username", bot.username)
putJsonElement("logs", logs)
put("count", logs.size)
    }
)
}
}
