package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.service.DispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量封禁/解封。 */
internal fun Route.configureAdminBulkBanRoutes(
    authTokenRepo: AuthTokenRepository,
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    post("/users/bulk-ban") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        val days = parseAdminBulkDays(obj, 1, DispositionService.MAX_BAN_DAYS)
        val reasonCode = parseAdminBulkReasonCode(obj)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val until = System.currentTimeMillis() + days * 86_400_000L
        val result = userDispositionService.bulkExtend(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.SUSPEND, until, "ADMIN_BULK_BAN", { _ -> "days=$days;reason=$reasonCode" })
        val banned = result.updated
        val skipped = result.skipped
        banned.forEach { id ->
            authTokenRepo.rotateAccessTokenVersion(id)
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk ban") }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("banned", banned)
putJsonElement("skipped", skipped)
put("until", until)
put("count", banned.size)
        }
    )
    }

    post("/users/bulk-unban") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.SUSPEND, "ADMIN_BULK_UNBAN", "cleared suspension")
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
