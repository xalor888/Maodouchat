package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.FriendRequestResponse
import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

// 好友请求决策：接受/拒绝/取消（从 FriendRequestRoutes 按域拆出）。
internal fun Route.configureFriendRequestDecisionRoutes(
    userRepository: UserRepository,
    friendRepository: FriendRepository,
    pushService: FcmPushService,
    json: Json,
) {
    authenticate("auth-jwt") {
        post("/api/friends/requests/{id}/accept") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            call.respondToMutation(friendRepository.acceptRequest(userId, parseRawOrEmpty(call.parameters, "id"))) {
                notifyFriendRequest(userRepository, pushService, json, it, "ACCEPTED")
            }
        }

        post("/api/friends/requests/{id}/reject") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            call.respondToMutation(friendRepository.rejectRequest(userId, parseRawOrEmpty(call.parameters, "id"))) {
                notifyFriendRequest(userRepository, pushService, json, it, "REJECTED")
            }
        }

        post("/api/friends/requests/{id}/cancel") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            call.respondToMutation(friendRepository.cancelRequest(userId, parseRawOrEmpty(call.parameters, "id"))) {
                notifyFriendRequest(userRepository, pushService, json, it, "CANCELLED")
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondToMutation(
    result: FriendRepository.Result,
    onSuccess: suspend (FriendRequestResponse) -> Unit,
) {
    when (result) {
        is FriendRepository.Result.Success -> {
            onSuccess(result.request)
            respond(result.request)
        }
        is FriendRepository.Result.Failure -> {
            val status = when (result.code) {
                "NOT_FOUND" -> HttpStatusCode.NotFound
                "FORBIDDEN" -> HttpStatusCode.Forbidden
                else -> HttpStatusCode.BadRequest
            }
            respond(status, ErrorResponse(result.message, code = result.code))
        }
    }
}

