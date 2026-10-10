package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AuthTokenRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量停权（按天/按截止时间/清除）。 */
internal fun Route.configureAdminBulkSuspendRoutes(
    authTokenRepo: AuthTokenRepository,
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    post("/users/bulk-suspend-days") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        val days = parseAdminBulkDays(obj, 1, 365)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val until = System.currentTimeMillis() + days * 86_400_000L
        val result = userDispositionService.bulkExtend(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.SUSPEND, until, "ADMIN_BULK_SUSPEND_DAYS", { e -> "days=$days;until=$e" })
        val updated = result.updated
        val skipped = result.skipped
        updated.forEach { id ->
            authTokenRepo.rotateAccessTokenVersion(id)
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk suspend days") }
        }
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

    post("/users/bulk-set-suspend-until") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val until = parseAdminBulkTimestampMs(obj, "suspendedUntil", "until")
            val now = System.currentTimeMillis()
            if (until < 0 || until > now + MAX_ADMIN_SUSPEND_MS || (until in 1..now)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("suspendedUntil invalid"))
            }
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSet(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.SUSPEND, until, "ADMIN_BULK_SET_SUSPEND_UNTIL", { _ -> "until=$until" })
            val updated = result.updated
            val skipped = result.skipped
            if (until > now) {
                updated.forEach { id ->
                    authTokenRepo.rotateAccessTokenVersion(id)
                    bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk suspend until") }
                }
            }
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
    put("suspendedUntil", until)
            }
        )
        }

    post("/users/bulk-clear-suspend") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.SUSPEND, "ADMIN_BULK_CLEAR_SUSPEND", "cleared")
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
