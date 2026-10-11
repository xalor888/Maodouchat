package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put

/** 管理后台诊断写操作（Bot 启停）。 */
internal fun Route.configureAdminDiagnosticsWriteRoutes() {

    put("/bots/{botId}/enabled") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val botId = call.requirePathParamOr400("botId", "missing botId") ?: return@put
        val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val enabled = parseAdminModeratorEnabled(bodyText)
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))

        val updated = BotRepository.setAdminEnabled(botId, enabled)
            ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
        recordAdminAudit(adminId, "bot_enabled", "bot=$botId;enabled=$enabled")
        call.respond(updated)
    }
}
