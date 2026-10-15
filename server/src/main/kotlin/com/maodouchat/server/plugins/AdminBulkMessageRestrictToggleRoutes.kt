package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserDispositionField
import com.maodouchat.server.service.DispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量禁发消息：开关与清除。 */
internal fun Route.configureAdminBulkMessageRestrictToggleRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
)
{
        post("/users/bulk-message-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.requireUserId()
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = call.requireJsonObjectOr400(body) ?: return@post
                val ids = parseAdminIds(obj)
                val days = parseAdminBulkDays(obj, 0, DispositionService.MAX_MESSAGE_RESTRICT_DAYS)
                if (ids.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                }
                val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
                val result = userDispositionService.bulkExtend(actorId, ids, UserDispositionField.MESSAGE, until, "ADMIN_BULK_MESSAGE_RESTRICT", { e -> "days=$days;until=$e" })
                val updated = result.updated
                val skipped = result.skipped
                call.respond(
                buildJsonObject {
        put("ok", true)
        putJsonElement("updated", updated)
        putJsonElement("skipped", skipped)
        put("until", until)
        put("count", updated.size)
                }
            )
            }

        post("/users/bulk-message-unrestrict") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            }
            val result = userDispositionService.bulkClear(actorId, ids, UserDispositionField.MESSAGE, "ADMIN_BULK_MESSAGE_UNRESTRICT", "cleared")
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

        post("/users/bulk-clear-message-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.requireUserId()
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = call.requireJsonObjectOr400(body) ?: return@post
                val ids = parseAdminIds(obj)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val result = userDispositionService.bulkClear(actorId, ids, UserDispositionField.MESSAGE, "ADMIN_BULK_CLEAR_MSG_RESTRICT", "cleared")
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
