package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量资料开关：状态与在线展示。 */
internal fun Route.configureAdminBulkProfilePresenceRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
)
{
        post("/users/bulk-set-show-status") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val showStatus = parseAdminBulkBooleanSetting(obj, "showStatus")
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("showStatus must be a boolean"))
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSetUserSettings(actorId, ids, com.maodouchat.server.repository.UserRepository.UserSettingsField.SHOW_STATUS, showStatus)
            val updated = result.updated
            val skipped = result.skipped
            recordAdminAudit(actorId = actorId, action = "bulk_set_show_status", detail = "count=${updated.size};showStatus=$showStatus")
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
    put("showStatus", showStatus)
            }
        )
        }

        post("/users/bulk-set-show-online") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val showOnline = parseAdminBulkBooleanSetting(obj, "showOnline")
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("showOnline must be a boolean"))
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSetUserSettings(actorId, ids, com.maodouchat.server.repository.UserRepository.UserSettingsField.SHOW_ONLINE, showOnline)
            val updated = result.updated
            val skipped = result.skipped
            recordAdminAudit(actorId = actorId, action = "bulk_set_show_online", detail = "count=${updated.size};showOnline=$showOnline")
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
    put("showOnline", showOnline)
            }
        )
        }
}
