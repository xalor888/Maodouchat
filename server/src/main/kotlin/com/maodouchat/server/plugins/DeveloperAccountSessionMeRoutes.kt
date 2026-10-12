package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.listByOwner
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 开发者账号·会话：当前用户。 */
internal fun Route.configureDeveloperAccountSessionMeRoutes(
    userRepo: UserRepository,
) {
    get("/me") {
        val userId = devSessionUserId(call)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        val user = userRepo.getById(userId)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户不存在"))
        val bots = BotRepository.listByOwner(userId)
        call.respond(DevMeResponse(userId = user.id, email = user.email, name = user.name, bots = bots))
    }
}
