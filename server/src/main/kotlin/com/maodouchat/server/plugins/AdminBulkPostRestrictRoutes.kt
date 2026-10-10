package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.DispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量禁发动态（按天/清除）。 */
internal fun Route.configureAdminBulkPostRestrictRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    post("/users/bulk-post-restrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        val days = parseAdminBulkDays(obj, 0, DispositionService.MAX_POST_RESTRICT_DAYS)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
        val result = userDispositionService.bulkExtend(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.POST, until, "ADMIN_BULK_POST_RESTRICT", { e -> "days=$days;until=$e" })
        val updated = result.updated
        val skipped = result.skipped
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("updated", updated)
putJsonElement("skipped", skipped)
put("until", until)
put("days", days)
put("count", updated.size)
        }
    )
    }

    post("/users/bulk-post-unrestrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.POST, "ADMIN_BULK_POST_UNRESTRICT", "cleared")
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

    post("/users/bulk-clear-post-restrict") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.POST, "ADMIN_BULK_CLEAR_POST_RESTRICT", "cleared")
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
