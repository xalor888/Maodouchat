package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.json.*

/** 管理用户：审核员开关（从 AdminOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminModeratorSwitchRoutes(
    userRepo: UserRepository,
) {

put("/users/{id}/moderator") {
    if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val actorId = call.requireUserId()
    val id = call.requirePathParamOr400("id", "missing user id") ?: return@put
    if (AdminAccess.isAdmin(id)) {
        return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot change master admin"))
    }
    val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
    val enabled = parseAdminModeratorEnabled(body)
    if (enabled == null) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled bool required"))
    if (!userRepo.setModerator(actorId, id, enabled)) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
    call.respond(
    buildJsonObject {
put("status", "ok")
put("userId", id)
put("isModerator", enabled)
    }
)
}
}
