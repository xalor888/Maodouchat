package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 账号资料只读：me/公开资料/他人资料。 */
internal fun Route.configureAccountProfileReadRoutes(
    userRepo: UserRepository,
) {
    authenticate("auth-jwt") {

        get("/api/users/me") {
            val userId = call.requireUserId()
            val user = userRepo.getById(userId)
            if (user != null) call.respond(user)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        }

        get("/api/users/{id}") {
            val viewerId = call.optionalUserId()
            val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@get
            val user = userRepo.getPublicById(id, viewerId = viewerId)
            if (user != null) {
                call.respond(
                    user.copy(isOnline = user.isOnline && userRepo.shouldShowOnlineTo(user.id, viewerId))
                )
            } else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        }

        get("/api/users/me/public") {
            val userId = call.requireUserId()
            val user = userRepo.getPublicMe(userId)
            if (user != null) {
                val publicProfileUrl = user.username?.let { "${ServerConfig.baseUrl.trimEnd('/')}/u/${it}" }
                call.respond(
            buildJsonObject {
putJsonElement("user", user)
put("publicProfileUrl", publicProfileUrl)
            }
        )
            } else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        }
    }
}
