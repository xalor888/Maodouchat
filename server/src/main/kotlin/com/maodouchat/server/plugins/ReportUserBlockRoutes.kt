package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 用户拉黑：拉黑/解除/拉黑列表。 */
internal fun Route.configureReportUserBlockRoutes(
    userRepo: UserRepository,
) {
    authenticate("auth-jwt") {

        post("/api/users/block/{uid}") {
            if (!RuntimeConfigService.isBlockReportEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("block_report_disabled"))
                return@post
            }
            val blockerId = call.requireUserId()
            val blockedId = parseRawOrEmpty(call.parameters, "uid")
            if (!userRepo.blockUser(blockerId, blockedId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法拉黑该用户"))
                return@post
            }
            call.respond(
            buildJsonObject {
put("status", "ok")
            }
        )
        }

        delete("/api/users/block/{uid}") { userRepo.unblockUser(call.requireUserId(), call.requirePathParamOr400("uid", "缺少用户 ID") ?: return@delete); call.respond(
            buildJsonObject {
put("status", "ok")
            }
        ) }

        get("/api/users/blocks") { call.respond(userRepo.getBlockedUsers(call.requireUserId())) }

        get("/api/users/blocks/details") { call.respond(userRepo.getBlockedUserDetails(call.requireUserId())) }
    }
}
