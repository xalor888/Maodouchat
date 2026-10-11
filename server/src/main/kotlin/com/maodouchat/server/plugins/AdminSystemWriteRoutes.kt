package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 管理后台系统写操作（运营配置更新）。 */
internal fun Route.configureAdminSystemWriteRoutes() {

    put("/settings") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@put
        val updates = parseAdminSettingsUpdates(obj)
        if (updates.isEmpty()) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("no settings"))
        }
        val applied = RuntimeConfigService.setMany(updates, actorId)
        recordAdminAudit(actorId, "ADMIN_SETTINGS_UPDATE", "keys=${updates.keys.joinToString(",")}")
        call.respond(
        buildJsonObject {
put("status", "ok")
putJsonElement("settings", applied)
        }
    )
    }
}
