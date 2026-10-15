package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserSettingsField
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量资料开关：可搜索。 */
internal fun Route.configureAdminBulkProfileSearchableRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
)
{
        post("/users/bulk-set-searchable-false") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSetUserSettings(actorId, ids, UserSettingsField.SEARCHABLE, false)
            val updated = result.updated
            val skipped = result.skipped
            recordAdminAudit(actorId = actorId, action = "bulk_set_searchable_false", detail = "count=${updated.size}")
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
            }
        )
        }

        post("/users/bulk-set-searchable-true") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSetUserSettings(actorId, ids, UserSettingsField.SEARCHABLE, true)
            val updated = result.updated
            val skipped = result.skipped
            recordAdminAudit(actorId = actorId, action = "bulk_set_searchable_true", detail = "count=${updated.size}")
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
            }
        )
        }

        post("/users/bulk-set-searchable") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val searchable = parseAdminBulkBooleanSetting(obj, "searchable")
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("searchable must be a boolean"))
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSetUserSettings(actorId, ids, UserSettingsField.SEARCHABLE, searchable)
            val updated = result.updated
            val skipped = result.skipped
            recordAdminAudit(actorId = actorId, action = "bulk_set_searchable", detail = "count=${updated.size};searchable=$searchable")
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
    put("searchable", searchable)
            }
        )
        }
}
