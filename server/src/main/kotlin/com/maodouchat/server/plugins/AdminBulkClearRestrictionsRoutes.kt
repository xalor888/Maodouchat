package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量清除全部/消息+动态限制。 */
internal fun Route.configureAdminBulkClearRestrictionsRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    post("/users/bulk-clear-all-restrictions") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.ALL, "ADMIN_BULK_CLEAR_ALL_RESTRICTIONS", "cleared msg+post+suspend")
            val updated = result.updated
            val skipped = result.skipped
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
            }
        )
        }

    post("/users/bulk-clear-message-and-post-restrict") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.MESSAGE_AND_POST, "ADMIN_BULK_CLEAR_MSG_AND_POST_RESTRICT", "cleared")
            val updated = result.updated
            val skipped = result.skipped
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
            }
        )
        }
}
