package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.StarMessageRepository
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isMessageStarringEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

// 消息星标（从 SocialPostStarPinRoutes 按域拆出）。
internal fun Route.configureSocialPostStarRoutes(
    starMessageRepo: StarMessageRepository,
) {
    authenticate("auth-jwt") {
            post("/api/messages/{messageId}/star") {
                val uid = call.requireUserId()
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("starring_disabled"))
                    return@post
                }
                val mid = call.requirePathParamOr400("messageId", "缺少消息 ID") ?: return@post
                val starred = starMessageRepo.toggleStar(uid, mid)
                if (starred == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能星标"))
                    return@post
                }
                call.respond(buildJsonObject { put("status", "ok"); put("starred", starred) })
            }
            get("/api/messages/starred") {
                val uid = call.requireUserId()
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(emptyList<com.maodouchat.server.model.StarredMessageReference>())
                    return@get
                }
                val chatId = parseRawOrNull(call.request.queryParameters, "chatId")
                call.respond(starMessageRepo.getStarredMessages(uid, chatId))
            }
    }
}
