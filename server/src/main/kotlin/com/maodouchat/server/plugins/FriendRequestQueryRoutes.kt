package com.maodouchat.server.plugins

import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.listIncoming
import com.maodouchat.server.repository.listOutgoing
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 好友请求查询：收到的/发出的（从 FriendRequestRoutes 按域拆出）。
internal fun Route.configureFriendRequestQueryRoutes(
    friendRepository: FriendRepository,
) {
    authenticate("auth-jwt") {
        get("/api/friends/requests/incoming") {
            val userId = call.requireUserId()
            val status = parseFriendRequestStatus(call.request.queryParameters)
            val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
            call.respond(friendRepository.listIncoming(userId, status, limit))
        }

        get("/api/friends/requests/outgoing") {
            val userId = call.requireUserId()
            val status = parseFriendRequestStatus(call.request.queryParameters)
            val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
            call.respond(friendRepository.listOutgoing(userId, status, limit))
        }
    }
}
