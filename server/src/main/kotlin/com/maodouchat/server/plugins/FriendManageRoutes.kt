package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.FriendRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 好友列表与删除好友。 */
internal fun Route.configureFriendManageRoutes(
    friendRepository: FriendRepository,
) {
    authenticate("auth-jwt") {
        get("/api/friends") {
            val userId = call.requireUserId()
            call.respond(friendRepository.listFriends(userId))
        }

        delete("/api/friends/{friendId}") {
            val userId = call.requireUserId()
            val friendId = parseRawOrEmpty(call.parameters, "friendId")
            if (friendId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@delete
            }
            if (friendRepository.removeFriend(userId, friendId)) {
                call.respond(buildJsonObject { put("status", "ok") })
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("好友关系不存在", code = "NOT_FRIENDS"))
            }
        }
    }
}
