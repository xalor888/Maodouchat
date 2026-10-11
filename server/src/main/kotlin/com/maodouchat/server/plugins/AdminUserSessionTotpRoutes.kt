package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

/** 管理后台：单用户 TOTP 关闭（从 AdminUserSessionRoutes 按域拆出）。 */
internal fun Route.configureAdminUserSessionTotpRoutes(
    userRepo: UserRepository,
) {

post("/users/{id}/disable-totp") {
    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val actorId = call.requireUserId()
    val id = call.requirePathParamOr400("id", "missing user id") ?: return@post
    if (AdminAccess.isAdmin(id) && id != actorId) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot disable TOTP for another master admin"))
    }
    if (!userRepo.disableTotp(actorId, id)) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
    call.respond(
    buildJsonObject {
put("status", "ok")
put("userId", id)
put("totpEnabled", false)
    }
)
}
}
